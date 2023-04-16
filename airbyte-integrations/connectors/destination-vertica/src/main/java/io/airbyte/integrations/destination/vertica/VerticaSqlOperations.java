package io.airbyte.integrations.destination.vertica;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.jdbc.JdbcDatabase;
import io.airbyte.integrations.destination.StandardNameTransformer;
import io.airbyte.integrations.destination.jdbc.JdbcSqlOperations;
import io.airbyte.protocol.models.v0.AirbyteRecordMessage;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class VerticaSqlOperations extends JdbcSqlOperations {

    private boolean isLocalFileEnabled = false;
    @Override
    protected void insertRecordsInternal(JdbcDatabase database, List<AirbyteRecordMessage> records, String schemaName, String tableName) throws Exception {
        final String tableNamee = String.format("%s.%s", schemaName, tableName);
        final String columns = String.format("(%s, %s, %s)",
                VerticaDestination.COLUMN_NAME_AB_ID, VerticaDestination.COLUMN_NAME_DATA, VerticaDestination.COLUMN_NAME_EMITTED_AT);
        final String recordQueryComponent = "(?, ?, ?);\n";
        insertRawRecordsInSingleQuery(tableNamee, columns, recordQueryComponent, database, records, UUID::randomUUID);
    }


    private static void insertRawRecordsInSingleQuery(final String tableName,
                                                      final String columns,
                                                      final String recordQueryComponent,
                                                      final JdbcDatabase jdbcDatabase,
                                                      final List<AirbyteRecordMessage> records,
                                                      final Supplier<UUID> uuidSupplier)
            throws SQLException {
        if (records.isEmpty()) {
            return;
        }
        jdbcDatabase.execute(connection -> {

            // Strategy: We want to use PreparedStatement because it handles binding values to the SQL query
            // (e.g. handling formatting timestamps). A PreparedStatement statement is created by supplying the
            // full SQL string at creation time. Then subsequently specifying which values are bound to the
            // string. Thus there will be two loops below.
            // 1) Loop over records to build the full string.
            // 2) Loop over the records and bind the appropriate values to the string.
            //
            // The "SELECT 1 FROM DUAL" at the end is a formality to satisfy the needs of the Oracle syntax.
            // (see https://stackoverflow.com/a/93724 for details)
            System.out.println("--------------- Executed --------------------");
            final StringBuilder sql = new StringBuilder(String.format("INSERT INTO %s %s VALUES %s", tableName, columns, recordQueryComponent));
            //records.forEach(r -> sql.append(String.format("INTO %s %s VALUES %s", tableName, columns, recordQueryComponent)));
            final String query = sql.toString().trim();
            try  {
                final PreparedStatement statement = connection.prepareStatement(query);
                // second loop: bind values to the SQL string.
                int i = 1;
                for (final AirbyteRecordMessage message : records) {
                    // 1-indexed
                    final JsonNode formattedData = StandardNameTransformer.formatJsonPath(message.getData());
                    statement.setString(i, uuidSupplier.get().toString());
                    statement.setString(i + 1, Jsons.serialize(formattedData));
                    statement.setTimestamp(i + 2, Timestamp.from(Instant.ofEpochMilli(message.getEmittedAt())));
                    i = 1;
                    statement.execute();
                }
                //statement.execute();
            } catch(Exception e){
                e.printStackTrace();
            }
        });
    }

    @Override
    public void createSchemaIfNotExists(final JdbcDatabase database, final String schemaName) throws Exception {
        final String query = String.format("CREATE SCHEMA IF NOT EXISTS %s", schemaName);
        // need to grant privileges to new user / this option is not mandatory for Oracle DB 18c or higher
        final String privileges = String.format("GRANT ALL PRIVILEGES TO %s", schemaName);
        database.execute(query);
        //database.execute(privileges);
    }

    @Override
    public String createTableQuery(final JdbcDatabase database, final String schemaName, final String tableName) {
        /*
        return String.format(
                "CREATE TABLE %s.%s ( \n"
                        + "%s VARCHAR(64) PRIMARY KEY,\n"
                        + "%s LONG VARCHAR,\n"
                        + "%s TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP\n"
                        + ")",
                schemaName, tableName,
                VerticaDestination.COLUMN_NAME_AB_ID, VerticaDestination.COLUMN_NAME_DATA, VerticaDestination.COLUMN_NAME_EMITTED_AT,
                VerticaDestination.COLUMN_NAME_DATA);
                */
        final String query = String.format(
                "CREATE TABLE IF NOT EXISTS %s.%s (%s VARCHAR(500) PRIMARY KEY,%s VARCHAR(1000),%s VARCHAR(1000));", schemaName, tableName, VerticaDestination.COLUMN_NAME_AB_ID, VerticaDestination.COLUMN_NAME_DATA,VerticaDestination.COLUMN_NAME_EMITTED_AT);
        return query;
    }

    private boolean tableExists(final JdbcDatabase database, final String schemaName, final String tableName) throws Exception {
        final String query = String.format("select count(*) from all_tables where table_name LIKE '%s';", tableName + "%");
        final Integer count = database.queryInt(query);
        return count == 1;
    }

    @Override
    public void createTableIfNotExists(final JdbcDatabase database, final String schemaName, final String tableName) {
        try {
            if (!tableExists(database, schemaName, tableName)) {
                database.execute(createTableQuery(database, schemaName, tableName));
            }
        } catch (final Exception e) {
            LOGGER.error("Error while creating table.", e);
        }
    }

    @Override
    public void dropTableIfExists(final JdbcDatabase database, final String schemaName, final String tableName) {
        try {
            if (tableExists(database, schemaName, tableName)) {
                try {
                    final String query = String.format("DROP TABLE %s.%s", schemaName, tableName);
                    database.execute(query);
                } catch (final Exception e) {
                    LOGGER.error(String.format("Error dropping table %s.%s", schemaName, tableName), e);
                    throw e;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void executeTransaction(final JdbcDatabase database, final List<String> queries) throws Exception {
        database.executeWithinTransaction(queries);
    }

    @Override
    public String insertTableQuery(final JdbcDatabase database,
                                   final String schemaName,
                                   final String sourceTableName,
                                   final String destinationTableName) {
        return String.format("INSERT INTO %s.%s SELECT * FROM %s.%s;\n", schemaName, destinationTableName.substring(destinationTableName.lastIndexOf("_")+1), schemaName, sourceTableName);
    }

    private boolean checkIfLocalFileIsEnabled(final JdbcDatabase database) throws SQLException {
        final List<String> localFiles = database.queryStrings(
                connection -> connection.createStatement().executeQuery("SHOW GLOBAL VARIABLES LIKE 'local_infile'"),
                resultSet -> resultSet.getString("Value"));
        return localFiles.get(0).equalsIgnoreCase("on");
    }

    private double getVersion(final JdbcDatabase database) throws SQLException {
        final List<String> versions = database.queryStrings(
                connection -> connection.createStatement().executeQuery("select version()"),
                resultSet -> resultSet.getString("version()"));
        return Double.parseDouble(versions.get(0).substring(0, 3));
    }


    VersionCompatibility isCompatibleVersion(final JdbcDatabase database) throws SQLException {
        final double version = getVersion(database);
        return new VersionCompatibility(version, version >= 5.7);
    }

    private void tryEnableLocalFile(final JdbcDatabase database) throws SQLException {
        database.execute(connection -> {
            try (final Statement statement = connection.createStatement()) {
                statement.execute("set global local_infile=true");
            } catch (final Exception e) {
                throw new RuntimeException(
                        "The DB user provided to airbyte was unable to switch on the local_infile attribute on the MySQL server. As an admin user, you will need to run \"SET GLOBAL local_infile = true\" before syncing data with Airbyte.",
                        e);
            }
        });
    }

    void verifyLocalFileEnabled(final JdbcDatabase database) throws SQLException {
        final boolean localFileEnabled = isLocalFileEnabled || checkIfLocalFileIsEnabled(database);
        if (!localFileEnabled) {
            tryEnableLocalFile(database);
        }
        isLocalFileEnabled = true;
    }

    public static class VersionCompatibility {

        private final double version;
        private final boolean isCompatible;

        public VersionCompatibility(final double version, final boolean isCompatible) {
            this.version = version;
            this.isCompatible = isCompatible;
        }

        public double getVersion() {
            return version;
        }

        public boolean isCompatible() {
            return isCompatible;
        }

    }

}
