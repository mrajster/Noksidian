package nok.ui;

import java.util.Calendar;
import java.util.Vector;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import nok.NoksidianMIDlet;
import nok.core.Path;
import nok.sys.Config;

/**
 * Themed vault directory listing (the home screen), fully Canvas-drawn so it
 * honors {@link Theme}. Rows: ".." (MARK_UP unless at the vault root), folders
 * (MARK_FOLDER, trailing '/'), then markdown / image / txt files (every type
 * when ui.showall=1). MARK_NOTE / MARK_IMAGE glyphs mark files; the encryption
 * descriptor "_vault.nkv" is never listed.
 *
 * <p>Right-soft "Open" (or FIRE) opens the selection: a folder navigates in, a
 * markdown note opens the viewer, an image opens the image view. Left-soft
 * "Menu" opens a {@link UiMenu} of vault commands. A sync/status line is drawn
 * under the title. File mutations (new note, folder, rename, delete, today)
 * run on a background thread so the paint path never blocks.</p>
 *
 * <p>Threading model: paint runs on the LCDUI event thread; everything that
 * touches JSR-75 (listing, create, rename, delete) runs on a plain worker
 * Thread. The state they share is the row model and the status line; the row
 * model is swapped whole rather than mutated in place, so a half-built list
 * can never be painted, and paint snapshots status into a local before
 * testing it. Every worker body catches Throwable rather than IOException: an
 * uncaught exception on a bare thread takes the whole MIDlet down, and
 * Symbian's JSR-75 throws unchecked IllegalArgumentException for names it
 * dislikes (emoji, leading dot) instead of a checked IOException.</p>
 *
 * <p>Note that no {@link UiList} instance is used here even though one exists:
 * the rows are hand-painted so the status ticker can share the body rect, and
 * because UiList.drawGlyph paints every mark in one color while the folder
 * glyph here has to be Theme.wikilink. Only the MARK_* constants are borrowed
 * from UiList.</p>
 */
