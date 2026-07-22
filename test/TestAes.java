import nok.core.Aes;

/**
 * Desktop tests for nok.core.Aes (AES-256 encrypt-only block + CTR mode).
 * Java 1.3 syntax, CLDC-safe APIs only.
 *
 * Run by test.sh as a plain main(), compiled by the repo-pinned tools/jdk8 javac at
 * -source 1.3 alongside nok/core itself. There is no JUnit anywhere in this repo: the
 * test dialect is kept identical to the phone dialect, so anything that would not
 * compile at 1.3 for the device does not compile here either. Failure is a thrown
 * RuntimeException, which exits non-zero and trips `set -e` in test.sh, so the first
 * bad vector stops the whole run instead of scrolling past.
 *
 * Vectors required by CONTRACTS-CRYPTO.md:
 *  - FIPS-197 C.3 AES-256 block vector
 *  - NIST SP 800-38A F.5.5 CTR-AES256 (all four blocks)
 *  - CTR with len not a multiple of 16
 *  - counter wraparound across 2^128 (IV near ff..ff) vs manual two-part encryption
 *  - ctr applied twice restores plaintext
 *
 * Beyond the mandated vectors the suites pin the rest of the Aes.ctr javadoc contract,
 * which VaultCrypto (its only production caller) relies on: iv16 is a pure input and
 * is never advanced, only data[off..off+len) is touched, and a short len yields a
 * strict prefix of the long ciphertext. No standards body publishes a counter-
 * wraparound vector, so those cases are checked against two independent oracles
 * instead - a manual split-and-restart encryption, and a keystream assembled by
 * calling encryptBlock on the exact counter blocks by hand.
 */
public class TestAes {

    // Assertion counter, shared by every suite and never reset; each suite prints
    // (n - start), so a block that stops running shows up as a shrunken count rather
    // than as a silent pass.
    static int n = 0;

    /**
     * Fail-fast assert. Throwing beats accumulating an error count here because a
     * half-broken cipher makes every later vector meaningless noise.
     */
    static void check(boolean cond, String name) {
        if (!cond) throw new RuntimeException("FAIL: " + name);
        n++;
    }

    /**
     * Array-equality assert that dumps both operands as hex on failure. With a block
     * cipher the position of the first differing byte is most of the diagnosis - a
     * botched final round, a wrong key-schedule word and an off-by-one counter each
     * corrupt a different span - and there is no assertion library here to print it.
     * Null on either side is a failure rather than an NPE, so the dump still runs.
     */
    static void checkEq(byte[] got, byte[] want, String name) {
        boolean ok = got != null && want != null && got.length == want.length;
        if (ok) {
            for (int i = 0; i < got.length; i++) {
                if (got[i] != want[i]) { ok = false; break; }
            }
        }
        if (!ok) {
            throw new RuntimeException("FAIL: " + name + " got=" + hex(got) + " want=" + hex(want));
        }
        n++;
    }

