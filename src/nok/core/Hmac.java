package nok.core;

/**
 * RFC 2104 HMAC with SHA-256 for CLDC 1.1 / Java 1.3.
 * Keys longer than the 64-byte SHA-256 block size are hashed first;
 * shorter keys are zero-padded to the block size.
 *
 * CLDC 1.1 ships no javax.crypto and no java.security.Mac, so this is the only
 * MAC in the vault stack and everything authenticated funnels through it: the
 * _vault.nkv "check" value that spots a wrong password before a single file is
 * touched, the encrypt-then-MAC tag over every NKE1 file, VaultCrypto's SIV-style
 * IV derivation (no secure RNG exists on the phone), and PBKDF2's U_1.
 *
 * Deliberately simple and allocation-heavy, because it is called rarely: every
 * invocation allocates two 64-byte pads, two Sha256 instances with their own
 * scratch arrays, and two digest buffers. That is fine per file, but it is
 * exactly why Pbkdf2 calls this only once per output block (for U_1) and then
 * runs its own inner loop over cached ipad/opad midstates -- routing 8192
 * iterations through here would churn well over a hundred thousand short-lived
 * objects on a ~2MB heap.
 *
 * Stateless, hence thread-safe: all working state is local to sha256() and the
 * Sha256 instances never escape. VaultCrypto relies on that, since its sync
 * thread and UI thread can both be encrypting at the same time.
 *
 * Verification is NOT done here. Callers compare tags themselves, and VaultCrypto
 * does so in constant time (OR of XORs) so a tampered file cannot be probed byte
 * by byte via timing.
 *
 * Byte-exactness is pinned by RFC 4231 cases 1-4 (plus the long-key case 6, which
 * is the only one that exercises the key-folding branch below) in test/TestSha.java.
 */
public final class Hmac {

    /**
     * RFC 2104's B: SHA-256's *compression block* size, not its 32-byte digest
     * size. The pads are built to B, so a 32-byte macKey gets zero-padded out to
     * 64 here; getting this wrong yields plausible-looking but non-interoperable
     * tags that tools/nokcrypt.py would reject.
     */
    private static final int BLOCK = 64;

    private Hmac() {
    }

    /** HMAC-SHA256(key, msg) -> 32 bytes. */
    public static byte[] sha256(byte[] key, byte[] msg) {
        // Over-long keys are folded with H so they fit a block. Short keys are only
        // read from, never written, so k may safely alias the caller's array and no
        // defensive copy is made. That matters: the caller's array is usually a live
        // macKey, and a copy would be one more heap object holding key material that
        // CLDC gives no way to scrub.
        byte[] k = key;
        if (k.length > BLOCK) {
            k = Sha256.hash(k);
        }
        byte[] ipad = new byte[BLOCK];
        byte[] opad = new byte[BLOCK];
        // One pass builds both pads: the ternary supplies RFC 2104's zero padding for
        // i >= k.length implicitly, so no separate arraycopy-then-fill is needed. The
        // & 0xff is normalization, not correctness -- Java bytes are signed, and it
        // keeps b in 0..255 so both branches of the ternary live in the same domain.
        // The (byte) casts would truncate a sign-extended value to the same result.
        for (int i = 0; i < BLOCK; i++) {
            int b = (i < k.length) ? (k[i] & 0xff) : 0;
            ipad[i] = (byte) (b ^ 0x36);
            opad[i] = (byte) (b ^ 0x5c);
        }
        // Two separate instances: Sha256.digest() finalizes and the instance cannot
        // be reset, so the inner and outer hashes cannot share one.
        Sha256 inner = new Sha256();
        inner.update(ipad, 0, BLOCK);
        inner.update(msg, 0, msg.length);
        byte[] ih = inner.digest();
        Sha256 outer = new Sha256();
        outer.update(opad, 0, BLOCK);
        outer.update(ih, 0, ih.length);
        return outer.digest();
    }
}
