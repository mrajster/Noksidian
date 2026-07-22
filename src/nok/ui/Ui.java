package nok.ui;

import java.util.Vector;

import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Shared constants and drawing helpers for the custom Canvas UI toolkit.
 *
 * Soft-key / select key codes follow the Nokia S60 convention (also wired
 * into the emulator device). Text keys are the printable ASCII range plus
 * the extended block; the d-pad is read through getGameAction elsewhere.
 * Every helper is defensive: never allocates per frame beyond what it must,
 * never divides by zero, never loops forever on a zero/negative width.
 *
 * The class holds no mutable state - only static final key codes - so no
 * method needs synchronizing whichever thread calls it; all layout state
 * lives in the calling screens. Colors are read straight out of Theme at
 * draw time, so a theme switch needs no invalidation here.
 */
public final class Ui {

    // MIDP assigns no portable constants to the soft keys: it only reserves
    // negative key codes for device-specific keys and leaves the numbering to
    // the handset. These are the S60 values the E71 reports from keyPressed;
    // on a non-Nokia device they would be different numbers.

    /** Left soft key (S60). */
    public static final int LSK = -6;
    /** Right soft key (S60). */
    public static final int RSK = -7;
    /** Middle / select key (S60). */
    public static final int MSK = -5;

    private Ui() {
    }

    /** True when keyCode is a printable text character (QWERTY typing). */
    public static boolean isChar(int keyCode) {
        // The gap skips 127..159 (DEL and the C1 control block); the second
        // test opens at 160 to admit Latin-1 and the rest of the BMP, which is
        // what carries the accented letters the E71 QWERTY can type. Negative
        // codes (soft keys, d-pad, clear) fail both tests.
        return (keyCode >= 32 && keyCode <= 126) || keyCode >= 160;
    }

    /** keyCode -> char. Assumes isChar(keyCode) was already checked. */
    public static char toChar(int keyCode) {
        // MIDP defines the key code of a character key to be that character's
        // Unicode value, so the narrowing cast is exact rather than a guess.
        return (char) keyCode;
    }

    /**
     * Word-wraps text to pixel width w for font f. Preserves explicit '\n'
     * (each becomes a line break), breaks on spaces, hard-breaks any word
     * wider than the whole line, and never loops forever when w <= 0.
     *
     * @return a Vector of String lines (at least one element).
     */
    public static Vector wrap(String text, Font f, int w) {
        Vector out = new Vector();
        if (text == null) {
            text = "";
        }
        int len = text.length();
        int start = 0;
        // i runs one past the end on purpose: the i == len pass flushes the
        // segment after the last '\n'. A trailing newline therefore yields a
        // final empty line rather than being swallowed.
        for (int i = 0; i <= len; i++) {
            if (i == len || text.charAt(i) == '\n') {
                String seg = text.substring(start, i);
                if (f == null || w <= 0) {
                    // No font (nothing to measure with), or the width is not
                    // known yet: emit the segment unwrapped. wrapLine would
                    // NPE on a null font, and against w <= 0 it would break
                    // every word down to single characters.
                    out.addElement(seg);
                } else {
                    wrapLine(seg, f, w, out);
                }
                start = i + 1;
            }
        }
        return out;
    }

