package no.hia.oblig4;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class FavoritesDao {
    private final String jdbcUrl;

    public FavoritesDao(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }
    public boolean addFavorite(long userId, String quayId, String note) throws Exception {
        quayId = normalizeQuayId(quayId);
        if (quayId == null || !quayId.matches("^NSR:Quay:\\d+$")) return false;

        final String sql = """
            INSERT OR IGNORE INTO user_favorites (user_id, quay_id, note)
            VALUES (?, ?, ?)
        """;
        try (var c = DriverManager.getConnection(jdbcUrl);
             var ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, quayId);
            if (note == null || note.isBlank()) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, note);
            }
            return ps.executeUpdate() > 0;
        }
    }
    public boolean setPosition(long userId, String quayId, int pos) throws Exception {
        quayId = normalizeQuayId(quayId);
        if (quayId == null || !quayId.matches("^NSR:Quay:\\d+$")) return false;

        final String sql = "UPDATE user_favorites SET position = ? WHERE user_id = ? AND quay_id = ?";
        try (var c = DriverManager.getConnection(jdbcUrl);
             var ps = c.prepareStatement(sql)) {
            ps.setInt(1, pos);
            ps.setLong(2, userId);
            ps.setString(3, quayId);
            return ps.executeUpdate() > 0;
        }
    }
    public List<String> listFavorites(long userId) throws Exception {
        final String sql = """
            SELECT quay_id
            FROM user_favorites
            WHERE user_id = ?
            ORDER BY COALESCE(position, 999999), created_at
        """;
        var out = new ArrayList<String>();
        try (var c = DriverManager.getConnection(jdbcUrl);
             var ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }
    private static String normalizeQuayId(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.matches("^\\d+$")) return "NSR:Quay:" + s;
        return s;
    }
}

