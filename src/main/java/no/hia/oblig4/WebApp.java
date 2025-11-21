package no.hia.oblig4;

import static spark.Spark.*;

import java.net.ServerSocket;
import java.nio.file.Paths;
import java.sql.*;
import java.util.List;
import java.util.Locale;

public class WebApp {

    private static final String CLIENT_NAME  = "norve.oblig4/1.0 (kontakt@eksempel.no)";


    private static final String JOURNEYS_PATH = "src/main/resources/Rutetabell/Rutetider630.json";
    private static final String QUAYS_PATH    = "src/main/resources/Rutetabell/stops_630_only.json";

    private static StopSearch        MOCK_STOP_SEARCH;
    private static DirectTripFinder  MOCK_TRIP_FINDER;
    private static EnturClient       ENTUR_CLIENT;
    private static NearestStopFinder NEAREST_STOP_FINDER;

    private static final DelaySimulator DELAY_SIM = new DelaySimulator();


    private static String jsonEscape(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static int pickFreePortNear(int start) {
        for (int i = 0; i < 10; i++) {
            int cand = start + i;
            try (ServerSocket ss = new ServerSocket(cand)) {
                return cand;
            } catch (Exception ignored) {}
        }
        return start;
    }

    private static String normalizeQuayId(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.matches("^\\d+$")) return "NSR:Quay:" + s;
        return s;
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

        if (bestDist <= 3) {
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
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[n][m];
    }

    public static void main(String[] args) {

        int desired = Integer.parseInt(System.getenv().getOrDefault("PORT", "8081"));
        int p = pickFreePortNear(desired);
        port(p);

        initExceptionHandler(e -> {
            e.printStackTrace();
            System.err.println("❌ Klarte ikke å starte Spark.");
            System.exit(1);
        });

        staticFiles.location("/public");
        before((req, res) ->
                System.out.println(req.requestMethod() + " " + req.pathInfo() + " " + req.queryString()));
        after((req, res) -> System.out.println(" -> " + res.status()));

        String dbPath = Paths.get("src", "data", "app.db").toAbsolutePath().toString();
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        System.out.println("💾 Bruker database: " + jdbcUrl);

        UserAuthenticator auth    = new UserAuthenticator(jdbcUrl);
        FavoritesDao      favs    = new FavoritesDao(jdbcUrl);
        UserCreator       userCreator = new UserCreator(jdbcUrl);
        UserDeleter       userDeleter = new UserDeleter(jdbcUrl);

        try {
            MOCK_STOP_SEARCH     = new StopSearch(QUAYS_PATH);
            MOCK_TRIP_FINDER     = new DirectTripFinder(JOURNEYS_PATH);
            NEAREST_STOP_FINDER  = new NearestStopFinder(QUAYS_PATH);

            System.out.println("🅿️  Mock-stopp lastet fra: " + QUAYS_PATH);
            System.out.println("🚌 Mock-ruter lastet fra: " + JOURNEYS_PATH);
        } catch (Exception e) {
            System.err.println("❌ Klarte ikke å laste mock-data fra JSON-filene");
            e.printStackTrace();
            MOCK_STOP_SEARCH     = null;
            MOCK_TRIP_FINDER     = null;
            NEAREST_STOP_FINDER  = null;
        }

        ENTUR_CLIENT = new EnturClient(CLIENT_NAME);

        System.out.println("📡 Starter på port " + p + " (ønsket: " + desired + ")");
        System.out.println("📄 Statisk innhold fra: src/main/resources/public");


        get("/health", (req, res) -> "ok");

        post("/login", (req, res) -> {
            String username = req.queryParams("username");
            String password = req.queryParams("password");
            if (username == null || password == null) {
                res.status(400);
                return "missing-params";
            }
            Long userId = auth.authenticateAndGetId(username.trim(), password.trim());
            if (userId != null) {
                req.session(true).attribute("userId", userId);
                res.status(200);
                return "ok";
            }
            res.status(401);
            return "bad-credentials";
        });

        post("/logout", (req, res) -> {
            if (req.session(false) != null) req.session().invalidate();
            res.type("application/json; charset=utf-8");
            return "{\"status\":\"ok\"}";
        });

        post("/register", (req, res) -> {
            String username = req.queryParams("username");
            String password = req.queryParams("password");

            if (username == null || password == null ||
                    username.isBlank() || password.isBlank()) {
                res.status(400);
                return "missing-params";
            }

            username = username.trim();

            String checkSql = "SELECT 1 FROM users WHERE username = ? LIMIT 1";
            try (Connection c = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement ps = c.prepareStatement(checkSql)) {

                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        res.status(409);
                        return "username-taken";
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                res.status(500);
                return "db-error";
            }

            try {
                long id = userCreator.createUser(username, password); // TODO: hash passord i ekte system
                res.status(200);
                return "user-created-" + id;
            } catch (Exception e) {
                e.printStackTrace();
                res.status(500);
                return "could-not-create-user";
            }
        });


        post("/delete-account", (req, res) -> {
            String username = req.queryParams("username");
            String password = req.queryParams("password");

            if (username == null || password == null ||
                    username.isBlank() || password.isBlank()) {
                res.status(400);
                return "missing-params";
            }

            username = username.trim();

            try {
                if (!auth.authenticate(username, password)) {
                    res.status(401);
                    return "bad-credentials";
                }

                int rows = userDeleter.deleteByUsername(username);
                if (rows == 0) {
                    res.status(404);
                    return "user-not-found";
                }

                if (req.session(false) != null) {
                    req.session().invalidate();
                }

                res.status(200);
                return "user-deleted";
            } catch (Exception e) {
                e.printStackTrace();
                res.status(500);
                return "could-not-delete-user";
            }
        });

        get("/api/favorites", (req, res) -> {
            Long userId = req.session().attribute("userId");
            if (userId == null) {
                res.status(401);
                return "";
            }

            String sql = """
                SELECT quay_id, note, position, created_at
                FROM user_favorites
                WHERE user_id = ?
                ORDER BY COALESCE(position, 999999), created_at
            """;

            StringBuilder json = new StringBuilder(256).append('[');
            try (Connection c = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(',');
                        first = false;
                        String quay   = rs.getString("quay_id");
                        String note   = rs.getString("note");
                        Object posObj = rs.getObject("position");
                        Integer pos   = (posObj == null ? null : rs.getInt("position"));
                        String created= rs.getString("created_at");

                        json.append('{')
                                .append("\"quay_id\":").append(jsonEscape(quay)).append(',')
                                .append("\"note\":").append(note == null ? "null" : jsonEscape(note)).append(',')
                                .append("\"position\":").append(pos == null ? "null" : pos).append(',')
                                .append("\"created_at\":").append(created == null ? "null" : jsonEscape(created))
                                .append('}');
                    }
                }
            }
            json.append(']');
            res.type("application/json; charset=utf-8");
            return json.toString();
        });

