/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.vertica;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.airbyte.commons.exceptions.ConnectionErrorException;
import io.airbyte.commons.functional.CheckedConsumer;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.factory.DatabaseDriver;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.db.jdbc.streaming.AdaptiveStreamingQueryConfig;
import io.airbyte.integrations.base.AirbyteTraceMessageUtility;
import io.airbyte.integrations.base.IntegrationRunner;
import io.airbyte.integrations.base.Source;
import io.airbyte.integrations.base.ssh.SshWrappedSource;
import io.airbyte.integrations.source.jdbc.AbstractJdbcSource;
import io.airbyte.integrations.util.ApmTraceUtils;
import io.airbyte.integrations.util.ConnectorExceptionUtil;
import io.airbyte.integrations.util.HostPortResolver;
import io.airbyte.protocol.models.v0.AirbyteCatalog;
import io.airbyte.protocol.models.v0.AirbyteConnectionStatus;
import io.airbyte.protocol.models.v0.AirbyteStream;
import io.airbyte.protocol.models.v0.SyncMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.JDBCType;
import java.util.List;
import java.util.Set;

import static io.airbyte.integrations.base.errors.messages.ErrorMessage.getErrorMessage;
import static java.util.stream.Collectors.toList;

public class VerticaSource extends AbstractJdbcSource<JDBCType> implements Source {

  private static final Logger LOGGER = LoggerFactory.getLogger(VerticaSource.class);

  // TODO insert your driver name. Ex: "com.microsoft.sqlserver.jdbc.SQLServerDriver"
  public static final String DRIVER_CLASS = DatabaseDriver.VERTICA.getDriverClassName();

  private static final int INTERMEDIATE_STATE_EMISSION_FREQUENCY = 10_000;

  public VerticaSource() {
    // TODO: if the JDBC driver does not support custom fetch size, use NoOpStreamingQueryConfig
    // instead of AdaptiveStreamingQueryConfig.
    super(DRIVER_CLASS, AdaptiveStreamingQueryConfig::new, new VerticaSourceOperations());
  }

  public static Source sshWrappedSource() {
    return new SshWrappedSource(new VerticaSource(), JdbcUtils.HOST_LIST_KEY, JdbcUtils.PORT_LIST_KEY);
  }

  @Override
  public JsonNode toDatabaseConfig(JsonNode config) {
    final String encodedDatabaseName = HostPortResolver.encodeValue(config.get(JdbcUtils.DATABASE_KEY).asText());
    final StringBuilder jdbcUrl = new StringBuilder(String.format("jdbc:vertica://%s:%s/%s?",
            config.get(JdbcUtils.HOST_KEY).asText(),
            config.get(JdbcUtils.PORT_KEY).asText(),
            encodedDatabaseName));
    if (config.get(JdbcUtils.JDBC_URL_PARAMS_KEY) != null && !config.get(JdbcUtils.JDBC_URL_PARAMS_KEY).asText().isEmpty()) {
      jdbcUrl.append(config.get(JdbcUtils.JDBC_URL_PARAMS_KEY).asText());
    }
    final ImmutableMap.Builder<Object, Object> configBuilder = ImmutableMap.builder()
            .put(JdbcUtils.USERNAME_KEY, config.get(JdbcUtils.USERNAME_KEY).asText())
            .put(JdbcUtils.JDBC_URL_KEY, jdbcUrl.toString());

    if (config.has(JdbcUtils.PASSWORD_KEY)) {
      configBuilder.put(JdbcUtils.PASSWORD_KEY, config.get(JdbcUtils.PASSWORD_KEY).asText());
    }
    System.out.println("---------------- toDatabaseConfig ----------------");
    System.out.println(jdbcUrl);
    return Jsons.jsonNode(configBuilder.build());
  }

  /**
   * Since the Oracle connector allows a user to specify schemas, and picks a default schemas
   * otherwise, system tables are never included, and do not need to be excluded by default.
   */
  @Override
  public Set<String> getExcludedInternalNameSpaces() {
    return Set.of("v_catalog","v_monitor","online_sales","public");
  }

  private static AirbyteStream overrideSyncModes(final AirbyteStream stream) {
    return stream.withSupportedSyncModes(Lists.newArrayList());
  }
    @Override
  public AirbyteCatalog discover(final JsonNode config) throws Exception {
    final AirbyteCatalog catalog = super.discover(config);
      final List<AirbyteStream> streams = catalog.getStreams().stream()
              .map(VerticaSource::overrideSyncModes)
              .collect(toList());

      catalog.setStreams(streams);
    return catalog;
  }

  public static void main(final String[] args) throws Exception {
    final Source source = new VerticaSource();
    LOGGER.info("starting source: {}", VerticaSource.class);
    new IntegrationRunner(source).run(args);
    LOGGER.info("completed source: {}", VerticaSource.class);
  }

}
