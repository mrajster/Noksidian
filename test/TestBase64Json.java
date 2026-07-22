import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Random;
import java.util.Vector;

import nok.core.Base64;
import nok.core.Json;

/**
 * Desktop-JVM tests for nok.core.Base64 and nok.core.Json.
 * Java 1.3 syntax, CLDC-compatible APIs only (Vector/Hashtable/Random).
 *
 * These two classes are group A of CONTRACTS.md and sit under everything the
 * sync engine does: GitHub.java parses every response through Json and moves
 * every file body through Base64 in both directions. CONTRACTS.md forbids
 * nok.core.* from importing javax.*, which is what lets this file run under
 * a plain JVM with no emulator, no MIDP stubs and no device in the loop.
 *
 * No JUnit: test.sh drives the JDK 8 vendored in tools/jdk8, and CONTRACTS.md
 * fixes the harness shape (a plain static main, a local check(), one line per
 * group and "ALL PASS n" at the end). Failure is signalled by throwing, so
 * the nonzero exit trips the shell script's `set -e`.
 *
 * The suites run from primitives outward: base64 alphabet and padding, then
 * JSON scalars, strings and structures, then realistic GitHub payloads that
 * combine both, then the writer, round trips, typed helpers and the rejection
 * table. Everything is deterministic - the one Random is seeded - so a
 * failing assertion name always reproduces.
 */
public class TestBase64Json {

    // Global running total rather than a per-suite counter: main() prints the
    // delta around each group, which localises a regression to a group without
    // every suite having to thread a count back out.
    static int passed = 0;

    /**
     * Fails the whole run on the first false condition, as CONTRACTS.md
     * requires. Aborting beats collecting failures because the suites build on
     * each other - once the alphabet is wrong, every later base64 assertion is
     * noise - and the exception message is the assertion name, which is the
     * only diagnostic the shell run gives you.
     */
    static void check(boolean cond, String name) {
        if (!cond) {
            throw new RuntimeException(name);
        }
        passed++;
    }

    public static void main(String[] args) {
        int before = passed;
        testBase64();
        System.out.println("Base64: " + (passed - before) + " checks OK");
        before = passed;
        testJson();
        System.out.println("Json: " + (passed - before) + " checks OK");
        System.out.println("ALL PASS " + passed);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * Latin-1 truncating conversion, used to turn the RFC 4648 test strings
     * into the byte arrays Base64 actually takes. Deliberately not
     * String.getBytes(), which consults the platform default encoding and
     * would make the expected side of a test vector depend on the developer's
     * locale. Every string passed here is ASCII, so the cast is exact.
     */
    static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }

