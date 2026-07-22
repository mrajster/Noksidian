package nok.core;

/**
 * RFC 2898 PBKDF2 with HMAC-SHA256 for CLDC 1.1 / Java 1.3, any dkLen.
 *
 * Speed: the c-1 chained iterations U_j = HMAC(P, U_{j-1}) are computed from
 * precomputed SHA-256 midstates of the ipad/opad blocks, so every iteration
 * costs exactly two compression calls and allocates NOTHING (all buffers are
 * reused). This matters at 8192+ iterations on a 369MHz phone CPU.
 *
 * The compression function is a private copy of the FIPS 180-4 transform
 * (Sha256's chaining state is not exposed); byte-exactness against Sha256 is
 * asserted by the test vectors in TestVault.
 *
 * Everything here is static and stateless: the only static state is the
 * read-only round-constant table, and every working buffer is a local of the
 * one public method, so two concurrent calls cannot corrupt each other. Callers
 * must still keep it off the MIDP event thread: the loop costs two SHA-256
 * compressions per iteration, so VaultCrypto.DEFAULT_ITERATIONS (8192) is around
 * 16k compressions per 32-byte output block - seconds of CPU here, long enough
 * to freeze the UI. Both vault flows spawn their own worker for it: the unlock
 * path in NoksidianMIDlet and the migration walk in CryptoSetup.
 *
 * The only caller outside the tests is VaultCrypto.deriveKeys, which asks for
 * dkLen=64; that 64-byte result is the encKey || macKey pair (CONTRACTS-CRYPTO),
 * split by its own callers rather than here.
 */
public final class Pbkdf2 {

    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private Pbkdf2() {
    }

    /**
     * PBKDF2-HMAC-SHA256(password, salt, iterations) -> dkLen bytes.
     * Throws IllegalArgumentException on null input, iterations < 1, dkLen < 1.
     */
    public static byte[] hmacSha256(byte[] password, byte[] salt, int iterations, int dkLen) {
        if (password == null || salt == null) {
            throw new IllegalArgumentException("null input");
        }
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be >= 1");
        }
        if (dkLen < 1) {
            throw new IllegalArgumentException("dkLen must be >= 1");
        }
        // RFC 2104 key prep: hash keys longer than the 64-byte block.
        byte[] key = password;
        if (key.length > 64) {
            key = Sha256.hash(key);
        }
        // Midstates: chaining state after absorbing the ipad / opad block.
        int[] w = new int[64];
        int[] hi = new int[8];
        int[] ho = new int[8];
        byte[] block = new byte[64];
        initState(hi);
        for (int i = 0; i < 64; i++) {
            block[i] = (byte) (((i < key.length) ? key[i] : 0) ^ 0x36);
        }
        transform(hi, block, 0, w);
        initState(ho);
        for (int i = 0; i < 64; i++) {
            block[i] = (byte) (((i < key.length) ? key[i] : 0) ^ 0x5c);
        }
        transform(ho, block, 0, w);
        // mb: the single final block of a 32-byte message that follows one
        // already-absorbed 64-byte pad block: msg32 | 0x80 | zeros | len=768 bits.
        byte[] mb = new byte[64];
        mb[32] = (byte) 0x80;
        mb[62] = (byte) 0x03;   // 96 bytes total = 768 = 0x0300 bits, big-endian

        int[] st = new int[8];
        byte[] u = new byte[32];
        byte[] t = new byte[32];
        byte[] out = new byte[dkLen];
        int nBlocks = (dkLen + 31) / 32;
        for (int bi = 1; bi <= nBlocks; bi++) {
            // U_1 = HMAC(P, salt || INT32BE(bi)) -- generic path, once per block.
            byte[] m1 = new byte[salt.length + 4];
            System.arraycopy(salt, 0, m1, 0, salt.length);
            m1[salt.length] = (byte) (bi >>> 24);
            m1[salt.length + 1] = (byte) (bi >>> 16);
            m1[salt.length + 2] = (byte) (bi >>> 8);
            m1[salt.length + 3] = (byte) bi;
            byte[] u1 = Hmac.sha256(password, m1);
            System.arraycopy(u1, 0, u, 0, 32);
            System.arraycopy(u1, 0, t, 0, 32);
            // U_j = HMAC(P, U_{j-1}); T ^= U_j. Two compressions, zero allocations.
            for (int j = 1; j < iterations; j++) {
                System.arraycopy(u, 0, mb, 0, 32);
                st[0] = hi[0]; st[1] = hi[1]; st[2] = hi[2]; st[3] = hi[3];
                st[4] = hi[4]; st[5] = hi[5]; st[6] = hi[6]; st[7] = hi[7];
                transform(st, mb, 0, w);
                stateToBytes(st, mb);           // inner digest -> outer message
                st[0] = ho[0]; st[1] = ho[1]; st[2] = ho[2]; st[3] = ho[3];
                st[4] = ho[4]; st[5] = ho[5]; st[6] = ho[6]; st[7] = ho[7];
                transform(st, mb, 0, w);
                stateToBytes(st, u);
                for (int i = 0; i < 32; i++) {
                    t[i] ^= u[i];
                }
            }
            int off = (bi - 1) * 32;
            int n = dkLen - off;
            if (n > 32) {
                n = 32;
            }
            System.arraycopy(t, 0, out, off, n);
        }
        return out;
    }

    /** SHA-256 initial hash value H(0). */
    private static void initState(int[] h) {
        h[0] = 0x6a09e667;
        h[1] = 0xbb67ae85;
        h[2] = 0x3c6ef372;
        h[3] = 0xa54ff53a;
        h[4] = 0x510e527f;
        h[5] = 0x9b05688c;
        h[6] = 0x1f83d9ab;
        h[7] = 0x5be0cd19;
    }

    /** Writes the 8-word state big-endian into out[0..32). */
    private static void stateToBytes(int[] st, byte[] out) {
        for (int i = 0; i < 8; i++) {
            int v = st[i];
            int j = i << 2;
            out[j] = (byte) (v >>> 24);
            out[j + 1] = (byte) (v >>> 16);
            out[j + 2] = (byte) (v >>> 8);
            out[j + 3] = (byte) v;
        }
    }

    /** FIPS 180-4 compression of one 64-byte block at p[off..off+64) into h. */
    private static void transform(int[] h, byte[] p, int off, int[] ww) {
        for (int i = 0; i < 16; i++) {
            ww[i] = ((p[off] & 0xff) << 24) | ((p[off + 1] & 0xff) << 16)
                  | ((p[off + 2] & 0xff) << 8) | (p[off + 3] & 0xff);
            off += 4;
        }
        for (int i = 16; i < 64; i++) {
            int x = ww[i - 15];
            int s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
            int y = ww[i - 2];
            int s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
            ww[i] = ww[i - 16] + s0 + ww[i - 7] + s1;
        }
        int a = h[0];
        int b = h[1];
        int c = h[2];
        int d = h[3];
        int e = h[4];
        int f = h[5];
        int g = h[6];
        int hh = h[7];
        for (int i = 0; i < 64; i++) {
            int s1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
            int ch = (e & f) ^ (~e & g);
            int t1 = hh + s1 + ch + K[i] + ww[i];
            int s0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
            int maj = (a & b) ^ (a & c) ^ (b & c);
            int t2 = s0 + maj;
            hh = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        h[0] += a;
        h[1] += b;
        h[2] += c;
        h[3] += d;
        h[4] += e;
        h[5] += f;
        h[6] += g;
        h[7] += hh;
    }
}
