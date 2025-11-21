package no.hia.oblig4;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class UserPasswordResetter {
    private final String jdbcUrl;

    public UserPasswordResetter(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }


    public int resetPassword(String username, String newPassword) throws Exception {
        final String sql = "UPDATE users SET password_hash = ? WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, newPassword);
            ps.setString(2, username);
            return ps.executeUpdate();
        }
    }
}
