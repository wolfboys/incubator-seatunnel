/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.cdc.sqlserver.source.source.reader.fetch;

import org.apache.seatunnel.api.table.type.SeaTunnelRowType;
import org.apache.seatunnel.connectors.cdc.base.config.JdbcSourceConfig;
import org.apache.seatunnel.connectors.cdc.base.dialect.JdbcDataSourceDialect;
import org.apache.seatunnel.connectors.cdc.base.relational.JdbcSourceEventDispatcher;
import org.apache.seatunnel.connectors.cdc.base.source.offset.Offset;
import org.apache.seatunnel.connectors.cdc.base.source.reader.external.JdbcSourceFetchTaskContext;
import org.apache.seatunnel.connectors.cdc.base.source.split.IncrementalSplit;
import org.apache.seatunnel.connectors.cdc.base.source.split.SnapshotSplit;
import org.apache.seatunnel.connectors.cdc.base.source.split.SourceSplitBase;
import org.apache.seatunnel.connectors.cdc.debezium.EmbeddedDatabaseHistory;
import org.apache.seatunnel.connectors.seatunnel.cdc.sqlserver.source.config.SqlServerSourceConfig;
import org.apache.seatunnel.connectors.seatunnel.cdc.sqlserver.source.source.offset.LsnOffset;
import org.apache.seatunnel.connectors.seatunnel.cdc.sqlserver.source.utils.SqlServerUtils;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.sqlserver.SourceInfo;
import io.debezium.connector.sqlserver.SqlServerConnection;
import io.debezium.connector.sqlserver.SqlServerConnectorConfig;
import io.debezium.connector.sqlserver.SqlServerDatabaseSchema;
import io.debezium.connector.sqlserver.SqlServerErrorHandler;
import io.debezium.connector.sqlserver.SqlServerOffsetContext;
import io.debezium.connector.sqlserver.SqlServerTaskContext;
import io.debezium.connector.sqlserver.SqlServerTopicSelector;
import io.debezium.connector.sqlserver.SqlServerValueConverters;
import io.debezium.data.Envelope;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.metrics.DefaultChangeEventSourceMetricsFactory;
import io.debezium.pipeline.metrics.SnapshotChangeEventSourceMetrics;
import io.debezium.pipeline.source.spi.EventMetadataProvider;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.relational.Tables;
import io.debezium.relational.history.TableChanges;
import io.debezium.schema.DataCollectionId;
import io.debezium.schema.TopicSelector;
import io.debezium.util.Collect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The context for fetch task that fetching data of snapshot split from MySQL data source. */
public class SqlServerSourceFetchTaskContext extends JdbcSourceFetchTaskContext {

    private final SqlServerConnection dataConnection;

    private final SqlServerConnection metadataConnection;

    private final SqlServerEventMetadataProvider metadataProvider;
    private SqlServerDatabaseSchema databaseSchema;
    private SqlServerOffsetContext offsetContext;
    private TopicSelector<TableId> topicSelector;
    private JdbcSourceEventDispatcher dispatcher;
    private ChangeEventQueue<DataChangeEvent> queue;
    private SqlServerErrorHandler errorHandler;
    private SqlServerTaskContext taskContext;

    private SnapshotChangeEventSourceMetrics snapshotChangeEventSourceMetrics;

    public SqlServerSourceFetchTaskContext(
            JdbcSourceConfig sourceConfig,
            JdbcDataSourceDialect dataSourceDialect,
            SqlServerConnection dataConnection,
            SqlServerConnection metadataConnection) {
        super(sourceConfig, dataSourceDialect);
        this.dataConnection = dataConnection;
        this.metadataConnection = metadataConnection;
        this.metadataProvider = new SqlServerEventMetadataProvider();
    }

    @Override
    public void configure(SourceSplitBase sourceSplitBase) {
        registerDatabaseHistory(sourceSplitBase);

        // initial stateful objects
        final SqlServerConnectorConfig connectorConfig = getDbzConnectorConfig();

        final SqlServerValueConverters valueConverters =
                new SqlServerValueConverters(
                        connectorConfig.getDecimalMode(),
                        connectorConfig.getTemporalPrecisionMode(),
                        connectorConfig.binaryHandlingMode());

        this.topicSelector = SqlServerTopicSelector.defaultSelector(connectorConfig);

        this.databaseSchema =
                new SqlServerDatabaseSchema(
                        connectorConfig, valueConverters, topicSelector, schemaNameAdjuster);

        this.offsetContext =
                loadStartingOffsetState(
                        new SqlServerOffsetContext.Loader(connectorConfig), sourceSplitBase);
        validateAndLoadDatabaseHistory(offsetContext, databaseSchema);

        this.taskContext = new SqlServerTaskContext(connectorConfig, databaseSchema);

        final int queueSize =
                sourceSplitBase.isSnapshotSplit()
                        ? Integer.MAX_VALUE
                        : getSourceConfig().getDbzConnectorConfig().getMaxQueueSize();

        this.queue =
                new ChangeEventQueue.Builder<DataChangeEvent>()
                        .pollInterval(connectorConfig.getPollInterval())
                        .maxBatchSize(connectorConfig.getMaxBatchSize())
                        .maxQueueSize(queueSize)
                        .maxQueueSizeInBytes(connectorConfig.getMaxQueueSizeInBytes())
                        .loggingContextSupplier(
                                () ->
                                        taskContext.configureLoggingContext(
                                                "sqlServer-cdc-connector-task"))
                        // do not buffer any element, we use signal event
                        // .buffering()
                        .build();
        this.dispatcher =
                new JdbcSourceEventDispatcher(
                        connectorConfig,
                        topicSelector,
                        databaseSchema,
                        queue,
                        connectorConfig.getTableFilters().dataCollectionFilter(),
                        DataChangeEvent::new,
                        metadataProvider,
                        schemaNameAdjuster);

        final DefaultChangeEventSourceMetricsFactory changeEventSourceMetricsFactory =
                new DefaultChangeEventSourceMetricsFactory();

        this.snapshotChangeEventSourceMetrics =
                changeEventSourceMetricsFactory.getSnapshotMetrics(
                        taskContext, queue, metadataProvider);

        this.errorHandler = new SqlServerErrorHandler(connectorConfig.getLogicalName(), queue);
    }

