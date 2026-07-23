package nok.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import nok.NoksidianMIDlet;
import nok.sys.Config;

/**
 * Themed special-character picker: a scrollable grid of symbols the E71
 * thumbboard cannot type directly, ordered most-used first.
 *
 * <p>Opened from the editor by the Ctrl key (any device key the editor does
 * not otherwise claim, since S60 assigns no portable code to Ctrl/Chr) or by
 * the "Insert symbol" menu command. Renders the editor's frame dimmed as the
 * backdrop and floats a bottom-anchored panel over it, exactly like UiMenu -
 * this is the same popup idiom, laid out as a grid instead of a row list.
 *
 * <p>Ordering: every pick increments a per-character counter kept in Config
 * under {@link #KEY} and the grid is sorted by that counter, descending, ties
 * falling back to the built-in order. So the first cells become whatever this
 * user actually inserts, while an untouched install still opens on the
 * markdown punctuation that is most likely to be wanted. The sort is a stable
 * insertion sort over ~70 entries built fresh on each open, which is cheaper
 * than it sounds and keeps no state alive between opens.
 *
 * <p>Everything here runs on the LCDUI thread; nothing is synchronized.
 */
public final class UiSymbols {

    /** Config key holding the usage counters (see encode/decode below). */
    public static final String KEY = "editor.symfreq";

    /**
     * The palette, in default (untouched) order: markdown syntax first, then
     * ordinary punctuation, typography, currency, maths and arrows. Written as
     * \\u escapes on purpose - build.sh runs javac with no -encoding flag, so a
     * literal non-ASCII source byte would be read in the platform default
     * charset and could reach the device as a different character.
     *
     * <p>Nothing here comes from a block Ui.plain() rejects: the E71 system
     * font draws a box for the emoji, dingbat and technical-symbol ranges
     * (U+2300..U+27BF, U+2B00..), so ticks, stars and the like are left out
     * rather than shipped as empty rectangles.
     *
     * <p>Reads, in order:
     * # * _ ` ~ [ ] ( ) &lt; &gt; | \ / ^ = + -
     * ! ? : ; , . ' " @ &amp; % $ { }
     * &#8212; &#8211; &#8230; &#8220; &#8221; &#8216; &#8217; &#171; &#187; &#8226; &#183; &#167; &#182; &#176;
     * &#8364; &#163; &#165; &#162;
     * &#177; &#215; &#247; &#8776; &#8800; &#8804; &#8805; &#8734; &#8730; &#189; &#188; &#190; &#181; &#960; &#937;
     * &#8592; &#8593; &#8594; &#8595; &#8596;
     */
    private static final String PALETTE =
            "#*_`~[]()<>|\\/^=+-"
            + "!?:;,.'\"@&%${}"
            + "\u2014\u2013\u2026\u201C\u201D\u2018\u2019\u00AB\u00BB\u2022"
            + "\u00B7\u00A7\u00B6\u00B0"
            + "\u20AC\u00A3\u00A5\u00A2"
            + "\u00B1\u00D7\u00F7\u2248\u2260\u2264\u2265\u221E\u221A"
            + "\u00BD\u00BC\u00BE\u00B5\u03C0\u03A9"
            + "\u2190\u2191\u2192\u2193\u2194";

    /**
     * Counter ceiling. Counts are persisted as decimal text in one Config
     * value, so an unbounded counter would grow the record forever; four
     * digits is far past the point where the order can still change.
     */
    private static final int MAX_COUNT = 9999;

    /** Separator between "&lt;char&gt;&lt;count&gt;" tokens in the stored value. */
    private static final char SEP = '\u0001';

    private final NoksidianMIDlet m;
    private final GridCanvas canvas;

    /**
     * Builds a picker over back - dimmed for the backdrop and returned to on
     * both pick and cancel - reporting the chosen character to owner. A null
     * owner still shows a working (if pointless) picker.
     */
    public UiSymbols(NoksidianMIDlet m, Displayable back, UiSymbolOwner owner) {
        this.m = m;
        this.canvas = new GridCanvas(back, owner);
    }

