package no.hia.oblig4;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Finner direkte turer mellom to NSR:Quay-id-er.
 * Gir også antall stopp før ankomst og estimert reisetid.
 */
public class DirectTripFinder {

    /** Alle stopp gruppert per service_journey_id */
    private final Map<String, List<Map<String, Object>>> byService = new HashMap<>();

    /** Info om én direkte tur fra A til B */
    public static class Trip {
        public final String serviceJourneyId;
        public final String line;
        public final String direction;
        public final String departureTime;   // ved start
        public final String arrivalTime;     // ved stopp
        public final int stopsBeforeDest;    // antall stopp mellom start og stopp (utenom dest)
        public final int durationMinutes;    // estimert reisetid i minutter (eller -1 hvis ukjent)

        public Trip(String serviceJourneyId,
                    String line,
                    String direction,
                    String departureTime,
                    String arrivalTime,
                    int stopsBeforeDest,
                    int durationMinutes) {

            this.serviceJourneyId = serviceJourneyId;
            this.line = line;
            this.direction = direction;
            this.departureTime = departureTime;
            this.arrivalTime = arrivalTime;
            this.stopsBeforeDest = stopsBeforeDest;
            this.durationMinutes = durationMinutes;
        }
    }

    public DirectTripFinder(String journeysJsonPath) throws Exception {
        String json = Files.readString(Path.of(journeysJsonPath), StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) StopSearch.MiniJson.parse(json);

        // grupper per service_journey_id
        for (Map<String, Object> row : rows) {
            String sid = (String) row.get("service_journey_id");
            if (sid == null) continue;
            byService.computeIfAbsent(sid, k -> new ArrayList<>()).add(row);
        }

        // sorter hver service etter stop_sequence
        for (List<Map<String, Object>> list : byService.values()) {
            list.sort(Comparator.comparingInt(m -> asInt(m.get("stop_sequence"))));
        }
    }

    /**
     * Finn alle direkte turer fra startQuayId til stopQuayId
     * (dvs. samme service_journey_id, og start kommer før stopp).
     */
    public List<Trip> findTrips(String startQuayId, String stopQuayId) {
        List<Trip> trips = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> e : byService.entrySet()) {
            String sid = e.getKey();
            List<Map<String, Object>> stops = e.getValue();

            Integer startIdx = null;
            Integer stopIdx  = null;

            for (int i = 0; i < stops.size(); i++) {
                String qid = (String) stops.get(i).get("quay_id");
                if (qid == null) continue;
                if (qid.equals(startQuayId) && startIdx == null) {
                    startIdx = i;
                }
                if (qid.equals(stopQuayId)) {
                    stopIdx = i;
                }
            }

            // krever at bussen besøker start før stopp
            if (startIdx == null || stopIdx == null || startIdx >= stopIdx) continue;

            Map<String, Object> startRow = stops.get(startIdx);
            Map<String, Object> stopRow  = stops.get(stopIdx);

            String dep = toStr(startRow.get("departure_time"));
            String arr = toStr(stopRow.get("arrival_time"));

            // antall stopp før dest (mellom A og B)
            int stopsBeforeDest = Math.max(0, stopIdx - startIdx - 1);

            // linje og retning
            String line = extractLineNumber(sid);
            String direction = null;

            Object extra1Stop = stopRow.get("extra1");
            if (extra1Stop != null) {
                direction = extra1Stop.toString();
            } else {
                // prøv å finne noe extra1 i samme journey
                for (Map<String, Object> r : stops) {
                    if (r.get("extra1") != null) {
                        direction = r.get("extra1").toString();
                        break;
                    }
                }
            }
            if (direction == null) {
                direction = "ukjent retning";
            }

            int durationMinutes = -1;
            if (dep != null && arr != null) {
                Integer depSec = parseTimeToSeconds(dep);
                Integer arrSec = parseTimeToSeconds(arr);
                if (depSec != null && arrSec != null) {
                    int diff = arrSec - depSec;
                    if (diff < 0) diff += 24 * 3600; // hvis det ruller over midnatt
                    durationMinutes = diff / 60;
                }
            }

            trips.add(new Trip(sid, line, direction, dep, arr, stopsBeforeDest, durationMinutes));
        }

        // sorter på avgangstid
        trips.sort(Comparator.comparing(t ->
                t.departureTime == null ? "99:99:99" : t.departureTime));
        return trips;
    }

    private static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String s) return Integer.parseInt(s.trim());
        throw new RuntimeException("Forventet heltall, fikk: " + o);
    }

    private static String toStr(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer parseTimeToSeconds(String t) {
        if (t == null) return null;
        String[] parts = t.split(":");
        try {
            int h, m, s;
            if (parts.length == 2) {
                h = Integer.parseInt(parts[0]);
                m = Integer.parseInt(parts[1]);
                s = 0;
            } else if (parts.length == 3) {
                h = Integer.parseInt(parts[0]);
                m = Integer.parseInt(parts[1]);
                s = Integer.parseInt(parts[2]);
            } else {
                return null;
            }
            return h * 3600 + m * 3600 / 60 + s;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Trekker linjenummer ut fra f.eks. "OST:ServiceJourney:630_2503..." → "630". */
    private String extractLineNumber(String serviceId) {
        try {
            String[] parts = serviceId.split(":");
            if (parts.length < 3) return "?";
            String tail = parts[2];        // f.eks. "630_2503..."
            String[] tailParts = tail.split("_");
            return tailParts[0];           // "630"
        } catch (Exception e) {
            return "?";
        }
    }
}
