package no.hia.oblig4;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Enkelt stoppesøk:
 *  - Søk etter stopp med navn (dele-match eller fullt navn)
 *  - Hent NSR:Quay-ID-er
 *  - Hent nøyaktig stoppnavn fra NSR:Quay-ID
 */
public class StopSearch {

    private final Map<String, List<String>> nameToIds = new HashMap<>();
    private final Map<String, String> idToName = new HashMap<>();

    public StopSearch(String quaysJsonPath) throws Exception {
        String json = Files.readString(Path.of(quaysJsonPath), StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> quays =
                (List<Map<String, Object>>) MiniJson.parse(json);

        for (Map<String, Object> q : quays) {
            String id   = (String) q.get("id");              // NSR:Quay:xxxx
            String navn = (String) q.getOrDefault("navn", q.get("name"));

            if (id == null || navn == null) continue;

            idToName.put(id, navn);

            String normalized = navn.toLowerCase(Locale.ROOT);
            nameToIds.computeIfAbsent(normalized, k -> new ArrayList<>()).add(id);
        }
    }

    /**
     * Søk på navn (fullt eller delvis, case-insensitivt).
     */
    public List<String> lookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();

        // Først eksakt match
        if (nameToIds.containsKey(key)) {
            result.addAll(nameToIds.get(key));
            return result;
        }

        // Ellers delvis match
        for (String n : nameToIds.keySet()) {
            if (n.contains(key)) {
                result.addAll(nameToIds.get(n));
            }
        }
        return result;
    }

    /**
     * Hent det menneskelige navnet gitt NSR:Quay-id.
     */
    public String getStopName(String quayId) {
        return idToName.getOrDefault(quayId, "(ukjent navn)");
    }

    /**
     * Ny: eksponer alle stopp som et read-only Map.
     * Brukes av webApp til å bygge egendefinerte søk / forslag.
     */
    public Map<String, String> getAllStops() {
        return Collections.unmodifiableMap(idToName);
    }


    /* ------------------------- Minimal JSON Parser ------------------------- */

    static final class MiniJson {
        private final String s; private int i;
        MiniJson(String s) { this.s = s; }

        static Object parse(String json) {
            return new MiniJson(json).val();
        }

        private Object val() {
            ws();
            if (i >= s.length()) throw err("slutt");
            char c = s.charAt(i);

            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"' || c == '\'') return str();
            if (c == '-' || dig(c)) return num();

            if (s.startsWith("true", i))  { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i))  { i += 4; return null; }

            throw err("ukjent verdi");
        }

        private Map<String,Object> obj() {
            Map<String,Object> m = new LinkedHashMap<>();
            expect('{'); ws();
            if (peek('}')) { i++; return m; }
            while (true) {
                ws();
                String key = str();
                ws(); expect(':');
                Object val = val();
                m.put(key, val);
                ws();
                if (peek('}')) { i++; break; }
                expect(',');
            }
            return m;
        }

        private List<Object> arr() {
            List<Object> a = new ArrayList<>();
            expect('['); ws();
            if (peek(']')) { i++; return a; }
            while (true) {
                a.add(val());
                ws();
                if (peek(']')) { i++; return a; }
                expect(',');
            }
        }

        private String str() {
            ws();
            char q = s.charAt(i++);
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == q) break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case '"': sb.append('"'); break;
                        case '\'': sb.append('\''); break;
                        case '\\': sb.append('\\'); break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }

        private Number num() {
            int st = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && dig(s.charAt(i))) i++;

            boolean frac = false;
            if (i < s.length() && s.charAt(i) == '.') {
                frac = true; i++;
                while (i < s.length() && dig(s.charAt(i))) i++;
            }

            String n = s.substring(st, i);
            return frac ? Double.parseDouble(n) : Long.parseLong(n);
        }

        private void ws() { while (i < s.length() && " \n\r\t".indexOf(s.charAt(i)) >= 0) i++; }
        private void expect(char c) { ws(); if (s.charAt(i) != c) throw err("forventet " + c); i++; }
        private boolean peek(char c) { ws(); return i < s.length() && s.charAt(i) == c; }
        private static boolean dig(char c) { return c >= '0' && c <= '9'; }
        private static RuntimeException err(String m) { return new RuntimeException("JSON-feil: " + m); }
    }
}


