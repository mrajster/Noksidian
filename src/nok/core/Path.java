package nok.core;

/**
 * Path helpers for '/'-separated vault-relative paths.
 * Java 1.3 / CLDC 1.1 only; no javax imports.
 *
 * <p>A "rel" here is vault-relative: no drive letter, no scheme, no leading
 * '/', '/' as the only separator. CLDC has no java.io.File and JSR-75 hands
 * back plain URL strings, so the platform offers no path type to lean on -
 * every bit of path arithmetic in the app is string arithmetic, and it lives
 * in this one class so the rules are stated once. Contract: CONTRACTS.md,
 * section "nok.core.Path".
 *
 * <p>No regex (CLDC ships no java.util.regex) and no String.split (CLDC 1.1
 * String has none), hence the hand-rolled character loops. The class is
 * stateless, so every method is safe to call from the sync thread and the UI
 * thread at once.
 *
 * <p>Every accessor tolerates a null path and answers "" instead of throwing:
 * the inputs come from disk listings, GitHub JSON and wikilink text, and a
 * missing value must degrade to "top level" rather than kill a sync pass.
 *
 * <p>Deliberate non-goal: nothing here resolves "." or ".." segments. Escaping
 * the vault is instead prevented one level up, by rejecting any path that still
 * contains such a segment after normalize() (see NoksidianMIDlet.safeRel).
 */
public final class Path {

    // Uppercase: RFC 3986 says producers should use uppercase percent triplets.
    private static final String HEX = "0123456789ABCDEF";

    private Path() {
    }

