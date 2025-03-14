/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.jdbc.table;

import org.apache.inlong.sort.base.dirty.DirtyOptions;
import org.apache.inlong.sort.base.dirty.sink.DirtySink;
import org.apache.inlong.sort.base.dirty.utils.DirtySinkFactoryUtils;
import org.apache.inlong.sort.base.format.DynamicSchemaFormatFactory;
import org.apache.inlong.sort.base.sink.SchemaUpdateExceptionPolicy;
import org.apache.inlong.sort.base.util.JdbcUrlUtils;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.dialect.JdbcDialect;
import org.apache.flink.connector.jdbc.internal.options.JdbcDmlOptions;
import org.apache.flink.connector.jdbc.internal.options.JdbcLookupOptions;
import org.apache.flink.connector.jdbc.internal.options.JdbcOptions;
import org.apache.flink.connector.jdbc.internal.options.JdbcReadOptions;
import org.apache.flink.connector.jdbc.table.JdbcDynamicTableSource;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.factories.DynamicTableSinkFactory;
import org.apache.flink.table.factories.DynamicTableSourceFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.utils.TableSchemaUtils;
import org.apache.flink.util.Preconditions;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.apache.flink.util.Preconditions.checkState;
import static org.apache.inlong.sort.base.Constants.AUDIT_KEYS;
import static org.apache.inlong.sort.base.Constants.DIRTY_PREFIX;
import static org.apache.inlong.sort.base.Constants.INLONG_AUDIT;
import static org.apache.inlong.sort.base.Constants.INLONG_METRIC;
import static org.apache.inlong.sort.base.Constants.SINK_MULTIPLE_DATABASE_PATTERN;
import static org.apache.inlong.sort.base.Constants.SINK_MULTIPLE_ENABLE;
import static org.apache.inlong.sort.base.Constants.SINK_MULTIPLE_FORMAT;
import static org.apache.inlong.sort.base.Constants.SINK_MULTIPLE_SCHEMA_UPDATE_POLICY;
import static org.apache.inlong.sort.base.Constants.SINK_MULTIPLE_TABLE_PATTERN;

/**
 * Copy from org.apache.flink:flink-connector-jdbc_2.11:1.13.5
 * <p>
 * Factory for creating configured instances of {@link JdbcDynamicTableSource} and {@link
 * JdbcDynamicTableSink}.
 * We modify it to strengthen capacity of registering other dialect.
 * Add an option `sink.ignore.changelog` to support insert-only mode without primaryKey.
 * </p>
 */
public class JdbcDynamicTableFactory implements DynamicTableSourceFactory, DynamicTableSinkFactory {

    public static final String IDENTIFIER = "jdbc-inlong";

    public static final ConfigOption<String> DIALECT_IMPL =
            ConfigOptions.key("dialect-impl")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("The JDBC Custom Dialect.");
    public static final ConfigOption<String> URL =
            ConfigOptions.key("url")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("The JDBC database URL.");
    public static final ConfigOption<String> TABLE_NAME =
            ConfigOptions.key("table-name")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("The JDBC table name.");
    public static final ConfigOption<String> USERNAME =
            ConfigOptions.key("username")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("The JDBC user name.");
    public static final ConfigOption<String> PASSWORD =
            ConfigOptions.key("password")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("The JDBC password.");
    public static final ConfigOption<Duration> MAX_RETRY_TIMEOUT =
            ConfigOptions.key("connection.max-retry-timeout")
                    .durationType()
                    .defaultValue(Duration.ofSeconds(60))
                    .withDescription("Maximum timeout between retries.");
    private static final ConfigOption<String> DRIVER =
            ConfigOptions.key("driver")
                    .stringType()
                    .noDefaultValue()
                    .withDescription(
                            "The class name of the JDBC driver to use to connect to this URL. "
                                    + "If not set, it will automatically be derived from the URL.");
    // read config options
    private static final ConfigOption<String> SCAN_PARTITION_COLUMN =
            ConfigOptions.key("scan.partition.column")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("The column name used for partitioning the input.");
    private static final ConfigOption<Integer> SCAN_PARTITION_NUM =
            ConfigOptions.key("scan.partition.num")
                    .intType()
                    .noDefaultValue()
                    .withDescription("The number of partitions.");
    private static final ConfigOption<Long> SCAN_PARTITION_LOWER_BOUND =
            ConfigOptions.key("scan.partition.lower-bound")
                    .longType()
                    .noDefaultValue()
                    .withDescription("The smallest value of the first partition.");
    private static final ConfigOption<Long> SCAN_PARTITION_UPPER_BOUND =
            ConfigOptions.key("scan.partition.upper-bound")
                    .longType()
                    .noDefaultValue()
                    .withDescription("The largest value of the last partition.");
    private static final ConfigOption<Integer> SCAN_FETCH_SIZE =
            ConfigOptions.key("scan.fetch-size")
                    .intType()
                    .defaultValue(0)
                    .withDescription(
                            "Gives the reader a hint as to the number of rows that should be fetched "
                                    + "from the database per round-trip when reading. "
                                    + "If the value is zero, this hint is ignored.");
    private static final ConfigOption<Boolean> SCAN_AUTO_COMMIT =
            ConfigOptions.key("scan.auto-commit")
                    .booleanType()
                    .defaultValue(true)
                    .withDescription("Sets whether the driver is in auto-commit mode.");

