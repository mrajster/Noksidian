package nok.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import nok.NoksidianMIDlet;

/**
 * Themed popup command menu (replaces the platform Options menu).
 *
 * <p>Renders the back screen's current frame dimmed (via
 * {@link Ui#dimSnapshot}) as the backdrop, then a bottom-anchored panel
 * listing the items. UP/DOWN move the selection (wrapping), FIRE / left-soft
 * choose (then return to the back screen and notify the owner), right-soft
 * cancels back to the caller.</p>
 *
 * <p>Colors are read from {@link Theme} at paint time so a theme switch is
 * honored immediately.</p>
 *
 * <p>Why hand-rolled rather than MIDP Commands: the platform Options menu is
 * drawn by the device in system colors and cannot be themed. This is an
 * ordinary full-screen Canvas instead, so it reads the same raw soft-key codes
 * ({@link Ui#LSK} / {@link Ui#RSK}) as the rest of the app's screens.</p>
 *
 * <p>Lifecycle: one-shot and stateless between opens. Callers construct a new
 * UiMenu on each left-soft press and drop it once it returns to the back
 * screen, so the selection always starts at row 0 and there is no state to
 * reset. Everything here runs on the single LCDUI event/paint thread, which is
 * why nothing is synchronized.</p>
 */
public final class UiMenu {

    /** Owning MIDlet; used only to swap the current Displayable. */
    private final NoksidianMIDlet m;
    private final MenuCanvas canvas;

    /**
     * Builds a menu over back, which serves double duty as the screen dimmed
     * for the backdrop and the screen returned to on either choose or cancel.
     * The strings in items are both drawn and handed back verbatim to
     * {@link UiMenuOwner#menuSelect}, so owners can compare against the very
     * constants they passed in. A null items array yields an empty menu, and a
     * null owner suppresses the callback; either way both soft keys still
     * return to the back screen.
     */
    public UiMenu(NoksidianMIDlet m, Displayable back, String[] items,
            UiMenuOwner owner) {
        this.m = m;
        this.canvas = new MenuCanvas(back, items, owner);
    }

    /** Make the menu the current screen. */
    public void show() {
        if (m != null) {
            // Build the dimmed backdrop while the back screen is STILL current,
            // so the switch to the menu canvas blits a ready image instead of
            // showing the platform's ~1s white Canvas-clear on the E71.
            canvas.prebuild();
            m.show(canvas);
        }
    }

    // ------------------------------------------------------------------

    /**
     * The screen itself. Inner (non-static) so it can reach the enclosing
     * MIDlet reference for the show() calls that hand control back.
     */
    private final class MenuCanvas extends Canvas {

        /**
         * Screen to return to and to render as the dimmed backdrop. Typed as
         * Displayable rather than UiScreen because Viewer extends Canvas
         * directly; {@link Ui#dimSnapshot} dispatches on UiScreen, Viewer and
         * ImageView and returns null for anything else (a menu over a menu),
         * which paint() handles with a solid scrim.
         */
        private final Displayable back;
        private final String[] items;
        private final UiMenuOwner owner;
        private int sel;
        private int scroll;   // first visible row index
        // Keyed on canvas size and rebuilt only when that changes, so ordinary
        // repaints (every selection move) reuse it instead of re-rendering the
        // whole back screen and halving every pixel again.
        private Image dimBack; // cached dimmed snapshot of the back screen

        MenuCanvas(Displayable back, String[] items, UiMenuOwner owner) {
            this.back = back;
            // Normalized to a zero-length array so paint and the key handlers
            // can read items.length unconditionally, with no null checks.
            this.items = (items != null) ? items : new String[0];
            this.owner = owner;
            this.sel = 0;
            this.scroll = 0;
            // Full screen so the panel can own the bottom soft-key strip and
            // the backdrop lines up pixel-for-pixel with the back screen's own
            // frame. Guarded like every other screen here: a device that throws
            // from setFullScreenMode still gets a usable (letterboxed) menu
            // rather than an exception escaping the constructor.
            try {
                setFullScreenMode(true);
            } catch (Throwable t) {
            }
        }

