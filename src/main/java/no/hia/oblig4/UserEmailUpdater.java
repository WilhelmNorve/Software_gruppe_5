package no.hia.oblig4;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class UserEmailUpdater {
    private final String jdbcUrl;

    public UserEmailUpdater(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Oppdaterer e-post basert på brukernavn. Returnerer antall oppdaterte rader (0 eller 1). */
    public int updateMailByUsername(String username, String newMail) throws Exception {
        final String sql = "UPDATE users SET Mail = ? WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newMail);
            ps.setString(2, username);
            return ps.executeUpdate();
        }
    }
}
