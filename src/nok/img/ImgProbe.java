package nok.img;

import java.io.IOException;
import java.io.InputStream;

/**
 * Header-only image sniffer (pure CLDC 1.1). Reads just the leading marker /
 * chunk bytes of a JSR-75 image stream and reports its pixel dimensions and a
 * decodability kind, WITHOUT loading the whole file into memory. This is the
 * cheap gate that lets the viewers refuse a multi-megapixel photo before the
 * decode allocation OOMs the ~2MB E71 heap.
 *
 * <p>Only PNG (IHDR) and JPEG (SOFn) are understood; anything else (GIF, BMP,
 * ciphertext, garbage) returns {@link #KIND_NONE} with 0x0 so the caller can
 * fall through to a normal guarded decode. The probe never throws - every
 * failure path returns the not-an-image holder.</p>
 *
 * <p>Result is a small {@code int[3]}: {@code {width, height, kind}}. Skips are
 * looped because JSR-75 {@code InputStream.skip} may short-skip, and segment
 * bodies (large EXIF APP1 blocks, etc.) are skipped rather than buffered, so
 * memory stays at a few hundred bytes regardless of file size.</p>
 *
 * <p>Policy lives in the callers, not here: this class only reports what the
 * header claims. Files.probeImage opens the stream (and memoizes each non-null
 * result per vault-relative path), then Viewer.layoutImageBlock and ImageView
 * gate on KIND_UNSUPPORTED or on width*height exceeding their own PIX_MAX;
 * Viewer then falls back to a desktop-generated preview sidecar, ImageView to a
 * placeholder. KIND_NONE therefore means "unrecognised, go ahead and try a
 * guarded decode", never "reject".</p>
 */
public final class ImgProbe {

    // Kind codes, returned in slot 2 of the result array. They classify the
    // ENCODING only; the pixel-count budget is the caller's business.

    /** Not a recognised image, or the header could not be parsed. */
    public static final int KIND_NONE = 0;
    /** Baseline / extended-sequential JPEG or PNG: native-decodable (size permitting). */
    public static final int KIND_OK = 1;
    /** Progressive / arithmetic / lossless JPEG: refuse (cannot decode in budget). */
    public static final int KIND_UNSUPPORTED = 2;

    /** Loop guard on stream advance so a malformed file can never spin forever. */
    private static final long SCAN_BUDGET = 8L * 1024 * 1024;

    private ImgProbe() {
    }