        post("/api/favorites", (req, res) -> {
            Long userId = req.session().attribute("userId");
            if (userId == null) {
                res.status(401);
                return "";
            }

            String quayId = req.queryParams("quay_id");
            String note   = req.queryParams("note");
            if (quayId == null || quayId.isBlank()) {
                res.status(400);
                return "missing-quay_id";
            }

            boolean ok = favs.addFavorite(userId,
                    normalizeQuayId(quayId.trim()),
                    note == null ? null : note.trim());
            if (!ok) {
                res.status(409);
                return "duplicate-or-bad-format";
            }

            res.type("application/json; charset=utf-8");
            return "{\"status\":\"ok\"}";
        });

        post("/api/favorites/position", (req, res) -> {
            Long userId = req.session().attribute("userId");
            if (userId == null) {
                res.status(401);
                return "";
            }

            String quayId = req.queryParams("quay_id");
            String posStr = req.queryParams("position");
            if (quayId == null || posStr == null) {
                res.status(400);
                return "missing-params";
            }

            int pos;
            try {
                pos = Integer.parseInt(posStr.trim());
            } catch (NumberFormatException e) {
                res.status(400);
                return "bad-position";
            }

            boolean ok = favs.setPosition(userId,
                    normalizeQuayId(quayId.trim()), pos);
            if (!ok) {
                res.status(404);
                return "not-found-or-bad-quay";
            }

            res.type("application/json; charset=utf-8");
            return "{\"status\":\"ok\"}";
        });

