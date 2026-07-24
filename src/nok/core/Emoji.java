package nok.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the committed emoji glyph pack and does greedy longest raw-code-unit
 * matching over note text, so the Viewer can blit a color glyph where a run of
 * UTF-16 units spells an emoji and silently drop the leftovers the E71 font
 * would otherwise render as missing-glyph boxes.
 *
 * <p>The pack is generated offline (tools/gen-emoji.py, whose docstring is the
 * authoritative byte layout for /emoji/index.bin): a set of strip PNGs plus a
 * binary index mapping RAW UTF-16 key sequences to glyph ids. Fully-qualified
 * RGI emoji, their FE0F-stripped and unqualified aliases, skin-tone and ZWJ
 * variants are all pre-baked as distinct keys pointing at the right glyph, so
 * this class does pure greedy matching with NO Unicode normalization pass at
 * runtime - the phone has neither the tables nor the cycles for one. A key that
 * is a single BMP code UNIT lives in a sorted (unit, gid) table; everything
 * longer lives as a run in one big char[] blob addressed by an offsets array,
 * both sorted so a lookup is a binary search with zero per-call allocation.
 *
 * <p>The index is ~86 KB of primitive arrays loaded once, lazily, on the first
 * match/geometry call and never freed. Load is synchronized and one-shot: a
 * missing resource, a bad magic/version, a truncated file or an out-of-memory
 * during the read all leave the class in a permanent "no emoji" mode where
 * match() returns 0 for everything and the geometry accessors return 0. It
 * never throws to its caller, because the Viewer runs it per character while
 * painting and must keep drawing text no matter what the resource does.
 *
 * <p>Threading: ensureLoaded() is synchronized so a first-call race cannot load
 * twice or read a half-built index (a late caller blocks on the monitor, then
 * sees ready/arrays published under it). The hot paths only take that monitor
 * before {@code tried} flips; afterwards match()/maybe() run lock-free, which is
 * safe because the Viewer paints on a single thread. maybe() never loads at all.
 *
 * Java 1.3 / CLDC 1.1 only; imports java.io but no javax.
 */
public final class Emoji {

    /**
     * Glyph field returned when the run at i is undrawable filler (a variation
     * selector, a ZWJ, an unknown astral pair, a lone surrogate half, a symbol
     * the E71 font lacks): the caller consumes the reported units and draws
     * nothing. 0xFFFF can never collide with a real glyph id - glyphCount is a
     * few thousand - so match()'s packed low half is unambiguous.
     */
    public static final int INVISIBLE = 0xFFFF;

    // Load state. tried flips true the moment the one load attempt begins (so a
    // failed attempt is never retried); ready is true only once the whole index
    // parsed and its arrays were published. Both are plain statics: writes
    // happen under the ensureLoaded monitor, and the sole hot-path reader (the
    // Viewer paint thread) is single-threaded.
    private static boolean tried = false;
    private static boolean ready = false;

    // Header scalars (see the gen-emoji.py docstring). Zero until a successful
    // load, which is also the value the accessors report in no-emoji mode.
    private static int glyphPx = 0;
    private static int perPage = 0;
    private static int maxUnits = 0;
    private static int pageCount = 0;
    private static int glyphCount = 0;
    private static int singleCount = 0;
    private static int seqCount = 0;

    // Single-code-unit keys: parallel arrays sorted ascending by unit, binary
    // searched by match(). gid stored as char purely because glyph ids fit in
    // 16 bits; it is widened back to int on the way out.
    private static char[] singleUnit;
    private static char[] singleGid;

    // Multi-unit keys. seqOff has seqCount+1 entries: key k is blob[seqOff[k] ..
    // seqOff[k+1]) and its glyph is seqGid[k]. The keys are concatenated into
    // blob in the same lexicographic order the offsets index, which is what lets
    // seqFind binary-search without ever cutting a substring out of the note.
    private static int[] seqOff;
    private static char[] seqGid;
    private static char[] blob;

