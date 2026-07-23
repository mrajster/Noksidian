package nok.ui;

import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import nok.NoksidianMIDlet;
import nok.core.MdList;
import nok.core.Path;

/**
 * Themed full-screen multi-line note editor (CONTRACTS-UI.md).
 *
 * Model: a StringBuffer of the whole note plus an int caret index. Edits are
 * O(n) buffer ops (fine for phone-sized notes). Text is word-wrapped to the
 * body width at Theme.bodySize; the wrap (a Vector of int[]{start,end} line
 * spans covering the whole buffer) and the caret's (line,col) are cached and
 * only recomputed when the text actually changes -- NOT on every keypress.
 *
 * A TimerTask toggles the blinking caret every ~500ms; the timer runs only
 * while the screen is visible (showNotify/hideNotify) and can also be stopped
 * explicitly via stop().
 *
 * The line spans are the invariant everything else rests on: they are
 * contiguous, cover the buffer exactly end to end, and the '\n' closing a
 * logical line belongs to that line's LAST visual segment. A buffer ending in
 * '\n' (or an empty one) also gets a final [len,len] span so the caret has
 * somewhere to sit past the last break. incrementalRewrap() may only produce
 * spans identical to those of a full rewrap(); wherever it cannot guarantee
 * that it returns false and the caller redoes the whole document.
 *
 * Threading: every on* handler and paintBody run on the MIDP event thread.
 * The blink TimerTask is the sole exception; it writes only caretOn and reads
 * the cached caret rect. Save and Cancel hand the text off to the MIDlet,
 * which does its IO on a worker thread, and stop the blink first so no timer
 * survives into a screen that is about to be replaced.
 *
 * nok.ui.Editor is a thin subclass kept only for constructor compatibility;
 * NoksidianMIDlet.editNote constructs UiEditor directly.
 */
public class UiEditor extends UiScreen implements UiMenuOwner, UiSymbolOwner {

    /**
     * Hard ceiling on editable note length, in characters. A note is live in
     * three copies at peak -- the StringBuffer, the cachedText snapshot and the
     * String handed to saveEdited -- which a ~2MB heap will not survive beyond
     * this size. NoksidianMIDlet.editNote gates on the same number before it
     * even constructs this screen; the check here is the last line of defense
     * for any other caller.
     */
    private static final int MAX_LEN = 200000;
    private static final int SBW = 6;         // right margin reserved for scrollbar
    private static final int PAD = 4;         // left/right body text inset

    /** Vault-relative path of the note; handed straight back to saveEdited. */
    private final String rel;
    private StringBuffer buf;
    /** Insertion point as an index into buf, always within [0, buf.length()]. */
    private int caret;
    /** Sticky "something was typed": gates the discard confirm on Cancel. */
    private boolean modified;
    /**
     * Latched by the constructor when the note exceeded MAX_LEN. The screen is
     * then inert -- every handler early-returns and paintBody draws only the
     * background -- while the "Too large" dialog hands the user to the
     * read-only viewer. buf is empty in this state, so nothing may be allowed
     * to reach doSave() and overwrite the real note with "".
     */
    private boolean tooLarge;

    // wrap cache
    /** Text changed since the last wrap; the next ensureLayout must re-run. */
    private boolean dirty;
    // Spans index cachedText, never buf: paint and caret geometry have to read
    // the same snapshot the spans were measured against.
    private Vector lines;                      // Vector of int[2] {start,end} into cachedText
    private String cachedText;                 // buf.toString() as of last rewrap
    /** Caret in wrap coordinates; meaningful only after ensureLayout(). */
    private int caretLine, caretCol;
    /** First visible line; clamped against the viewport in paintBody. */
    private int topLine;
    // Wrap inputs as of the last rewrap. Body width or Theme.bodySize changing
    // (the Settings font size is live) invalidates every span, not just the
    // caret, so both are checked before the cache is trusted.
    private int lastW = -1;
    private int lastSize = -1;
    private int lastCaret = -1;                // caret index at last computeCaretLoc
    // Incremental-rewrap hint: a single non-'\n' edit at editPos (NEW coords)
    // of length editDelta (+1 insert, -1 backspace). -1 => full rewrap needed.
    private int editPos = -1;
    private int editDelta;

    // caret blink
    private Timer blinkTimer;
    private boolean caretOn = true;
    // Cached caret pixel rect so the blink toggle repaints only a 3px sliver
    // instead of the whole screen. caretPh == 0 means the caret is off-screen.
    private int caretPx, caretPy, caretPh;