    /**
     * Parses an even-length hex string into bytes. The vectors are kept as hex string
     * literals rather than byte arrays so they stay character-for-character diffable
     * against the published FIPS-197 / SP 800-38A text.
     */
    static byte[] fromHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new RuntimeException("bad hex: " + s);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * Bytes to lowercase hex for failure messages. StringBuffer because 1.3 has no
     * StringBuilder; returns "null" instead of throwing so checkEq can still print a
     * useful message when one side is null.
     */
    static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuffer sb = new StringBuffer(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            sb.append(Character.forDigit(v >> 4, 16));
            sb.append(Character.forDigit(v & 15, 16));
        }
        return sb.toString();
    }

    /**
     * Copy of src[off..off+len). Every ctr() call rewrites its buffer in place, so a
     * suite's plaintext/ciphertext array is sliced before each call and stays intact
     * for the comparisons afterwards; slicing is also how a "first k bytes of the
     * ciphertext" expectation is built without a second vector.
     */
    static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    // deterministic pseudo-random fill, no java.util.Random needed
    // Classic glibc LCG, so a failure at some length is replayable byte-for-byte on
    // the next run. A middle byte is taken (x >> 16) rather than the low one because
    // an LCG's low bits cycle with very short periods, and a plaintext that repeats
    // every few bytes would mask keystream bugs under XOR.
    static byte[] pattern(int len, int seed) {
        byte[] out = new byte[len];
        int x = seed;
        for (int i = 0; i < len; i++) {
            x = x * 1103515245 + 12345;
            out[i] = (byte) (x >> 16);
        }
        return out;
    }

    // ---- NIST SP 800-38A common material ----
    // Named after the section they are copied from. F.5.5 is the CTR-AES256 encryption
    // example and F.5.6 the decryption one; both use this key, this initial counter and
    // this plaintext/ciphertext pair, so one set of constants drives both directions.
    static final String KEY_F55 =
        "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4";
    static final String IV_F55 = "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff";
    static final String PT_F55 =
        "6bc1bee22e409f96e93d7e117393172a" +
        "ae2d8a571e03ac9c9eb76fac45af8e51" +
        "30c81c46a35ce411e5fbc1191a0a52ef" +
        "f69f2445df4f9b17ad2b417be66c3710";
    static final String CT_F55 =
        "601ec313775789a5b7a7f504bbf3d228" +
        "f443e3ca4d62b59aca84e990cacaf5c5" +
        "2b0930daa23de94ce87017ba2d84988d" +
        "dfc9c58db67aada613c2dd08457941a6";

    /**
     * Proves the forward cipher on its own, before anything builds a keystream from it.
     * After the C.3 known answer come the three properties encryptBlock's javadoc
     * promises: one Aes instance stays valid across calls (the key schedule is written
     * in the constructor and only read afterwards), which is exactly what ctr() leans
     * on when it drives one instance over every counter block; independent in/out
     * offsets work; and in == out at the same offset is safe because encryptBlock loads
     * all sixteen input bytes into locals before it writes any output. The final case
     * pins the AES-256-only restriction from CONTRACTS-CRYPTO.md - a 128-bit key must
     * be refused up front rather than run off the end of the key array partway through
     * building the schedule.
     */
    static void testBlockFips197() {
        int start = n;
        // FIPS-197 Appendix C.3: AES-256 example vector
        byte[] key = fromHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] pt = fromHex("00112233445566778899aabbccddeeff");
        byte[] want = fromHex("8ea2b7ca516745bfeafc49904b496089");

        Aes aes = new Aes(key);
        byte[] ct = new byte[16];
        aes.encryptBlock(pt, 0, ct, 0);
        checkEq(ct, want, "FIPS-197 C.3 block");

        // same instance is reusable (key schedule not consumed)
        byte[] ct2 = new byte[16];
        aes.encryptBlock(pt, 0, ct2, 0);
        checkEq(ct2, want, "FIPS-197 C.3 block repeat");

        // offsets: in at 3, out at 5
        byte[] inBuf = new byte[32];
        System.arraycopy(pt, 0, inBuf, 3, 16);
        byte[] outBuf = new byte[32];
        aes.encryptBlock(inBuf, 3, outBuf, 5);
        checkEq(slice(outBuf, 5, 16), want, "FIPS-197 C.3 block with offsets");

        // in-place: in == out, same offset
        byte[] buf = new byte[16];
        System.arraycopy(pt, 0, buf, 0, 16);
        aes.encryptBlock(buf, 0, buf, 0);
        checkEq(buf, want, "FIPS-197 C.3 block in-place");

        // bad key length rejected
        boolean threw = false;
        try {
            new Aes(new byte[16]);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "16-byte key rejected");

        System.out.println("block suite: " + (n - start) + " pass");
    }

    /**
     * The CTR vector the contract mandates, asserted whole and then block by block so a
     * failure names the counter increment that broke instead of reporting "64 bytes
     * differ". Also pins two documented behaviours: CTR decryption is literally the
     * same call with the same arguments (that is why Aes ships no inverse cipher at
     * all), and ctr() leaves iv16 untouched - iv16 is the initial counter block, not a
     * running counter, and ctr() copies it into a private array before incrementing.
     * VaultCrypto.encryptWithIv writes the IV into the NKE1 header before calling
     * ctr(), so it would not itself notice a mutating ctr(); this assertion is the
     * only thing holding that half of the contract up.
     */
    static void testCtrNist() {
        int start = n;
        byte[] key = fromHex(KEY_F55);
        byte[] iv = fromHex(IV_F55);
        byte[] pt = fromHex(PT_F55);
        byte[] ct = fromHex(CT_F55);

        // encrypt all 4 blocks in one call
        byte[] data = slice(pt, 0, pt.length);
        Aes.ctr(key, iv, data, 0, data.length);
        checkEq(data, ct, "SP800-38A F.5.5 encrypt (4 blocks)");

        // assert each of the four blocks individually
        for (int b = 0; b < 4; b++) {
            checkEq(slice(data, b * 16, 16), slice(ct, b * 16, 16),
                    "SP800-38A F.5.5 block " + (b + 1));
        }

        // decrypt == encrypt (F.5.6 uses the same keystream)
        Aes.ctr(key, iv, data, 0, data.length);
        checkEq(data, pt, "SP800-38A F.5.6 decrypt restores plaintext");

        // iv buffer must not be modified by ctr()
        checkEq(iv, fromHex(IV_F55), "ctr leaves iv16 untouched");

        System.out.println("ctr-nist suite: " + (n - start) + " pass");
    }

    /**
     * Tail handling and buffer windowing. Because CTR is a pure stream cipher the
     * output for any len is a prefix of the output for a longer len, so the ragged
     * final block can be checked against the same F.5.5 ciphertext and no extra vector
     * is needed. len=0 must be a no-op, which is the path an empty file takes through
     * VaultCrypto.encrypt(). The guard bytes at 6 and 28 catch a final block that XORs
     * a full 16 bytes instead of the remaining n, which would corrupt a caller's buffer
     * past the end of the window.
     */
    static void testCtrPartial() {
        int start = n;
        byte[] key = fromHex(KEY_F55);
        byte[] iv = fromHex(IV_F55);
        byte[] pt = fromHex(PT_F55);
        byte[] ct = fromHex(CT_F55);

        // len = 37 (not a multiple of 16): must equal the first 37 bytes of the
        // full-length ciphertext, since CTR is a pure stream cipher.
        byte[] data = slice(pt, 0, 37);
        Aes.ctr(key, iv, data, 0, 37);
        checkEq(data, slice(ct, 0, 37), "CTR partial len=37");

        // len = 1
        byte[] one = slice(pt, 0, 1);
        Aes.ctr(key, iv, one, 0, 1);
        checkEq(one, slice(ct, 0, 1), "CTR partial len=1");

        // len = 0 is a no-op
        byte[] zero = new byte[4];
        Aes.ctr(key, iv, zero, 2, 0);
        checkEq(zero, new byte[4], "CTR len=0 no-op");

        // off > 0 inside a larger buffer: only [off, off+len) is touched
        byte[] buf = new byte[64];
        System.arraycopy(pt, 0, buf, 7, 21);
        Aes.ctr(key, iv, buf, 7, 21);
        checkEq(slice(buf, 7, 21), slice(ct, 0, 21), "CTR at offset 7 len=21");
        check(buf[6] == 0 && buf[28] == 0, "CTR does not touch bytes outside range");

        System.out.println("ctr-partial suite: " + (n - start) + " pass");
    }

    /**
     * The counter block must increment as one 128-bit big-endian integer and wrap
     * cleanly at 2^128. This is not a theoretical corner on this device: the phone IV
     * is the first 16 bytes of an HMAC-SHA256 (CONTRACTS-CRYPTO.md), so it is uniformly
     * distributed and ff..fe is exactly as reachable as any other value.
     *
     * NIST publishes no wraparound vector, so correctness is established against two
     * oracles that do not share code with the increment loop: encrypting the same
     * plaintext in two pieces with the second piece restarted at IV 00..00, and XORing
     * against a keystream built by calling encryptBlock on the three counter blocks
     * written out by hand.
     *
     * The last case is the one that catches a genuine, common implementation mistake:
     * incrementing only the low 32 bits, which some CTR profiles permit. IV
     * 00..00ffffffff must carry all the way into byte 11, making the second counter
     * block 00..0100000000; a 32-bit-only counter would roll back to 00..0000000000 and
     * silently reuse the first block's keystream.
     */
    static void testCtrWraparound() {
        int start = n;
        byte[] key = fromHex(KEY_F55);
        byte[] pt = pattern(48, 0x1234567);

        // IV = ff..fe: counter blocks are ff..fe, ff..ff, then wrap to 00..00
        byte[] ivFe = fromHex("fffffffffffffffffffffffffffffffe");
        byte[] whole = slice(pt, 0, 48);
        Aes.ctr(key, ivFe, whole, 0, 48);

        // manual two-part encryption across the wrap: first 32 bytes from ivFe,
        // last 16 bytes restarted at IV = 00..00
        byte[] two = slice(pt, 0, 48);
        Aes.ctr(key, ivFe, two, 0, 32);
        Aes.ctr(key, new byte[16], two, 32, 16);
        checkEq(whole, two, "wraparound == manual two-part (split at wrap)");

        // and against a raw keystream built with encryptBlock on the exact counters
        Aes aes = new Aes(key);
        byte[] ks = new byte[48];
        aes.encryptBlock(fromHex("fffffffffffffffffffffffffffffffe"), 0, ks, 0);
        aes.encryptBlock(fromHex("ffffffffffffffffffffffffffffffff"), 0, ks, 16);
        aes.encryptBlock(fromHex("00000000000000000000000000000000"), 0, ks, 32);
        byte[] manual = slice(pt, 0, 48);
        for (int i = 0; i < 48; i++) manual[i] ^= ks[i];
        checkEq(whole, manual, "wraparound == explicit counter keystream");

        // IV = ff..ff: very first increment wraps to zero
        byte[] ivFf = fromHex("ffffffffffffffffffffffffffffffff");
        byte[] whole2 = slice(pt, 0, 48);
        Aes.ctr(key, ivFf, whole2, 0, 48);
        byte[] two2 = slice(pt, 0, 48);
        Aes.ctr(key, ivFf, two2, 0, 16);
        Aes.ctr(key, new byte[16], two2, 16, 32);
        checkEq(whole2, two2, "IV=ff..ff wraps to 00..00 after first block");

        // partial-carry increment: IV = 00..00ffffffff must carry into byte 11
        byte[] ivCarry = fromHex("000000000000000000000000ffffffff");
        byte[] whole3 = slice(pt, 0, 32);
        Aes.ctr(key, ivCarry, whole3, 0, 32);
        byte[] two3 = slice(pt, 0, 32);
        Aes.ctr(key, ivCarry, two3, 0, 16);
        Aes.ctr(key, fromHex("00000000000000000000000100000000"), two3, 16, 16);
        checkEq(whole3, two3, "carry propagates past 32-bit boundary");

        System.out.println("ctr-wrap suite: " + (n - start) + " pass");
    }

    /**
     * Involution over lengths that bracket the block boundary (0, 1, 15, 16, 17, 37)
     * plus two multi-block sizes; 100000 mirrors at this layer the 100KB round trip
     * CONTRACTS-CRYPTO.md demands of the vault layer, and is the only place the
     * per-block loop runs thousands of iterations. IV ff..fd is chosen deliberately:
     * its fourth counter block is the wrap to 00..00, so every input longer than 48
     * bytes - 4096, 100000, and the off=13 len=61 case - re-crosses 2^128.
     *
     * The "changed" assertion is what makes the round trip mean anything - a ctr() that
     * returned early, or emitted an all-zero keystream, would satisfy "applied twice
     * restores the plaintext" perfectly while encrypting nothing at all.
     */
    static void testCtrRoundTrip() {
        int start = n;
        byte[] key = fromHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] iv = fromHex("fffffffffffffffffffffffffffffffd");

        int[] lens = {0, 1, 15, 16, 17, 37, 4096, 100000};
        for (int i = 0; i < lens.length; i++) {
            int len = lens[i];
            byte[] orig = pattern(len, 77 + len);
            byte[] data = slice(orig, 0, len);
            Aes.ctr(key, iv, data, 0, len);
            if (len > 0) {
                boolean changed = false;
                for (int j = 0; j < len; j++) {
                    if (data[j] != orig[j]) { changed = true; break; }
                }
                check(changed, "ctr changed data len=" + len);
            }
            Aes.ctr(key, iv, data, 0, len);
            checkEq(data, orig, "ctr twice restores plaintext len=" + len);
        }

        // round trip at an offset, odd length, wrapping IV
        byte[] buf = pattern(100, 5);
        byte[] orig = slice(buf, 0, 100);
        Aes.ctr(key, iv, buf, 13, 61);
        Aes.ctr(key, iv, buf, 13, 61);
        checkEq(buf, orig, "ctr twice restores plaintext off=13 len=61");

        System.out.println("ctr-roundtrip suite: " + (n - start) + " pass");
    }

    /**
     * Ordered cheapest and most fundamental first: if encryptBlock is wrong, every CTR
     * failure below it is a false lead, so the block suite has to run and pass before
     * anything that builds a keystream out of it.
     */
    public static void main(String[] args) {
        testBlockFips197();
        testCtrNist();
        testCtrPartial();
        testCtrWraparound();
        testCtrRoundTrip();
        System.out.println("ALL PASS " + n);
    }
}
