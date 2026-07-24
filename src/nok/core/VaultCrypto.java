package nok.core;

/**
 * NKV1 vault descriptor + NKE1 encrypted file format layer (CONTRACTS-CRYPTO.md).
 *
 * dk = PBKDF2-HMAC-SHA256(UTF8(password), salt16, iterations, 64);
 * encKey = dk[0..32) (AES-256), macKey = dk[32..64) (HMAC-SHA256).
 *
 * NKV1 descriptor (60 bytes):
 *   "NKV1" | ver 0x01 | kdf 0x01 | iterations int32BE | 0x10 | salt16 |
 *   0x20 | HMAC-SHA256(macKey, "noksidian-check-v1")
 *
 * NKE1 file:
 *   "NKE1" | ver 0x01 | flags 0x00 | IV16 | AES-256-CTR(encKey, IV, plain) |
 *   HMAC-SHA256(macKey, header||IV||ct)     (encrypt-then-MAC)
 *
 * decrypt() verifies the MAC FIRST with a constant-time compare (OR of XORs).
 * encrypt() derives the IV SIV-style (no secure RNG on CLDC):
 *   IV = first 16 bytes of HMAC-SHA256(macKey,
 *        "nok-iv" || SHA256(plain) || UTF8(path) || int64BE(timeMillis) || int32BE(counter))
 * with a per-session incrementing internal counter.
 *
 * Role: this is the entire cipher layer. Normal note traffic goes through
 * NoksidianMIDlet.readBytes/writeBytes and the Sync codec seam, so every layer
 * above them sees plaintext; CryptoSetup.migrate is the one place that calls
 * encrypt/decrypt directly, because it has to re-key files with two different
 * keys at once. Only file CONTENTS are protected. Names and folder structure stay
 * plaintext on purpose so wikilink resolution still works and the GitHub repo
 * stays browsable.
 *
 * Lifecycle: one instance per unlocked session, normally built by open() from the
 * password the user typed at startup. The keys exist in RAM only for the life of
 * the MIDlet - there is no key file - and a locked vault is simply a null
 * VaultCrypto reference. Instances are safe to share across the UI and sync
 * threads: encKey/macKey are final and every method except the ivCounter bump is
 * stateless.
 *
 * Nothing here zeroes key material when it is done with it. CLDC gives no way to
 * pin or scrub a byte[] before GC, and deriveKeys() hands the full 64-byte dk back
 * to callers (the optional "remember password" feature base64s it into RMS), so
 * treat key bytes as living until the process dies.
 *
 * Java 1.3 / CLDC 1.1 safe; no javax imports; the MAC is computed with an
 * offset/length HMAC over the output buffer so large files are never copied
 * into a second body array.
 */
public final class VaultCrypto {

    /**
     * PBKDF2 work factor for new vaults. Deliberately far below desktop norms:
     * the E71 runs a 369MHz ARM11 with no crypto instructions, and this whole
     * derivation sits in front of the unlock screen on every app start, where it
     * costs a few seconds. The phone has no UI for changing it; a vault that
     * wants a stronger KDF is initialized on the desktop with
     * "nokcrypt.py init --iterations N" and the count travels in the descriptor.
     */
    public static final int DEFAULT_ITERATIONS = 8192;

    // Fixed sizes of the two on-disk formats. NKV1 is a fixed 60 bytes end to end,
    // so open() demands exactly that length and treats any other size as garbage.
    private static final int NKV_LEN = 60;
    private static final int NKE_HEADER_LEN = 22;              // magic4+ver1+flags1+iv16
    // Header plus trailing MAC with an empty body, i.e. the size of an encrypted
    // zero-byte file. Anything shorter cannot be NKE1 no matter what the first
    // four bytes say, which is why isEncrypted() gates on it as well as the magic.
    private static final int NKE_MIN_LEN = NKE_HEADER_LEN + 32;

    // Fixed HMAC labels, encoded once at class-load. They are domain separators:
    // macKey is used both to prove the password (CHECK_MSG) and to derive IVs
    // (IV_LABEL), and the distinct prefixes keep one from ever producing the other.
    /** ASCII "noksidian-check-v1". */
    private static final byte[] CHECK_MSG = ascii("noksidian-check-v1");
    /** ASCII "nok-iv". */
    private static final byte[] IV_LABEL = ascii("nok-iv");

    // The two halves of the 64-byte derived key, already split by the caller.
    // Separate keys for confidentiality and authenticity is what makes the
    // encrypt-then-MAC construction sound; never let these become the same array.
    private final byte[] encKey;
    private final byte[] macKey;
    /** Per-session IV derivation counter (int32BE(counter) in the IV formula). */
    private int ivCounter;