    /** Make the picker the current screen. */
    public void show() {
        if (m != null) {
            // Snapshot the back screen while it is still current, so switching
            // to this canvas blits a ready image instead of showing the E71's
            // ~1s white Canvas-clear (same reason as UiMenu.show).
            canvas.prebuild();
            m.show(canvas);
        }
    }

    // ------------------------------------------------------------------
    // Usage counters
    // ------------------------------------------------------------------

    /**
     * Counts for every palette character, index-parallel to PALETTE. Unknown
     * characters in the stored value (a palette that changed between versions)
     * are dropped silently rather than treated as corruption.
     */
    private static int[] counts() {
        int[] c = new int[PALETTE.length()];
        String s = Config.get(KEY, "");
        int i = 0;
        int n = s.length();
        while (i < n) {
            char sym = s.charAt(i);
            i++;
            int v = 0;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                v = v * 10 + (s.charAt(i) - '0');
                if (v > MAX_COUNT) {
                    v = MAX_COUNT;
                }
                i++;
            }
            // Skip to just past the separator, tolerating a value written by a
            // future version with extra fields in the token.
            while (i < n && s.charAt(i) != SEP) {
                i++;
            }
            i++;
            int at = PALETTE.indexOf(sym);
            if (at >= 0) {
                c[at] = v;
            }
        }
        return c;
    }

    /** Adds one to sym's counter and persists the whole table. Never throws. */
    private static void bump(char sym) {
        int[] c = counts();
        int at = PALETTE.indexOf(sym);
        if (at < 0) {
            return;
        }
        if (c[at] < MAX_COUNT) {
            c[at]++;
        }
        StringBuffer sb = new StringBuffer(64);
        for (int i = 0; i < c.length; i++) {
            // Only non-zero counters are written: a fresh install stores
            // nothing, and the value stays proportional to what was used
            // rather than to the palette size.
            if (c[i] > 0) {
                sb.append(PALETTE.charAt(i));
                sb.append(c[i]);
                sb.append(SEP);
            }
        }
        Config.set(KEY, sb.toString());
    }

    /**
     * The palette ordered most-used first. Stable insertion sort so characters
     * never used keep their default order behind the ones that were, which is
     * what makes the grid settle into a familiar layout instead of reshuffling
     * unpredictably.
     */
    private static char[] ordered() {
        int[] c = counts();
        int n = PALETTE.length();
        char[] out = new char[n];
        int[] key = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = PALETTE.charAt(i);
            key[i] = c[i];
        }
        for (int i = 1; i < n; i++) {
            char sym = out[i];
            int k = key[i];
            int j = i - 1;
            // Strictly-greater keeps equal counts in their original relative
            // order, which is what makes this sort stable.
            while (j >= 0 && key[j] < k) {
                out[j + 1] = out[j];
                key[j + 1] = key[j];
                j--;
            }
            out[j + 1] = sym;
            key[j + 1] = k;
        }
        return out;
    }

    // ------------------------------------------------------------------

    /**
     * The picker screen. Inner (non-static) so it can reach the enclosing
     * MIDlet reference for the show() calls that hand control back.
     */
    private final class GridCanvas extends Canvas {

        /** Screen to return to and to render dimmed behind the panel. */
        private final Displayable back;
        private final UiSymbolOwner owner;
        /** Palette snapshot taken at open time, so the grid cannot move under the cursor. */
        private final char[] syms;
        private int sel;
        private int scroll;    // first visible ROW (not pixels)
        /** Columns as of the last paint; the key handlers need the same value. */
        private int cols = 1;
        private Image dimBack;

        GridCanvas(Displayable back, UiSymbolOwner owner) {
            this.back = back;
            this.owner = owner;
            this.syms = ordered();
            try {
                setFullScreenMode(true);
            } catch (Throwable t) {
            }
        }

        /** Builds the dimmed backdrop while the back screen is still current. */
        void prebuild() {
            int w = getWidth();
            int h = getHeight();
            if (back instanceof Canvas) {
                w = ((Canvas) back).getWidth();
                h = ((Canvas) back).getHeight();
            }
            if (dimBack == null || dimBack.getWidth() != w
                    || dimBack.getHeight() != h) {
                dimBack = Ui.dimSnapshot(back, w, h);
            }
        }

        protected void paint(Graphics g) {
            int w = getWidth();
            int h = getHeight();

            if (dimBack == null || dimBack.getWidth() != w
                    || dimBack.getHeight() != h) {
                dimBack = Ui.dimSnapshot(back, w, h);
            }
            if (dimBack != null) {
                g.drawImage(dimBack, 0, 0, Graphics.TOP | Graphics.LEFT);
            } else {
                g.setColor((Theme.bg >> 1) & 0x7F7F7F);
                g.fillRect(0, 0, w, h);
            }

            Font sf = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                    Font.SIZE_SMALL);
            Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                    Theme.bodySize);
            int softH = sf.getHeight() + 4;
            // Square-ish cells: tall enough for the glyph plus padding, wide
            // enough for the widest character in the palette so nothing is
            // clipped, and never narrower than it is tall.
            int cellH = f.getHeight() + 8;
            int cellW = f.charWidth('W') + 12;
            if (cellW < cellH) {
                cellW = cellH;
            }
            int pad = 6;
            int n = syms.length;
            int c = (w - 2 * pad) / cellW;
            if (c < 1) {
                c = 1;
            }
            cols = c;
            int rows = (n + c - 1) / c;

            int topMargin = 24;   // leave a strip of backdrop visible above
            int maxPanelH = h - softH - topMargin;
            int visRows = (maxPanelH - 2 * pad) / cellH;
            if (visRows < 1) {
                visRows = 1;
            }
            if (visRows > rows) {
                visRows = rows;
            }

            int gridH = visRows * cellH;
            int panelH = gridH + 2 * pad;
            int panelY = h - softH - panelH;
            if (panelY < 0) {
                panelY = 0;
            }

            // Keep the cursor's row in view. Resolved here, like UiMenu's,
            // because visRows depends on the device font and screen height,
            // neither of which the key handler knows.
            int selRow = sel / c;
            if (selRow < scroll) {
                scroll = selRow;
            }
            if (selRow >= scroll + visRows) {
                scroll = selRow - visRows + 1;
            }
            if (scroll < 0) {
                scroll = 0;
            }

            int arc = 12;
            g.setColor(Theme.bg2);
            g.fillRoundRect(0, panelY, w, panelH + arc, arc, arc);
            g.setColor(Theme.hr);
            g.drawRoundRect(0, panelY, w - 1, panelH + arc, arc, arc);

            boolean overflow = rows > visRows;
            // Centre the grid in whatever width the cells actually use, so the
            // leftover pixels are split evenly instead of piling up on the right.
            int gridW = c * cellW;
            int gx = (w - gridW) / 2;
            if (overflow) {
                gx -= 3;   // shift off the scrollbar gutter
            }
            if (gx < 0) {
                gx = 0;
            }
            int gy = panelY + pad;
            g.setFont(f);
            for (int r = 0; r < visRows; r++) {
                for (int col = 0; col < c; col++) {
                    int idx = (scroll + r) * c + col;
                    if (idx >= n) {
                        break;
                    }
                    int x = gx + col * cellW;
                    int y = gy + r * cellH;
                    if (idx == sel) {
                        g.setColor(Theme.selBg);
                        g.fillRoundRect(x + 1, y + 1, cellW - 2, cellH - 2, 6, 6);
                        g.setColor(Theme.accent);
                        g.drawRoundRect(x + 1, y + 1, cellW - 3, cellH - 3, 6, 6);
                        g.setColor(Theme.selText);
                    } else {
                        g.setColor(Theme.text);
                    }
                    // Drawn straight, not through Ui.clip: clip() runs
                    // Ui.plain() first, which exists to strip exactly the kind
                    // of non-ASCII character this grid is made of.
                    g.drawChar(syms[idx], x + cellW / 2, y + (cellH - f.getHeight()) / 2,
                            Graphics.TOP | Graphics.HCENTER);
                }
            }

            if (overflow) {
                // Row units, not pixels - drawScrollbar only needs the
                // total/shown/offset triple to be self-consistent.
                Ui.drawScrollbar(g, w - 5, gy, gridH, rows, visRows, scroll);
            }

            int by = h - softH;
            g.setColor(Theme.softBar);
            g.fillRect(0, by, w, softH);
            g.setColor(Theme.hr);
            g.drawLine(0, by, w, by);
            g.setFont(sf);
            g.setColor(Theme.dimText);
            g.drawString("Insert", 3, by + 2, Graphics.TOP | Graphics.LEFT);
            g.drawString("Cancel", w - 3, by + 2, Graphics.TOP | Graphics.RIGHT);
        }

        protected void keyPressed(int key) {
            // Raw device codes before game actions, as everywhere else in the
            // app: the E71 centre key does not reliably map to FIRE and the
            // soft keys have no game action at all.
            // The soft key is NOT filtered: a soft key is its own physical
            // key and never arrives as the twin of a centre/Enter press, so
            // running it through the shared filter could only discard a real
            // press - and would arm the 400ms window against the user's next
            // Enter, which is a different code. UiMenu, UiDialog and
            // ImageView split their confirm branches the same way.
            if (key == Ui.LSK) {
                choose();
                return;
            }
            if (key == Ui.RSK) {
                cancel();
                return;
            }
            // Centre key and Enter: one physical press, two key events.
            if (key == Ui.MSK || key == 10 || key == 13) {
                if (UiScreen.confirmAccepted(key)) {
                    choose();
                }
                return;
            }
            if (key == 8 || key == -8) {
                cancel();   // Clear backs out, matching the editor's Clear key
                return;
            }
            int ga;
            try {
                ga = getGameAction(key);
            } catch (Throwable t) {
                ga = 0;
            }
            if (ga == Canvas.UP) {
                move(-cols);
            } else if (ga == Canvas.DOWN) {
                move(cols);
            } else if (ga == Canvas.LEFT) {
                move(-1);
            } else if (ga == Canvas.RIGHT) {
                move(1);
            } else if (ga == Canvas.FIRE) {
                if (UiScreen.confirmAccepted(key)) {
                    choose();
                }
            }
        }

        protected void keyRepeated(int key) {
            // Only movement repeats: holding the confirm key must not insert a
            // string of symbols, and Clear must not close the picker twice.
            int ga;
            try {
                ga = getGameAction(key);
            } catch (Throwable t) {
                ga = 0;
            }
            if (ga == Canvas.UP) {
                move(-cols);
            } else if (ga == Canvas.DOWN) {
                move(cols);
            } else if (ga == Canvas.LEFT) {
                move(-1);
            } else if (ga == Canvas.RIGHT) {
                move(1);
            }
        }

        /**
         * Moves the cursor by d cells, clamped rather than wrapped: the grid is
         * a rectangle whose last row is usually short, so wrapping would land
         * the cursor on nothing. Vertical moves that would leave the grid are
         * dropped whole, which keeps a DOWN press from sliding sideways.
         */
        private void move(int d) {
            int n = syms.length;
            if (n == 0) {
                return;
            }
            int t = sel + d;
            if (t < 0 || t >= n) {
                // A DOWN that leaves the grid because the last row is SHORT
                // still parks on the final cell, which is what the user was
                // reaching for. The row test is what keeps that from also
                // firing on a DOWN pressed while already on the last row,
                // where it would slide the cursor sideways to the end (and UP
                // would not bring it back).
                if (d > 0 && d == cols && sel / cols < (n - 1) / cols) {
                    t = n - 1;
                } else {
                    return;
                }
            }
            sel = t;
            repaint();
        }

        /** Commits the highlighted symbol and returns to the owner's screen. */
        private void choose() {
            int n = syms.length;
            if (m != null) {
                m.show(back);
            }
            if (sel >= 0 && sel < n) {
                char sym = syms[sel];
                // Persist first: the owner may navigate away (or throw) while
                // handling the insert, and a pick that moved a symbol up the
                // grid should survive that.
                bump(sym);
                if (owner != null) {
                    owner.symbolPicked(sym);
                }
            }
        }

        private void cancel() {
            if (m != null) {
                m.show(back);
            }
        }
    }
}
