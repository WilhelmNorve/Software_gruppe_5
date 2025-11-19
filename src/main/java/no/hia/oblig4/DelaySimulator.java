package no.hia.oblig4;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Enkel simulator som lager tilfeldige forsinkelser for avganger.
 * - Nye tilfeldige forsinkelser hver gang programmet kjører
 * - Samme rute (samme tur) får samme forsinkelse innen én kjøring
 *
 * NB: Dette er MOCK-data, ikke ekte sanntid.
 */
public class DelaySimulator {

    private final Random random;
    private final Map<String, DelayInfo> cache = new HashMap<>();

    public static class DelayInfo {
        public final int delayMinutes;      // 0 = i rute, >0 = forsinket
        public final String newDeparture;   // ny avgang ved start-stopp
        public final String newArrival;     // ny ankomst ved destinasjon

        public DelayInfo(int delayMinutes, String newDeparture, String newArrival) {
            this.delayMinutes = delayMinutes;
            this.newDeparture = newDeparture;
            this.newArrival = newArrival;
        }
    }

    public DelaySimulator() {
        // Nye forsinkelser hver gang programmet starter
        this.random = new Random();
    }

    public DelaySimulator(long seed) {
        // Kan brukes for testing med deterministisk oppførsel
        this.random = new Random(seed);
    }

    /**
     * Hent (eller generer) forsinkelse for en gitt tur mellom start og dest.
     * Forsinkelsen caches, så samme tur får samme delay i én kjøring.
     */
    public DelayInfo getDelay(DirectTripFinder.Trip t, String startId, String destId) {
        if (t == null) {
            return new DelayInfo(0, null, null);
        }

        String key = makeKey(t, startId, destId);
        DelayInfo existing = cache.get(key);
        if (existing != null) {
            return existing;
        }

        int delay = randomDelayMinutes();
        String newDep = addMinutes(t.departureTime, delay);
        String newArr = addMinutes(t.arrivalTime, delay);

        DelayInfo info = new DelayInfo(delay, newDep, newArr);
        cache.put(key, info);
        return info;
    }

    /** Lager en nøkkel som identifiserer en "tur" fra A til B. */
    private String makeKey(DirectTripFinder.Trip t, String startId, String destId) {
        return (t.serviceJourneyId != null ? t.serviceJourneyId : "?") + "|" +
                (t.departureTime != null ? t.departureTime : "?") + "|" +
                (startId != null ? startId : "?") + "|" +
                (destId != null ? destId : "?");
    }

    /**
     * Tilfeldig forsinkelse:
     * - ca. 50% i rute
     * - ca. 10% +5 min
     * - ca. 10% +10 min
     * - ca. 15% +20 min
     * - ca. 15% +30 min  (disse behandles som kansellert i UI)
     */
    private int randomDelayMinutes() {
        int r = random.nextInt(20); // 0–19

        if (r < 10) {          // 0–9
            return 0;          // 50% i rute
        } else if (r < 12) {   // 10–11
            return 5;          // 10%
        } else if (r < 14) {   // 12–13
            return 10;         // 10%
        } else if (r < 17) {   // 14–16
            return 20;         // 15%
        } else {               // 17–19
            return 30;         // 15%
        }
    }

    /**
     * Legger X minutter til en tid på format HH:MM eller HH:MM:SS.
     * Ruller over midnatt hvis nødvendig.
     */
    private String addMinutes(String time, int minutes) {
        if (time == null || minutes == 0) return time;
        String[] parts = time.split(":");
        if (parts.length < 2) return time;

        int h = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);

        int total = h * 60 + m + minutes;
        // håndterer rulling over midnatt
        total = Math.floorMod(total, 24 * 60);

        int newH = total / 60;
        int newM = total % 60;

        String base = String.format("%02d:%02d", newH, newM);
        if (parts.length == 3) {
            return base + ":" + parts[2];
        }
        return base;
    }
}