    /** Direct-key constructor (tests / open()). Both keys must be 32 bytes. */
    public VaultCrypto(byte[] encKey32, byte[] macKey32) {
        if (encKey32 == null || encKey32.length != 32
                || macKey32 == null || macKey32.length != 32) {
            throw new IllegalArgumentException("keys must be 32 bytes each");
        }
        // Copy rather than alias, so nothing a caller does to its arrays afterwards
        // can reach the keys. That matters because the callers that build these
        // (CryptoSetup.split, NoksidianMIDlet.splitDk) carve them out of a derived
        // key that also lives on elsewhere.
        encKey = new byte[32];
        macKey = new byte[32];
        System.arraycopy(encKey32, 0, encKey, 0, 32);
        System.arraycopy(macKey32, 0, macKey, 0, 32);
    }

    /** dk (64 bytes): encKey = dk[0..32), macKey = dk[32..64). */
    public static byte[] deriveKeys(String password, byte[] salt16, int iterations) {
        if (password == null) {
            throw new IllegalArgumentException("null password");
        }
        if (salt16 == null || salt16.length != 16) {
            throw new IllegalArgumentException("salt must be 16 bytes");
        }
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be >= 1");
        }
        // One 64-byte PBKDF2 pass yields both keys. Deriving them separately would
        // double the single most expensive operation in the app for no benefit;
        // PBKDF2's own block counter already keeps the two halves independent.
        // The combined dk is returned whole because that is the form the optional
        // "remember password" RMS record stores.
        return Pbkdf2.hmacSha256(utf8(password), salt16, iterations, 64);
    }

    // The salt is an argument rather than generated here for two reasons: CLDC has
    // no SecureRandom, so the UI glue has to whisk one up out of clock, free
    // memory and object hashes (CONTRACTS-CRYPTO), and taking it as a parameter is
    // what makes the fixed-salt test vectors reproducible.
    /** Builds the 60-byte _vault.nkv descriptor. Salt comes from the caller. */
    public static byte[] newDescriptor(String password, byte[] salt16, int iterations) {
        byte[] dk = deriveKeys(password, salt16, iterations);
        byte[] mk = new byte[32];
        System.arraycopy(dk, 32, mk, 0, 32);
        byte[] d = new byte[NKV_LEN];
        d[0] = (byte) 'N';
        d[1] = (byte) 'K';
        d[2] = (byte) 'V';
        d[3] = (byte) '1';
        d[4] = 0x01;                       // version
        d[5] = 0x01;                       // kdf id: PBKDF2-HMAC-SHA256
        d[6] = (byte) (iterations >>> 24);
        d[7] = (byte) (iterations >>> 16);
        d[8] = (byte) (iterations >>> 8);
        d[9] = (byte) iterations;
        d[10] = 16;                        // salt length
        System.arraycopy(salt16, 0, d, 11, 16);
        d[27] = 32;                        // check length
        // The check field is the entire point of the descriptor: it lets open()
        // reject a wrong password up front instead of handing back a key that
        // would fail the MAC on every single file the user then tries to read.
        byte[] check = Hmac.sha256(mk, CHECK_MSG);
        System.arraycopy(check, 0, d, 28, 32);
        return d;
    }

    /**
     * Parses + validates a descriptor and password.
     * Throws IllegalArgumentException("bad descriptor") on malformed input and
     * IllegalArgumentException("wrong password") when the check MAC differs.
     */
    public static VaultCrypto open(byte[] descriptor, String password) {
        if (descriptor == null || descriptor.length != NKV_LEN
                || !isDescriptor(descriptor)
                || descriptor[4] != 0x01 || descriptor[5] != 0x01
                || descriptor[10] != 16 || descriptor[27] != 32) {
            throw new IllegalArgumentException("bad descriptor");
        }
        // Every fixed field is pinned above (version, kdf id, and the two length
        // bytes) so a descriptor from a future format version is rejected cleanly
        // rather than silently misparsed into a wrong-password error.
        int iterations = ((descriptor[6] & 0xff) << 24) | ((descriptor[7] & 0xff) << 16)
                       | ((descriptor[8] & 0xff) << 8) | (descriptor[9] & 0xff);
        // Java has no unsigned int, so a stored count above 2^31 arrives negative.
        // deriveKeys would reject it anyway; catching it here turns a corrupt
        // descriptor into "bad descriptor" rather than a stray "iterations" error
        // leaking out of the KDF.
        if (iterations < 1) {
            throw new IllegalArgumentException("bad descriptor");
        }
        byte[] salt = new byte[16];
        System.arraycopy(descriptor, 11, salt, 0, 16);
        byte[] dk = deriveKeys(password, salt, iterations);
        byte[] mk = new byte[32];
        System.arraycopy(dk, 32, mk, 0, 32);
        byte[] check = Hmac.sha256(mk, CHECK_MSG);
        // Accumulate every byte difference and test once at the end, so the loop
        // runs the same 32 rounds whether the password is right, wrong, or wrong
        // in the last byte only. An early-exit compare would leak how far a
        // guessed check value matched.
        int diff = 0;                                   // constant-time compare
        for (int i = 0; i < 32; i++) {
            diff |= check[i] ^ descriptor[28 + i];
        }
        if (diff != 0) {
            throw new IllegalArgumentException("wrong password");
        }
        byte[] ek = new byte[32];
        System.arraycopy(dk, 0, ek, 0, 32);
        return new VaultCrypto(ek, mk);
    }

    // Magic sniffing, not a stored flag, is what lets a half-migrated vault keep
    // working: encrypting an existing vault is a file-by-file walk that can be
    // interrupted by a battery pull, so readers must decide per file. The cost is
    // that a genuine plaintext file whose first bytes are "NKE1" would be
    // misdetected - accepted, since markdown never starts that way.
    /** True iff data starts with "NKE1" and is at least header+MAC long. */
    public static boolean isEncrypted(byte[] data) {
        return data != null && data.length >= NKE_MIN_LEN
            && data[0] == (byte) 'N' && data[1] == (byte) 'K'
            && data[2] == (byte) 'E' && data[3] == (byte) '1';
    }

    /** True iff data starts with "NKV1". */
    public static boolean isDescriptor(byte[] data) {
        return data != null && data.length >= 4
            && data[0] == (byte) 'N' && data[1] == (byte) 'K'
            && data[2] == (byte) 'V' && data[3] == (byte) '1';
    }

    /** Encrypts with the SIV-style derived IV (per-session internal counter). */
    public byte[] encrypt(byte[] plain, String path, long timeMillis) {
        if (plain == null) {
            throw new IllegalArgumentException("null plaintext");
        }
        if (path == null) {
            throw new IllegalArgumentException("null path");
        }
        // Reusing an IV under one key is fatal for CTR (the keystreams cancel and
        // the XOR of both plaintexts falls out), and the sync thread and UI thread
        // both write files, so the counter bump has to be atomic. It is the only
        // mutable state on the class - hence a lock this narrow.
        int c;
        synchronized (this) {
            c = ivCounter++;
        }
        // SIV-style: the IV is a MAC over everything that distinguishes this write.
        // The plaintext hash is the load-bearing input, because the counter restarts
        // at zero every launch and the clock is only millisecond-grained - after a
        // restart, path plus time plus counter can repeat, and the hash is what still
        // separates two different bodies. Identical bytes written to the same path at
        // the same millisecond do collide, which is harmless: same plaintext.
        byte[] ph = Sha256.hash(plain);
        byte[] pb = utf8(path);
        // Only the path is variable-length and it sits between fixed-size fields,
        // so the concatenation stays unambiguous without any length prefix.
        byte[] msg = new byte[IV_LABEL.length + 32 + pb.length + 8 + 4];
        int o = 0;
        System.arraycopy(IV_LABEL, 0, msg, o, IV_LABEL.length);
        o += IV_LABEL.length;
        System.arraycopy(ph, 0, msg, o, 32);
        o += 32;
        System.arraycopy(pb, 0, msg, o, pb.length);
        o += pb.length;
        // int64BE. The loop shifts the timeMillis parameter down to zero as it
        // writes, so it is destroyed here and must not be read again below.
        for (int i = 7; i >= 0; i--) {
            msg[o + i] = (byte) timeMillis;
            timeMillis >>>= 8;
        }
        o += 8;
        msg[o] = (byte) (c >>> 24);
        msg[o + 1] = (byte) (c >>> 16);
        msg[o + 2] = (byte) (c >>> 8);
        msg[o + 3] = (byte) c;
        byte[] full = Hmac.sha256(macKey, msg);
        // Truncate the 32-byte MAC to the 16-byte CTR counter block. Half the
        // output is discarded; that is standard SIV practice and keeps the
        // remaining 128 bits unpredictable to anyone without macKey.
        byte[] iv = new byte[16];
        System.arraycopy(full, 0, iv, 0, 16);
        return encryptWithIv(plain, iv);
    }

    // Public only so the desktop tool (tools/nokcrypt.py enc --iv, a hidden interop
    // flag) can be checked byte-for-byte against this implementation, and so tests
    // can pin a known IV.
    // Application code must go through encrypt(): passing a repeated IV here
    // silently destroys the confidentiality of every file sharing it.
    /** Encrypts with a caller-supplied IV (tests / desktop interop only). */
    public byte[] encryptWithIv(byte[] plain, byte[] iv16) {
        if (plain == null) {
            throw new IllegalArgumentException("null plaintext");
        }
        if (iv16 == null || iv16.length != 16) {
            throw new IllegalArgumentException("IV must be 16 bytes");
        }
        // Single output buffer holding header, ciphertext and MAC. The plaintext is
        // copied in and then CTR'd in place, so peak cost is one extra copy of the
        // file rather than the three buffers a naive header+body+mac concat needs -
        // and the caller's plain[] is left untouched.
        int n = plain.length;
        byte[] out = new byte[NKE_HEADER_LEN + n + 32];
        out[0] = (byte) 'N';
        out[1] = (byte) 'K';
        out[2] = (byte) 'E';
        out[3] = (byte) '1';
        out[4] = 0x01;                     // version
        out[5] = 0x00;                     // flags
        System.arraycopy(iv16, 0, out, 6, 16);
        System.arraycopy(plain, 0, out, NKE_HEADER_LEN, n);
        Aes.ctr(encKey, iv16, out, NKE_HEADER_LEN, n);
        // Encrypt-then-MAC: the MAC covers magic, version, flags and IV as well as
        // the ciphertext, so none of the header can be altered without detection.
        // The IV is written into out[] before this runs precisely so it is included.
        byte[] mac = hmacRange(macKey, out, 0, NKE_HEADER_LEN + n);
        System.arraycopy(mac, 0, out, NKE_HEADER_LEN + n, 32);
        return out;
    }

    /**
     * Verifies the MAC FIRST (constant-time), then CTR-decrypts.
     * Throws IllegalArgumentException("bad header") / ("mac mismatch").
     */
    public byte[] decrypt(byte[] data) {
        if (data == null || data.length < NKE_MIN_LEN || !isEncrypted(data)) {
            throw new IllegalArgumentException("bad header");
        }
        // isEncrypted already gated the length at NKE_MIN_LEN, so bodyLen is at
        // least NKE_HEADER_LEN here and the plaintext size below cannot go negative.
        int bodyLen = data.length - 32;
        byte[] mac = hmacRange(macKey, data, 0, bodyLen);
        int diff = 0;                                   // constant-time compare
        for (int i = 0; i < 32; i++) {
            diff |= mac[i] ^ data[bodyLen + i];
        }
        if (diff != 0) {
            throw new IllegalArgumentException("mac mismatch");
        }
        // Version and flags are only inspected AFTER the MAC verifies. Reading
        // attacker-chosen header fields before authenticating them is the classic
        // way to turn a parser into an oracle; here a tampered byte anywhere in the
        // file - header included - always surfaces as "mac mismatch" instead.
        if (data[4] != 0x01 || data[5] != 0x00) {
            throw new IllegalArgumentException("bad header");
        }
        byte[] iv = new byte[16];
        System.arraycopy(data, 6, iv, 0, 16);
        int n = bodyLen - NKE_HEADER_LEN;
        byte[] plain = new byte[n];
        System.arraycopy(data, NKE_HEADER_LEN, plain, 0, n);
        Aes.ctr(encKey, iv, plain, 0, n);
        return plain;
    }

    /** RFC 2104 HMAC-SHA256 over msg[off..off+len) - avoids copying big bodies. */
    private static byte[] hmacRange(byte[] key, byte[] msg, int off, int len) {
        byte[] k = key;
        if (k.length > 64) {
            k = Sha256.hash(k);
        }
        byte[] pad = new byte[64];
        for (int i = 0; i < 64; i++) {
            pad[i] = (byte) (((i < k.length) ? k[i] : 0) ^ 0x36);
        }
        Sha256 inner = new Sha256();
        inner.update(pad, 0, 64);
        inner.update(msg, off, len);
        byte[] ih = inner.digest();
        for (int i = 0; i < 64; i++) {
            pad[i] = (byte) (((i < k.length) ? k[i] : 0) ^ 0x5c);
        }
        Sha256 outer = new Sha256();
        outer.update(pad, 0, 64);
        outer.update(ih, 0, 32);
        return outer.digest();
    }

    // Utf8.encode, not the platform codec: the per-file key is HMAC'd over
    // these bytes, so an accented or emoji path name must produce the exact
    // same UTF-8 on the device as in the desktop tools/nokcrypt.py reference,
    // or the two sides derive different keys and the file will not decrypt.
    private static byte[] utf8(String s) {
        return Utf8.encode(s);
    }

    private static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }
}