    /**
     * Probes an already-open image input stream. Consumes bytes from it (the
     * caller should not reuse the stream for decoding). Returns
     * {@code {width, height, kind}}; never throws, never null.
     */
    public static int[] probe(InputStream in) {
        try {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) {
                return none();
            }
            // Two magic bytes are enough to pick a parser; each helper resumes
            // from exactly this offset. PNG's helper still checks the other six
            // signature bytes, so a false match here costs nothing.
            if (b0 == 0x89 && b1 == 0x50) {
                return probePng(in);
            }
            if (b0 == 0xFF && b1 == 0xD8) {
                return probeJpeg(in);
            }
            return none();
        } catch (Throwable t) {
            // Throwable rather than IOException: this is reached from
            // Viewer.layoutImageBlock, so a truncated, encrypted or hostile
            // header must degrade to "unknown" instead of aborting the layout.
            return none();
        }
    }

    /** The not-an-image result {0, 0, KIND_NONE} returned from every failure path. */
    private static int[] none() {
        return new int[] { 0, 0, KIND_NONE };
    }

    /**
     * PNG: the first two bytes (0x89 'P') are already consumed. Verify the rest
     * of the 8-byte signature, then read the IHDR width @16 and height @20.
     */
    private static int[] probePng(InputStream in) throws IOException {
        int[] sig = { 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }; // "NG\r\n\032\n"
        for (int i = 0; i < sig.length; i++) {
            if (in.read() != sig[i]) {
                return none();
            }
        }
        // Signature (8) consumed. Skip IHDR length(4) + type(4) to reach the
        // width field at file offset 16.
        // The PNG spec pins IHDR as the first chunk, so there is no chunk walk
        // to do here - unlike the marker walk probeJpeg needs below.
        if (skipFully(in, 8) != 8) {
            return none();
        }
        int w = readInt(in);
        int h = readInt(in);
        // readInt hands back -1 on a short read, and PNG caps dimensions at
        // 2^31-1 (anything larger reads back negative), so this one test covers
        // truncation, zero and out-of-range alike.
        if (w <= 0 || h <= 0) {
            return none();
        }
        return new int[] { w, h, KIND_OK };
    }

    /**
     * JPEG: the SOI (0xFF 0xD8) is already consumed. Walk marker segments -
     * tolerating 0xFF fill bytes and the length-less standalone markers
     * (RSTn / TEM / stray SOI-EOI) - skipping every segment body until a SOFn
     * marker, whose payload gives precision, height, width.
     */
    private static int[] probeJpeg(InputStream in) throws IOException {
        long used = 0;
        while (used < SCAN_BUDGET) {
            int b = in.read();
            used++;
            if (b < 0) {
                return none();
            }
            if (b != 0xFF) {
                continue; // resync to the next marker prefix
            }
            int marker = in.read();
            used++;
            while (marker == 0xFF) { // 0xFF fill bytes before the marker code
                marker = in.read();
                used++;
            }
            if (marker < 0) {
                return none();
            }
            // Standalone markers carry no length segment.
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01
                    || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            int l1 = in.read();
            int l2 = in.read();
            used += 2;
            if ((l1 | l2) < 0) {
                return none();
            }
            int len = (l1 << 8) | l2;
            if (len < 2) {
                return none();
            }
            if (isSof(marker)) {
                int prec = in.read();
                int h1 = in.read();
                int h2 = in.read();
                int w1 = in.read();
                int w2 = in.read();
                if ((prec | h1 | h2 | w1 | w2) < 0) {
                    return none();
                }
                int h = (h1 << 8) | h2;
                int w = (w1 << 8) | w2;
                if (w <= 0 || h <= 0) {
                    return none();
                }
                // SOF0 (baseline) and SOF1 (extended sequential) decode on the
                // native path; SOF2/6/10/14 (progressive) and the arithmetic /
                // lossless / differential variants cannot be handled in budget.
                int kind = (marker == 0xC0 || marker == 0xC1)
                        ? KIND_OK : KIND_UNSUPPORTED;
                return new int[] { w, h, kind };
            }
            long body = len - 2;
            long sk = skipFully(in, body);
            if (sk > 0) {
                used += sk;
            }
            if (sk != body) {
                return none();
            }
        }
        return none();
    }

    /** SOFn = 0xC0..0xCF except DHT(0xC4), JPG(0xC8), DAC(0xCC). */
    private static boolean isSof(int m) {
        return m >= 0xC0 && m <= 0xCF
                && m != 0xC4 && m != 0xC8 && m != 0xCC;
    }

    /** Reads a 4-byte big-endian int; returns -1 on short read. */
    private static int readInt(InputStream in) throws IOException {
        int a = in.read();
        int b = in.read();
        int c = in.read();
        int d = in.read();
        if ((a | b | c | d) < 0) {
            return -1;
        }
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    /**
     * Skips exactly {@code n} bytes, looping because JSR-75 skip() may
     * short-skip or return 0. Falls back to a small buffered read (never a
     * whole-file read) when skip stalls. Returns the number actually skipped.
     */
    private static long skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        byte[] buf = null;
        while (remaining > 0) {
            long s = in.skip(remaining);
            if (s > 0) {
                remaining -= s;
                continue;
            }
            if (buf == null) {
                buf = new byte[512];
            }
            int want = (int) (remaining < buf.length ? remaining : buf.length);
            int r = in.read(buf, 0, want);
            if (r < 0) {
                break; // EOF
            }
            remaining -= r;
        }
        return n - remaining;
    }
}
