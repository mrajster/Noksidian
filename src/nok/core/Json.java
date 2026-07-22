package nok.core;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

/**
 * Minimal JSON parser (recursive descent) and writer for CLDC 1.1.
 *
 * Value mapping:
 *   object  -> java.util.Hashtable (String keys)
 *   array   -> java.util.Vector
 *   string  -> java.lang.String
 *   number  -> Long (integral, no . e E) or Double (otherwise)
 *   boolean -> java.lang.Boolean
 *   null    -> Json.NULL sentinel (Hashtable cannot hold null)
 *
 * parse() throws IllegalArgumentException on any malformed input.
 * write() escapes '"', '\\', '\n', '\r', '\t' by name and any other
 * control char below 0x20 as \\u00XX.
 *
 * Exists because CLDC 1.1 ships no JSON support and no java.util.regex,
 * so there is nothing to lean on. Every JSON byte the app touches goes
 * through here: GitHub REST responses (tree listings, blobs, contents
 * PUT/DELETE replies) and the sync state file. Kept deliberately small -
 * one pass over the source String, no tokenizer object, no intermediate
 * token list - because a parse runs on a ~2MB heap next to the note text
 * that was just downloaded.
 *
 * Number validation is delegated, not scanned: parseNumber grabs a greedy
 * run of number-ish characters and lets Long.parseLong / Double.parseDouble
 * reject it. That keeps the scanner trivial at the cost of accepting a few
 * forms strict JSON forbids (leading zeros, so "01" reads as 1). No caller
 * depends on the stricter reading.
 *
 * A Json instance is a private cursor over one String, created and thrown
 * away inside parse(); the static entry points are therefore safe to call
 * from any thread, but an instance must never be shared.
 *
 * Java 1.3 / CLDC 1.1 only. No javax imports.
 */
public final class Json {

    /** Sentinel singleton for JSON null. */
    public static final Object NULL = new NullVal();

    /** Nibble table for the \\u00XX control escapes; lowercase, as JSON emits. */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    // Parse cursor. One instance per parse() call, never shared or reused;
    // the whole document is already in memory as a String, so the parser is
    // just an index walking it rather than a stream reader.
    private final String src;
    private int pos;

    private Json(String s) {
        src = s;
        pos = 0;
    }

    /**
     * Stand-in for JSON null, which Hashtable/Vector cannot store directly.
     * toString() answers "null" so a parsed tree still prints sensibly when
     * concatenated into a log line or an error message.
     */
    private static final class NullVal {
        public String toString() {
            return "null";
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /**
     * Parses a complete JSON text (any value type at top level).
     * Throws IllegalArgumentException on bad input.
     */
    public static Object parse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("JSON: null input");
        }
        Json p = new Json(s);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        // Insisting on end-of-input is what rejects "{}{}", "[1] 2" and the
        // half-consumed junk the lenient number scanner leaves behind: "0x10"
        // scans as 0 and then trips here on the stranded "x10".
        if (p.pos != s.length()) {
            throw p.err("trailing characters");
        }
        return v;
    }

