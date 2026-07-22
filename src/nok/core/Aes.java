package nok.core;

/**
 * AES-256 block cipher, ENCRYPT direction only (Nk=8, Nr=14), plus CTR mode.
 * CTR decryption == CTR encryption, so no InvCipher is needed for the vault.
 *
 * T-table implementation (four 256-entry int tables built once from the S-box)
 * for speed on slow phone CPUs; no per-byte object allocation anywhere.
 *
 * Java 1.3 / CLDC 1.1 safe: no javax imports, ints and arrays only.
 *
 * Exists at all because CLDC 1.1 ships no javax.crypto, so the cipher has to be
 * hand-rolled. Its only production caller is VaultCrypto, which runs ctr() over the
 * body of the NKE1 on-disk format (see CONTRACTS-CRYPTO.md); test/TestAes.java is
 * the only other user.
 *
 * Dropping the decrypt direction is what keeps this file small: an InvCipher would
 * need its own inverse S-box plus four more 1KB TD tables, all dead weight in a ~2MB
 * heap when CTR only ever calls the forward cipher.
 *
 * The key schedule is written once in the constructor and only read afterwards, so
 * one instance can be shared and reused across calls; encryptBlock keeps all working
 * state in locals. ctr() is not itself reentrant on a shared data[]: it XORs the
 * caller's array in place.
 *
 * Covered by test/TestAes.java against FIPS-197 C.3 (AES-256 block) and NIST
 * SP 800-38A F.5.5 (CTR-AES256), plus counter wraparound near 2^128.
 *
 * NOT hardened against side channels: table and S-box lookups are cache- and
 * timing-dependent, and the key schedule is never zeroed. The threat model here is a
 * lost or synced phone, not an attacker running code alongside the MIDlet - and on a
 * copying-GC CLDC VM zeroing rk would not reliably erase earlier copies anyway.
 */
public final class Aes {

    // Kept as a standalone table even though TE0..TE3 already embed it: key expansion
    // needs the raw S-box, and so does the final round, which has no MixColumns and
    // therefore cannot use the T-tables.
    /** FIPS-197 S-box. */
    private static final int[] SBOX = {
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5,
        0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0,
        0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc,
        0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a,
        0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0,
        0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b,
        0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85,
        0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5,
        0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17,
        0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88,
        0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c,
        0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9,
        0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6,
        0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e,
        0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94,
        0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68,
        0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
    };

