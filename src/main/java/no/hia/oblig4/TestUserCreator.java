package no.hia.oblig4;

import java.util.Scanner;

public class TestUserCreator {

    public static void main(String[] args) {
        // Tilpass sti til databasen hvis nødvendig
        String jdbcUrl = "jdbc:sqlite:src/data/app.db";

        try (Scanner scanner = new Scanner(System.in)) {

            System.out.println("=== Opprett ny bruker ===");

            System.out.print("Brukernavn: ");
            String username = scanner.nextLine().trim();

            System.out.print("Passord: ");
            String password = scanner.nextLine(); // i ekte system: HASH dette først

            if (username.isEmpty() || password.isEmpty()) {
                System.out.println("❌ Brukernavn og passord kan ikke være tomme.");
                return;
            }

            UserCreator creator = new UserCreator(jdbcUrl);

            try {
                long id = creator.createUser(username, password);
                System.out.println("✅ Bruker opprettet!");
                System.out.println("   ID: " + id);
                System.out.println("   Brukernavn: " + username);
            } catch (Exception e) {
                System.out.println("❌ Klarte ikke å opprette bruker.");
                // Vanlig feil her kan være UNIQUE-brudd på username
                e.printStackTrace();
            }
        }
    }
}
