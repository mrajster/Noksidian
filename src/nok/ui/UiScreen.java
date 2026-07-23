package nok.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import nok.NoksidianMIDlet;

/**
 * Base class for every themed screen: a title bar, a content body painted by
 * the subclass, and a soft-key caption bar. All colors come from nok.ui.Theme
 * read at paint time, so a Theme change applies on the next repaint.
 *
 * keyPressed is final: it normalizes the S60 soft keys (-6/-7/-5), the d-pad
 * game actions, printable QWERTY characters, backspace (8 / -8) and enter
 * (10 / 13) into the overridable on* callbacks. Subclasses implement
 * paintBody and whichever on* handlers they need.
 *
 * The app uses none of MIDP's high-level widgets (List, Form, TextBox, Alert):
 * their look is fixed by the handset and cannot be themed, so every screen is
 * a Canvas that paints itself. That is why this class exists - it re-implements
 * the chrome (title bar, soft-key captions) the native widgets would have
 * supplied. UiList is the Canvas replacement for lcdui List.
 *
 * Bar heights are derived from font metrics rather than fixed pixel counts, so
 * the chrome still fits if the device reports a different system font size.
 * The body rect is recomputed on every call instead of cached, since it is
 * built from getWidth/getHeight, which the platform is free to change.
 *
 * Threading: paint and the key callbacks arrive on the LCDUI event thread, so
 * nothing here is synchronized. Anything that can block (vault I/O, sync,
 * index rebuilds) has to be pushed to a worker thread by the subclass, or the
 * whole UI freezes with it.
 */
public abstract class UiScreen extends Canvas {

    /**
     * The owning MIDlet, which is the app's one controller: screens route
     * navigation (showLibrary, openNote, openSettings), vault I/O (readText,
     * writeText, listDir) and Display access (m.disp) through it.
     */
    protected final NoksidianMIDlet m;
    protected String title;
    /** Soft-key captions; null hides that side. */
    protected String leftLabel;
    protected String rightLabel;

    // Fonts are resolved once here: Font.getFont is a device lookup, and
    // getHeight/stringWidth run several times per repaint. Font instances are
    // immutable shared objects, so holding them for the screen's life is safe.
    private final Font titleFont;
    private final Font skFont;

