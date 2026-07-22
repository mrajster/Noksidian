package nok.core;

/**
 * Pure SHA-256 (FIPS 180-4) for CLDC 1.1 / Java 1.3.
 * Incremental update()/digest() plus a one-shot static hash().
 * Table/int-array based; no per-byte object allocation.
 * The instance is NOT reusable after digest().
 *
 * CLDC 1.1 ships neither javax.crypto nor java.security.MessageDigest, so the
 * entire vault crypto stack is hand-rolled and this class sits at the bottom of
 * it. Everything above leans on it: Hmac.sha256 (key folding plus the inner and
 * outer digests), Pbkdf2 (only for RFC 2104 key prep - its hot loop keeps a
 * private copy of the compression function so it can reuse ipad/opad midstates),
 * VaultCrypto's per-file IV derivation, and CryptoSetup's salt derivation.
 *
 * Java 1.3 has no Integer.rotateRight (that arrived in 5.0), so every ROTR(x, n)
 * below is spelled out by hand as (x >>> n) | (x << 32 - n).
 *
 * NOT thread-safe: h, w and buf are mutable instance state, so each thread needs
 * its own instance. Callers get that for free because every instance here is a
 * method local that never escapes (see Hmac.sha256 and VaultCrypto.hmacRange),
 * which is what lets the sync worker thread and the UI thread encrypt at the same
 * time. Hashing a large file is also slow enough on a 369MHz phone to freeze the
 * UI, so those flows run on worker threads anyway.
 *
 * Byte-exactness is pinned by the FIPS 180-4 / NIST vectors in test/TestSha.java
 * (empty, "abc", the 56-byte two-block string, and 100000 x 'a' one-shot vs
 * incremental), plus a 1537-byte pseudorandom message re-fed in 18 awkward chunk
 * sizes to straddle the block boundary from every offset.
 */
public final class Sha256 {

    /**
     * The 64 FIPS 180-4 round constants: the first 32 bits of the fractional parts
     * of the cube roots of the first 64 primes. Static and read-only, so a single
     * table is shared by every instance instead of being rebuilt per hash.
     */
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

    /** Chaining state H0..H7. */
    private final int[] h = new int[8];
    /** Message schedule scratch (reused across blocks). */
    // Held as a field rather than a transform() local purely to avoid a 256-byte
    // allocation per block; hashing a 100KB file is ~1600 blocks.
    private final int[] w = new int[64];
    /** Partial-block buffer. */
    private final byte[] buf = new byte[64];
    /**
     * Bytes currently held in buf. Invariant between calls: 0..63 - update() never
     * leaves a full block sitting here, which is what lets digest() append its 0x80
     * terminator with an unchecked buf[bufLen++].
     */
    private int bufLen;
    /** Total bytes absorbed so far; digest() turns it into the big-endian bit length. */
    private long byteCount;

    // H(0): first 32 bits of the fractional parts of the square roots of the first
    // eight primes (FIPS 180-4 5.3.3).
    public Sha256() {
        h[0] = 0x6a09e667;
        h[1] = 0xbb67ae85;
        h[2] = 0x3c6ef372;
        h[3] = 0xa54ff53a;
        h[4] = 0x510e527f;
        h[5] = 0x9b05688c;
        h[6] = 0x1f83d9ab;
        h[7] = 0x5be0cd19;
    }

    /** Absorbs len bytes of d starting at off. */
    public void update(byte[] d, int off, int len) {
        if (len <= 0) {
            return;
        }
        byteCount += len;
        // Three phases, in this order: top up any partial block left by the previous
        // call, then compress whole blocks straight out of the caller's array, then
        // stash the remainder. The middle phase is why buf is not a staging area for
        // everything - a bulk update() copies nothing at all.
        if (bufLen > 0) {
            int n = 64 - bufLen;
            if (n > len) {
                n = len;
            }
            System.arraycopy(d, off, buf, bufLen, n);
            bufLen += n;
            off += n;
            len -= n;
            if (bufLen == 64) {
                transform(buf, 0);
                bufLen = 0;
            }
        }
        while (len >= 64) {
            transform(d, off);
            off += 64;
            len -= 64;
        }
        if (len > 0) {
            System.arraycopy(d, off, buf, 0, len);
            bufLen = len;
        }
    }

