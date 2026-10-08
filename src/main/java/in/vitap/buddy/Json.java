package in.vitap.buddy;

/** Tiny JSON helpers so the project needs no external libraries. */
final class Json {
    private Json() {}

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.toString();
    }

    static String str(String s) { return "\"" + esc(s) + "\""; }

    /** Extracts the first string value for "key" (handles escapes). Returns "" if absent. */
    static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        i = json.indexOf(':', i);
        if (i < 0) return "";
        i = json.indexOf('"', i);
        if (i < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int p = i + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '"') break;
            if (c == '\\' && p + 1 < json.length()) {
                char n = json.charAt(++p);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(json.substring(p + 1, p + 5), 16));
                        p += 4;
                    }
                    default -> sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }
}
