package no.hia.oblig4;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class UserDeleter {
    private final String jdbcUrl;

    public UserDeleter(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public int deleteByUsername(String username) throws Exception {
        final String sql = "DELETE FROM users WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeUpdate();
        }
    }
}
