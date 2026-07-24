package nok.core;

/**
 * Hand-rolled UTF-8 codec for the note-content and crypto byte<->String
 * boundary. Java 1.3 / CLDC 1.1 only; no javax imports.
 *
 * <p>Exists because the platform codec cannot be trusted on this hardware.
 * Path.segEnc (Path.java) documents the encode half of the problem: some
 * Symbian CLDC VMs emit CESU-8, spelling an astral code point as two 3-byte
 * surrogate halves instead of one 4-byte sequence, so a strict consumer never
 * sees the emoji. The decode half is untrusted the same way. Routing every
 * note read/write and every crypto key-derivation input through this class
 * makes the conversion deterministic and identical on the phone, in the
 * emulator and in the desktop tools (tools/nokcrypt.py). Contract:
 * CONTRACTS.md, section "nok.core.Utf8".
 *
 * <p>Two deliberate deviations from a strict RFC-3629 codec, both required by
 * the device:
 * <ul>
 * <li>decode() is CESU-8 tolerant. A 3-byte form whose value lands in
 *     U+D800..U+DFFF is emitted as that surrogate char unchanged rather than
 *     rejected, so a CESU-8 high+low pair reassembles into a proper astral
 *     char for free (a Java String is UTF-16, so two appended halves already
 *     ARE the code point). Re-encoding that String then yields spec 4-byte
 *     UTF-8, which is how a note typed on the phone lands correctly in git.
 * <li>Neither direction ever throws. decode() substitutes U+FFFD for any
 *     malformed byte and resyncs at the next lead byte; encode() substitutes
 *     U+FFFD's bytes (EF BF BD) for an unpaired surrogate half. Both run on the
 *     sync worker over bytes a flaky FileConnection or a corrupt repo may have
 *     mangled, and a thrown exception there aborts a whole sync pass.
 * </ul>
 *
 * <p>For any WELL-FORMED input the output is byte-identical to J2SE
 * String.getBytes("UTF-8") / new String(bytes,"UTF-8"); the deviations above
 * only ever fire on CESU-8 or malformed input, which a conforming stack never
 * produces. The class is stateless, so both methods are safe to call from the
 * sync thread and the UI thread at once. Both size their output in a single
 * up-front pass rather than growing a buffer, for the same ~2MB-heap reason
 * Base64 does (a note or merged file can be hundreds of KB).
 */
public final class Utf8 {

    // The Unicode replacement character, substituted for every malformed byte
    // (decode) and every unpaired surrogate half (encode). Kept as a constant
    // so the two encode sites and the decode site cannot drift apart.
    private static final char REPL = '\uFFFD';

    private Utf8() {
    }

    /** Decodes the whole array; convenience for the common full-buffer case. */
    public static String decode(byte[] b) {
        return decode(b, 0, b == null ? 0 : b.length);
    }

