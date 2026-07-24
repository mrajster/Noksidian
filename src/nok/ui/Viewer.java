package nok.ui;

import java.io.IOException;

import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import nok.NoksidianMIDlet;
import nok.core.Emoji;
import nok.core.Md;
import nok.core.MdBlock;
import nok.core.MdSpan;
import nok.core.NoteIndex;
import nok.core.Path;
import nok.core.Utf8;
import nok.img.ImgProbe;

/**
 * Markdown renderer. setNote() parses the note into blocks, a layout pass
 * turns those into positioned draw items (word-wrapped text runs, images,
 * rects, links), and paint() just blits the precomputed items with a scroll
 * offset. Nothing is allocated per frame.
 *
 * <p>J2ME background for readers coming from desktop Java: a Canvas is a
 * raw drawing surface with a paint(Graphics) callback and key callbacks;
 * there is no widget tree, no layout manager and no scroll pane, so every
 * pixel below (word wrap, scrollbars, focus rings, even the bottom soft-key
 * bar) is hand-drawn here. There are no generics and no collections beyond
 * Vector/Hashtable, hence the casts and the parallel int fields.
 *
 * <p>Two coordinate spaces are in play. The layout pass works in CONTENT
 * space: y starts at 0 at the top of the note and grows downward past the
 * bottom of the screen. paint() converts to DEVICE space by translating the
 * Graphics by -scrollX and adding dy = -scrollY to each item's y. The
 * scrollbars and the soft-key bar are drawn after the translate is undone,
 * so they live in absolute device coords and never scroll. Content space is
 * laid out for one specific canvas width, so any width change invalidates
 * the whole layout (the layW/layH guard in sizeChanged).
 *
 * <p>Concurrency: relayout() is entered from setNote (on whatever thread
 * loaded the note - presentNote is reached from the note-reading workers),
 * and from sizeChanged and paint() on the LCDUI event thread, so it is
 * synchronized and never mutates the published items/links/allLinks
 * in place. It builds into the private bItems/bLinks/bAll and swaps the
 * references in one go at the end, so a paint racing a relayout always sees
 * a complete layout - the old one or the new one, never a half-built one.
 * paint() additionally walks itemArr, a plain-array snapshot of items, to
 * avoid a synchronized Vector.elementAt call per item per frame.
 *
 * <p>Text scaling: MIDP offers exactly three font sizes, which is not enough
 * for a readable "larger text" setting on a 320x240 screen. Sizes above the
 * largest native font are therefore synthesized by rasterizing each run at a
 * base font size and integer-upscaling it (Theme.bodyFactor, nearest
 * neighbor, cached). Consequence for anyone editing the layout code: every
 * measurement here is "base-font px * factor", and anything that hands a
 * width budget to a base-font measurement (Ui.clip) must divide by factor
 * first. factor == 1 is the fast path and skips the whole mechanism.
 */
public final class Viewer extends Canvas {

    // All palette colors come from nok.ui.Theme statics, read at
    // layout/paint time so a Settings change applies on next relayout.

    // draw-item kinds
    // Stored in DrawItem.kind; drawItem() branches on it. See DrawItem for why
    // these are ints on one flat class rather than a subclass per primitive.
    private static final int K_TEXT = 0;
    private static final int K_RECT = 1;
    private static final int K_BORDER = 2;
    private static final int K_LINE = 3;
    private static final int K_IMAGE = 4;
    private static final int K_CHECK = 5;
    private static final int K_BULLET = 6;
    /**
     * Inline color emoji glyph. Carries no text/font: it blits a 16x16 region
     * of a strip page (nok.core.Emoji glyph pack) via drawItem's K_EMOJI
     * branch, and only ever exists when the pack loaded (match() returns 0 in
     * no-emoji mode, so flowText emits none). See flowEmoji for its geometry.
     */
    private static final int K_EMOJI = 7;

    // render flags
    // Bitmask stored in DrawItem.flags. R_UNDER/R_STRIKE apply to K_TEXT and
    // R_CHECKED to K_CHECK; R_ROUND asks K_RECT/K_BORDER for rounded corners
    // (code panels, frontmatter panels, callouts, tag pills, placeholders).
    private static final int R_UNDER = 1;
    private static final int R_STRIKE = 2;
    private static final int R_CHECKED = 4;
    private static final int R_ROUND = 8;

    // layout metrics
    // Device px, deliberately NOT multiplied by factor: gutters and padding
    // stay constant as the body font grows, so bigger text buys more text per
    // screen rather than proportionally fatter whitespace.
    private static final int MARGIN = 4;
    /** Right-hand gutter reserved for the vertical scrollbar; excluded from `right`. */
    private static final int SBAR = 6;
    /** Top inset of the first block, reused as the tail padding in contentH. */
    private static final int TOPM = 4;
    /** Vertical space left between two block-level elements. */
    private static final int GAP = 4;
    /** Horizontal step per list nesting level (MdBlock.level). */
    private static final int INDENT = 14;
    /** Inner top/bottom padding of a fenced code panel. */
    private static final int CODEPAD = 4;
    /** Inner padding of a callout box, applied on all four sides. */
    private static final int CALLPAD = 5;
    /**
     * Pixel budget for inline images (~1280x960). The old byte-size gate let a
     * 400KB 5-megapixel JPEG through and OOMed the ~2MB heap on decode; the
     * header probe gates on the real pixel count instead, before any read.
     */
    private static final int PIX_MAX = 1200000;
    /** Vault-relative prefix (no dot-dir) for desktop-generated preview sidecars. */
    private static final String THUMB_DIR = "noksidian/thumbs/";
    /** Height of the self-drawn soft-key bar at the bottom. */
    private static final int SOFTH = 18;
    /**
     * Max decoded emoji strip pages held resident at once. Each page is one
     * 512x16 PNG (~32KB decoded), so 8 caps the pack's paint-time footprint at
     * ~256KB regardless of how many distinct emoji a note uses. LRU-evicted.
     */
    private static final int PAGE_CAP = 8;

    /**
     * Owning MIDlet: the route to file reads (m.readBytes, m.files), the note
     * index (m.index) and screen navigation (m.show, m.openWikilink,
     * m.openImage). The Viewer never talks to the display stack directly.
     */
    private final NoksidianMIDlet m;
    /** Base body font (Theme.bodyBase); refreshed at every relayout. */
    private Font bodyFont;
    /**
     * Integer upscale factor for note text (Theme.bodyFactor). 1 = native
     * (fast path, unchanged draw); >1 = every text run is laid out at
     * baseWidth*factor and painted from a cached nearest-neighbor upscale,
     * so non-native sizes stay pixel-perfect crisp.
     */
    private int factor = 1;

    /** Vault-relative path of the open note; the target of Edit and Info. */
    private String rel = "";
    /** Raw markdown source, retained so Info can report byte size and words. */
    private String text = "";
    /**
     * Parsed block list, or null when no note has been set yet. paint() and
     * keyPressed both treat null as "nothing to show" and bail out early.
     */
    private Vector blocks;

    // published (read by paint / input)
    // Swapped in wholesale at the end of relayout(), never mutated in place.
    /** Positioned draw primitives in content coordinates, in paint order. */
    private Vector items;
    /**
     * Hit boxes for arrow-key focus, one per DRAWN fragment: a link that wraps
     * across three lines contributes three boxes, so the focus ring hugs the
     * text. Indexed by `focus`.
     */
    private Vector links;
    /**
     * One MdSpan per distinct destination (deduped by kind+target), feeding the
     * Menu > Links list. Unlike `links` this is per-target, not per-fragment.
     */
    private Vector allLinks;
    // build targets (written only inside relayout)
    private Vector bItems;
    private Vector bLinks;
    private Vector bAll;

    /**
     * Per-note image cache keyed by the RESOLVED vault-relative path. The value
     * is either a decoded Image or a String placeholder label, so a decode that
     * failed or was refused by the size gate is remembered as a failure and
     * never retried on the next relayout. Cleared by setNote.
     */
    private final Hashtable imgCache;
    /** Scaled text-run images, keyed text|face|style|size|color|bg|factor. */
    private final Hashtable glyphCache;
    /**
     * Decoded emoji strip pages, keyed Integer(pageNo) -> Image, bounded to
     * PAGE_CAP. Unlike imgCache/glyphCache this is note-INDEPENDENT (a page is
     * a slice of the bundled pack, not of any note), so setNote does NOT clear
     * it - the pages a note needs are almost always already resident from the
     * previous note. Touched (LRU) only on the paint thread via emojiPage.
     */
    private final Hashtable pageCache;
    /** LRU order for pageCache: element 0 is least-recently-used. */
    private final Vector pageOrder;

    private int scrollY;
    private int scrollX;      // horizontal pan, for content wider than the screen
    /** Total laid-out height including the TOPM tail; sizes the scrollbar thumb. */
    private int contentH;
    private int contentW;     // widest laid-out row; drives horizontal scroll
    /**
     * Index into `links` of the focused link, or -1 for none. Reset by every
     * relayout because the box list is rebuilt and old indices are meaningless.
     */
    private int focus = -1;
    /**
     * False while the layout is stale. paint() relayouts lazily when it sees
     * this, which is what lets setNote run before the canvas has ever been
     * shown (getWidth() is 0 until then, so laying out early is impossible).
     */
    private boolean laidOut;
    /** Dimensions the current layout was built for (rank-1 relayout guard). */
    private int layW;
    private int layH;
    /** Snapshot of items[] iterated by paint without synchronized elementAt. */
    private DrawItem[] itemArr;

    // flow (line-builder) state, only one flow active at a time
    // The flow is a single-pass, left-to-right pen: fragments are emitted as
    // soon as they are placed, so a line's final height is only known once the
    // line ends. Anything whose size depends on the flowed text (a quote bar, a
    // callout background) must therefore be emitted or inserted after flowEnd.
    /** Left/right bounds of the current flow, in content px. */
    private int flLeft;
    private int flRight;
    /** Pen position: x within the current line, y of the current line's top. */
    private int flX;
    private int flTop;
    /** Tallest fragment placed on the current line; becomes its line height. */
    private int flH;
    /** True once the current line has at least one fragment (an empty line
     * must not be advanced past, and takes no leading space). */
    private boolean flHas;
    // A trailing space that ended the previous span's text, still owed to the
    // NEXT word so a space between "your " and a **bold** span is not lost.
    private boolean flPendingSpace;
    /**
     * Effective background behind the current flow's text runs (-1 = page
     * bg). Set while flowing over a filled block (callout) so factor > 1
     * upscales bake the right color; at factor 1 it is a no-op refill.
     */
    private int flBg = -1;
    /** Cached space width for the last font measured by spaceW(). */
    private Font spFont;
    private int spWpx;

