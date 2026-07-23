package nok.ui;

import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import nok.NoksidianMIDlet;

/**
 * Themed modal dialog (replaces Alert / confirmations).
 *
 * <p>Kinds: {@link #OK}, {@link #YES_NO}, {@link #OK_CANCEL}. A centered
 * themed box ({@link Theme#calloutBg} fill, {@link Theme#focus} border) shows
 * the title + word-wrapped message; the soft-key bar carries the buttons.
 * FIRE is the positive action. On any button the owner's
 * {@link UiDialogOwner#dialogResult} is called (positive = OK / Yes); the
 * owner navigates. For a plain informational alert use {@link #info}.</p>
 *
 * <p>Hand-rolled rather than MIDP {@code Alert} because Alert is drawn by the
 * platform in system chrome that cannot be themed; CONTRACTS-UI.md mandates
 * replacing every Alert with this. Being an ordinary full-screen Canvas also
 * means it reads the same raw soft-key codes ({@link Ui#LSK} / {@link Ui#RSK} /
 * {@link Ui#MSK}) as every other screen in the app.</p>
 *
 * <p>The colors named above are the CONTRACTS-UI.md wording; what is actually
 * painted is a {@link Theme#bg2} panel with a {@link Theme#accent} border. In
 * the dark theme accent and focus are the same purple, so the border matches
 * the contract; the fill is the neutral panel grey rather than the callout's
 * purple tint. Every color is read from {@link Theme} at paint time, so a theme
 * switch is honored immediately.</p>
 *
 * <p>Shape: this class is only a handle - the real screen is the inner
 * DialogCanvas. One-shot and stateless between opens: every caller does
 * new UiDialog(...).show() per prompt and drops it, so there is never any state
 * to reset. paint() and keyPressed() run on the LCDUI thread, but the
 * constructor and show() are not always called from it (NoksidianMIDlet's
 * editNote worker thread opens one); nothing is synchronized because those
 * off-thread writes all complete before setCurrent hands the canvas over.</p>
 *
 * <p>The box does not scroll. A message taller than the screen is wrapped, the
 * box is clamped to the height available above the soft-key bar and the
 * overflowing lines are simply dropped - so keep dialog text short and put
 * anything long on a real screen.</p>
 */
public final class UiDialog {

    // Dialog kinds. They pick the soft-key captions and decide which side is
    // the positive answer: OK draws a single "OK" (either soft key dismisses it
    // positively), YES_NO is Yes/No and OK_CANCEL is OK/Cancel, left = positive.
    public static final int OK = 0, YES_NO = 1, OK_CANCEL = 2;

    private final NoksidianMIDlet m;
    private final DialogCanvas canvas;

    /**
     * Builds a dialog over back, which is both the screen dimmed for the
     * backdrop and the one returned to when there is no owner. Many callers
     * pass null and let the owner navigate; show() then captures whatever
     * screen is current instead. kind is one of OK / YES_NO / OK_CANCEL, a null
     * title or message is treated as empty, and a null owner falls back to
     * re-showing back.
     */
    public UiDialog(NoksidianMIDlet m, Displayable back, String title,
            String message, int kind, UiDialogOwner owner) {
        this.m = m;
        this.canvas = new DialogCanvas(back, title, message, kind, owner);
    }

    /** Make the dialog the current screen. */
    public void show() {
        if (m != null) {
            // Only the reference to the outgoing screen is taken here; the
            // dimmed image itself is built on the first paint, once the canvas
            // knows its real size.
            canvas.captureBack();   // snapshot the outgoing screen to dim it
            m.show(canvas);
        }
    }

    /** Informational OK dialog that simply re-shows the back screen. */
    public static void info(NoksidianMIDlet m, Displayable back, String title,
            String msg) {
        // back is handed over twice on purpose: once as the screen to dim
        // behind the box, and once to the owner that re-shows it on dismissal.
        UiDialog d = new UiDialog(m, back, title, msg, OK,
                new DismissOwner(m, back));
        d.show();
    }

    // ------------------------------------------------------------------

    /**
     * Owner used by {@link #info}: an informational box has nothing to decide,
     * so both answers just put the back screen up again. Behaviorally the same
     * as passing a null owner, which finish() also resolves to m.show(back);
     * this spells out the "owner that re-shows back" shape CONTRACTS-UI.md
     * describes for info().
     */
    private static final class DismissOwner implements UiDialogOwner {
        private final NoksidianMIDlet m;
        private final Displayable back;

        DismissOwner(NoksidianMIDlet m, Displayable back) {
            this.m = m;
            this.back = back;
        }