    /**
     * Element-wise array compare that treats two nulls as equal and a null
     * against an array as unequal. Hand-rolled because CLDC 1.1 has no
     * java.util.Arrays, and the header above commits this file to the same
     * CLDC-only API surface as the code it exercises.
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

    /**
     * True when decode() rejected the input, so a rejection reads as one
     * check() call like every other assertion. The narrow catch is the point:
     * Base64 documents IllegalArgumentException, so a NullPointerException or
     * an ArrayIndexOutOfBoundsException escaping from a malformed payload
     * propagates and fails the run instead of passing as "it threw".
     */
    static boolean badB64(String s) {
        try {
            Base64.decode(s);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** Same inverted check for parse(); see badB64 for why the catch is narrow. */
    static boolean badJson(String s) {
        try {
            Json.parse(s);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /**
     * Structural equality over a parsed JSON tree. Needed because CLDC 1.1's
     * Hashtable and Vector define no equals of their own, so two structurally
     * identical trees compare unequal there; recursing by hand makes the
     * comparison mean the same thing at every level, which is the whole claim
     * a round-trip test is making.
     *
     * The final a.equals(b) covers String/Long/Double/Boolean, and reaches
     * Json.NULL only against itself, since the sentinel is a singleton whose
     * inherited equals is identity anyway.
     */
    static boolean deepEq(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Hashtable && b instanceof Hashtable) {
            Hashtable ha = (Hashtable) a;
            Hashtable hb = (Hashtable) b;
            if (ha.size() != hb.size()) {
                return false;
            }
            Enumeration e = ha.keys();
            while (e.hasMoreElements()) {
                Object k = e.nextElement();
                if (!hb.containsKey(k)) {
                    return false;
                }
                if (!deepEq(ha.get(k), hb.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Vector && b instanceof Vector) {
            Vector va = (Vector) a;
            Vector vb = (Vector) b;
            if (va.size() != vb.size()) {
                return false;
            }
            for (int i = 0; i < va.size(); i++) {
                if (!deepEq(va.elementAt(i), vb.elementAt(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    // These two assert the parser's boxing choice as a side effect: casting to
    // the exact box means a number that came back as the other one fails the
    // run with a ClassCastException, instead of quietly comparing equal after
    // a widening conversion.
    static long asLong(Object v) {
        return ((Long) v).longValue();
    }

    static double asDouble(Object v) {
        return ((Double) v).doubleValue();
    }

    // ==================================================================
    // Base64
    // ==================================================================

    /**
     * Proves the codec is interoperable, not merely self-consistent. A
     * round-trip-only suite would pass happily on a private alphabet, so the
     * first block pins the output against published vectors and the later
     * blocks pin the leniencies the GitHub API forces on the decoder.
     */
    static void testBase64() {
        // --- RFC 4648 known vectors, encode and decode ---
        // Verbatim from RFC 4648 section 10. All seven lengths are kept
        // because 0..6 walks the length-mod-3 tail cases more than once each,
        // so a tail bug cannot hide behind one lucky length.
        String[] plain = { "", "f", "fo", "foo", "foob", "fooba", "foobar" };
        String[] enc = { "", "Zg==", "Zm8=", "Zm9v", "Zm9vYg==",
                         "Zm9vYmE=", "Zm9vYmFy" };
        for (int i = 0; i < plain.length; i++) {
            check(Base64.encode(ascii(plain[i])).equals(enc[i]),
                  "b64 rfc encode " + i);
            check(bytesEq(Base64.decode(enc[i]), ascii(plain[i])),
                  "b64 rfc decode " + i);
        }

        // --- round trips of every length 0..70, pseudo-random bytes ---
        // Fixed seed so a failure name reproduces exactly; an unseeded Random
        // would make "b64 rt 37" unreproducible on the next run. The explicit
        // "b64 no breaks" assertion exists because line breaks in the encoder
        // output would still round-trip here - our own decoder skips
        // whitespace - while breaking any stricter consumer.
        Random rnd = new Random(424242L);
        for (int len = 0; len <= 70; len++) {
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) {
                data[i] = (byte) rnd.nextInt();
            }
            String e = Base64.encode(data);
            check(e.length() == ((len + 2) / 3) * 4, "b64 len " + len);
            check(e.indexOf('\n') < 0 && e.indexOf('\r') < 0,
                  "b64 no breaks " + len);
            check(bytesEq(Base64.decode(e), data), "b64 rt " + len);
        }

        // --- padding correctness by length mod 3 ---
        check(Base64.encode(new byte[1]).endsWith("=="), "b64 pad 1");
        check(Base64.encode(new byte[2]).endsWith("=")
              && !Base64.encode(new byte[2]).endsWith("=="), "b64 pad 2");
        check(!Base64.encode(new byte[3]).endsWith("="), "b64 pad 3");

        // --- all binary byte values 0..255 ---
        // Java bytes are signed, so this is the sign-extension guard: every
        // value >= 0x80 goes through the encoder's & 0xFF masking. Without the
        // masks the ones from a negative byte flood the neighbouring sextets,
        // which is invisible on ASCII test vectors and corrupts every image
        // and every encrypted note the moment a real file is synced.
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        check(bytesEq(Base64.decode(Base64.encode(all)), all), "b64 all bytes");

        // --- GitHub style: newline every 60 chars must decode ---
        // Not a hypothetical: GET /git/blobs/{sha} returns "content" wrapped
        // at 60 columns, and GitHub.getBlob hands that payload to decode()
        // uncleaned. Two payloads rather than one because the encoded lengths
        // differ, so the final short line lands differently in each; the
        // second also ends on a trailing newline after the last full line.
        byte[] big = new byte[300];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        String be = Base64.encode(big);
        StringBuffer withNl = new StringBuffer();
        for (int i = 0; i < be.length(); i++) {
            if (i > 0 && i % 60 == 0) {
                withNl.append('\n');
            }
            withNl.append(be.charAt(i));
        }
        withNl.append('\n');
        check(bytesEq(Base64.decode(withNl.toString()), big), "b64 nl60");

        // newline every 60 for the 0..255 payload too
        String ae = Base64.encode(all);
        StringBuffer anl = new StringBuffer();
        for (int i = 0; i < ae.length(); i++) {
            anl.append(ae.charAt(i));
            if ((i + 1) % 60 == 0) {
                anl.append('\n');
            }
        }
        check(bytesEq(Base64.decode(anl.toString()), all), "b64 nl60 all");

        // --- whitespace tolerance: space, tab, CR, LF scattered ---
        // Exactly the four characters CONTRACTS.md names as JSON/base64
        // whitespace, and no others - "b64 unicode ws" below pins that U+2028
        // is still rejected, so the skip set cannot be widened into a general
        // "ignore anything unprintable". The null and empty cases pin the
        // documented "returns an empty array" behaviour; no caller relies on
        // it today, both go through non-null strings.
        check(bytesEq(Base64.decode(" Z m 9\tv\r\nY m F y "),
                      ascii("foobar")), "b64 ws mix");
        check(bytesEq(Base64.decode("Zg==\n"), ascii("f")), "b64 trail nl");
        check(bytesEq(Base64.decode("\r\nZm8=\r\n"), ascii("fo")),
              "b64 crlf wrap");
        check(Base64.decode("").length == 0, "b64 empty");
        check(Base64.decode("  \t\r\n ").length == 0, "b64 all ws");
        check(Base64.decode(null).length == 0, "b64 null");
        check(Base64.encode(null).equals(""), "b64 enc null");
        check(Base64.encode(new byte[0]).equals(""), "b64 enc empty");

        // --- missing padding tolerated ---
        // The decoder gets this for free by treating '=' as skippable filler
        // rather than as a terminator, which also means it never checks that
        // padding sits only at the end. "b64 pad eq nopad" pins the padded and
        // unpadded spellings to the same bytes, so the leniency cannot regress
        // into a length bug that silently drops the tail.
        check(bytesEq(Base64.decode("Zg"), ascii("f")), "b64 nopad 1");
        check(bytesEq(Base64.decode("Zm8"), ascii("fo")), "b64 nopad 2");
        check(bytesEq(Base64.decode("Zm9vYg"), ascii("foob")), "b64 nopad 3");
        check(bytesEq(Base64.decode("Zm9vYmE"), ascii("fooba")),
              "b64 nopad 4");
        check(bytesEq(Base64.decode("Zg=="), Base64.decode("Zg")),
              "b64 pad eq nopad");

        // --- '+' and '/' chars (values 62/63) ---
        // Indices 62 and 63 are the only two positions where the standard
        // alphabet differs from the URL-safe one ('-' and '_'), so these four
        // assertions are the guard against someone swapping in the URL-safe
        // table. Both the GitHub API and our own RMS config store standard
        // base64; a swap would corrupt silently rather than throw. The byte
        // triples are chosen to make all four sextets come out as 62 (0xFB
        // 0xEF 0xBE) and as 63 (0xFF 0xFF 0xFF).
        byte[] pf = { (byte) 0xFB, (byte) 0xEF, (byte) 0xBE };
        check(Base64.encode(pf).equals("++++"), "b64 plus");
        byte[] sl = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        check(Base64.encode(sl).equals("////"), "b64 slash");
        check(bytesEq(Base64.decode("++++"), pf), "b64 plus dec");
        check(bytesEq(Base64.decode("////"), sl), "b64 slash dec");

        // --- invalid input throws ---
        // Corruption must fail loudly rather than produce short or garbled
        // bytes, because the result is written straight into the vault as a
        // note or an image. The lone-char and 5-char cases are the length
        // guard: a significant-char count of n mod 4 == 1 carries 6 bits,
        // which no valid encoder can emit, so it means a truncated transfer.
        // The last two are the high-char guard - the decode table is only 128
        // entries, so anything >= 128 has to be range-checked before it is
        // used as an index, or a non-ASCII char walks off the end of it.
        check(badB64("Zm9*"), "b64 bad char");
        check(badB64("Zm9v!"), "b64 bang");
        check(badB64("A"), "b64 lone char");
        check(badB64("AAAAA"), "b64 5 chars");
        check(badB64("Zg==,"), "b64 comma");
        check(badB64("Z" + (char) 0xE9 + "g=="), "b64 high char");
        check(badB64("Zg=" + (char) 0x2028), "b64 unicode ws");
    }

    // ==================================================================
    // Json
    // ==================================================================

    /**
     * Split into named sub-suites rather than one long method partly for
     * readability and partly because CONTRACTS.md caps every method at ~150
     * lines (old verifiers choke on huge ones); the tests keep to the same
     * rule as the code they exercise.
     */
    static void testJson() {
        testJsonScalars();
        testJsonStrings();
        testJsonStructures();
        testJsonGitHub();
        testJsonWriter();
        testJsonRoundTrip();
        testJsonHelpers();
        testJsonBadInput();
    }

    /**
     * Pins the Long-versus-Double decision, which is the one parser choice a
     * caller can actually be hurt by: file sizes and sync timestamps must come
     * back integral and exact, so a parser that routed everything through
     * Double would corrupt a millisecond timestamp past 2^53. The number
     * literals sit on the long-range boundary rather than being pretty.
     */
    static void testJsonScalars() {
        check(Json.parse("true").equals(Boolean.TRUE), "js true");
        check(Json.parse("false").equals(Boolean.FALSE), "js false");
        // Identity, not equals: Json.NULL is a singleton sentinel standing in
        // for a value Hashtable cannot store, so parse() must hand back that
        // exact object for a == test to work anywhere downstream.
        check(Json.parse("null") == Json.NULL, "js null identity");
        check(Json.parse(" \t\r\n 5 \r\n") instanceof Long, "js ws long");
        check(asLong(Json.parse("42")) == 42L, "js 42");
        check(asLong(Json.parse("0")) == 0L, "js 0");
        check(asLong(Json.parse("-7")) == -7L, "js -7");
        check(asLong(Json.parse("-0")) == 0L, "js -0");
        // The two long extremes are the last tokens Long.parseLong accepts;
        // one digit more and the parser must fall through to Double instead of
        // throwing, which is what the next check pins. That fallback is the
        // reason a huge number in an API response degrades in precision rather
        // than aborting the whole sync pass.
        check(asLong(Json.parse("9223372036854775807"))
              == Long.MAX_VALUE, "js long max");
        check(asLong(Json.parse("-9223372036854775808"))
              == Long.MIN_VALUE, "js long min");
        // integral overflowing long falls back to Double
        check(Json.parse("92233720368547758070") instanceof Double,
              "js overflow dbl");
        // Exponent forms in all four spellings the grammar allows (e/E, with
        // and without an explicit sign). They matter because the number
        // scanner is deliberately dumb - it grabs a greedy run of number-ish
        // chars and lets Double.parseDouble judge it - so these confirm the
        // greedy run does not stop early at 'e', '+' or '-'. "js 2.0 dbl" is
        // the other half of the rule: a '.' forces Double even when the value
        // is integral, so the type follows the spelling, not the magnitude.
        check(Json.parse("3.14") instanceof Double, "js dbl type");
        check(asDouble(Json.parse("3.14")) == 3.14, "js 3.14");
        check(asDouble(Json.parse("-2.5e3")) == -2500.0, "js -2.5e3");
        check(asDouble(Json.parse("1E2")) == 100.0, "js 1E2");
        check(asDouble(Json.parse("1e-2")) == 0.01, "js 1e-2");
        check(asDouble(Json.parse("2E+3")) == 2000.0, "js 2E+3");
        check(Json.parse("2.0") instanceof Double, "js 2.0 dbl");
        check(asDouble(Json.parse("-0.5")) == -0.5, "js -0.5");
    }

    /**
     * Walks every escape the JSON grammar defines, one per assertion, because
     * an escape table is exactly the kind of code where a single wrong case
     * label survives every realistic payload and then mangles one note. Note
     * titles are user text, so '"', '\\', '/' and newlines all arrive here in
     * practice; \\b and \\f effectively never do, so this suite is where the
     * parser side of them is proven at all.
     */
    static void testJsonStrings() {
        check(Json.parse("\"\"").equals(""), "js empty str");
        check(Json.parse("\"hello\"").equals("hello"), "js hello");
        check(Json.parse("\"a\\\"b\"").equals("a\"b"), "js esc quote");
        check(Json.parse("\"a\\\\b\"").equals("a\\b"), "js esc bslash");
        check(Json.parse("\"a\\/b\"").equals("a/b"), "js esc slash");
        check(Json.parse("\"a\\nb\"").equals("a\nb"), "js esc n");
        check(Json.parse("\"a\\rb\"").equals("a\rb"), "js esc r");
        check(Json.parse("\"a\\tb\"").equals("a\tb"), "js esc t");
        check(Json.parse("\"a\\bb\"").equals("a\bb"), "js esc b");
        check(Json.parse("\"a\\fb\"").equals("a\fb"), "js esc f");
        // \\u coverage in ascending width: ASCII, Latin-1 in both hex cases
        // (the digit decoder handles 'a'-'f' and 'A'-'F' on separate branches,
        // so both must be walked), CJK above 0x7FF, and NUL - which is legal
        // in a JSON string and must not be mistaken for a terminator by any
        // C-flavoured shortcut.
        check(Json.parse("\"\\u0041\"").equals("A"), "js u0041");
        check(Json.parse("\"\\u00e9\"").equals("" + (char) 0xE9),
              "js u00e9 lower");
        check(Json.parse("\"\\u00E9\"").equals("" + (char) 0xE9),
              "js u00E9 upper");
        check(Json.parse("\"\\u4e16\"").equals("" + (char) 0x4E16),
              "js u4e16");
        check(Json.parse("\"\\u0000\"").equals("" + (char) 0), "js u0000");
        // Astral characters reach the parser as two consecutive surrogate
        // escapes when a producer escapes them. readHex4 deliberately has no
        // pairing logic: each escape appends one UTF-16 code unit and a Java
        // String is UTF-16, so the pair reassembles for free. This asserts
        // exactly that - two chars, the raw halves kept - so that anyone
        // "helpfully" combining them into a code point fails here.
        String sp = (String) Json.parse("\"\\ud83d\\ude00\"");
        check(sp.length() == 2 && sp.charAt(0) == (char) 0xD83D
              && sp.charAt(1) == (char) 0xDE00, "js surrogate pair");
        check(Json.parse("\"mix\\u0041\\n\\t\\\"end\"")
                  .equals("mixA\n\t\"end"), "js mixed esc");
    }

    /**
     * Container assembly: empties, whitespace between every structural token,
     * heterogeneous elements, nesting, escaped keys and duplicate keys. The
     * mixed array is one literal rather than seven so it also proves the
     * element separator logic survives a type change between neighbours.
     */
    static void testJsonStructures() {
        check(((Vector) Json.parse("[]")).size() == 0, "js empty arr");
        check(((Hashtable) Json.parse("{}")).size() == 0, "js empty obj");
        check(((Vector) Json.parse(" [ ] ")).size() == 0, "js ws arr");
        check(((Hashtable) Json.parse(" { } ")).size() == 0, "js ws obj");

        Vector v = (Vector) Json.parse("[1,\"a\",true,null,2.5,[2],{\"k\":3}]");
        check(v.size() == 7, "js arr size");
        check(asLong(v.elementAt(0)) == 1L, "js arr 0");
        check(v.elementAt(1).equals("a"), "js arr 1");
        check(v.elementAt(2).equals(Boolean.TRUE), "js arr 2");
        check(v.elementAt(3) == Json.NULL, "js arr 3");
        check(asDouble(v.elementAt(4)) == 2.5, "js arr 4");
        check(asLong(((Vector) v.elementAt(5)).elementAt(0)) == 2L,
              "js arr nested");
        check(Json.num((Hashtable) v.elementAt(6), "k", -1) == 3L,
              "js arr obj");

        Hashtable h = (Hashtable) Json.parse(
            " { \"a\" : [ 1 , 2 ] , \"b\" : { \"c\" : \"d\" } , \"e\":null } ");
        check(h.size() == 3, "js obj size");
        check(((Vector) h.get("a")).size() == 2, "js obj arr");
        check(Json.str((Hashtable) h.get("b"), "c").equals("d"),
              "js obj nested");
        check(h.get("e") == Json.NULL, "js obj null val");

        // A key is a full JSON string, not a bare identifier, so it must go
        // through the same unescaping as a value - vault paths become keys in
        // state.json and can contain anything a filename can.
        Hashtable ek = (Hashtable) Json.parse("{\"a\\nb\":1}");
        check(asLong(ek.get("a\nb")) == 1L, "js escaped key");

        // The parser is recursive descent, so nesting depth costs Java stack.
        // Six levels is far past anything the GitHub API or state.json emits
        // and just confirms the recursion unwinds correctly; the parser has no
        // depth cap, which is acceptable only because every document it sees
        // comes from our own repo or from GitHub.
        Vector deep = (Vector) Json.parse("[[[[[[1]]]]]]");
        for (int i = 0; i < 5; i++) {
            deep = (Vector) deep.elementAt(0);
        }
        check(asLong(deep.elementAt(0)) == 1L, "js deep nest");

        // duplicate keys: last wins
        // JSON leaves this undefined; pinning it here documents that we take
        // the Hashtable.put behaviour rather than rejecting the document, so a
        // future stricter parser would have to change this test on purpose.
        Hashtable dup = (Hashtable) Json.parse("{\"a\":1,\"a\":2}");
        check(dup.size() == 1 && asLong(dup.get("a")) == 2L, "js dup key");
    }

    /**
     * Integration-flavoured suite: the three response shapes Sync actually
     * consumes, parsed through the same typed helpers the engine uses. Unit
     * coverage of parse() alone would not catch a wrong assumption about the
     * payload, and the alternative - hitting the live API from a test - is not
     * reproducible and needs a token.
     *
     * The fixtures are trimmed copies of real responses (the shas and the
     * octocat/Hello-World URLs come from GitHub's own documented examples),
     * left multi-line with mixed whitespace rather than minified, because
     * tolerating whitespace between structural tokens is part of what is
     * under test. Fields the engine never reads are kept for the same reason:
     * a parser that only works on the fields we read is not proven.
     */
    static void testJsonGitHub() {
        // realistic GET /repos/{o}/{r}/git/trees/{branch}?recursive=1 response
        String tree = "{\n"
            + "  \"sha\": \"9fb037999f264ba9a7fc6274d15fa3ae2ab98312\",\n"
            + "  \"url\": \"https://api.github.com/repos/octocat/Hello-World/"
            + "git/trees/9fb037999f264ba9a7fc6274d15fa3ae2ab98312\",\n"
            + "  \"tree\": [\n"
            + "    {\"path\": \"README.md\", \"mode\": \"100644\","
            + " \"type\": \"blob\","
            + " \"sha\": \"44b4fc6d56897b048c772eb4087f854f46256132\","
            + " \"size\": 132,"
            + " \"url\": \"https://api.github.com/repos/octocat/Hello-World/"
            + "git/blobs/44b4fc6d56897b048c772eb4087f854f46256132\"},\n"
            + "    {\"path\": \"notes\", \"mode\": \"040000\","
            + " \"type\": \"tree\","
            + " \"sha\": \"f484d249c660418515fb01c2b9662073663c242e\"},\n"
            + "    {\"path\": \"notes/daily/2026-07-01.md\","
            + " \"mode\": \"100644\", \"type\": \"blob\","
            + " \"sha\": \"abc123\", \"size\": 2048,"
            + " \"url\": \"https://api.github.com/x\"}\n"
            + "  ],\n"
            + "  \"truncated\": false\n"
            + "}";
        Hashtable root = Json.obj(Json.parse(tree));
        check(root != null, "gh root obj");
        check(Json.str(root, "sha")
                  .equals("9fb037999f264ba9a7fc6274d15fa3ae2ab98312"),
              "gh sha");
        check(!Json.bool(root, "truncated", true), "gh truncated");
        Vector entries = Json.arr(root.get("tree"));
        check(entries != null && entries.size() == 3, "gh tree size");
        Hashtable e0 = Json.obj(entries.elementAt(0));
        check(Json.str(e0, "path").equals("README.md"), "gh e0 path");
        check(Json.str(e0, "type").equals("blob"), "gh e0 type");
        check(Json.num(e0, "size", -1) == 132L, "gh e0 size");
        // Entry 1 is a directory, and the API omits "size" entirely on
        // type=="tree" entries - absent, not null and not zero. That absence
        // is why num() takes a default at all; GitHub.listTree drops these
        // entries on "type" before size is ever consulted, so the -1 here is
        // the test asking the question the engine does not have to.
        Hashtable e1 = Json.obj(entries.elementAt(1));
        check(Json.str(e1, "type").equals("tree"), "gh e1 type");
        check(Json.num(e1, "size", -1) == -1L, "gh e1 no size");
        // recursive=1 flattens the tree, so nested paths arrive as one slashed
        // string rather than as nested objects; GitHub.listTree relies on that
        // to map an entry onto a vault path without walking anything.
        Hashtable e2 = Json.obj(entries.elementAt(2));
        check(Json.str(e2, "path").equals("notes/daily/2026-07-01.md"),
              "gh e2 path");
        check(Json.num(e2, "size", 0) == 2048L, "gh e2 size");

        // realistic blob response: base64 content with embedded newlines
        // The end-to-end check for the pull path, and the only place this file
        // wires the two classes together: GitHub wraps blob base64 at 60
        // columns, so the newlines arrive as \\n escapes inside the JSON
        // string, come out of the parser as real newlines, and must then be
        // tolerated by Base64.decode. Breaking either half - a parser that
        // dropped them or a decoder that rejected them - would leave getBlob
        // unable to pull any file over 45 bytes.
        String blob = "{\"sha\": \"s1\", \"node_id\": \"MDQ6\","
            + " \"size\": 11, \"url\": \"https://api.github.com/b\","
            + " \"content\": \"SGVsbG8g\\nd29ybGQ=\\n\","
            + " \"encoding\": \"base64\"}";
        Hashtable b = Json.obj(Json.parse(blob));
        check(Json.str(b, "encoding").equals("base64"), "gh blob enc");
        String content = Json.str(b, "content");
        check(content.indexOf('\n') >= 0, "gh blob has nl");
        byte[] decoded = Base64.decode(content);
        check(bytesEq(decoded, ascii("Hello world")), "gh blob decode");
        // Cross-checking the API's own "size" against the decoded length is a
        // cheap end-to-end assertion: a padding or tail bug that produced
        // bytes at all would land here as an off-by-one or -two.
        check(Json.num(b, "size", -1) == (long) decoded.length,
              "gh blob size");

        // contents PUT response fragment
        // Nested-object read on the push path: after a write, the NEW blob sha
        // lives at content.sha, while commit.sha is the commit it landed in.
        // GitHub.putFile returns content.sha and Sync stores it as the file's
        // known sha; reading the wrong one would make the next pass send a
        // stale sha and get a 409 back as a phantom conflict.
        String put = "{\"content\": {\"name\": \"a.md\","
            + " \"path\": \"dir/a.md\", \"sha\": \"newsha42\"},"
            + " \"commit\": {\"sha\": \"c1\","
            + " \"message\": \"Noksidian: update dir/a.md\"}}";
        Hashtable pr = Json.obj(Json.parse(put));
        check(Json.str(Json.obj(pr.get("content")), "sha").equals("newsha42"),
              "gh put sha");
    }

    /**
     * Asserts the writer's exact byte output, not just that it round-trips
     * through our own parser. That distinction matters because the output goes
     * to GitHub's parser, which is far stricter than ours: a self-consistent
     * but non-conforming escaping would pass a round-trip test here and be
     * rejected by the API with an opaque 400 on the device.
     */
    static void testJsonWriter() {
        check(Json.write(new Long(5L)).equals("5"), "jw long");
        check(Json.write(new Long(-12L)).equals("-12"), "jw neg long");
        check(Json.write(Boolean.TRUE).equals("true"), "jw true");
        check(Json.write(Boolean.FALSE).equals("false"), "jw false");
        // Java null and the Json.NULL sentinel must both write as "null", so
        // a caller assembling a request body never has to know which of the
        // two a parsed subtree handed it.
        check(Json.write(Json.NULL).equals("null"), "jw NULL");
        check(Json.write(null).equals("null"), "jw java null");
        check(Json.write("").equals("\"\""), "jw empty str");
        check(Json.write("hi").equals("\"hi\""), "jw hi");
        check(Json.write(new Double(2.5)).equals("2.5"), "jw double");
        check(Json.write(new Vector()).equals("[]"), "jw empty arr");
        check(Json.write(new Hashtable()).equals("{}"), "jw empty obj");

        Vector v = new Vector();
        v.addElement(new Long(1L));
        v.addElement(new Long(2L));
        v.addElement(new Long(3L));
        check(Json.write(v).equals("[1,2,3]"), "jw arr 123");

        // Exactly one entry, deliberately: Hashtable iteration order is
        // unspecified, so a two-key object has no single correct spelling and
        // could not be pinned as a string literal. Multi-key objects are
        // therefore only ever checked through deepEq in the round-trip suite.
        Hashtable one = new Hashtable();
        one.put("a", new Long(1L));
        check(Json.write(one).equals("{\"a\":1}"), "jw obj one");

        // exact escape output: quote, backslash, n, r, t named;
        // other chars below 0x20 as backslash-u00XX
        String in = "a\"b\\c\nd\re\tf" + (char) 1 + "g" + (char) 0 + "h"
            + (char) 0x1F + "i";
        String expect = "\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\\u0000h\\u001fi\"";
        check(Json.write(in).equals(expect), "jw escapes exact");
        // Writer and parser are deliberately asymmetric, and these two pin the
        // asymmetry so nobody "fixes" it: the parser accepts \\b and \\f, but
        // the writer emits them numerically. Only five named escapes exist on
        // the write side, so backspace and form feed fall to the generic
        // below-0x20 path. The expected hex is lowercase because the writer's
        // nibble table is - an uppercase \\u001F would still be valid JSON,
        // which is exactly why it has to be pinned rather than round-tripped.
        check(Json.write("" + (char) 8).equals("\"\\u0008\""), "jw bs ctl");
        check(Json.write("" + (char) 12).equals("\"\\u000c\""), "jw ff ctl");
        // chars >= 0x20 pass through unescaped (incl / and non-ascii)
        // '/' is the important one: escaping it is legal but pointless, and
        // vault paths are full of slashes, so leaving them raw keeps request
        // bodies readable and shorter. Non-ASCII is emitted raw rather than as
        // \\uXXXX because the body is sent as UTF-8; escaping it would be a
        // pure size cost on a phone's connection.
        check(Json.write("a/b").equals("\"a/b\""), "jw slash raw");
        check(Json.write("" + (char) 0xE9).equals("\"" + (char) 0xE9 + "\""),
              "jw high raw");
        // key needing escapes
        Hashtable ke = new Hashtable();
        ke.put("a\nb", Boolean.TRUE);
        check(Json.write(ke).equals("{\"a\\nb\":true}"), "jw esc key");

        // unsupported type throws
        // Failing loudly beats emitting toString() of whatever was passed:
        // Java 1.3 has no generics to catch a stray object at compile time, so
        // a silent fallback would ship a malformed body to the API.
        boolean threw = false;
        try {
            Json.write(new Object());
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "jw bad type");
    }

    /**
     * Proves write() and parse() are mutual inverses over a tree containing
     * every supported type. This is the property state.json depends on: it is
     * written by write() and read back by parse() on the next launch, so any
     * asymmetry between the two shows up as lost sync state and a vault that
     * re-pulls or re-pushes everything.
     */
    static void testJsonRoundTrip() {
        // build a structure with every value type, 3 levels deep
        Hashtable root = new Hashtable();
        root.put("title", new String("Note \"one\"\nline2\ttab"));
        // 2^53 + 1, the smallest integer a double cannot represent. If the
        // writer or parser ever routed integral values through Double this
        // value would come back as 2^53 and the check would fail; a rounder
        // literal would survive the trip and prove nothing.
        root.put("count", new Long(9007199254740993L));
        root.put("ratio", new Double(-0.125));
        root.put("done", Boolean.FALSE);
        root.put("nothing", Json.NULL);
        Vector tags = new Vector();
        tags.addElement("alpha");
        tags.addElement(new Long(-3L));
        tags.addElement(Boolean.TRUE);
        tags.addElement(Json.NULL);
        Hashtable inner = new Hashtable();
        inner.put("path", "dir/sub/file name.md");
        inner.put("size", new Double(1.5E10));
        tags.addElement(inner);
        root.put("tags", tags);
        root.put("empty_o", new Hashtable());
        root.put("empty_a", new Vector());

        Object back = Json.parse(Json.write(root));
        check(deepEq(root, back), "rt structure");
        // double round trip is stable
        // One pass could still hide a lossy step that happens to be idempotent
        // after the first write; running it twice catches drift, which for
        // state.json would mean a file that mutates a little on every sync.
        check(deepEq(back, Json.parse(Json.write(back))), "rt twice");

        // round trip of parsed github-ish text
        String src = "{\"a\":[1,2.5,\"x\\u0041\",true,null],"
            + "\"b\":{\"c\":[[]],\"d\":\"\"}}";
        Object p1 = Json.parse(src);
        Object p2 = Json.parse(Json.write(p1));
        check(deepEq(p1, p2), "rt reparse");

        // scalar round trips
        check(Json.parse(Json.write(new Long(Long.MIN_VALUE)))
                  .equals(new Long(Long.MIN_VALUE)), "rt long min");
        check(asDouble(Json.parse(Json.write(new Double(1.0E100))))
              == 1.0E100, "rt 1e100");
        // Windows-style path with backslashes, a CRLF, quotes and a raw BEL:
        // a note title can legitimately hold all of these, and every one of
        // them takes a different branch of the escaper.
        String tricky = "\\path\\to\r\n\"x\" " + (char) 7;
        check(Json.parse(Json.write(tricky)).equals(tricky), "rt tricky str");
    }

    /**
     * The typed accessors are why Sync and GitHub walk API responses with
     * almost no instanceof or cast of their own: none of them throw, and a
     * wrong type is treated exactly like a missing key. This suite walks the
     * whole grid - present-and-right, present-but-wrong-type, absent, JSON
     * null and a null container - because a half-hardened helper turns an
     * unexpected response into a ClassCastException on the sync worker
     * thread, where it surfaces to the user only as a failed pass.
     */
    static void testJsonHelpers() {
        Hashtable h = (Hashtable) Json.parse(
            "{\"s\":\"v\",\"l\":7,\"d\":2.9,\"b\":true,\"n\":null,"
            + "\"o\":{},\"a\":[]}");
        check(Json.obj(h) == h, "hp obj self");
        check(Json.obj("x") == null, "hp obj wrong");
        check(Json.obj(null) == null, "hp obj null");
        check(Json.arr(h.get("a")) != null, "hp arr ok");
        check(Json.arr(h.get("o")) == null, "hp arr wrong");
        check(Json.arr(null) == null, "hp arr null");
        check(Json.str(h, "s").equals("v"), "hp str ok");
        check(Json.str(h, "l") == null, "hp str wrong type");
        check(Json.str(h, "missing") == null, "hp str missing");
        // A JSON null reads the same as an absent key, because the sentinel is
        // not a String. Callers get one "no value here" answer instead of
        // having to test for Json.NULL at every site.
        check(Json.str(h, "n") == null, "hp str null val");
        check(Json.str(null, "s") == null, "hp str null obj");
        check(Json.num(h, "l", -1) == 7L, "hp num long");
        // num() accepts a Double and truncates toward zero rather than
        // refusing it: GitHub is free to send a size as 2048.0, and losing the
        // fraction is harmless for the sizes and timestamps this is used for.
        check(Json.num(h, "d", -1) == 2L, "hp num double trunc");
        check(Json.num(h, "s", -1) == -1L, "hp num wrong type");
        check(Json.num(h, "missing", 99) == 99L, "hp num missing");
        check(Json.num(null, "l", 5) == 5L, "hp num null obj");
        check(Json.bool(h, "b", false), "hp bool ok");
        check(Json.bool(h, "missing", true), "hp bool missing def");
        check(!Json.bool(h, "missing", false), "hp bool missing def2");
        // Wrong type yields the caller's default, not false. The pair of
        // "missing def" checks above exists for the same reason: asserting
        // only the true default would pass on a helper that always returned
        // true, so both polarities are walked.
        check(Json.bool(h, "s", true), "hp bool wrong type");
        check(Json.bool(null, "b", true), "hp bool null obj");
    }

    /**
     * The rejection table. A lenient parser is the dangerous failure mode
     * here: silently accepting a truncated response - which is what a dropped
     * GPRS connection produces - would let Sync act on half a tree listing and
     * trash local files that merely fell off the end of the JSON. So every
     * form below must throw rather than return a partial value.
     *
     * Grouped by row: empty and unterminated containers; missing, doubled and
     * trailing separators; unquoted, single-quoted and non-string keys plus a
     * missing colon;
     * truncated and near-miss literals (JSON keywords are case-sensitive, so
     * Python's "None" and SQL-ish "TRUE" are errors); unterminated strings and
     * malformed escapes; malformed numbers; and trailing junk after a complete
     * value, which is what catches a second document glued onto the first.
     *
     * Deliberately absent: "01". The number scanner delegates validation to
     * Long.parseLong, which accepts leading zeros, and Json.java documents
     * that as an accepted deviation rather than a bug. "0x10" is in the list
     * but fails for a different reason than it looks - it scans as 0 and then
     * trips the trailing-characters check on the stranded "x10".
     */
    static void testJsonBadInput() {
        String[] bad = {
            "", "   ", "{", "}", "[", "]",
            "[1,", "[1,2", "[1 2]", "[1,]",
            "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "{\"a\":1",
            "{a:1}", "{'a':1}", "{\"a\" 1}", "{1:2}",
            "tru", "truex", "falsee", "nul", "None", "TRUE",
            "\"abc", "\"a\\\"", "\"a\\q\"", "\"\\u12G4\"", "\"\\u12\"",
            "-", "--1", "1.2.3", "+1", ".5", "1e", "0x10",
            "1 2", "[1] 2", "{}{}", "[],",
            ", ", ":", "{,}", "[,]"
        };
        for (int i = 0; i < bad.length; i++) {
            // Index and the offending text both go in the name: several
            // entries differ only in whitespace or quoting, so the literal is
            // the only way to tell from the failure line which one broke.
            check(badJson(bad[i]), "bad json #" + i + " <" + bad[i] + ">");
        }
        // Null input throws rather than returning NULL, unlike Base64.decode
        // which answers an empty array. parse() has no sensible empty value to
        // return, and a caller passing null here has a bug worth surfacing.
        boolean threw = false;
        try {
            Json.parse(null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "bad json null input");
    }
}