    public Viewer(NoksidianMIDlet m) {
        this.m = m;
        // Viewer extends Canvas directly (it draws its own themed soft-key bar
        // and reads the raw S60 soft keys -6/-7 in keyPressed). Without
        // full-screen mode a real E71 keeps the system soft-key row below the
        // canvas - the app's bar renders above an empty system bar and the
        // physical soft keys go to the (absent) command menu instead of
        // keyPressed, so Menu/Edit are dead. Every UiScreen already does this;
        // the Viewer must too. (MicroEmulator delivered the keys regardless,
        // which is why this only showed up on hardware.)
        try {
            setFullScreenMode(true);
        } catch (Throwable t) {
        }
        bodyFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Theme.bodyBase);
        factor = (Theme.bodyFactor > 0) ? Theme.bodyFactor : 1;
        items = new Vector();
        links = new Vector();
        allLinks = new Vector();
        imgCache = new Hashtable();
        glyphCache = new Hashtable();
        pageCache = new Hashtable();
        pageOrder = new Vector();
    }

    /** Parses + lays out the note, resets scroll, repaints. */
    public void setNote(String rel, String text) {
        this.rel = rel;
        this.text = (text != null) ? text : "";
        // Both caches are strictly per-note: imgCache is keyed by resolved path
        // (another note can resolve the same bare name to a different file) and
        // glyphCache holds rasterized runs of THIS note's text. Holding either
        // across notes is how the ~2MB heap gets eaten.
        imgCache.clear();
        glyphCache.clear();
        blocks = Md.parse(this.text);
        scrollY = 0;
        scrollX = 0;
        focus = -1;
        laidOut = false;
        // getWidth() is 0 until the canvas has been shown at least once, and
        // laying out against width 0 would wrap every word onto its own line.
        // Defer to paint(), which relayouts when it sees laidOut == false.
        if (getWidth() > 0) {
            relayout();
            laidOut = true;
        }
        repaint();
    }

    /**
     * MIDP size notification (first show, rotation, chrome appearing). S60
     * fires it redundantly with unchanged dimensions, so an identical w/h only
     * re-clamps and repaints - a full relayout of a long note is expensive
     * enough to be visible on the E71.
     */
    protected void sizeChanged(int w, int h) {
        if (laidOut && w == layW && h == layH) {
            clampScroll();
            repaint();
            return;
        }
        laidOut = false;
        if (w > 0) {
            relayout();
            laidOut = true;
        }
        clampScroll();
        repaint();
    }

    // ------------------------------------------------------------------
    // Layout pass
    // ------------------------------------------------------------------

    /**
     * Rebuilds the whole layout for the current canvas width. Blocks are walked
     * once, top to bottom, each returning the y where the next block starts.
     *
     * <p>Synchronized and double-buffered: the pass writes only into
     * bItems/bLinks/bAll and publishes them with plain reference assignments at
     * the end, so a concurrent paint sees a consistent layout either way. Font
     * and factor are re-read from Theme here rather than cached at construction
     * so a Settings change takes effect on the next relayout.
     */
    private synchronized void relayout() {
        bodyFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Theme.bodyBase);
        factor = (Theme.bodyFactor > 0) ? Theme.bodyFactor : 1;
        bItems = new Vector();
        bLinks = new Vector();
        bAll = new Vector();
        // Text column stops short of the scrollbar gutter, whether or not the
        // scrollbar ends up being drawn, so wrapping does not change when a
        // note grows past one screen.
        int right = getWidth() - SBAR;
        int y = TOPM;
        if (blocks != null) {
            for (int i = 0; i < blocks.size(); i++) {
                MdBlock b = (MdBlock) blocks.elementAt(i);
                if (b.type == MdBlock.TABLE_ROW) {
                    // Gather the whole run of consecutive rows so columns align
                    // across the table, then lay it out as one grid.
                    Vector rowLines = new Vector();
                    int j = i;
                    while (j < blocks.size()
                            && ((MdBlock) blocks.elementAt(j)).type
                                    == MdBlock.TABLE_ROW) {
                        rowLines.addElement(((MdBlock) blocks.elementAt(j)).text);
                        j++;
                    }
                    y = layoutTableGroup(rowLines, y);
                    i = j - 1;
                } else {
                    y = layoutBlock(b, right, y);
                }
            }
        }
        // Publish. The array snapshot is built before itemArr is swapped so
        // paint never observes a half-filled array.
        items = bItems;
        DrawItem[] a = new DrawItem[bItems.size()];
        bItems.copyInto(a);
        itemArr = a;
        links = bLinks;
        allLinks = bAll;
        // TOPM again at the tail, so the last block gets the same breathing
        // room below it as the first one gets above it.
        contentH = y + TOPM;
        contentW = computeContentWidth();
        focus = -1;
        // Scroll is kept (a relayout after a font change should stay roughly
        // where the reader was) but must be re-clamped against the new height.
        clampScroll();
        layW = getWidth();
        layH = getHeight();
    }

    /**
     * Dispatches one block to its layout routine and returns the y at which the
     * next block starts. Every layout* method follows that same contract.
     */
    private int layoutBlock(MdBlock b, int right, int y) {
        switch (b.type) {
            case MdBlock.HEADING:
                return layoutHeading(b, right, y);
            case MdBlock.BULLET:
                return layoutBullet(b, right, y);
            case MdBlock.NUMBERED:
                return layoutNumbered(b, right, y);
            case MdBlock.TASK:
                return layoutTask(b, right, y);
            case MdBlock.QUOTE:
                return layoutQuote(b, right, y);
            case MdBlock.CODE:
                return layoutCode(b, right, y);
            case MdBlock.HR:
                return layoutHr(b, right, y);
            case MdBlock.IMAGE:
                return layoutImageLine(b, right, y);
            case MdBlock.TABLE_ROW: {
                // Normally relayout() groups table rows and calls
                // layoutTableGroup directly; this handles a stray lone row.
                Vector one = new Vector();
                one.addElement(b.text);
                return layoutTableGroup(one, y);
            }
            case MdBlock.FRONTMATTER:
                return layoutFront(b, right, y);
            case MdBlock.CALLOUT:
                return layoutCallout(b, right, y);
            default:
                return layoutPara(b, right, y);
        }
    }

    private int layoutPara(MdBlock b, int right, int y) {
        flowStart(MARGIN, right, y);
        flowSpans(Md.inline(nl2sp(b.text)), Theme.bodyBase, false, -1);
        return flowEnd() + GAP;
    }

    private int layoutHeading(MdBlock b, int right, int y) {
        // Space goes ABOVE the heading, so it binds visually to the section it
        // introduces rather than to the paragraph it follows.
        y += (b.level <= 2) ? 8 : 5;
        // MIDP exposes only three sizes, so six heading levels collapse onto
        // large/medium/small; bold is what actually separates H5/H6 from body.
        int size = (b.level <= 2) ? Font.SIZE_LARGE
                : ((b.level <= 4) ? Font.SIZE_MEDIUM : Font.SIZE_SMALL);
        // H1 carries a restrained accent tint; deeper headings stay in text.
        int hc = (b.level == 1) ? Theme.accent : -1;
        flowStart(MARGIN, right, y);
        flowSpans(Md.inline(b.text), size, true, hc);
        return flowEnd() + 4;
    }

    private int layoutBullet(MdBlock b, int right, int y) {
        int indent = MARGIN + b.level * INDENT;
        int sh = bodyFont.getHeight() * factor;
        DrawItem dot = new DrawItem(K_BULLET);
        dot.x = indent + 2;
        // Centre the 4px dot on the FIRST line's height, not on the whole
        // (possibly wrapped) item, so it sits beside the first line of text.
        dot.y = y + sh / 2 - 2;
        dot.w = 4;
        dot.h = 4;
        dot.color = Theme.text;
        bItems.addElement(dot);
        flowStart(indent + 10, right, y);
        flowSpans(Md.inline(nl2sp(b.text)), Theme.bodyBase, false, -1);
        return flowEnd() + 2;
    }

    private int layoutNumbered(MdBlock b, int right, int y) {
        int indent = MARGIN + b.level * INDENT;
        String label = (b.num != null) ? b.num : "-";
        // Hanging indent: the label plus one space, so continuation lines align
        // under the text rather than under the number.
        int lw = bodyFont.stringWidth(label + " ") * factor;
        DrawItem t = new DrawItem(K_TEXT);
        t.x = indent;
        t.y = y;
        t.w = bodyFont.stringWidth(label) * factor;
        t.h = bodyFont.getHeight() * factor;
        t.text = label;
        t.font = bodyFont;
        t.color = Theme.text;
        bItems.addElement(t);
        flowStart(indent + lw, right, y);
        flowSpans(Md.inline(nl2sp(b.text)), Theme.bodyBase, false, -1);
        return flowEnd() + 2;
    }

    private int layoutTask(MdBlock b, int right, int y) {
        int indent = MARGIN + b.level * INDENT;
        int box = 12;
        DrawItem cb = new DrawItem(K_CHECK);
        cb.x = indent;
        cb.y = y + 1;
        cb.w = box;
        cb.h = box;
        cb.flags = b.checked ? R_CHECKED : 0;
        cb.color = Theme.dimText;
        bItems.addElement(cb);
        flowStart(indent + box + 4, right, y);
        flowSpans(Md.inline(nl2sp(b.text)), Theme.bodyBase, false, -1);
        int e = flowEnd();
        // The 12px box can outgrow a one-line label, which would let the next
        // block overlap it; floor the row height at the box instead.
        int min = y + box + 2;
        return ((e > min) ? e : min) + 2;
    }

    private int layoutQuote(MdBlock b, int right, int y) {
        int top = y;
        flowStart(MARGIN + 8, right, y);
        flowSpans(Md.inline(nl2sp(b.text)), Theme.bodyBase, false,
                Theme.quoteText);
        int bot = flowEnd();
        // The bar spans the flowed text, so its height is only known now -
        // hence it is emitted after the text instead of before it. An empty
        // quote still gets one line's worth so the bar is never zero-height.
        int barH = (bot - top > 0) ? (bot - top)
                : bodyFont.getHeight() * factor;
        DrawItem bar = new DrawItem(K_RECT);
        bar.x = MARGIN + 2;
        bar.y = top;
        bar.w = 3;
        bar.h = barH;
        bar.color = Theme.quoteBar;
        bItems.addElement(bar);
        return bot + GAP;
    }

    /**
     * Lays out a fenced code block: a rounded filled panel, a border, and one
     * text item per source line. Code lines are never wrapped or clipped -
     * breaking code hurts more than losing it off the right edge - so an
     * over-wide line widens contentW and puts the note into horizontal pan.
     */
    private int layoutCode(MdBlock b, int right, int y) {
        Font cf = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN,
                Theme.bodyBase);
        Vector lines = splitN(b.text);
        int chh = cf.getHeight() * factor;
        int h = lines.size() * chh + 2 * CODEPAD;
        int w = right - MARGIN;
        DrawItem bg = new DrawItem(K_RECT);
        bg.x = MARGIN;
        bg.y = y;
        bg.w = w;
        bg.h = h;
        bg.color = Theme.codeBg;
        bg.flags = R_ROUND;
        bItems.addElement(bg);
        DrawItem bd = new DrawItem(K_BORDER);
        bd.x = MARGIN;
        bd.y = y;
        bd.w = w;
        bd.h = h;
        bd.color = Theme.hr;
        bd.flags = R_ROUND;
        bItems.addElement(bd);
        int ty = y + CODEPAD;
        for (int i = 0; i < lines.size(); i++) {
            String ln = (String) lines.elementAt(i);
            DrawItem t = new DrawItem(K_TEXT);
            t.x = MARGIN + 6;
            t.y = ty;
            t.w = cf.stringWidth(ln) * factor;
            t.h = chh;
            t.text = ln;
            t.font = cf;
            t.color = Theme.codeText;
            // Effective run background: the scaled draw bakes it into the
            // upscaled image; at factor 1 it just refills the same color.
            t.bg = Theme.codeBg;
            bItems.addElement(t);
            ty += chh;
        }
        return y + h + GAP;
    }

    private int layoutHr(MdBlock b, int right, int y) {
        y += 4;
        DrawItem ln = new DrawItem(K_LINE);
        ln.x = MARGIN;
        ln.y = y;
        ln.w = right - MARGIN;
        ln.h = 1;
        ln.color = Theme.hr;
        bItems.addElement(ln);
        return y + 6;
    }

    /**
     * A standalone image line. Repackaged as a synthetic T_IMAGE span so block
     * images and inline images go through exactly one code path.
     */
    private int layoutImageLine(MdBlock b, int right, int y) {
        MdSpan sp = new MdSpan(MdSpan.T_IMAGE,
                (b.extra != null) ? b.extra : "", b.text, 0);
        return layoutImageBlock(MARGIN, right, y, sp);
    }

    /**
     * Lays out a run of Markdown table rows as an aligned grid: cells split on
     * '|', each column sized to its widest cell (emoji stripped, over-long
     * cells clipped to one screen), the header row bold, and a full 1px grid
     * (row rules, column separators, outer border). When the grid is wider than
     * the screen the whole view scrolls horizontally (LEFT/RIGHT). All widths
     * are scaled by {@code factor} so it stays aligned at any font size.
     */
    private int layoutTableGroup(Vector rowLines, int y) {
        Font cf = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Theme.bodyBase);
        Vector rows = new Vector();
        int cols = 0;
        for (int r = 0; r < rowLines.size(); r++) {
            Vector cells = splitCells((String) rowLines.elementAt(r));
            rows.addElement(cells);
            if (cells.size() > cols) {
                cols = cells.size();
            }
        }
        if (cols == 0) {
            return y;
        }
        int pad = 5;
        // Ui.clip measures in base-font px; divide the one-screen cap by factor
        // so the SCALED cell obeys it too (same convention as flowTag).
        int budgetBase = (getWidth() - 2 * MARGIN) / factor;
        if (budgetBase < 8) {
            budgetBase = 8;
        }
        int[] colW = new int[cols];
        // Seeded with the plain body height so an all-empty table still has
        // usable rows; bold/code runs may raise it.
        int maxHBase = cf.getHeight();
        // Pass 1: parse each cell's inline markup into styled runs + measure.
        for (int r = 0; r < rows.size(); r++) {
            Vector cells = (Vector) rows.elementAt(r);
            for (int c = 0; c < cells.size(); c++) {
                Vector runs = cellRuns((String) cells.elementAt(c),
                        r == 0, budgetBase);
                cells.setElementAt(runs, c); // String -> Vector<CellRun>
                int wpx = 0;
                for (int i = 0; i < runs.size(); i++) {
                    CellRun run = (CellRun) runs.elementAt(i);
                    wpx += run.w;
                    int fh = run.font.getHeight();
                    if (fh > maxHBase) {
                        maxHBase = fh;
                    }
                }
                wpx += 2 * pad;
                if (wpx > colW[c]) {
                    colW[c] = wpx;
                }
            }
        }
        int rowH = maxHBase * factor + 2 * pad;
        int x0 = MARGIN;
        int totalW = 0;
        for (int c = 0; c < cols; c++) {
            totalW += colW[c];
        }
        int startY = y;
        // Pass 2: place the pre-measured runs (columns fit by construction).
        for (int r = 0; r < rows.size(); r++) {
            Vector cells = (Vector) rows.elementAt(r);
            int cx = x0;
            for (int c = 0; c < cols; c++) {
                Vector runs = (c < cells.size())
                        ? (Vector) cells.elementAt(c) : null;
                int n = (runs != null) ? runs.size() : 0;
                int rx = cx + pad;
                for (int i = 0; i < n; i++) {
                    CellRun run = (CellRun) runs.elementAt(i);
                    int rh = run.font.getHeight() * factor;
                    DrawItem t = new DrawItem(K_TEXT);
                    t.x = rx;
                    t.y = y + pad;
                    t.w = run.w;
                    t.h = rh;
                    t.text = run.text;
                    t.font = run.font;
                    t.color = run.color;
                    t.bg = run.bg;
                    t.flags = run.flags;
                    bItems.addElement(t);
                    if (run.link != null) {
                        addLinkBox(rx, y + pad, run.w, rh, run.link);
                        addAllLink(run.link);
                    }
                    rx += run.w;
                }
                cx += colW[c];
            }
            // Every row gets a rule underneath, so the last one doubles as the
            // table's bottom border and no separate bottom line is needed.
            addHLine(x0, y + rowH - 1, totalW, Theme.hr); // rule under each row
            y += rowH;
        }
        addHLine(x0, startY, totalW, Theme.hr);            // top border
        int cx = x0;
        // cols + 1 separators: one before each column plus the closing edge.
        for (int c = 0; c <= cols; c++) {                  // column separators
            addVLine(cx, startY, y - startY, Theme.hr);
            if (c < cols) {
                cx += colW[c];
            }
        }
        return y + GAP;
    }

    /**
     * Parses one table cell into styled, single-line, pre-measured runs so a
     * cell shows real bold/italic/code/strike/highlight/links instead of literal
     * markup. Mirrors flowSpan's span->style mapping (kept in sync by hand - the
     * flow machinery wraps downward and can't be reused for the fixed grid);
     * cells never wrap, so one MdSpan = one run, truncated with ".." once the
     * cell reaches budgetBase (base-font px). Never returns null.
     */
    private Vector cellRuns(String raw, boolean header, int budgetBase) {
        Vector out = new Vector();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        Vector spans = Md.inline(raw);
        int used = 0; // base px consumed
        for (int i = 0; i < spans.size() && used < budgetBase; i++) {
            MdSpan sp = (MdSpan) spans.elementAt(i);
            int k = sp.kind;
            String disp = sp.text;
            MdSpan link = null;
            int color;
            int bg = -1;
            int rflags = 0;
            if (k == MdSpan.T_TAG) {
                color = Theme.tagText;
                bg = Theme.tagBg;
                link = sp;
            } else if (k == MdSpan.T_IMAGE) {
                disp = "[img: " + sp.text + "]";
                color = Theme.link;
                rflags |= R_UNDER;
                link = isHttp(sp.target)
                        ? new MdSpan(MdSpan.T_LINK, disp, sp.target, 0) : sp;
            } else if (k == MdSpan.T_LINK) {
                color = Theme.link;
                rflags |= R_UNDER;
                link = sp;
            } else if (k == MdSpan.T_WIKILINK) {
                color = Theme.wikilink;
                rflags |= R_UNDER;
                link = sp;
            } else {
                color = Theme.text;
            }
            if ((sp.style & MdSpan.B_STRIKE) != 0) {
                rflags |= R_STRIKE;
            }
            if ((sp.style & MdSpan.B_HIGHLIGHT) != 0) {
                bg = Theme.highlightBg;
                if (k == MdSpan.T_TEXT) {
                    color = Theme.highlightText;
                }
            } else if ((sp.style & MdSpan.B_CODE) != 0) {
                bg = Theme.codeBg;
                if (k == MdSpan.T_TEXT) {
                    color = Theme.codeText;
                }
            }
            Font f = fontFor(sp.style, Theme.bodyBase, header);
            String txt = Ui.plain(disp); // strip emoji, as the rest of the UI
            if (txt.length() == 0) {
                continue;
            }
            int bw = f.stringWidth(txt);
            if (used + bw > budgetBase) { // clip the run that overflows the cell
                txt = Ui.clip(txt, f, budgetBase - used);
                if (txt.length() == 0) {
                    break; // not even ".." fits
                }
                bw = f.stringWidth(txt);
            }
            CellRun run = new CellRun();
            run.text = txt;
            run.font = f;
            run.color = color;
            run.bg = bg;
            run.flags = rflags;
            run.link = link;
            // Two unit systems on purpose: run.w is device px (ready to place),
            // `used` stays in base px to compare against budgetBase.
            run.w = bw * factor;
            out.addElement(run);
            used += bw;
        }
        return out;
    }

    /** Splits a "| a | b |" row into trimmed cells (outer pipes dropped). */
    private static Vector splitCells(String line) {
        Vector out = new Vector();
        String s = (line == null) ? "" : line.trim();
        int start = 0;
        int end = s.length();
        if (start < end && s.charAt(start) == '|') {
            start++;
        }
        if (end > start && s.charAt(end - 1) == '|') {
            end--;
        }
        StringBuffer cur = new StringBuffer();
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < end && s.charAt(i + 1) == '|') {
                cur.append('|'); // escaped pipe -> literal (Obsidian alias syntax)
                i++;
            } else if (c == '|') {
                out.addElement(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.addElement(cur.toString().trim());
        return out;
    }

    private void addHLine(int x, int y, int w, int color) {
        DrawItem d = new DrawItem(K_LINE);
        d.x = x;
        d.y = y;
        d.w = w;
        d.h = 1;
        d.color = color;
        bItems.addElement(d);
    }

    private void addVLine(int x, int y, int h, int color) {
        DrawItem d = new DrawItem(K_RECT); // a 1px-wide filled rect = v-line
        d.x = x;
        d.y = y;
        d.w = 1;
        d.h = h;
        d.color = color;
        bItems.addElement(d);
    }

    /** Widest item right-edge, so horizontal scroll only engages on overflow. */
    private int computeContentWidth() {
        int screen = getWidth();
        int max = screen;
        DrawItem[] it = itemArr;
        int n = (it != null) ? it.length : 0;
        for (int i = 0; i < n; i++) {
            DrawItem d = it[i];
            // Images are clipped to the viewport anyway; an oversized image
            // (e.g. the fitWidth OOM fallback that returns the raw frame) must
            // not drive horizontal scroll, or it would flip a note with no wide
            // table into pan mode and disable arrow-key link navigation.
            if (d.kind == K_IMAGE) {
                continue;
            }
            int r = d.x + d.w;
            if (r > max) {
                max = r;
            }
        }
        // Only exceed the screen width on genuine overflow; otherwise return the
        // screen width exactly so hScrollable() stays false and normal notes
        // keep arrow-key link focus (never engaging horizontal pan).
        return (max > screen) ? max + MARGIN : screen;
    }

    /**
     * YAML frontmatter, shown as a dimmed collapsed panel rather than parsed:
     * the properties are metadata, so they are rendered verbatim and clipped to
     * one line each instead of competing with the note body.
     */
    private int layoutFront(MdBlock b, int right, int y) {
        Vector lines = splitN(b.text);
        int hh = bodyFont.getHeight() * factor;
        // +1 row for the "--- properties ---" caption, +8 for panel padding.
        int h = (lines.size() + 1) * hh + 8;
        int w = right - MARGIN;
        DrawItem bg = new DrawItem(K_RECT);
        bg.x = MARGIN;
        bg.y = y;
        bg.w = w;
        bg.h = h;
        bg.color = Theme.placeholderBg;
        bg.flags = R_ROUND;
        bItems.addElement(bg);
        DrawItem bd = new DrawItem(K_BORDER);
        bd.x = MARGIN;
        bd.y = y;
        bd.w = w;
        bd.h = h;
        bd.color = Theme.hr;
        bd.flags = R_ROUND;
        bItems.addElement(bd);
        int ty = y + 4;
        addPlainText("--- properties ---", MARGIN + 4, ty, Theme.dimText);
        ty += hh;
        // Rows must stay inside the panel (left inset 6, right inset 6) and out
        // of the scrollbar gutter that `right` already excludes. Ui.clip works
        // in base-font units, so divide the screen budget by the scale factor.
        int rowW = (w - 12) / factor;
        for (int i = 0; i < lines.size(); i++) {
            addPlainText(Ui.clip((String) lines.elementAt(i), bodyFont, rowW),
                    MARGIN + 6, ty, Theme.dimText);
            ty += hh;
        }
        return y + h + GAP;
    }

    /**
     * Obsidian callout ("> [!note] Title" plus body) as a tinted rounded box
     * with a bold coloured title line. The box height depends on the flowed
     * text, so the background rect is built last and INSERTED at the index the
     * block started at - painting is index-ordered, and a rect appended after
     * the text would cover it.
     */
    private int layoutCallout(MdBlock b, int right, int y) {
        int startIdx = bItems.size();
        int top = y;
        int inner = y + CALLPAD;
        String type = (b.extra != null && b.extra.length() > 0) ? b.extra : "note";
        // Md packs the callout into one block: b.extra is the "[!type]" word
        // and b.text is the title line followed by the body lines.
        String first = firstLine(b.text);
        String title = capitalize(type) + (first.length() > 0 ? ": " + first : "");
        // Tell the flow what colour it is sitting on, so factor > 1 rasterizes
        // runs against the callout tint instead of the page background.
        flBg = Theme.calloutBg;
        flowStart(MARGIN + CALLPAD, right - CALLPAD, inner);
        flowSpans(Md.inline(title), Theme.bodyBase, true, Theme.calloutTitle);
        inner = flowEnd() + 2;
        String body = restLines(b.text);
        if (body.length() > 0) {
            flowStart(MARGIN + CALLPAD, right - CALLPAD, inner);
            flowSpans(Md.inline(nl2sp(body)), Theme.bodyBase, false,
                    Theme.text);
            inner = flowEnd();
        }
        flBg = -1;
        int bot = inner + CALLPAD;
        DrawItem bg = new DrawItem(K_RECT);
        bg.x = MARGIN;
        bg.y = top;
        bg.w = right - MARGIN;
        bg.h = bot - top;
        bg.color = Theme.calloutBg;
        bg.flags = R_ROUND;
        bItems.insertElementAt(bg, startIdx);
        return bot + GAP;
    }

    /** Plain run over a placeholder/frontmatter panel (placeholderBg). */
    private void addPlainText(String s, int x, int y, int color) {
        DrawItem t = new DrawItem(K_TEXT);
        t.x = x;
        t.y = y;
        t.text = s;
        t.font = bodyFont;
        t.w = bodyFont.stringWidth(s) * factor;
        t.h = bodyFont.getHeight() * factor;
        t.color = color;
        t.bg = Theme.placeholderBg;
        bItems.addElement(t);
    }

    // ------------------------------------------------------------------
    // Inline flow (word wrapping)
    // ------------------------------------------------------------------

    /** Begins a line-builder between left and right, first line topped at top. */
    private void flowStart(int left, int right, int top) {
        flLeft = left;
        flRight = right;
        flX = left;
        flTop = top;
        flH = 0;
        flHas = false;
        flPendingSpace = false;
    }

    /**
     * Closes the flow and returns the y just below its last line, which is what
     * the calling layout method hands back as the next block's y. A trailing
     * empty line contributes nothing, so a flow that placed no text returns its
     * starting y unchanged.
     */
    private int flowEnd() {
        if (flHas) {
            flTop += flH;
        }
        flHas = false;
        flH = 0;
        return flTop;
    }

    /** Wraps to the next line, advancing by the finished line's tallest run. */
    private void flowNewline() {
        if (flHas) {
            flTop += flH;
        }
        flX = flLeft;
        flH = 0;
        flHas = false;
    }

    private void flowSpans(Vector spans, int size, boolean bold, int fc) {
        for (int i = 0; i < spans.size(); i++) {
            flowSpan((MdSpan) spans.elementAt(i), size, bold, fc);
        }
    }

    /**
     * Maps one inline span to a font, colour, background chip and render flags,
     * then hands its text to the wrapper. forceColor >= 0 overrides the plain
     * text colour (headings, quotes, callout bodies) but never a link colour -
     * a link inside a quote must still look like a link.
     */
    private void flowSpan(MdSpan sp, int size, boolean bold, int forceColor) {
        int k = sp.kind;
        if (k == MdSpan.T_TAG) {
            flowTag(sp, size);
            return;
        }
        // A local image is block-level even when it appears mid-sentence: end
        // the current line, lay the image out across the full flow width, and
        // restart the flow beneath it. Remote images stay inline as link text.
        if (k == MdSpan.T_IMAGE && !isHttp(sp.target)) {
            if (flHas) {
                flowNewline();
            }
            flTop = layoutImageBlock(flLeft, flRight, flTop, sp);
            flX = flLeft;
            flH = 0;
            flHas = false;
            flPendingSpace = false;
            return;
        }
        Font f = fontFor(sp.style, size, bold);
        int color;
        int rflags = 0;
        int bg = flBg;
        MdSpan link = null;
        String disp = sp.text;
        if (k == MdSpan.T_IMAGE) {
            disp = "[img: " + sp.text + "]";
            color = Theme.link;
            rflags |= R_UNDER;
            link = new MdSpan(MdSpan.T_LINK, disp, sp.target, 0);
        } else if (k == MdSpan.T_LINK) {
            color = Theme.link;
            rflags |= R_UNDER;
            link = sp;
        } else if (k == MdSpan.T_WIKILINK) {
            color = Theme.wikilink;
            rflags |= R_UNDER;
            link = sp;
        } else {
            color = (forceColor >= 0) ? forceColor : Theme.text;
        }
        if ((sp.style & MdSpan.B_STRIKE) != 0) {
            rflags |= R_STRIKE;
        }
        if ((sp.style & MdSpan.B_HIGHLIGHT) != 0) {
            bg = Theme.highlightBg;
            if (k == MdSpan.T_TEXT) {
                color = Theme.highlightText;
            }
        } else if ((sp.style & MdSpan.B_CODE) != 0) {
            bg = Theme.codeBg;
            if (k == MdSpan.T_TEXT) {
                color = Theme.codeText;
            }
        }
        if (link != null) {
            addAllLink(link);
        }
        flowText(disp, f, color, bg, rflags, link);
    }

    /**
     * Splits a span's text on spaces and feeds it to the wrapper word by word,
     * peeling off any inline emoji as its own token so a color glyph never
     * lands inside a wrapped/measured text word. There is no java.util.regex
     * or String.split on CLDC, hence the manual scan - which also avoids
     * allocating an array per span.
     *
     * <p>Emoji handling is confined here, the one place span text becomes
     * words, so flowWord/flowBroken stay emoji-free and every OTHER renderer
     * (table cells via cellRuns/Ui.plain, fenced code via addPlainText, the
     * Library) keeps stripping exactly as before. An emoji key never spans a
     * space, so scanning within a non-space segment is sufficient: at each
     * position gate cheaply with Emoji.maybe, then Emoji.match - a glyph hit
     * flushes the pending text run as a word and emits flowEmoji; INVISIBLE
     * consumes its units and draws nothing; 0 leaves the char in the text run.
     * In no-emoji mode match() returns 0 for everything, so text flows exactly
     * as the current release with no extra handling.
     */
    private void flowText(String s, Font f, int color, int bg, int rflags,
            MdSpan link) {
        int i = 0;
        int n = s.length();
        boolean firstWord = true;
        while (i < n) {
            // Honour a space owed by the previous span, then any spaces here.
            boolean sp = flPendingSpace;
            flPendingSpace = false;
            while (i < n && s.charAt(i) == ' ') {
                i++;
                sp = true;
            }
            if (i >= n) {
                // Trailing space with no word after it in this span: carry it
                // to the next span so the boundary space survives.
                flPendingSpace = sp;
                break;
            }
            // Walk this non-space segment, splitting out emoji tokens. `st`
            // marks the start of the pending text run; `segLead` stays true
            // until the segment's first piece is placed, so only that piece
            // takes the leading space `sp` - inner pieces are glued to their
            // predecessor with no space between them.
            int st = i;
            boolean segLead = true;
            while (i < n && s.charAt(i) != ' ') {
                if (Emoji.maybe(s, i)) {
                    int mr = Emoji.match(s, i);
                    if (mr != 0) {
                        if (i > st) {
                            // A space between two words of the SAME span carries
                            // that span's chip/underline (B15); the boundary
                            // space before its first word does not.
                            flowWord(s.substring(st, i), f, color, bg, rflags,
                                    link, segLead && sp, !firstWord);
                            firstWord = false;
                            segLead = false;
                        }
                        int glyph = mr & 0xFFFF;
                        if (glyph != Emoji.INVISIBLE) {
                            flowEmoji(glyph, f, segLead && sp, link);
                            firstWord = false;
                            segLead = false;
                        }
                        i += (mr >>> 16);
                        st = i;
                        continue;
                    }
                }
                i++;
            }
            if (i > st) {
                flowWord(s.substring(st, i), f, color, bg, rflags, link,
                        segLead && sp, !firstWord);
                firstWord = false;
            }
        }
    }

    /** factor-scaled width of a single space, cached per Font. */
    private int spaceW(Font f) {
        if (f != spFont) {
            spFont = f;
            spWpx = f.stringWidth(" ");
        }
        return spWpx * factor;
    }

    /**
     * Places one word, wrapping to a new line first if it does not fit.
     * leadingSpace says a space precedes this word; paintLeadSpace says that
     * space belongs INSIDE the span (so it must carry the span's chip
     * background and underline) rather than being an inter-span gap.
     */
    private void flowWord(String word, Font f, int color, int bg, int rflags,
            MdSpan link, boolean leadingSpace, boolean paintLeadSpace) {
        if (word.length() == 0) {
            return;
        }
        int avail = flRight - flLeft;
        int ww = f.stringWidth(word) * factor;
        // A line-leading word takes no space before it, regardless of the source.
        int spW = (leadingSpace && flHas) ? spaceW(f) : 0;
        // Wider than a whole empty line (long URL, unbroken token): wrapping
        // would never help, so break it across lines character by character.
        if (ww > avail) {
            if (spW > 0 && flX + spW < flRight) {
                flX += spW;
            }
            flowBroken(word, f, color, bg, rflags, link);
            return;
        }
        if (flHas && flX + spW + ww > flRight) {
            flowNewline();
            spW = 0;
        }
        if (spW > 0 && paintLeadSpace) {
            // Fold the intra-span space into this fragment so the span's chip
            // background / underline runs continuously across it (B15) instead
            // of leaving an unpainted gap between the words.
            String sw = " " + word;
            flowFrag(sw, f.stringWidth(sw) * factor, f, color, bg, rflags,
                    link);
            return;
        }
        if (spW > 0) {
            flX += spW;
        }
        flowFrag(word, ww, f, color, bg, rflags, link);
    }

    /**
     * Emergency break for a word too wide to fit any line: emits the longest
     * prefix that fits on the current line, wraps, and repeats. Measurement is
     * charsWidth over a char[] rather than repeated substring() so the inner
     * probe loop allocates nothing.
     */
    private void flowBroken(String word, Font f, int color, int bg, int rflags,
            MdSpan link) {
        char[] cs = word.toCharArray();
        int i = 0;
        int n = word.length();
        while (i < n) {
            int fit = 0;
            int wFit = 0;
            while (i + fit + 1 <= n) {
                int w2 = f.charsWidth(cs, i, fit + 1) * factor;
                if (flX + w2 > flRight) {
                    break;
                }
                fit++;
                wFit = w2;
            }
            if (fit == 0) {
                // Nothing fits in what is left of this line: wrap and retry.
                // If the line is already empty, even one character overflows
                // (absurdly narrow column) - emit it anyway, or this loops
                // forever.
                if (flHas) {
                    flowNewline();
                    continue;
                }
                fit = 1;
                wFit = f.charsWidth(cs, i, 1) * factor;
            }
            flowFrag(word.substring(i, i + fit), wFit, f, color, bg,
                    rflags, link);
            i += fit;
            if (i < n) {
                flowNewline();
            }
        }
    }

    /**
     * Emits one already-measured text fragment at the pen and advances it. The
     * single place where WRAPPED text becomes a DrawItem (flowTag builds its
     * pill itself), so it is also where a link's per-fragment hit box is
     * registered.
     */
    private void flowFrag(String s, int w, Font f, int color, int bg,
            int rflags, MdSpan link) {
        int h = f.getHeight() * factor;
        DrawItem it = new DrawItem(K_TEXT);
        it.x = flX;
        it.y = flTop;
        it.w = w;
        it.h = h;
        it.text = s;
        it.font = f;
        it.color = color;
        it.bg = bg;
        it.flags = rflags;
        bItems.addElement(it);
        if (link != null) {
            addLinkBox(flX, flTop, w, h, link);
        }
        flX += w;
        if (h > flH) {
            flH = h;
        }
        flHas = true;
    }

    /**
     * Places one inline emoji as an unbreakable K_EMOJI token. The box is
     * 16*factor tall and (16*factor + 2) wide - 1 device px of breathing room
     * on each side, so the glyph paints at x+1 - and wraps to a new line
     * exactly like a word that does not fit (leading space width if a space
     * preceded it, flowNewline when it would overrun flRight). Its height only
     * feeds flH like any other fragment; DrawItem.h stays 16*factor rather than
     * the line-max, because the E71 body font (15-19px) sits close enough to
     * 16 that the optical mismatch is nil and the line grows via flH anyway. A
     * hit box is registered when the emoji falls inside a link span, so a link
     * whose only visible glyph is an emoji is still focusable (same as
     * flowFrag). No font/color/bg: the glyph carries its own colour.
     */
    private void flowEmoji(int glyph, Font f, boolean leadingSpace,
            MdSpan link) {
        int gpx = 16 * factor;
        int emw = gpx + 2;
        int spW = (leadingSpace && flHas) ? spaceW(f) : 0;
        // Wrap first if the token plus its leading space would overrun. On an
        // already-empty line it is emitted regardless (emw is ~18px, far under
        // any real flow width) rather than looping - mirrors flowBroken's
        // empty-line fallback so the pen never stalls.
        if (flHas && flX + spW + emw > flRight) {
            flowNewline();
            spW = 0;
        }
        if (spW > 0) {
            flX += spW;
        }
        DrawItem it = new DrawItem(K_EMOJI);
        it.x = flX;
        it.y = flTop;
        it.w = emw;
        it.h = gpx;
        it.glyph = glyph;
        bItems.addElement(it);
        if (link != null) {
            addLinkBox(flX, flTop, emw, gpx, link);
        }
        flX += emw;
        if (gpx > flH) {
            flH = gpx;
        }
        flHas = true;
    }

    /**
     * Places a #tag as an unbreakable rounded pill (background rect + text +
     * hit box). Tags never wrap internally, so the pill moves to the next line
     * as a unit and is clipped rather than split when it is too wide.
     */
    private void flowTag(MdSpan sp, int size) {
        // A tag pill computes its own leading gap from flHas; consume any
        // space owed by the previous span so it can't leak past the tag.
        flPendingSpace = false;
        Font f = fontFor(0, size, false);
        String word = sp.text;
        int padX = 3;
        // Clamp an over-wide pill to the flow width so a single long #tag can
        // never push contentW past the screen (which would flip the whole note
        // into horizontal-pan mode and disable arrow-key link nav). Ui.clip
        // measures at base size, so give it the unscaled width budget; the
        // upscale factor is reapplied below. Mirrors how table cells clip.
        int availUnscaled = (flRight - flLeft - 2 * padX) / factor;
        if (availUnscaled < 1) {
            availUnscaled = 1;
        }
        word = Ui.clip(word, f, availUnscaled);
        int ww = f.stringWidth(word) * factor;
        int total = ww + 2 * padX;
        int spW = flHas ? spaceW(f) : 0;
        if (flHas && flX + spW + total > flRight) {
            flowNewline();
            spW = 0;
        }
        if (spW > 0) {
            flX += spW;
        }
        int h = f.getHeight() * factor;
        DrawItem r = new DrawItem(K_RECT);
        r.x = flX;
        r.y = flTop;
        r.w = total;
        r.h = h;
        r.color = Theme.tagBg;
        r.flags = R_ROUND;
        bItems.addElement(r);
        DrawItem t = new DrawItem(K_TEXT);
        t.x = flX + padX;
        t.y = flTop;
        t.w = ww;
        t.h = h;
        t.text = word;
        t.font = f;
        t.color = Theme.tagText;
        // Pill color as effective run bg so factor > 1 upscales stay clean.
        t.bg = Theme.tagBg;
        bItems.addElement(t);
        addLinkBox(flX, flTop, total, h, sp);
        addAllLink(sp);
        flX += total;
        if (h > flH) {
            flH = h;
        }
        flHas = true;
    }

    private void addLinkBox(int x, int y, int w, int h, MdSpan span) {
        LinkBox lb = new LinkBox();
        lb.x = x;
        lb.y = y;
        lb.w = w;
        lb.h = h;
        lb.span = span;
        bLinks.addElement(lb);
    }

    /**
     * Records a link destination for the Menu > Links list, deduped on
     * kind+target so a repeated or wrapped link is offered exactly once. A
     * Vector plus a linear scan rather than a Hashtable because the list is
     * shown in document order, which a hashed set would not preserve.
     */
    private void addAllLink(MdSpan sp) {
        for (int i = 0; i < bAll.size(); i++) {
            MdSpan e = (MdSpan) bAll.elementAt(i);
            if (e.kind == sp.kind && eq(e.target, sp.target)) {
                return;
            }
        }
        bAll.addElement(sp);
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    /**
     * Lays out one image, returning the y below it. The ladder of fallbacks,
     * in order: a remote src becomes tappable link text (there is no image
     * fetching here); a cached entry is reused as-is; otherwise the header is
     * probed and, if the pixel count or encoding is out of budget, a
     * desktop-generated preview sidecar is tried before giving up on a
     * placeholder box. Whatever the outcome, it is written into imgCache -
     * failures included - so a relayout never repeats the work.
     *
     * <p>This runs inside the layout pass, i.e. it can do blocking JSR-75 file
     * I/O and JPEG decoding. That is why an image-heavy note is slow to open on
     * the E71 and why the size gate below matters so much.
     */
    private int layoutImageBlock(int leftX, int right, int topY, MdSpan sp) {
        String src = sp.target;
        String alt = sp.text;
        int maxW = right - leftX;
        if (isHttp(src)) {
            Font f = bodyFont;
            String disp = "[img: " + ((alt != null) ? alt : "") + "]";
            int ww = f.stringWidth(disp) * factor;
            int fh = f.getHeight() * factor;
            DrawItem it = new DrawItem(K_TEXT);
            it.x = leftX;
            it.y = topY;
            it.w = ww;
            it.h = fh;
            it.text = disp;
            it.font = f;
            it.color = Theme.link;
            it.flags = R_UNDER;
            bItems.addElement(it);
            MdSpan link = new MdSpan(MdSpan.T_LINK, disp, src, 0);
            addLinkBox(leftX, topY, ww, fh, link);
            addAllLink(link);
            return topY + fh + GAP;
        }
        // Cache on the RESOLVED path, not the raw src, so a note that writes
        // the same image both by bare name and by full path decodes it once.
        String rel2 = resolveImg(src);
        Object cached = imgCache.get(rel2);
        Image img = null;
        String ph = null;
        if (cached instanceof Image) {
            img = (Image) cached;
        } else if (cached instanceof String) {
            ph = (String) cached;
        } else {
            // Header probe BEFORE any read: a big or progressive image is
            // refused here, so readBytes never allocates a multi-MB buffer.
            int[] pr = (m.files != null) ? m.files.probeImage(rel2) : null;
            boolean gated = pr != null
                    && (pr[2] == ImgProbe.KIND_UNSUPPORTED
                        || (pr[2] == ImgProbe.KIND_OK
                            && (long) pr[0] * pr[1] > PIX_MAX));
            if (gated) {
                // The original is too big to decode, but a desktop-generated
                // preview sidecar (<=640x480) is small and safe - show it inline
                // instead of a placeholder. Read it directly (try/catch, no extra
                // exists() prompt), and only for already-gated images so small
                // images never pay a sidecar probe.
                Image sim = null;
                try {
                    byte[] sb = m.readBytes(THUMB_DIR + rel2 + ".jpg");
                    Image sraw;
                    try {
                        sraw = Image.createImage(sb, 0, sb.length);
                    } finally {
                        sb = null;
                    }
                    sim = fitWidth(sraw, maxW);
                    sraw = null;
                } catch (Throwable t) {
                    sim = null; // no sidecar (or decode failed) -> placeholder
                }
                if (sim != null) {
                    img = sim;
                    imgCache.put(rel2, img);
                } else {
                    ph = "image too large: " + nameOf(src, alt)
                            + " " + pr[0] + "x" + pr[1];
                    imgCache.put(rel2, ph);
                }
            } else {
                try {
                    byte[] b = m.readBytes(rel2);
                    Image raw;
                    try {
                        raw = Image.createImage(b, 0, b.length);
                    } finally {
                        b = null; // release the compressed bytes immediately
                    }
                    img = fitWidth(raw, maxW);
                    raw = null; // drop the full-res original; keep only the fit
                    if (img != null) {
                        imgCache.put(rel2, img);
                    } else {
                        // fitWidth OOMed building the downscale: placeholder,
                        // and DO NOT cache the raw full-res hog.
                        ph = "image too large: " + nameOf(src, alt);
                        imgCache.put(rel2, ph);
                    }
                } catch (OutOfMemoryError oom) {
                    // Drop the reference and force a collect before continuing:
                    // the rest of the layout pass still has to allocate, and on
                    // the ~2MB heap it will not get far otherwise.
                    img = null;
                    System.gc();
                    ph = "image too large: " + nameOf(src, alt);
                    imgCache.put(rel2, ph);
                } catch (IOException e) {
                    // An encrypted vault whose key has not been entered reports
                    // itself through this message; show "locked" rather than a
                    // misleading "missing image" placeholder.
                    ph = "vault locked".equals(e.getMessage()) ? "locked"
                            : "[image: " + nameOf(src, alt) + "]";
                    imgCache.put(rel2, ph);
                } catch (Throwable t) {
                    ph = "[image: " + nameOf(src, alt) + "]";
                    imgCache.put(rel2, ph);
                }
            }
        }
        if (img != null) {
            int iw = img.getWidth();
            int ih = img.getHeight();
            DrawItem it = new DrawItem(K_IMAGE);
            it.x = leftX;
            it.y = topY;
            it.w = iw;
            it.h = ih;
            it.image = img;
            bItems.addElement(it);
            addLinkBox(leftX, topY, iw, ih, sp);
            addAllLink(sp);
            return topY + ih + GAP;
        }
        return placeholder(leftX, topY, maxW, (ph != null) ? ph : "[image]", sp);
    }

    /**
     * A bordered stand-in box for an image that could not be shown. It still
     * gets a link box and an entry in the Links list, so FIRE hands the image
     * to ImageView, which retries the load with its own decode/OOM handling.
     * Note that ImageView applies the SAME PIX_MAX gate, so an over-budget
     * original with no preview sidecar is refused there too - the placeholder
     * is a second chance, not a guaranteed one.
     */
    private int placeholder(int leftX, int topY, int maxW, String label,
            MdSpan sp) {
        int h = bodyFont.getHeight() * factor + 10;
        DrawItem bg = new DrawItem(K_RECT);
        bg.x = leftX;
        bg.y = topY;
        bg.w = maxW;
        bg.h = h;
        bg.color = Theme.placeholderBg;
        bg.flags = R_ROUND;
        bItems.addElement(bg);
        DrawItem bd = new DrawItem(K_BORDER);
        bd.x = leftX;
        bd.y = topY;
        bd.w = maxW;
        bd.h = h;
        bd.color = Theme.hr;
        bd.flags = R_ROUND;
        bItems.addElement(bd);
        int tw = bodyFont.stringWidth(label) * factor;
        int tx = leftX + (maxW - tw) / 2;
        if (tx < leftX + 2) {
            tx = leftX + 2;
        }
        addPlainText(label, tx, topY + 5, Theme.placeholderText);
        addLinkBox(leftX, topY, maxW, h, sp);
        addAllLink(sp);
        return topY + h + GAP;
    }

    /**
     * Nearest-neighbor downscale to at most maxW, preserving aspect ratio.
     * Returns raw untouched when it already fits, and null when the resample
     * failed for any reason (see the catch below). MIDP has no scaling blit,
     * so the pixels are resampled by hand.
     */
    private Image fitWidth(Image raw, int maxW) {
        int iw = raw.getWidth();
        int ih = raw.getHeight();
        if (iw <= maxW || maxW <= 0) {
            return raw;
        }
        int nw = maxW;
        int nh = ih * nw / iw;
        if (nh < 1) {
            nh = 1;
        }
        try {
            // One source row at a time, never the whole source image: a 5MP
            // frame as int[] would be 20MB against a ~2MB heap.
            int[] srow = new int[iw];
            int[] dst = new int[nw * nh];
            int last = -1;
            for (int y = 0; y < nh; y++) {
                int sy = y * ih / nh;
                // Consecutive output rows often map to the same source row on a
                // big downscale; skip the getRGB when it has not changed.
                if (sy != last) {
                    raw.getRGB(srow, 0, iw, 0, sy, iw, 1);
                    last = sy;
                }
                int dr = y * nw;
                for (int x = 0; x < nw; x++) {
                    dst[dr + x] = srow[x * iw / nw];
                }
            }
            return Image.createRGBImage(dst, nw, nh, true);
        } catch (Throwable t) {
            // Never return the raw full-res Image here: the caller would cache
            // it and keep the memory hog alive (and it could drive horizontal
            // pan). Return null so the caller emits a placeholder instead.
            return null;
        }
    }

    /**
     * Maps a markdown image src onto a vault-relative path. NoteIndex.resolve
     * is itself synchronized, but a background rebuild empties and refills the
     * index as a clear()+add() batch under synchronized(ix); taking the same
     * lock here is what stops a resolve landing in the middle of that and
     * seeing an empty index. When there is no index, or it cannot resolve the
     * name, the literal path is normalized and used as-is.
     */
    private String resolveImg(String src) {
        NoteIndex ix = m.index;
        String r = null;
        if (ix != null) {
            synchronized (ix) {
                r = ix.resolve(src);
            }
        }
        if (r == null || r.length() == 0) {
            r = Path.normalize(src);
        }
        return r;
    }

    /** Opens a local (vault-relative) markdown-link target inside the app. */
    private void openLocal(String target) {
        String t = target;
        if (t == null) {
            return;
        }
        int h = t.indexOf('#');      // strip "#heading" anchor (raw '#' is the
        if (h >= 0) {                // fragment separator; a '#' in a file name
            t = t.substring(0, h);   // arrives percent-encoded as %23)
        }
        t = pctDecode(t.trim());     // Obsidian encodes spaces as %20 in ( ) links
        t = Path.normalize(t);
        while (t.startsWith("./")) { // note-relative prefixes: strip and fall back
            t = t.substring(2);      // to NoteIndex vault-relative/basename match
        }
        while (t.startsWith("../")) {
            t = t.substring(3);
        }
        if (t.length() == 0) {
            return;                  // pure "#heading" self-anchor: no-op
        }
        if (Path.isImage(t)) {
            m.openImage(resolveImg(t));
            return;
        }
        String e = Path.ext(t);
        if (Path.isMarkdown(t) || e.length() == 0 || e.indexOf(' ') >= 0) {
            // A note link: .md/.markdown, a bare name ("00-START-HERE"), or a
            // dotted title whose "extension" contains a space. Resolves via the
            // lazily built index and opens the note or offers to create it.
            m.openWikilink(t);
        } else {
            // Other file types (.pdf, .zip, ...): openNote would render the raw
            // bytes, so refuse with a message instead.
            m.alertErr("Link", "Can't open '" + t + "': unsupported file type.");
        }
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    /**
     * Draws one frame. Runs on the LCDUI event thread and allocates nothing in
     * the steady state: items are already positioned and measured, so this is
     * culling plus blitting. The one exception is the lazy relayout below, and
     * the first paint of a scaled run (which rasterizes and caches it).
     */
    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        // The soft-key bar is drawn by this canvas (full-screen mode removes
        // the system one), so the content viewport stops short of it.
        int viewH = h - SOFTH;
        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);
        if (blocks == null) {
            drawSoftBar(g, w, h);
            return;
        }
        // Lazy first layout: setNote may have run before the canvas had a size.
        if (!laidOut) {
            relayout();
            laidOut = true;
        }
        if (itemArr == null || itemArr.length == 0) {
            Font ef = Font.getFont(Font.FACE_PROPORTIONAL,
                    Font.STYLE_ITALIC, Font.SIZE_SMALL);
            g.setFont(ef);
            g.setColor(Theme.dimText);
            String em = "This note is empty";
            g.drawString(em, (w - ef.stringWidth(em)) / 2,
                    viewH / 2 - ef.getHeight(), Graphics.TOP | Graphics.LEFT);
            drawSoftBar(g, w, h);
            return;
        }
        // Clip content to the viewport so nothing bleeds under the soft bar.
        g.setClip(0, 0, w, viewH);
        int dy = -scrollY;
        int top = scrollY;
        int bot = scrollY + viewH;
        // Horizontal pan (wide tables): shift the whole content coordinate
        // system left by scrollX; the viewport clip stays put, so anything
        // panned off-screen is clipped cleanly. Restored before the scrollbars.
        int dx = -scrollX;
        g.translate(dx, 0);
        // Read the snapshot once into a local: a relayout on another thread can
        // swap itemArr mid-frame, and this loop must not see two different
        // layouts. Long notes hold thousands of items, so the vertical cull
        // below is what keeps the frame cheap.
        DrawItem[] it = itemArr;
        if (it != null) for (int i = 0; i < it.length; i++) {
            DrawItem di = it[i];
            if (di.y + di.h < top || di.y > bot) {
                continue;
            }
            // Suppress content straddling the bottom edge so nothing paints as
            // a fractional sliver above the soft bar: any text row (which would
            // show amputated glyph tops), and any box only peeking in at the
            // bottom (< half visible -> a thin top-edge sliver, e.g. a callout).
            // A box that is mostly visible clips cleanly and is kept; anything
            // taller than the viewport is always drawn so it is never hidden
            // outright. Everything reappears in full once scrolled up.
            if (di.y + di.h > bot) {
                // Emoji share a text line, so drop a straddling glyph with the
                // text row it sits on rather than leaving it floating above the
                // soft bar once its neighbours are suppressed.
                if (di.kind == K_TEXT || di.kind == K_EMOJI) {
                    continue;
                }
                // Box-sliver suppression is for wide filled boxes (callouts);
                // exempt thin items (table column separators are 1px-wide
                // full-height rects) so a table's vertical lines never vanish
                // as a group while its cells are still on screen.
                if (di.w > 8 && di.h <= viewH && 2 * (bot - di.y) < di.h) {
                    continue;
                }
            }
            drawItem(g, di, dy);
        }
        // Focus ring last, so it sits on top of the item it outlines. Drawn in
        // the panned coordinate system, hence lb.x without dx.
        Vector lk = links;
        if (focus >= 0 && focus < lk.size()) {
            LinkBox lb = (LinkBox) lk.elementAt(focus);
            g.setColor(Theme.focus);
            g.drawRoundRect(lb.x - 2, lb.y + dy - 1, lb.w + 3, lb.h + 1, 6, 6);
        }
        g.translate(-dx, 0); // back to absolute coords for the scrollbars/soft bar
        drawScrollbar(g, w, viewH);
        drawHScrollbar(g, w, viewH);
        g.setClip(0, 0, w, h);
        drawSoftBar(g, w, h);
    }

    /** Self-drawn themed soft-key bar (Menu / Edit), matching UiScreen. */
    private void drawSoftBar(Graphics g, int w, int h) {
        int y = h - SOFTH;
        g.setColor(Theme.softBar);
        g.fillRect(0, y, w, SOFTH);
        g.setColor(Theme.hr);
        g.drawLine(0, y, w, y);
        Font sf = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Font.SIZE_SMALL);
        g.setFont(sf);
        int ty = y + (SOFTH - sf.getHeight()) / 2;
        g.setColor(Theme.dimText);
        g.drawString("Menu", 4, ty, Graphics.TOP | Graphics.LEFT);
        g.setColor(Theme.dimText);
        g.drawString("Edit", w - 4 - sf.stringWidth("Edit"), ty,
                Graphics.TOP | Graphics.LEFT);
    }

    /** Draws one primitive, converting its content y to screen y with dy. */
    private void drawItem(Graphics g, DrawItem it, int dy) {
        int y = it.y + dy;
        int k = it.kind;
        if (k == K_TEXT) {
            if (it.bg >= 0) {
                // 1px bleed on each side so adjacent fragments of the same
                // chip (inline code, highlight) join without a hairline seam
                // where the measured widths do not quite meet.
                g.setColor(it.bg);
                g.fillRect(it.x - 1, y, it.w + 2, it.h);
            }
            if (factor > 1) {
                drawScaledText(g, it, y);
                return;
            }
            g.setFont(it.font);
            g.setColor(it.color);
            g.drawString(it.text, it.x, y, Graphics.TOP | Graphics.LEFT);
            if ((it.flags & R_UNDER) != 0) {
                g.drawLine(it.x, y + it.h - 1, it.x + it.w, y + it.h - 1);
            }
            if ((it.flags & R_STRIKE) != 0) {
                int my = y + it.h / 2;
                g.drawLine(it.x, my, it.x + it.w, my);
            }
        } else if (k == K_RECT) {
            g.setColor(it.color);
            if ((it.flags & R_ROUND) != 0) {
                g.fillRoundRect(it.x, y, it.w, it.h, 8, 8);
            } else {
                g.fillRect(it.x, y, it.w, it.h);
            }
        } else if (k == K_BORDER) {
            // drawRect/drawRoundRect paint an inclusive outline, so the far
            // edge lands at x+w: subtract 1 to stay inside the item's box.
            g.setColor(it.color);
            if ((it.flags & R_ROUND) != 0) {
                g.drawRoundRect(it.x, y, it.w - 1, it.h - 1, 8, 8);
            } else {
                g.drawRect(it.x, y, it.w - 1, it.h - 1);
            }
        } else if (k == K_LINE) {
            g.setColor(it.color);
            g.drawLine(it.x, y, it.x + it.w, y);
        } else if (k == K_IMAGE) {
            if (it.image != null) {
                g.drawImage(it.image, it.x, y, Graphics.TOP | Graphics.LEFT);
            }
        } else if (k == K_CHECK) {
            // Clean rounded box; a 2px accent check when the task is done.
            if ((it.flags & R_CHECKED) != 0) {
                g.setColor(Theme.accent);
                g.drawRoundRect(it.x, y, it.w - 1, it.h - 1, 4, 4);
                int x0 = it.x + 3;
                int y0 = y + it.h / 2;
                int x1 = it.x + it.w / 2 - 1;
                int y1 = y + it.h - 4;
                int x2 = it.x + it.w - 3;
                int y2 = y + 3;
                g.drawLine(x0, y0, x1, y1);
                g.drawLine(x1, y1, x2, y2);
                // Same two strokes offset by 1px: MIDP has no line width, so a
                // 2px tick has to be drawn twice to read at all on the E71.
                g.drawLine(x0, y0 + 1, x1, y1 + 1);
                g.drawLine(x1, y1 + 1, x2, y2 + 1);
            } else {
                g.setColor(Theme.dimText);
                g.drawRoundRect(it.x, y, it.w - 1, it.h - 1, 4, 4);
            }
        } else if (k == K_BULLET) {
            // MIDP has no fillOval; a full-circle arc is the equivalent.
            g.setColor(it.color);
            g.fillArc(it.x, y, it.w, it.h, 0, 360);
        } else if (k == K_EMOJI) {
            drawEmoji(g, it, y);
        }
    }

    /**
     * Blits one emoji glyph. At factor 1 the 16x16 strip region is drawn
     * directly from its (LRU-cached) page; at factor > 1 a cached
     * nearest-neighbor upscale is drawn instead. The glyph paints at it.x + 1
     * (the box carries 1px of breathing room each side) and, since it.h is
     * exactly 16*factor, its (it.h - gpx)/2 vertical inset is 0. A page that
     * failed to load or an upscale that OOMed leaves the 16px box blank -
     * never a crash and never a fallback glyph.
     */
    private void drawEmoji(Graphics g, DrawItem it, int y) {
        int gpx = 16 * factor;
        int gx = it.x + 1;
        int gy = y + (it.h - gpx) / 2;
        if (factor > 1) {
            Image up = scaledEmoji(it.glyph);
            if (up != null) {
                g.drawImage(up, gx, gy, Graphics.TOP | Graphics.LEFT);
            }
            return;
        }
        Image page = emojiPage(Emoji.pageOf(it.glyph));
        if (page == null) {
            return;
        }
        int slot = Emoji.slotOf(it.glyph);
        // 0 == Sprite.TRANS_NONE; no import needed for the one literal.
        g.drawRegion(page, slot * 16, 0, 16, 16, 0, gx, gy,
                Graphics.TOP | Graphics.LEFT);
    }

    /**
     * Returns the decoded strip page n from the LRU cache, loading and
     * inserting it (evicting the least-recently-used page past PAGE_CAP) on a
     * miss. Null when the page could not be loaded, which the caller treats as
     * a blank glyph box. Paint-thread only, so no synchronization.
     */
    private Image emojiPage(int n) {
        Integer key = new Integer(n);
        Object c = pageCache.get(key);
        if (c != null) {
            touchPage(key);
            return (Image) c;
        }
        Image img = loadPage(n);
        if (img == null) {
            return null;
        }
        if (pageCache.size() >= PAGE_CAP && !pageOrder.isEmpty()) {
            Object lru = pageOrder.elementAt(0);
            pageOrder.removeElementAt(0);
            pageCache.remove(lru);
        }
        pageCache.put(key, img);
        pageOrder.addElement(key);
        return img;
    }

    /** Moves key to the most-recently-used end (linear scan, <= 8 entries). */
    private void touchPage(Object key) {
        for (int i = 0; i < pageOrder.size(); i++) {
            if (pageOrder.elementAt(i).equals(key)) {
                pageOrder.removeElementAt(i);
                break;
            }
        }
        pageOrder.addElement(key);
    }

    /**
     * Decodes strip page n from the jar. IOException (missing/corrupt entry) is
     * a permanent blank. OutOfMemoryError is recoverable once: drop every
     * resident page to reclaim ~256KB and retry; if it still fails, give up for
     * this paint pass (the glyph box stays blank, no crash).
     */
    private Image loadPage(int n) {
        try {
            return Image.createImage("/emoji/p" + n + ".png");
        } catch (IOException e) {
            return null;
        } catch (OutOfMemoryError oom) {
            pageCache.clear();
            pageOrder.removeAllElements();
            try {
                return Image.createImage("/emoji/p" + n + ".png");
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * Returns the cached factor-scaled image of one emoji glyph, building it
     * once. Shares glyphCache with scaledRun under the disjoint key
     * "E|"+glyph+"|"+factor, so it is evicted on the same setNote lifecycle.
     * The 16x16 region is read from its page via getRGB and integer-block
     * upscaled with the same replication as scaledRun, then rebuilt via
     * createRGBImage(..., true) so the glyph's alpha (its transparent
     * background) is preserved. (MicroEmulator's LCD filter dims such images
     * ~0.75x; that is an emulator-only artifact, irrelevant on the E71.)
     */
    private Image scaledEmoji(int glyph) {
        String key = "E|" + glyph + '|' + factor;
        Object c = glyphCache.get(key);
        if (c != null) {
            return (Image) c;
        }
        Image page = emojiPage(Emoji.pageOf(glyph));
        if (page == null) {
            return null;
        }
        try {
            int slot = Emoji.slotOf(glyph);
            int[] src = new int[16 * 16];
            page.getRGB(src, 0, 16, slot * 16, 0, 16, 16);
            int nw = 16 * factor;
            int[] dst = new int[nw * nw];
            // Box replication: expand each source row once, then arraycopy it
            // down for the remaining factor-1 rows (see scaledRun).
            for (int sy = 0; sy < 16; sy++) {
                int so = sy * 16;
                int drow = sy * factor * nw;
                for (int sx = 0; sx < 16; sx++) {
                    int p = src[so + sx];
                    int d = drow + sx * factor;
                    for (int i = 0; i < factor; i++) {
                        dst[d + i] = p;
                    }
                }
                for (int i = 1; i < factor; i++) {
                    System.arraycopy(dst, drow, dst, drow + i * nw, nw);
                }
            }
            Image out = Image.createRGBImage(dst, nw, nw, true);
            glyphCache.put(key, out);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Paints a text run at factor > 1. The run is rasterized once at its
     * base font size, integer-upscaled (nearest neighbor: every source
     * pixel becomes a factor x factor block, perfectly crisp) and cached,
     * so the steady-state per-frame cost is a single drawImage.
     */
    private void drawScaledText(Graphics g, DrawItem it, int y) {
        Image im = it.scaled;
        if (im == null) {
            im = scaledRun(it.text, it.font, it.color, it.bg);
            if (im != null) {
                it.scaled = im;
            }
        }
        if (im != null) {
            g.drawImage(im, it.x, y, Graphics.TOP | Graphics.LEFT);
        } else {
            // Out-of-memory fallback: draw unscaled rather than nothing.
            g.setFont(it.font);
            g.setColor(it.color);
            g.drawString(it.text, it.x, y, Graphics.TOP | Graphics.LEFT);
        }
        // Rules are stroked after the blit rather than baked into the cached
        // image: they are pure geometry, so keeping them out lets runs that
        // differ only in underline/strike share one cache entry, and drawing
        // them factor px thick keeps them exactly as crisp as the upscale.
        if ((it.flags & (R_UNDER | R_STRIKE)) == 0) {
            return;
        }
        g.setColor(it.color);
        if ((it.flags & R_UNDER) != 0) {
            g.fillRect(it.x, y + it.h - factor, it.w, factor);
        }
        if ((it.flags & R_STRIKE) != 0) {
            g.fillRect(it.x, y + (it.h - factor) / 2, it.w, factor);
        }
    }

    /** Returns the cached factor-scaled image of a run, building it once. */
    private Image scaledRun(String s, Font f, int color, int bg) {
        // The run is rasterized ONTO its background (the base image has no
        // alpha), so the effective bg is part of the cache identity: the same
        // word over a callout tint and over the page bg are different images.
        int ebg = (bg >= 0) ? bg : Theme.bg;
        String key = s + '|' + f.getFace() + '|' + f.getStyle() + '|'
                + f.getSize() + '|' + color + '|' + ebg + '|' + factor;
        Object c = glyphCache.get(key);
        if (c != null) {
            return (Image) c;
        }
        try {
            int bw = f.stringWidth(s);
            int bh = f.getHeight();
            if (bw < 1) {
                bw = 1;
            }
            if (bh < 1) {
                bh = 1;
            }
            Image base = Image.createImage(bw, bh);
            Graphics bg2 = base.getGraphics();
            bg2.setColor(ebg);
            bg2.fillRect(0, 0, bw, bh);
            bg2.setFont(f);
            bg2.setColor(color);
            bg2.drawString(s, 0, 0, Graphics.TOP | Graphics.LEFT);
            int[] src = new int[bw * bh];
            base.getRGB(src, 0, bw, 0, 0, bw, bh);
            int nw = bw * factor;
            int nh = bh * factor;
            int[] dst = new int[nw * nh];
            // Box replication: expand each source row horizontally once, then
            // arraycopy it down for the remaining factor-1 rows, so the inner
            // per-pixel work is done bw times per row instead of bw*factor.
            for (int sy = 0; sy < bh; sy++) {
                int so = sy * bw;
                int drow = sy * factor * nw;
                for (int sx = 0; sx < bw; sx++) {
                    int p = src[so + sx];
                    int d = drow + sx * factor;
                    for (int i = 0; i < factor; i++) {
                        dst[d + i] = p;
                    }
                }
                // Duplicate the expanded row for the remaining factor-1 rows.
                for (int i = 1; i < factor; i++) {
                    System.arraycopy(dst, drow, dst, drow + i * nw, nw);
                }
            }
            // Blit the upscaled pixels into a MUTABLE image via drawRGB, then
            // return that. createRGBImage would route the pixels through this
            // device's RGBImageFilter (LCD colour simulation: background is
            // 0xC0C0C0, not white), remapping every channel to ~0.75x and so
            // dimming the whole run relative to the directly-drawn chrome. A
            // mutable image filled with drawRGB and later blitted with
            // drawImage takes the same direct, unfiltered path as the chrome,
            // so scaled runs keep their exact theme colours.
            Image out = Image.createImage(nw, nh);
            out.getGraphics().drawRGB(dst, 0, nw, 0, 0, nw, nh, false);
            // Cache each unique run once; evicted only on setNote. A running
            // pixel-budget clear() was tried here but was catastrophic: at
            // factor>=2 a single screen of scaled runs already sums to far more
            // than any small budget, so the clear tripped several times PER
            // paint, discarding still-visible images that the same paint then
            // re-rasterized - unbounded per-frame allocation that exhausted the
            // ~2MB E71 heap and crashed with OutOfMemoryError. Steady caching is
            // bounded by the note's unique visible runs and is what shipped
            // stably before the optimization pass.
            glyphCache.put(key, out);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Right-edge vertical scrollbar, drawn only when the note overflows. */
    private void drawScrollbar(Graphics g, int w, int h) {
        if (contentH <= h) {
            return;
        }
        int x = w - 4;
        g.setColor(Theme.placeholderBg);
        g.fillRect(x, 0, 3, h);
        // Thumb length is proportional to the visible fraction, floored at 12px
        // so a very long note still shows a thumb instead of a stray pixel.
        int thumb = h * h / contentH;
        if (thumb < 12) {
            thumb = 12;
        }
        if (thumb > h) {
            thumb = h;
        }
        int max = contentH - h;
        int ty = (max > 0) ? (h - thumb) * scrollY / max : 0;
        g.setColor(Theme.scrollbar);
        g.fillRect(x, ty, 3, thumb);
    }

    /** Horizontal scrollbar, shown only when content is wider than the screen. */
    private void drawHScrollbar(Graphics g, int w, int viewH) {
        if (contentW <= w) {
            return;
        }
        int y = viewH - 3;
        g.setColor(Theme.placeholderBg);
        g.fillRect(0, y, w, 3);
        int thumb = w * w / contentW;
        if (thumb < 12) {
            thumb = 12;
        }
        if (thumb > w) {
            thumb = w;
        }
        int max = contentW - w;
        int tx = (max > 0) ? (w - thumb) * scrollX / max : 0;
        g.setColor(Theme.scrollbar);
        g.fillRect(tx, y, thumb, 3);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Key handling. Ui.LSK/RSK are the raw S60 soft-key codes (-6/-7), which
     * only reach a canvas because the constructor turned on full-screen mode;
     * without it Symbian routes them to the (unused) command menu instead.
     * Game actions are used for the d-pad so the same code works on devices
     * with different key codes.
     */
    protected void keyPressed(int key) {
        if (blocks == null) {
            return;
        }
        if (key == Ui.LSK) {
            openMenu();
            return;
        }
        if (key == Ui.RSK) {
            if (rel != null) {
                m.editNote(rel);
            }
            return;
        }
        if (key == Canvas.KEY_NUM0) {
            scrollY = 0;
            scrollX = 0;
            repaint();
            return;
        }
        if (key == ' ') { // space = page down (~80% of the visible height)
            scrollBy((getHeight() - SOFTH) * 4 / 5);
            return;
        }
        int ga = gameAction(key);
        if (ga == Canvas.UP) {
            scrollBy(-48);
        } else if (ga == Canvas.DOWN) {
            scrollBy(48);
        } else if (ga == Canvas.LEFT) {
            // Pan a wide table; otherwise step link focus (links stay reachable
            // via Menu > Links even when panning is active).
            if (hScrollable()) {
                scrollXBy(-48);
            } else {
                moveFocus(-1);
            }
        } else if (ga == Canvas.RIGHT) {
            if (hScrollable()) {
                scrollXBy(48);
            } else {
                moveFocus(1);
            }
        } else if (ga == Canvas.FIRE) {
            // Shared confirm filter: the E71 delivers one Enter as two events,
            // and following a wikilink twice would push the same note onto the
            // stack twice (or open a link on the note it just opened).
            if (UiScreen.confirmAccepted(key)) {
                activateFocus();
            }
        }
    }

    /** Opens the themed Options menu (replaces the native command menu). */
    private void openMenu() {
        String[] mi = {"Edit", "Links", "Top", "Info", "Back"};
        UiMenuOwner ow = new UiMenuOwner() {
            public void menuSelect(String item, int index) {
                if ("Edit".equals(item)) {
                    if (rel != null) {
                        m.editNote(rel);
                    }
                } else if ("Links".equals(item)) {
                    showLinks();
                } else if ("Top".equals(item)) {
                    scrollY = 0;
                    scrollX = 0;
                    repaint();
                } else if ("Info".equals(item)) {
                    showInfo();
                } else if ("Back".equals(item)) {
                    m.back();
                }
            }
        };
        new UiMenu(m, this, mi, ow).show();
    }

    /**
     * Auto-repeat while an arrow is held. Deliberately covers scrolling and
     * panning only: repeating link focus would make a held LEFT/RIGHT race
     * through every link on screen faster than anyone can see.
     */
    protected void keyRepeated(int key) {
        int ga = gameAction(key);
        if (ga == Canvas.UP) {
            scrollBy(-48);
        } else if (ga == Canvas.DOWN) {
            scrollBy(48);
        } else if (ga == Canvas.LEFT && hScrollable()) {
            scrollXBy(-48);
        } else if (ga == Canvas.RIGHT && hScrollable()) {
            scrollXBy(48);
        }
    }

    /**
     * getGameAction throws on some S60 builds for keys it has no mapping for
     * (and 0 is not a valid action), so unmapped keys are swallowed rather than
     * allowed to kill the event thread.
     */
    private int gameAction(int key) {
        try {
            return getGameAction(key);
        } catch (Throwable t) {
            return 0;
        }
    }

    private void scrollBy(int d) {
        scrollY += d;
        clampScroll();
        repaint();
    }

    private void scrollXBy(int d) {
        scrollX += d;
        clampScroll();
        repaint();
    }

    /** True when the laid-out content is wider than the screen (wide table). */
    private boolean hScrollable() {
        return contentW > getWidth();
    }

    /**
     * Clamps both scroll axes into range. Called after every scroll, relayout
     * and size change, so scroll offsets are always valid by the time paint
     * reads them. The viewport height excludes the self-drawn soft-key bar.
     */
    private void clampScroll() {
        int max = contentH - (getHeight() - SOFTH);
        if (max < 0) {
            max = 0;
        }
        if (scrollY > max) {
            scrollY = max;
        }
        if (scrollY < 0) {
            scrollY = 0;
        }
        int maxX = contentW - getWidth();
        if (maxX < 0) {
            maxX = 0;
        }
        if (scrollX > maxX) {
            scrollX = maxX;
        }
        if (scrollX < 0) {
            scrollX = 0;
        }
    }

    /**
     * Steps link focus by dir (+1/-1), wrapping around and skipping any link
     * scrolled out of view - focusing something off-screen would move the
     * highlight where the reader cannot see it. Gives up silently after a full
     * cycle, which is the case where the visible region holds no links.
     */
    private void moveFocus(int dir) {
        Vector lk = links;
        int n = lk.size();
        if (n == 0) {
            return;
        }
        int h = getHeight() - SOFTH;
        // With nothing focused yet, start just outside the end we are entering
        // from, so the first step lands on index 0 (forward) or n-1 (backward).
        int start = (focus < 0) ? (dir > 0 ? -1 : 0) : focus;
        for (int step = 1; step <= n; step++) {
            // Java's % keeps the sign of the dividend, so the extra +n and
            // second % turn a negative remainder into a real modulo.
            int idx = ((start + dir * step) % n + n) % n;
            LinkBox lb = (LinkBox) lk.elementAt(idx);
            // Any overlap with the viewport counts as visible.
            if (lb.y + lb.h >= scrollY && lb.y <= scrollY + h) {
                focus = idx;
                repaint();
                return;
            }
        }
    }

    private void activateFocus() {
        Vector lk = links;
        if (focus < 0 || focus >= lk.size()) {
            return;
        }
        activate(((LinkBox) lk.elementAt(focus)).span);
    }

    /**
     * Follows a link span. Shared by FIRE on the focused box and by the
     * Menu > Links list, so both routes behave identically.
     */
    private void activate(MdSpan sp) {
        if (sp == null) {
            return;
        }
        int k = sp.kind;
        if (k == MdSpan.T_WIKILINK) {
            // Delegates to the MIDlet, which builds the index lazily (off the
            // UI thread, behind a progress screen) the first time a wikilink is
            // followed, then resolves and opens or offers to create the note.
            m.openWikilink(sp.target);
        } else if (k == MdSpan.T_LINK) {
            if (isExternal(sp.target)) {
                // platformRequest hands the URL to the phone's browser/dialer.
                // It throws on unsupported schemes and can also fail outright
                // on S60, so it is never allowed to escape as a crash.
                try {
                    m.platformRequest(sp.target);
                } catch (Throwable t) {
                    m.alertErr("Link", sp.target + ": " + t.toString());
                }
            } else {
                openLocal(sp.target);
            }
        } else if (k == MdSpan.T_IMAGE) {
            m.openImage(resolveImg(sp.target));
        } else if (k == MdSpan.T_TAG) {
            m.searchNotes("#" + sp.target);
        }
    }

    // ------------------------------------------------------------------
    // Menu actions
    // ------------------------------------------------------------------

    /**
     * Menu > Links: a flat list of every distinct destination in the note. This
     * is the only way to reach links while a wide table owns LEFT/RIGHT for
     * panning, and it also beats arrowing through a long note by hand.
     */
    private void showLinks() {
        // Snapshot the vector: a relayout would swap the field out from under
        // the list owner, which keeps using it after this method returns.
        final Vector al = allLinks;
        if (al == null || al.isEmpty()) {
            UiDialogOwner ow = new UiDialogOwner() {
                public void dialogResult(boolean positive) {
                    m.show(Viewer.this);
                }
            };
            new UiDialog(m, Viewer.this, "Links", "This note has no links.",
                    UiDialog.OK, ow).show();
            return;
        }
        UiListOwner owner = new UiListOwner() {
            public void listSelect(int index) {
                if (index >= 0 && index < al.size()) {
                    activate((MdSpan) al.elementAt(index));
                } else {
                    m.show(Viewer.this);
                }
            }

            public void listMenu(String command) {
                m.show(Viewer.this);
            }

            public void listBack() {
                m.show(Viewer.this);
            }
        };
        UiList list = new UiList(m, "Links", owner);
        Vector labels = new Vector();
        for (int i = 0; i < al.size(); i++) {
            labels.addElement(linkLabel((MdSpan) al.elementAt(i)));
        }
        list.setItems(labels);
        list.setCommands("Menu", "Open");
        String[] menu = new String[1];
        menu[0] = "Back";
        list.setMenu(menu);
        m.show(list);
    }

    /** Menu > Info: path, on-disk size and word count for the open note. */
    private void showInfo() {
        // Utf8.encode is the same encoder NoksidianMIDlet.writeText uses, so the
        // figure matches the file on the card rather than the char count
        // (non-ASCII and emoji notes differ); it never throws.
        int bytes = Utf8.encode(text).length;
        int words = wordCount(text);
        String msg = "Path: " + ((rel != null) ? rel : "(none)")
                + "\nSize: " + bytes + " bytes"
                + "\nWords: " + words;
        UiDialogOwner ow = new UiDialogOwner() {
            public void dialogResult(boolean positive) {
                m.show(Viewer.this);
            }
        };
        new UiDialog(m, Viewer.this, "Note info", msg, UiDialog.OK, ow).show();
    }

    // ------------------------------------------------------------------
    // Static helpers
    // ------------------------------------------------------------------

    /** Human-readable row label for one destination in the Links list. */
    private static String linkLabel(MdSpan sp) {
        if (sp.kind == MdSpan.T_TAG) {
            return "#" + sp.target;
        }
        if (sp.kind == MdSpan.T_WIKILINK) {
            // Clean name, no [[ ]] brackets (B16). sp.text is the alias when
            // the link is aliased, otherwise the bare target.
            return (sp.text != null && sp.text.length() > 0)
                    ? sp.text : sp.target;
        }
        if (sp.kind == MdSpan.T_IMAGE) {
            return "img: " + ((sp.text != null) ? sp.text : sp.target);
        }
        return (sp.text != null && sp.text.length() > 0) ? sp.text : sp.target;
    }

    /**
     * Counts whitespace-delimited runs in the RAW markdown, so markup tokens
     * count as words. Close enough for the Info screen and far cheaper than
     * counting the rendered text.
     */
    private static int wordCount(String s) {
        if (s == null) {
            return 0;
        }
        int c = 0;
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean sp = (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r');
            if (!sp) {
                if (!in) {
                    c++;
                    in = true;
                }
            } else {
                in = false;
            }
        }
        return c;
    }

    /**
     * Turns an MdSpan style bitmask into a MIDP Font. The bold argument forces
     * bold on top of the mask, for headings and callout titles whose emphasis
     * is not in the markup.
     */
    private static Font fontFor(int style, int size, boolean bold) {
        int st = 0;
        if (bold || (style & MdSpan.B_BOLD) != 0) {
            st |= Font.STYLE_BOLD;
        }
        if ((style & MdSpan.B_ITALIC) != 0) {
            st |= Font.STYLE_ITALIC;
        }
        int face = ((style & MdSpan.B_CODE) != 0) ? Font.FACE_MONOSPACE
                : Font.FACE_PROPORTIONAL;
        return Font.getFont(face, st, size);
    }

    /**
     * True for a remote image/link source. Remote images are never fetched -
     * they render as focusable link text and open in the phone's browser.
     */
    private static boolean isHttp(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    /** True for a real external URL scheme (opened via platformRequest). */
    private static boolean isExternal(String s) {
        if (s == null) {
            return false;
        }
        return isHttp(s) || s.startsWith("mailto:") || s.startsWith("tel:")
                || s.startsWith("sms:") || s.indexOf("://") >= 0;
    }

    /** Decodes %XX percent-escapes as UTF-8; returns s unchanged if no '%'. */
    private static String pctDecode(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        int n = s.length();
        // Worst case is 3 bytes per char (a BMP char re-encoded to UTF-8);
        // escapes only ever shrink the output, so one allocation suffices.
        // CLDC has no URLDecoder, hence the hand-rolled decode.
        byte[] b = new byte[n * 3];
        int o = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < n) {
                int hi = hexNib(s.charAt(i + 1));
                int lo = hexNib(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    b[o++] = (byte) ((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            if (c < 0x80) {
                b[o++] = (byte) c;
            } else if (c < 0x800) {
                b[o++] = (byte) (0xC0 | (c >> 6));
                b[o++] = (byte) (0x80 | (c & 0x3F));
            } else {
                b[o++] = (byte) (0xE0 | (c >> 12));
                b[o++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                b[o++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        // Utf8.decode is CESU-8 tolerant and never throws, so a %-escaped
        // astral target (e.g. %F0%9F%98%80) resolves to the real emoji rather
        // than mojibake or the raw escapes.
        return Utf8.decode(b, 0, o);
    }

    private static int hexNib(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** Display name for an image: its alt text, or the bare file name. */
    private static String nameOf(String src, String alt) {
        if (alt != null && alt.length() > 0) {
            return alt;
        }
        return Path.name(src);
    }

    /**
     * Flattens newlines, carriage returns and tabs to spaces so a multi-line
     * block reflows as one paragraph (markdown soft breaks are not line
     * breaks).
     */
    private static String nl2sp(String s) {
        if (s == null) {
            return "";
        }
        // Scan first and return the original String when there is nothing to
        // replace. That is the overwhelmingly common case, and it saves a
        // StringBuffer plus a copy for every paragraph in the note.
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                break;
            }
            i++;
        }
        if (i == n) {
            return s;
        }
        StringBuffer sb = new StringBuffer(s.length());
        for (int j = 0; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Splits on '\n', keeping empty lines (blank lines matter inside code
     * blocks) and always yielding at least one element. CLDC has no
     * String.split.
     */
    private static Vector splitN(String s) {
        Vector v = new Vector();
        if (s == null) {
            v.addElement("");
            return v;
        }
        int st = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                v.addElement(s.substring(st, i));
                st = i + 1;
            }
        }
        v.addElement(s.substring(st));
        return v;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return (i < 0) ? s : s.substring(0, i);
    }

    private static String restLines(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return (i < 0) ? "" : s.substring(i + 1);
    }

    private static String capitalize(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static boolean eq(String a, String b) {
        return (a == null) ? (b == null) : a.equals(b);
    }

    // ------------------------------------------------------------------
    // Data holders
    // ------------------------------------------------------------------

    /** One styled, pre-measured, single-line run inside a table cell. */
    private static final class CellRun {
        String text;   // emoji-stripped, possibly ".."-clipped
        Font font;
        int color;
        int bg;        // -1 = page bg; else chip color (code/highlight/tag)
        int flags;     // R_UNDER / R_STRIKE
        MdSpan link;   // non-null => addLinkBox + addAllLink at place time
        int w;         // device px (already * factor)
    }

    /**
     * One positioned, pre-measured drawing primitive. A single flat class for
     * every kind rather than a hierarchy: one allocation per primitive and an
     * if-chain in drawItem instead of virtual dispatch in the paint loop, at
     * the cost of a few unused fields per item. Which fields are meaningful
     * depends on `kind` - text/font apply to K_TEXT, image to K_IMAGE, and so
     * on.
     */
    private static final class DrawItem {
        /** One of the K_* constants; selects the branch in drawItem(). */
        int kind;
        /** Box in CONTENT coordinates; paint adds the scroll offset. */
        int x;
        int y;
        int w;
        int h;
        int color;
        /** Effective run background, or -1 for none (see the constructor). */
        int bg;
        /** Bitmask of R_* flags. */
        int flags;
        String text;
        Font font;
        Image image;
        /** K_EMOJI only: glyph id into the nok.core.Emoji pack (page/slot). */
        int glyph;
        /**
         * Lazily built nearest-neighbor upscale of this run, only ever set when
         * factor > 1. Shares the underlying Image with glyphCache, so this is a
         * per-item shortcut past the cache lookup, not a second copy.
         */
        Image scaled;

        DrawItem(int kind) {
            this.kind = kind;
            this.bg = -1;
        }
    }

    /**
     * Focus/hit rectangle for one drawn fragment of a link, in content
     * coordinates. A link that wraps across lines produces several of these,
     * all pointing at the same span.
     */
    private static final class LinkBox {
        int x;
        int y;
        int w;
        int h;
        MdSpan span;
    }
}
