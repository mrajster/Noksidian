import java.util.Random;

import nok.core.Utf8;

/**
 * Desktop-JVM tests for nok.core.Utf8, the hand-rolled UTF-8 codec that owns
 * the note-content and crypto byte<->String boundary on the device.
 *
 * The point of the class is that the platform codec on some Symbian CLDC VMs
 * is CESU-8 (an astral char is two 3-byte surrogate halves, not one 4-byte
 * sequence), so the app can trust neither getBytes("UTF-8") nor
 * new String(b,"UTF-8") for emoji. This suite pins three separate claims:
 *   1. round trips survive for ASCII, accented Latin, Slovenian, CJK and
 *      astral emoji up to U+10FFFF;
 *   2. encode()/decode() are BYTE-identical to the desktop JDK for every
 *      well-formed input (the JDK is the oracle here, since desktop HotSpot is
 *      a conforming UTF-8 stack) - this is the interop guarantee other tools
 *      like tools/nokcrypt.py rely on;
 *   3. the two deviations are exactly the ones the device needs: a CESU-8 pair
 *      decodes to the right emoji, and malformed bytes degrade to U+FFFD with
 *      a clean resync instead of throwing on the sync worker thread.
 *
 * No JUnit and no javax: like TestBase64Json this runs under the vendored
 * JDK 8 with a plain main(), a local check() that throws on the first failure,
 * and an "ALL PASS n" line at the end. All non-ASCII test data is written as
 * \\uXXXX escapes because the source file itself must stay ASCII-only.
 */
