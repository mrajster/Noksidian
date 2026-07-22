import nok.core.Hmac;
import nok.core.Sha256;

/**
 * Desktop test main for nok.core.Sha256 and nok.core.Hmac (Java 1.3 syntax).
 * Suites: SHA-256 contract vectors (+ a locally verified 100000-byte 'a' vector),
 * HMAC-SHA256 RFC 4231 cases 1-4 (full 32-byte outputs) + long-key case 6,
 * and incremental-vs-oneshot equality across odd chunk sizes.
 *
 * Sha256 and Hmac sit at the bottom of the vault crypto stack -- Pbkdf2, the NKE1
 * encrypt-then-MAC tag, the _vault.nkv wrong-password check and the derived per-file
 * IV all funnel through them -- so a single wrong bit here silently corrupts every
 * encrypted vault and breaks byte compatibility with tools/nokcrypt.py. That is why
 * this file is almost entirely published vectors rather than round-trip checks: a
 * hand-rolled hash that is self-consistently wrong sails through a round trip.
 *
 * No JUnit. test.sh compiles nok/core/** plus test/** with tools/jdk8 javac at
 * -source 1.3 and runs each Test class as a plain main, so check() throws on the
 * first mismatch and test.sh's `set -e` turns the nonzero exit into an aborted run.
 *
 * Desktop-only by construction: nothing here touches MIDP, and the classes under
 * test are pure integer arithmetic, so their behaviour on CLDC 1.1 is identical.
 *
 * The vectors CONTRACTS-CRYPTO.md mandates are marked as such below; the rest were
 * added to pin the buffering and key-folding edges the published vectors never reach.
 */
public final class TestSha {

    /**
     * Assertions passed so far, across every suite. Each suite returns its own delta
     * (count - before) so main can print a per-suite tally without each suite having
     * to carry a counter of its own.
     */
    private static int count = 0;

    /**
     * Records a passing assertion, or aborts the run naming the failed vector.
     * Failing fast is deliberate: if the compression function is broken then every
     * later vector fails too, and only the first name tells you anything.
     */
    static void check(boolean cond, String name) {
        if (!cond) {
            throw new RuntimeException(name);
        }
        count++;
    }

    /**
     * Lowercase hex digits. Digests are compared as hex strings so the expected
     * values can be pasted verbatim out of FIPS/RFC documents and sha256sum output
     * without transcribing them into byte arrays. The table is used instead of
     * Integer.toHexString, which drops the leading zero of any byte below 0x10 and
     * would shift the rest of the string.
     */
    private static final char[] HEXC = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    static String hex(byte[] b) {
        StringBuffer sb = new StringBuffer(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            sb.append(HEXC[v >>> 4]);
            sb.append(HEXC[v & 15]);
        }
        return sb.toString();
    }

