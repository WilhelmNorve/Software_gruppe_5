package no.hia.oblig4;

import java.net.http.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Enkel Entur-klient uten eksterne JSON-bibliotek. */
public class EnturClient {
    private static final String JP_ENDPOINT  = "https://api.entur.io/journey-planner/v3/graphql";
    private static final String GEO_ENDPOINT = "https://api.entur.io/geocoder/v1/autocomplete";

    private final HttpClient http;
    private final String clientName; // brukes i ET-Client-Name-header

    public EnturClient(String clientName) {
        this.clientName = clientName;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Hent avganger for en Quay (returnerer rå JSON fra Journey Planner). */
    public String fetchDepartures(String quayId, int limit) throws Exception {
        if (quayId == null || quayId.isBlank()) throw new IllegalArgumentException("quayId mangler");
        if (limit <= 0) limit = 10;

        String query = """
            query ($quayId: String!, $limit: Int!) {
              quay(id: $quayId) {
                id
                name
                estimatedCalls(timeRange: 72000, numberOfDepartures: $limit) {
                  expectedDepartureTime
                  aimedDepartureTime
                  realtime
                  destinationDisplay { frontText }
                  serviceJourney {
                    journeyPattern {
                      line { id name publicCode transportMode }
                    }
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
                .header("ET-Client-Name", clientName)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Entur JP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /* =====================  GEOCODER (AUTOSØK)  ===================== */

    /** Praktisk overload uten fokuspunkt. */
    public String searchStops(String q, int limit) throws Exception {
        return searchStops(q, limit, null, null);
    }

    /** Autosøk med valgfritt fokuspunkt. */
    public String searchStops(String q, int limit, String lat, String lon) throws Exception {
        if (q == null || q.isBlank()) throw new IllegalArgumentException("q mangler");
        if (limit <= 0) limit = 8;

        String focus = (lat != null && lon != null)
                ? "&focus.point.lat=" + URLEncoder.encode(lat, StandardCharsets.UTF_8)
                + "&focus.point.lon=" + URLEncoder.encode(lon, StandardCharsets.UTF_8)
                : "";

        String base = GEO_ENDPOINT
                + "?text=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&lang=no"
                + "&size=" + limit
                + "&boundary.country=nor"
                + focus;

        String[] attempts = new String[] {
                base + "&layers=venue&categories=transport&sources=nsr",
                base + "&layers=venue&categories=transport",
                base
        };

        HttpResponse<String> last = null;
        for (String url : attempts) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("ET-Client-Name", clientName)
                    .GET()
                    .build();

            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            last = r;

            if (r.statusCode() / 100 != 2) continue;

            String body = r.body();
            if (body != null && body.contains("\"features\":[") && !body.contains("\"features\":[]")) {
                return body;
            }
        }

        if (last == null) throw new RuntimeException("Geocoder: ingen respons");
        if (last.statusCode() / 100 == 2) return last.body(); // 200 men tomt
        throw new RuntimeException("Entur geocoder " + last.statusCode() + ": " + last.body());
    }

    /** Autosøk begrenset til sirkel (region) i meter rundt lat/lon. */
    public String searchStopsInRegion(String q, int limit, String lat, String lon, int radiusMeters) throws Exception {
        if (q == null || q.isBlank()) throw new IllegalArgumentException("q mangler");
        if (limit <= 0) limit = 8;
        if (lat == null || lon == null) throw new IllegalArgumentException("lat/lon mangler");
        if (radiusMeters <= 0) radiusMeters = 15000; // default 15 km

        String focus =
                "&focus.point.lat=" + URLEncoder.encode(lat, StandardCharsets.UTF_8) +
                        "&focus.point.lon=" + URLEncoder.encode(lon, StandardCharsets.UTF_8);

        // boundary.circle begrenser treff til region
        String boundary =
                "&boundary.circle.lat=" + URLEncoder.encode(lat, StandardCharsets.UTF_8) +
                        "&boundary.circle.lon=" + URLEncoder.encode(lon, StandardCharsets.UTF_8) +
                        "&boundary.circle.radius=" + URLEncoder.encode(String.valueOf(radiusMeters), StandardCharsets.UTF_8);

        String base = GEO_ENDPOINT
                + "?text=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&lang=no"
                + "&size=" + limit
                + "&boundary.country=nor"
                + focus
                + boundary;

        String[] attempts = new String[] {
                base + "&layers=venue&categories=transport&sources=nsr",
                base + "&layers=venue&categories=transport",
                base
        };

        HttpResponse<String> last = null;
        for (String url : attempts) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("ET-Client-Name", clientName)
                    .GET()
                    .build();

            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            last = r;

            if (r.statusCode() / 100 != 2) continue;

            String body = r.body();
            if (body != null && body.contains("\"features\":[") && !body.contains("\"features\":[]")) {
                return body;
            }
        }

        if (last == null) throw new RuntimeException("Geocoder: ingen respons");
        if (last.statusCode() / 100 == 2) return last.body(); // 200 men tomt
        throw new RuntimeException("Entur geocoder " + last.statusCode() + ": " + last.body());
    }

    /* ===========  StopPlace → Quay-oppløsning (for avganger)  =========== */

    /** Tar enten NSR:StopPlace:* eller NSR:Quay:* og returnerer en Quay-id. */
    public String resolveQuayId(String stopOrQuayId) throws Exception {
        if (stopOrQuayId == null || stopOrQuayId.isBlank()) return null;
        if (stopOrQuayId.startsWith("NSR:Quay:")) return stopOrQuayId;

        String query = """
          query($id: String!) {
            stopPlace(id: $id) { quays { id } }
          }
        """;
        String body = "{"
                + "\"query\":" + jsonEscape(query) + ","
                + "\"variables\":{\"id\":" + jsonEscape(stopOrQuayId) + "}"
                + "}";

        HttpRequest req = HttpRequest.newBuilder(URI.create(JP_ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("ET-Client-Name", clientName)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() / 100 != 2) {
            throw new RuntimeException("resolveQuayId " + r.statusCode() + ": " + r.body());
        }

        Matcher m = Pattern.compile("NSR:Quay:\\d+").matcher(r.body());
        return m.find() ? m.group() : stopOrQuayId; // fallback: returner original hvis ingen quay
    }

    /* =====================  Utils  ===================== */

    /** Liten helper for å escape JSON-strenger uten bibliotek. */
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int)c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}