    /** Joins two path pieces with '/'; handles empty (or null) a and b. */
    // Returning the other side untouched when one is empty is what makes
    // join("", name) produce a bare top-level name rather than "/name". Every
    // caller that pairs this with parent() depends on it, since parent() of a
    // vault-root file is "": Library.entryRel, Sync.remoteCopyPath, ImageView.
    public static String join(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.length() == 0) return b;
        if (b.length() == 0) return a;
        boolean slashA = a.charAt(a.length() - 1) == '/';
        boolean slashB = b.charAt(0) == '/';
        if (slashA && slashB) return a + b.substring(1);
        if (slashA || slashB) return a + b;
        return a + "/" + b;
    }

    /** Parent path, "" if top-level. */
    // Purely textual, so it expects an already normalized rel: the trailing '/'
    // that Files.list appends to directory entries would make this hand back
    // the directory itself instead of its parent. Same trap applies to name().
    public static String parent(String p) {
        if (p == null) return "";
        int i = p.lastIndexOf('/');
        if (i < 0) return "";
        return p.substring(0, i);
    }

    /** Last segment of the path. */
    public static String name(String p) {
        if (p == null) return "";
        int i = p.lastIndexOf('/');
        if (i < 0) return p;
        return p.substring(i + 1);
    }

    /** Last segment minus final ".ext". Dotfiles (".gitignore") keep their name. */
    // Only the LAST dot splits, so "a.tar.gz" yields "a.tar" - no guessing at
    // compound extensions. Used for wikilink basename matching (NoteIndex
    // .resolve) and for building conflict-copy names (Sync.remoteCopyPath).
    public static String baseName(String p) {
        String n = name(p);
        // "<= 0" not "< 0": a dot at index 0 is the hidden-file marker, not an
        // extension separator, so ".gitignore" survives whole.
        int dot = n.lastIndexOf('.');
        if (dot <= 0) return n;
        return n.substring(0, dot);
    }

    /** Extension, lowercase, without the dot; "" if none. Dotfiles have no extension. */
    // Lowercasing here is what lets every caller test with a plain equals() on
    // a lowercase literal ("txt", "md", ...), so the case rule lives in one
    // place instead of at each call site.
    public static String ext(String p) {
        String n = name(p);
        // Same "<= 0" dotfile rule as baseName; see there.
        int dot = n.lastIndexOf('.');
        if (dot <= 0) return "";
        return n.substring(dot + 1).toLowerCase();
    }

    /** True for .md / .markdown files (case-insensitive). */
    public static boolean isMarkdown(String p) {
        String e = ext(p);
        return e.equals("md") || e.equals("markdown");
    }

    /** True for png/jpg/jpeg/gif/bmp files (case-insensitive). */
    // A routing predicate - "open this in ImageView / lay it out as an inline
    // image" - not a promise the device can decode it. ImgProbe only parses PNG
    // and JPEG headers; GIF and BMP get KIND_NONE and fall through to a guarded
    // native decode that may still fail on the E71.
    public static boolean isImage(String p) {
        String e = ext(p);
        return e.equals("png") || e.equals("jpg") || e.equals("jpeg")
                || e.equals("gif") || e.equals("bmp");
    }

    /** Converts '\' to '/', collapses "//" runs, strips leading/trailing '/'. */
    // The canonical form every path key is stored in - NoteIndex keys, Sync's
    // remote-vs-local comparison and the GitHub contents URL all run through it,
    // so a path typed with backslashes, a stray leading '/' or a doubled
    // separator still compares equal to the same file seen from the other side.
    //
    // Does NOT touch "." or ".." segments; see the class comment for why.
    public static String normalize(String p) {
        if (p == null) return "";
        StringBuffer sb = new StringBuffer(p.length());
        boolean lastSlash = true; // swallow leading separators
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '\\') c = '/';
            if (c == '/') {
                if (lastSlash) continue;
                lastSlash = true;
                sb.append('/');
            } else {
                lastSlash = false;
                sb.append(c);
            }
        }
        // At most one trailing '/' can be left, since runs were already
        // collapsed above, so a single truncation is enough - no loop needed.
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '/') sb.setLength(len - 1);
        return sb.toString();
    }

    /**
     * Percent-encodes the UTF-8 bytes of each segment for use in URLs.
     * Keeps '/' separators and unreserved chars A-Za-z0-9 - _ . ~; space becomes %20.
     *
     * <p>Two very different consumers share this encoder: JSR-75 file URLs
     * (Files.urlEnc and VaultPicker, the spec-strict-Symbian fallback form) and
     * GitHub REST URLs (GitHub.contentsUrl plus the branch/tree URLs). Keeping
     * one implementation means a vault whose names survive locally also
     * round-trips through the API.
     *
     * <p>Encoding per segment rather than over the whole string is the point:
     * a '%' or a space inside a name must become %25 / %20, while the '/' that
     * separates names must stay a literal '/' or the URL loses its structure.
     * Callers that need "//" collapsed should normalize() first - VaultPicker
     * deliberately does not, because it passes directory names that must keep
     * their trailing '/' (which falls out as a harmless empty last segment).
     */
    public static String urlEncodePath(String p) {
        if (p == null) return "";
        StringBuffer sb = new StringBuffer(p.length() + 8);
        int start = 0;
        // i runs one past the end so the final segment (which has no closing
        // '/') is flushed by the same branch as every other one.
        for (int i = 0; i <= p.length(); i++) {
            if (i == p.length() || p.charAt(i) == '/') {
                encodeSegment(p.substring(start, i), sb);
                if (i < p.length()) sb.append('/');
                start = i + 1;
            }
        }
        return sb.toString();
    }

    /**
     * Percent-encodes one path segment as spec-correct UTF-8. Surrogate pairs
     * are combined into one code point and emitted as a single 4-byte sequence,
     * NOT String.getBytes("UTF-8") - some Symbian CLDC VMs emit CESU-8 (two
     * 3-byte halves) for astral chars, which a strict URL decoder won't map back
     * to the emoji. Byte-identical to J2SE getBytes("UTF-8") for well-formed
     * input, so callers on other stacks are unaffected. Unreserved
     * A-Za-z0-9 - _ . ~ pass through.
     */
    private static void encodeSegment(String seg, StringBuffer sb) {
        int n = seg.length();
        for (int i = 0; i < n; i++) {
            // char widens to int unsigned, so cp is already 0..0xFFFF here and
            // the range tests below need no masking.
            int cp = seg.charAt(i);
            // Combine a high+low surrogate pair into one code point.
            if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n) {
                int lo = seg.charAt(i + 1);
                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    i++;
                }
            }
            // Below: the four standard UTF-8 length classes. Only the RFC 3986
            // "unreserved" set passes through - sub-delims like '+', '&' and
            // '(' are escaped too, on purpose: JSR-75 stacks and the GitHub API
            // disagree about which reserved characters are safe unescaped in a
            // path, and escaping them is always accepted by both.
            if (cp < 0x80) {
                if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')
                        || (cp >= '0' && cp <= '9')
                        || cp == '-' || cp == '_' || cp == '.' || cp == '~') {
                    sb.append((char) cp);
                } else {
                    pct(sb, cp);
                }
            } else if (cp < 0x800) {
                pct(sb, 0xC0 | (cp >> 6));
                pct(sb, 0x80 | (cp & 0x3F));
            } else if (cp < 0x10000) {
                pct(sb, 0xE0 | (cp >> 12));
                pct(sb, 0x80 | ((cp >> 6) & 0x3F));
                pct(sb, 0x80 | (cp & 0x3F));
            } else {
                pct(sb, 0xF0 | (cp >> 18));
                pct(sb, 0x80 | ((cp >> 12) & 0x3F));
                pct(sb, 0x80 | ((cp >> 6) & 0x3F));
                pct(sb, 0x80 | (cp & 0x3F));
            }
        }
    }

    /** Appends "%XX" for one byte value. */
    private static void pct(StringBuffer sb, int b) {
        b &= 0xFF;
        sb.append('%');
        sb.append(HEX.charAt(b >> 4));
        sb.append(HEX.charAt(b & 15));
    }
}