    /**
     * One byte per char. Avoids String.getBytes("UTF-8"), which forces checked
     * exception handling for no benefit here; every message in this file is pure
     * ASCII, so the truncating cast is exact.
     */
    static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }

    /** len copies of value. Takes an int so callers can write 0xaa without a cast. */
    static byte[] fill(int len, int value) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) value;
        }
        return b;
    }

    /**
     * SHA-256 against the three vectors CONTRACTS-CRYPTO.md mandates (FIPS 180-4),
     * plus the buffering edges those fixed short inputs never reach: a large
     * multi-block hash, the same input fed through the incremental API, and an
     * update() call on a slice of a larger array.
     */
    static int suiteSha() {
        int before = count;
        // Contract vector. Empty input is the only case where the block that gets
        // compressed is nothing but padding, so it pins the whole
        // 0x80 / zero-fill / 64-bit-length layout on its own.
        check(hex(Sha256.hash(new byte[0])).equals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            "sha256 empty");
        // Contract vector. Single block, message and padding both inside one block.
        check(hex(Sha256.hash(ascii("abc"))).equals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            "sha256 abc");
        // Contract vector, and the reason it is worth having: the string is exactly
        // 56 bytes, the padding boundary. The 0x80 terminator lands at index 56 and
        // leaves no room for the 8-byte length, forcing digest() to flush a whole
        // extra all-padding block. An off-by-one in that test is the classic SHA bug
        // and is invisible to the other vectors here.
        check(hex(Sha256.hash(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))).equals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"),
            "sha256 two-block");
        // 100000 x 'a'; expected digest computed locally with:
        //   printf 'a%.0s' $(seq 1 100000) | sha256sum
        // Not a published vector, hence the reproduction command above. It earns its
        // place by running the bulk path across ~1560 blocks and ending on a 32-byte
        // tail, and by being hashed twice below so one-shot and incremental are pinned
        // to each other on an input far larger than any FIPS vector.
        byte[] aaa = fill(100000, 'a');
        String expA = "6d1cf22d7cc09b085dfc25ee1a1f3ae0265804c607bc2074ad253bcc82fd81ee";
        check(hex(Sha256.hash(aaa)).equals(expA), "sha256 100000xa oneshot");
        Sha256 inc = new Sha256();
        inc.update(aaa, 0, 100000);
        check(hex(inc.digest()).equals(expA), "sha256 100000xa incremental");
        // update with off/len slicing must hash only the slice
        // The 7 leading and 5 trailing bytes stay zero, so an implementation that
        // ignored off, or that read len as an end index rather than a count, would
        // hash a different string and miss the known "abc" digest. VaultCrypto.hmacRange
        // is the caller that depends on this: it MACs msg[off..off+len) of an
        // already-assembled NKE1 buffer rather than copying the body out first.
        byte[] padded = new byte[7 + 3 + 5];
        byte[] abc = ascii("abc");
        System.arraycopy(abc, 0, padded, 7, 3);
        Sha256 sl = new Sha256();
        sl.update(padded, 7, 3);
        check(hex(sl.digest()).equals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            "sha256 off/len slice");
        return count - before;
    }

    static void hmacCase(byte[] key, byte[] msg, String expected, String name) {
        check(hex(Hmac.sha256(key, msg)).equals(expected), name);
    }

    /**
     * HMAC-SHA256 against RFC 4231. Cases 1-4 are the set CONTRACTS-CRYPTO.md
     * mandates, asserted as full 32-byte outputs rather than the truncated forms the
     * RFC also lists. Case 5 is skipped on purpose: it only tests 128-bit output
     * truncation, and Hmac.sha256 has no truncation argument to get wrong. Case 6 is
     * added because it is the only vector here whose key exceeds the 64-byte block
     * size and therefore the only one that reaches the hash-the-key-first branch.
     */
    static int suiteHmac() {
        int before = count;
        // RFC 4231 test case 1
        hmacCase(fill(20, 0x0b), ascii("Hi There"),
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            "hmac rfc4231 case1");
        // RFC 4231 test case 2
        // A 4-byte key: the far end of the short-key path, where all but the first
        // four bytes of both pads come from the implicit zero padding.
        hmacCase(ascii("Jefe"), ascii("what do ya want for nothing?"),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            "hmac rfc4231 case2");
        // RFC 4231 test case 3
        hmacCase(fill(20, 0xaa), fill(50, 0xdd),
            "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe",
            "hmac rfc4231 case3");
        // RFC 4231 test case 4 (key = 0x01..0x19)
        byte[] k4 = new byte[25];
        for (int i = 0; i < 25; i++) {
            k4[i] = (byte) (i + 1);
        }
        hmacCase(k4, fill(50, 0xcd),
            "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b",
            "hmac rfc4231 case4");
        // RFC 4231 test case 6: 131-byte key exercises the hash-key-first branch
        // (131 > BLOCK, so Hmac folds the key down to SHA256(key) before padding).
        // Skipping the fold still produces a plausible, stable, entirely wrong tag --
        // the kind of bug only a cross-implementation vector catches, since every
        // Noksidian-to-Noksidian round trip would still verify.
        hmacCase(fill(131, 0xaa),
            ascii("Test Using Larger Than Block-Size Key - Hash Key First"),
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            "hmac rfc4231 case6 longkey");
        return count - before;
    }

    /**
     * Proves update() is a genuine streaming API: any chunking of the same bytes has
     * to produce the one-shot digest. This is the only suite that exercises all three
     * phases of update() -- topping up a partial block left by the previous call,
     * compressing whole blocks straight out of the caller's array, and stashing the
     * tail -- which the fixed-size published vectors cannot reach. The chunk sizes are
     * arbitrary on purpose: nothing in the vault stack promises update() a friendly
     * length, so a phase-boundary bug would surface only on real files, after they
     * were already encrypted.
     */
    static int suiteIncremental() {
        int before = count;
        // Deterministic pseudo-random message (simple LCG), 1537 bytes:
        // crosses many 64-byte block boundaries in odd ways.
        // 1537 = 24 * 64 + 1, so the message always ends with a one-byte partial
        // block no matter how it is chunked. The message is generated from a fixed
        // seed rather than stored so the test carries no 1.5KB literal, and bits 16..23
        // are taken because the low bits of an LCG cycle with a very short period and
        // would produce a near-repeating message.
        byte[] msg = new byte[1537];
        int seed = 0x1234567;
        for (int i = 0; i < msg.length; i++) {
            seed = seed * 1103515245 + 12345;
            msg[i] = (byte) (seed >>> 16);
        }
        String oneshot = hex(Sha256.hash(msg));
        // Chunk sizes picked to hit every branch: 1..3 keep buf permanently partial so
        // the bulk loop never runs; 63/64/65 straddle the block boundary from both
        // sides and land on it exactly; 1536 is an exact multiple of 64 that leaves a
        // single trailing byte; the odd sizes in between put the boundary at a
        // different offset within each chunk on every pass.
        int[] chunks = { 1, 2, 3, 5, 7, 11, 13, 17, 31, 33, 63, 64, 65, 127, 129, 511, 997, 1536 };
        for (int c = 0; c < chunks.length; c++) {
            int step = chunks[c];
            Sha256 s = new Sha256();
            int off = 0;
            while (off < msg.length) {
                int n = msg.length - off;
                if (n > step) {
                    n = step;
                }
                s.update(msg, off, n);
                off += n;
            }
            check(hex(s.digest()).equals(oneshot), "incremental chunk=" + step);
        }
        // zero-length updates interleaved must not change the result
        // update() early-returns on len <= 0. The bug this pins is an implementation
        // that falls through and stashes the empty tail unconditionally, resetting
        // bufLen to 0 and discarding a partial block -- which changes the digest of
        // everything hashed afterwards while every fixed-chunk vector above still passes.
        Sha256 z = new Sha256();
        z.update(msg, 0, 0);
        z.update(msg, 0, 700);
        z.update(msg, 700, 0);
        z.update(msg, 700, msg.length - 700);
        check(hex(z.digest()).equals(oneshot), "incremental zero-len updates");
        return count - before;
    }

    /**
     * Entry point for test.sh. The per-suite counts are printed rather than just a
     * total so a suite that stopped asserting -- an early return, a deleted vector --
     * is visible in the log instead of hiding behind a still-green "ALL PASS".
     * Failures never reach here: check() throws, and the nonzero exit stops test.sh.
     */
    public static void main(String[] args) {
        int nSha = suiteSha();
        int nHmac = suiteHmac();
        int nInc = suiteIncremental();
        System.out.println("sha256: " + nSha);
        System.out.println("hmac: " + nHmac);
        System.out.println("incremental: " + nInc);
        System.out.println("ALL PASS " + count);
    }
}