    // All-static: never instantiated.
    private Emoji() {
    }

    /**
     * Cheap allocation-free, load-free gate the Viewer runs on every character
     * before deciding to pay for match(): true only where an emoji could
     * plausibly begin. Everything at/above U+2000 (the symbol/dingbat/surrogate
     * planes, a superset of every key's first unit) passes, as does an ASCII
     * keycap base (#, * or a digit) that is actually followed by a keycap member
     * (U+FE0F or U+20E3) - the one case where a sub-0x2000 unit starts a key.
     *
     * <p>Deliberately an over-estimate: plenty of >= 0x2000 punctuation (en
     * dash, smart quotes) passes here and is then rejected by match() with 0.
     * The gate only has to never MISS a real start, so the common ASCII path
     * stays a single compare.
     */
    public static boolean maybe(String s, int i) {
        if (s == null || i < 0 || i >= s.length()) {
            return false;
        }
        char c = s.charAt(i);
        if (c >= 0x2000) {
            return true;
        }
        if (c == '#' || c == '*' || (c >= '0' && c <= '9')) {
            if (i + 1 < s.length()) {
                char d = s.charAt(i + 1);
                if (d == '\uFE0F' || d == '\u20E3') {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Matches the text starting at i and returns a packed result:
     * {@code 0} means "no emoji here, draw s.charAt(i) as text as before";
     * anything else is {@code (unitsConsumed << 16) | glyphField}, where
     * glyphField is a glyph id in [0, glyphCount) to blit, or {@link #INVISIBLE}
     * to consume the units and draw nothing. The caller always advances by
     * unitsConsumed.
     *
     * <p>Greedy LONGEST raw match, no normalization: try sequence keys from the
     * longest possible length (bounded by maxUnits and the remaining text) down
     * to 2 and take the first hit, so a skin-tone or ZWJ cluster wins over its
     * bare base and an RGI family is consumed whole; then the single-unit table.
     * Only when nothing matched does it classify the lead unit for the
     * INVISIBLE-vs-0 decision (classifyUndrawable), which is what makes a
     * non-RGI ZWJ cluster degrade into its component glyphs with the joiner
     * dropped. Allocates nothing: every comparison reads the note and the blob
     * in place.
     */
    public static int match(String s, int i) {
        if (!tried) {
            ensureLoaded();
        }
        if (!ready || s == null || i < 0 || i >= s.length()) {
            // No-emoji mode (or a nonsensical index) draws everything as text,
            // exactly as the app behaved before the pack existed.
            return 0;
        }
        int rem = s.length() - i;
        int maxLen = maxUnits < rem ? maxUnits : rem;
        // Longest first: the first hit is the greedy-longest answer.
        for (int len = maxLen; len >= 2; len--) {
            int g = seqFind(s, i, len);
            if (g >= 0) {
                return (len << 16) | g;
            }
        }
        int g = singleFind(s.charAt(i));
        if (g >= 0) {
            return (1 << 16) | g;
        }
        return classifyUndrawable(s, i);
    }

    /** glyph pixel size (square); 0 in no-emoji mode. */
    public static int glyphPx() {
        if (!tried) {
            ensureLoaded();
        }
        return glyphPx;
    }

    /** glyphs per strip PNG; 0 in no-emoji mode. */
    public static int perPage() {
        if (!tried) {
            ensureLoaded();
        }
        return perPage;
    }

    /** number of strip PNGs (p0..pN-1); 0 in no-emoji mode. */
    public static int pageCount() {
        if (!tried) {
            ensureLoaded();
        }
        return pageCount;
    }

    /** distinct rendered glyphs; ids run [0, glyphCount). 0 in no-emoji mode. */
    public static int glyphCount() {
        if (!tried) {
            ensureLoaded();
        }
        return glyphCount;
    }

    /** Strip page holding {@code glyph} (row-major id layout); 0 if unusable. */
    public static int pageOf(int glyph) {
        if (!tried) {
            ensureLoaded();
        }
        return ready ? glyph / perPage : 0;
    }

    /** Slot within its page for {@code glyph}; 0 if unusable. */
    public static int slotOf(int glyph) {
        if (!tried) {
            ensureLoaded();
        }
        return ready ? glyph % perPage : 0;
    }

    // ==================================================================
    // matching internals
    // ==================================================================

    /**
     * Binary-searches the single-unit table for {@code u}; returns its glyph id
     * or -1. The table is sorted ascending by unit (the generator writes it that
     * way), so a plain ordered search suffices.
     */
    private static int singleFind(int u) {
        int lo = 0;
        int hi = singleCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int mu = singleUnit[mid];
            if (mu == u) {
                return singleGid[mid];
            }
            if (u < mu) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return -1;
    }

    /**
     * Binary-searches the sequence blob for the key exactly equal to the
     * {@code len} units s[i..i+len); returns its glyph id or -1. The blob keys
     * are sorted lexicographically by unit value with a shorter prefix ordering
     * before its extensions, and seqCompare implements that same total order
     * treating the target as a fixed-length key, so the search is exact even
     * though keys of every length are interleaved in one array.
     */
    private static int seqFind(String s, int i, int len) {
        int lo = 0;
        int hi = seqCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = seqCompare(s, i, len, mid);
            if (c == 0) {
                return seqGid[mid];
            }
            if (c < 0) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return -1;
    }

    /**
     * Orders the target s[i..i+len) against sequence key {@code k}: negative if
     * the target sorts first, positive if last, 0 if identical. Compares unit by
     * unit over the shared length (chars are unsigned 16-bit, so the plain
     * subtraction is a correct unsigned compare), and on an all-equal prefix the
     * shorter run sorts first - the exact rule the generator sorted the blob by.
     */
    private static int seqCompare(String s, int i, int len, int k) {
        int koff = seqOff[k];
        int klen = seqOff[k + 1] - koff;
        int m = len < klen ? len : klen;
        for (int j = 0; j < m; j++) {
            int tu = s.charAt(i + j);
            int bu = blob[koff + j];
            if (tu != bu) {
                return tu - bu;
            }
        }
        if (len == klen) {
            return 0;
        }
        return len < klen ? -1 : 1;
    }

    /**
     * The INVISIBLE-vs-0 decision for a lead unit that matched no glyph,
     * replicating Ui.isUndrawable's stripping so the emoji path drops exactly
     * what the plain-text path already dropped (and, on top of it, the combining
     * keycap U+20E3, which only ever appears as an emoji member). A well-formed
     * surrogate pair is an unknown astral emoji: consume both units. A lone or
     * mis-ordered surrogate half, and every BMP unit in the undrawable ranges,
     * consume one. Anything the E71 font renders fine (en dash, ASCII, ...) is
     * left as text with a 0 return.
     */
    private static int classifyUndrawable(String s, int i) {
        int u = s.charAt(i);
        if (u >= 0xD800 && u <= 0xDBFF) {
            // High surrogate: pair it with a following low half if there is one
            // (2 units, unknown astral), else it is a lone half (1 unit).
            if (i + 1 < s.length()) {
                int d = s.charAt(i + 1);
                if (d >= 0xDC00 && d <= 0xDFFF) {
                    return (2 << 16) | INVISIBLE;
                }
            }
            return (1 << 16) | INVISIBLE;
        }
        if (u >= 0xDC00 && u <= 0xDFFF) {
            return (1 << 16) | INVISIBLE; // lone low half
        }
        if (isBmpUndrawable(u)) {
            return (1 << 16) | INVISIBLE;
        }
        return 0;
    }

    /**
     * The exact BMP set Ui.isUndrawable rejects (ZWJ, the FE00-FE0F variation
     * selectors, misc symbols/dingbats/technical/arrows, TM and info), plus the
     * combining enclosing keycap U+20E3. Kept as ordered range compares rather
     * than a table for the same reason Ui does: it runs per drawn character and
     * a table would be permanent heap to replace a handful of integer tests.
     */
    private static boolean isBmpUndrawable(int u) {
        if (u == 0x200D) {
            return true; // ZWJ
        }
        if (u >= 0xFE00 && u <= 0xFE0F) {
            return true; // variation selectors (FE0E text / FE0F emoji, ...)
        }
        if (u == 0x20E3) {
            return true; // combining enclosing keycap (bare, outside a keycap)
        }
        if (u >= 0x2600 && u <= 0x27BF) {
            return true; // misc symbols + dingbats
        }
        if (u >= 0x2300 && u <= 0x23FF) {
            return true; // misc technical
        }
        if (u >= 0x2B00 && u <= 0x2BFF) {
            return true; // misc symbols and arrows
        }
        if (u == 0x2122 || u == 0x2139) {
            return true; // TM, info source
        }
        return false;
    }

    // ==================================================================
    // one-shot lazy load
    // ==================================================================

    /**
     * Loads /emoji/index.bin once. Parses into locals first and publishes the
     * arrays and ready flag only after the whole file reads cleanly, so a
     * truncation cannot leave a half-built index visible. Any failure - absent
     * resource, wrong magic/version, short read, or OutOfMemoryError on the
     * ~86 KB of arrays - is swallowed into permanent no-emoji mode; the method
     * never throws, because its callers paint the UI. {@code tried} is set up
     * front so a failed load is attempted exactly once, not on every character.
     */
    private static synchronized void ensureLoaded() {
        if (tried) {
            return;
        }
        tried = true;
        InputStream in = null;
        try {
            in = Emoji.class.getResourceAsStream("/emoji/index.bin");
            if (in == null) {
                return;
            }
            DataInputStream d = new DataInputStream(in);
            if (d.read() != 'N' || d.read() != 'K'
                    || d.read() != 'E' || d.read() != 'M') {
                return;
            }
            if (d.readUnsignedByte() != 1) {
                return; // unknown version: refuse rather than misread
            }
            int gpx = d.readUnsignedByte();
            int per = d.readUnsignedByte();
            int maxu = d.readUnsignedByte();
            int npages = d.readUnsignedShort();
            int nsing = d.readUnsignedShort();
            int nseq = d.readUnsignedShort();
            int nglyph = d.readUnsignedShort();

            char[] su = new char[nsing];
            char[] sg = new char[nsing];
            for (int k = 0; k < nsing; k++) {
                su[k] = (char) d.readUnsignedShort();
                sg[k] = (char) d.readUnsignedShort();
            }
            int[] off = new int[nseq + 1];
            for (int k = 0; k <= nseq; k++) {
                off[k] = d.readInt(); // u32 offset, always < 0x7FFFFFFF
            }
            char[] sq = new char[nseq];
            for (int k = 0; k < nseq; k++) {
                sq[k] = (char) d.readUnsignedShort();
            }
            int blobLen = off[nseq];
            char[] bl = new char[blobLen];
            for (int k = 0; k < blobLen; k++) {
                bl[k] = (char) d.readUnsignedShort();
            }

            // Publish only now that every array is fully populated.
            glyphPx = gpx;
            perPage = per;
            maxUnits = maxu;
            pageCount = npages;
            singleCount = nsing;
            seqCount = nseq;
            glyphCount = nglyph;
            singleUnit = su;
            singleGid = sg;
            seqOff = off;
            seqGid = sq;
            blob = bl;
            ready = true;
        } catch (Throwable t) {
            // Corrupt/truncated file or low memory: drop everything and stay in
            // no-emoji mode. Catch Throwable so an OutOfMemoryError on the arrays
            // degrades gracefully instead of tearing down the paint.
            ready = false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // closing a resource stream that already gave us its bytes
                }
            }
        }
    }
}