    // look up config options
    private static final ConfigOption<Long> LOOKUP_CACHE_MAX_ROWS =
            ConfigOptions.key("lookup.cache.max-rows")
                    .longType()
                    .defaultValue(-1L)
                    .withDescription(
                            "The max number of rows of lookup cache, over this value, the oldest rows will "
                                    + "be eliminated. \"cache.max-rows\" and \"cache.ttl\""
                                    + " options must all be specified if any of them is specified.");
    private static final ConfigOption<Duration> LOOKUP_CACHE_TTL =
            ConfigOptions.key("lookup.cache.ttl")
                    .durationType()
                    .defaultValue(Duration.ofSeconds(10))
                    .withDescription("The cache time to live.");
    private static final ConfigOption<Integer> LOOKUP_MAX_RETRIES =
            ConfigOptions.key("lookup.max-retries")
                    .intType()
                    .defaultValue(3)
                    .withDescription("The max retry times if lookup database failed.");

    // write config options
    private static final ConfigOption<Integer> SINK_BUFFER_FLUSH_MAX_ROWS =
            ConfigOptions.key("sink.buffer-flush.max-rows")
                    .intType()
                    .defaultValue(100)
                    .withDescription(
                            "The flush max size (includes all append, upsert and delete records), over this number"
                                    + " of records, will flush data.");
    private static final ConfigOption<Duration> SINK_BUFFER_FLUSH_INTERVAL =
            ConfigOptions.key("sink.buffer-flush.interval")
                    .durationType()
                    .defaultValue(Duration.ofSeconds(1))
                    .withDescription(
                            "The flush interval mills, over this time, asynchronous threads will flush data.");
    private static final ConfigOption<Integer> SINK_MAX_RETRIES =
            ConfigOptions.key("sink.max-retries")
                    .intType()
                    .defaultValue(3)
                    .withDescription("The max retry times if writing records to database failed.");
    private static final ConfigOption<Boolean> SINK_APPEND_MODE =
            ConfigOptions.key("sink.ignore.changelog")
                    .booleanType()
                    .defaultValue(false)
                    .withDescription("Whether to support sink update/delete data without primaryKey.");

    public static final ConfigOption<String> SINK_MULTIPLE_SCHEMA_PATTERN =
            ConfigOptions.key("sink.multiple.schema-pattern")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("The option 'sink.multiple.schema-pattern' "
                            + "is used extract table name from the raw binary data, "
                            + "this is only used in the multiple sink writing scenario.");