            get("/api/mock/stops", (req, res) -> {

            String q = req.queryParams("q");
            if (q == null || q.isBlank()) {
                res.status(400);
                return "missing-q";
            }

            if (MOCK_STOP_SEARCH == null) {
                res.status(500);
                return "stop-search-not-available";
            }

            String term = q.trim();
            List<String> ids = MOCK_STOP_SEARCH.lookup(term);

            if (ids.isEmpty()) {
                String guess = guessKnownPlaceName(term);
                if (guess != null) {
                    System.out.println("🔤 /api/mock/stops: tolket \"" + term + "\" som \"" + guess + "\"");
                    ids = MOCK_STOP_SEARCH.lookup(guess);
                }
            }

            if (ids.isEmpty()) {
                System.out.println("❌ /api/mock/stops: ukjent sted \"" + term + "\"");
                res.status(404);
                return "unknown-place";
            }

            System.out.println("🧪 /api/mock/stops: \"" + term + "\" ga " + ids.size() + " treff");

            StringBuilder json = new StringBuilder(256);
            json.append('[');
            boolean first = true;
            for (String id : ids) {
                String name = MOCK_STOP_SEARCH.getStopName(id);
                if (name == null) continue;

                if (!first) json.append(',');
                first = false;

                json.append('{')
                        .append("\"id\":").append(jsonEscape(id)).append(',')
                        .append("\"name\":").append(jsonEscape(name))
                        .append('}');
            }
            json.append(']');

            res.type("application/json; charset=utf-8");
            return json.toString();
        });

