package no.hia.oblig4;

import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public class Main5 {

    // Simulator for mock-forsinkelser (samme som i webApp)
    private static final DelaySimulator DELAY_SIM = new DelaySimulator();

    public static void main(String[] args) throws Exception {

        String journeysPath = "src/main/resources/Rutetabell/Rutetider630.json";
        String quaysPath    = "src/main/resources/Rutetabell/stops_630_only.json";

        StopSearch search = new StopSearch(quaysPath);
        DirectTripFinder finder = new DirectTripFinder(journeysPath);

        Scanner sc = new Scanner(System.in);

        List<String> startCandidates;

        // -------- 1: VELG START-STOPP (med navn) --------
        while (true) {
            System.out.print("Søk etter START-stopp (vanlig navn): ");
            String startSearch = sc.nextLine().trim();

            // Prøv å gjette om brukeren egentlig mente Halden/Fredrikstad/Moss
            startSearch = maybeCorrectPlaceInput(startSearch, "START-stopp", sc);

            startCandidates = search.lookup(startSearch);

            if (!startCandidates.isEmpty()) {
                break; // vi har gyldige kandidater
            }

            System.out.println("Fant ingen stopp som matcher: \"" + startSearch + "\".");

            if (!looksLikeKnownPlace(startSearch)) {
                System.out.println("\nJeg forsto ikke hvor du mente som START-stopp.");
                System.out.println("Rute 630 går mellom Halden, Fredrikstad-området og Moss.");
                System.out.println("Prøv for eksempel å søke på \"Halden\", \"Fredrikstad\", \"Moss\"");
                System.out.println("eller et konkret holdeplasnavn langs denne strekningen (f.eks. \"Snippen\").");
            }

            System.out.println("\nPrøv å skrive START-stopp på nytt.\n");
        }

        System.out.println("\nForslag til START-stopp:");
        for (int i = 0; i < startCandidates.size(); i++) {
            String id = startCandidates.get(i);
            String navn = search.getStopName(id);
            System.out.println(" " + (i + 1) + ") " + navn + " (" + id + ")");
        }

        int startChoice = readIntInRange(sc,
                "Velg nummer for START-stopp: ",
                1,
                startCandidates.size(),
                "Ugyldig valg. Skriv et tall mellom 1 og " + startCandidates.size() + " for å velge START-stopp.");

        String chosenStartId   = startCandidates.get(startChoice - 1);
        String chosenStartName = search.getStopName(chosenStartId);

        // -------- 2: VELG DESTINASJON-STOPP (med navn) --------
        List<String> destCandidates;

        while (true) {
            System.out.print("\nSøk etter DESTINASJON-stopp (vanlig navn): ");
            String destSearch = sc.nextLine().trim();

            // Fuzzy-heuristikk for destinasjon
            destSearch = maybeCorrectPlaceInput(destSearch, "DESTINASJON-stopp", sc);

            destCandidates = search.lookup(destSearch);

            if (!destCandidates.isEmpty()) {
                break;
            }

            System.out.println("Fant ingen stopp som matcher: \"" + destSearch + "\".");

            if (!looksLikeKnownPlace(destSearch)) {
                System.out.println("\nJeg forsto ikke hvor du mente som DESTINASJON-stopp.");
                System.out.println("Rute 630 går mellom Halden, Fredrikstad-området og Moss.");
                System.out.println("Prøv for eksempel å søke på \"Halden\", \"Fredrikstad\", \"Moss\"");
                System.out.println("eller et konkret holdeplasnavn langs denne strekningen (f.eks. \"Snippen\").");
            }

            System.out.println("\nPrøv å skrive DESTINASJON-stopp på nytt.\n");
        }

        System.out.println("\nForslag til DESTINASJON-stopp:");
        for (int i = 0; i < destCandidates.size(); i++) {
            String id = destCandidates.get(i);
            String navn = search.getStopName(id);
            System.out.println(" " + (i + 1) + ") " + navn + " (" + id + ")");
        }

        int destChoice = readIntInRange(sc,
                "Velg nummer for DESTINASJON-stopp: ",
                1,
                destCandidates.size(),
                "Ugyldig valg. Skriv et tall mellom 1 og " + destCandidates.size() + " for å velge DESTINASJON-stopp.");

        String chosenDestId   = destCandidates.get(destChoice - 1);
        String chosenDestName = search.getStopName(chosenDestId);

        // -------- 3: PRØV VALGT KOMBINAJON --------
        System.out.println("\nSøker etter direkte turer fra "
                + chosenStartName + " (" + chosenStartId + ") til "
                + chosenDestName + " (" + chosenDestId + ") ...");

        List<DirectTripFinder.Trip> trips = finder.findTrips(chosenStartId, chosenDestId);

        if (!trips.isEmpty()) {
            System.out.println("\nFant " + trips.size() + " direkte avganger denne dagen.");
            showTripsInteractively(trips,
                    chosenStartName, chosenStartId,
                    chosenDestName, chosenDestId,
                    search,
                    sc);
        } else {
            System.out.println("\nFant ingen direkte turer mellom disse stoppene.");
            // Prøv å finne en annen kombinasjon som faktisk har rute
            boolean foundAlternative = findAlternativeRoute(
                    search, finder,
                    startCandidates, destCandidates,
                    chosenStartId, chosenDestId,
                    sc
            );
            if (!foundAlternative) {
                System.out.println("Fant heller ingen ruter for andre kombinasjoner av start og dest.");
            }
        }

        sc.close();
    }

    /**
     * Fallback:
     *  1) Prøver å finne rute med SAMME start-stopp og annen destinasjon.
     *  2) Prøver å finne rute med SAMME destinasjon og annet start-stopp.
     *  3) Hvis fortsatt ingenting: prøver alle kombinasjoner (unntatt den brukeren valgte).
     */
    private static boolean findAlternativeRoute(
            StopSearch search,
            DirectTripFinder finder,
            List<String> startCandidates,
            List<String> destCandidates,
            String chosenStartId,
            String chosenDestId,
            Scanner sc
    ) {

        String chosenStartName = search.getStopName(chosenStartId);
        String chosenDestName  = search.getStopName(chosenDestId);

        // 1) Samme start-stopp, prøv andre destinasjonsstopp
        for (String destId : destCandidates) {
            if (destId.equals(chosenDestId)) continue; // hopp over den brukeren valgte

            List<DirectTripFinder.Trip> trips = finder.findTrips(chosenStartId, destId);
            if (!trips.isEmpty()) {
                String destName = search.getStopName(destId);
                System.out.println("\nFant en alternativ rute med samme START-stopp:");
                System.out.println("  Start: " + chosenStartName + " (" + chosenStartId + ")");
                System.out.println("  Stopp: " + destName + " (" + destId + ")");
                System.out.println("\nFant " + trips.size() + " direkte avganger denne dagen.");

                showTripsInteractively(trips,
                        chosenStartName, chosenStartId,
                        destName, destId,
                        search,
                        sc);
                return true;
            }
        }

        // 2) Samme destinasjon, prøv andre start-stopp
        for (String startId : startCandidates) {
            if (startId.equals(chosenStartId)) continue; // hopp over den brukeren valgte

            List<DirectTripFinder.Trip> trips = finder.findTrips(startId, chosenDestId);
            if (!trips.isEmpty()) {
                String startName = search.getStopName(startId);
                System.out.println("\nFant en alternativ rute med samme DESTINASJON-stopp:");
                System.out.println("  Start: " + startName + " (" + startId + ")");
                System.out.println("  Stopp: " + chosenDestName + " (" + chosenDestId + ")");
                System.out.println("\nFant " + trips.size() + " direkte avganger denne dagen.");

                showTripsInteractively(trips,
                        startName, startId,
                        chosenDestName, chosenDestId,
                        search,
                        sc);
                return true;
            }
        }

        // 3) Hvis ingenting funket: prøv alle kombinasjoner (som siste utvei)
        for (String startId : startCandidates) {
            String startName = search.getStopName(startId);

            for (String destId : destCandidates) {
                String destName = search.getStopName(destId);

                if (startId.equals(chosenStartId) && destId.equals(chosenDestId)) {
                    continue; // hopp over den brukeren allerede prøvde
                }

                List<DirectTripFinder.Trip> trips = finder.findTrips(startId, destId);
                if (!trips.isEmpty()) {
                    System.out.println("\nFant en alternativ rute mellom:");
                    System.out.println("  Start: " + startName + " (" + startId + ")");
                    System.out.println("  Stopp: " + destName + " (" + destId + ")");
                    System.out.println("\nFant " + trips.size() + " direkte avganger denne dagen.");

                    showTripsInteractively(trips, startName, startId, destName, destId, search, sc);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Viser alle avganger (som liste), lar brukeren velge én,
     * eller 0 for å få detaljinfo om alle.
     * Her vises også forsinkelse / i rute / kansellert på hver avgang.
     */
    private static void showTripsInteractively(List<DirectTripFinder.Trip> trips,
                                               String startName, String startId,
                                               String destName,  String destId,
                                               StopSearch search,
                                               Scanner sc) {

        System.out.println("\nTilgjengelige avganger:");
        for (int i = 0; i < trips.size(); i++) {
            DirectTripFinder.Trip t = trips.get(i);
            DelaySimulator.DelayInfo d = DELAY_SIM.getDelay(t, startId, destId);

            String status;
            String depShown = t.departureTime;

            if (d.delayMinutes == 0) {
                status = "I rute";
            } else if (d.delayMinutes == 30) {
                // 30 min → behandles som kansellert
                status = "KANSELLERT (30 min forsinket)";
            } else {
                // 5, 10, 20 min
                String newDep = d.newDeparture != null ? d.newDeparture : t.departureTime;
                status = "Forsinket +" + d.delayMinutes + " min (ny avgang " + newDep + ")";
                depShown = newDep;
            }

            System.out.println(" " + (i + 1) + ") " +
                    depShown + " → " + t.arrivalTime +
                    " (linje " + t.line + " mot " + t.direction + ") " +
                    "[" + status + "]");
        }

        System.out.println("\nSkriv inn nummer for å velge en avgang,");
        System.out.println("eller 0 for å se detaljer om ALLE avganger denne dagen.");

        int choice = readIntInRange(sc,
                "Valg: ",
                0,
                trips.size(),
                "Ugyldig valg. Skriv et tall mellom 0 og " + trips.size() + ".");

        if (choice == 0) {
            // Vis detaljinfo for ALLE avganger
            for (DirectTripFinder.Trip t : trips) {
                printTripDetails(t, startName, startId, destName, destId, search);
            }
        } else {
            // Bruker har valgt én spesiell avgang
            DirectTripFinder.Trip chosen = trips.get(choice - 1);
            DelaySimulator.DelayInfo chosenDelay = DELAY_SIM.getDelay(chosen, startId, destId);

            if (chosenDelay.delayMinutes == 30) {
                // Denne avgangen er kansellert → foreslå nærmeste rute som er i rute
                System.out.println("\nDu har valgt en avgang som er KANSELLERT (30 min forsinkelse).");

                DirectTripFinder.Trip alt = findAlternativeOnTimeTrip(trips, startId, destId, chosen);
                if (alt != null) {
                    DelaySimulator.DelayInfo altDelay = DELAY_SIM.getDelay(alt, startId, destId);
                    String altDep = altDelay.newDeparture != null ? altDelay.newDeparture : alt.departureTime;

                    System.out.println("Anbefalt alternativ (i rute):");
                    System.out.println("  Avgang " + altDep + " → " + alt.arrivalTime +
                            " (linje " + alt.line + " mot " + alt.direction + ") [I rute]");

                    System.out.println("\nHva vil du gjøre?");
                    System.out.println("  1) Gå tilbake til oversikten over alle avganger");
                    System.out.println("  2) Velg denne anbefalte avgangen og se detaljer");

                    // validering av input 1/2
                    String altChoice;
                    while (true) {
                        System.out.print("Valg (1/2): ");
                        altChoice = sc.nextLine().trim();
                        if ("1".equals(altChoice) || "2".equals(altChoice)) {
                            break;
                        }
                        System.out.println("Ugyldig valg. Du må skrive 1 eller 2.");
                    }

                    if ("2".equals(altChoice)) {
                        // Vis detaljer for anbefalt avgang
                        printTripDetails(alt, startName, startId, destName, destId, search);
                    } else {
                        // Gå tilbake til oversikten (vis lista på nytt)
                        showTripsInteractively(trips, startName, startId, destName, destId, search, sc);
                    }
                } else {
                    System.out.println("Fant ingen annen avgang som er i rute.");
                    System.out.println("Går tilbake til oversikten over alle avganger.");
                    showTripsInteractively(trips, startName, startId, destName, destId, search, sc);
                }

                // Ikke vis detaljvisning for kansellert avgang
                return;
            }

            // Ikke kansellert → vis fulle detaljer
            printTripDetails(chosen, startName, startId, destName, destId, search);
        }
    }

    /**
     * Skriver ut detaljinfo for én avgang.
     */
    private static void printTripDetails(DirectTripFinder.Trip t,
                                         String startName, String startId,
                                         String destName,  String destId,
                                         StopSearch search) {

        DelaySimulator.DelayInfo d = DELAY_SIM.getDelay(t, startId, destId);

        System.out.println("-------------------------------------------------");
        System.out.println("Avgang (planlagt):    " + t.departureTime);
        System.out.println("Ankomst (planlagt):   " + t.arrivalTime);
        System.out.println("Linje:   " + t.line + " mot " + t.direction);
        System.out.println("Påstigning:  " + startName + " (" + startId + ")");
        System.out.println("Avstigning:  " + destName  + " (" + destId + ")");

        // Sanntidsstatus
        if (d.delayMinutes == 0) {
            System.out.println("Sanntidsstatus (mock): I rute");
        } else if (d.delayMinutes == 30) {
            System.out.println("Sanntidsstatus (mock): KANSELLERT (30 min forsinkelse)");
        } else {
            System.out.println("Sanntidsstatus (mock): FORSINKET +" + d.delayMinutes + " min");
        }

        // Estimert avgang fra start-stopp
        if (t.departureTime != null) {
            String etaDep = (d.newDeparture != null) ? d.newDeparture : t.departureTime;
            if (d.delayMinutes > 0 && d.delayMinutes != 30) {
                System.out.println("Estimert avgang (start-stopp): " + etaDep +
                        " (planlagt " + t.departureTime + ")");
            } else {
                System.out.println("Estimert avgang (start-stopp): " + etaDep +
                        (d.delayMinutes == 0 ? " (i rute)" : ""));
            }
        }

        // Estimert ankomst til destinasjon
        if (t.arrivalTime != null) {
            String etaArr = (d.newArrival != null) ? d.newArrival : t.arrivalTime;
            if (d.delayMinutes > 0 && d.delayMinutes != 30) {
                System.out.println("Oppdatert ETA (destinasjon):   " + etaArr +
                        " (planlagt " + t.arrivalTime + ")");
            } else {
                System.out.println("Oppdatert ETA (destinasjon):   " + etaArr +
                        (d.delayMinutes == 0 ? " (i rute)" : ""));
            }
        }

        // Koordinater – ikke tilgjengelig i denne mock-versjonen,
        // men vi viser en forklarende linje i stedet for å kalle en manglende funksjon.
        System.out.println("Koordinater (start-stopp): ikke tilgjengelig i denne mock-versjonen.");

        System.out.println("Antall stopp før ankomst: " + t.stopsBeforeDest);

        if (t.durationMinutes >= 0) {
            System.out.println("Estimert reisetid (planlagt): " + t.durationMinutes + " minutter");
        } else {
            System.out.println("Estimert reisetid (planlagt): ukjent (mangler tider)");
        }
        System.out.println("-------------------------------------------------");
    }

    /**
     * Finn en alternativ avgang som er "i rute" (delay = 0).
     */
    private static DirectTripFinder.Trip findAlternativeOnTimeTrip(
            List<DirectTripFinder.Trip> trips,
            String startId,
            String destId,
            DirectTripFinder.Trip exclude
    ) {
        if (trips == null || trips.isEmpty()) return null;

        int chosenIndex = -1;
        for (int i = 0; i < trips.size(); i++) {
            if (trips.get(i) == exclude) {
                chosenIndex = i;
                break;
            }
        }

        // Hvis vi ikke fant valgt avgang i lista, fall tilbake til "første i rute"
        if (chosenIndex == -1) {
            DirectTripFinder.Trip best = null;
            for (DirectTripFinder.Trip t : trips) {
                if (t == exclude) continue;
                DelaySimulator.DelayInfo d = DELAY_SIM.getDelay(t, startId, destId);
                if (d.delayMinutes == 0) {
                    best = t;
                    break;
                }
            }
            return best;
        }

        DirectTripFinder.Trip best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestIndex = -1;

        for (int i = 0; i < trips.size(); i++) {
            if (i == chosenIndex) continue;

            DirectTripFinder.Trip t = trips.get(i);
            DelaySimulator.DelayInfo d = DELAY_SIM.getDelay(t, startId, destId);
            if (d.delayMinutes != 0) continue; // vi vil bare ha ruter som er i rute

            int distance = Math.abs(i - chosenIndex);

            if (distance < bestDistance) {
                best = t;
                bestDistance = distance;
                bestIndex = i;
            } else if (distance == bestDistance) {
                // Ved lik "avstand", foretrekk en som er senere på dagen
                if (bestIndex < chosenIndex && i > chosenIndex) {
                    best = t;
                    bestIndex = i;
                }
            }
        }

        return best;
    }

    /* ===================== Input-hjelper ===================== */

    /**
     * Leser et heltall fra Scanner, med validering og feilmeldinger,
     * til brukeren skriver noe mellom min og max (inkludert).
     */
    private static int readIntInRange(Scanner sc,
                                      String prompt,
                                      int min,
                                      int max,
                                      String errorMessage) {
        while (true) {
            System.out.print(prompt);
            String line = sc.nextLine().trim();
            int val;
            try {
                val = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("Ugyldig input. Du må skrive et heltall.");
                continue;
            }
            if (val < min || val > max) {
                System.out.println(errorMessage);
                continue;
            }
            return val;
        }
    }

    /* ===================== Fuzzy stedsnavn-hjelpere ===================== */

    private static String maybeCorrectPlaceInput(String input, String role, Scanner sc) {
        String original = input;
        String guess = guessKnownPlaceName(input);

        if (guess != null && !guess.equalsIgnoreCase(original)) {
            System.out.print("Mente du \"" + capitalize(guess) + "\" som " + role + "? (j/n): ");
            String ans = sc.nextLine().trim().toLowerCase(Locale.ROOT);
            if (ans.startsWith("j")) {
                return guess;
            }
        }
        return original;
    }

    private static boolean looksLikeKnownPlace(String input) {
        return guessKnownPlaceName(input) != null;
    }

    private static String guessKnownPlaceName(String raw) {
        if (raw == null) return null;
        String u = raw.trim().toLowerCase(Locale.ROOT);
        if (u.isEmpty()) return null;

        String[] known = {"halden", "fredrikstad", "moss"};
        int bestDist = Integer.MAX_VALUE;
        String best = null;

        for (String k : known) {
            int d = levenshtein(u, k);
            if (d < bestDist) {
                bestDist = d;
                best = k;
            }
        }

        if (bestDist <= 3) { // f.eks. "hølden", "hæøden", "frdrikstad", "måsss"
            return best;
        }
        return null;
    }

    private static int levenshtein(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] dp = new int[n + 1][m + 1];

        for (int i = 0; i <= n; i++) dp[i][0] = i;
        for (int j = 0; j <= m; j++) dp[0][j] = j;

        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                int cost = (ca == cb) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1,
                                dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[n][m];
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}