    @Override
    public DynamicTableSink createDynamicTableSink(Context context) {
        final FactoryUtil.TableFactoryHelper helper =
                FactoryUtil.createTableFactoryHelper(this, context);
        final ReadableConfig config = helper.getOptions();

        helper.validateExcept(DIRTY_PREFIX);
        validateConfigOptions(config);
        boolean multipleSink = config.getOptional(SINK_MULTIPLE_ENABLE).orElse(false);
        String sinkMultipleFormat = helper.getOptions().getOptional(SINK_MULTIPLE_FORMAT).orElse(null);
        String databasePattern = helper.getOptions().getOptional(SINK_MULTIPLE_DATABASE_PATTERN).orElse(null);
        String tablePattern = helper.getOptions().getOptional(SINK_MULTIPLE_TABLE_PATTERN).orElse(null);
        String schemaPattern = helper.getOptions().getOptional(SINK_MULTIPLE_SCHEMA_PATTERN).orElse(databasePattern);
        validateSinkMultiple(multipleSink, sinkMultipleFormat, databasePattern, schemaPattern, tablePattern);
        JdbcOptions jdbcOptions = getJdbcOptions(config);
        TableSchema physicalSchema =
                TableSchemaUtils.getPhysicalSchema(context.getCatalogTable().getSchema());
        boolean appendMode = config.get(SINK_APPEND_MODE);
        String inlongMetric = config.getOptional(INLONG_METRIC).orElse(null);
        String auditHostAndPorts = config.getOptional(INLONG_AUDIT).orElse(null);
        SchemaUpdateExceptionPolicy schemaUpdateExceptionPolicy =
                helper.getOptions().getOptional(SINK_MULTIPLE_SCHEMA_UPDATE_POLICY).orElse(null);
        // Build the dirty data side-output
        final DirtyOptions dirtyOptions = DirtyOptions.fromConfig(helper.getOptions());
        final DirtySink<Object> dirtySink = DirtySinkFactoryUtils.createDirtySink(context, dirtyOptions);
        return new JdbcDynamicTableSink(
                jdbcOptions,
                getJdbcExecutionOptions(config),
                getJdbcDmlOptions(jdbcOptions, physicalSchema),
                physicalSchema,
                appendMode,
                multipleSink,
                sinkMultipleFormat,
                databasePattern,
                tablePattern,
                schemaPattern,
                inlongMetric,
                auditHostAndPorts,
                schemaUpdateExceptionPolicy,
                dirtyOptions,
                dirtySink);
    }

    @Override
    public DynamicTableSource createDynamicTableSource(Context context) {
        final FactoryUtil.TableFactoryHelper helper =
                FactoryUtil.createTableFactoryHelper(this, context);
        final ReadableConfig config = helper.getOptions();

        helper.validate();
        validateConfigOptions(config);
        TableSchema physicalSchema =
                TableSchemaUtils.getPhysicalSchema(context.getCatalogTable().getSchema());
        return new JdbcDynamicTableSource(
                getJdbcOptions(helper.getOptions()),
                getJdbcReadOptions(helper.getOptions()),
                getJdbcLookupOptions(helper.getOptions()),
                physicalSchema);
    }

    private void validateSinkMultiple(boolean multipleSink, String sinkMultipleFormat,
            String databasePattern, String schemaPattern, String tablePattern) {
        Preconditions.checkNotNull(multipleSink, "The option 'sink.multiple.enable' is not allowed null");
        if (multipleSink) {
            Preconditions.checkNotNull(databasePattern, "The option 'sink.multiple.database-pattern'"
                    + " is not allowed blank when the option 'sink.multiple.enable' is 'true'");
            Preconditions.checkNotNull(schemaPattern, "The option 'sink.multiple.schema-pattern'"
                    + " is not allowed blank when the option 'sink.multiple.enable' is 'true'");
            Preconditions.checkNotNull(tablePattern, "The option 'sink.multiple.table-pattern' "
                    + "is not allowed blank when the option 'sink.multiple.enable' is 'true'");
            Preconditions.checkNotNull(sinkMultipleFormat, "The option 'sink.multiple.format' "
                    + "is not allowed blank when the option 'sink.multiple.enable' is 'true'");
            DynamicSchemaFormatFactory.getFormat(sinkMultipleFormat);
            Set<String> supportFormats = DynamicSchemaFormatFactory.SUPPORT_FORMATS.keySet();
            Preconditions.checkArgument(supportFormats.contains(sinkMultipleFormat), String.format(
                    "Unsupported value '%s' for '%s'. Supported values are %s.",
                    sinkMultipleFormat, SINK_MULTIPLE_FORMAT.key(), supportFormats));
        }
    }

