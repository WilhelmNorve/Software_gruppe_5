package no.hia.oblig4;

import java.sql.*;

public class UserCreator {
    private final String jdbcUrl;

    public UserCreator(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public long createUser(String username, String passwordHash) throws Exception {
        final String sql = """
            INSERT INTO users (username, password_hash, created_at)
            VALUES (?, ?, datetime('now'))
            """;

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, username);
            ps.setString(2, passwordHash);

            int rows = ps.executeUpdate();
            if (rows == 0) throw new SQLException("Ingen rader ble innsatt.");

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("Mangler generert nøkkel.");
            }
        }
    }
}
