import nok.core.Hmac;
import nok.core.Pbkdf2;
import nok.core.Sha256;
import nok.core.VaultCrypto;

/**
 * Desktop test main for nok.core.Pbkdf2 and nok.core.VaultCrypto (Java 1.3).
 *
 * Suites: both contract PBKDF2 vectors (incl. the 80000-iteration one; NOTE:
 * the contract's byte-exact hex for that vector is the canonical RFC 7914
 * vector whose password is "Password" -- the prose "password" is a typo, the
 * hex is authoritative, matching tools/nokcrypt.py); descriptor round-trip /
 * wrong password / corrupted check; encrypt->decrypt round trips for lengths
 * 0,1,15,16,17,1000,100000; every single-byte-flip position on a small file
 * -> "mac mismatch" (positions inside the magic -> "bad header"); truncations
 * -> throw; isEncrypted/isDescriptor negatives on plain markdown.
 *
 * Interop modes (used by the nokcrypt.py cross-check script; not part of the
 * no-arg test run):
 *   desc  PW SALTHEX ITERS                       -> print descriptor hex
 *   enciv PW SALTHEX ITERS IVHEX IN OUT          -> encryptWithIv IN -> OUT
 *   enc   PW SALTHEX ITERS PATH MILLIS IN OUT    -> encrypt() (derived IV)
 *   dec   PW SALTHEX ITERS IN OUT                -> decrypt IN -> OUT
 *
 * Harness shape: there is no JUnit anywhere in this repo. The desktop tests
 * compile at -source 1.3 against the very same nok.core sources the MIDlet
 * ships, so a static main plus a check() that throws is the whole framework.
 * test.sh runs under `set -e`, so the uncaught RuntimeException from the first
 * failed check is what fails the build.
 *
 * None of this is ever packaged into the JAR: build.sh globs src only, and this
 * file uses java.io, which CLDC 1.1 does not have.
 */
public final class TestVault {

    // Global assertion tally shared by every helper; each suite returns its own
    // delta (count - before) so main can print a per-suite breakdown.
    private static int count = 0;

    /** Fail-fast assertion: the first false condition aborts the entire run. */
    static void check(boolean cond, String name) {
        if (!cond) {
            throw new RuntimeException(name);
        }
        count++;
    }

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

