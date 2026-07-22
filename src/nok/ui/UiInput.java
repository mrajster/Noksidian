package nok.ui;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import nok.NoksidianMIDlet;

/**
 * Themed single-line text input (replaces name/search/token/password
 * prompts). Extends {@link UiScreen} so it gets the standard themed title bar
 * and soft-key bar; the body draws a prompt line plus a bordered field
 * ({@link Theme#codeBg}) with the current value and a blinking caret.
 *
 * <p>Typed chars insert at the caret; backspace deletes; LEFT/RIGHT move the
 * caret; the field scrolls horizontally when the value is wider than it.
 * masked mode renders each char as '*' while keeping the real value.
 * Right-soft / FIRE / Enter confirm; left-soft cancels.</p>
 *
 * <p>It exists because the native MIDP TextBox is a separate OS-drawn
 * editor: it ignores Theme entirely, arrives wrapped in Symbian's own
 * chrome, and cannot be blended into the app's look. Routing every
 * name/search/token/password prompt through this Canvas keeps the whole UI
 * on one theme.</p>
 *
 * <p>Threading: key handling and painting all run on the MIDP UI thread.
 * The blink TimerTask is the sole exception, and it touches only caretOn
 * before calling repaint() -- both safe off-thread. The timer is created in
 * showNotify and cancelled in hideNotify, so it stops shortly after the
 * screen leaves the display; a task already running when hideNotify fires
 * can still repaint once, which is harmless on a Canvas no longer current.</p>
 *
 * <p>Lifecycle: instances are single-use and cheap. Callers show a fresh
 * UiInput per prompt rather than reusing one (NoksidianMIDlet's unlock
 * retry relies on that to start each attempt from an empty field) and drop
 * it once inputOk/inputCancel fires.</p>
 */
public final class UiInput extends UiScreen {

    /** Label line above the field; "" (never null) means no label at all. */
    private final String prompt;

    /**
     * Password mode. Only the rendering changes: buf and value() keep the
     * real characters, so the caller still gets plaintext back.
     */
    private final boolean masked;

    /**
     * Answer callback. Every current caller passes a real owner; the null
     * guards on each dispatch are only belt and braces.
     */
    private final UiInputOwner owner;

    /**
     * The real (unmasked) value, edited in place on every keystroke. CLDC
     * 1.1 has no StringBuilder, so StringBuffer is the only growable option.
     */
    private final StringBuffer buf;

    /**
     * Insertion point, 0..buf.length(), counted between characters rather
     * than on one. Starts at the end so an initial value (a rename, say) can
     * be appended to or backspaced immediately.
     */
    private int caret;

    /**
     * Adjusted only inside paintBody, which is where the Font metrics needed
     * to clamp it are already in hand. The key handlers just move the caret
     * and let the next paint chase it back into view.
     */
    private int hscroll;      // horizontal pixel scroll of the field text

    /** Blink phase: written by the timer thread, read only by paintBody. */
    private boolean caretOn;

    /** Non-null only while this screen is the current Displayable. */
    private Timer timer;

    /**
     * The soft keys are fixed to Cancel/OK here instead of being left to the
     * caller, so every prompt in the app answers the same two keys.
     */
    public UiInput(NoksidianMIDlet m, String title, String prompt,
            String initial, boolean masked, UiInputOwner owner) {
        super(m, title);
        this.prompt = (prompt != null) ? prompt : "";
        this.masked = masked;
        this.owner = owner;
        this.buf = new StringBuffer(initial != null ? initial : "");
        this.caret = this.buf.length();
        this.caretOn = true;
        this.leftLabel = "Cancel";
        this.rightLabel = "OK";
    }

    public String value() {
        return buf.toString();
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    protected void paintBody(Graphics g, int cx, int cy, int cw, int ch) {
        // pf paints the prompt, ff the field text. Font.getFont returns shared
        // instances, so re-fetching per paint allocates nothing and holding
        // them in fields would buy no heap back.
        // The field font being monospace matters to the scroll math below: it
        // makes the sum of charWidth() over a prefix equal substringWidth() of
        // that same prefix, which is what keeps the first-visible-glyph scan in
        // step with the caret pixel offset.
        Font pf = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Font.SIZE_SMALL);
        Font ff = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN,
                Font.SIZE_SMALL);

        int y = cy + 6;
        if (prompt.length() > 0) {
            // Label above the field, muted.
            g.setFont(pf);
            g.setColor(Theme.dimText);
            g.drawString(Ui.clip(prompt, pf, cw - 12), cx + 6, y,
                    Graphics.TOP | Graphics.LEFT);
            y += pf.getHeight() + 6;
        }

        // Field box geometry: 4px of breathing room above and below the
        // glyphs, 6px gutters left and right. The floors on fw (and textW
        // below) are only there so a pathologically narrow body cannot hand a
        // negative width to fillRoundRect/setClip; on the E71's 320x240 they
        // never bite.
        int fh = ff.getHeight() + 8;
        int fx = cx + 6;
        int fw = cw - 12;
        if (fw < 8) {
            fw = 8;
        }
        // Field box: codeBg fill, rounded, 1px accent border (active edit).
        g.setColor(Theme.codeBg);
        g.fillRoundRect(fx, y, fw, fh, 6, 6);
        g.setColor(Theme.accent);
        g.drawRoundRect(fx, y, fw - 1, fh - 1, 6, 6);