        public void dialogResult(boolean positive) {
            if (m != null) {
                m.show(back);
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * The screen itself. Inner (non-static) so it can reach the enclosing
     * MIDlet reference, both for the show() calls that hand control back and
     * for the Display lookup in captureBack.
     */
    private final class DialogCanvas extends Canvas {

        /**
         * Screen returned to when there is no owner, and the preferred one to
         * dim. Typed as Displayable rather than the UiScreen of
         * CONTRACTS-UI.md because Viewer and ImageView extend Canvas directly.
         * {@link Ui#dimSnapshot} renders exactly those two plus any UiScreen
         * and returns null for anything else (a popup over a popup), which
         * paint() handles with a solid fill. Often null: callers that navigate
         * from their own owner pass null and let show() capture the live
         * screen.
         */
        private final Displayable back;
        // Both normalized to "" in the constructor so paint() can call
        // length() / wrap() on them with no null checks per repaint.
        private final String title;
        private final String message;
        private final int kind;
        private final UiDialogOwner owner;
        private Displayable frameBack;  // screen to dim (explicit back or captured)
        // Keyed on canvas size and rebuilt only when that changes, so ordinary
        // repaints reuse it instead of re-rendering the whole back screen and
        // halving every one of its pixels again.
        private Image dimBack; // cached dimmed snapshot of the back screen

        DialogCanvas(Displayable back, String title, String message, int kind,
                UiDialogOwner owner) {
            this.back = back;
            this.frameBack = back;
            this.title = (title != null) ? title : "";
            this.message = (message != null) ? message : "";
            this.kind = kind;
            this.owner = owner;
            try {
                setFullScreenMode(true);
            } catch (Throwable t) {
            }
        }

        /**
         * Remembers the currently displayed screen so it can be dimmed behind
         * the box. Most callers pass back=null (the owner handles navigation),
         * so without this the dialog would have no frame to dim; the live
         * Display.getCurrent() at show() time is the outgoing screen.
         */
        void captureBack() {
            if (frameBack == null && m != null && m.disp != null) {
                frameBack = m.disp.getCurrent();
            }
        }

        protected void paint(Graphics g) {
            int w = getWidth();
            int h = getHeight();

            // Render the back screen's frame dimmed behind the box so the
            // dialog floats over dimmed context (CONTRACTS-UI.md). Built once
            // and reused across repaints; a null snapshot (no back, e.g. a
            // startup alert) falls back to a solid theme fill.
            if (dimBack == null || dimBack.getWidth() != w
                    || dimBack.getHeight() != h) {
                dimBack = Ui.dimSnapshot(frameBack, w, h);
            }
            if (dimBack != null) {
                g.drawImage(dimBack, 0, 0, Graphics.TOP | Graphics.LEFT);
            } else {
                g.setColor(Theme.bg);
                g.fillRect(0, 0, w, h);
            }

            Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                    Font.SIZE_SMALL);
            Font tf = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,
                    Font.SIZE_SMALL);
            int softH = f.getHeight() + 4;
            int pad = 6;

            int boxW = w - 24;
            if (boxW < 40) {
                boxW = w;
            }
            int innerW = boxW - pad * 2;

            Vector lines = Ui.wrap(message, f, innerW);
            int lineH = f.getHeight();
            int titleH = (title.length() > 0) ? tf.getHeight() + 4 : 0;
            int boxH = pad + titleH + lines.size() * lineH + pad;
            int maxBoxH = h - softH - 8;
            if (boxH > maxBoxH) {
                boxH = maxBoxH;
            }
            int boxX = (w - boxW) / 2;
            int boxY = (h - softH - boxH) / 2;
            if (boxY < 2) {
                boxY = 2;
            }

            // Centered card: bg2 panel, 1px accent border, ~8px rounded.
            g.setColor(Theme.bg2);
            g.fillRoundRect(boxX, boxY, boxW, boxH, 8, 8);
            g.setColor(Theme.accent);
            g.drawRoundRect(boxX, boxY, boxW - 1, boxH - 1, 8, 8);

            int ty = boxY + pad;
            if (titleH > 0) {
                g.setFont(tf);
                g.setColor(Theme.text);
                g.drawString(Ui.clip(title, tf, innerW), boxX + pad, ty,
                        Graphics.TOP | Graphics.LEFT);
                // 1px hairline separates the title from the message body.
                int dy = ty + tf.getHeight() + 1;
                g.setColor(Theme.hr);
                g.drawLine(boxX + pad, dy, boxX + boxW - pad, dy);
                ty += titleH;
            }
            g.setFont(f);
            g.setColor(Theme.text);
            int bottomLimit = boxY + boxH - pad;
            for (int i = 0; i < lines.size(); i++) {
                if (ty + lineH > bottomLimit) {
                    break;
                }
                String ln = (String) lines.elementAt(i);
                g.drawString(ln, boxX + pad, ty, Graphics.TOP | Graphics.LEFT);
                ty += lineH;
            }

            String left, right;
            if (kind == YES_NO) {
                left = "Yes";
                right = "No";
            } else if (kind == OK_CANCEL) {
                left = "OK";
                right = "Cancel";
            } else {
                left = "OK";
                right = null;
            }

            int by = h - softH;
            g.setColor(Theme.softBar);
            g.fillRect(0, by, w, softH);
            g.setColor(Theme.hr);
            g.drawLine(0, by, w, by);
            g.setFont(f);
            // Soft-key captions are dimText per CONTRACTS-UI.md (both sides).
            g.setColor(Theme.dimText);
            g.drawString(left, 4, by + 2, Graphics.TOP | Graphics.LEFT);
            if (right != null) {
                g.drawString(right, w - 4, by + 2,
                        Graphics.TOP | Graphics.RIGHT);
            }
        }

        protected void keyPressed(int key) {
            if (key == Ui.LSK) {
                finish(true);
                return;
            }
            // Filtered: one E71 Enter press arrives as two key events, and the
            // twin would otherwise confirm again on the screen this dialog
            // just handed control back to.
            if (key == Ui.MSK || key == 10 || key == 13) {
                if (UiScreen.confirmAccepted(key)) {
                    finish(true);
                }
                return;
            }
            if (key == Ui.RSK) {
                finish(kind == OK);
                return;
            }
            int ga;
            try {
                ga = getGameAction(key);
            } catch (Throwable t) {
                ga = 0;
            }
            if (ga == Canvas.FIRE) {
                if (UiScreen.confirmAccepted(key)) {
                    finish(true);
                }
            }
        }

        private void finish(boolean positive) {
            if (owner != null) {
                owner.dialogResult(positive);
            } else if (m != null) {
                m.show(back);
            }
        }
    }
}