    /** Finalizes and returns the 32-byte digest. Instance not reusable after. */
    // Padding is FIPS 180-4 5.1.1: a single 0x80 byte, zeros, then the message length
    // in bits as a 64-bit big-endian value in the last 8 bytes of the final block.
    // Nothing here resets state, so a second digest() would silently return a wrong
    // answer - construct a fresh instance instead.
    public byte[] digest() {
        long bits = byteCount << 3;
        buf[bufLen++] = (byte) 0x80;
        // The length needs the 8 bytes at 56..63. If the terminator pushed us past
        // byte 56 there is no room left, so flush a zero-filled block and put the
        // length in the next one. bufLen == 56 exactly still fits and needs no flush.
        if (bufLen > 56) {
            while (bufLen < 64) {
                buf[bufLen++] = 0;
            }
            transform(buf, 0);
            bufLen = 0;
        }
        while (bufLen < 56) {
            buf[bufLen++] = 0;
        }
        // Big-endian 64-bit bit length into buf[56..63], filled from the last byte
        // backwards so each pass can just take the low 8 bits and shift down.
        for (int i = 7; i >= 0; i--) {
            buf[56 + i] = (byte) bits;
            bits >>>= 8;
        }
        transform(buf, 0);
        // Serialize H0..H7 big-endian; this is the only allocation digest() makes.
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            int v = h[i];
            int j = i << 2;
            out[j] = (byte) (v >>> 24);
            out[j + 1] = (byte) (v >>> 16);
            out[j + 2] = (byte) (v >>> 8);
            out[j + 3] = (byte) v;
        }
        return out;
    }

    /** One-shot SHA-256 of the whole array. */
    public static byte[] hash(byte[] data) {
        Sha256 s = new Sha256();
        s.update(data, 0, data.length);
        return s.digest();
    }

    /** Compression function: one 64-byte block at p[off..off+64). */
    // Called with p == buf for buffered and padding blocks, and with the caller's own
    // array for the bulk path in update() - hence the explicit offset.
    private void transform(byte[] p, int off) {
        // Local alias for the field: one getfield instead of 64+ in the loops below.
        int[] ww = w;
        // W[0..15]: the block read as big-endian 32-bit words. The & 0xff undoes
        // Java's sign extension of byte.
        for (int i = 0; i < 16; i++) {
            ww[i] = ((p[off] & 0xff) << 24) | ((p[off + 1] & 0xff) << 16)
                  | ((p[off + 2] & 0xff) << 8) | (p[off + 3] & 0xff);
            off += 4;
        }
        // W[16..63]: message schedule expansion. s0 is sigma0 (ROTR7 ^ ROTR18 ^ SHR3),
        // s1 is sigma1 (ROTR17 ^ ROTR19 ^ SHR10) - note the last term of each is a
        // plain shift, not a rotate.
        for (int i = 16; i < 64; i++) {
            int x = ww[i - 15];
            int s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
            int y = ww[i - 2];
            int s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
            ww[i] = ww[i - 16] + s0 + ww[i - 7] + s1;
        }
        // The eight working variables stay in locals for the whole round loop; h[] is
        // read once here and written once at the end, so the 64 rounds do no array
        // access beyond K[i] and ww[i].
        int a = h[0];
        int b = h[1];
        int c = h[2];
        int d = h[3];
        int e = h[4];
        int f = h[5];
        int g = h[6];
        int hh = h[7];
        // 64 rounds, fully inlined rather than split into Ch/Maj/Sigma helpers, since
        // this loop is the entire cost of hashing a note body or MACing a file. Note
        // that PBKDF2's 8192-iteration hot loop does NOT run through here: it needs
        // the chaining state, which this class does not expose, so Pbkdf2 carries its
        // own byte-identical copy of this function. Any change here must be mirrored
        // there or the two will disagree. All adds are mod 2^32 via int overflow.
        for (int i = 0; i < 64; i++) {
            int s1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
            int ch = (e & f) ^ (~e & g);
            int t1 = hh + s1 + ch + K[i] + ww[i];
            int s0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
            int maj = (a & b) ^ (a & c) ^ (b & c);
            int t2 = s0 + maj;
            // Shift the register file down one slot; only e and a take new values.
            hh = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        // Davies-Meyer feed-forward: add the round output back into the chaining state.
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