    /** Builds the failure with the character offset attached; parse errors on
     *  a phone have no stack trace worth reading, so the offset is the only
     *  clue about which part of a multi-KB API response went wrong. */
    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON: " + msg + " at " + pos);
    }

    /** Skips exactly the four whitespace characters JSON allows. Form feed and
     *  vertical tab are deliberately not in the set: they are not legal
     *  separators, so they must reach parseValue and fail there. */
    private void skipWs() {
        int len = src.length();
        while (pos < len) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    /**
     * Current character, or a parse failure at end of input. The structural
     * decisions (which value type, closer or separator) all route through
     * here, so truncated input - a response cut short by a dropped GPRS
     * connection - fails cleanly at the cut without a length check at each
     * of those call sites.
     */
    private char peek() {
        if (pos >= src.length()) {
            throw err("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw err("expected '" + c + "'");
        }
        pos++;
    }

    /** Compares a literal at the cursor and advances only on success, so the
     *  caller can try "true", then "false", then "null" without saving and
     *  restoring pos itself. */
    private boolean match(String lit) {
        int n = lit.length();
        if (pos + n > src.length()) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (src.charAt(pos + i) != lit.charAt(i)) {
                return false;
            }
        }
        pos += n;
        return true;
    }

    /**
     * Dispatches on the first character alone - JSON is designed so this is
     * unambiguous. Note what the number arm does NOT accept: a leading '+'
     * or a bare '.', which is why "+1" and ".5" fail here rather than in
     * parseNumber.
     *
     * Recursion depth is unbounded, matching the input's nesting depth. Fine
     * for GitHub payloads and the sync state file, both a handful of levels
     * deep; a hostile document could exhaust the small J2ME stack.
     */
    private Object parseValue() {
        char c = peek();
        if (c == '{') {
            return parseObject();
        }
        if (c == '[') {
            return parseArray();
        }
        if (c == '"') {
            return parseString();
        }
        if (c == 't' || c == 'f' || c == 'n') {
            if (match("true")) {
                return Boolean.TRUE;
            }
            if (match("false")) {
                return Boolean.FALSE;
            }
            if (match("null")) {
                return NULL;
            }
            throw err("unexpected token");
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber();
        }
        throw err("unexpected character '" + c + "'");
    }

    /**
     * Duplicate keys resolve last-wins, since each pair is simply put() into
     * the Hashtable. Keys must be quoted strings: the unquoted-key and
     * single-quoted-key forms that JavaScript tolerates are rejected.
     */
    private Hashtable parseObject() {
        expect('{');
        Hashtable h = new Hashtable();
        skipWs();
        // Empty object is handled before the loop, because the loop body
        // always demands a key - which is also what makes "{"a":1,}" fail.
        if (peek() == '}') {
            pos++;
            return h;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw err("expected string key");
            }
            String key = parseString();
            skipWs();
            expect(':');
            skipWs();
            Object v = parseValue();
            h.put(key, v);
            skipWs();
            // peek() first so a document truncated right here fails as
            // "unexpected end of input"; the bump then consumes whichever
            // delimiter it turned out to be, closer or separator alike.
            char c = peek();
            pos++;
            if (c == '}') {
                return h;
            }
            if (c != ',') {
                throw err("expected ',' or '}'");
            }
        }
    }

    private Vector parseArray() {
        expect('[');
        Vector v = new Vector();
        skipWs();
        // Same shape as parseObject: the empty case is peeled off up front so
        // the loop can require a value every pass, rejecting "[1,]".
        if (peek() == ']') {
            pos++;
            return v;
        }
        while (true) {
            skipWs();
            v.addElement(parseValue());
            skipWs();
            // peek-then-bump, as in parseObject: end of input is a parse
            // error, anything else is consumed as the delimiter.
            char c = peek();
            pos++;
            if (c == ']') {
                return v;
            }
            if (c != ',') {
                throw err("expected ',' or ']'");
            }
        }
    }

    /**
     * Reads a quoted string, decoding escapes in place. Lenient in one way:
     * a raw control character below 0x20 inside the quotes is copied through
     * rather than rejected, since nothing downstream benefits from failing
     * a response over it. write() still escapes those on the way back out.
     *
     * Also used for object keys, so "{"a\nb":1}" yields the key "a\nb".
     */
    private String parseString() {
        expect('"');
        StringBuffer sb = new StringBuffer();
        int len = src.length();
        while (true) {
            if (pos >= len) {
                throw err("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= len) {
                throw err("unterminated escape");
            }
            char e = src.charAt(pos++);
            switch (e) {
            case '"':
                sb.append('"');
                break;
            case '\\':
                sb.append('\\');
                break;
            case '/':
                sb.append('/');
                break;
            case 'b':
                sb.append('\b');
                break;
            case 'f':
                sb.append('\f');
                break;
            case 'n':
                sb.append('\n');
                break;
            case 'r':
                sb.append('\r');
                break;
            case 't':
                sb.append('\t');
                break;
            case 'u':
                // One escape yields one UTF-16 code unit, with no surrogate
                // pairing logic on purpose: Java Strings are UTF-16, so an
                // emoji sent as two consecutive surrogate escapes rebuilds
                // itself correctly just by appending each half as it arrives.
                sb.append(readHex4());
                break;
            default:
                throw err("bad escape '\\" + e + "'");
            }
        }
    }

    /**
     * Decodes the four hex digits of a unicode escape. Hand-rolled instead of
     * Integer.parseInt(substring, 16) to skip the substring allocation, and
     * so a bad digit reports its own offset rather than surfacing as a
     * NumberFormatException with no position in it.
     */
    private char readHex4() {
        if (pos + 4 > src.length()) {
            throw err("bad \\u escape");
        }
        int v = 0;
        for (int i = 0; i < 4; i++) {
            char c = src.charAt(pos++);
            int d;
            if (c >= '0' && c <= '9') {
                d = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                d = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                d = c - 'A' + 10;
            } else {
                throw err("bad hex digit '" + c + "'");
            }
            v = (v << 4) | d;
        }
        return (char) v;
    }

    /**
     * Scans a greedy run of number characters and hands the token to the
     * platform parsers, which do the real validation - that is why "1.2.3"
     * and "1e" fail here with no grammar in this method. Integral tokens
     * become Long so file sizes and timestamps survive exactly; anything
     * carrying a '.', 'e', 'E' or a later sign becomes Double, as does an
     * integral token too big for a long.
     */
    private Object parseNumber() {
        int start = pos;
        boolean dbl = false;
        int len = src.length();
        // The leading sign is eaten before the loop so it cannot set dbl; a
        // '-' met inside the loop can only be an exponent's, as in 1e-2.
        if (pos < len && src.charAt(pos) == '-') {
            pos++;
        }
        while (pos < len) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                dbl = true;
                pos++;
            } else {
                break;
            }
        }
        String tok = src.substring(start, pos);
        if (tok.length() == 0 || tok.equals("-")) {
            throw err("bad number");
        }
        if (!dbl) {
            try {
                return new Long(Long.parseLong(tok));
            } catch (NumberFormatException nfe) {
                // out of long range -> fall through to double
            }
        }
        try {
            return new Double(Double.parseDouble(tok));
        } catch (NumberFormatException nfe) {
            throw err("bad number '" + tok + "'");
        }
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Serializes a value (Hashtable/Vector/String/Long/Double/Boolean/
     * Json.NULL; Integer/Short/Byte/Float tolerated). Java null writes
     * as JSON null. Throws IllegalArgumentException for other types.
     */
    public static String write(Object v) {
        StringBuffer sb = new StringBuffer();
        writeValue(sb, v);
        return sb.toString();
    }

    /**
     * The boxed types beyond Long and Double are accepted for the caller's
     * convenience only; every number this class parses comes back as Long or
     * Double, and the only Hashtables written today are built in Sync.
     *
     * Numbers go out via toString(), which is exact for Long and round-trips
     * for Double. It would also emit NaN and Infinity, which are not legal
     * JSON, and nothing guards against it: a token too large for a double
     * (say 1e999) parses to Infinity here and would be written straight back
     * out that way.
     */
    private static void writeValue(StringBuffer sb, Object v) {
        if (v == null || v == NULL) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof Long || v instanceof Integer
                || v instanceof Short || v instanceof Byte
                || v instanceof Double || v instanceof Float) {
            sb.append(v.toString());
        } else if (v instanceof Hashtable) {
            writeObject(sb, (Hashtable) v);
        } else if (v instanceof Vector) {
            writeArray(sb, (Vector) v);
        } else {
            throw new IllegalArgumentException(
                "JSON: cannot write " + v.getClass().getName());
        }
    }

    /**
     * Key order follows Hashtable's enumeration order, which is unspecified
     * and varies with hashing and insertion history. Output is therefore not
     * byte-stable across runs: compare parsed structures, never the emitted
     * text, and never checksum this to decide whether state changed.
     */
    private static void writeObject(StringBuffer sb, Hashtable h) {
        sb.append('{');
        Enumeration e = h.keys();
        boolean first = true;
        while (e.hasMoreElements()) {
            Object k = e.nextElement();
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, k.toString());
            sb.append(':');
            writeValue(sb, h.get(k));
        }
        sb.append('}');
    }

    private static void writeArray(StringBuffer sb, Vector v) {
        sb.append('[');
        int n = v.size();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, v.elementAt(i));
        }
        sb.append(']');
    }

    /**
     * Escapes the five characters that turn up in practice by name, then
     * sweeps every remaining control character into the numeric form.
     * Backspace and form feed have short JSON names but are not special-
     * cased: they fall into the below-0x20 branch and go out numerically,
     * which is equally valid and keeps the loop short.
     *
     * Everything from 0x20 up is emitted raw, including non-ASCII. The result
     * is a UTF-16 Java String, so the caller must encode it as UTF-8 before
     * it reaches a socket or a file - see utf8b() in Sync and utf8() in
     * GitHub.
     */
    private static void writeString(StringBuffer sb, String s) {
        sb.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append("\\u00");
                sb.append(HEX[(c >> 4) & 0xF]);
                sb.append(HEX[c & 0xF]);
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Typed helpers
    // ------------------------------------------------------------------
    // These let callers walk an untrusted API response without an instanceof
    // and a cast at every hop. None of them throw: obj/arr answer null for a
    // wrong type, the Hashtable lookups tolerate a null table and a null key,
    // and a wrong type is treated exactly like a missing key - so a malformed
    // or unexpected response degrades to defaults instead of throwing
    // ClassCastException deep inside sync. Absent fields are the normal case,
    // not an error: GitHub error bodies do not always carry "message", and
    // GitHub.httpError() just falls back to the bare status code.

    /** Returns v as a Hashtable, or null if it is not one. */
    public static Hashtable obj(Object v) {
        if (v instanceof Hashtable) {
            return (Hashtable) v;
        }
        return null;
    }

    /** Returns v as a Vector, or null if it is not one. */
    public static Vector arr(Object v) {
        if (v instanceof Vector) {
            return (Vector) v;
        }
        return null;
    }

    /** Returns o.get(key) as a String, or null if absent / wrong type. */
    public static String str(Hashtable o, String key) {
        if (o == null || key == null) {
            return null;
        }
        Object v = o.get(key);
        // A JSON null reads as absent here, since the NULL sentinel is not a
        // String. Callers wanting to tell "field was null" from "field was
        // missing" have to check the Hashtable directly.
        if (v instanceof String) {
            return (String) v;
        }
        return null;
    }

    /**
     * Returns o.get(key) as a long (accepts Long or Double values),
     * or def if absent / wrong type.
     */
    public static long num(Hashtable o, String key, long def) {
        if (o == null || key == null) {
            return def;
        }
        Object v = o.get(key);
        if (v instanceof Long) {
            return ((Long) v).longValue();
        }
        if (v instanceof Double) {
            // Truncates toward zero. The fields read this way (sizes, times)
            // are integral, so a Double here means the server spelled one in
            // exponent form; a genuine fraction would be a server bug, and
            // silently flooring it beats throwing mid-sync.
            return (long) ((Double) v).doubleValue();
        }
        return def;
    }

    /** Returns o.get(key) as a boolean, or def if absent / wrong type. */
    public static boolean bool(Hashtable o, String key, boolean def) {
        if (o == null || key == null) {
            return def;
        }
        Object v = o.get(key);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        return def;
    }
}
