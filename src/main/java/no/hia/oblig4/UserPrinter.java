package no.hia.oblig4;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class UserPrinter {
    private final String jdbcUrl;

    public UserPrinter(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Skriver ut alle kolonner i users-tabellen, uansett hva som legges til senere. */
    public void printAllUsers() throws Exception {
        printTable("users");
    }

    /** Generisk: skriv ut alle rader/kolonner i en tabell. */
    public void printTable(String table) throws Exception {
        // Enkel validering for å unngå SQL-injeksjon i tabellnavn
        if (!table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Ugyldig tabellnavn: " + table);
        }

        final String sql = "SELECT * FROM " + table + " ORDER BY 1";

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();

            // Les alle rader først for å kunne beregne kolonnebredder
            List<String[]> rows = new ArrayList<>();
            int[] widths = new int[cols];

            // Start med kolonnenavn
            for (int i = 1; i <= cols; i++) {
                String label = md.getColumnLabel(i);
                widths[i - 1] = label.length();
            }

            // Data
            while (rs.next()) {
                String[] row = new String[cols];
                for (int i = 1; i <= cols; i++) {
                    String val = rs.getString(i);
                    if (rs.wasNull()) val = "(null)";
                    row[i - 1] = val;
                    widths[i - 1] = Math.max(widths[i - 1], val.length());
                }
                rows.add(row);
            }

            // Skriv header
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) header.append(" | ");
                String label = md.getColumnLabel(i);
                header.append(padRight(label, widths[i - 1]));
            }
            System.out.println(header);

            // Separator
            StringBuilder sep = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) sep.append("-+-");
                sep.append("-".repeat(widths[i - 1]));
            }
            System.out.println(sep);

            // Rader
            if (rows.isEmpty()) {
                System.out.println("(ingen rader)");
                return;
            }
            for (String[] row : rows) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < cols; i++) {
                    if (i > 0) line.append(" | ");
                    line.append(padRight(row[i], widths[i]));
                }
                System.out.println(line);
            }
        }
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}