    /** Wraps a single newline-free segment, appending lines to out. */
    private static void wrapLine(String line, Font f, int w, Vector out) {
        int n = line.length();
        if (n == 0) {
            out.addElement("");
            return;
        }
        // stringWidth() walks the string glyph by glyph, so the separator width
        // is hoisted out of the loop instead of being remeasured per word.
        int spaceW = f.stringWidth(" ");
        StringBuffer cur = new StringBuffer();
        int curW = 0;
        int i = 0;
        // The running width is carried in curW instead of remeasuring cur on
        // every append, which holds ordinary wrapping to one stringWidth call
        // per word (the hard-break path below costs more).
        while (i < n) {
            while (i < n && isSpace(line.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int ws = i;
            while (i < n && !isSpace(line.charAt(i))) {
                i++;
            }
            String word = line.substring(ws, i);
            int wordW = f.stringWidth(word);
            if (curW == 0) {
                if (wordW <= w) {
                    cur.append(word);
                    curW = wordW;
                } else {
                    curW = placeBroken(word, f, w, out, cur);
                }
            } else if (curW + spaceW + wordW <= w) {
                cur.append(' ').append(word);
                curW += spaceW + wordW;
            } else {
                out.addElement(cur.toString());
                cur.setLength(0);
                curW = 0;
                if (wordW <= w) {
                    cur.append(word);
                    curW = wordW;
                } else {
                    curW = placeBroken(word, f, w, out, cur);
                }
            }
        }
        // Flushes the last partial line, and stays unconditional so a segment
        // of nothing but whitespace still emits one line: a source line must
        // never collapse to zero lines of layout.
        out.addElement(cur.toString());
    }

    /**
     * Hard-breaks an over-long word: emits every full-width fragment as its
     * own line and leaves the trailing partial fragment in cur. cur is empty
     * on entry. Returns the pixel width left in cur.
     */
    private static int placeBroken(String word, Font f, int w, Vector out,
            StringBuffer cur) {
        int n = word.length();
        int i = 0;
        while (i < n) {
            int j = i;
            // Linear widen, allocating and measuring a fresh substring on every
            // probe, so this is quadratic in the fragment length. Tolerated
            // because wrapLine only calls in for a word wider than the whole
            // line (long URLs, base64 blobs), never for ordinary prose.
            while (j < n && f.stringWidth(word.substring(i, j + 1)) <= w) {
                j++;
            }
            if (j == i) {
                j = i + 1; // one char wider than the whole line: force progress
            }
            String piece = word.substring(i, j);
            if (j < n) {
                out.addElement(piece);
                i = j;
            } else {
                cur.append(piece);
                return f.stringWidth(piece);
            }
        }
        return 0;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r';
    }

    /**
     * Draws a vertical scrollbar in the track (x,y)-(x+3,y+h). Nothing is
     * drawn when the content fits (total <= shown) or the track is empty.
     *
     * total, shown and off are unit-agnostic: only their ratios are used, so
     * callers scroll in whatever unit suits them and merely have to be
     * self-consistent within one call. Library and VaultPicker pass pixels;
     * Settings, UiMenu and UiEditor pass row/line counts. off is the offset of
     * the first visible unit; the thumb position derived from it is clamped to
     * the track, so a caller that over-scrolls still draws a sane thumb.
     */
    public static void drawScrollbar(Graphics g, int x, int y, int h,
            int total, int shown, int off) {
        if (h <= 0 || total <= shown || shown <= 0) {
            return;
        }
        int wdt = 3;
        g.setColor(Theme.placeholderBg);
        g.fillRect(x, y, wdt, h);
        // Proportional thumb, floored at 8px so a very long document still
        // leaves a thumb big enough to see, then re-clamped to the track in
        // case that floor overshot a very short track.
        int thumb = shown * h / total;
        if (thumb < 8) {
            thumb = 8;
        }
        if (thumb > h) {
            thumb = h;
        }
        // The thumb travels over the track minus its own length, so full scroll
        // (off == max) lands its bottom edge exactly on the track bottom. The
        // early return already guarantees max > 0, so the ternary is belt and
        // braces against a future caller reaching this without that guard.
        int max = total - shown;
        int range = h - thumb;
        int ty = (max > 0) ? off * range / max : 0;
        if (ty < 0) {
            ty = 0;
        }
        if (ty > range) {
            ty = range;
        }
        g.setColor(Theme.scrollbar);
        g.fillRect(x, y + ty, wdt, thumb);
    }

    /** Truncates s with a trailing ".." so it fits pixel width w in font f. */
    public static String clip(String s, Font f, int w) {
        if (s == null) {
            return "";
        }
        // Drop glyphs the E71 system font cannot draw (emoji) before measuring,
        // so decorative emoji in names render as clean text, not empty boxes.
        s = plain(s);
        if (f == null || w <= 0 || f.stringWidth(s) <= w) {
            return s;
        }
        // Two ASCII dots rather than the U+2026 ellipsis: everything drawn
        // here goes through plain() first, and the codebase keeps drawn text
        // to characters the E71 system font is known to carry.
        String ell = "..";
        int ew = f.stringWidth(ell);
        if (ew > w) {
            // Not even the marker fits; anything drawn here would overflow.
            return "";
        }
        // Binary search for the longest prefix that fits alongside the marker.
        // substringWidth measures in place, so this costs O(log n) measurement
        // calls and allocates no intermediate strings, which matters because
        // clip runs on every row of every list (Library, UiList, VaultPicker,
        // Settings) on every repaint. The upper midpoint (lo+hi+1)>>1 is
        // required: with the lower one, lo == hi - 1 would pick mid == lo and
        // a fitting prefix would leave lo unchanged, looping forever.
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >> 1;
            if (f.substringWidth(s, 0, mid) + ew <= w) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo) + ell;
    }

    /**
     * Strips characters the E71 system font cannot render - astral-plane emoji
     * (UTF-16 surrogate pairs), variation selectors, the zero-width joiner and
     * the emoji / dingbat symbol blocks - so decorative emoji in file and
     * folder names show as clean text instead of empty boxes. Latin letters
     * (including Slovenian c/s/z), digits, arrows and ordinary punctuation are
     * kept. Whitespace left behind is collapsed and the ends trimmed. The
     * vault's real filenames are never changed; this only affects drawn text.
     * The common no-emoji string is returned unchanged with no allocation.
     */
    public static String plain(String s) {
        if (s == null) {
            return "";
        }
        int n = s.length();
        StringBuffer b = null; // allocated only once we actually drop a char
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (isUndrawable(c)) {
                if (b == null) {
                    b = new StringBuffer(n);
                    b.append(s.substring(0, i));
                }
            } else if (b != null) {
                b.append(c);
            }
        }
        if (b == null) {
            return s;
        }
        return collapseSpaces(b.toString());
    }

