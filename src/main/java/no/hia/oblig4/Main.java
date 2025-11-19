package no.hia.oblig4;

import java.util.Scanner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// For GraphQL-kall direkte fra Main (uten libs)
import java.net.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class Main {

    // === Konfig: region (midtpkt + radius). Sett disse til din region. ===
    private static final String REGION_LAT = "59.9127"; // Oslo sentralt som default
    private static final String REGION_LON = "10.7461";
    private static final int REGION_RADIUS_KM = 20;
    // =====================================================================

    // Entur Journey Planner v3
    private static final String JP_ENDPOINT = "https://api.entur.io/journey-planner/v3/graphql";
    private static final String ET_CLIENT_NAME = "norve.oblig4/1.0 (kontakt@eksempel.no)";

    public static void main(String[] args) {
        String dbPath = Paths.get("src", "data", "app.db").toAbsolutePath().toString();
        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        userCreator creator = new userCreator(jdbcUrl);
        userAuthenticator auth = new userAuthenticator(jdbcUrl);
        userDeleter deleter = new userDeleter(jdbcUrl);
        userEmailUpdater updater = new userEmailUpdater(jdbcUrl);
        userPasswordResetter resetter = new userPasswordResetter(jdbcUrl);
        favoritesDao favs = new favoritesDao(jdbcUrl);

        boolean loop = true;
        Long currentUserId = null;
        String currentUsername = null;

        try (Scanner sc = new Scanner(System.in)) {
            while (loop) {
                System.out.println(
                        "Velg: [1] Registrer [2] Logg inn [3] Slett bruker [4] Skriv ut databasen " +
                                "[5] Oppdater e-post [6] Endre passord [7] Legg til favoritt [8] Vis favoritter " +
                                "[9] Sett rekkefølge (position) [10] Hent ruter for holdeplass (Entur) " +
                                "[11] Søk holdeplasser (prefiks i region) [12] Avgangstider for rutenummer " +
                                "[13] Finn alle stopp for rutenummer (i region) " +
                                "[14] Østfold: Stopp for rutenummer (start→slutt) " +
                                "[15] Østfold: Neste avgang fra start → alle stopp " +
                                "[16] Antall stopp pr rute (publicCode) " +
                                "[17] Neste avgang (navn/ID → navn/ID) " +
                                "[0] Avslutt"
                );
                System.out.print("Valg (0-17): ");
                String choice = sc.nextLine().trim();

                switch (choice) {
                    case "1" -> {
                        System.out.print("Nytt brukernavn: ");
                        String newUser = sc.nextLine().trim();
                        System.out.print("Passord (lagres i password_hash-feltet): ");
                        String newPass = sc.nextLine();
                        try {
                            long id = creator.createUser(newUser, newPass);
                            System.out.println("✅ Bruker opprettet med id=" + id);
                        } catch (Exception e) {
                            System.err.println("❌ Klarte ikke å opprette bruker/Bruker eksisterer fra før av");
                        }
                    }

                    case "2" -> {
                        System.out.print("Brukernavn: ");
                        String username = sc.nextLine().trim();
                        System.out.print("Passord: ");
                        String password = sc.nextLine();
                        try {
                            Long uid = auth.authenticateAndGetId(username, password);
                            if (uid != null) {
                                currentUserId = uid;
                                currentUsername = username;
                                System.out.println("Innlogging OK ✅ (userId=" + uid + ")");
                            } else {
                                System.out.println("Feil brukernavn eller passord ❌");
                            }
                        } catch (Exception e) {
                            System.err.println("❌ Feil ved autentisering: " + e.getMessage());
                        }
                    }

                    case "3" -> {
                        System.out.print("Brukernavn som skal slettes: ");
                        String delUser = sc.nextLine().trim();
                        try {
                            int rows = deleter.deleteByUsername(delUser);
                            if (rows > 0) {
                                System.out.println("🗑️ Slettet " + rows + " rad(er) for bruker '" + delUser + "'.");
                                if (currentUsername != null && currentUsername.equals(delUser)) {
                                    currentUserId = null;
                                    currentUsername = null;
                                }
                            } else {
                                System.out.println("Fant ingen bruker å slette for '" + delUser + "'.");
                            }
                        } catch (Exception e) {
                            System.err.println("❌ Klarte ikke å slette: " + e.getMessage());
                        }
                    }

                    case "4" -> {
                        userPrinter printer = new userPrinter(jdbcUrl);
                        try {
                            printer.printAllUsers();
                        } catch (Exception e) {
                            System.err.println("❌ Feil ved utskrift: " + e.getMessage());
                        }
                    }

                    case "5" -> {
                        System.out.print("Brukernavn å oppdatere: ");
                        String username = sc.nextLine().trim();
                        System.out.print("Ny e-post: ");
                        String newMail = sc.nextLine().trim();
                        try {
                            int rows = updater.updateMailByUsername(username, newMail);
                            if (rows > 0) {
                                System.out.println("✅ Oppdaterte e-post for '" + username + "' til: " + newMail);
                            } else {
                                System.out.println("❌ Fant ingen bruker med brukernavn '" + username + "'.");
                            }
                        } catch (Exception e) {
                            System.err.println("Feil ved oppdatering: " + e.getMessage());
                        }
                    }

                    case "6" -> {
                        System.out.print("Brukernavn: ");
                        String username = sc.nextLine().trim();
                        System.out.print("Nytt passord: ");
                        String newPassword = sc.nextLine();
                        try {
                            int rows = resetter.resetPassword(username, newPassword);
                            if (rows > 0) {
                                System.out.println("✅ Passordet ble tilbakestilt for '" + username + "'.");
                            } else {
                                System.out.println("❌ Fant ingen bruker med brukernavn '" + username + "'.");
                            }
                        } catch (Exception e) {
                            System.err.println("Feil ved passordendring: " + e.getMessage());
                        }
                    }

                    case "7" -> {
                        if (currentUserId == null) {
                            System.out.println("⚠️ Du må være innlogget for å lagre favoritt-rute.");
                            break;
                        }
                        System.out.print("Quay ID (f.eks. 101991 eller NSR:Quay:101991): ");
                        String quayIdInput = sc.nextLine().trim();
                        String quayId = normalizeQuayId(quayIdInput);
                        System.out.print("Valgfri note/beskrivelse: ");
                        String note = sc.nextLine().trim();
                        try {
                            boolean ok = favs.addFavorite(currentUserId, quayId, note.isBlank() ? null : note);
                            if (ok) System.out.println("✅ Lagret favoritt for bruker id=" + currentUserId + ": " + quayId);
                            else   System.out.println("❌ Ugyldig format eller finnes allerede for denne brukeren.");
                        } catch (Exception e) {
                            System.err.println("Feil ved lagring av favoritt: " + e.getMessage());
                        }
                    }

                    case "8" -> {
                        if (currentUserId == null) { System.out.println("⚠️ Du må være innlogget."); break; }
                        try {
                            var list = favs.listFavorites(currentUserId);
                            if (list.isEmpty()) System.out.println("(ingen favoritter)");
                            else {
                                System.out.println("📌 Dine favoritter (i rekkefølge):");
                                int i = 1;
                                for (var q : list) System.out.println("  " + (i++) + ") " + q);
                            }
                        } catch (Exception e) {
                            System.err.println("Feil ved uthenting: " + e.getMessage());
                        }
                    }

                    case "9" -> {
                        if (currentUserId == null) { System.out.println("⚠️ Du må være innlogget."); break; }
                        System.out.print("Quay ID du vil rangere (f.eks. 101991 eller NSR:Quay:101991): ");
                        String quayIdInput = sc.nextLine().trim();
                        String quayId = normalizeQuayId(quayIdInput);
                        System.out.print("Hvilken position (1 = øverst): ");
                        String posStr = sc.nextLine().trim();
                        int pos;
                        try {
                            pos = Integer.parseInt(posStr);
                            if (pos < 1) { System.out.println("❌ Position må være 1 eller høyere."); break; }
                        } catch (NumberFormatException nfe) { System.out.println("❌ Ugyldig tall."); break; }
                        try {
                            boolean ok = favs.setPosition(currentUserId, quayId, pos);
                            System.out.println(ok ? "✅ Oppdatert position for " + quayId + " til " + pos
                                    : "❌ Fant ikke favoritten for denne brukeren.");
                        } catch (Exception e) {
                            System.err.println("Feil ved oppdatering: " + e.getMessage());
                        }
                    }

                    case "10" -> { // Entur: rutenummer + navn + neste avgang for et konkret Quay
                        System.out.print("NSR:Quay-ID (f.eks. NSR:Quay:101991): ");
                        String quayId = sc.nextLine().trim();
                        if (quayId.isEmpty()) { System.out.println("Ingen Quay-ID oppgitt."); break; }
                        System.out.print("Antall avganger å sjekke [10]: ");
                        String limitStr = sc.nextLine().trim();
                        int limit = 10; if (!limitStr.isEmpty()) try { limit = Integer.parseInt(limitStr); } catch (Exception ignored) {}
                        try {
                            enturClient client = new enturClient(ET_CLIENT_NAME);
                            String json = client.fetchDepartures(quayId, limit);
                            String quayName = extractFirst(json, "\"name\"\\s*:\\s*\"([^\"]+)\"");
                            var routes = extractRoutesWithNameAndNext(json);
                            System.out.println();
                            if (quayName != null) System.out.println("Busstopp: " + quayName + " (" + quayId + ")");
                            else                  System.out.println("Busstopp-ID: " + quayId);
                            if (routes.isEmpty()) System.out.println("Ingen ruter funnet (kan være tomt nå eller feil ID).");
                            else {
                                System.out.println("Ruter (nummer — navn — neste avgang):");
                                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
                                routes.forEach((code, info) -> {
                                    String name = info.name == null || info.name.isBlank() ? "" : (" — " + info.name);
                                    String time = info.next == null ? "—" :
                                            fmt.format(info.next.atZoneSameInstant(ZoneId.systemDefault()));
                                    System.out.println(" • " + code + name + " — " + time);
                                });
                                System.out.println("Totalt unike ruter: " + routes.size());
                            }
                        } catch (Exception e) {
                            System.err.println("❌ Feil ved Entur-oppslag: " + e.getMessage());
                        }
                    }

                    case "11" -> { // Geocoder: søk holdeplasser på prefiks (begrenset til region)
                        System.out.print("Skriv prefiks for holdeplassnavn: ");
                        String prefix = sc.nextLine().trim();
                        if (prefix.isEmpty()) { System.out.println("Ingen tekst oppgitt."); break; }
                        System.out.print("Maks antall forslag [20]: ");
                        String limStr = sc.nextLine().trim();
                        int limit = 20; if (!limStr.isEmpty()) try { limit = Integer.parseInt(limStr); } catch (Exception ignored) {}
                        try {
                            enturClient client = new enturClient(ET_CLIENT_NAME);
                            String body = client.searchStopsInRegion(prefix, limit, REGION_LAT, REGION_LON, Math.max(1, REGION_RADIUS_KM) * 1000);
                            var hits = extractStopsFromGeocoder(body);
                            hits.removeIf(h -> !(h.id.startsWith("NSR:StopPlace:") || h.id.startsWith("NSR:Quay:")));
                            String pfxLower = prefix.toLowerCase();
                            var filtered = new ArrayList<StopHit>();
                            for (var h : hits) if (h.name != null && h.name.toLowerCase().startsWith(pfxLower)) filtered.add(h);
                            var toShow = filtered.isEmpty() ? hits : filtered;
                            if (toShow.isEmpty()) System.out.println("(ingen holdeplasser funnet for \"" + prefix + "\" i regionen)");
                            else {
                                System.out.println("🔎 Forslag i region (" + toShow.size() + "):");
                                int i = 1; for (var h : toShow) System.out.println("  " + (i++) + ") " + h.name + " — " + h.id);
                                System.out.println("Tips: Bruk NSR:StopPlace med resolveQuayId(…) for å få en NSR:Quay (case 10).");
                            }
                        } catch (Exception e) {
                            System.err.println("❌ Feil ved geocoder-søk: " + e.getMessage());
                        }
                    }

                    case "12" -> { // Avgangstider for et gitt rutenummer (publicCode) per stopp
                        System.out.print("Rutenummer (publicCode, f.eks. 3 eller 3E): ");
                        String routeCode = sc.nextLine().trim();
                        if (routeCode.isEmpty()) { System.out.println("Ingen rutenummer oppgitt."); break; }
                        System.out.print("Antall avganger per stopp [10]: ");
                        String limStr = sc.nextLine().trim();
                        int perStop = 10; if (!limStr.isEmpty()) try { perStop = Integer.parseInt(limStr); } catch (Exception ignored) {}
                        try {
                            enturClient client = new enturClient(ET_CLIENT_NAME);
                            var stopEntries = new ArrayList<String>();
                            if (currentUserId != null) {
                                try {
                                    var favList = favs.listFavorites(currentUserId);
                                    for (var favQuay : favList) {
                                        String qid = client.resolveQuayId(favQuay);
                                        if (qid != null && qid.startsWith("NSR:Quay:")) stopEntries.add(qid);
                                    }
                                } catch (Exception ignored) {}
                            }
                            if (stopEntries.isEmpty()) {
                                System.out.print("Valgfritt prefiks for stopp (enter = ta første 10 treff i region): ");
                                String prefix = sc.nextLine().trim();
                                int howManyStops = 10;
                                String body = client.searchStopsInRegion(prefix.isEmpty() ? "a" : prefix, howManyStops, REGION_LAT, REGION_LON, Math.max(1, REGION_RADIUS_KM) * 1000);
                                var hits = extractStopsFromGeocoder(body);
                                hits.removeIf(h -> !(h.id.startsWith("NSR:StopPlace:") || h.id.startsWith("NSR:Quay:")));
                                var list = new ArrayList<StopHit>();
                                if (!prefix.isEmpty()) {
                                    String pfxLower = prefix.toLowerCase();
                                    for (var h : hits) if (h.name != null && h.name.toLowerCase().startsWith(pfxLower)) list.add(h);
                                } else list = hits;
                                int added = 0;
                                for (var h : list) {
                                    if (added >= howManyStops) break;
                                    String qid = client.resolveQuayId(h.id);
                                    if (qid != null && qid.startsWith("NSR:Quay:")) { stopEntries.add(qid); added++; }
                                }
                            }
                            if (stopEntries.isEmpty()) { System.out.println("(fant ingen stopp å sjekke)"); break; }
                            System.out.println("⏱️ Avgangstider for rute " + routeCode + " (per stopp):");
                            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
                            for (String qid : stopEntries) {
                                try {
                                    String json = client.fetchDepartures(qid, Math.max(5, perStop));
                                    String quayName = extractFirst(json, "\"name\"\\s*:\\s*\"([^\"]+)\"");
                                    var times = extractTimesForRoute(json, routeCode, perStop);
                                    if (times.isEmpty()) System.out.println(" • " + (quayName != null ? quayName : qid) + " — (ingen avganger funnet nå)");
                                    else {
                                        var sb = new StringBuilder(); int k = 0;
                                        for (var t : times) { if (k++ > 0) sb.append(", "); sb.append(fmt.format(t.atZoneSameInstant(ZoneId.systemDefault()))); }
                                        System.out.println(" • " + (quayName != null ? quayName : qid) + " — " + sb);
                                    }
                                } catch (Exception e) {
                                    System.out.println(" • " + qid + " — feil ved oppslag: " + e.getMessage());
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("❌ Feil: " + e.getMessage());
                        }
                    }

                    case "13" -> { // Finn alle stopp i regionen der rutenummeret faktisk går
                        System.out.print("Rutenummer (publicCode, f.eks. 3 eller 3E): ");
                        String routeCode = sc.nextLine().trim();
                        if (routeCode.isEmpty()) { System.out.println("Ingen rutenummer oppgitt."); break; }
                        System.out.print("Hvor mange stopp i regionen skal skannes [100]: ");
                        String scanStr = sc.nextLine().trim();
                        int scanLimit = 100; if (!scanStr.isEmpty()) try { scanLimit = Integer.parseInt(scanStr); } catch (Exception ignored) {}
                        try {
                            enturClient client = new enturClient(ET_CLIENT_NAME);
                            var candidates = new ArrayList<String>();
                            if (currentUserId != null) {
                                try {
                                    var favList = favs.listFavorites(currentUserId);
                                    for (var fav : favList) {
                                        String qid = client.resolveQuayId(fav);
                                        if (qid != null && qid.startsWith("NSR:Quay:")) candidates.add(qid);
                                    }
                                } catch (Exception ignored) {}
                            }
                            if (candidates.size() < scanLimit) {
                                String body = client.searchStopsInRegion("a", scanLimit, REGION_LAT, REGION_LON, Math.max(1, REGION_RADIUS_KM) * 1000);
                                var hits = extractStopsFromGeocoder(body);
                                hits.removeIf(h -> !(h.id.startsWith("NSR:StopPlace:") || h.id.startsWith("NSR:Quay:")));
                                for (var h : hits) {
                                    if (candidates.size() >= scanLimit) break;
                                    String qid = client.resolveQuayId(h.id);
                                    if (qid != null && qid.startsWith("NSR:Quay:")) candidates.add(qid);
                                }
                            }
                            if (candidates.isEmpty()) { System.out.println("(fant ingen stopp å skanne i regionen)"); break; }
                            LinkedHashMap<String, String> foundStops = new LinkedHashMap<>(); // name -> quayId
                            LinkedHashSet<String> seenNames = new LinkedHashSet<>();
                            int checked = 0;
                            for (String qid : candidates) {
                                if (checked >= scanLimit) break;
                                checked++;
                                try {
                                    String json = client.fetchDepartures(qid, 30);
                                    var times = extractTimesForRoute(json, routeCode, 1);
                                    if (!times.isEmpty()) {
                                        String name = extractFirst(json, "\"name\"\\s*:\\s*\"([^\"]+)\"");
                                        if (name == null || name.isBlank()) name = qid;
                                        if (!seenNames.contains(name)) {
                                            seenNames.add(name);
                                            foundStops.put(name, qid);
                                        }
                                    }
                                } catch (Exception e) { /* hopp over feilende stopp */ }
                            }
                            if (foundStops.isEmpty()) System.out.println("Fant ingen stopp i regionen for rute " + routeCode + " akkurat nå.");
                            else {
                                System.out.println("🚌 Stopp der rute " + routeCode + " har avganger (funn: " + foundStops.size() + "):");
                                int i = 1; for (var entry : foundStops.entrySet()) System.out.println("  " + (i++) + ") " + entry.getKey() + " — " + entry.getValue());
                                System.out.println("Merk: Øyeblikksbilde basert på planlagte/neste avganger i regionen.");
                            }
                        } catch (Exception e) {
                            System.err.println("❌ Feil: " + e.getMessage());
                        }
                    }

                    case "14" -> { // Østfold: full stopp-sekvens (start→slutt) for en rute (publicCode)
                        System.out.print("Rutenummer (publicCode, f.eks. 3 eller 3E): ");
                        String routeCode = sc.nextLine().trim();
                        if (routeCode.isEmpty()) { System.out.println("Ingen rutenummer oppgitt."); break; }
                        System.out.print("Hvor mange stopp i Østfold skal skannes [200]: ");
                        String scanStr = sc.nextLine().trim();
                        int scanLimit = 200; if (!scanStr.isEmpty()) try { scanLimit = Integer.parseInt(scanStr); } catch (Exception ignored) {}

                        final String OST_LAT = "59.27"; // Østfold-ish
                        final String OST_LON = "11.02";
                        final int OST_RADIUS_KM = 55;

                        try {
                            enturClient client = new enturClient(ET_CLIENT_NAME);
                            var candidates = buildOstfoldQuayCandidates(client, currentUserId, favs, scanLimit, OST_LAT, OST_LON, OST_RADIUS_KM);
                            if (candidates.isEmpty()) { System.out.println("(fant ingen stopp å skanne i Østfold)"); break; }

                            String anyServiceJourneyId = null;
                            String lineName = null;

                            for (String qid : candidates) {
                                try {
                                    String json = fetchDeparturesWithServiceJourney(qid, 50);
                                    String callBlockRegex = "\\{[\\s\\S]*?\"expectedDepartureTime\"[\\s\\S]*?\"line\"\\s*:\\s*\\{([\\s\\S]*?)\\}[\\s\\S]*?\"serviceJourney\"\\s*:\\s*\\{([\\s\\S]*?)\\}";
                                    Matcher m = Pattern.compile(callBlockRegex).matcher(json);
                                    while (m.find()) {
                                        String lineBlock = m.group(1);
                                        String sjBlock = m.group(2);
                                        String code = extractFirst(lineBlock, "\"publicCode\"\\s*:\\s*\"([^\"]+)\"");
                                        if (code != null && code.equalsIgnoreCase(routeCode)) {
                                            String sjId = extractFirst(sjBlock, "\"id\"\\s*:\\s*\"([^\"]+)\"");
                                            if (sjId != null) { anyServiceJourneyId = sjId; lineName = extractFirst(lineBlock, "\"name\"\\s*:\\s*\"([^\"]+)\""); break; }
                                        }
                                    }
                                    if (anyServiceJourneyId != null) break;
                                } catch (Exception ignored) {}
                            }

                            if (anyServiceJourneyId == null) {
                                System.out.println("Fant ingen aktiv serviceJourney i Østfold for rute «" + routeCode + "» nå.");
                                break;
                            }

                            var orderedStops = fetchJourneyPatternStops(anyServiceJourneyId);
                            if (orderedStops.isEmpty()) {
                                System.out.println("Fant ikke stoppsekvens for ruten «" + routeCode + "» (sj=" + anyServiceJourneyId + ").");
                                break;
                            }

                            System.out.println("🧭 Østfold — rute " + routeCode + (lineName != null ? (" — " + lineName) : "") +
                                    " (start→slutt) — totalt " + orderedStops.size() + " stopp:");
                            int i = 1; for (var s : orderedStops) System.out.println("  " + (i++) + ") " + s.name + " — " + s.quayId);
                            System.out.println("Merk: Sekvens fra journeyPattern til en aktuell serviceJourney.");
                        } catch (Exception e) {
                            System.err.println("❌ Feil: " + e.getMessage());
                        }
                    }

                    case "15" -> { // Østfold: neste avgang fra start → alle stopp
                        System.out.print("Rutenummer (publicCode, f.eks. 34): ");
                        String routeCode = sc.nextLine().trim();
                        if (routeCode.isEmpty()) { System.out.println("Ingen rutenummer oppgitt."); break; }
                        System.out.print("Hvor mange quays i Østfold skal skannes [250]: ");
                        String scanStr = sc.nextLine().trim();
                        int scanLimit = 250; if (!scanStr.isEmpty()) try { scanLimit = Integer.parseInt(scanStr); } catch (Exception ignored) {}

                        final String OST_LAT = "59.27";
                        final String OST_LON = "11.02";
                        final int OST_RADIUS_KM = 55;

                        try {
                            enturClient client = new enturClient(ET_CLIENT_NAME);
                            var candidates = buildOstfoldQuayCandidates(client, currentUserId, favs, scanLimit, OST_LAT, OST_LON, OST_RADIUS_KM);
                            if (candidates.isEmpty()) { System.out.println("(fant ingen stopp å skanne i Østfold)"); break; }

                            String bestSjId = null, bestLineName = null, bestStartStopName = null, bestStartQuay = null;
                            OffsetDateTime bestWhen = null;

                            for (String qid : candidates) {
                                String depJson;
                                try { depJson = fetchDeparturesWithServiceJourney(qid, 60); } catch (Exception e) { continue; }

                                Pattern callPat = Pattern.compile(
                                        "\\{[\\s\\S]*?\"expectedDepartureTime\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"line\"\\s*:\\s*\\{([\\s\\S]*?)\\}[\\s\\S]*?\"serviceJourney\"\\s*:\\s*\\{([\\s\\S]*?)\\}",
                                        Pattern.DOTALL
                                );
                                Matcher m = callPat.matcher(depJson);
                                while (m.find()) {
                                    String ts = m.group(1);
                                    String lineBlock = m.group(2);
                                    String sjBlock = m.group(3);
                                    String code = extractFirst(lineBlock, "\"publicCode\"\\s*:\\s*\"([^\"]+)\"");
                                    if (code == null || !code.equalsIgnoreCase(routeCode)) continue;
                                    OffsetDateTime when; try { when = OffsetDateTime.parse(ts); } catch (Exception ex) { continue; }
                                    if (when.isBefore(OffsetDateTime.now())) continue;
                                    String sjId = extractFirst(sjBlock, "\"id\"\\s*:\\s*\"([^\"]+)\"");
                                    if (sjId == null) continue;
                                    var seq = fetchJourneyPatternStops(sjId);
                                    if (seq.isEmpty()) continue;
                                    var first = seq.get(0);
                                    if (!qid.equals(first.quayId)) continue;
                                    if (bestWhen == null || when.isBefore(bestWhen)) {
                                        bestWhen = when; bestSjId = sjId;
                                        bestLineName = extractFirst(lineBlock, "\"name\"\\s*:\\s*\"([^\"]+)\"");
                                        bestStartStopName = first.name; bestStartQuay = first.quayId;
                                    }
                                }
                            }

                            if (bestSjId == null) {
                                System.out.println("Fant ingen kommende avganger for rute «" + routeCode + "» som starter på startstopp i Østfold.");
                                break;
                            }

                            var fullSeq = fetchJourneyPatternStops(bestSjId);
                            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
                            System.out.println("🧭 Østfold — rute " + routeCode + (bestLineName != null ? (" — " + bestLineName) : "")
                                    + " — neste avgang fra: " + (bestStartStopName != null ? bestStartStopName : bestStartQuay)
                                    + " kl. " + fmt.format(bestWhen.atZoneSameInstant(ZoneId.systemDefault())));
                            System.out.println("Stopp (start→slutt), totalt " + fullSeq.size() + ":");
                            int i = 1; for (var s : fullSeq) System.out.println("  " + (i++) + ") " + s.name + " — " + s.quayId);
                        } catch (Exception e) {
                            System.err.println("❌ Feil: " + e.getMessage());
                        }
                    }

                    case "16" -> { // Antall stopp pr rute (publicCode) — prøv Line først, fallback Route
                        System.out.print("Rutenummer (publicCode, f.eks. 34): ");
                        String routeCode = sc.nextLine().trim();
                        if (routeCode.isEmpty()) { System.out.println("Ingen rutenummer oppgitt."); break; }
                        try {
                            String jsonLines = fetchLinesByPublicCode(routeCode);
                            boolean linesEmpty = jsonLines.contains("\"lines\":[]") || !jsonLines.contains("\"lines\":");
                            if (linesEmpty) {
                                String jsonRoutes = fetchRoutesByPublicCode(routeCode);
                                printStopCountsFromRoutes(jsonRoutes);
                            } else {
                                printStopCountsPerRoute(jsonLines);
                            }
                        } catch (Exception e) {
                            System.err.println("❌ Feil ved oppslag: " + e.getMessage());
                        }
                    }

                    case "17" -> { // HYBRID: Neste avgang (navn/ID → navn/ID)
                        System.out.print("Fra (navn ELLER NSR:StopPlace/Quay): ");
                        String from = sc.nextLine().trim();
                        System.out.print("Til  (navn ELLER NSR:StopPlace/Quay): ");
                        String to = sc.nextLine().trim();

                        try {
                            boolean fromIsId = from.matches("^NSR:(StopPlace|Quay):\\d+$");
                            boolean toIsId   = to.matches("^NSR:(StopPlace|Quay):\\d+$");

                            if (!fromIsId) {
                                // Løft navn → StopPlace
                                enturClient ec = new enturClient(ET_CLIENT_NAME);
                                String fromId = liftNameToStopPlace(ec, from);
                                if (fromId == null) { System.out.println("Fant ikke startstopp: " + from); break; }
                                from = fromId;
                                fromIsId = true;
                            }
                            if (!toIsId) {
                                enturClient ec = new enturClient(ET_CLIENT_NAME);
                                String toId = liftNameToStopPlace(ec, to);
                                if (toId == null) { System.out.println("Fant ikke sluttstopp: " + to); break; }
                                to = toId;
                                toIsId = true;
                            }

                            // Nå har vi NSR-id på begge → kall enkel NextDeparture (inline GraphQL her)
                            var trip = findNextTripInline(from, to);
                            if (trip == null) System.out.println("(ingen reise funnet i søkevinduet)");
                            else {
                                System.out.println("Neste avgang:");
                                System.out.println("  " + trip);
                            }
                        } catch (Exception e) {
                            System.err.println("❌ " + e.getMessage());
                        }
                    }

                    case "0" -> { loop = false; System.out.println("Programmet avsluttes."); }

                    default -> System.out.println("Ugyldig valg.");
                }
            }
        } catch (Exception e) {
            System.err.println("Feil: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("💾 JDBC URL = " + jdbcUrl);
        System.out.println("📁 Finnes DB? " + new java.io.File(dbPath).exists());
    }

    private static String normalizeQuayId(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.matches("^\\d+$")) return "NSR:Quay:" + s;
        return s;
    }

    // === Hjelpere for enkel JSON-uttrekk uten libs ===
    private static String extractFirst(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Holder både navn og neste avgangstid (OffsetDateTime). */
    private static final class RouteInfo {
        final String name; final OffsetDateTime next;
        RouteInfo(String name, OffsetDateTime next) { this.name = name; this.next = next; }
    }

    /** publicCode -> (name, neste expectedDepartureTime) fra JSON. */
    private static LinkedHashMap<String, RouteInfo> extractRoutesWithNameAndNext(String json) {
        var map = new LinkedHashMap<String, RouteInfo>();
        Pattern callPat = Pattern.compile("\"expectedDepartureTime\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"line\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
        Matcher callMatcher = callPat.matcher(json);
        Pattern pubCodePat = Pattern.compile("\"publicCode\"\\s*:\\s*\"([^\"]+)\"");
        Pattern namePat = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        while (callMatcher.find()) {
            String ts = callMatcher.group(1);
            String lineBlock = callMatcher.group(2);
            Matcher mCode = pubCodePat.matcher(lineBlock);
            if (!mCode.find()) continue;
            String code = mCode.group(1);
            String name = null;
            Matcher mName = namePat.matcher(lineBlock);
            if (mName.find()) name = mName.group(1);
            OffsetDateTime when; try { when = OffsetDateTime.parse(ts); } catch (Exception e) { when = null; }
            if (!map.containsKey(code)) map.put(code, new RouteInfo(name, when));
            else {
                RouteInfo old = map.get(code);
                if (when != null && old.next != null) {
                    if (when.isBefore(old.next)) map.put(code, new RouteInfo(name != null ? name : old.name, when));
                } else if (when != null && old.next == null) {
                    map.put(code, new RouteInfo(name != null ? name : old.name, when));
                } else if (when == null && old.next == null && name != null && (old.name == null || old.name.isBlank())) {
                    map.put(code, new RouteInfo(name, null));
                }
            }
        }
        return map;
    }

    /** Enkel geocoder-trefflinje (id + navn). */
    private static final class StopHit {
        final String id; final String name;
        StopHit(String id, String name) { this.id = id; this.name = name; }
    }

    // Struktur for stopp-sekvens
    private static final class SeqStop {
        final String quayId; final String name;
        SeqStop(String quayId, String name) { this.quayId = quayId; this.name = name; }
    }

    /** Parser GeoJSON-tekst fra Entur geocoder → liste av StopHit. */
    private static ArrayList<StopHit> extractStopsFromGeocoder(String json) {
        var out = new ArrayList<StopHit>();
        if (json == null || json.isBlank()) return out;
        Pattern propsPat = Pattern.compile("\"properties\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
        Matcher propsM = propsPat.matcher(json);
        Pattern idPat = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
        Pattern namePat = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        while (propsM.find()) {
            String block = propsM.group(1);
            String id = null, name = null;
            Matcher mId = idPat.matcher(block); if (mId.find()) id = mId.group(1);
            Matcher mName = namePat.matcher(block); if (mName.find()) name = mName.group(1);
            if (id != null && name != null) out.add(new StopHit(id, name));
        }
        return out;
    }

    /** Plukk ut alle expectedDepartureTime for en gitt rute (publicCode) fra quay-JSON. */
    private static ArrayList<OffsetDateTime> extractTimesForRoute(String json, String publicCode, int maxCount) {
        var out = new ArrayList<OffsetDateTime>();
        if (json == null || json.isBlank() || publicCode == null || publicCode.isBlank()) return out;
        if (maxCount <= 0) maxCount = 10;
        Pattern callsArrPat = Pattern.compile("\"estimatedCalls\"\\s*:\\s*\\[([\\s\\S]*?)\\]");
        Matcher callsM = callsArrPat.matcher(json);
        if (!callsM.find()) return out;
        String callsBlock = callsM.group(1);
        Pattern singlePat = Pattern.compile("\"expectedDepartureTime\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"line\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
        Matcher m = singlePat.matcher(callsBlock);
        Pattern codePat = Pattern.compile("\"publicCode\"\\s*:\\s*\"([^\"]+)\"");
        while (m.find() && out.size() < maxCount) {
            String ts = m.group(1);
            String lineBlock = m.group(2);
            Matcher mc = codePat.matcher(lineBlock);
            if (mc.find()) {
                String code = mc.group(1);
                if (publicCode.equalsIgnoreCase(code)) {
                    try { out.add(OffsetDateTime.parse(ts)); } catch (Exception ignored) {}
                }
            }
        }
        return out;
    }

    // === GraphQL-kall direkte fra Main ===

    /** Henter departures for en Quay, inkl. serviceJourney.id (brukes i 14/15). */
    private static String fetchDeparturesWithServiceJourney(String quayId, int limit) throws Exception {
        if (quayId == null || quayId.isBlank()) throw new IllegalArgumentException("quayId mangler");
        if (limit <= 0) limit = 10;

        String query = """
            query ($quayId: String!, $limit: Int!) {
              quay(id: $quayId) {
                id
                name
                estimatedCalls(timeRange: 72000, numberOfDepartures: $limit) {
                  expectedDepartureTime
                  destinationDisplay { frontText }
                  serviceJourney {
                    id
                    journeyPattern { line { id name publicCode transportMode } }
                  }
                }
              }
            }
        """;

        String body = "{"
                + "\"query\":" + jsonEscape(query) + ","
                + "\"variables\":{"
                + "\"quayId\":" + jsonEscape(quayId) + ","
                + "\"limit\":" + limit
                + "}"
                + "}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(JP_ENDPOINT))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("ET-Client-Name", ET_CLIENT_NAME)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("Entur JP " + resp.statusCode() + ": " + resp.body());
        return resp.body();
    }

    /** Hent full stopp-sekvens (start→slutt) for en serviceJourney via journeyPattern. */
    private static ArrayList<SeqStop> fetchJourneyPatternStops(String serviceJourneyId) throws Exception {
        var out = new ArrayList<SeqStop>();
        if (serviceJourneyId == null || serviceJourneyId.isBlank()) return out;

        String query = """
            query($id: String!) {
              serviceJourney(id: $id) {
                journeyPattern {
                  pointsOnLink {
                    stopPoint {
                      quay { id }
                      stopPlace { name }
                    }
                  }
                }
              }
            }
        """;

        String body = "{"
                + "\"query\":" + jsonEscape(query) + ","
                + "\"variables\":{\"id\":" + jsonEscape(serviceJourneyId) + "}"
                + "}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(JP_ENDPOINT))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("ET-Client-Name", ET_CLIENT_NAME)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("journeyPattern " + resp.statusCode() + ": " + resp.body());
        String json = resp.body();

        Pattern p = Pattern.compile(
                "\"quay\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"(NSR:Quay:\\d+)\"\\s*\\}[\\s\\S]*?\"stopPlace\"\\s*:\\s*\\{[\\s\\S]*?\"name\"\\s*:\\s*\"([^\"]+)\"",
                Pattern.DOTALL
        );
        Matcher m = p.matcher(json);
        while (m.find()) {
            String qid = m.group(1);
            String name = m.group(2);
            out.add(new SeqStop(qid, name));
        }
        return out;
    }

    /** Bygg Østfold-kandidater (favoritter + region-søk). */
    private static ArrayList<String> buildOstfoldQuayCandidates(
            enturClient client,
            Long currentUserId,
            favoritesDao favs,
            int scanLimit,
            String OST_LAT,
            String OST_LON,
            int OST_RADIUS_KM
    ) throws Exception {
        var candidates = new ArrayList<String>();
        if (currentUserId != null) {
            try {
                var favList = favs.listFavorites(currentUserId);
                for (var fav : favList) {
                    String qid = client.resolveQuayId(fav);
                    if (qid != null && qid.startsWith("NSR:Quay:")) candidates.add(qid);
                }
            } catch (Exception ignored) {}
        }
        if (candidates.size() < scanLimit) {
            String body = client.searchStopsInRegion("a", scanLimit, OST_LAT, OST_LON, Math.max(1, OST_RADIUS_KM) * 1000);
            var hits = extractStopsFromGeocoder(body);
            hits.removeIf(h -> !(h.id.startsWith("NSR:StopPlace:") || h.id.startsWith("NSR:Quay:")));
            for (var h : hits) {
                if (candidates.size() >= scanLimit) break;
                String qid = client.resolveQuayId(h.id);
                if (qid != null && qid.startsWith("NSR:Quay:")) candidates.add(qid);
            }
        }
        return candidates;
    }

    /** Hent alle lines som matcher publicCode → routes → journeyPatterns → pointsOnLink. */
    private static String fetchLinesByPublicCode(String publicCode) throws Exception {
        String query = """
            query($publicCode: String!) {
              lines(publicCode: $publicCode) {
                id
                name
                publicCode
                transportMode
                routes {
                  id
                  directionType
                  journeyPatterns {
                    id
                    name
                    pointsOnLink {
                      stopPoint {
                        quay { id }
                        stopPlace { name }
                      }
                    }
                  }
                }
              }
            }
        """;

        String body = "{"
                + "\"query\":" + jsonEscape(query) + ","
                + "\"variables\":{\"publicCode\":" + jsonEscape(publicCode) + "}"
                + "}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(JP_ENDPOINT))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("ET-Client-Name", ET_CLIENT_NAME)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("Entur JP " + resp.statusCode() + ": " + resp.body());
        return resp.body();
    }

    /** Hent alle routes som matcher publicCode → journeyPatterns → pointsOnLink (fallback når Line er tom). */
    private static String fetchRoutesByPublicCode(String publicCode) throws Exception {
        String query = """
            query($publicCode: String!) {
              routes(publicCode: $publicCode) {
                id
                directionType
                line { id name publicCode transportMode }
                journeyPatterns {
                  id
                  name
                  pointsOnLink {
                    stopPoint { quay { id } stopPlace { name } }
                  }
                }
              }
            }
        """;

        String body = "{"
                + "\"query\":" + jsonEscape(query) + ","
                + "\"variables\":{\"publicCode\":" + jsonEscape(publicCode) + "}"
                + "}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(JP_ENDPOINT))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("ET-Client-Name", ET_CLIENT_NAME)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("Entur JP " + resp.statusCode() + ": " + resp.body());
        return resp.body();
    }

    /** Parse JSON (regex) og skriv antall stopp per journeyPattern — for Lines-svaret. */
    private static void printStopCountsPerRoute(String json) {
        Pattern linePat = Pattern.compile(
                "\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"name\"\\s*:\\s*\"([^\"]*)\"[\\s\\S]*?\"publicCode\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"routes\"\\s*:\\s*\\[(.*?)\\]\\s*\\}",
                Pattern.DOTALL
        );
        Matcher lineM = linePat.matcher(json);
        boolean any = false;
        while (lineM.find()) {
            any = true;
            String lineId = lineM.group(1);
            String lineName = lineM.group(2);
            String pubCode = lineM.group(3);
            String routesBlock = lineM.group(4);
            System.out.println("\n📚 Linje " + pubCode + (lineName.isBlank() ? "" : (" — " + lineName)) + " (" + lineId + ")");

            Pattern routePat = Pattern.compile(
                    "\\{[\\s\\S]*?\"id\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"directionType\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"journeyPatterns\"\\s*:\\s*\\[(.*?)\\]\\s*\\}",
                    Pattern.DOTALL
            );
            Matcher routeM = routePat.matcher(routesBlock);

            while (routeM.find()) {
                String routeId = routeM.group(1);
                String direction = routeM.group(2);
                String jpBlock = routeM.group(3);
                System.out.println("  ▶ Retning: " + direction + " (routeId=" + routeId + ")");

                Pattern jpPat = Pattern.compile(
                        "\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"name\"\\s*:\\s*\"([^\"]*)\"[\\s\\S]*?\"pointsOnLink\"\\s*:\\s*\\[(.*?)\\]\\s*\\}",
                        Pattern.DOTALL
                );
                Matcher jpM = jpPat.matcher(jpBlock);

                int jpIdx = 0, maxStops = 0, sumStops = 0;
                while (jpM.find()) {
                    jpIdx++;
                    String jpId = jpM.group(1);
                    String jpName = jpM.group(2);
                    String polBlock = jpM.group(3);

                    int count = 0;
                    Matcher qM = Pattern.compile("\"quay\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"NSR:Quay:\\d+\"\\s*\\}", Pattern.DOTALL).matcher(polBlock);
                    while (qM.find()) count++;

                    maxStops = Math.max(maxStops, count);
                    sumStops += count;
                    System.out.println("     • JP " + jpIdx + " (" + jpId + (jpName.isBlank() ? "" : ", " + jpName) + "): " + count + " stopp");
                }
                if (jpIdx > 0) {
                    double avg = (double) sumStops / jpIdx;
                    System.out.printf("     ↳ Oppsummering retning %s: varianter=%d, maks=%d, snitt=%.1f%n", direction, jpIdx, maxStops, avg);
                } else System.out.println("     (ingen journeyPatterns i denne retningen)");
            }
        }
        if (!any) System.out.println("Fant ingen linjer for angitt publicCode.");
    }

    /** Parse JSON (regex) og skriv antall stopp per journeyPattern — for Routes-fallback. */
    private static void printStopCountsFromRoutes(String json) {
        Pattern routePat = Pattern.compile(
                "\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"directionType\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"line\"\\s*:\\s*\\{([\\s\\S]*?)\\}[\\s\\S]*?\"journeyPatterns\"\\s*:\\s*\\[(.*?)\\]\\s*\\}",
                Pattern.DOTALL
        );
        Matcher routeM = routePat.matcher(json);
        boolean any = false;
        while (routeM.find()) {
            any = true;
            String routeId = routeM.group(1);
            String direction = routeM.group(2);
            String lineBlock = routeM.group(3);
            String jpBlock = routeM.group(4);

            String pubCode = extractFirst(lineBlock, "\"publicCode\"\\s*:\\s*\"([^\"]+)\"");
            String lineName = extractFirst(lineBlock, "\"name\"\\s*:\\s*\"([^\"]*)\"");
            String lineId = extractFirst(lineBlock, "\"id\"\\s*:\\s*\"([^\"]+)\"");

            System.out.println("\n📚 Linje " + (pubCode != null ? pubCode : "?")
                    + (lineName != null && !lineName.isBlank() ? (" — " + lineName) : "")
                    + " (" + (lineId != null ? lineId : "?") + ")");
            System.out.println("  ▶ Retning: " + direction + " (routeId=" + routeId + ")");

            Pattern jpPat = Pattern.compile(
                    "\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"name\"\\s*:\\s*\"([^\"]*)\"[\\s\\S]*?\"pointsOnLink\"\\s*:\\s*\\[(.*?)\\]\\s*\\}",
                    Pattern.DOTALL
            );
            Matcher jpM = jpPat.matcher(jpBlock);

            int jpIdx = 0, maxStops = 0, sumStops = 0;
            while (jpM.find()) {
                jpIdx++;
                String jpId = jpM.group(1);
                String jpName = jpM.group(2);
                String polBlock = jpM.group(3);

                int count = 0;
                Matcher qM = Pattern.compile("\"quay\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"NSR:Quay:\\d+\"\\s*\\}", Pattern.DOTALL).matcher(polBlock);
                while (qM.find()) count++;

                maxStops = Math.max(maxStops, count);
                sumStops += count;
                System.out.println("     • JP " + jpIdx + " (" + jpId + (jpName.isBlank() ? "" : ", " + jpName) + "): " + count + " stopp");
            }
            if (jpIdx > 0) {
                double avg = (double) sumStops / jpIdx;
                System.out.printf("     ↳ Oppsummering: varianter=%d, maks=%d, snitt=%.1f%n", jpIdx, maxStops, avg);
            } else System.out.println("     (ingen journeyPatterns på denne ruten)");
        }
        if (!any) System.out.println("Fant ingen ruter (hverken Line eller Route) for angitt publicCode.");
    }

    // enkel JSON-escape (samme stil som i enturClient)
    private static String jsonEscape(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int)c)); else sb.append(c); }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ====== HYBRID: finn neste avgang inline (brukes av case 17) ======
    private static class NextTripView {
        final String lineCode, lineName, fromName, toName, depIso, arrIso;
        NextTripView(String c, String n, String f, String t, String d, String a) {
            lineCode = c; lineName = n; fromName = f; toName = t; depIso = d; arrIso = a;
        }
        @Override public String toString() {
            String dep = hhmm(depIso), arr = hhmm(arrIso);
            return String.format("%s%s  %s (%s) → %s (%s)",
                    lineCode != null ? lineCode : "—",
                    (lineName != null && !lineName.isBlank()) ? " — " + lineName : "",
                    dep, fromName != null ? fromName : "",
                    arr, toName   != null ? toName   : "");
        }
        private String hhmm(String iso) {
            if (iso == null) return "";
            Matcher m = Pattern.compile("T(\\d\\d:\\d\\d)").matcher(iso);
            return m.find() ? m.group(1) : iso;
        }
    }

    private static NextTripView findNextTripInline(String fromId, String toId) throws Exception {
        String nowIso = java.time.OffsetDateTime.now().toString();
        int windowSeconds = 36 * 3600; // 36 timer — fanger også "i morgen"

        String query = """
            query($from:String!, $to:String!, $num:Int!, $dt:DateTime!, $win:Int!) {
              trip(
                from: { place: { id: $from } }
                to:   { place: { id: $to   } }
                dateTime: $dt
                searchWindow: $win
                arriveBy: false
                numTripPatterns: $num
              ) {
                tripPatterns {
                  expectedStartTime
                  expectedEndTime
                  legs {
                    line { publicCode name }
                    fromEstimatedCall { expectedDepartureTime stopPlace { name } }
                    toEstimatedCall   { expectedArrivalTime   stopPlace { name } }
                  }
                }
              }
            }
        """;

        String body = "{"
                + "\"query\":" + jsonEscape(query) + ","
                + "\"variables\":{"
                + "\"from\":" + jsonEscape(fromId) + ","
                + "\"to\":"   + jsonEscape(toId)   + ","
                + "\"num\":1,"
                + "\"dt\":"   + jsonEscape(nowIso) + ","
                + "\"win\":"  + windowSeconds
                + "}"
                + "}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(JP_ENDPOINT))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("ET-Client-Name", ET_CLIENT_NAME)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("Entur JP " + resp.statusCode() + ": " + resp.body());
        String json = resp.body();

        // Parse første tripPattern
        Matcher mTrip = Pattern.compile(
                "\\{\\s*\"expectedStartTime\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"expectedEndTime\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"legs\"\\s*:\\s*\\[(.*?)\\]\\s*\\}",
                Pattern.DOTALL).matcher(json);
        if (!mTrip.find()) return null;

        String depIso = mTrip.group(1);
        String arrIso = mTrip.group(2);
        String legsB  = mTrip.group(3);

        Matcher mLeg = Pattern.compile(
                "\\{[\\s\\S]*?\"line\"\\s*:\\s*\\{(.*?)\\}[\\s\\S]*?\"fromEstimatedCall\"\\s*:\\s*\\{(.*?)\\}[\\s\\S]*?\"toEstimatedCall\"\\s*:\\s*\\{(.*?)\\}[\\s\\S]*?\\}",
                Pattern.DOTALL).matcher(legsB);

        String code = null, lname = null, fromName = null, toName = null;
        if (mLeg.find()) {
            String lineB = mLeg.group(1);
            String fromB = mLeg.group(2);
            String toB   = mLeg.group(3);
            code     = extractFirst(lineB, "\"publicCode\"\\s*:\\s*\"([^\"]+)\"");
            lname    = extractFirst(lineB, "\"name\"\\s*:\\s*\"([^\"]*)\"");
            fromName = extractFirst(fromB, "\"stopPlace\"\\s*:\\s*\\{[\\s\\S]*?\"name\"\\s*:\\s*\"([^\"]*)\"");
            toName   = extractFirst(toB,   "\"stopPlace\"\\s*:\\s*\\{[\\s\\S]*?\"name\"\\s*:\\s*\"([^\"]*)\"");
        }
        return new NextTripView(code, lname, fromName, toName, depIso, arrIso);
    }

    /** Løft «navn» → første StopPlace-id i regionen (brukes av case 17). */
    private static String liftNameToStopPlace(enturClient ec, String name) throws Exception {
        String geo = ec.searchStopsInRegion(name, 10, REGION_LAT, REGION_LON, Math.max(1, REGION_RADIUS_KM) * 1000);
        Matcher m = Pattern.compile("\"properties\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL).matcher(geo);
        while (m.find()) {
            String block = m.group(1);
            String id = extractFirst(block, "\"id\"\\s*:\\s*\"([^\"]+)\"");
            if (id != null && id.startsWith("NSR:StopPlace:")) return id;
        }
        return null;
    }
}