    static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /**
     * ASCII string to bytes. Every vector in this file is ASCII, where UTF-8 and
     * ASCII agree byte-for-byte, so this stands in for the getBytes("UTF-8") that
     * VaultCrypto uses internally without dragging an encoder into the test.
     */
    static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }

    /**
     * Deterministic filler: b[i] = (89*i + 41) mod 256. 89 is odd and therefore
     * coprime with 256, so the sequence walks all 256 byte values before it
     * repeats -- it exercises sign-extension bugs the way random data would,
     * while keeping a failing length exactly reproducible with no seed to record.
     */
    static byte[] pattern(int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) ((i * 89 + 41) & 0xff);
        }
        return b;
    }

    static boolean same(byte[] a, byte[] b) {
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

    /** Runs r, expecting IllegalArgumentException with exactly msg (msg null = any). */
    static String failMsg(byte[] blob, VaultCrypto vc) {
        // The Javadoc above is stale -- there is no Runnable parameter. Actual
        // contract: returns the rejection message, or null when decrypt() wrongly
        // SUCCEEDED, so callers treat null as the failure case.
        try {
            vc.decrypt(blob);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // ------------------------------------------------------------------ PBKDF2

    /**
     * Proves Pbkdf2 and VaultCrypto.deriveKeys against the two vectors that
     * CONTRACTS-CRYPTO.md mandates, plus dkLen cases the contract never reaches.
     * Both contract vectors are dkLen=64, an exact multiple of the 32-byte PRF
     * block, so on their own they would never exercise the final partial-block
     * truncation or the single-block path; the 20/33/40-byte vectors were checked
     * against python3 hashlib.pbkdf2_hmac to close that gap.
     */
    static int suitePbkdf2() {
        int before = count;
        // Contract vector 1: P="passwd", S="salt", c=1, dkLen=64.
        check(hex(Pbkdf2.hmacSha256(ascii("passwd"), ascii("salt"), 1, 64)).equals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
            + "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"),
            "pbkdf2 contract vector c=1");
        // Contract vector 2: the 80000-iteration one. The required hex is the
        // canonical RFC 7914 vector -> password is "Password" (capital P);
        // the contract prose's lowercase "password" is a typo. Hex rules.
        check(hex(Pbkdf2.hmacSha256(ascii("Password"), ascii("NaCl"), 80000, 64)).equals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56"
            + "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"),
            "pbkdf2 contract vector c=80000");
        // dkLen not a multiple of 32 + shorter than one block (verified with
        // python3 hashlib.pbkdf2_hmac locally; 'a'/'b' are also the well-known
        // PBKDF2-HMAC-SHA256 community vectors).
        check(hex(Pbkdf2.hmacSha256(ascii("password"), ascii("salt"), 2, 20)).equals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8e"),
            "pbkdf2 dkLen=20 c=2");
        check(hex(Pbkdf2.hmacSha256(ascii("password"), ascii("salt"), 4096, 40)).equals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"
            + "f7ad98c1b458ce3f"),
            "pbkdf2 dkLen=40 c=4096");
        check(hex(Pbkdf2.hmacSha256(ascii("password"), ascii("salt"), 3, 33)).equals(
            "ad35240ac683febfaf3cd49d845473fbbbaa2437f5f82d5a415ae00ac76c6bfccf"),
            "pbkdf2 dkLen=33 c=3");
        // Long password (> 64 bytes) exercises the hash-key-first branch:
        // must equal PBKDF2 with the pre-hashed key (RFC 2104 equivalence).
        byte[] longPw = pattern(100);
        check(same(Pbkdf2.hmacSha256(longPw, ascii("salt"), 10, 32),
                   Pbkdf2.hmacSha256(Sha256.hash(longPw), ascii("salt"), 10, 32)),
            "pbkdf2 long password == pre-hashed key");
        // c=1 must equal a single HMAC(P, salt || INT32BE(1)).
        // "salt" is exactly 4 bytes, so m1 ends up as salt || 00 00 00 01: the
        // three leading counter bytes are just the array's own zero fill.
        byte[] m1 = new byte[8];
        System.arraycopy(ascii("salt"), 0, m1, 0, 4);
        m1[7] = 1;
        check(same(Pbkdf2.hmacSha256(ascii("pw"), ascii("salt"), 1, 32),
                   Hmac.sha256(ascii("pw"), m1)),
            "pbkdf2 c=1 == HMAC(P, salt||INT(1))");
        // deriveKeys: UTF8(password) + 16-byte salt (verified with python3).
        byte[] salt16 = unhex("000102030405060708090a0b0c0d0e0f");
        check(hex(VaultCrypto.deriveKeys("hunter2", salt16, 1000)).equals(
            "f5550e89119f593cd3662c6d7da5bd3f7a90e25cd2dc221f58a6166c772753d3"
            + "3c5111a6de6e3f38357c8e69d70df968ffedfa1d26dd9f2be528529939b38988"),
            "deriveKeys hunter2/1000");
        // argument validation
        boolean threw = false;
        try {
            Pbkdf2.hmacSha256(ascii("x"), ascii("y"), 0, 32);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "pbkdf2 iterations=0 throws");
        threw = false;
        try {
            Pbkdf2.hmacSha256(ascii("x"), ascii("y"), 1, 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "pbkdf2 dkLen=0 throws");
        threw = false;
        try {
            VaultCrypto.deriveKeys("x", new byte[15], 1);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "deriveKeys short salt throws");
        return count - before;
    }

    // -------------------------------------------------------------- descriptor

    /**
     * Proves the 60-byte _vault.nkv descriptor byte-for-byte against the offset
     * table in CONTRACTS-CRYPTO.md, plus open()'s wrong-password and malformed
     * cases. The offsets are hard-coded on purpose: VaultCrypto's own size
     * constants are private, and even if they were not, reusing them would only
     * prove the code agrees with itself. This is an on-disk format shared with
     * tools/nokcrypt.py, so it is pinned to the spec instead.
     */
    static int suiteDescriptor() {
        int before = count;
        byte[] salt = unhex("101112131415161718191a1b1c1d1e1f");
        byte[] d = VaultCrypto.newDescriptor("hunter2", salt, 1000);
        check(d.length == 60, "descriptor length 60");
        check(d[0] == 'N' && d[1] == 'K' && d[2] == 'V' && d[3] == '1',
            "descriptor magic NKV1");
        check(d[4] == 1 && d[5] == 1, "descriptor version/kdf");
        // 1000 == 0x000003e8. d[9] needs the & 0xff because Java bytes are signed
        // and 0xe8 would otherwise compare as -24.
        check(d[6] == 0 && d[7] == 0 && d[8] == 3 && (d[9] & 0xff) == 0xe8,
            "descriptor iterations int32BE 1000");
        check(d[10] == 16 && d[27] == 32, "descriptor salt/check lengths");
        boolean saltOk = true;
        for (int i = 0; i < 16; i++) {
            if (d[11 + i] != salt[i]) {
                saltOk = false;
            }
        }
        check(saltOk, "descriptor salt bytes");
        byte[] dk = VaultCrypto.deriveKeys("hunter2", salt, 1000);
        byte[] mk = new byte[32];
        System.arraycopy(dk, 32, mk, 0, 32);
        byte[] wantCheck = Hmac.sha256(mk, ascii("noksidian-check-v1"));
        boolean checkOk = true;
        for (int i = 0; i < 32; i++) {
            if (d[28 + i] != wantCheck[i]) {
                checkOk = false;
            }
        }
        check(checkOk, "descriptor check = HMAC(macKey, noksidian-check-v1)");
        check(VaultCrypto.isDescriptor(d), "isDescriptor(descriptor)");
        check(!VaultCrypto.isEncrypted(d), "isEncrypted(descriptor) false");
        check(VaultCrypto.DEFAULT_ITERATIONS == 8192, "DEFAULT_ITERATIONS 8192");

        // round-trip: open with the right password, use the keys
        VaultCrypto vc = VaultCrypto.open(d, "hunter2");
        byte[] msg = ascii("round trip through opened vault");
        check(same(vc.decrypt(vc.encrypt(msg, "a/b.md", 1234567890123L)), msg),
            "open() keys encrypt/decrypt round-trip");
        // keys from open() must equal deriveKeys() split
        byte[] ek = new byte[32];
        System.arraycopy(dk, 0, ek, 0, 32);
        VaultCrypto direct = new VaultCrypto(ek, mk);
        byte[] iv = unhex("00112233445566778899aabbccddeeff");
        check(same(vc.encryptWithIv(msg, iv), direct.encryptWithIv(msg, iv)),
            "open() keys == deriveKeys split");

        // wrong password
        String got = openMsg(d, "hunter3");
        check("wrong password".equals(got), "open wrong password -> 'wrong password'");
        // corrupted check byte
        byte[] bad = new byte[60];
        System.arraycopy(d, 0, bad, 0, 60);
        bad[59] ^= 0x01;
        check("wrong password".equals(openMsg(bad, "hunter2")),
            "corrupted check -> 'wrong password'");
        // corrupted salt: keys change -> check no longer matches
        System.arraycopy(d, 0, bad, 0, 60);
        // bad[12] is salt byte 1 (the salt field starts at offset 11).
        bad[12] ^= 0x40;
        check("wrong password".equals(openMsg(bad, "hunter2")),
            "corrupted salt -> 'wrong password'");
        // Structural damage must report "bad descriptor", never "wrong password":
        // CryptoSetup branches on the exact message text, and telling a user their
        // password is wrong when the vault file is actually truncated or from a
        // future version sends them off retyping a password that was fine.
        // structural corruption -> bad descriptor
        System.arraycopy(d, 0, bad, 0, 60);
        bad[0] = 'X';
        check("bad descriptor".equals(openMsg(bad, "hunter2")), "bad magic");
        System.arraycopy(d, 0, bad, 0, 60);
        bad[4] = 2;
        check("bad descriptor".equals(openMsg(bad, "hunter2")), "bad version");
        System.arraycopy(d, 0, bad, 0, 60);
        bad[10] = 17;
        check("bad descriptor".equals(openMsg(bad, "hunter2")), "bad salt length");
        System.arraycopy(d, 0, bad, 0, 60);
        bad[6] = bad[7] = bad[8] = bad[9] = 0;
        check("bad descriptor".equals(openMsg(bad, "hunter2")), "iterations 0");
        byte[] short59 = new byte[59];
        System.arraycopy(d, 0, short59, 0, 59);
        check("bad descriptor".equals(openMsg(short59, "hunter2")), "59-byte descriptor");
        check("bad descriptor".equals(openMsg(null, "hunter2")), "null descriptor");
        return count - before;
    }

    /** Rejection message from open(), or null when it unexpectedly succeeded. */
    static String openMsg(byte[] d, String pw) {
        try {
            VaultCrypto.open(d, pw);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // -------------------------------------------------------------- round trip

    /**
     * Proves encrypt/decrypt round trips at every length that matters and that the
     * derived IV really follows the spec formula. The length set straddles the
     * AES-CTR boundaries: 0 and 1 for degenerate bodies, 15/16/17 around the
     * 16-byte block, 1000 for a partial trailing block, and 100000 for the
     * contract's mandated 100KB case, which at 6250 blocks also carries the CTR
     * counter out of its low byte a couple of dozen times.
     */
    static int suiteRoundTrip() {
        int before = count;
        byte[] ek = pattern(32);
        byte[] mk = unhex("202122232425262728292a2b2c2d2e2f"
            + "303132333435363738393a3b3c3d3e3f");
        VaultCrypto vc = new VaultCrypto(ek, mk);
        int[] lens = { 0, 1, 15, 16, 17, 1000, 100000 };
        byte[] iv = unhex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
        for (int li = 0; li < lens.length; li++) {
            int n = lens[li];
            byte[] plain = pattern(n);
            byte[] blob = vc.encrypt(plain, "notes/roundtrip.md", 1710000000000L);
            // 54 == 22-byte header (magic+version+flags+IV) + 32-byte MAC. CTR is
            // a stream mode, so ciphertext is 1:1 with plaintext and there is no
            // padding to account for at any length, including zero.
            check(blob.length == 54 + n, "blob length n=" + n);
            check(VaultCrypto.isEncrypted(blob), "isEncrypted(blob) n=" + n);
            check(!VaultCrypto.isDescriptor(blob), "isDescriptor(blob) false n=" + n);
            check(same(vc.decrypt(blob), plain), "derived-IV round-trip n=" + n);
            byte[] blob2 = vc.encryptWithIv(plain, iv);
            check(same(vc.decrypt(blob2), plain), "fixed-IV round-trip n=" + n);
            boolean ivOk = true;
            for (int i = 0; i < 16; i++) {
                if (blob2[6 + i] != iv[i]) {
                    ivOk = false;
                }
            }
            check(ivOk, "IV stored at offset 6 n=" + n);
        }
        // derived IV must match the spec formula for a FRESH session (counter=0)
        // ivCounter is per-instance and vc above has already burned several values,
        // so the counter=0 arm of the formula can only be checked on a new object.
        VaultCrypto fresh = new VaultCrypto(ek, mk);
        byte[] plain = ascii("iv formula check");
        long millis = 1234567890123L;
        String path = "dir/note.md";
        byte[] blob = fresh.encrypt(plain, path, millis);
        // The expected IV is rebuilt straight from the CONTRACTS-CRYPTO.md formula
        // rather than by calling any VaultCrypto helper, so a drift on either side
        // shows up here instead of cancelling out. Since the IV is derived, not
        // random, this formula is also what lets tools/nokcrypt.py reproduce the
        // phone's ciphertext byte for byte.
        byte[] ph = Sha256.hash(plain);
        byte[] pb = ascii(path);            // ASCII path == UTF-8 bytes
        byte[] m = new byte[6 + 32 + pb.length + 8 + 4];
        System.arraycopy(ascii("nok-iv"), 0, m, 0, 6);
        System.arraycopy(ph, 0, m, 6, 32);
        System.arraycopy(pb, 0, m, 38, pb.length);
        long t = millis;
        for (int i = 7; i >= 0; i--) {
            m[38 + pb.length + i] = (byte) t;
            t >>>= 8;
        }
        // counter int32BE(0): already zero
        byte[] full = Hmac.sha256(mk, m);
        boolean ivMatch = true;
        for (int i = 0; i < 16; i++) {
            if (blob[6 + i] != full[i]) {
                ivMatch = false;
            }
        }
        check(ivMatch, "derived IV matches HMAC(nok-iv||sha||path||t||ctr) formula");
        // internal counter: same args twice -> different IVs
        byte[] blobA = fresh.encrypt(plain, path, millis);
        boolean differ = false;
        for (int i = 6; i < 22; i++) {
            if (blob[i] != blobA[i]) {
                differ = true;
            }
        }
        check(differ, "session counter changes IV for identical inputs");
        check(same(fresh.decrypt(blobA), plain), "counter-bumped blob still decrypts");
        // wrong keys must not decrypt
        VaultCrypto other = new VaultCrypto(mk, ek);
        check("mac mismatch".equals(failMsg(blob, other)), "wrong keys -> mac mismatch");
        return count - before;
    }

    // ------------------------------------------------- tamper / truncation / sniff

    /**
     * Proves the authenticate-before-decrypt promise: no tampered, truncated or
     * extended blob can ever yield plaintext. This is the suite that matters most
     * in the field -- a note silently corrupted on a memory card, or a repo file
     * edited by someone without the key, is exactly the failure the MAC exists to
     * turn into an error message instead of garbage in the editor.
     */
    static int suiteTamper() {
        int before = count;
        byte[] ek = unhex("404142434445464748494a4b4c4d4e4f"
            + "505152535455565758595a5b5c5d5e5f");
        byte[] mk = unhex("606162636465666768696a6b6c6d6e6f"
            + "707172737475767778797a7b7c7d7e7f");
        VaultCrypto vc = new VaultCrypto(ek, mk);
        byte[] plain = ascii("# Secret note\nnothing to see here.\n");
        byte[] blob = vc.encryptWithIv(plain, unhex("0f0e0d0c0b0a09080706050403020100"));
        check(same(vc.decrypt(blob), plain), "tamper baseline decrypts");
        // every single-byte-flip position (two bit patterns) must be rejected;
        // outside the 4-byte magic the rejection MUST be the MAC.
        // LSB and MSB only. Testing all eight bits per byte would quadruple the
        // HMAC work for no extra coverage: HMAC-SHA256 has no bit-position blind
        // spots, and these two also catch a sign-extension slip that a mid-byte
        // bit would not.
        int[] bits = { 0x01, 0x80 };
        byte[] t = new byte[blob.length];
        for (int i = 0; i < blob.length; i++) {
            for (int bi = 0; bi < 2; bi++) {
                System.arraycopy(blob, 0, t, 0, blob.length);
                t[i] ^= (byte) bits[bi];
                String msg = failMsg(t, vc);
                if (msg == null) {
                    throw new RuntimeException("flip byte " + i + " bit "
                        + bits[bi] + " not detected");
                }
                // Bytes 0-3 fail the magic sniff before a MAC is ever computed.
                // Bytes 4-5 (version/flags) deliberately do NOT short-circuit:
                // decrypt() inspects them only after the MAC verifies, so a
                // tampering attacker cannot use the two distinct messages as an
                // oracle to probe header fields. Asserting the exact string here
                // is what locks that ordering in.
                if (i < 4) {
                    check("bad header".equals(msg), "flip magic byte " + i);
                } else {
                    check("mac mismatch".equals(msg),
                        "flip byte " + i + " bit " + bits[bi] + " -> mac mismatch");
                }
            }
        }
        // truncations -> throw (never garbage output)
        // 0 and 3 stop short of the 4-byte magic, 10 lands inside the header, 22
        // is the header exactly with no MAC at all, 53 is one byte under the
        // 54-byte minimum, length-32 strips the MAC exactly, and length-1 clips a
        // single MAC byte off the end.
        int[] cuts = { 0, 3, 10, 22, 53, blob.length - 32, blob.length - 1 };
        for (int ci = 0; ci < cuts.length; ci++) {
            int cut = cuts[ci];
            byte[] cutb = new byte[cut];
            System.arraycopy(blob, 0, cutb, 0, cut);
            String msg = failMsg(cutb, vc);
            check(msg != null, "truncation to " + cut + " throws");
            check("bad header".equals(msg) || "mac mismatch".equals(msg),
                "truncation to " + cut + " message");
        }
        // extension also breaks the MAC
        byte[] ext = new byte[blob.length + 1];
        System.arraycopy(blob, 0, ext, 0, blob.length);
        check("mac mismatch".equals(failMsg(ext, vc)), "1-byte extension -> mac mismatch");
        check("bad header".equals(failMsg(null, vc)), "decrypt(null) -> bad header");
        // magic sniffing negatives on plain markdown / junk
        byte[] md = ascii("# plain markdown\n\nhello [[world]] #tag\n");
        check(!VaultCrypto.isEncrypted(md), "isEncrypted(markdown) false");
        check(!VaultCrypto.isDescriptor(md), "isDescriptor(markdown) false");
        check(!VaultCrypto.isEncrypted(new byte[0]), "isEncrypted(empty) false");
        check(!VaultCrypto.isDescriptor(new byte[0]), "isDescriptor(empty) false");
        check(!VaultCrypto.isEncrypted(null), "isEncrypted(null) false");
        check(!VaultCrypto.isDescriptor(null), "isDescriptor(null) false");
        check(!VaultCrypto.isEncrypted(ascii("NKE")), "isEncrypted(short junk) false");
        // Real NKE1 magic but one byte under the 54-byte minimum: isEncrypted must
        // gate on length as well as magic. A magic-only sniff would route this to
        // decrypt(), whose body length would come out as 53-32=21, one short of the
        // 22-byte header, i.e. a negative-length plaintext array. decrypt() repeats
        // the same length check for exactly that reason.
        byte[] belowMin = new byte[53];
        belowMin[0] = 'N';
        belowMin[1] = 'K';
        belowMin[2] = 'E';
        belowMin[3] = '1';
        check(!VaultCrypto.isEncrypted(belowMin), "isEncrypted(53 bytes) false");
        // ...and the boundary from the other side: an empty plaintext produces the
        // smallest legal blob, header + MAC and nothing between, which must pass.
        byte[] atMin = vc.encryptWithIv(new byte[0], unhex("000102030405060708090a0b0c0d0e0f"));
        check(atMin.length == 54 && VaultCrypto.isEncrypted(atMin),
            "isEncrypted(54-byte empty blob) true");
        // markdown starting with NKE1 text but shorter than min is still plain
        check(!VaultCrypto.isEncrypted(ascii("NKE1 is the magic")), "NKE1 text 17B false");
        return count - before;
    }

    // -------------------------------------------------------------- interop I/O

    // Desktop-only, and reached solely from the interop modes below -- none of the
    // four suites touches the filesystem. Hand-rolled and fully qualified so the
    // whole java.io dependency stays confined to this section: nok.core itself
    // runs on CLDC 1.1, which has no java.io.File.
    static byte[] readFile(String p) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(p);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    static void writeFile(String p, byte[] data) throws java.io.IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(p);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    /**
     * Rebuilds a descriptor from the command-line password/salt/iterations, then
     * opens it. Every interop mode takes the three KDF inputs rather than a path
     * to an existing _vault.nkv, so a cross-check against nokcrypt.py also covers
     * key derivation and not just the file format.
     */
    static VaultCrypto openArgs(String pw, String saltHex, String iters) {
        byte[] desc = VaultCrypto.newDescriptor(pw, unhex(saltHex),
            Integer.parseInt(iters));
        return VaultCrypto.open(desc, pw);
    }

    /**
     * Cross-implementation entry point; modes and argument order are listed in the
     * class Javadoc. Nothing in the repo drives these -- test.sh runs this class
     * with no arguments -- they exist to be pushed the same inputs as
     * tools/nokcrypt.py so the two outputs can be compared byte for byte. "enc"
     * takes path and millis explicitly because both feed the derived IV, and that
     * determinism is the only reason two programs sharing no random source can be
     * compared this way at all.
     */
    static int interop(String[] a) throws java.io.IOException {
        if (a[0].equals("desc")) {
            System.out.println(hex(VaultCrypto.newDescriptor(a[1], unhex(a[2]),
                Integer.parseInt(a[3]))));
            return 0;
        }
        if (a[0].equals("enciv")) {
            VaultCrypto vc = openArgs(a[1], a[2], a[3]);
            writeFile(a[6], vc.encryptWithIv(readFile(a[5]), unhex(a[4])));
            return 0;
        }
        if (a[0].equals("enc")) {
            VaultCrypto vc = openArgs(a[1], a[2], a[3]);
            writeFile(a[7], vc.encrypt(readFile(a[6]), a[4], Long.parseLong(a[5])));
            return 0;
        }
        if (a[0].equals("dec")) {
            VaultCrypto vc = openArgs(a[1], a[2], a[3]);
            writeFile(a[5], vc.decrypt(readFile(a[4])));
            return 0;
        }
        System.out.println("unknown mode: " + a[0]);
        return 1;
    }

    /**
     * No arguments runs every suite; any argument selects an interop mode. There
     * is deliberately no try/catch: a failed check throws straight out of main,
     * which is what test.sh (running under set -e) needs in order to stop.
     */
    public static void main(String[] args) throws java.io.IOException {
        if (args.length > 0) {
            int rc = interop(args);
            if (rc != 0) {
                throw new RuntimeException("interop mode failed");
            }
            return;
        }
        int nP = suitePbkdf2();
        int nD = suiteDescriptor();
        int nR = suiteRoundTrip();
        int nT = suiteTamper();
        System.out.println("pbkdf2: " + nP);
        System.out.println("descriptor: " + nD);
        System.out.println("roundtrip: " + nR);
        System.out.println("tamper: " + nT);
        System.out.println("ALL PASS " + count);
    }
}