    /** True for codepoints the E71 font renders as a missing-glyph box. */
    private static boolean isUndrawable(char c) {
        // Ranges, not a lookup table: this runs per character of every drawn
        // label, and a table would cost permanent heap to replace what is only
        // a handful of integer compares even on the fall-through ASCII path.
        int u = c & 0xFFFF;
        // Rejecting the whole surrogate range drops both halves of every pair,
        // so no orphaned half can survive into the drawn string. CLDC 1.1 has
        // no codePointAt, so pairs cannot be inspected as single characters.
        if (u >= 0xD800 && u <= 0xDFFF) {
            return true; // UTF-16 surrogate half: all astral-plane emoji
        }
        if (u == 0x200D || (u >= 0xFE00 && u <= 0xFE0F)) {
            return true; // ZWJ and variation selectors (e.g. the U+FE0F in ✔️)
        }
        if (u >= 0x2600 && u <= 0x27BF) {
            return true; // misc symbols + dingbats (☀ ✔ ✈ ...)
        }
        if (u >= 0x2300 && u <= 0x23FF) {
            return true; // misc technical (⏰ ⚙ ⌚ ...)
        }
        if (u >= 0x2B00 && u <= 0x2BFF) {
            return true; // misc symbols and arrows (⭐ ...)
        }
        if (u == 0x2122 || u == 0x2139) {
            return true; // ™ ℹ
        }
        return false;
    }