        get("/api/mock/trips", (req, res) -> {

            String fromIdRaw = req.queryParams("fromId");
            String toIdRaw   = req.queryParams("toId");
            if (fromIdRaw == null || toIdRaw == null ||
                    fromIdRaw.isBlank() || toIdRaw.isBlank()) {
                res.status(400);
                return "missing-fromId-or-toId";
            }

            if (MOCK_TRIP_FINDER == null || MOCK_STOP_SEARCH == null) {
                res.status(500);
                return "trip-finder-or-stop-search-not-available";
            }

            String originalFromId = fromIdRaw.trim();
            String originalToId   = toIdRaw.trim();

            String effectiveFromId = originalFromId;
            String effectiveToId   = originalToId;

            List<DirectTripFinder.Trip> trips =
                    MOCK_TRIP_FINDER.findTrips(effectiveFromId, effectiveToId);

            if (trips.isEmpty()) {
                String fromName = MOCK_STOP_SEARCH.getStopName(originalFromId);
                String toName   = MOCK_STOP_SEARCH.getStopName(originalToId);

                if (fromName != null && toName != null) {
                    List<String> fromCandidates = MOCK_STOP_SEARCH.lookup(fromName);
                    List<String> toCandidates   = MOCK_STOP_SEARCH.lookup(toName);

                    boolean found = false;
                    outer:
                    for (String fId : fromCandidates) {
                        for (String tId : toCandidates) {
                            if (fId.equals(originalFromId) && tId.equals(originalToId)) continue;

                            List<DirectTripFinder.Trip> altTrips =
                                    MOCK_TRIP_FINDER.findTrips(fId, tId);
                            if (!altTrips.isEmpty()) {
                                System.out.println("🔄 /api/mock/trips: fant alternativ kombinasjon "
                                        + fId + " → " + tId + " for "
                                        + originalFromId + " → " + originalToId);
                                trips = altTrips;
                                effectiveFromId = fId;
                                effectiveToId   = tId;
                                found = true;
                                break outer;
                            }
                        }
                    }

                    if (!found) {
                        System.out.println("❌ /api/mock/trips: fant ingen alternativ kombinasjon for "
                                + originalFromId + " → " + originalToId + " (" + fromName + " → " + toName + ")");
                    }
                } else {
                    System.out.println("❌ /api/mock/trips: mangler navn for fromId/toId, kan ikke prøve alternativ retning.");
                }
            }

            String fromNameUsed = MOCK_STOP_SEARCH.getStopName(effectiveFromId);
            String toNameUsed   = MOCK_STOP_SEARCH.getStopName(effectiveToId);

            StringBuilder json = new StringBuilder(512);
            json.append('{');

            json.append("\"from\":{")
                    .append("\"id\":").append(jsonEscape(effectiveFromId)).append(',')
                    .append("\"name\":").append(jsonEscape(fromNameUsed != null ? fromNameUsed : effectiveFromId))
                    .append("},");

            json.append("\"to\":{")
                    .append("\"id\":").append(jsonEscape(effectiveToId)).append(',')
                    .append("\"name\":").append(jsonEscape(toNameUsed != null ? toNameUsed : effectiveToId))
                    .append("},");

            json.append("\"trips\":[");
            boolean first = true;
            for (DirectTripFinder.Trip t : trips) {
                if (!first) json.append(',');
                first = false;

                DelaySimulator.DelayInfo d = DELAY_SIM.getDelay(t, effectiveFromId, effectiveToId);

                json.append('{')
                        .append("\"serviceJourneyId\":").append(jsonEscape(t.serviceJourneyId)).append(',')
                        .append("\"line\":").append(jsonEscape(t.line)).append(',')
                        .append("\"direction\":").append(jsonEscape(t.direction)).append(',')
                        .append("\"departureTime\":").append(jsonEscape(t.departureTime)).append(',')
                        .append("\"arrivalTime\":").append(jsonEscape(t.arrivalTime)).append(',')
                        .append("\"stopsBeforeDest\":").append(t.stopsBeforeDest).append(',')
                        .append("\"durationMinutes\":").append(t.durationMinutes).append(',')
                        .append("\"delayMinutes\":").append(d.delayMinutes).append(',')
                        .append("\"newDeparture\":").append(d.newDeparture == null ? "null" : jsonEscape(d.newDeparture)).append(',')
                        .append("\"newArrival\":").append(d.newArrival == null ? "null" : jsonEscape(d.newArrival))
                        .append('}');
            }
            json.append(']');

            json.append('}');

            res.type("application/json; charset=utf-8");
            return json.toString();
        });


