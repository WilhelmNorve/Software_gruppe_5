package no.hia.oblig4;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class NearestStopFinder {

    public static final class Stop {
        public final String id;
        public final String name;
        public final double lat;
        public final double lon;

        public Stop(String id, String name, double lat, double lon) {
            this.id = id;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private final List<Stop> stops = new ArrayList<>();

    /**
     * Leser inn alle stopp fra JSON-fila og lagrer id/navn/koordinater.
     * Forutsetter at hver entry i JSON-lista har feltene:
     *  - "id": String (NSR:Quay:xxxx)
     *  - "navn" eller "name": String
     *  - "lat": Number
     *  - "lon": Number
     */
    @SuppressWarnings("unchecked")
    public NearestStopFinder(String quaysJsonPath) throws Exception {
        String json = Files.readString(Path.of(quaysJsonPath), StandardCharsets.UTF_8);

        // Vi gjenbruker mini-JSON-parseren fra stopSearch
        List<Map<String, Object>> quays =
                (List<Map<String, Object>>) StopSearch.MiniJson.parse(json);

        for (Map<String, Object> q : quays) {
            String id   = (String) q.get("id");              // NSR:Quay:xxxx
            String navn = (String) q.getOrDefault("navn", q.get("name"));

            // JUSTER DISSE FELTENE hvis JSON-fila di bruker andre navn:
            Object latObj = q.get("lat");
            Object lonObj = q.get("lon");

            if (id == null || navn == null || latObj == null || lonObj == null) continue;

            double lat = toDouble(latObj);
            double lon = toDouble(lonObj);

            stops.add(new Stop(id, navn, lat, lon));
        }

        System.out.println("📍 nearestStopFinder: lastet " + stops.size() + " stopp med koordinater");
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }

    public boolean isEmpty() {
        return stops.isEmpty();
    }

    /**
     * Finn nærmeste stopp til (lat,lon).
     * Returnerer null hvis vi ikke har noen stopp med koordinater.
     */
    public Stop findNearest(double lat, double lon) {
        if (stops.isEmpty()) return null;

        Stop best = null;
        double bestDist = Double.MAX_VALUE;

        for (Stop s : stops) {
            double d = haversineMeters(lat, lon, s.lat, s.lon);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    /**
     * Haversine-formel: avstand i meter mellom to koordinater.
     */
    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0; // jordradius i meter
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