    @Override
    public SqlServerSourceConfig getSourceConfig() {
        return (SqlServerSourceConfig) sourceConfig;
    }

    public SqlServerConnection getDataConnection() {
        return dataConnection;
    }

    public SqlServerConnection getMetadataConnection() {
        return metadataConnection;
    }

    public SnapshotChangeEventSourceMetrics getSnapshotChangeEventSourceMetrics() {
        return snapshotChangeEventSourceMetrics;
    }

    @Override
    public SqlServerConnectorConfig getDbzConnectorConfig() {
        return (SqlServerConnectorConfig) super.getDbzConnectorConfig();
    }

    @Override
    public SqlServerOffsetContext getOffsetContext() {
        return offsetContext;
    }

    @Override
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    @Override
    public SqlServerDatabaseSchema getDatabaseSchema() {
        return databaseSchema;
    }

    @Override
    public SeaTunnelRowType getSplitType(Table table) {
        return SqlServerUtils.getSplitType(table);
    }

    @Override
    public JdbcSourceEventDispatcher getDispatcher() {
        return dispatcher;
    }

    @Override
    public ChangeEventQueue<DataChangeEvent> getQueue() {
        return queue;
    }

    @Override
    public Tables.TableFilter getTableFilter() {
        return getDbzConnectorConfig().getTableFilters().dataCollectionFilter();
    }

    @Override
    public Offset getStreamOffset(SourceRecord sourceRecord) {
        return SqlServerUtils.getLsn(sourceRecord);
    }

    private void validateAndLoadDatabaseHistory(
            SqlServerOffsetContext offset, SqlServerDatabaseSchema schema) {
        schema.initializeStorage();
        schema.recover(offset);
    }

    /** Loads the connector's persistent offset (if present) via the given loader. */
    private SqlServerOffsetContext loadStartingOffsetState(
            SqlServerOffsetContext.Loader loader, SourceSplitBase split) {
        Offset offset =
                split.isSnapshotSplit()
                        ? LsnOffset.INITIAL_OFFSET
                        : split.asIncrementalSplit().getStartupOffset();

        SqlServerOffsetContext sqlServerOffsetContext = loader.load(offset.getOffset());

        return sqlServerOffsetContext;
    }

    private void registerDatabaseHistory(SourceSplitBase sourceSplitBase) {
        List<TableChanges.TableChange> engineHistory = new ArrayList<>();
        // TODO: support save table schema
        if (sourceSplitBase instanceof SnapshotSplit) {
            SnapshotSplit snapshotSplit = (SnapshotSplit) sourceSplitBase;
            engineHistory.add(
                    dataSourceDialect.queryTableSchema(dataConnection, snapshotSplit.getTableId()));
        } else {
            IncrementalSplit incrementalSplit = (IncrementalSplit) sourceSplitBase;
            for (TableId tableId : incrementalSplit.getTableIds()) {
                engineHistory.add(dataSourceDialect.queryTableSchema(dataConnection, tableId));
            }
        }

        EmbeddedDatabaseHistory.registerHistory(
                sourceConfig
                        .getDbzConfiguration()
                        .getString(EmbeddedDatabaseHistory.DATABASE_HISTORY_INSTANCE_NAME),
                engineHistory);
    }

    public static class SqlServerEventMetadataProvider implements EventMetadataProvider {

        @Override
        public Instant getEventTimestamp(
                DataCollectionId source, OffsetContext offset, Object key, Struct value) {
            if (value == null) {
                return null;
            }
            final Struct sourceInfo = value.getStruct(Envelope.FieldName.SOURCE);
            if (source == null) {
                return null;
            }
            final Long timestamp = sourceInfo.getInt64(SourceInfo.TIMESTAMP_KEY);
            return timestamp == null ? null : Instant.ofEpochMilli(timestamp);
        }

        @Override
        public Map<String, String> getEventSourcePosition(
                DataCollectionId source, OffsetContext offset, Object key, Struct value) {
            if (value == null) {
                return null;
            }
            final Struct sourceInfo = value.getStruct(Envelope.FieldName.SOURCE);
            if (source == null) {
                return null;
            }
            return Collect.hashMapOf(
                    SourceInfo.COMMIT_LSN_KEY, sourceInfo.getString(SourceInfo.COMMIT_LSN_KEY),
                    SourceInfo.CHANGE_LSN_KEY, sourceInfo.getString(SourceInfo.CHANGE_LSN_KEY));
        }

        @Override
        public String getTransactionId(
                DataCollectionId source, OffsetContext offset, Object key, Struct value) {
            if (value == null) {
                return null;
            }
            final Struct sourceInfo = value.getStruct(Envelope.FieldName.SOURCE);
            if (source == null) {
                return null;
            }
            return sourceInfo.getString(SourceInfo.COMMIT_LSN_KEY);
        }
    }
}