        get("/api/mock/tripsByName", (req, res) -> {

            if (MOCK_STOP_SEARCH == null || MOCK_TRIP_FINDER == null) {
                res.status(500);
                return "mock-data-not-available";
            }

            String fromName = req.queryParams("from");
            String toName   = req.queryParams("to");

            if (fromName == null || fromName.isBlank() ||
                    toName   == null || toName.isBlank()) {
                res.status(400);
                return "missing-from-or-to";
            }

            fromName = fromName.trim();
            toName   = toName.trim();

            String gFrom = guessKnownPlaceName(fromName);
            if (gFrom != null) fromName = gFrom;
            String gTo   = guessKnownPlaceName(toName);
            if (gTo != null) toName = gTo;

            List<String> fromIds = MOCK_STOP_SEARCH.lookup(fromName);
            List<String> toIds   = MOCK_STOP_SEARCH.lookup(toName);

            if (fromIds.isEmpty() || toIds.isEmpty()) {
                res.status(404);
                return "no-stops-found";
            }

            String fromId = fromIds.get(0);
            String toId   = toIds.get(0);

            List<DirectTripFinder.Trip> trips = MOCK_TRIP_FINDER.findTrips(fromId, toId);

            StringBuilder json = new StringBuilder(512);
            json.append('{');

            json.append("\"from\":{")
                    .append("\"id\":").append(jsonEscape(fromId)).append(',')
                    .append("\"name\":").append(jsonEscape(MOCK_STOP_SEARCH.getStopName(fromId)))
                    .append("},");

            json.append("\"to\":{")
                    .append("\"id\":").append(jsonEscape(toId)).append(',')
                    .append("\"name\":").append(jsonEscape(MOCK_STOP_SEARCH.getStopName(toId)))
                    .append("},");

            json.append("\"trips\":[");
            boolean first = true;
            for (DirectTripFinder.Trip t : trips) {
                if (!first) json.append(',');
                first = false;

                DelaySimulator.DelayInfo d = DELAY_SIM.getDelay(t, fromId, toId);

                json.append('{')
                        .append("\"serviceJourneyId\":").append(jsonEscape(t.serviceJourneyId)).append(',')
                        .append("\"line\":").append(jsonEscape(t.line)).append(',')
                        .append("\"direction\":").append(jsonEscape(t.direction)).append(',')
                        .append("\"departureTime\":").append(jsonEscape(t.departureTime)).append(',')
                        .append("\"arrivalTime\":").append(jsonEscape(t.arrivalTime)).append(',')
                        .append("\"stopsBeforeDest\":").append(t.stopsBeforeDest).append(',')
                        .append("\"durationMinutes\":").append(t.durationMinutes).append(',')
                        .append("\"delayMinutes\":").append(d.delayMinutes).append(',')
                        .append("\"newDeparture\":").append(d.newDeparture == null ? "null" : jsonEscape(d.newDeparture)).append(',')
                        .append("\"newArrival\":").append(d.newArrival == null ? "null" : jsonEscape(d.newArrival))
                        .append('}');
            }
            json.append(']');

            json.append('}');

            res.type("application/json; charset=utf-8");
            return json.toString();
        });

        get("/api/mock/nearestStop", (req, res) -> {

            String latStr = req.queryParams("lat");
            String lonStr = req.queryParams("lon");

            if (latStr == null || lonStr == null || latStr.isBlank() || lonStr.isBlank()) {
                res.status(400);
                return "missing-lat-or-lon";
            }

            if (NEAREST_STOP_FINDER == null || NEAREST_STOP_FINDER.isEmpty()) {
                res.status(500);
                return "nearest-stop-not-available";
            }

            double lat, lon;
            try {
                lat = Double.parseDouble(latStr.trim());
                lon = Double.parseDouble(lonStr.trim());
            } catch (NumberFormatException e) {
                res.status(400);
                return "bad-lat-or-lon";
            }

            NearestStopFinder.Stop s = NEAREST_STOP_FINDER.findNearest(lat, lon);
            if (s == null) {
                res.status(404);
                return "no-stops-have-coordinates";
            }

            StringBuilder json = new StringBuilder(128);
            json.append('{')
                    .append("\"id\":").append(jsonEscape(s.id)).append(',')
                    .append("\"name\":").append(jsonEscape(s.name)).append(',')
                    .append("\"lat\":").append(s.lat).append(',')
                    .append("\"lon\":").append(s.lon)
                    .append('}');

            res.type("application/json; charset=utf-8");
            return json.toString();
        });

        get("/api/entur/departures", (req, res) -> {

            String id = req.queryParams("quay_id");
            if (id == null || id.isBlank()) {
                res.status(400);
                return "missing-quay_id";
            }

            int limit = 10;
            try {
                String lim = req.queryParams("limit");
                if (lim != null) limit = Integer.parseInt(lim.trim());
            } catch (Exception ignored) {}

            try {
                id = normalizeQuayId(id.trim());
                String json = ENTUR_CLIENT.fetchDepartures(id, limit);
                res.type("application/json; charset=utf-8");
                return json;
            } catch (Exception e) {
                e.printStackTrace();
                res.status(502);
                return "entur-error";
            }
        });

        init();
        awaitInitialization();
        System.out.println("🚀 Server startet: http://localhost:" + p);
    }
}