public final class Library extends UiScreen
        implements UiListOwner, UiMenuOwner {

    // Left-soft "Menu" items, in the order fixed by CONTRACTS-UI.md. Dispatch
    // in menuSelect is by string, not by index, so this array is the single
    // place the wording lives.
    private static final String[] MENU = {
        "New note", "New folder", "Rename", "Delete", "Search",
        "Today", "Sync now", "Settings", "About", "Exit"
    };

    // Prompt modes. All four commands reuse one UiInput screen, so the mode is
    // what tells handleName which action the returned text belongs to. These
    // are NOT indices into MENU.
    private static final int P_NEW_NOTE = 0;
    private static final int P_NEW_FOLDER = 1;
    private static final int P_RENAME = 2;
    private static final int P_SEARCH = 3;

    /** Vault-relative directory this instance lists; "" is the vault root. */
    private final String dirRel;
    /**
     * Cached dirRel.length() == 0. Suppresses the ".." row, makes listBack a
     * no-op, and gates the "noksidian/" filter (that dir only exists at the
     * vault root, so deeper folders must not hide a note of that name).
     */
    private final boolean atRoot;

    // Two parallel Vectors rather than a Vector of row objects: CLDC 1.1 has
    // no generics and one small object per row is real heap pressure in ~2MB,
    // so the mark is a boxed Integer alongside the label instead.
    /** Row model (swapped atomically by refresh so paint never sees a gap). */
    private Vector labels = new Vector();   // String display names
    private Vector marks = new Vector();    // Integer MARK_* per row

    /** Index of the highlighted row into labels; clamped by refresh/paint. */
    private int sel;
    /** Vertical list offset in PIXELS (not rows); 0 at the top of the list. */
    private int scroll;
    /** Ticker text under the title, or null for no ticker band at all. */
    private String status;

    /** Target of a pending rename prompt. */
    private String renameRel;
    /**
     * Whether renameRel was a markdown file, remembered because the prompt
     * shows a bare name: an extensionless reply then gets ".md" re-appended so
     * renaming "Notes.md" to "Ideas" does not silently drop it from the vault.
     */
    private boolean renameWasMd;

    public Library(NoksidianMIDlet m, String dirRel) {
        super(m, (dirRel == null || dirRel.length() == 0) ? "Noksidian"
                : Path.name(dirRel));
        this.dirRel = (dirRel == null) ? "" : dirRel;
        this.atRoot = this.dirRel.length() == 0;
        this.leftLabel = "Menu";
        this.rightLabel = "Open";
        // The first listing of a directory can pull a JSR-75 permission prompt
        // and hit a slow memory card, so it must not run on the LCDUI thread
        // that is about to paint this screen. The screen therefore appears
        // empty for a moment and fills in when refresh() repaints.
        new Thread(new Runnable() {
            public void run() {
                refresh();
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    // Does blocking file I/O, so it must never be reached from the LCDUI
    // thread: the constructor and every mutation below call it from a worker.
    // It publishes the new model by plain field assignment rather than under a
    // lock, so paint is never blocked waiting on a slow memory card.
    /** Re-reads the directory into a fresh row model. */
    public void refresh() {
        Vector labs = new Vector();
        Vector mks = new Vector();
        if (!atRoot) {
            labs.addElement("..");
            mks.addElement(new Integer(UiList.MARK_UP));
        }
        if (m.files != null) {
            boolean showAll = "1".equals(Config.get("ui.showall", "0"));
            try {
                // Memory-cached listing: re-entering a folder in one session is
                // prompt-free (the first visit lists, later visits are served
                // from memory until a mutation clears the cache).
                Vector names = m.listDir(dirRel);
                for (int i = 0; i < names.size(); i++) {
                    String name = (String) names.elementAt(i);
                    // Hidden entries, including Obsidian's own .obsidian dir.
                    if (name.length() == 0 || name.charAt(0) == '.') {
                        continue;
                    }
                    // The app dir is "noksidian", not ".noksidian": the real
                    // E71's JSR-75 rejects any path segment starting with '.'
                    // outright, so it cannot be hidden by the rule above and
                    // has to be filtered by name here.
                    if (atRoot && name.equals("noksidian/")) {
                        continue; // app's private sync-state dir
                    }
                    // JSR-75's list() returns directory names with a trailing
                    // '/' and Files.list passes that through; it is the only
                    // type information a listing carries, so the slash is kept
                    // in the label and stripped again in entryRel and
                    // startRename when a real path or a bare name is needed.
                    if (name.endsWith("/")) {
                        labs.addElement(name);
                        mks.addElement(new Integer(UiList.MARK_FOLDER));
                        continue;
                    }
                    if (NoksidianMIDlet.VAULT_DESC.equals(name)) {
                        continue; // encryption descriptor: never listed
                    }
                    // Default view is vault-shaped, not file-manager-shaped:
                    // only the types this app can actually open. ui.showall=1
                    // lifts the filter for people keeping PDFs etc. alongside
                    // their notes (they still cannot be opened, only seen).
                    if (showAll || Path.isMarkdown(name) || Path.isImage(name)
                            || "txt".equals(Path.ext(name))) {
                        labs.addElement(name);
                        mks.addElement(new Integer(Path.isImage(name)
                                ? UiList.MARK_IMAGE : UiList.MARK_NOTE));
                    }
                }
                status = null;
            } catch (Throwable e) {
                // Throwable, not just IOException: this runs on a bare worker
                // thread (constructor), and files.list() can throw a
                // RuntimeException (e.g. a rejected emoji URL on Symbian). An
                // uncaught exception here would kill the whole MIDlet.
                status = "Cannot list " + (atRoot ? "vault" : dirRel);
            }
        }
        // Assign marks before labels: paint keys row count off labels, so a
        // concurrent snapshot never sees new labels paired with stale marks.
        this.marks = mks;
        this.labels = labs;
        // The row under the cursor may have just been deleted or renamed away,
        // so pin the selection back inside the new model rather than resetting
        // it to 0: after a delete the cursor should stay where the user was.
        if (sel >= labs.size()) {
            sel = (labs.size() > 0) ? labs.size() - 1 : 0;
        }
        if (sel < 0) {
            sel = 0;
        }
        repaint();
    }

    /** Sync/status line shown under the title (null clears it). */
    public void setStatus(String s) {
        status = (s == null || s.length() == 0) ? null : s;
        repaint();
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    protected void paintBody(Graphics g, int cx, int cy, int cw, int ch) {
        int listY = cy;
        int listH = ch;
        // Snapshot the volatile-ish status once: a worker thread can null it
        // between the emptiness test and the draw, and re-reading the field
        // would then NPE inside the paint path.
        String st = status;
        if (st != null && st.length() > 0) {
            Font sf = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                    Font.SIZE_SMALL);
            int shh = sf.getHeight() + 4;
            // ticker band: subtle bg2 panel with a hr hairline below it, a
            // small accent activity dot, and muted status text.
            g.setColor(Theme.bg2);
            g.fillRect(cx, cy, cw, shh);
            g.setColor(Theme.hr);
            g.drawLine(cx, cy + shh - 1, cx + cw, cy + shh - 1);
            g.setColor(Theme.accent);
            g.fillArc(cx + 4, cy + shh / 2 - 2, 4, 4, 0, 360);
            g.setFont(sf);
            g.setColor(Theme.dimText);
            g.drawString(Ui.clip(st, sf, cw - 15), cx + 12, cy + 2,
                    Graphics.TOP | Graphics.LEFT);
            listY += shh;
            listH -= shh;
        }
        if (listH < 0) {
            listH = 0;
        }
        paintList(g, cx, listY, cw, listH);
    }

    /**
     * Draws the rows into the rect left over below the ticker. Row geometry is
     * recomputed every frame instead of cached because the body font follows
     * Theme.bodySize, which the user can change in Settings at any time.
     */
    private void paintList(Graphics g, int cx, int cy, int cw, int ch) {
        // Snapshot both Vectors once. refresh() swaps the fields wholesale from
        // a worker thread, so re-reading them mid-loop could pair labels with
        // the wrong marks; markAt additionally tolerates a shorter mks.
        Vector labs = labels;
        Vector mks = marks;
        Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Theme.bodySize);
        int fh = f.getHeight();
        int rowH = fh + 4;
        int n = labs.size();
        int total = n * rowH;
        boolean overflow = total > ch;
        // Scroll is clamped here rather than on key press because the viewport
        // height is only known during paint (full-screen mode, and the ticker
        // steals a variable slice of the body).
        ensureVisible(rowH, ch, n);
        int listW = overflow ? (cw - 5) : cw;
        // Glyph is sized off the font so icons track the chosen text size, with
        // a floor because below ~6px the hand-drawn shapes stop being legible.
        int glyph = fh - 4;
        if (glyph < 6) {
            glyph = 6;
        }
        int textX = cx + 6 + glyph + 4;
        for (int i = 0; i < n; i++) {
            int ry = cy + i * rowH - scroll;
            // Cull off-screen rows: a large vault can hold hundreds of entries
            // and drawString per row is the expensive part of a repaint.
            if (ry + rowH < cy || ry > cy + ch) {
                continue;
            }
            boolean seld = (i == sel);
            int fg;
            if (seld) {
                // selBg tint fill + 3px accent bar down the left edge
                g.setColor(Theme.selBg);
                g.fillRect(cx, ry, listW, rowH);
                g.setColor(Theme.accent);
                g.fillRect(cx, ry, 3, rowH);
                fg = Theme.selText;
            } else {
                fg = Theme.text;
            }
            int gy = ry + (rowH - glyph) / 2;
            drawGlyph(g, markAt(mks, i), cx + 6, gy, glyph, seld);
            g.setFont(f);
            g.setColor(fg);
            String lab = (String) labs.elementAt(i);
            g.drawString(Ui.clip(lab, f, listW - (textX - cx) - 2), textX,
                    ry + 2, Graphics.TOP | Graphics.LEFT);
        }
        if (n == 0) {
            g.setFont(f);
            g.setColor(Theme.faint);
            g.drawString("(empty)", cx + 6, cy + 4,
                    Graphics.TOP | Graphics.LEFT);
        }
        if (overflow) {
            Ui.drawScrollbar(g, cx + cw - 4, cy, ch, total, ch, scroll);
        }
    }

    /**
     * Clamps sel into the model and scrolls the minimum distance needed to put
     * the selected row fully inside the viewport. n is the caller's snapshot of
     * the row count, so sel is re-clamped against it rather than against the
     * live Vector, which a worker may have replaced since.
     */
    private void ensureVisible(int rowH, int viewH, int n) {
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
        // Never scroll past the last row: when the whole list fits, max goes
        // negative and is floored to 0 so short lists stay pinned to the top.
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
     * Mark for row i, degrading to MARK_NONE for a null, short or non-Integer
     * Vector. Belt and braces around the lock-free model swap: refresh()
     * stores marks before labels so a mismatch should be impossible, but the
     * cost of being wrong here is a repaint that throws on the LCDUI thread,
     * so a missing glyph is taken instead.
     */
    private static int markAt(Vector mks, int i) {
        if (mks == null || i >= mks.size()) {
            return UiList.MARK_NONE;
        }
        Object o = mks.elementAt(i);
        return (o instanceof Integer) ? ((Integer) o).intValue()
                : UiList.MARK_NONE;
    }

    /**
     * Draws one row icon at (gx,gy) in a gs-by-gs box. The icons are primitive
     * calls rather than bitmaps deliberately: an image set would have to ship
     * at several sizes to follow the ui.font setting, would cost heap for every
     * decoded Image, and could not be recolored per theme.
     */
    private void drawGlyph(Graphics g, int mark, int gx, int gy, int gs,
            boolean seld) {
        if (mark == UiList.MARK_NONE) {
            return;
        }
        if (mark == UiList.MARK_FOLDER) {
            // Folder glyph is Theme.wikilink for every row (contract), not the
            // dimText/accent used for the other, unspecified glyph types.
            g.setColor(Theme.wikilink);
            int tabW = gs / 2;
            g.fillRect(gx, gy + gs / 4 - 2, tabW, 3);
            g.fillRect(gx, gy + gs / 4, gs, gs / 2 + 1);
            return;
        }
        // minimal line-weight glyphs: accent when selected, dimText otherwise
        int gc = seld ? Theme.accent : Theme.dimText;
        if (mark == UiList.MARK_NOTE) {
            // Sheet outline with three text rules, the last one short.
            g.setColor(gc);
            g.drawRect(gx + 1, gy, gs - 3, gs - 1);
            g.drawLine(gx + 3, gy + 3, gx + gs - 4, gy + 3);
            g.drawLine(gx + 3, gy + gs / 2, gx + gs - 4, gy + gs / 2);
            g.drawLine(gx + 3, gy + gs - 4, gx + gs - 6, gy + gs - 4);
        } else if (mark == UiList.MARK_IMAGE) {
            // Photo frame: a sun dot plus a two-segment mountain ridge.
            g.setColor(gc);
            g.drawRect(gx, gy, gs - 1, gs - 1);
            g.fillArc(gx + 2, gy + 2, 3, 3, 0, 360);
            g.drawLine(gx + 1, gy + gs - 2, gx + gs / 2, gy + gs / 2);
            g.drawLine(gx + gs / 2, gy + gs / 2, gx + gs - 2, gy + gs - 2);
        } else if (mark == UiList.MARK_UP) {
            g.setColor(gc);
            g.fillTriangle(gx + gs / 2, gy, gx, gy + gs - 1,
                    gx + gs - 1, gy + gs - 1);
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
     * Moves the selection by d rows, wrapping around the ends. Wrapping is
     * worth it on a d-pad: reaching the last entry of a long folder is one
     * "up" press instead of a hundred "down" presses. The double modulo is
     * there because Java's % keeps the sign of the dividend, so a plain
     * (sel + d) % n goes negative when moving up off row 0.
     */
    private void move(int d) {
        int n = labels.size();
        if (n == 0) {
            return;
        }
        sel = ((sel + d) % n + n) % n;
        repaint();
    }

    protected void onSelect() {
        openSelected();
    }

    protected void onRightSoft() {
        openSelected();
    }

    protected void onLeftSoft() {
        new UiMenu(m, this, MENU, this).show();
    }

    // ------------------------------------------------------------------
    // UiListOwner (self-owner: our key handlers route through these)
    // ------------------------------------------------------------------

    /** Activates row index (moving the cursor there first). */
    public void listSelect(int index) {
        sel = index;
        openSelected();
    }

    /** Runs a menu command by name; index -1 means "not from a menu row". */
    public void listMenu(String command) {
        menuSelect(command, -1);
    }

    /** Back: go up one directory, or do nothing when already at the root. */
    public void listBack() {
        if (!atRoot) {
            m.showLibrary(Path.parent(dirRel));
        }
    }

    // ------------------------------------------------------------------
    // UiMenuOwner
    // ------------------------------------------------------------------

    /**
     * Runs a MENU command. Matched on the label rather than the index so the
     * menu can be reordered or reached from listMenu (which has no index)
     * without this dispatch silently firing the wrong action.
     */
    public void menuSelect(String item, int index) {
        if ("New note".equals(item)) {
            prompt("New note", "", P_NEW_NOTE);
        } else if ("New folder".equals(item)) {
            prompt("New folder", "", P_NEW_FOLDER);
        } else if ("Rename".equals(item)) {
            startRename();
        } else if ("Delete".equals(item)) {
            confirmDelete();
        } else if ("Search".equals(item)) {
            prompt("Search", "", P_SEARCH);
        } else if ("Today".equals(item)) {
            openToday();
        } else if ("Sync now".equals(item)) {
            syncNow();
        } else if ("Settings".equals(item)) {
            m.openSettings();
        } else if ("About".equals(item)) {
            showAbout();
        } else if ("Exit".equals(item)) {
            m.exit();
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    /**
     * The highlighted row's raw label (folders keep their trailing '/'), or
     * null when the list is empty or sel is stale after a concurrent refresh.
     * Every command starts here and bails on null, so an empty folder can
     * never dereference a missing row.
     */
    private String selectedLabel() {
        Vector labs = labels;
        if (sel < 0 || sel >= labs.size()) {
            return null;
        }
        return (String) labs.elementAt(sel);
    }

    /** Rel path of an entry (folder entries lose their trailing slash). */
    private String entryRel(String entry) {
        String name = entry;
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        return Path.join(dirRel, name);
    }

    /**
     * Opens the selection, dispatching on the label's shape because that is
     * all the listing tells us: ".." and a trailing '/' are directories, and
     * the rest is decided by extension. Anything not an image is handed to
     * openNote, which is also how ui.showall=1 rows behave.
     */
    private void openSelected() {
        String s = selectedLabel();
        if (s == null) {
            return;
        }
        if (s.equals("..")) {
            m.showLibrary(Path.parent(dirRel));
        } else if (s.endsWith("/")) {
            m.showLibrary(entryRel(s));
        } else if (Path.isImage(s)) {
            m.openImage(entryRel(s));
        } else {
            m.openNote(entryRel(s));
        }
    }

    /**
     * Asks the sync engine for a run. requestSync only queues the request; the
     * engine reports back through the MIDlet's SyncListener, which drives this
     * screen's ticker via setStatus, so nothing is awaited here. sync is null
     * until a vault has been chosen.
     */
    private void syncNow() {
        if (m.sync != null) {
            m.sync.requestSync("manual");
            setStatus("Sync requested...");
        } else {
            setStatus("Sync not ready");
        }
    }

    private void showAbout() {
        UiDialog.info(m, this, "About Noksidian",
                "Noksidian 1.0\nObsidian-style markdown vault for the Nokia"
                        + " E71.\nMarkdown viewer, editor and GitHub sync.\n"
                        + "Vault: " + Config.get("vault", "(none)"));
    }

    /**
     * Captures the rename target and opens the name prompt. The target is
     * stashed in a field because UiInput answers asynchronously, by which time
     * sel may have moved; ".." is excluded so the parent cannot be renamed
     * from inside a child folder.
     */
    private void startRename() {
        String s = selectedLabel();
        if (s == null || s.equals("..")) {
            return;
        }
        renameRel = entryRel(s);
        renameWasMd = !s.endsWith("/") && Path.isMarkdown(s);
        String name = s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        prompt("Rename", name, P_RENAME);
    }

    /**
     * Asks for confirmation before deleting. Files.delete is unconditional and
     * the next sync pass turns the local deletion into a remote one, so there
     * is no undo; the target path is therefore frozen into the DeleteOwner now
     * rather than re-read from sel once the user answers.
     */
    private void confirmDelete() {
        String s = selectedLabel();
        if (s == null || s.equals("..")) {
            return;
        }
        new UiDialog(m, this, "Delete", "Delete " + s + "?",
                UiDialog.YES_NO, new DeleteOwner(entryRel(s))).show();
    }

    /**
     * Opens today's daily note (&lt;daily.folder&gt;/YYYY-MM-DD.md), creating
     * it with a "# YYYY-MM-DD" heading on a background thread if missing.
     */
    private void openToday() {
        if (m.files == null) {
            return;
        }
        // ISO date assembled by hand: CLDC 1.1 has no SimpleDateFormat and no
        // String.format, and Calendar.MONTH is 0-based so it needs the +1.
        // This uses the device clock in its local zone, which is what a daily
        // note should follow.
        Calendar cal = Calendar.getInstance();
        final String date = cal.get(Calendar.YEAR) + "-"
                + pad2(cal.get(Calendar.MONTH) + 1) + "-"
                + pad2(cal.get(Calendar.DAY_OF_MONTH));
        // Daily notes live at a fixed place in the vault, not in whatever
        // folder is currently open, so "Today" means the same thing anywhere.
        // A blank config value falls back rather than writing to the root.
        String folder = Config.get("daily.folder", "Daily").trim();
        if (folder.length() == 0) {
            folder = "Daily";
        }
        final String rel = Path.join(folder, date + ".md");
        setStatus("Opening " + date + ".md ...");
        // Make sure the ticker set above is actually on screen while the worker
        // runs. Redundant on the menu path (UiMenu shows the back screen before
        // dispatching), but openToday must not depend on who invoked it.
        m.show(this);
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Create-if-missing, never overwrite: pressing Today twice
                    // in a day must reopen the note, not wipe it. writeText
                    // goes through the codec seam and Files.write creates the
                    // daily folder on the way, so no mkdirs is needed here.
                    if (!m.files.exists(rel)) {
                        m.writeText(rel, "# " + date + "\n");
                        m.noteWritten(rel);
                    }
                    setStatus(null);
                    m.openNote(rel);
                } catch (Throwable e) {
                    setStatus(null);
                    m.show(Library.this);
                    UiDialog.info(m, Library.this, "Today",
                            rel + ": " + e.toString());
                }
            }
        }).start();
    }

    /** Zero-pads to two digits; CLDC has no String.format to do it for us. */
    private static String pad2(int v) {
        return (v < 10) ? "0" + v : Integer.toString(v);
    }

    // ------------------------------------------------------------------
    // Input prompt handling
    // ------------------------------------------------------------------

    /**
     * Opens the shared single-line prompt in the given P_* mode. The answer
     * arrives later on PromptOwner.inputOk, so nothing after this call may
     * assume the user has replied.
     */
    private void prompt(String title, String initial, int mode) {
        // Contract: UiInput shows a prompt line above the field. Search asks
        // "Find:"; the name-entry prompts (new note/folder, rename) ask "Name:".
        String label = (mode == P_SEARCH) ? "Find:" : "Name:";
        m.show(new UiInput(m, title, label, initial, false,
                new PromptOwner(mode)));
    }

    /** Routes a non-empty prompt answer to the command that asked for it. */
    private void handleName(int mode, String name) {
        if (mode == P_SEARCH) {
            m.searchNotes(name);
        } else if (mode == P_NEW_NOTE) {
            newNote(name);
        } else if (mode == P_NEW_FOLDER) {
            newFolder(name);
        } else if (mode == P_RENAME) {
            doRename(name);
        }
    }

    /**
     * Creates an empty note in the current folder and opens the editor on it;
     * an already-existing file of that name is opened untouched. The ".md"
     * suffix is appended unless the user typed one, so notes stay markdown
     * even when named casually.
     */
    private void newNote(String name) {
        // Strip trailing dots/spaces first so "Foo." yields "Foo.md" rather
        // than "Foo..md", and an all-dots name reduces to empty and is
        // rejected by validName (B11).
        String base = trimName(name);
        if (!validName(base)) {
            UiDialog.info(m, this, "New note", "Invalid name: " + name);
            return;
        }
        // Case-insensitive test so a typed "Foo.MD" is left alone instead of
        // becoming "Foo.MD.md"; Path.isMarkdown accepts either casing too.
        String n = base;
        if (!n.toLowerCase().endsWith(".md")) {
            n = n + ".md";
        }
        final String rel = Path.join(dirRel, n);
        setStatus("Creating " + Path.name(rel) + "...");
        m.show(this);
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (m.files.exists(rel)) {
                        setStatus(null);
                        m.editNote(rel); // never truncate an existing note
                        return;
                    }
                    // writeText goes through the codec seam so a new note in
                    // an encrypted vault lands on disk encrypted at once.
                    m.writeText(rel, "");
                    m.noteWritten(rel);
                    setStatus(null);
                    m.editNote(rel);
                } catch (Throwable e) {
                    setStatus(null);
                    m.show(Library.this);
                    UiDialog.info(m, Library.this, "New note",
                            rel + ": " + e.toString());
                }
            }
        }).start();
    }

    /**
     * Creates a folder under the current one and re-lists. A name containing
     * '/' is allowed on purpose: mkdirs builds the whole chain, so one prompt
     * can create a nested path on a keypad-hostile device.
     */
    private void newFolder(final String name) {
        if (!validName(name)) {
            UiDialog.info(m, this, "New folder", "Invalid name: " + name);
            return;
        }
        final String rel = Path.join(dirRel, name);
        setStatus("Creating " + name + "...");
        m.show(this);
        new Thread(new Runnable() {
            public void run() {
                try {
                    m.files.mkdirs(rel);
                    // mkdirs bypasses the note* callbacks, so drop the cached
                    // listing directly before refresh re-lists this folder.
                    m.invalidateDirCache();
                    setStatus(null);
                    refresh();
                    m.show(Library.this);
                } catch (Throwable e) {
                    setStatus(null);
                    m.show(Library.this);
                    UiDialog.info(m, Library.this, "New folder",
                            name + ": " + e.toString());
                }
            }
        }).start();
    }

    /**
     * Applies a rename to the target captured by startRename. Unlike new
     * note/folder this is a pure rename within the same parent, so '/' is
     * rejected outright: Files.rename takes a bare name, not a path, and a
     * slash would otherwise be an accidental move request.
     */
    private void doRename(String name) {
        final String rel = renameRel;
        if (rel == null) {
            m.show(this);
            return;
        }
        if (name.indexOf('/') >= 0 || !validName(name)) {
            UiDialog.info(m, this, "Rename", "Invalid name: " + name);
            return;
        }
        // Re-attach ".md" only when the reply has no extension at all: the user
        // may deliberately be changing "a.md" to "a.txt", but a bare "a" almost
        // certainly means they just retyped the name and would otherwise drop
        // the note out of the markdown listing.
        String n = name;
        if (renameWasMd && n.indexOf('.') < 0) {
            n = n + ".md";
        }
        final String newName = n;
        setStatus("Renaming " + Path.name(rel) + "...");
        m.show(this);
        new Thread(new Runnable() {
            public void run() {
                try {
                    m.files.rename(rel, newName);
                    // noteRenamed re-prefixes the whole subtree in the note
                    // index, so renaming a folder keeps its children findable
                    // without a full rescan.
                    m.noteRenamed(rel, Path.join(Path.parent(rel), newName));
                    setStatus(null);
                    refresh();
                    m.show(Library.this);
                } catch (Throwable e) {
                    setStatus(null);
                    m.show(Library.this);
                    UiDialog.info(m, Library.this, "Rename",
                            rel + ": " + e.toString());
                }
            }
        }).start();
    }

    /** Upper bound on an entered name (before any ".md" is appended). */
    private static final int MAX_NAME = 120;

    /** Removes trailing dots and blanks (which FSes strip or choke on). */
    private static String trimName(String s) {
        if (s == null) {
            return "";
        }
        int e = s.length();
        while (e > 0) {
            char c = s.charAt(e - 1);
            if (c == '.' || c == ' ' || c == '\t') {
                e--;
            } else {
                break;
            }
        }
        return s.substring(0, e);
    }

    /**
     * Rejects names that would escape the vault or break URL building:
     * backslashes, over-long names, and empty / "." / ".." path segments.
     */
    private static boolean validName(String n) {
        // The blacklist is the FAT/Symbian illegal set. Rejecting here gives a
        // clear "Invalid name" dialog instead of an opaque JSR-75 failure (or,
        // worse, a file that is created but then cannot be reopened) later.
        if (n == null || n.length() == 0 || n.length() > MAX_NAME
                || n.indexOf('\\') >= 0
                || n.indexOf(':') >= 0 || n.indexOf('*') >= 0
                || n.indexOf('?') >= 0 || n.indexOf('<') >= 0
                || n.indexOf('>') >= 0 || n.indexOf('|') >= 0
                || n.indexOf('"') >= 0) {
            return false;
        }
        // Hand-rolled split (CLDC has no String.split and no java.util.regex):
        // walk to length() inclusive so the trailing segment after the last
        // '/' is checked too. A ".." segment is the path-escape case, and an
        // empty one comes from "a//b" or a leading/trailing slash.
        int start = 0;
        for (int i = 0; i <= n.length(); i++) {
            if (i == n.length() || n.charAt(i) == '/') {
                String seg = n.substring(start, i);
                if (seg.length() == 0 || seg.equals(".")
                        || seg.equals("..")) {
                    return false;
                }
                start = i + 1;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Owners
    // ------------------------------------------------------------------

    /**
     * Receives the answer from the shared UiInput and replays it into the
     * command that opened the prompt, using the P_* mode captured when the
     * prompt was raised: by the time the user replies, sel and the row model
     * may both have moved on.
     */
    private final class PromptOwner implements UiInputOwner {
        private final int mode;

        PromptOwner(int mode) {
            this.mode = mode;
        }

        public void inputOk(String value) {
            // An empty (or all-blank) reply is treated as a cancel rather than
            // an error: OK on an untouched field is a common misfire on a
            // keypad and should not raise a dialog.
            String name = (value == null) ? "" : value.trim();
            if (name.length() == 0) {
                m.show(Library.this);
                return;
            }
            handleName(mode, name);
        }

        public void inputCancel() {
            m.show(Library.this);
        }
    }

    /**
     * Confirmation handler for Delete, holding the path frozen at the moment
     * the dialog was raised so a stray key press cannot make the answer apply
     * to a different row.
     */
    private final class DeleteOwner implements UiDialogOwner {
        private final String rel;

        DeleteOwner(String rel) {
            this.rel = rel;
        }

        public void dialogResult(boolean positive) {
            if (!positive) {
                m.show(Library.this);
                return;
            }
            setStatus("Deleting " + Path.name(rel) + "...");
            m.show(Library.this);
            new Thread(new Runnable() {
                public void run() {
                    try {
                        m.files.delete(rel);
                        // noteRemoved drops the whole subtree from the note
                        // index and invalidates the listing cache, so the
                        // refresh below re-lists from the file system.
                        m.noteRemoved(rel);
                        setStatus(null);
                        refresh();
                        m.show(Library.this);
                    } catch (Throwable e) {
                        setStatus(null);
                        m.show(Library.this);
                        UiDialog.info(m, Library.this, "Delete",
                                rel + ": " + e.toString());
                    }
                }
            }).start();
        }
    }
}