    /**
     * Builds the editor over content (null is treated as empty). An oversized
     * note is not an error: the buffer is left empty, tooLarge latches, and an
     * OK dialog routes the user to the read-only viewer instead.
     */
    public UiEditor(NoksidianMIDlet mid, String rel, String content) {
        super(mid, Path.name(rel));
        this.rel = rel;
        this.leftLabel = "Menu";
        this.rightLabel = "Save";
        String c = (content == null) ? "" : content;
        if (c.length() > MAX_LEN) {
            this.tooLarge = true;
            this.buf = new StringBuffer();
            final String fr = rel;
            UiDialogOwner ow = new UiDialogOwner() {
                public void dialogResult(boolean positive) {
                    m.openNote(fr);
                }
            };
            new UiDialog(mid, this, "Too large",
                    "note too large to edit", UiDialog.OK, ow).show();
            return;
        }
        this.buf = new StringBuffer(c);
        this.caret = 0;
        this.dirty = true;
    }

    /** Current full text of the buffer. */
    public String text() {
        return buf.toString();
    }

    /** Stop the caret-blink timer (safe to call repeatedly). */
    public void stop() {
        stopBlink();
    }

    // ---- caret blink -------------------------------------------------------

    // MIDP calls showNotify/hideNotify as this Canvas becomes and stops being
    // the current displayable -- including when a UiDialog or UiMenu covers it.
    // Binding the timer to visibility keeps a backgrounded editor from waking
    // the phone twice a second.

    protected void showNotify() {
        if (!tooLarge) {
            startBlink();
        }
    }

    protected void hideNotify() {
        stopBlink();
    }

    /**
     * Idempotent: the null check makes a repeated showNotify (or a showNotify
     * racing an explicit stop()) harmless. A second Timer would both double
     * the apparent toggle rate and leak its thread, since only the newest
     * reference is reachable for cancel().
     */
    private void startBlink() {
        if (blinkTimer != null) {
            return;
        }
        blinkTimer = new Timer();
        blinkTimer.schedule(new TimerTask() {
            public void run() {
                caretOn = !caretOn;
                if (caretPh > 0) {
                    repaint(caretPx - 1, caretPy, 3, caretPh);
                } else {
                    // No cached rect (caret scrolled out of view, or nothing
                    // painted yet): a full repaint is what re-establishes one.
                    repaint();
                }
            }
        }, 500, 500);
    }

    /**
     * Cancels the blink and leaves the caret in its ON phase, so the editor
     * always comes back visibly focused instead of frozen mid-blink.
     */
    private void stopBlink() {
        if (blinkTimer != null) {
            blinkTimer.cancel();
            blinkTimer = null;
        }
        caretOn = true;
    }

    // ---- editing -----------------------------------------------------------

    /**
     * Inserts c at the caret and records the incremental-rewrap hint. editPos
     * is the post-increment caret, i.e. the index just past the inserted
     * character in the NEW buffer's coordinates -- incrementalRewrap reasons
     * entirely in those and converts back to old indices itself.
     */
    private void insertChar(char c) {
        // dirty already set => a prior edit's layout has not run yet, so the
        // cached wrap is stale by more than this one char: force a full rewrap.
        boolean coalesced = dirty;
        buf.insert(caret, c);
        caret++;
        if (!coalesced && c != '\n') {
            editPos = caret;
            editDelta = 1;
        } else {
            editPos = -1; // '\n' or coalesced edit: fall back to full rewrap
        }
        modified = true;
        dirty = true;
        repaint();
    }

    /**
     * Inserts a whole string at the caret. Only used for edits that are not a
     * single keystroke (the Enter key's list marker, a picked symbol), so it
     * always clears the incremental-rewrap hint: incrementalRewrap is written
     * for a one-character delta and cannot describe this one.
     */
    private void insertString(String s) {
        if (s == null || s.length() == 0) {
            return;
        }
        buf.insert(caret, s);
        caret += s.length();
        editPos = -1;
        modified = true;
        dirty = true;
        repaint();
    }

    // Every key handler below no-ops while tooLarge: the buffer is empty and
    // the screen only exists until the dialog callback replaces it, so
    // accepting input would edit (and could later save) a note that was never
    // loaded. menuSelect needs no such guard because onLeftSoft is gated and
    // the menu can therefore never be opened in this state.

    protected void onChar(char c) {
        if (tooLarge) {
            return;
        }
        insertChar(c);
    }

