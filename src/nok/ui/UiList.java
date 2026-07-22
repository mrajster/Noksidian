package nok.ui;

import java.util.Vector;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import nok.NoksidianMIDlet;

/**
 * Themed scrollable, selectable list - the Canvas replacement for lcdui List.
 *
 * Each row may carry a MARK_* glyph drawn left of the label. The selection
 * wraps around, stays visible via pixel scrolling, and a Ui scrollbar appears
 * when the rows overflow. UP/DOWN move the selection; FIRE / enter / right-soft
 * activate it (or, when the right caption is "Back", call listBack); left-soft
 * opens the setMenu popup (a UiMenu) whose choice is forwarded to listMenu.
 *
 * MIDP's own List is a high-level Screen: the platform owns its look, so it
 * ignores Theme entirely and paints S60 chrome that clashes with the rest of
 * the app. Everything here is therefore hand-painted on a Canvas. Only the
 * throwaway pickers use it (search results in NoksidianMIDlet, the "Links"
 * list in Viewer). The vault browser in Library does not: it also needs a
 * sync ticker band above the rows and swaps its whole model from a worker
 * thread, so it extends UiScreen and re-implements the row painting with its
 * own snapshot rules - the parallel markAt/drawGlyph pair over there is
 * deliberate, not drift (its glyph shapes differ too). Everything in this
 * class runs on the LCDUI thread.
 *
 * Design notes worth knowing before touching this file:
 * - The label/mark Vectors are aliased, never copied. Callers build them once
 *   and hand them over; on a ~2MB heap a defensive copy of a few hundred file
 *   names is a real cost. Do not mutate them behind the list's back.
 * - Scroll position is a pixel offset, not a row index (UiMenu uses a row
 *   index), because rows are laid out from the live Theme font height.
 * - Nothing is precomputed: fonts, row height and colors are all read inside
 *   paintBody, so a Theme or font-size change lands on the next repaint with
 *   no invalidation step.
 */
public final class UiList extends UiScreen implements UiMenuOwner {

    // Row glyph ids. Stored per row as a boxed Integer in the marks Vector -
    // CLDC 1.1 has no generics and no autoboxing, hence the explicit
    // new Integer(...) at the call sites. The numbers are fixed by
    // CONTRACTS-UI.md and shared with Library's own painter; do not renumber.
    public static final int MARK_NONE = 0;
    public static final int MARK_FOLDER = 1;
    public static final int MARK_NOTE = 2;
    public static final int MARK_IMAGE = 3;
    public static final int MARK_UP = 4;

    /** Host screen notified of activation/menu/back; may be null (no-op list). */
    private final UiListOwner owner;
    private Vector labels;   // String labels
    private Vector marks;    // Integer MARK_* per row (may be null)
    /** Left-soft popup contents; null or empty leaves the left soft key inert. */
    private String[] menuItems;

    /** Selected row, always a valid index while labels is non-empty. */
    private int sel;
    private int scroll;      // pixel offset of the top of the viewport