public class TestUtf8 {

    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) {
            throw new RuntimeException(name);
        }
        passed++;
    }

    // Well-formed corpus, shared by the round-trip and both byte-identity
    // suites. Every entry is proper UTF-16: astral code points appear only as
    // matched surrogate pairs, never a lone half, so the JDK's own encoder
    // treats each as well-formed and can serve as the oracle. Widths are
    // walked on purpose - 1-byte ASCII, the 2-byte boundary U+0080/U+07FF, the
    // 3-byte boundary U+0800/U+D7FF/U+E000, and 4-byte astral up to U+10FFFF.
    static final String[] CORPUS = {
        "",
        "hello world",
        "The quick brown fox 0123456789 !@#$%^&*()_+-=[]{}",
        "caf\u00E9 na\u00EFve \u00FCber \u00DF",   // Latin-1 accents
        "\u010D\u0161\u017E \u010C\u0160\u017D",   // Slovenian cszCSZ
        "\u4E16\u754C \u3053\u3093\u306B\u3061\u306F", // CJK + kana
        "\u0080\u07FF",                            // 2-byte edges
        "\u0800\uD7FF\uE000",                      // 3-byte edges
        "\uD83D\uDE00\uD83D\uDC4D\uD83C\uDF89",    // emoji U+1F600 etc
        "\uD800\uDC00",                            // first astral U+10000
        "\uDBFF\uDFFF",                            // last astral U+10FFFF
        "a\u010D\uD83D\uDE00\u4E16z end",          // mixed widths
        "line1\nline2\ttab \u00E9\uD83D\uDE00"     // control + mix
    };

    public static void main(String[] args) {
        int before = passed;
        testRoundTrips();
        System.out.println("RoundTrips: " + (passed - before) + " checks OK");
        before = passed;
        testEncodeVsJdk();
        System.out.println("EncodeVsJdk: " + (passed - before) + " checks OK");
        before = passed;
        testDecodeVsJdk();
        System.out.println("DecodeVsJdk: " + (passed - before) + " checks OK");
        before = passed;
        testCesu8();
        System.out.println("Cesu8: " + (passed - before) + " checks OK");
        before = passed;
        testInvalid();
        System.out.println("Invalid: " + (passed - before) + " checks OK");
        before = passed;
        testEncodeSurrogates();
        System.out.println("EncodeSurrogates: " + (passed - before) + " checks OK");
        before = passed;
        testEmptyAndOffsets();
        System.out.println("EmptyAndOffsets: " + (passed - before) + " checks OK");
        before = passed;
        testFuzzNeverThrows();
        System.out.println("FuzzNeverThrows: " + (passed - before) + " checks OK");
        System.out.println("ALL PASS " + passed);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * Element-wise byte compare (two nulls equal). Hand-rolled because CLDC 1.1
     * has no java.util.Arrays and this file stays on the same API surface as
     * the code it tests.
     */
    static boolean bytesEq(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /** Builds a byte[] from int values, so test vectors read as hex literals. */
    static byte[] bytes(int[] vals) {
        byte[] b = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) {
            b[i] = (byte) vals[i];
        }
        return b;
    }

    /** The JDK's UTF-8 bytes for s; the conforming-stack oracle. */
    static byte[] jdk(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException("no UTF-8?");
        }
    }

    /** The JDK's decode of proper UTF-8 bytes; the conforming-stack oracle. */
    static String jdkDec(byte[] b) {
        try {
            return new String(b, 0, b.length, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException("no UTF-8?");
        }
    }

    /** Index of the first U+FFFD in s, or -1. */
    static int fffd(String s) {
        return s.indexOf('\uFFFD');
    }

    // ==================================================================
    // round trips
    // ==================================================================

    /**
     * The property every caller depends on: a note read back is the note that
     * was written. encode() then decode() must be the identity over the whole
     * well-formed corpus, and the emoji entries are the reason the class exists
     * at all - a CESU-8 platform codec would fail these on the device.
     */
    static void testRoundTrips() {
        for (int i = 0; i < CORPUS.length; i++) {
            String s = CORPUS[i];
            check(Utf8.decode(Utf8.encode(s)).equals(s), "rt " + i);
        }
        // Named single-code-point round trips, so a failure localises to the
        // exact width class rather than to a corpus blob.
        check(Utf8.decode(Utf8.encode("A")).equals("A"), "rt ascii");
        check(Utf8.decode(Utf8.encode("\u010D")).equals("\u010D"), "rt c-caron");
        check(Utf8.decode(Utf8.encode("\uD83D\uDE00")).equals("\uD83D\uDE00"),
              "rt emoji");
        check(Utf8.decode(Utf8.encode("\uDBFF\uDFFF")).equals("\uDBFF\uDFFF"),
              "rt max");
    }

    // ==================================================================
    // encode() vs the JDK
    // ==================================================================

    /**
     * Interop, not self-consistency: a round-trip-only codec could pass on a
     * private byte layout. This pins encode() to the exact bytes the JDK emits
     * for every well-formed corpus entry, which is what makes the device's
     * output readable by the desktop tools (nokcrypt.py, git) and vice versa.
     */
    static void testEncodeVsJdk() {
        for (int i = 0; i < CORPUS.length; i++) {
            check(bytesEq(Utf8.encode(CORPUS[i]), jdk(CORPUS[i])),
                  "enc jdk " + i);
        }
        // Spot-check the exact 4-byte spelling of U+1F600, so the test does not
        // merely trust the JDK oracle for the astral case it most cares about.
        check(bytesEq(Utf8.encode("\uD83D\uDE00"),
                      bytes(new int[] { 0xF0, 0x9F, 0x98, 0x80 })), "enc 1F600");
        check(bytesEq(Utf8.encode("\uDBFF\uDFFF"),
                      bytes(new int[] { 0xF4, 0x8F, 0xBF, 0xBF })), "enc 10FFFF");
    }

    // ==================================================================
    // decode() vs the JDK
    // ==================================================================

    /**
     * The decode direction of the same interop claim: proper UTF-8 the JDK
     * produced must decode back to the same String on our codec. The corpus is
     * well-formed, so this never exercises the CESU-8 or U+FFFD deviations -
     * those live in their own suites, which is what keeps this one a pure
     * "identical to a conforming stack" assertion.
     */
    static void testDecodeVsJdk() {
        for (int i = 0; i < CORPUS.length; i++) {
            byte[] b = jdk(CORPUS[i]);
            check(Utf8.decode(b).equals(jdkDec(b)), "dec jdk " + i);
            check(Utf8.decode(b).equals(CORPUS[i]), "dec eq src " + i);
        }
    }

    // ==================================================================
    // CESU-8 tolerance
    // ==================================================================

    /**
     * The first deliberate deviation from the JDK. A Symbian stack encodes
     * U+1F600 as two 3-byte surrogate halves (ED A0 BD, ED B8 80). decode()
     * emits each half unchanged; because a Java String is UTF-16, the two
     * halves reassemble into the emoji for free. Re-encoding then yields the
     * proper 4-byte form, which is how a note authored on the phone lands as
     * spec UTF-8 in the repo.
     */
    static void testCesu8() {
        byte[] cesu = bytes(new int[] {
            0xED, 0xA0, 0xBD,   // high surrogate D83D as a 3-byte form
            0xED, 0xB8, 0x80    // low surrogate DE00 as a 3-byte form
        });
        String out = Utf8.decode(cesu);
        check(out.equals("\uD83D\uDE00"), "cesu decode");
        check(out.length() == 2, "cesu len");
        check(bytesEq(Utf8.encode(out),
                      bytes(new int[] { 0xF0, 0x9F, 0x98, 0x80 })), "cesu reencode");
        // A lone 3-byte surrogate half with no partner stays a lone half (it is
        // not rejected); re-encoding an unpaired half is where encode() steps
        // in and substitutes U+FFFD's bytes.
        byte[] loneHalf = bytes(new int[] { 0xED, 0xA0, 0xBD });
        String half = Utf8.decode(loneHalf);
        check(half.length() == 1 && half.charAt(0) == '\uD83D', "cesu lone half");
        check(bytesEq(Utf8.encode(half),
                      bytes(new int[] { 0xEF, 0xBF, 0xBD })), "cesu lone reencode");
    }

    // ==================================================================
    // invalid input
    // ==================================================================

    /**
     * The second deviation: malformed bytes must never throw on the sync
     * worker, so every bad form degrades to U+FFFD and the decoder resyncs at
     * the next lead byte. Each vector pins one class of corruption and asserts
     * that a trailing valid 'A' still comes through, which is the resync claim.
     */
    static void testInvalid() {
        // Truncated 4-byte: valid lead + two valid continuations, tail missing.
        // The whole maximal subpart collapses to a single U+FFFD.
        String t = Utf8.decode(bytes(new int[] { 0xF0, 0x9F, 0x98 }));
        check(t.equals("\uFFFD"), "inv truncated4");

        // Truncated then a valid ASCII byte: one U+FFFD, then the 'A' resyncs.
        String tr = Utf8.decode(bytes(new int[] { 0xE0, 0x41 }));
        check(tr.equals("\uFFFDA"), "inv bad-cont resync");

        // Lone continuation byte with no lead.
        String lc = Utf8.decode(bytes(new int[] { 0x80, 0x41 }));
        check(lc.equals("\uFFFDA"), "inv lone-cont");

        // Overlong encoding of U+0000 (C0 80) must be rejected, not decoded to
        // NUL - accepting overlongs is a classic security hole.
        String ov = Utf8.decode(bytes(new int[] { 0xC0, 0x80, 0x41 }));
        check(ov.equals("\uFFFDA"), "inv overlong-nul");

        // Overlong 3-byte form of '/' (E0 80 AF) likewise rejected.
        String ov3 = Utf8.decode(bytes(new int[] { 0xE0, 0x80, 0xAF, 0x41 }));
        check(ov3.equals("\uFFFDA"), "inv overlong-slash");

        // 0xF8 is a 5-byte lead in the obsolete UTF-8 - never valid now.
        String f8 = Utf8.decode(bytes(new int[] { 0xF8, 0x41 }));
        check(f8.equals("\uFFFDA"), "inv f8-lead");

        // 0xFF is never a legal UTF-8 byte at all.
        String ff = Utf8.decode(bytes(new int[] { 0xFF, 0x41 }));
        check(ff.equals("\uFFFDA"), "inv ff-byte");

        // Astral value above U+10FFFF (F4 90 80 80 == U+110000) is out of range.
        String hi = Utf8.decode(bytes(new int[] { 0xF4, 0x90, 0x80, 0x80, 0x41 }));
        check(fffd(hi) == 0 && hi.endsWith("A"), "inv above-max");

        // Valid text on both sides of a bad byte proves the resync is local.
        String mid = Utf8.decode(bytes(new int[] { 0x41, 0xFF, 0x42 }));
        check(mid.equals("A\uFFFDB"), "inv mid-resync");
    }

    // ==================================================================
    // encode() of surrogates
    // ==================================================================

    /**
     * encode() must pair matched surrogates into one 4-byte sequence and
     * substitute U+FFFD's bytes for any unpaired half. The unpaired case is
     * where the class is deliberately stricter than the JDK, whose default
     * UTF-8 encoder emits a bare '?' - the byte-identity guarantee is scoped to
     * well-formed input only, and these vectors are ill-formed on purpose.
     */
    static void testEncodeSurrogates() {
        // Lone high surrogate.
        check(bytesEq(Utf8.encode("\uD83D"),
                      bytes(new int[] { 0xEF, 0xBF, 0xBD })), "enc lone-high");
        // Lone low surrogate.
        check(bytesEq(Utf8.encode("\uDE00"),
                      bytes(new int[] { 0xEF, 0xBF, 0xBD })), "enc lone-low");
        // High surrogate at end of string (no partner possible).
        check(bytesEq(Utf8.encode("A\uD83D"),
                      bytes(new int[] { 0x41, 0xEF, 0xBF, 0xBD })), "enc high-at-end");
        // High followed by a non-low char: high -> U+FFFD, the char itself
        // encodes normally right after.
        check(bytesEq(Utf8.encode("\uD83DA"),
                      bytes(new int[] { 0xEF, 0xBF, 0xBD, 0x41 })), "enc high-then-ascii");
        // Two highs in a row: each is unpaired.
        check(bytesEq(Utf8.encode("\uD83D\uD83D"),
                      bytes(new int[] { 0xEF, 0xBF, 0xBD, 0xEF, 0xBF, 0xBD })),
              "enc high-high");
        // A proper pair still becomes one 4-byte sequence, guarding against a
        // fix to the lone case that breaks the paired case.
        check(bytesEq(Utf8.encode("\uD83D\uDE00"),
                      bytes(new int[] { 0xF0, 0x9F, 0x98, 0x80 })), "enc proper-pair");
    }

    // ==================================================================
    // empty / null / offset handling
    // ==================================================================

    /**
     * The boundary contract: null and empty inputs answer empty rather than
     * throwing (callers hand these codecs the result of a possibly-empty file
     * read), and the decode(b,off,len) overload honours the window so a caller
     * can decode a slice of a larger buffer without copying it first.
     */
    static void testEmptyAndOffsets() {
        check(Utf8.encode(null).length == 0, "enc null");
        check(Utf8.encode("").length == 0, "enc empty");
        check(Utf8.decode(null).equals(""), "dec null");
        check(Utf8.decode(new byte[0]).equals(""), "dec empty");
        check(Utf8.decode(null, 0, 0).equals(""), "dec null off");

        // decode(b, off, len) reads only the window: the emoji sits between
        // guard bytes that must be ignored.
        byte[] framed = bytes(new int[] {
            0x58, 0x59,                     // 'X' 'Y' before the window
            0xF0, 0x9F, 0x98, 0x80,         // U+1F600
            0x5A                            // 'Z' after the window
        });
        check(Utf8.decode(framed, 2, 4).equals("\uD83D\uDE00"), "dec window");
        check(Utf8.decode(framed, 0, framed.length).equals("XY\uD83D\uDE00Z"),
              "dec full");
        check(Utf8.decode(framed, 2, 0).equals(""), "dec zero len");
    }

    // ==================================================================
    // fuzz: never throws
    // ==================================================================

    /**
     * The hard guarantee in one line: decode() of arbitrary bytes must return a
     * non-null String and never throw, because it runs on the sync worker over
     * whatever a flaky FileConnection hands back. A seeded Random keeps a
     * failure reproducible.
     *
     * <p>The stability claim is deliberately a fixed point AFTER the first
     * encode, not decode(encode(s)).equals(s). decode() can hand back a lone
     * surrogate (a single CESU-8 half with no partner), which encode() then
     * collapses to U+FFFD's bytes - so the raw round trip is lossy exactly
     * once, on purpose, for that ill-formed half. But encode()'s output is
     * always well-formed UTF-8, so re = encode(decode(b)) contains no lone
     * surrogates, and decoding then re-encoding re must reproduce it byte for
     * byte. That is the invariant a note's on-disk bytes actually rely on:
     * once written, every later read/write cycle is a no-op.
     */
    static void testFuzzNeverThrows() {
        Random rnd = new Random(20260724L);
        for (int t = 0; t < 4000; t++) {
            int len = rnd.nextInt(24);
            byte[] b = new byte[len];
            for (int i = 0; i < len; i++) {
                b[i] = (byte) rnd.nextInt();
            }
            String s = Utf8.decode(b);
            check(s != null, "fuzz nonnull " + t);
            // re is well-formed UTF-8; decoding and re-encoding it is a no-op.
            byte[] re = Utf8.encode(s);
            check(bytesEq(Utf8.encode(Utf8.decode(re)), re), "fuzz stable " + t);
        }
    }
}