    protected void onBackspace() {
        if (tooLarge) {
            return;
        }
        if (caret > 0) {
            // dirty already set => a prior edit's layout has not run yet, so the
            // cached wrap is stale by more than this one char: full rewrap.
            boolean coalesced = dirty;
            boolean wasNL = buf.charAt(caret - 1) == '\n';
            buf.delete(caret - 1, caret);
            caret--;
            if (!coalesced && !wasNL) {
                editPos = caret;
                editDelta = -1;
            } else {
                editPos = -1; // newline-merge or coalesced edit: full rewrap
            }
            modified = true;
            dirty = true;
            repaint();
        }
    }

    // ---- caret movement ----------------------------------------------------

    protected void onLeftArrow() {
        if (tooLarge) {
            return;
        }
        if (caret > 0) {
            caret--;
            repaint();
        }
    }

    protected void onRightArrow() {
        if (tooLarge) {
            return;
        }
        if (caret < buf.length()) {
            caret++;
            repaint();
        }
    }

    // UP/DOWN are the only movements that need geometry, so they force the
    // layout up to date before reading caretLine. LEFT/RIGHT walk raw buffer
    // indices and can stay ignorant of the wrap.

    protected void onUp() {
        if (tooLarge) {
            return;
        }
        ensureLayout();
        if (caretLine > 0) {
            moveToLine(caretLine - 1, caretCol);
            repaint();
        }
    }

    protected void onDown() {
        if (tooLarge) {
            return;
        }
        ensureLayout();
        if (caretLine < lines.size() - 1) {
            moveToLine(caretLine + 1, caretCol);
            repaint();
        }
    }

    /**
     * Puts the caret at column col of line target, clamped to that line. maxIdx
     * stops one index short of the span end on every line but the last: spans
     * are contiguous, so that index already belongs to the next line (it is
     * either this line's own '\n' or, after a soft wrap, the next line's first
     * character) and the caret would be drawn a row lower than intended. There
     * is no sticky goal column: col is read back from the caret each time, so
     * travelling through a short line permanently narrows it.
     */
    private void moveToLine(int target, int col) {
        int[] tl = (int[]) lines.elementAt(target);
        int maxIdx = (target == lines.size() - 1) ? tl[1] : (tl[1] - 1);
        int nc = tl[0] + col;
        if (nc > maxIdx) {
            nc = maxIdx;
        }
        if (nc < tl[0]) {
            nc = tl[0];
        }
        caret = nc;
    }

    // ---- soft keys / menu --------------------------------------------------

    /**
     * Opens the Menu. These strings are the commands' only identity -- UiMenu
     * reports the selected label and menuSelect matches it with equals() -- so
     * the two lists have to stay character-for-character in step. The item set
     * itself is fixed by CONTRACTS-UI.md.
     */
    protected void onLeftSoft() {
        if (tooLarge) {
            return;
        }
        String[] items = new String[5];
        items[0] = "Save";
        items[1] = "Cancel";
        items[2] = "Insert symbol";
        items[3] = "Word wrap? (n/a)";
        items[4] = "Go to top";
        new UiMenu(m, this, items, this).show();
    }

    /**
     * A device key the dispatcher could not place opens the symbol grid -
     * unless it is one of the S60 system keys below.
     *
     * <p>MIDP reserves negative codes for device-specific keys but assigns
     * none of them a name, so there is no portable constant for a Ctrl or Chr
     * key and no way to learn a handset's numbers except by pressing them.
     * Everything the editor actually uses - soft keys, the d-pad, Clear,
     * Enter and every printable character - has already been claimed and
     * returned by the time this runs, so what is left is a key with nothing
     * else to do here.
     *
     * <p>Real-E71 finding (v1.2.0): the QWERTY SHIFT key reaches a Canvas as
     * the S60 Edit key, code -50, sent before every capital letter - so the
     * original "any unclaimed key" rule popped the grid on Shift. The keys
     * this hook was hoping to catch, Ctrl and Chr, are consumed by the OS on
     * that handset and never reach Java at all; there the grid is opened from
     * the Menu. The hook stays for handsets that do deliver such a key.
     */
    protected void onKeyOther(int keyCode) {
        if (tooLarge) {
            return;
        }
        // Ctrl+I. Modifier keys themselves never reach Java here, but on
        // QWERTY E-series firmware a Ctrl+letter chord arrives as the ASCII
        // control character, and Ctrl+I (= 9) is the one convention with
        // history: J2ME Polish bound it as its "add symbol" key on the
        // E61/E63/E70 family. It cannot collide with typing - Ui.isChar
        // starts at 32, and the E71 has no Tab key to produce a 9 otherwise.
        if (keyCode == 9) {
            showSymbols();
            return;
        }
        if (keyCode >= 0 || isSystemKey(keyCode)) {
            return;
        }
        showSymbols();
    }