    private JdbcOptions getJdbcOptions(ReadableConfig readableConfig) {
        String url = JdbcUrlUtils.replaceInvalidUrlProperty(readableConfig.get(URL));
        Optional<String> dialectImplOptional = readableConfig.getOptional(DIALECT_IMPL);
        Optional<JdbcDialect> jdbcDialect;
        if (dialectImplOptional.isPresent()) {
            jdbcDialect = JdbcDialects.getCustomDialect(dialectImplOptional.get());
        } else {
            jdbcDialect = JdbcDialects.get(url);
        }
        final JdbcOptions.Builder builder =
                JdbcOptions.builder()
                        .setDBUrl(url)
                        .setTableName(readableConfig.get(TABLE_NAME))
                        .setDialect(jdbcDialect.get())
                        .setParallelism(
                                readableConfig
                                        .getOptional(FactoryUtil.SINK_PARALLELISM)
                                        .orElse(null))
                        .setConnectionCheckTimeoutSeconds(
                                (int) readableConfig.get(MAX_RETRY_TIMEOUT).getSeconds());

        readableConfig.getOptional(DRIVER).ifPresent(builder::setDriverName);
        readableConfig.getOptional(USERNAME).ifPresent(builder::setUsername);
        readableConfig.getOptional(PASSWORD).ifPresent(builder::setPassword);
        return builder.build();
    }

    private JdbcReadOptions getJdbcReadOptions(ReadableConfig readableConfig) {
        final Optional<String> partitionColumnName =
                readableConfig.getOptional(SCAN_PARTITION_COLUMN);
        final JdbcReadOptions.Builder builder = JdbcReadOptions.builder();
        if (partitionColumnName.isPresent()) {
            builder.setPartitionColumnName(partitionColumnName.get());
            builder.setPartitionLowerBound(readableConfig.get(SCAN_PARTITION_LOWER_BOUND));
            builder.setPartitionUpperBound(readableConfig.get(SCAN_PARTITION_UPPER_BOUND));
            builder.setNumPartitions(readableConfig.get(SCAN_PARTITION_NUM));
        }
        readableConfig.getOptional(SCAN_FETCH_SIZE).ifPresent(builder::setFetchSize);
        builder.setAutoCommit(readableConfig.get(SCAN_AUTO_COMMIT));
        return builder.build();
    }

    private JdbcLookupOptions getJdbcLookupOptions(ReadableConfig readableConfig) {
        return new JdbcLookupOptions(
                readableConfig.get(LOOKUP_CACHE_MAX_ROWS),
                readableConfig.get(LOOKUP_CACHE_TTL).toMillis(),
                readableConfig.get(LOOKUP_MAX_RETRIES));
    }

    private JdbcExecutionOptions getJdbcExecutionOptions(ReadableConfig config) {
        final JdbcExecutionOptions.Builder builder = new JdbcExecutionOptions.Builder();
        builder.withBatchSize(config.get(SINK_BUFFER_FLUSH_MAX_ROWS));
        builder.withBatchIntervalMs(config.get(SINK_BUFFER_FLUSH_INTERVAL).toMillis());
        builder.withMaxRetries(config.get(SINK_MAX_RETRIES));
        return builder.build();
    }

    private JdbcDmlOptions getJdbcDmlOptions(JdbcOptions jdbcOptions, TableSchema schema) {
        String[] keyFields =
                schema.getPrimaryKey()
                        .map(pk -> pk.getColumns().toArray(new String[0]))
                        .orElse(null);

        return JdbcDmlOptions.builder()
                .withTableName(jdbcOptions.getTableName())
                .withDialect(jdbcOptions.getDialect())
                .withFieldNames(schema.getFieldNames())
                .withKeyFields(keyFields)
                .build();
    }