    public UiList(NoksidianMIDlet m, String title, UiListOwner owner) {
        super(m, title);
        this.owner = owner;
        // Empty (not null) so a paint that arrives before setItems - the
        // platform can show and paint the Canvas as soon as it is current -
        // draws an empty list instead of throwing on the LCDUI thread.
        labels = new Vector();
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    /** Labels only; also drops any marks a previous setItems installed. */
    public void setItems(Vector items) {
        setItems(items, null);
    }

    /**
     * Replaces the model. marks is index-parallel to labels and may be null,
     * shorter, or hold non-Integer junk - markAt tolerates all three, so a
     * caller that only knows the type of some rows can leave the rest out.
     * The selection is clamped but deliberately not reset, so a caller that
     * refreshes the same list in place keeps its cursor. scroll is left alone
     * here and re-clamped by ensureVisible at paint time, when the viewport
     * height is actually known.
     */
    public void setItems(Vector labels, Vector marks) {
        this.labels = (labels != null) ? labels : new Vector();
        this.marks = marks;
        int n = this.labels.size();
        if (sel >= n) {
            sel = (n > 0) ? n - 1 : 0;
        }
        if (sel < 0) {
            sel = 0;
        }
        repaint();
    }

    public int selectedIndex() {
        return sel;
    }

    /** Moves the cursor, clamping instead of rejecting out-of-range values. */
    public void setSelected(int i) {
        int n = labels.size();
        if (i < 0) {
            i = 0;
        }
        if (i >= n) {
            i = (n > 0) ? n - 1 : 0;
        }
        sel = i;
        repaint();
    }

    /**
     * Sets the soft-key captions. The right caption is not cosmetic: "Back"
     * switches the right key from activate to owner.listBack (see onRightSoft).
     */
    public void setCommands(String left, String right) {
        leftLabel = left;
        rightLabel = right;
        repaint();
    }

    /**
     * Installs the left-soft popup items. Only labels a caption when the left
     * key is still unlabelled, so an explicit setCommands wins whichever order
     * the two are called in.
     */
    public void setMenu(String[] menuItems) {
        this.menuItems = menuItems;
        if (leftLabel == null && menuItems != null && menuItems.length > 0) {
            leftLabel = "Menu";
        }
        repaint();
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    /**
     * Paints the visible rows. Row geometry is derived from the current Theme
     * font every frame rather than cached, so changing the reading size in
     * Settings needs no invalidation hook here. Note this is not a pure
     * painter: it mutates sel and scroll through ensureVisible, so it is safe
     * only because everything on this screen runs on the LCDUI thread.
     */
    protected void paintBody(Graphics g, int cx, int cy, int cw, int ch) {
        Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Theme.bodySize);
        int fh = f.getHeight();
        int rowH = fh + 6;
        int n = labels.size();
        int total = n * rowH;
        boolean overflow = total > ch;
        // Clamping happens here, not in the setters: the viewport height is a
        // paint-time input and the row height depends on the current font.
        ensureVisible(rowH, ch);
        // Reserve the scrollbar gutter only when it will actually be drawn,
        // so short lists get the full width for their labels.
        int listW = overflow ? (cw - 6) : cw;
        int gs = fh - 4;
        if (gs < 8) {
            gs = 8;
        }
        int glyphX = cx + 8;
        int textX = glyphX + gs + 6;
        g.setFont(f);
        for (int i = 0; i < n; i++) {
            int ry = cy + i * rowH - scroll;
            // Cull off-screen rows. The clip would hide them anyway, but on the
            // E71 every drawString/clip test costs real time, and a vault folder
            // can hold hundreds of rows against a viewport of about a dozen.
            if (ry + rowH < cy || ry > cy + ch) {
                continue;
            }
            boolean seld = (i == sel);
            int fg;
            if (seld) {
                // Modern selection: subtle tinted fill + 3px accent left bar.
                g.setColor(Theme.selBg);
                g.fillRect(cx, ry, listW, rowH);
                g.setColor(Theme.accent);
                g.fillRect(cx, ry, 3, rowH);
                fg = Theme.selText;
            } else {
                fg = Theme.text;
            }
            int gy = ry + (rowH - gs) / 2;
            drawGlyph(g, markAt(i), glyphX, gy, gs,
                    seld ? Theme.accent : Theme.dimText);
            g.setColor(fg);
            String lab = (String) labels.elementAt(i);
            // Ui.clip both strips glyphs the E71 font cannot draw (emoji in
            // file names) and truncates to the remaining pixel width with a
            // trailing ".."; lcdui measures but never truncates for you, so
            // this has to be redone for every row on every repaint.
            int ty = ry + (rowH - fh) / 2;
            g.drawString(Ui.clip(lab, f, listW - (textX - cx) - 2), textX,
                    ty, Graphics.TOP | Graphics.LEFT);
        }
        if (overflow) {
            drawScroll(g, cx + cw - 5, cy, ch, total, ch, scroll);
        }
    }

    /** Thin, trackless scrollbar with a rounded Theme.scrollbar thumb. */
    // Deliberately a local variant of Ui.drawScrollbar rather than a call to
    // it: that one fills a solid 3px placeholderBg track and squares off the
    // thumb, which reads as a second column beside full-width list rows. Here
    // the track is reduced to a 1px hr hairline down the middle and the 4px
    // thumb is rounded. Ui.drawScrollbar is unit-agnostic; this one is only
    // ever fed pixels by paintBody (unlike UiMenu, which scrolls by row
    // index).
    private void drawScroll(Graphics g, int x, int y, int h, int total,
            int shown, int off) {
        if (h <= 0 || total <= shown || shown <= 0) {
            return;
        }
        int wdt = 4;
        g.setColor(Theme.hr);
        g.drawLine(x + wdt / 2, y, x + wdt / 2, y + h - 1);
        // Proportional thumb, floored at 12px (Ui.drawScrollbar uses 8) so a
        // few-hundred-row folder still shows a grabbable handle rather than a
        // rounded-off sliver, then re-clamped in case that floor overshot a
        // very short track.
        int thumb = shown * h / total;
        if (thumb < 12) {
            thumb = 12;
        }
        if (thumb > h) {
            thumb = h;
        }
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
        g.fillRoundRect(x, y + ty, wdt, thumb, wdt, wdt);
    }

    /**
     * Scrolls the minimum distance that brings the selected row fully into the
     * viewport, then clamps scroll into [0, contentHeight - viewH]. Minimum
     * movement is what makes UP/DOWN feel like a cursor walking a stationary
     * page instead of the page jumping to re-center on every key. It also
     * re-clamps sel: the setters clamp too, but this is the last check before
     * the paint loop indexes labels, and it is the only one that runs after
     * the row height and viewport are known.
     */
    private void ensureVisible(int rowH, int viewH) {
        int n = labels.size();
        if (n == 0) {
            scroll = 0;
            sel = 0;
            return;
        }
        if (sel < 0) {
            sel = 0;
        }
        if (sel >= n) {
            sel = n - 1;
        }
        int selTop = sel * rowH;
        int selBot = selTop + rowH;
        if (selTop < scroll) {
            scroll = selTop;
        }
        if (selBot > scroll + viewH) {
            scroll = selBot - viewH;
        }
        int max = n * rowH - viewH;
        if (max < 0) {
            max = 0;
        }
        if (scroll > max) {
            scroll = max;
        }
        if (scroll < 0) {
            scroll = 0;
        }
    }

    /**
     * Mark for row i, defaulting to MARK_NONE. Tolerates a null, short or
     * mis-typed marks Vector so a caller that only knows the type of some rows
     * can leave the rest out: a missing glyph is a better failure than an
     * exception thrown out of the paint loop.
     */
    private int markAt(int i) {
        if (marks == null || i >= marks.size()) {
            return MARK_NONE;
        }
        Object o = marks.elementAt(i);
        return (o instanceof Integer) ? ((Integer) o).intValue() : MARK_NONE;
    }

    /**
     * Minimal line-weight row icons, all drawn in a single color (dimText
     * normally, accent when the row is selected).
     *
     * Drawn with primitives rather than shipped as PNGs: bitmap icons would
     * need one asset per size and per theme, cost JAR space and live in the
     * ~2MB heap, whereas these follow the reading font (gs is derived from it)
     * and recolor for free. gx/gy is the top-left of a gs-by-gs box; every
     * offset below is hand-tuned for the small sizes that box actually takes
     * (roughly 8 to 16 px), so the shapes will not survive being rescaled.
     * Note this paints the folder in the passed color like every other glyph;
     * Library's copy special-cases it to Theme.wikilink per CONTRACTS-UI.md.
     */
    private void drawGlyph(Graphics g, int mark, int gx, int gy, int gs,
            int col) {
        if (mark == MARK_NONE) {
            return;
        }
        g.setColor(col);
        if (mark == MARK_FOLDER) {
            int bt = gy + 3;                       // folder body top
            g.drawRect(gx, bt, gs - 1, (gy + gs - 1) - bt);
            g.drawLine(gx + 1, bt, gx + 2, gy + 1); // tab left slope
            g.drawLine(gx + 2, gy + 1, gx + gs / 2, gy + 1);
            g.drawLine(gx + gs / 2, gy + 1, gx + gs / 2 + 1, bt);
        } else if (mark == MARK_NOTE) {
            int rw = gs - 3;                        // doc width
            int fold = 3;                           // folded corner
            g.drawLine(gx, gy, gx + rw - fold, gy);
            g.drawLine(gx + rw - fold, gy, gx + rw, gy + fold);
            g.drawLine(gx + rw, gy + fold, gx + rw, gy + gs - 1);
            g.drawLine(gx + rw, gy + gs - 1, gx, gy + gs - 1);
            g.drawLine(gx, gy + gs - 1, gx, gy);
            g.drawLine(gx + rw - fold, gy + fold, gx + rw, gy + fold);
            g.drawLine(gx + 2, gy + gs / 2, gx + rw - 2, gy + gs / 2);
            g.drawLine(gx + 2, gy + gs - 4, gx + rw - 3, gy + gs - 4);
        } else if (mark == MARK_IMAGE) {
            g.drawRect(gx, gy, gs - 1, gs - 1);
            g.fillArc(gx + 2, gy + 2, 3, 3, 0, 360); // sun
            g.drawLine(gx + 1, gy + gs - 2, gx + gs / 2 - 1, gy + gs / 2);
            g.drawLine(gx + gs / 2 - 1, gy + gs / 2, gx + gs - 2, gy + gs - 2);
        } else if (mark == MARK_UP) {
            int mx = gx + gs / 2;                   // up-arrow
            g.drawLine(mx, gy + 1, gx + 1, gy + gs / 2);
            g.drawLine(mx, gy + 1, gx + gs - 2, gy + gs / 2);
            g.drawLine(mx, gy + 1, mx, gy + gs - 1);
        }
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    protected void onUp() {
        move(-1);
    }

    protected void onDown() {
        move(1);
    }

    /**
     * Moves the selection by d rows, wrapping at both ends. The doubled modulo
     * is the standard fix for Java's remainder keeping the sign of the left
     * operand: a plain (sel - 1) % n goes to -1 at the top of the list.
     * Wrapping matters on a d-pad, where holding UP is the cheapest way to
     * reach the last row of a long folder.
     */
    private void move(int d) {
        int n = labels.size();
        if (n == 0) {
            return;
        }
        sel = ((sel + d) % n + n) % n;
        repaint();
    }

    /**
     * Activates the current row. The bounds test is what makes an empty list
     * inert: sel is pinned to 0 there, so without it every FIRE would report
     * row 0 of nothing.
     */
    protected void onSelect() {
        int n = labels.size();
        if (owner != null && sel >= 0 && sel < n) {
            owner.listSelect(sel);
        }
    }

    /**
     * The right soft key is caption-driven: the literal string "Back" is the
     * whole protocol for turning it into a back key, so callers only have to
     * call setCommands. Any other caption activates the row, which is what the
     * "Open" both current callers pass means. A missing caption activates too,
     * because UiScreen dispatches the raw RSK code whether or not anything is
     * painted over the key.
     */
    protected void onRightSoft() {
        if (rightLabel != null && "Back".equals(rightLabel)) {
            if (owner != null) {
                owner.listBack();
            }
            return;
        }
        onSelect();
    }

    /**
     * Opens the popup menu, if any. A fresh UiMenu per press is cheap and
     * avoids holding a second Canvas plus its dimmed backdrop snapshot alive
     * for the life of the list; passing this as the back screen is what makes
     * cancel return here.
     */
    protected void onLeftSoft() {
        if (menuItems != null && menuItems.length > 0) {
            new UiMenu(m, this, menuItems, this).show();
        }
    }

    // ------------------------------------------------------------------
    // UiMenuOwner
    // ------------------------------------------------------------------

    /**
     * Forwards a popup choice as UiListOwner.listMenu. Only the label travels
     * on, so owners dispatch on the command string and must keep the strings
     * they pass to setMenu distinct. UiMenu has already shown this screen
     * again by the time this runs, so the owner is free to navigate away.
     */
    public void menuSelect(String item, int index) {
        if (owner != null) {
            owner.listMenu(item);
        }
    }
}