    /**
     * Keys with system meanings of their own, which must never raise a popup:
     * -10/-11 Send and End (call handling), -50 the S60 Edit key (the E71's
     * Shift, see onKeyOther), and -26/-36/-37 - camera and side-volume codes
     * on Sony Ericsson JP6+ handsets, excluded defensively for portability
     * (S60 itself delivers no volume or camera codes to a Canvas).
     */
    private static boolean isSystemKey(int k) {
        return k == -10 || k == -11 || k == -26
                || k == -36 || k == -37 || k == -50;
    }

    /**
     * Long-press SPACE opens the symbol grid. This is the one key gesture the
     * E71 actually has to give: Ctrl and Chr never reach Java on this handset,
     * Ctrl+letter chords died with the E61 generation, Shift is the Edit key
     * (-50, needed for capitals), and every other key types. Holding space is
     * free - UiScreen.keyRepeated deliberately never repeats printable
     * characters, so the hold previously did nothing at all.
     *
     * <p>The space the initial press typed is deleted first, so the gesture
     * leaves no residue in the note; onBackspace does that with the same
     * bookkeeping as the Clear key. Once the grid is current, further repeat
     * events from the still-held key land on it (which ignores them), so the
     * gesture cannot re-fire.
     */
    protected void keyRepeated(int keyCode) {
        if (!tooLarge && keyCode == ' ') {
            if (caret > 0 && buf.charAt(caret - 1) == ' ') {
                onBackspace();
            }
            showSymbols();
            return;
        }
        super.keyRepeated(keyCode);
    }

    /** Opens the symbol grid over this screen. */
    private void showSymbols() {
        new UiSymbols(m, this, this).show();
    }

    /**
     * UiSymbolOwner: the picked character is inserted exactly as if it had been
     * typed, so it coalesces with the surrounding text, marks the note modified
     * and takes the same incremental-rewrap path as any other keystroke.
     */
    public void symbolPicked(char c) {
        if (tooLarge) {
            return;
        }
        insertChar(c);
    }

    protected void onRightSoft() {
        if (tooLarge) {
            return;
        }
        doSave();
    }

    /**
     * FIRE / Select AND the Enter key both arrive here (UiScreen routes
     * keyCodes 10/13 to onSelect, and suppresses the duplicate event S60 sends
     * for one Enter press, so this runs exactly once per key). Inserts ONE
     * newline, carrying the current line's list or quote marker onto it the way
     * a desktop markdown editor does; Save stays on the right soft key + Menu.
     *
     * <p>Two rules, both from nok.core.MdList:
     * <ul>
     *   <li>a line that opened a list continues it - "- x" gives "- ",
     *       "3. x" gives "4. ", "- [x] x" gives "- [ ] ", "&gt; x" gives
     *       "&gt; " - and the marker is ordinary text the user can rub out
     *       with Clear if this line was not meant to be an item;</li>
     *   <li>Enter on an item that is still EMPTY ends the list instead of
     *       stacking another dead marker, which is the only way out of a list
     *       that does not involve deleting characters by hand.</li>
     * </ul>
     */
    protected void onSelect() {
        if (tooLarge) {
            return;
        }
        int ls = lineStart();
        int le = lineEnd();
        // Inside a fenced code block every marker is literal text the viewer
        // prints verbatim, so neither rule applies: Enter is just a newline.
        // (MdList is line-local by design and cannot see the fence itself.)
        if (inFence(ls)) {
            insertString("\n");
            return;
        }
        String line = span(ls, le);
        // Enter at the end of a bare marker: drop the marker, stay put. The
        // caret test keeps this to the case the user can actually see - with
        // text still to the right of the caret the line is being split, not
        // abandoned.
        if (caret == le && MdList.isBare(line)) {
            // Keep a CRLF note's '\r': deleting it too would leave this one
            // line ending in a bare LF while the rest of the file stays CRLF.
            int del = le;
            if (del > ls && buf.charAt(del - 1) == '\r') {
                del--;
            }
            buf.delete(ls, del);
            caret = ls;
            editPos = -1;
            modified = true;
            dirty = true;
            repaint();
            return;
        }
        insertString("\n" + MdList.nextPrefixAt(line, caret - ls));
    }

