package nok.core;

/**
 * Standard Base64 (RFC 4648) encoder/decoder for CLDC 1.1.
 *
 * encode(): standard alphabet, padded output, no line breaks.
 * decode(): skips whitespace (space, tab, CR, LF) anywhere in the
 * input and tolerates missing padding. This is required because the
 * GitHub blob API returns base64 content with embedded newlines.
 *
 * Java 1.3 / CLDC 1.1 only. No javax imports.
 *
 * Hand-rolled because CLDC 1.1 ships no Base64 whatsoever (java.util.Base64
 * only arrived in Java 8) and nok.core must stay javax-free so the desktop
 * unit tests can exercise it unchanged.
 *
 * Both directions sit on the sync hot path: every pushed file is encoded
 * whole and every pulled blob is decoded whole, so both size their output
 * exactly up front instead of growing a buffer. A grow-and-copy scheme would
 * double the peak footprint at exactly the moment a few hundred KB of note or
 * image is already resident in the ~2MB heap. The other caller is the
 * "remember password" path, which stores the 64-byte derived key in the RMS
 * config as base64 because config values are plain strings.
 */
public final class Base64 {

    // Standard RFC 4648 alphabet ('+' and '/'), NOT the URL-safe variant:
    // both consumers -- the GitHub blobs/contents API and our own RMS config
    // -- speak standard base64. Emitting '-'/'_' instead would make GitHub
    // decode our pushes to the wrong bytes, and decode() below rejects those
    // two characters outright.
    // Held as char[] rather than String so the four lookups per input triple
    // are plain array loads instead of String.charAt calls.
    private static final char[] ENC =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            .toCharArray();

    /** DEC[c] = 6-bit value of char c, or -1 if not a base64 char. */
    // 128 entries covers ASCII only, which is all base64 can legally contain.
    // decode() tests c >= 128 before indexing, so a stray high UTF-16 char
    // can never run off the end. 512 bytes of permanent heap buys a single
    // table lookup per character instead of a chain of range comparisons.
    private static final int[] DEC = new int[128];

    static {
        for (int i = 0; i < 128; i++) {
            DEC[i] = -1;
        }
        for (int i = 0; i < 64; i++) {
            DEC[ENC[i]] = i;
        }
    }

    private Base64() {
    }

    /**
     * Encodes data with the standard alphabet, with '=' padding and
     * no line breaks. Returns "" for null or empty input.
     */
    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        int len = data.length;
        // Padded base64 is exactly ceil(len/3)*4 chars, so this capacity is
        // the final length, not an estimate: StringBuffer never has to grow
        // and copy. Encoding a 300KB image otherwise means several array
        // copies of a buffer that is already 4/3 the size of the input.
        StringBuffer sb = new StringBuffer(((len + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < len) {
            // Java bytes are signed, so every one is masked back to 0..255
            // before shifting. Without the mask a byte >= 0x80 sign-extends
            // and its ones flood the sextets of its neighbours.
            int b = ((data[i] & 0xFF) << 16)
                  | ((data[i + 1] & 0xFF) << 8)
                  |  (data[i + 2] & 0xFF);
            sb.append(ENC[(b >> 18) & 63]);
            sb.append(ENC[(b >> 12) & 63]);
            sb.append(ENC[(b >> 6) & 63]);
            sb.append(ENC[b & 63]);
            i += 3;
        }
        int rem = len - i;
        // Tail: 1 leftover byte is 8 bits -> 2 sextets (the second holds 2
        // real bits and 4 zeros) plus "=="; 2 leftover bytes are 16 bits ->
        // 3 sextets plus "=". The unused low bits are emitted as zero, which
        // is what canonical RFC 4648 requires.
        if (rem == 1) {
            int b = (data[i] & 0xFF) << 16;
            sb.append(ENC[(b >> 18) & 63]);
            sb.append(ENC[(b >> 12) & 63]);
            sb.append('=');
            sb.append('=');
        } else if (rem == 2) {
            int b = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(ENC[(b >> 18) & 63]);
            sb.append(ENC[(b >> 12) & 63]);
            sb.append(ENC[(b >> 6) & 63]);
            sb.append('=');
        }
        return sb.toString();
    }

    /**
     * Decodes base64. Skips space, tab, CR and LF anywhere; '=' padding
     * is optional (missing padding is tolerated). Throws
     * IllegalArgumentException on any other invalid character or on a
     * truncated input (a single leftover base64 char cannot encode a byte).
     * Returns an empty array for null, empty or all-whitespace input.
     */
    public static byte[] decode(String s) {
        if (s == null) {
            return new byte[0];
        }
        int len = s.length();
        int n = 0;
        // Pass 1 counts the significant chars so pass 2 can write into an
        // exactly-sized array. The input is a GitHub blob that may be
        // hundreds of KB, and on a ~2MB heap holding the string, a
        // grow-and-copy buffer and its replacement at once is what OOMs.
        // Validating here also means a corrupt payload throws before the
        // output array is ever allocated.
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            // '=' is treated as skippable filler rather than a terminator.
            // That is what makes missing padding tolerable and removes any
            // need to check that padding sits only at the end; the price is
            // that a stray interior '=' is accepted instead of rejected.
            // Whitespace is skipped because GitHub wraps blob base64 at 60
            // chars and the caller hands the payload over uncleaned.
            if (c == '=' || c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                continue;
            }
            if (c >= 128 || DEC[c] < 0) {
                throw new IllegalArgumentException(
                    "bad base64 char '" + c + "' at " + i);
            }
            n++;
        }
        // A single leftover char carries only 6 bits, which cannot complete a
        // byte no matter how much padding was dropped, so this length is
        // unreachable from any valid encoder and means the payload was cut
        // short in transit. Remainders of 2 and 3 are the legal short tails.
        if ((n & 3) == 1) {
            throw new IllegalArgumentException("truncated base64 input");
        }
        int outLen = (n / 4) * 3;
        int rem = n & 3;
        // 2 chars = 12 bits -> 1 whole byte (4 bits dropped);
        // 3 chars = 18 bits -> 2 whole bytes (2 bits dropped).
        if (rem == 2) {
            outLen += 1;
        } else if (rem == 3) {
            outLen += 2;
        }
        byte[] out = new byte[outLen];
        // Pass 2 shifts sextets into a bit accumulator and drains a byte
        // whenever 8 bits are available. buf keeps shifting left and will
        // overflow past 32 bits on any real input; that is harmless because
        // only the low 8 + bits bits are ever read back, and bits stays under
        // 8. No re-validation here: pass 1 already proved every surviving
        // char is below 128 and maps to a non-negative DEC entry, so the
        // c >= 128 / DEC[c] < 0 guard does not have to be repeated.
        int buf = 0;
        int bits = 0;
        int o = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '=' || c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                continue;
            }
            buf = (buf << 6) | DEC[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) ((buf >> bits) & 0xFF);
            }
        }
        // Any 2 or 4 bits still in buf belong to a partial byte and are
        // dropped. They are not checked for being zero, so a non-canonical
        // encoding decodes rather than throwing.
        return out;
    }
}