        /** Builds the dimmed backdrop while the back screen is still current. */
        void prebuild() {
            int w = getWidth();
            int h = getHeight();
            // Prefer the back screen's dimensions: this canvas has never been
            // shown, so its own getWidth/getHeight can still report the
            // pre-fullscreen size and would build a snapshot of the wrong
            // shape, which paint() would then throw away and rebuild - exactly
            // the stall prebuild exists to avoid. Every back screen the app
            // actually passes is a Canvas.
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

            // Render the back screen's frame dimmed behind the panel so the
            // menu floats over dimmed context (CONTRACTS-UI.md). The snapshot
            // is built once and reused across repaints; if it cannot be made
            // we fall back to a solid theme fill.
            if (dimBack == null || dimBack.getWidth() != w
                    || dimBack.getHeight() != h) {
                dimBack = Ui.dimSnapshot(back, w, h);
            }
            if (dimBack != null) {
                g.drawImage(dimBack, 0, 0, Graphics.TOP | Graphics.LEFT);
            } else {
                // Snapshot unavailable: a dim scrim (same per-channel halving as
                // dimSnapshot), never a pure-white Theme.bg fill in the light theme.
                g.setColor((Theme.bg >> 1) & 0x7F7F7F);
                g.fillRect(0, 0, w, h);
            }

            // Font.getFont hands back a shared, immutable device instance, so
            // looking it up every paint allocates nothing.
            Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                    Font.SIZE_SMALL);
            int softH = f.getHeight() + 4;
            // +6 = the 3px inset drawString uses below, mirrored above.
            int rowH = f.getHeight() + 6;
            int topPad = 8;    // clears the rounded top corners
            int botPad = 6;

            // The panel shrinks to fit its items but is capped so a strip of
            // dimmed backdrop always shows above it: that gap is the only cue
            // that this is a popup over the previous screen rather than a
            // whole new screen.
            int n = items.length;
            int topMargin = 24;   // reveal some backdrop above the panel
            int maxPanelH = h - softH - topMargin;
            if (maxPanelH < rowH + topPad + botPad) {
                maxPanelH = rowH + topPad + botPad;
            }
            // Floor at one row so an absurdly short screen still draws
            // something, then shrink to the item count so a 3-item menu is a
            // 3-row panel and not a half-screen slab. That second clamp already
            // yields 0 when items is empty; the final clamp back to 0 is
            // defensive and never fires.
            int visRows = (maxPanelH - topPad - botPad) / rowH;
            if (visRows < 1) {
                visRows = 1;
            }
            if (visRows > n) {
                visRows = n;
            }
            if (visRows < 1) {
                visRows = 0;
            }

            int listH = visRows * rowH;
            int panelH = topPad + listH + botPad;
            int panelY = h - softH - panelH;
            if (panelY < 0) {
                panelY = 0;
            }

            // keep selection visible
            // Scrolling is resolved here rather than in move() because visRows
            // is not known until layout: it depends on the device font metrics
            // and the current screen height, neither of which the key handler
            // has. move() therefore only touches sel and lets paint catch up.
            if (sel < scroll) {
                scroll = sel;
            }
            if (visRows > 0 && sel >= scroll + visRows) {
                scroll = sel - visRows + 1;
            }
            if (scroll < 0) {
                scroll = 0;
            }

            // Modern bottom-sheet panel: bg2 card with gently rounded top
            // corners and a 1px hr edge. The fill extends past the bottom
            // (under the soft-key bar drawn later) so only the top corners
            // round against the backdrop.
            int arc = 12;
            g.setColor(Theme.bg2);
            g.fillRoundRect(0, panelY, w, panelH + arc, arc, arc);
            g.setColor(Theme.hr);
            // w - 1 because the stroke of drawRoundRect lands on the far edge
            // itself, unlike fillRoundRect whose width is exclusive; passing w
            // would push the right hairline one pixel off screen.
            g.drawRoundRect(0, panelY, w - 1, panelH + arc, arc, arc);

            boolean overflow = n > visRows;
            int labelX = 10;
            // Reserve the scrollbar gutter only when it will actually be drawn,
            // so labels get clipped with ".." instead of sliding under the bar.
            int textW = w - labelX - 6 - (overflow ? 6 : 0);