    @Override
    public String factoryIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        Set<ConfigOption<?>> requiredOptions = new HashSet<>();
        requiredOptions.add(URL);
        requiredOptions.add(TABLE_NAME);
        return requiredOptions;
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        Set<ConfigOption<?>> optionalOptions = new HashSet<>();
        optionalOptions.add(DRIVER);
        optionalOptions.add(USERNAME);
        optionalOptions.add(PASSWORD);
        optionalOptions.add(SCAN_PARTITION_COLUMN);
        optionalOptions.add(SCAN_PARTITION_LOWER_BOUND);
        optionalOptions.add(SCAN_PARTITION_UPPER_BOUND);
        optionalOptions.add(SCAN_PARTITION_NUM);
        optionalOptions.add(SCAN_FETCH_SIZE);
        optionalOptions.add(SCAN_AUTO_COMMIT);
        optionalOptions.add(LOOKUP_CACHE_MAX_ROWS);
        optionalOptions.add(LOOKUP_CACHE_TTL);
        optionalOptions.add(LOOKUP_MAX_RETRIES);
        optionalOptions.add(SINK_BUFFER_FLUSH_MAX_ROWS);
        optionalOptions.add(SINK_BUFFER_FLUSH_INTERVAL);
        optionalOptions.add(SINK_MAX_RETRIES);
        optionalOptions.add(SINK_APPEND_MODE);
        optionalOptions.add(FactoryUtil.SINK_PARALLELISM);
        optionalOptions.add(MAX_RETRY_TIMEOUT);
        optionalOptions.add(DIALECT_IMPL);
        optionalOptions.add(SINK_MULTIPLE_ENABLE);
        optionalOptions.add(SINK_MULTIPLE_FORMAT);
        optionalOptions.add(SINK_MULTIPLE_DATABASE_PATTERN);
        optionalOptions.add(SINK_MULTIPLE_TABLE_PATTERN);
        optionalOptions.add(SINK_MULTIPLE_SCHEMA_PATTERN);
        optionalOptions.add(SINK_MULTIPLE_SCHEMA_UPDATE_POLICY);
        optionalOptions.add(INLONG_METRIC);
        optionalOptions.add(INLONG_AUDIT);
        optionalOptions.add(AUDIT_KEYS);
        return optionalOptions;
    }

    private void validateConfigOptions(ReadableConfig config) {
        // Register custom dialect first
        Optional<String> dialectImplOptional = config.getOptional(DIALECT_IMPL);
        String jdbcUrl = config.get(URL);
        final Optional<JdbcDialect> dialect = dialectImplOptional.map(JdbcDialects::register)
                .orElseGet(() -> JdbcDialects.get(jdbcUrl));
        checkState(dialect.isPresent(), "Cannot handle such jdbc url: " + jdbcUrl);
        checkAllOrNone(config, new ConfigOption[]{USERNAME, PASSWORD});
        checkAllOrNone(
                config,
                new ConfigOption[]{
                        SCAN_PARTITION_COLUMN,
                        SCAN_PARTITION_NUM,
                        SCAN_PARTITION_LOWER_BOUND,
                        SCAN_PARTITION_UPPER_BOUND
                });
        if (config.getOptional(SCAN_PARTITION_LOWER_BOUND).isPresent()
                && config.getOptional(SCAN_PARTITION_UPPER_BOUND).isPresent()) {
            long lowerBound = config.get(SCAN_PARTITION_LOWER_BOUND);
            long upperBound = config.get(SCAN_PARTITION_UPPER_BOUND);
            if (lowerBound > upperBound) {
                throw new IllegalArgumentException(
                        String.format(
                                "'%s'='%s' must not be larger than '%s'='%s'.",
                                SCAN_PARTITION_LOWER_BOUND.key(),
                                lowerBound,
                                SCAN_PARTITION_UPPER_BOUND.key(),
                                upperBound));
            }
        }

        checkAllOrNone(config, new ConfigOption[]{LOOKUP_CACHE_MAX_ROWS, LOOKUP_CACHE_TTL});

        if (config.get(LOOKUP_MAX_RETRIES) < 0) {
            throw new IllegalArgumentException(
                    String.format(
                            "The value of '%s' option shouldn't be negative, but is %s.",
                            LOOKUP_MAX_RETRIES.key(), config.get(LOOKUP_MAX_RETRIES)));
        }

        if (config.get(SINK_MAX_RETRIES) < 0) {
            throw new IllegalArgumentException(
                    String.format(
                            "The value of '%s' option shouldn't be negative, but is %s.",
                            SINK_MAX_RETRIES.key(), config.get(SINK_MAX_RETRIES)));
        }

        if (config.get(MAX_RETRY_TIMEOUT).getSeconds() <= 0) {
            throw new IllegalArgumentException(
                    String.format(
                            "The value of '%s' option must be in second granularity and shouldn't be "
                                    + "smaller than 1 second, but is %s.",
                            MAX_RETRY_TIMEOUT.key(),
                            config.get(
                                    ConfigOptions.key(MAX_RETRY_TIMEOUT.key())
                                            .stringType()
                                            .noDefaultValue())));
        }
    }

    private void checkAllOrNone(ReadableConfig config, ConfigOption<?>[] configOptions) {
        int presentCount = 0;
        for (ConfigOption configOption : configOptions) {
            if (config.getOptional(configOption).isPresent()) {
                presentCount++;
            }
        }
        String[] propertyNames =
                Arrays.stream(configOptions).map(ConfigOption::key).toArray(String[]::new);
        Preconditions.checkArgument(
                configOptions.length == presentCount || presentCount == 0,
                "Either all or none of the following options should be provided:\n"
                        + String.join("\n", propertyNames));
    }
}