    /** Encryption T-tables: TE0[x] = S[x] * (02,01,01,03) column, TE1..TE3 byte rotations. */
    private static final int[] TE0 = new int[256];
    private static final int[] TE1 = new int[256];
    private static final int[] TE2 = new int[256];
    private static final int[] TE3 = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int s = SBOX[i];
            int s2 = (s << 1) ^ (((s >> 7) & 1) * 0x11b);   // xtime in GF(2^8)
            int s3 = s2 ^ s;
            int t = (s2 << 24) | (s << 16) | (s << 8) | s3;
            TE0[i] = t;
            TE1[i] = (t >>> 8) | (t << 24);
            TE2[i] = (t >>> 16) | (t << 16);
            TE3[i] = (t >>> 24) | (t << 8);
        }
    }

    /** Expanded key schedule: 4 * (Nr + 1) = 60 words. */
    private final int[] rk = new int[60];

    /** Builds the AES-256 key schedule. key32 must be exactly 32 bytes. */
    public Aes(byte[] key32) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        }
        int i;
        for (i = 0; i < 8; i++) {
            int j = i << 2;
            rk[i] = ((key32[j] & 0xff) << 24) | ((key32[j + 1] & 0xff) << 16)
                  | ((key32[j + 2] & 0xff) << 8) | (key32[j + 3] & 0xff);
        }
        int rcon = 1;
        for (i = 8; i < 60; i++) {
            int t = rk[i - 1];
            int m = i & 7;
            if (m == 0) {
                t = subWord((t << 8) | (t >>> 24)) ^ (rcon << 24);
                rcon <<= 1;                       // never reaches 0x80 for Nk=8
            } else if (m == 4) {
                t = subWord(t);
            }
            rk[i] = rk[i - 8] ^ t;
        }
    }

    private static int subWord(int w) {
        return (SBOX[(w >>> 24) & 0xff] << 24) | (SBOX[(w >>> 16) & 0xff] << 16)
             | (SBOX[(w >>> 8) & 0xff] << 8) | SBOX[w & 0xff];
    }

    /**
     * Encrypts one 16-byte block: out[outOff..outOff+16) = AES-256(in[inOff..inOff+16)).
     * in and out may be the same array (even overlapping at the same offset).
     */
    public void encryptBlock(byte[] in, int inOff, byte[] out, int outOff) {
        int s0 = ((in[inOff] & 0xff) << 24) | ((in[inOff + 1] & 0xff) << 16)
               | ((in[inOff + 2] & 0xff) << 8) | (in[inOff + 3] & 0xff);
        int s1 = ((in[inOff + 4] & 0xff) << 24) | ((in[inOff + 5] & 0xff) << 16)
               | ((in[inOff + 6] & 0xff) << 8) | (in[inOff + 7] & 0xff);
        int s2 = ((in[inOff + 8] & 0xff) << 24) | ((in[inOff + 9] & 0xff) << 16)
               | ((in[inOff + 10] & 0xff) << 8) | (in[inOff + 11] & 0xff);
        int s3 = ((in[inOff + 12] & 0xff) << 24) | ((in[inOff + 13] & 0xff) << 16)
               | ((in[inOff + 14] & 0xff) << 8) | (in[inOff + 15] & 0xff);
        s0 ^= rk[0];
        s1 ^= rk[1];
        s2 ^= rk[2];
        s3 ^= rk[3];

        int r = 4;
        for (int round = 1; round < 14; round++) {          // rounds 1..13
            int t0 = TE0[(s0 >>> 24) & 0xff] ^ TE1[(s1 >>> 16) & 0xff]
                   ^ TE2[(s2 >>> 8) & 0xff] ^ TE3[s3 & 0xff] ^ rk[r];
            int t1 = TE0[(s1 >>> 24) & 0xff] ^ TE1[(s2 >>> 16) & 0xff]
                   ^ TE2[(s3 >>> 8) & 0xff] ^ TE3[s0 & 0xff] ^ rk[r + 1];
            int t2 = TE0[(s2 >>> 24) & 0xff] ^ TE1[(s3 >>> 16) & 0xff]
                   ^ TE2[(s0 >>> 8) & 0xff] ^ TE3[s1 & 0xff] ^ rk[r + 2];
            int t3 = TE0[(s3 >>> 24) & 0xff] ^ TE1[(s0 >>> 16) & 0xff]
                   ^ TE2[(s1 >>> 8) & 0xff] ^ TE3[s2 & 0xff] ^ rk[r + 3];
            s0 = t0;
            s1 = t1;
            s2 = t2;
            s3 = t3;
            r += 4;
        }

        // final round: SubBytes + ShiftRows + AddRoundKey (no MixColumns); r == 56
        int o0 = ((SBOX[(s0 >>> 24) & 0xff] << 24) | (SBOX[(s1 >>> 16) & 0xff] << 16)
                | (SBOX[(s2 >>> 8) & 0xff] << 8) | SBOX[s3 & 0xff]) ^ rk[56];
        int o1 = ((SBOX[(s1 >>> 24) & 0xff] << 24) | (SBOX[(s2 >>> 16) & 0xff] << 16)
                | (SBOX[(s3 >>> 8) & 0xff] << 8) | SBOX[s0 & 0xff]) ^ rk[57];
        int o2 = ((SBOX[(s2 >>> 24) & 0xff] << 24) | (SBOX[(s3 >>> 16) & 0xff] << 16)
                | (SBOX[(s0 >>> 8) & 0xff] << 8) | SBOX[s1 & 0xff]) ^ rk[58];
        int o3 = ((SBOX[(s3 >>> 24) & 0xff] << 24) | (SBOX[(s0 >>> 16) & 0xff] << 16)
                | (SBOX[(s1 >>> 8) & 0xff] << 8) | SBOX[s2 & 0xff]) ^ rk[59];

        out[outOff] = (byte) (o0 >>> 24);
        out[outOff + 1] = (byte) (o0 >>> 16);
        out[outOff + 2] = (byte) (o0 >>> 8);
        out[outOff + 3] = (byte) o0;
        out[outOff + 4] = (byte) (o1 >>> 24);
        out[outOff + 5] = (byte) (o1 >>> 16);
        out[outOff + 6] = (byte) (o1 >>> 8);
        out[outOff + 7] = (byte) o1;
        out[outOff + 8] = (byte) (o2 >>> 24);
        out[outOff + 9] = (byte) (o2 >>> 16);
        out[outOff + 10] = (byte) (o2 >>> 8);
        out[outOff + 11] = (byte) o2;
        out[outOff + 12] = (byte) (o3 >>> 24);
        out[outOff + 13] = (byte) (o3 >>> 16);
        out[outOff + 14] = (byte) (o3 >>> 8);
        out[outOff + 15] = (byte) o3;
    }

    /**
     * AES-256-CTR, in-place over data[off..off+len). Encryption and decryption are
     * the same operation. iv16 is the INITIAL counter block and is NOT modified;
     * keystream block i is AES(key, iv16 + i) where the 16-byte counter block is
     * treated as one 128-bit big-endian integer with wraparound at 2^128.
     */
    public static void ctr(byte[] key32, byte[] iv16, byte[] data, int off, int len) {
        if (iv16 == null || iv16.length != 16) {
            throw new IllegalArgumentException("CTR IV must be 16 bytes");
        }
        if (len < 0 || off < 0 || off + len > data.length) {
            throw new IllegalArgumentException("bad CTR range");
        }
        Aes aes = new Aes(key32);
        byte[] counter = new byte[16];
        System.arraycopy(iv16, 0, counter, 0, 16);
        byte[] ks = new byte[16];
        int pos = off;
        int end = off + len;
        while (pos < end) {
            aes.encryptBlock(counter, 0, ks, 0);
            int n = end - pos;
            if (n > 16) {
                n = 16;
            }
            for (int i = 0; i < n; i++) {
                data[pos + i] ^= ks[i];
            }
            pos += n;
            // increment counter as 128-bit big-endian integer (wraps at 2^128)
            for (int i = 15; i >= 0; i--) {
                counter[i]++;
                if (counter[i] != 0) {
                    break;
                }
            }
        }
    }
}
