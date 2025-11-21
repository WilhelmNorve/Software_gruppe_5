package no.hia.oblig4;

import java.sql.*;

public class UserAuthenticator {
    private final String jdbcUrl;
    public UserAuthenticator(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }

    public Long authenticateAndGetId(String username, String password) throws Exception {
        final String sql = """
            SELECT id FROM users
            WHERE username = ? AND password_hash = ?
            LIMIT 1
        """;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, password);  // TODO: erstatt med hash-sjekk senere
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("id") : null;
            }
        }
    }

    /** Behold evt. denne om du vil: */
    public boolean authenticate(String username, String password) throws Exception {
        return authenticateAndGetId(username, password) != null;
    }
}