        int textX = fx + 5;
        int textW = fw - 10;
        if (textW < 4) {
            textW = 4;
        }
        int textY = y + (fh - ff.getHeight()) / 2;

        String disp = display();
        int caretPx = ff.substringWidth(disp, 0, caret);

        // keep caret visible within the field (reserve 1px so the caret
        // line itself is not clipped when it sits at the right edge)
        if (caretPx - hscroll > textW - 1) {
            hscroll = caretPx - (textW - 1);
        }
        if (caretPx - hscroll < 0) {
            hscroll = caretPx;
        }
        // Defensive only: caretPx is never negative, so neither branch above
        // can leave hscroll below zero. Kept so a future change to the two
        // conditions cannot silently start scrolling the text off to the right.
        if (hscroll < 0) {
            hscroll = 0;
        }

        // MIDP Graphics has no clip stack, so the incoming clip (UiScreen.
        // paint narrowed it to the body rect) is saved by hand here and put
        // back at the end of the method. Without the inner clip a long value
        // would draw straight over the rounded border and out into the body.
        int ox = g.getClipX();
        int oy = g.getClipY();
        int ow = g.getClipWidth();
        int oh = g.getClipHeight();
        g.setClip(textX, y, textW, fh);
        g.setFont(ff);
        g.setColor(Theme.codeText);
        // Start at the first character whose left edge is at/after the scroll
        // offset so no half-clipped leading glyph sticks to the left border
        // (B19). The skipped chars are fully off-screen to the left anyway.
        int fv = 0;
        int fvPx = 0;
        while (fv < disp.length() && fvPx < hscroll) {
            fvPx += ff.charWidth(disp.charAt(fv));
            fv++;
        }
        g.drawString(disp.substring(fv), textX + fvPx - hscroll, textY,
                Graphics.TOP | Graphics.LEFT);
        if (caretOn) {
            // Drawn 4px short of the box top and bottom so the caret reads as
            // a caret rather than merging into the rounded border.
            int cxp = textX - hscroll + caretPx;
            g.setColor(Theme.accent);
            g.drawLine(cxp, y + 4, cxp, y + fh - 5);
        }
        g.setClip(ox, oy, ow, oh);
    }

    /** U+2022 bullet for masked chars; a code point keeps the source ASCII. */
    private static final char BULLET = (char) 0x2022;

    /**
     * The string actually painted. A bullet run of exactly buf.length()
     * keeps every caret index valid against the displayed text too, so the
     * pixel math above needs no separate masked/unmasked path. The per-paint
     * allocation is affordable because a masked field only ever holds a
     * password.
     */
    private String display() {
        if (!masked) {
            return buf.toString();
        }
        int n = buf.length();
        StringBuffer sb = new StringBuffer(n);
        for (int i = 0; i < n; i++) {
            sb.append(BULLET);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Keys (dispatched by UiScreen)
    // ------------------------------------------------------------------

    protected void onLeftSoft() {
        if (owner != null) {
            owner.inputCancel();
        }
    }

    protected void onRightSoft() {
        commit();
    }

    protected void onSelect() {
        commit();
    }

    /**
     * No length cap and no character filtering: this screen accepts anything
     * the keypad emits, and the owner validates in inputOk (a file name, a
     * PAT and a password have nothing useful in common to enforce here).
     */
    protected void onChar(char c) {
        buf.insert(caret, c);
        caret++;
        repaint();
    }

    protected void onBackspace() {
        if (caret > 0) {
            buf.deleteCharAt(caret - 1);
            caret--;
            repaint();
        }
    }

    protected void onLeftArrow() {
        if (caret > 0) {
            caret--;
            repaint();
        }
    }

    protected void onRightArrow() {
        if (caret < buf.length()) {
            caret++;
            repaint();
        }
    }

    protected void onKeyOther(int keyCode) {
        if (keyCode == 10 || keyCode == 13) {   // Enter
            commit();
        }
    }

    private void commit() {
        if (owner != null) {
            owner.inputOk(value());
        }
    }

    // ------------------------------------------------------------------
    // Blinking caret
    // ------------------------------------------------------------------

    protected void showNotify() {
        super.showNotify();
        caretOn = true;
        stopBlink();
        timer = new Timer();
        timer.schedule(new BlinkTask(), 500, 500);
    }

    protected void hideNotify() {
        stopBlink();
        super.hideNotify();
    }

    private void stopBlink() {
        if (timer != null) {
            try {
                timer.cancel();
            } catch (Throwable t) {
            }
            timer = null;
        }
    }

    private final class BlinkTask extends TimerTask {
        public void run() {
            caretOn = !caretOn;
            repaint();
        }
    }
}