    /**
     * True when the line starting at ls sits inside a fenced code block, using
     * the SAME rules as Md.readFence so the editor and the viewer never
     * disagree about what is code:
     * <ul>
     *   <li>an opener is a line indented less than 4 columns (tab = 2, as in
     *       Md.indentCols) whose trimmed text starts with three or more of
     *       '`' or '~'; its fence character and run length are remembered;</li>
     *   <li>a closer is a line of NOTHING but that same character, at least as
     *       long as the opener. So a ``` line inside a ~~~ block - a code
     *       sample showing markdown - does not end it, an info string
     *       ("```java") never closes anything, and a shorter run does not
     *       close a longer fence.</li>
     * </ul>
     * A fence left open at the end of the note stays open, which is what
     * Md.readFence does when it runs off the last line.
     *
     * <p>Walks the raw buffer instead of splitting it into lines: this runs on
     * one keypress, and materializing a note's worth of Strings to answer a
     * yes/no question would be the most expensive thing Enter does. The scan
     * is O(caret), well inside the full re-wrap the same keypress forces.
     *
     * <p>Known gap, shared with nothing else in the app: Md.parse also treats
     * a 4-column-indented run as code, and skips %% comment blocks, and this
     * scan judges neither. Both need the block state of everything above the
     * line, which is a second parser; the cost of being wrong is one unwanted
     * marker the user can rub out with Clear.
     */
    private boolean inFence(int ls) {
        char fc = 0;        // fence character of the open block, 0 = none open
        int flen = 0;       // its run length
        int i = 0;
        while (i < ls) {
            // Column indent of this line, and the index of its first
            // non-blank character.
            int j = i;
            int sp = 0;
            while (j < ls) {
                char ic = buf.charAt(j);
                if (ic == ' ') {
                    sp++;
                } else if (ic == '\t') {
                    sp += 2;
                } else {
                    break;
                }
                j++;
            }
            // Length of the run of one fence character starting there, and
            // whether anything else follows it on the line (trailing blanks
            // do not count - Md compares the TRIMMED line).
            int end = j;
            while (end < ls && buf.charAt(end) != '\n') {
                end++;
            }
            int run = 0;
            char c = (j < end) ? buf.charAt(j) : 0;
            if (c == '`' || c == '~') {
                int k = j;
                while (k < end && buf.charAt(k) == c) {
                    k++;
                    run++;
                }
                // Trailing whitespace (and a CR from a CRLF note) is trimmed
                // away before Md looks at the line, so it must not count as
                // "something after the run" either.
                int t = end;
                while (t > k) {
                    char tc = buf.charAt(t - 1);
                    if (tc == ' ' || tc == '\t' || tc == '\r') {
                        t--;
                    } else {
                        break;
                    }
                }
                boolean bare = (k >= t);
                if (fc != 0) {
                    if (c == fc && run >= flen && bare) {
                        fc = 0;
                        flen = 0;
                    }
                } else if (run >= 3 && sp < 4) {
                    fc = c;
                    flen = run;
                }
            }
            // Skip to the start of the next line. A buffer that does not end
            // in '\n' just ends the loop, since i then lands on ls itself.
            i = end + 1;
        }
        return fc != 0;
    }

