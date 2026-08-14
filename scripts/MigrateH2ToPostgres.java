import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Copies the complete application schema data from an offline H2 file into PostgreSQL. */
public final class MigrateH2ToPostgres {

    private MigrateH2ToPostgres() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: <h2-jdbc-url> <postgres-jdbc-url> <user> <password>");
        }
        try (Connection source = DriverManager.getConnection(args[0], "sa", "");
             Connection target = DriverManager.getConnection(args[1], args[2], args[3])) {
            target.setAutoCommit(false);
            List<String> tables = applicationTables(source.getMetaData());
            try (Statement statement = target.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.execute("truncate table " + String.join(", ", tables) + " restart identity cascade");
            }
            for (String table : tables) {
                int copied = copyTable(source, target, table);
                long sourceCount = count(source, table);
                long targetCount = count(target, table);
                if (sourceCount != copied || sourceCount != targetCount) {
                    throw new IllegalStateException(table + " copy verification failed: source=" + sourceCount
                            + ", copied=" + copied + ", target=" + targetCount);
                }
                System.out.println(table + "=" + copied);
            }
            try (Statement statement = target.createStatement()) {
                statement.execute("set session_replication_role = origin");
            }
            target.commit();
        }
    }

    private static List<String> applicationTables(DatabaseMetaData metadata) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet result = metadata.getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME").toLowerCase());
            }
        }
        return tables;
    }

    private static int copyTable(Connection source, Connection target, String table) throws SQLException {
        try (Statement query = source.createStatement();
             ResultSet rows = query.executeQuery("select * from " + table)) {
            ResultSetMetaData metadata = rows.getMetaData();
            int columns = metadata.getColumnCount();
            List<String> names = new ArrayList<>();
            List<String> placeholders = new ArrayList<>();
            for (int index = 1; index <= columns; index++) {
                names.add(metadata.getColumnName(index).toLowerCase());
                placeholders.add("?");
            }
            String sql = "insert into " + table + " (" + String.join(", ", names) + ") values ("
                    + String.join(", ", placeholders) + ")";
            int copied = 0;
            try (PreparedStatement insert = target.prepareStatement(sql)) {
                while (rows.next()) {
                    for (int index = 1; index <= columns; index++) {
                        Object value = rows.getObject(index);
                        if (value instanceof Timestamp timestamp) {
                            insert.setObject(index, timestamp.toInstant());
                        } else if (value instanceof BigDecimal decimal) {
                            insert.setBigDecimal(index, decimal);
                        } else {
                            insert.setObject(index, value);
                        }
                    }
                    insert.addBatch();
                    copied++;
                    if (copied % 250 == 0) {
                        insert.executeBatch();
                    }
                }
                insert.executeBatch();
            }
            return copied;
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