    public UiScreen(NoksidianMIDlet m, String title) {
        this.m = m;
        this.title = title;
        // Full-screen mode hands over the rows the platform would use for its
        // own title and annunciators so this class can draw its own chrome
        // there. An implementation is free to ignore it or throw; a screen left
        // in normal mode is still usable, just with the handset's chrome
        // stacked around ours, so the failure is swallowed.
        try {
            setFullScreenMode(true);
        } catch (Throwable t) {
        }
        titleFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,
                Font.SIZE_SMALL);
        skFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Font.SIZE_SMALL);
    }

    // ------------------------------------------------------------------
    // Layout metrics
    // ------------------------------------------------------------------

    // The +6 is 3px of breathing room above and below the glyph box. Both bar
    // heights are font-derived rather than constants so the chrome grows with
    // the system font instead of clipping the caption on a device whose small
    // font is taller than the E71's.

    private int titleBarH() {
        return titleFont.getHeight() + 6;
    }

    private int softBarH() {
        return skFont.getHeight() + 6;
    }

    // Body rect accessors, for subclasses laying content out. Note that paint()
    // passes literal 0 and getWidth() to paintBody rather than calling bodyX()
    // and bodyW(), so overriding those two moves a subclass's own arithmetic
    // but not the rect it is actually handed.

    protected int bodyX() {
        return 0;
    }

    protected int bodyY() {
        return titleBarH();
    }

    protected int bodyW() {
        return getWidth();
    }

    /**
     * Height left between the two bars, clamped at zero. A canvas that has not
     * been sized yet, or a device whose font makes the two bars taller than the
     * screen, would otherwise yield a negative height that goes straight into
     * clipRect and out to paintBody.
     */
    protected int bodyH() {
        int h = getHeight() - titleBarH() - softBarH();
        return (h > 0) ? h : 0;
    }

    /** Convenience: full repaint (the body has no independent dirty rect). */
    protected void repaintBody() {
        repaint();
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    /**
     * Paints the chrome, then delegates the middle to paintBody. Called with
     * an offscreen Graphics as well: Ui.dimSnapshot (via its paintBack helper)
     * renders the screen into an Image to use as the dimmed backdrop behind a
     * popup, so this must not assume it is drawing to the live display.
     */
    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        // The incoming clip is whatever dirty rect the platform asked for, not
        // necessarily the whole screen. Saved verbatim and put back after the
        // body so the soft-key bar is drawn under the same restriction the
        // platform intended, rather than under a widened clip.
        int ocx = g.getClipX();
        int ocy = g.getClipY();
        int ocw = g.getClipWidth();
        int och = g.getClipHeight();

        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);

        int tbh = titleBarH();
        g.setColor(Theme.titleBar);
        g.fillRect(0, 0, w, tbh);
        // Minimal flat title bar: a small accent dot precedes a bold title,
        // with a 1px hairline separating it from the body.
        int dotD = 5;
        int dotX = 5;
        g.setColor(Theme.accent);
        g.fillArc(dotX, (tbh - dotD) / 2, dotD, dotD, 0, 360);
        int tx = dotX + dotD + 5;
        g.setFont(titleFont);
        g.setColor(Theme.text);
        String t = (title != null) ? title : "";
        int tty = (tbh - titleFont.getHeight()) / 2;
        g.drawString(Ui.clip(t, titleFont, w - tx - 4), tx, tty,
                Graphics.TOP | Graphics.LEFT);
        g.setColor(Theme.hr);
        g.drawLine(0, tbh - 1, w, tbh - 1);

        int by = bodyY();
        int bh = bodyH();
        // clipRect intersects with the current clip rather than replacing it,
        // so this can only narrow the platform's rect. The body becomes the
        // only region paintBody can reach: a subclass that runs a row loop past
        // the bottom of its content cannot scribble over the two bars.
        g.clipRect(0, by, w, bh);
        paintBody(g, 0, by, w, bh);
        g.setClip(ocx, ocy, ocw, och);

        drawSoftKeys(g, w, h);
    }

    private void drawSoftKeys(Graphics g, int w, int h) {
        int sh = softBarH();
        int y = h - sh;
        g.setColor(Theme.softBar);
        g.fillRect(0, y, w, sh);
        g.setColor(Theme.hr);
        g.drawLine(0, y, w, y);
        g.setFont(skFont);
        int ly = y + (sh - skFont.getHeight()) / 2;
        // Each caption is clipped to half the bar less its 4px margin, so a
        // long left label truncates instead of running into the right one.
        int half = w / 2 - 4;
        // Soft-key captions are dimText per CONTRACTS-UI.md (both sides).
        g.setColor(Theme.dimText);
        if (leftLabel != null) {
            g.drawString(Ui.clip(leftLabel, skFont, half), 4, ly,
                    Graphics.TOP | Graphics.LEFT);
        }
        if (rightLabel != null) {
            g.drawString(Ui.clip(rightLabel, skFont, half), w - 4, ly,
                    Graphics.TOP | Graphics.RIGHT);
        }
    }

    /** Subclass content painter. The rect (cx,cy,cw,ch) is the body area. */
    protected abstract void paintBody(Graphics g, int cx, int cy, int cw,
            int ch);

    // ------------------------------------------------------------------
    // Key dispatch (final)
    // ------------------------------------------------------------------

    /**
     * Turns raw key codes into the on* callbacks. Final on purpose: MIDP
     * guarantees no portable soft-key codes, so the S60 numbers in Ui.LSK /
     * RSK / MSK are decoded here and nowhere else. Subclasses that need a code
     * this method does not recognize get it through onKeyOther.
     */
    protected final void keyPressed(int keyCode) {
        if (keyCode == Ui.LSK) {
            onLeftSoft();
            return;
        }
        if (keyCode == Ui.RSK) {
            onRightSoft();
            return;
        }
        if (keyCode == Ui.MSK) {
            select(keyCode);
            return;
        }
        // Two codes for one intent: 8 is ASCII backspace, -8 is Nokia's
        // dedicated "clear" key. Ui.isChar starts at 32, so neither would be
        // taken for a printable character even without this early return.
        if (keyCode == 8 || keyCode == -8) {
            onBackspace();
            return;
        }
        // Enter is folded into onSelect rather than given its own callback:
        // confirming is one intent whether it arrives from the QWERTY Enter
        // key, the d-pad centre (Ui.MSK on the E71) or a FIRE game action. 10
        // and 13 are both accepted because the code sent varies by handset.
        if (keyCode == 10 || keyCode == 13) {
            select(keyCode);
            return;
        }
        // Printable characters take priority over game actions so that on a
        // QWERTY device number/letter keys type instead of being read as
        // d-pad actions; the real d-pad delivers negative (non-char) codes.
        if (Ui.isChar(keyCode)) {
            onChar(Ui.toChar(keyCode));
            return;
        }
        // Whatever is left is a device key. Going through the game-action
        // mapping rather than raw codes is what makes the d-pad work regardless
        // of the numbers this particular handset assigns to it. Unmapped keys
        // fall through to onKeyOther with the code intact, which is the only
        // escape hatch a subclass has past this final method.
        int ga = gameAction(keyCode);
        if (ga == Canvas.UP) {
            onUp();
        } else if (ga == Canvas.DOWN) {
            onDown();
        } else if (ga == Canvas.LEFT) {
            onLeftArrow();
        } else if (ga == Canvas.RIGHT) {
            onRightArrow();
        } else if (ga == Canvas.FIRE) {
            select(keyCode);
        } else {
            onKeyOther(keyCode);
        }
    }

    // ------------------------------------------------------------------
    // Duplicate-confirm suppression
    // ------------------------------------------------------------------

    /**
     * One physical confirm press is not always one key event. S60 hands the
     * QWERTY Enter key to a Canvas as the selection key as well, so the E71
     * delivers keyPressed twice for it (10 or 13 AND -5), and other handsets
     * pair 13 with 10; MicroEmulator does the same thing from the AWT typed
     * character plus the mapped SELECT button. Every one of those is a real
     * keyPressed callback, so without a filter one Enter inserts two newlines
     * in the editor and opens a list row twice.
     *
     * <p>The state is static because the twin lands wherever the FIRST event
     * left the app: a list that opened a note gets its phantom select on the
     * brand-new screen, which no per-screen field could catch.
     *
     * <p>A twin is identified by three things together, and all three are
     * needed to tell it from a second real press: it carries the PARTNER code
     * of the press just accepted (the confirm codes pair up as -5 with 10 or
     * 13), it arrives inside DIFF_MS, and it is the FIRST such event - one
     * physical press yields at most one partner, so the suppression is spent
     * once it fires. Without the partner test a lone d-pad centre press
     * 200ms after an Enter would be read as Enter's twin and silently lost;
     * without spending it, every alternation of the two confirm keys inside
     * the window would be. SAME_MS separately drops an identical code
     * repeated inside 60ms, which is a bouncing key or a doubled dispatch
     * rather than fingers - a human cannot press 16 times a second, and a
     * held key produces keyRepeated, not keyPressed.
     */
    /**
     * Twin window. The partner event is generated by the platform inside the
     * same key dispatch, single-digit milliseconds after the first - so this
     * is already ~30x the gap it has to cover, and every millisecond above
     * that is time in which a DELIBERATE press of the other confirm key would
     * be mistaken for a twin and lost (the one failure this heuristic cannot
     * rule out; keeping the window small is what makes it unreachable in
     * practice).
     */
    private static final int DIFF_MS = 150;
    private static final int SAME_MS = 60;
    /** Code of the last confirm actually delivered; MIN_VALUE = none yet. */
    private static int lastSelCode = Integer.MIN_VALUE;
    private static long lastSelMs;
    /**
     * Whether the accepted press may still be followed by its partner event.
     * Spent by the first partner-shaped event, so a THIRD confirm code (a
     * number key the device maps to FIRE, which the raw canvases accept) can
     * never eat a whole Enter press that follows it.
     */
    private static boolean twinOwed;

    /** True when a and b are the two halves of one S60 confirm press. */
    private static boolean partners(int a, int b) {
        return a != b && isConfirmCode(a) && isConfirmCode(b);
    }

    /** The codes S60 pairs up for one confirm press: centre key, LF, CR. */
    private static boolean isConfirmCode(int c) {
        return c == Ui.MSK || c == 10 || c == 13;
    }

    /**
     * True when keyCode is a real confirm press rather than the twin of the one
     * just handled. Package-visible because the popups are raw Canvases with
     * their own keyPressed (UiMenu, UiDialog, UiSymbols) and Viewer/ImageView
     * are not UiScreens either: they all have to consult the SAME filter, or a
     * twin generated on one of them lands as a phantom confirm on whatever
     * screen it opened.
     */
    static boolean confirmAccepted(int keyCode) {
        long now = System.currentTimeMillis();
        long since = now - lastSelMs;
        // currentTimeMillis is the wall clock, and CLDC offers nothing
        // monotonic to use instead. A network time correction, a DST roll or
        // the user editing Date & Time can move it BACKWARDS, which makes
        // since negative -- smaller than either window, so every confirm
        // would be rejected, and since the reject path leaves lastSelMs alone
        // it would stay that way until the clock caught up again: Enter, the
        // d-pad centre and every dialog OK dead app-wide for as long as the
        // step. A backward jump is therefore read as "long ago": the press is
        // taken and lastSelMs re-bases on the new clock.
        if (since < 0L) {
            since = DIFF_MS;
        }
        // Only an accepted press advances the clock: with the timestamp bumped
        // by suppressed events too, a stream of twins could keep pushing the
        // window forward and starve a genuine second press.
        if (keyCode == lastSelCode) {
            // Same code twice is never the partner event, which by definition
            // carries the other code: it is a bounce or a doubled dispatch.
            if (since < SAME_MS) {
                return false;
            }
        } else if (twinOwed && since < DIFF_MS
                && partners(keyCode, lastSelCode)) {
            twinOwed = false;   // one press owes at most one partner
            return false;
        }
        lastSelCode = keyCode;
        lastSelMs = now;
        twinOwed = true;
        return true;
    }

    /** Runs onSelect unless this event is the twin of the one just handled. */
    private void select(int keyCode) {
        if (confirmAccepted(keyCode)) {
            onSelect();
        }
    }

    /** Auto-repeat for navigation and deletion only. */
    protected void keyRepeated(int keyCode) {
        // Soft keys, select and enter are deliberately absent: holding one down
        // would re-open a menu or re-fire a confirmation several times over.
        // Only scrolling and deleting are safe to run at the repeat rate.
        if (keyCode == 8 || keyCode == -8) {
            onBackspace();
            return;
        }
        if (Ui.isChar(keyCode)) {
            // Typed characters never auto-repeat: a key held a fraction too
            // long on the E71 thumbboard would stutter duplicates into the
            // editor buffer, and MIDP offers no way to tune the repeat rate.
            return;
        }
        int ga = gameAction(keyCode);
        if (ga == Canvas.UP) {
            onUp();
        } else if (ga == Canvas.DOWN) {
            onDown();
        } else if (ga == Canvas.LEFT) {
            onLeftArrow();
        } else if (ga == Canvas.RIGHT) {
            onRightArrow();
        }
    }

    /**
     * getGameAction that answers 0 (no action) instead of propagating. MIDP
     * lets an implementation reject a key code it has no mapping for, and one
     * stray key must not throw out of the LCDUI event thread mid-dispatch; 0
     * matches none of the Canvas action constants, so the caller routes it to
     * onKeyOther exactly as it would any other unmapped key.
     */
    private int gameAction(int keyCode) {
        try {
            return getGameAction(keyCode);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Overridable handlers (empty defaults)
    // ------------------------------------------------------------------

    // Empty bodies rather than abstract declarations: a typical screen cares
    // about three or four of these, and making them abstract would force every
    // subclass to stub out the rest, burying the handlers that do something.

    protected void onLeftSoft() {
    }

    protected void onRightSoft() {
    }

    protected void onSelect() {
    }

    protected void onUp() {
    }

    protected void onDown() {
    }

    protected void onLeftArrow() {
    }

    protected void onRightArrow() {
    }

    protected void onChar(char c) {
    }

    protected void onBackspace() {
    }

    protected void onKeyOther(int keyCode) {
    }
}