    /** Index of the first character of the logical line holding the caret. */
    private int lineStart() {
        int i = caret;
        while (i > 0 && buf.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    /** Index of the '\n' ending the caret's logical line, or the buffer end. */
    private int lineEnd() {
        int n = buf.length();
        int i = caret;
        while (i < n && buf.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /**
     * buf[from,to) as a String. Walks the characters rather than calling
     * buf.toString().substring: the buffer holds the whole note (up to
     * MAX_LEN), and copying all of it to read one line would be a 200k-char
     * allocation on every Enter, on a ~2MB heap.
     */
    private String span(int from, int to) {
        StringBuffer sb = new StringBuffer(to - from);
        for (int i = from; i < to; i++) {
            sb.append(buf.charAt(i));
        }
        return sb.toString();
    }

    public void menuSelect(String item, int index) {
        if ("Save".equals(item)) {
            doSave();
        } else if ("Cancel".equals(item)) {
            doCancel();
        } else if ("Insert symbol".equals(item)) {
            showSymbols();
        } else if ("Word wrap? (n/a)".equals(item)) {
            // Placeholder command (contract item): wrap is always on here.
            UiDialog.info(m, this, "Word wrap", "Word wrap is always on.");
        } else if ("Go to top".equals(item)) {
            caret = 0;
            topLine = 0;
            repaint();
        }
    }

    /**
     * Hands the text to the MIDlet, which writes, syncs and reopens the note
     * from a worker thread. The blink stops first: this screen is about to be
     * replaced and its Timer must not repaint a Canvas that is off-display.
     */
    private void doSave() {
        stopBlink();
        m.saveEdited(rel, text());
    }

    /**
     * Leaves the editor, confirming first only when there is something to
     * lose. The "No" branch has to re-show this screen explicitly, because the
     * UiDialog took over as the current displayable when it appeared.
     */
    private void doCancel() {
        if (!modified) {
            stopBlink();
            m.back();
            return;
        }
        UiDialogOwner ow = new UiDialogOwner() {
            public void dialogResult(boolean positive) {
                if (positive) {
                    stopBlink();
                    m.back();
                } else {
                    m.show(UiEditor.this);
                }
            }
        };
        new UiDialog(m, this, "Discard changes?",
                "You have unsaved changes. Discard them?",
                UiDialog.YES_NO, ow).show();
    }

    // ---- layout ------------------------------------------------------------

    /**
     * Looked up rather than cached in a field: Theme.bodySize changes under us
     * when the user picks a different font size in Settings, and MIDP hands
     * back a shared Font instance for a given triple, so this is cheap enough
     * to call on every layout and every paint.
     */
    private Font bodyFont() {
        return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Theme.bodySize);
    }

    /**
     * Brings the wrap cache and the caret's (line,col) up to date doing the
     * least work that is still exact. Called from paintBody and from the
     * UP/DOWN handlers, which need caretLine before they can move; everything
     * else just marks dirty and lets the next paint pay for it.
     */
    private void ensureLayout() {
        Font f = bodyFont();
        // Wrap width leaves a PAD inset on the left, a PAD inset on the right,
        // and the scrollbar margin (SBW) beyond that.
        int w = bodyW() - SBW - PAD * 2;
        if (w < 8) {
            w = 8;
        }
        boolean rewrapped = false;
        boolean sizeChanged = (w != lastW || Theme.bodySize != lastSize);
        if (dirty || sizeChanged) {
            boolean done = false;
            // Single-char, same-width/font edit: re-wrap only the edited
            // paragraph. incrementalRewrap() returns false for anything it
            // cannot handle exactly, and we then do the proven full rewrap.
            if (dirty && !sizeChanged && editPos >= 0
                    && lines != null && lines.size() > 0) {
                done = incrementalRewrap(f, w);
            }
            if (!done) {
                rewrap(f, w);
            }
            lastW = w;
            lastSize = Theme.bodySize;
            dirty = false;
            editPos = -1;
            rewrapped = true;
            lastCaret = -1; // lines replaced: force a caret recompute
        }
        if (rewrapped || caret != lastCaret) {
            computeCaretLoc();
            lastCaret = caret;
        }
    }

    /**
     * Re-wraps only the paragraph touched by a single non-'\n' edit (editPos /
     * editDelta), then shifts the following line spans by editDelta -- so a
     * keystroke costs O(edited paragraph) instead of O(document). Returns false
     * (caller falls back to a full {@link #rewrap}) whenever the incremental
     * assumptions do not hold exactly, so the result is always identical to a
     * full rewrap.
     */
    private boolean incrementalRewrap(Font f, int w) {
        String newText = buf.toString();
        int newLen = newText.length();
        int oldLen = newLen - editDelta;
        // Guard: `lines` must be a valid full wrap of the OLD text (its last
        // span ends at oldLen). If not (e.g. two edits coalesced before a
        // layout), bail to a full rewrap.
        int[] lastOld = (int[]) lines.elementAt(lines.size() - 1);
        if (lastOld[1] != oldLen) {
            return false;
        }
        // The paragraph is the text between the enclosing newlines. rewrap()
        // wraps one logical line at a time and never carries width across a
        // '\n', so re-wrapping this range alone reproduces exactly what a full
        // rewrap would produce for it.
        int pStart = newText.lastIndexOf('\n', editPos - 1) + 1;
        int pEnd = newText.indexOf('\n', editPos);
        boolean hasTrailingNL = (pEnd >= 0);
        if (!hasTrailingNL) {
            pEnd = newLen;
        }
        if (pStart >= pEnd) {
            return false; // empty paragraph: let the full rewrap handle it
        }
        int oldPEnd = pEnd - editDelta;
        int oldParaSpanEnd = oldPEnd + (hasTrailingNL ? 1 : 0);
        // Old spans of this paragraph: from the span starting at pStart to the
        // span ending at oldParaSpanEnd (all spans are contiguous).
        int startIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            int[] ln = (int[]) lines.elementAt(i);
            if (ln[0] == pStart) {
                startIdx = i;
                break;
            }
            if (ln[0] > pStart) {
                return false;
            }
        }
        if (startIdx < 0) {
            return false;
        }
        int endIdx = -1;
        for (int i = startIdx; i < lines.size(); i++) {
            int[] ln = (int[]) lines.elementAt(i);
            if (ln[1] == oldParaSpanEnd) {
                endIdx = i;
                break;
            }
            if (ln[1] > oldParaSpanEnd) {
                return false;
            }
        }
        if (endIdx < 0) {
            return false;
        }
        // Re-wrap the edited paragraph over the NEW text (one logical line),
        // mirroring rewrap()'s inner loop including the trailing-'\n' include.
        Vector newSpans = new Vector();
        int nl = hasTrailingNL ? pEnd : -1;
        int seg = pStart;
        while (seg < pEnd) {
            int brk = wrapPoint(newText, seg, pEnd, f, w);
            int e = brk;
            if (brk >= pEnd && nl >= 0) {
                e = nl + 1;
            }
            int[] a = new int[2];
            a[0] = seg;
            a[1] = e;
            newSpans.addElement(a);
            seg = brk;
        }
        if (newSpans.size() == 0) {
            return false;
        }
        // Commit: swap the paragraph's spans, shift the following spans.
        cachedText = newText;
        for (int i = endIdx; i >= startIdx; i--) {
            lines.removeElementAt(i);
        }
        for (int i = 0; i < newSpans.size(); i++) {
            lines.insertElementAt(newSpans.elementAt(i), startIdx + i);
        }
        // Everything after the edited paragraph keeps its wrapping -- the edit
        // was a single non-'\n' character confined to this paragraph, so later
        // paragraphs only shift by editDelta.
        int shiftFrom = startIdx + newSpans.size();
        if (editDelta != 0) {
            for (int i = shiftFrom; i < lines.size(); i++) {
                int[] ln = (int[]) lines.elementAt(i);
                ln[0] += editDelta;
                ln[1] += editDelta;
            }
        }
        return true;
    }

    /**
     * Full re-wrap of the buffer into visual line spans. Empty logical lines
     * still get a span of their own so a run of blank lines occupies real rows,
     * and a buffer that ends in '\n' (or is empty) gets a final [len,len] span
     * so the caret has a line to sit on past the last break.
     */
    private void rewrap(Font f, int w) {
        lines = new Vector();
        cachedText = buf.toString();
        String t = cachedText;
        int len = t.length();
        int i = 0;
        while (i < len) {
            int nl = t.indexOf('\n', i);
            int logEnd = (nl < 0) ? len : nl;
            if (i == logEnd) {
                addLine(i, nl + 1);      // empty logical line (nl>=0 here)
                i = nl + 1;
            } else {
                int seg = i;
                while (seg < logEnd) {
                    int brk = wrapPoint(t, seg, logEnd, f, w);
                    int e = brk;
                    if (brk >= logEnd && nl >= 0) {
                        e = nl + 1;      // include '\n' in the last visual segment
                    }
                    addLine(seg, e);
                    seg = brk;
                }
                i = (nl < 0) ? logEnd : (nl + 1);
            }
        }
        if (len == 0 || t.charAt(len - 1) == '\n') {
            addLine(len, len);           // trailing empty line for the caret
        }
    }

    /**
     * First index of the next line when t[start,limit) is wrapped to w pixels.
     * Breaks just after the last space so the space stays on the ending line;
     * a single word wider than the whole body is hard-broken mid-word. The
     * i > start test guarantees at least one character per line, so a glyph
     * wider than w cannot spin the callers forever.
     */
    private int wrapPoint(String t, int start, int limit, Font f, int w) {
        int lastSpace = -1;
        int width = 0;
        int i = start;
        while (i < limit) {
            char c = t.charAt(i);
            int cw = f.charWidth(c);
            if (width + cw > w && i > start) {
                if (lastSpace >= start) {
                    return lastSpace + 1;
                }
                return i;
            }
            width += cw;
            if (c == ' ') {
                lastSpace = i;
            }
            i++;
        }
        return limit;
    }

    private void addLine(int s, int e) {
        int[] a = new int[2];
        a[0] = s;
        a[1] = e;
        lines.addElement(a);
    }

    /**
     * Maps caret (a buffer index) onto caretLine/caretCol. The first span whose
     * end strictly exceeds the caret owns it; a caret sitting at the very end
     * of the buffer matches no span at all, which is why the search starts out
     * already pointing at the last line.
     */
    private void computeCaretLoc() {
        int c = caret;
        int n = lines.size();
        int line = n - 1;
        for (int i = 0; i < n; i++) {
            int[] ln = (int[]) lines.elementAt(i);
            if (c < ln[1]) {
                line = i;
                break;
            }
        }
        if (line < 0) {
            line = 0;
        }
        caretLine = line;
        int[] cl = (int[]) lines.elementAt(caretLine);
        caretCol = c - cl[0];
        if (caretCol < 0) {
            caretCol = 0;
        }
    }

    // Currently has no callers: paintBody and cacheCaretRect deliberately
    // inline this same trim so no String is allocated per line per frame.
    // Kept as the readable reference for what a line span's visible text is.
    /** Visible text of line i (its buffer span minus a trailing '\n'). */
    private String lineText(int[] ln) {
        int e = ln[1];
        if (e > ln[0] && cachedText.charAt(e - 1) == '\n') {
            e--;
        }
        return cachedText.substring(ln[0], e);
    }

    // ---- paint -------------------------------------------------------------

    /**
     * Paints the visible window of lines, the current-line tint, the caret and
     * the scrollbar. Scrolling is derived here rather than in the key handlers:
     * topLine is chased to wherever the caret ended up and then clamped, so no
     * movement or editing handler has to reason about it. (The "Go to top"
     * menu command is the one other writer of topLine, and it only resets it
     * to 0 alongside the caret.)
     */
    protected void paintBody(Graphics g, int cx, int cy, int cw, int ch) {
        g.setColor(Theme.bg);
        g.fillRect(cx, cy, cw, ch);
        if (tooLarge) {
            return;
        }
        ensureLayout();
        Font f = bodyFont();
        g.setFont(f);
        int lh = f.getHeight();
        // Whole rows only: a row that would not fit inside the body height ch
        // is not counted as visible, so no line is ever drawn half-clipped.
        int visible = ch / lh;
        if (visible < 1) {
            visible = 1;
        }
        int total = lines.size();

        // keep caret visible, then clamp
        if (caretLine < topLine) {
            topLine = caretLine;
        } else if (caretLine >= topLine + visible) {
            topLine = caretLine - visible + 1;
        }
        int maxTop = total - visible;
        if (maxTop < 0) {
            maxTop = 0;
        }
        if (topLine > maxTop) {
            topLine = maxTop;
        }
        if (topLine < 0) {
            topLine = 0;
        }

        // subtle current-line tint (just above black)
        if (caretLine >= topLine && caretLine < topLine + visible) {
            g.setColor(Theme.bg2);
            g.fillRect(cx, cy + (caretLine - topLine) * lh, cw, lh);
        }

        int last = topLine + visible;
        if (last > total) {
            last = total;
        }
        // Clip-cull the line loop so a 3px blink repaint only touches the
        // caret's line, not all ~15-20 visible lines.
        int clipTop = g.getClipY();
        int clipBot = clipTop + g.getClipHeight();
        int y = cy;
        for (int i = topLine; i < last; i++) {
            if (y + lh <= clipTop || y >= clipBot) {
                y += lh;
                continue;
            }
            int[] ln = (int[]) lines.elementAt(i);
            int e = ln[1];
            if (e > ln[0] && cachedText.charAt(e - 1) == '\n') {
                e--;
            }
            g.setColor(Theme.text);
            g.drawSubstring(cachedText, ln[0], e - ln[0], cx + PAD, y,
                    Graphics.TOP | Graphics.LEFT);
            y += lh;
        }

        // Cache the caret pixel rect unconditionally (regardless of caretOn or
        // the clip), so the next blink toggle can repaint just a 3px sliver.
        cacheCaretRect(f, cx, cy, lh, visible);

        drawCaret(g);

        if (total > visible) {
            Ui.drawScrollbar(g, cx + cw - SBW, cy, ch, total, visible, topLine);
        }
    }

    /** Recomputes caretPx/caretPy/caretPh for the current caret + scroll. */
    private void cacheCaretRect(Font f, int cx, int cy, int lh, int visible) {
        if (caretLine < topLine || caretLine >= topLine + visible) {
            caretPh = 0;
            return;
        }
        int[] cl = (int[]) lines.elementAt(caretLine);
        int e = cl[1];
        if (e > cl[0] && cachedText.charAt(e - 1) == '\n') {
            e--;
        }
        // caretCol can point past the drawable text when the caret sits on the
        // line's own trailing '\n'; clamp it so substringWidth stays in range.
        int col = caretCol;
        int max = e - cl[0];
        if (col > max) {
            col = max;
        }
        if (col < 0) {
            col = 0;
        }
        caretPx = cx + PAD + f.substringWidth(cachedText, cl[0], col);
        caretPy = cy + (caretLine - topLine) * lh;
        caretPh = lh;
    }

    private void drawCaret(Graphics g) {
        if (!caretOn || caretPh <= 0) {
            return;
        }
        g.setColor(Theme.accent);
        g.drawLine(caretPx, caretPy, caretPx, caretPy + caretPh - 1);
    }
}
