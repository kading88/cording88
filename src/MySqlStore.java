import cic.cs.unb.ca.jnetpcap.FlowFeature;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Properties;

/** Stores complete flow records with one transaction per generated CSV. */
public final class MySqlStore {
    public static void save(Path csv, String sourceFile, Path configuration) throws Exception {
        Properties config = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(configuration, StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        String url = config.getProperty("jdbc.url", "");
        if (!url.startsWith("jdbc:mysql://")) throw new IOException("Set jdbc.url in the MySQL configuration.");
        String hash = sha256(csv);
        Properties credentials = new Properties();
        credentials.setProperty("user", config.getProperty("username", ""));
        credentials.setProperty("password", config.getProperty("password", ""));
        credentials.setProperty("rewriteBatchedStatements", "true");
        credentials.setProperty("connectTimeout", "10000");
        credentials.setProperty("socketTimeout", "60000");
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            createTables(connection);
            connection.setAutoCommit(false);
            try {
                if (alreadyImported(connection, hash)) {
                    connection.rollback();
                    System.out.println("MySQL: skipped (this CSV is already imported).");
                    return;
                }
                long importId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO pcap_imports (csv_sha256, source_file) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, hash);
                    statement.setString(2, sourceFile);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Missing import identifier.");
                        importId = keys.getLong(1);
                    }
                }
                int rows = insertFlows(connection, csv, importId);
                try (PreparedStatement statement = connection.prepareStatement("UPDATE pcap_imports SET row_count=? WHERE id=?")) {
                    statement.setInt(1, rows);
                    statement.setLong(2, importId);
                    statement.executeUpdate();
                }
                connection.commit();
                System.out.println("MySQL: committed " + rows + " flow rows (import " + importId + ").");
            } catch (Exception error) {
                connection.rollback();
                // The unique hash also prevents two concurrent identical imports.
                if (error instanceof SQLException && ((SQLException) error).getErrorCode() == 1062
                        && alreadyImported(connection, hash)) {
                    connection.rollback();
                    System.out.println("MySQL: skipped (another process imported this CSV).");
                    return;
                }
                throw error;
            }
        }
    }

    private static void createTables(Connection connection) throws SQLException {
        // Schema creation happens before the data transaction because MySQL DDL commits implicitly.
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS pcap_imports ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "csv_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,"
                    + "source_file VARCHAR(255) NOT NULL, row_count INT NOT NULL DEFAULT 0,"
                    + "imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS traffic_flows ("
                    + "import_id BIGINT NOT NULL, flow_index INT NOT NULL, flow_id VARCHAR(200) NOT NULL,"
                    + "src_ip VARCHAR(45) NOT NULL, src_port INT NOT NULL, dst_ip VARCHAR(45) NOT NULL,"
                    + "dst_port INT NOT NULL, protocol INT NOT NULL, flow_duration BIGINT NOT NULL,"
                    + "label VARCHAR(64) NOT NULL, features JSON NOT NULL,"
                    + "PRIMARY KEY(import_id, flow_index), INDEX idx_source (src_ip), INDEX idx_label (label),"
                    + "CONSTRAINT fk_flow_import FOREIGN KEY(import_id) REFERENCES pcap_imports(id) ON DELETE CASCADE) ENGINE=InnoDB");
        }
    }

    private static boolean alreadyImported(Connection connection, String hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM pcap_imports WHERE csv_sha256=?")) {
            statement.setString(1, hash);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static int insertFlows(Connection connection, Path csv, long importId) throws Exception {
        String sql = "INSERT INTO traffic_flows (import_id,flow_index,flow_id,src_ip,src_port,"
                + "dst_ip,dst_port,protocol,flow_duration,label,features) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            String header = reader.readLine();
            if (!FlowFeature.getHeader().equals(header)) throw new IOException("Unexpected CSV header.");
            String[] columns = header.split(",", -1);
            int count = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                // CICFlowMeter's generated fields do not contain embedded commas or newlines.
                String[] values = line.split(",", -1);
                if (values.length != 84) throw new IOException("CSV row " + (count + 2) + " must contain 84 fields.");
                statement.setLong(1, importId);
                statement.setInt(2, ++count);
                statement.setString(3, values[0]);
                statement.setString(4, values[1]);
                statement.setInt(5, Integer.parseInt(values[2]));
                statement.setString(6, values[3]);
                statement.setInt(7, Integer.parseInt(values[4]));
                statement.setInt(8, Integer.parseInt(values[5]));
                statement.setLong(9, Long.parseLong(values[7]));
                statement.setString(10, values[83]);
                statement.setString(11, featureJson(columns, values));
                statement.addBatch();
                if (count % 500 == 0) statement.executeBatch();
            }
            if (count % 500 != 0) statement.executeBatch();
            return count;
        }
    }

    private static String featureJson(String[] columns, String[] values) {
        // Keep all 84 values as strings, including any upstream NaN or Infinity values.
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < columns.length; i++) {
            if (i != 0) json.append(',');
            json.append(quote(columns[i])).append(':').append(quote(values[i]));
        }
        return json.append('}').toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\\') out.append('\\').append(ch);
            else if (ch < 32) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] block = new byte[65536];
            int read;
            while ((read = stream.read(block)) != -1) digest.update(block, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }
}