            int y = panelY + topPad;
            g.setFont(f);
            for (int i = 0; i < visRows; i++) {
                int idx = scroll + i;
                // Belt and braces: the clamps above already keep scroll +
                // visRows within n, but a stale scroll from a previous, taller
                // layout would otherwise index past the end.
                if (idx >= n) {
                    break;
                }
                String label = items[idx];
                if (label == null) {
                    label = "";
                }
                int fg;
                if (idx == sel) {
                    // selBg tint fill + 3px accent bar down the left edge
                    g.setColor(Theme.selBg);
                    g.fillRect(0, y, w, rowH);
                    g.setColor(Theme.accent);
                    g.fillRect(0, y, 3, rowH);
                    fg = Theme.selText;
                } else {
                    fg = Theme.text;
                }
                g.setColor(fg);
                g.drawString(Ui.clip(label, f, textW), labelX, y + 3,
                        Graphics.TOP | Graphics.LEFT);
                y += rowH;
            }

            if (overflow) {
                // Row units, not pixels: drawScrollbar only requires the
                // total/shown/offset triple to be self-consistent.
                Ui.drawScrollbar(g, w - 5, panelY + topPad, listH, n,
                        visRows, scroll);
                // The scrollbar alone is nearly invisible on the dark panel;
                // add clear dimText chevrons in the top/bottom padding strips
                // (no row overlap) to signal there are more items to scroll to.
                g.setColor(Theme.dimText);
                int chX = w / 2;
                if (scroll > 0) {
                    int cyT = panelY + topPad / 2;
                    g.fillTriangle(chX, cyT - 2, chX - 4, cyT + 2,
                            chX + 4, cyT + 2);
                }
                if (scroll + visRows < n) {
                    int cyB = panelY + topPad + listH + botPad / 2;
                    g.fillTriangle(chX - 4, cyB - 2, chX + 4, cyB - 2,
                            chX, cyB + 2);
                }
            }

            // soft-key bar: softBar fill, 1px hr hairline, accent primary
            int by = h - softH;
            g.setColor(Theme.softBar);
            g.fillRect(0, by, w, softH);
            g.setColor(Theme.hr);
            g.drawLine(0, by, w, by);
            g.setFont(f);
            // Soft-key captions are dimText per CONTRACTS-UI.md (both sides).
            g.setColor(Theme.dimText);
            g.drawString("Select", 3, by + 2, Graphics.TOP | Graphics.LEFT);
            g.drawString("Cancel", w - 3, by + 2, Graphics.TOP | Graphics.RIGHT);
        }

        protected void keyPressed(int key) {
            // Raw device codes are matched before game actions: the E71 centre
            // key (MSK) does not reliably map to FIRE, and soft keys have no
            // game action at all. Returning here also stops a centre press that
            // does map to FIRE from choosing twice.
            if (key == Ui.LSK || key == Ui.MSK) {
                choose();
                return;
            }
            if (key == Ui.RSK) {
                cancel();
                return;
            }
            // getGameAction is permitted to throw for codes the device does not
            // consider valid key codes, which some handsets do for the negative
            // device keys. 0 is no game action, so it falls through to a no-op.
            int ga;
            try {
                ga = getGameAction(key);
            } catch (Throwable t) {
                ga = 0;
            }
            if (ga == Canvas.UP) {
                move(-1);
            } else if (ga == Canvas.DOWN) {
                move(1);
            } else if (ga == Canvas.FIRE) {
                choose();
            }
        }

        /**
         * Moves the selection by d rows, wrapping past either end so holding
         * DOWN cycles rather than sticking on the last item. Only sel changes;
         * the next paint scrolls the window to follow it.
         */
        private void move(int d) {
            int n = items.length;
            if (n == 0) {
                return;
            }
            sel += d;
            if (sel < 0) {
                sel = n - 1;
            }
            if (sel >= n) {
                sel = 0;
            }
            repaint();
        }

        /**
         * Commits the highlighted row. The range test also covers the empty
         * menu, where sel is 0 but there is nothing at index 0, so an empty
         * menu just dismisses. The back screen is restored before the callback
         * fires, so an owner is free to navigate somewhere else instead.
         */
        private void choose() {
            int n = items.length;
            // return to the back screen first; owner may re-navigate
            if (m != null) {
                m.show(back);
            }
            if (owner != null && sel >= 0 && sel < n) {
                owner.menuSelect(items[sel], sel);
            }
        }

        /**
         * Right-soft dismiss. Deliberately silent: with no owner callback, a
         * cancelled menu is indistinguishable to the owner from one that was
         * never opened, so owners need no cancel branch.
         */
        private void cancel() {
            if (m != null) {
                m.show(back);
            }
        }
    }
}