    /**
     * Decodes len UTF-8 bytes starting at off. Handles 1/2/3/4-byte forms;
     * 4-byte forms above U+FFFF become a surrogate pair by hand. CESU-8 3-byte
     * surrogate halves pass through unchanged (see the class comment). Any
     * malformed byte - bad or missing continuation, overlong form, out-of-range
     * or 0xF8+ lead, lone continuation - becomes one U+FFFD, after which the
     * loop resumes at the next byte that is not a continuation. Never throws;
     * returns "" for null/empty/non-positive len.
     */
    public static String decode(byte[] b, int off, int len) {
        if (b == null || len <= 0) {
            return "";
        }
        // len is an upper bound on the char count: every char costs at least
        // one byte (a 4-byte form yields two chars from four bytes, still <=
        // len), so the buffer is sized once and never grown.
        StringBuffer sb = new StringBuffer(len);
        int end = off + len;
        int i = off;
        while (i < end) {
            int c0 = b[i] & 0xFF;
            if (c0 < 0x80) {
                // 1-byte ASCII: the overwhelmingly common case, kept first.
                sb.append((char) c0);
                i++;
                continue;
            }
            // Decode the lead byte into an expected continuation count, the
            // seed bits, and the overlong threshold. 0x80..0xBF is a lone
            // continuation and 0xF8+ is an obsolete >=5-byte lead; both are
            // invalid leads and collapse to one U+FFFD below.
            int need;
            int cp;
            int min;
            if (c0 < 0xC0) {
                sb.append(REPL);
                i++;
                continue;
            } else if (c0 < 0xE0) {
                need = 1;
                cp = c0 & 0x1F;
                min = 0x80;
            } else if (c0 < 0xF0) {
                need = 2;
                cp = c0 & 0x0F;
                min = 0x800;
            } else if (c0 < 0xF8) {
                need = 3;
                cp = c0 & 0x07;
                min = 0x10000;
            } else {
                sb.append(REPL);
                i++;
                continue;
            }
            // Gather exactly the continuation bytes this lead needs. j counts
            // bytes consumed including the lead, so on a short or bad tail it is
            // both the U+FFFD's width and the amount to advance: consuming the
            // lead plus the valid continuations seen (the "maximal subpart")
            // means a following non-continuation byte is left to resync as its
            // own char rather than being eaten.
            int j = 1;
            boolean ok = true;
            while (j <= need) {
                if (i + j >= end) {
                    ok = false;
                    break;
                }
                int cc = b[i + j] & 0xFF;
                if ((cc & 0xC0) != 0x80) {
                    ok = false;
                    break;
                }
                cp = (cp << 6) | (cc & 0x3F);
                j++;
            }
            if (!ok) {
                sb.append(REPL);
                i += j;
                continue;
            }
            // A structurally complete sequence: reject overlong spellings and
            // anything past U+10FFFF, consuming the whole (invalid) run. Note
            // that D800..DFFF is deliberately NOT rejected here - a 3-byte
            // surrogate form is the CESU-8 half we must keep.
            if (cp < min || cp > 0x10FFFF) {
                sb.append(REPL);
                i += (need + 1);
                continue;
            }
            if (cp < 0x10000) {
                // BMP, including a CESU-8 surrogate half emitted verbatim.
                sb.append((char) cp);
            } else {
                // Astral: split into a high+low surrogate pair by hand (CLDC
                // 1.1 has no Character.toChars / codePointAt).
                cp -= 0x10000;
                sb.append((char) (0xD800 + (cp >> 10)));
                sb.append((char) (0xDC00 + (cp & 0x3FF)));
            }
            i += (need + 1);
        }
        return sb.toString();
    }

    /**
     * Encodes a String to spec UTF-8. A matched high+low surrogate pair becomes
     * one 4-byte sequence (the arithmetic mirrors Path.segEnc); any unpaired
     * surrogate half - a high with no low after it, or a stray low - becomes
     * U+FFFD's bytes (EF BF BD). Byte-identical to J2SE getBytes("UTF-8") for
     * well-formed input; the unpaired-half substitution is the only divergence
     * and only fires on ill-formed UTF-16, which a note round-tripped through
     * decode() never contains. Never throws; returns an empty array for
     * null/empty input.
     */
    public static byte[] encode(String s) {
        if (s == null || s.length() == 0) {
            return new byte[0];
        }
        int n = s.length();
        // Pass 1: exact byte count. The branching mirrors pass 2 so the array
        // is sized to the byte and never grown-and-copied (Base64 explains why
        // that matters on this heap).
        int size = 0;
        int i = 0;
        while (i < n) {
            int cp = s.charAt(i);
            if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n) {
                int lo = s.charAt(i + 1);
                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                    size += 4;
                    i += 2;
                    continue;
                }
            }
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                // Unpaired surrogate half -> the 3 bytes of U+FFFD.
                size += 3;
            } else if (cp < 0x80) {
                size += 1;
            } else if (cp < 0x800) {
                size += 2;
            } else {
                size += 3;
            }
            i++;
        }
        byte[] out = new byte[size];
        int o = 0;
        i = 0;
        while (i < n) {
            int cp = s.charAt(i);
            if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n) {
                int lo = s.charAt(i + 1);
                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                    // Combine the pair into one code point, then split into the
                    // 4-byte form (mirrors Path.segEnc, Path.java ~line 195).
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    out[o++] = (byte) (0xF0 | (cp >> 18));
                    out[o++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                    out[o++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                    out[o++] = (byte) (0x80 | (cp & 0x3F));
                    i += 2;
                    continue;
                }
            }
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                out[o++] = (byte) 0xEF;
                out[o++] = (byte) 0xBF;
                out[o++] = (byte) 0xBD;
            } else if (cp < 0x80) {
                out[o++] = (byte) cp;
            } else if (cp < 0x800) {
                out[o++] = (byte) (0xC0 | (cp >> 6));
                out[o++] = (byte) (0x80 | (cp & 0x3F));
            } else {
                out[o++] = (byte) (0xE0 | (cp >> 12));
                out[o++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                out[o++] = (byte) (0x80 | (cp & 0x3F));
            }
            i++;
        }
        return out;
    }
}