    /** Collapses runs of spaces/tabs to one space and trims both ends. */
    private static String collapseSpaces(String s) {
        StringBuffer b = new StringBuffer(s.length());
        boolean prevSpace = true; // start true so leading space is trimmed
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                if (!prevSpace) {
                    b.append(' ');
                }
                prevSpace = true;
            } else {
                b.append(c);
                prevSpace = false;
            }
        }
        int len = b.length();
        while (len > 0 && b.charAt(len - 1) == ' ') {
            len--;
        }
        b.setLength(len);
        return b.toString();
    }

    /**
     * Renders the back screen's current frame into an offscreen image and
     * darkens every pixel ~50% so a popup (menu / dialog) can float above the
     * dimmed context instead of erasing it. Darkening runs in place in
     * horizontal strips through one small reused int[] buffer (~30KB), so no
     * second full-screen snapshot is allocated; the returned image (the frame
     * itself) is retained by the caller, which should cache it and blit it on
     * every repaint rather than rebuilding it.
     *
     * <p>Returns null when there is nothing to render (back is null or an
     * unknown Displayable) or the image could not be created (low memory);
     * the caller then falls back to a solid {@link Theme#bg} fill.</p>
     *
     * <p>The back screen's paint() is invoked directly here, outside the
     * platform's own repaint cycle, so the caller must not run this while that
     * screen could be painting concurrently. The result is a snapshot of one
     * moment: UiMenu and UiDialog cache it keyed on width and height and
     * rebuild only when either differs, so it will not track later changes to
     * the back screen. UiMenu goes further and builds it in prebuild(), while
     * the back screen is still current, to avoid a visible stall on the E71.</p>
     */
    public static Image dimSnapshot(Displayable back, int w, int h) {
        if (back == null || w <= 0 || h <= 0) {
            return null;
        }
        try {
            Image frame = Image.createImage(w, h);
            Graphics ig = frame.getGraphics();
            if (!paintBack(back, ig)) {
                return null;
            }
            // Fresh Graphics: paintBack handed `ig` to arbitrary screen paint()
            // methods (e.g. Viewer.paint sets clip/translate) and that state
            // persists on the same object; a new getGraphics() gives full clip
            // and zero translate so the darkened output can't be corrupted.
            Graphics dg = frame.getGraphics();
            // 24 rows x 320 px x 4 bytes is about 30KB. A whole-screen int[]
            // would be ~300KB - a sixth of the ~2MB heap - demanded at the
            // exact moment a popup is opening, which is when the heap is
            // already carrying the note being viewed.
            final int STRIP = 24;
            int[] px = new int[w * STRIP];
            // Read a strip out of the frame, darken it, blit it straight back
            // into the same image. Safe in place because each strip is fully
            // read before any of it is written, and strips never overlap.
            for (int y = 0; y < h; y += STRIP) {
                int rows = h - y;
                if (rows > STRIP) {
                    rows = STRIP;
                }
                frame.getRGB(px, 0, w, 0, y, w, rows);
                int n = w * rows;
                for (int i = 0; i < n; i++) {
                    px[i] = (px[i] >> 1) & 0x7F7F7F;   // halve R,G,B (drop alpha)
                }
                dg.drawRGB(px, 0, w, 0, y, w, rows, false);
            }
            return frame;
        } catch (Throwable t) {
            return null;   // OOM or paint failure -> caller fills solid bg
        }
    }

    /**
     * Repaints a known nok.ui screen into an offscreen Graphics. paint() is
     * protected but every candidate type lives in this package, so the call is
     * legal package access. Returns false for types we cannot render.
     */
    private static boolean paintBack(Displayable back, Graphics ig) {
        // UiScreen covers Library, Settings, VaultPicker, UiInput, UiList and
        // UiEditor (hence Editor); Viewer and ImageView extend Canvas directly.
        // UiMenu's MenuCanvas and UiDialog's DialogCanvas match none of these
        // and fall through to false, so a popup stacked on a popup draws over a
        // plain fill instead of a snapshot of the popup underneath.
        if (back instanceof UiScreen) {
            ((UiScreen) back).paint(ig);
            return true;
        }
        if (back instanceof Viewer) {
            ((Viewer) back).paint(ig);
            return true;
        }
        if (back instanceof ImageView) {
            ((ImageView) back).paint(ig);
            return true;
        }
        return false;
    }
}
