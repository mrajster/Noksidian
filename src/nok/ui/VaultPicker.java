package nok.ui;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import nok.NoksidianMIDlet;
import nok.core.Path;
import nok.sys.Files;

/**
 * Themed directory-only browser over FileSystemRegistry roots, used to pick
 * (or create) the vault folder. Fully Canvas-drawn so it honors {@link Theme}.
 *
 * <p>Right-soft "Open" (or FIRE) enters the selected drive/subfolder.
 * Left-soft "Menu" opens a {@link UiMenu}: "Use this folder" chooses the
 * current directory as the vault, "New folder" prompts for a name, "Up" goes
 * back up the tree, "Cancel" returns to the previous screen.</p>
 *
 * <p>Reached from the first-run welcome screen in NoksidianMIDlet, from
 * Settings -> "Change vault", and again from vaultChosen() when the folder it
 * was handed turns out to be unwritable. The only terminal action is
 * useFolder(), which passes the current directory URL to
 * NoksidianMIDlet.vaultChosen(); that probes writability by creating
 * "noksidian", stores the slash-terminated URL into Config under "vault", and
 * boots the vault. Every later file operation in the app is built by
 * concatenating a vault-relative path onto that string, so the exact encoding
 * this screen settles on is a permanent, on-disk decision - not a display
 * detail.</p>
 *
 * <p>That is why openSelected() probes two encodings instead of guessing.
 * JSR-75 stacks disagree: Nokia S60 / MicroEmulator take the name mostly
 * literally, while a spec-strict stack wants full UTF-8 percent-encoding, and
 * the real E71 throws IllegalArgumentException at raw non-ASCII. Only a URL
 * that Connector.open() has actually accepted here is allowed to become
 * curUrl, so the vault URL is provably openable on this device.</p>
 *
 * <p>Everything here - including the blocking Connector.open()/list() calls -
 * runs on the LCDUI event thread, and nothing outside this class touches its
 * state. That is tolerable only because directory listings are small and there
 * is nothing else to draw meanwhile; on an untrusted MIDlet each open can also
 * raise a platform "allow read?" prompt, which is why openSelected() skips the
 * second candidate when it would be the identical URL.</p>
 */
public final class VaultPicker extends UiScreen
        implements UiMenuOwner, UiInputOwner {

    // menuSelect() dispatches on the label text, not on the index, so this
    // array may be reordered without touching the handler.
    private static final String[] MENU = {
        "Use this folder", "New folder", "Up", "Cancel"
    };

    /** Current directory URL (always encoded, ends with '/'); null = roots. */
    private String curUrl;
    /** URL history; "" marks the roots level. */
    private final Vector hist = new Vector();
    /** Screen to return to on Cancel. */
    private final Displayable prev;

    // Visible list state. The row strings are the raw names JSR-75 handed back,
    // and the trailing '/' is the only type tag there is: openSelected() treats
    // a row without it as the "(no subfolders)" / "(no file systems found)"
    // placeholder and refuses to descend into it. paintBody uses the same test
    // to decide whether to draw a folder glyph.
    private Vector labels = new Vector();
    private int sel;
    /** Vertical offset in PIXELS (not rows) - rows are theme-font sized. */
    private int scroll;

    public VaultPicker(NoksidianMIDlet m) {
        super(m, "Choose vault");
        // Snapshot whatever is on screen right now as the Cancel target: the
        // welcome screen, Settings, or - on the vaultChosen() unwritable-folder
        // retry - the outgoing VaultPicker instance. The null guards cover a
        // construction before any screen has been shown; cancel() has its own
        // fallbacks for a prev it cannot use.
        Displayable p = null;
        if (m.disp != null) {
            p = m.disp.getCurrent();
        }
        this.prev = p;
        this.leftLabel = "Menu";
        this.rightLabel = "Open";
        showRoots();
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    /**
     * Switches to the top level: the FileSystemRegistry roots ("C:/", "E:/",
     * ...). curUrl is null here rather than "file:///" because a root is not
     * yet a usable vault - useFolder() keys off that null to refuse. The names
     * already carry a trailing '/', so they satisfy the row type tag as-is.
     */
    private void showRoots() {
        curUrl = null;
        title = "Choose vault";
        Vector labs = new Vector();
        Vector roots = Files.listRoots();
        for (int i = 0; i < roots.size(); i++) {
            labs.addElement((String) roots.elementAt(i));
        }
        if (roots.isEmpty()) {
            // A never-empty list keeps paintBody and the key handlers on one
            // code path; the placeholder has no trailing '/' so it is inert.
            labs.addElement("(no file systems found)");
        }
        // Build into a local and publish in one assignment, so labels always
        // refers to a complete list and never to one mid-fill.
        labels = labs;
        sel = 0;
        scroll = 0;
        repaint();
    }

    /**
     * Shows an already-listed directory: retitles to the decoded path and
     * resets sel/scroll to the top, since after a level change the old cursor
     * position indexes an unrelated list. curUrl must already be set to the
     * directory these entries came from - the title is derived from it.
     */
    private void fill(Vector dirs) {
        title = pretty(curUrl);
        Vector labs = new Vector();
        for (int i = 0; i < dirs.size(); i++) {
            labs.addElement((String) dirs.elementAt(i));
        }
        if (dirs.isEmpty()) {
            labs.addElement("(no subfolders)");
        }
        labels = labs;
        sel = 0;
        scroll = 0;
        repaint();
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    protected void paintBody(Graphics g, int cx, int cy, int cw, int ch) {
        // Read the list field once so the size taken here and every elementAt
        // below are guaranteed to be against the same Vector.
        Vector labs = labels;
        // Font and colors are resolved per paint, never cached, so a Theme
        // change (including the body-size setting) takes effect on next repaint.
        Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Theme.bodySize);
        int fh = f.getHeight();
        int rowH = fh + 4;
        int n = labs.size();
        int total = n * rowH;
        boolean overflow = total > ch;
        ensureVisible(rowH, ch, n);
        // Give up 5px of row width when the scrollbar is drawn so long names
        // are clipped short of it instead of running underneath.
        int listW = overflow ? (cw - 5) : cw;
        int glyph = fh - 4;
        if (glyph < 6) {
            // drawFolder's tab is a fixed 3px band, so below 6px the glyph
            // collapses into a smudge; stop tracking the font past that.
            glyph = 6;
        }
        int textX = cx + 6 + glyph + 4;
        for (int i = 0; i < n; i++) {
            int ry = cy + i * rowH - scroll;
            if (ry + rowH < cy || ry > cy + ch) {
                // Off-screen row: skip the string measuring and drawing, which
                // is what keeps a long drive listing scrolling smoothly.
                continue;
            }
            String lab = (String) labs.elementAt(i);
            boolean isDir = lab.endsWith("/");
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
                // The cursor can still land on a placeholder row, but opening it
                // does nothing, so it is drawn dim to read as a message.
                fg = isDir ? Theme.text : Theme.dimText;
            }
            int gy = ry + (rowH - glyph) / 2;
            if (isDir) {
                drawFolder(g, cx + 6, gy, glyph, seld);
            }
            g.setFont(f);
            g.setColor(fg);
            g.drawString(Ui.clip(lab, f, listW - (textX - cx) - 2), textX,
                    ry + 2, Graphics.TOP | Graphics.LEFT);
        }
        if (overflow) {
            Ui.drawScrollbar(g, cx + cw - 4, cy, ch, total, ch, scroll);
        }
    }

    /**
     * Draws the folder icon as two filled rects rather than blitting an image
     * asset, because it has to follow both the theme colors and the settable
     * body font size (gs is derived from the font height by the caller).
     */
    private void drawFolder(Graphics g, int gx, int gy, int gs, boolean seld) {
        // minimal line-weight glyph: accent when selected, dimText otherwise
        g.setColor(seld ? Theme.accent : Theme.dimText);
        int tabW = gs / 2;
        g.fillRect(gx, gy + gs / 4 - 2, tabW, 3);
        g.fillRect(gx, gy + gs / 4, gs, gs / 2 + 1);
    }

    /**
     * Clamps sel into the list and scrolls the minimum distance needed to bring
     * the selected row fully into the viewport. Called from paintBody because
     * rowH and the viewport height only exist there; the clamp on sel is purely
     * defensive, since every list rebuild resets sel to 0.
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
        // Never scroll past the last row, and pin to 0 when the whole list fits
        // (max would otherwise go negative and push the list off the top).
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

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    protected void onUp() {
        move(-1);
    }

    protected void onDown() {
        move(1);
    }

    private void move(int d) {
        int n = labels.size();
        if (n == 0) {
            return;
        }
        // Double modulo because Java's % keeps the sign of the dividend, so a
        // plain (sel + d) % n goes negative when moving up off row 0. This
        // wraps the cursor around, which matters on a phone with no page keys.
        sel = ((sel + d) % n + n) % n;
        repaint();
    }

    protected void onSelect() {
        openSelected();
    }

    // Open is bound to the right soft key as well as to onSelect (centre key /
    // Enter) because the d-pad centre does not reliably surface as FIRE - not
    // at all under the emulator - so the soft key is the dependable path.
    protected void onRightSoft() {
        openSelected();
    }

    protected void onLeftSoft() {
        new UiMenu(m, this, MENU, this).show();
    }

    // ------------------------------------------------------------------
    // UiMenuOwner
    // ------------------------------------------------------------------

    public void menuSelect(String item, int index) {
        if ("Use this folder".equals(item)) {
            useFolder();
        } else if ("New folder".equals(item)) {
            newFolderPrompt();
        } else if ("Up".equals(item)) {
            goBack();
        } else if ("Cancel".equals(item)) {
            cancel();
        }
    }

    // ------------------------------------------------------------------
    // UiInputOwner (new folder name)
    // ------------------------------------------------------------------

    /**
     * Receives the typed new-folder name. UiInput replaced this screen while it
     * was up, so every path out of here has to put the picker back on the
     * display (directly, or via the dialog's back target).
     */
    public void inputOk(String value) {
        String name = (value == null) ? "" : value.trim();
        if (name.length() == 0) {
            // Empty input is read as "changed my mind", not as an error.
            m.show(this);
            return;
        }
        if (!validName(name)) {
            UiDialog.info(m, this, "New folder", "Invalid name: " + name);
            return;
        }
        createFolder(name);
    }

    public void inputCancel() {
        m.show(this);
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    /**
     * Descends into the highlighted row. Nothing is committed until a listing
     * has actually succeeded, so a directory that cannot be opened leaves
     * curUrl, hist and the visible list exactly as they were.
     */
    private void openSelected() {
        Vector labs = labels;
        if (sel < 0 || sel >= labs.size()) {
            return;
        }
        String name = (String) labs.elementAt(sel);
        if (!name.endsWith("/")) {
            return; // placeholder line
        }
        String base = (curUrl == null) ? "file:///" : curUrl;
        // Candidate 1: local enc() (space/% only) - keeps spaces working and is
        // what MicroEmulator wants. Candidate 2: full UTF-8 percent-encoding for
        // spec-strict Symbian (emoji/accented names). Whichever the device's
        // JSR-75 accepts becomes curUrl, so the persisted vault URL is a form the
        // device provably opens.
        String cand1 = base + enc(name);
        String cand2 = base + Path.urlEncodePath(name);
        try {
            Vector dirs;
            String chosen;
            try {
                dirs = listDirs(cand1);
                chosen = cand1;
            } catch (IOException e1) {
                // Plain ASCII names encode identically both ways; retrying an
                // identical URL would only fail again and burn a second JSR-75
                // permission prompt, so report the original failure instead.
                if (cand2.equals(cand1)) {
                    throw e1;
                }
                dirs = listDirs(cand2);
                chosen = cand2;
            }
            // "" is the sentinel for the roots level, since curUrl is null there
            // and Vector cannot hold null on CLDC.
            hist.addElement(curUrl == null ? "" : curUrl);
            curUrl = chosen;
            fill(dirs);
        } catch (IOException e) {
            UiDialog.info(m, this, "Folder", name + ": " + e.toString());
        }
    }

    /**
     * Commits the current directory as the vault. A root cannot be picked
     * straight from the top level: the row labels there are bare volume names
     * ("E:/"), not the "file:///E:/" URL the rest of the app concatenates onto,
     * and they have not been proven openable yet.
     */
    private void useFolder() {
        if (curUrl == null) {
            UiDialog.info(m, this, "Vault", "Open a drive or folder first,"
                    + " then choose 'Use this folder'.");
            return;
        }
        m.vaultChosen(curUrl);
    }

    private void newFolderPrompt() {
        if (curUrl == null) {
            UiDialog.info(m, this, "New folder", "Open a drive first.");
            return;
        }
        m.show(new UiInput(m, "New folder", "", "", false, this));
    }

    /**
     * Walks one level up. The parent is popped off hist rather than derived by
     * trimming curUrl, because the stack holds the exact URL string that was
     * proven to open on the way down - re-deriving it could reconstruct the
     * encoding variant this device rejects.
     */
    private void goBack() {
        // Already at the top: there is nowhere further up, so Up doubles as
        // Cancel instead of being a dead key.
        if (curUrl == null) {
            cancel();
            return;
        }
        if (hist.isEmpty()) {
            showRoots();
            return;
        }
        String up = (String) hist.elementAt(hist.size() - 1);
        hist.removeElementAt(hist.size() - 1);
        if (up.length() == 0) {
            showRoots();
            return;
        }
        try {
            Vector dirs = listDirs(up);
            curUrl = up;
            fill(dirs);
        } catch (IOException e) {
            // The parent has become unreadable (memory card pulled, permission
            // denied). Falling back to the roots keeps the user somewhere they
            // can navigate from rather than stranding them on a dead level.
            showRoots();
        }
    }

    /**
     * Leaves the picker without choosing, trying three targets in decreasing
     * order of precision: the screen that opened us (Settings, the welcome
     * screen), else the Library if a vault is already open, else quit. The last
     * rung matters because there must always be a way off this canvas; doing
     * nothing would trap the user here.
     */
    private void cancel() {
        if (prev != null && prev != this) {
            m.show(prev);
        } else if (m.files != null) {
            m.back();
        } else {
            m.exit();
        }
    }

    private void createFolder(String name) {
        String url = curUrl + enc(name);
        // JSR-75 identifies a directory purely by the trailing '/'; without it
        // Connector.open would set up a file connection and mkdir would fail.
        if (!url.endsWith("/")) {
            url = url + "/";
        }
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(url, Connector.READ_WRITE);
            // Opening a non-existent path is legal and creates no entry, so the
            // exists() check makes re-running with the same name a no-op rather
            // than an error the user has to interpret.
            if (!fc.exists()) {
                fc.mkdir();
            }
            // Re-list instead of appending to labels: this picks up the real
            // on-disk name (the FS may have rewritten it) in the sorted spot.
            Vector dirs = listDirs(curUrl);
            fill(dirs);
            m.show(this);
        } catch (Throwable e) {
            // Throwable, not IOException: Symbian's JSR-75 throws unchecked
            // IllegalArgumentException/SecurityException for names its URL
            // parser dislikes, and losing the vault picker to an uncaught
            // exception is far worse than showing the message.
            m.show(this);
            UiDialog.info(m, this, "New folder", name + ": " + e.toString());
        } finally {
            if (fc != null) {
                try {
                    fc.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /** Lists only visible subdirectories of an encoded dir URL. */
    private static Vector listDirs(String url) throws IOException {
        Vector v = new Vector();
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(url, Connector.READ);
            if (!fc.exists() || !fc.isDirectory()) {
                throw new IOException("not a directory");
            }
            Enumeration e = fc.list();
            while (e.hasMoreElements()) {
                String n = (String) e.nextElement();
                // Directories only (trailing '/'), never a bare "/" entry, and
                // never a dot-name: the real E71 refuses to build a URL whose
                // segment starts with '.' (IllegalArgumentException), so such a
                // folder could be listed but never entered or used as a vault.
                if (n.endsWith("/") && n.length() > 1 && n.charAt(0) != '.') {
                    v.addElement(n);
                }
            }
        } catch (RuntimeException re) {
            // Symbian throws IllegalArgumentException for a raw-emoji URL;
            // convert to IOException so callers (openSelected/goBack) handle it
            // gracefully instead of the app crashing on an uncaught exception.
            throw new IOException("bad URL: " + re.toString());
        } finally {
            if (fc != null) {
                try {
                    fc.close();
                } catch (IOException e) {
                }
            }
        }
        sort(v);
        return v;
    }

    /**
     * Case-insensitive ascending order (insertion sort), matching
     * nok.sys.Files so folders read the same here as in the Library.
     */
    private static void sort(Vector v) {
        for (int i = 1; i < v.size(); i++) {
            String cur = (String) v.elementAt(i);
            int j = i - 1;
            while (j >= 0 && cmp((String) v.elementAt(j), cur) > 0) {
                v.setElementAt(v.elementAt(j), j + 1);
                j--;
            }
            v.setElementAt(cur, j + 1);
        }
    }

    private static int cmp(String a, String b) {
        // Case-folded first, then exact as a tie-break, so "Notes/" and
        // "notes/" land next to each other yet still get a deterministic order
        // instead of being left in whatever sequence the file system reported.
        int c = a.toLowerCase().compareTo(b.toLowerCase());
        return (c != 0) ? c : a.compareTo(b);
    }

    /**
     * Rejects a new-folder name that could escape the current directory or
     * break URL building: '/', '\\', ':' and the "." / ".." segments.
     */
    private static boolean validName(String n) {
        if (n == null || n.length() == 0) {
            return false;
        }
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0
                || n.indexOf(':') >= 0) {
            return false;
        }
        if (n.equals(".") || n.equals("..")) {
            return false;
        }
        return true;
    }

    /** Encodes a path segment for a file URL: space -> %20, % -> %25. */
    // Deliberately minimal: Nokia S60 / MicroEmulator hand back names they want
    // to see again nearly verbatim, but a literal space breaks their URL parser
    // and a literal '%' would be read as the start of an escape. Encoding only
    // those two leaves everything else byte-identical to what the file system
    // reported. pretty() is the exact inverse of this, and full spec-strict
    // encoding is the separate second candidate in openSelected().
    private static String enc(String s) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                b.append("%25");
            } else if (c == ' ') {
                b.append("%20");
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Human-readable form of an encoded file URL for the title. */
    private static String pretty(String url) {
        StringBuffer b = new StringBuffer();
        String s = url;
        // Strip the scheme so the title reads like a phone path ("E:/Notes/")
        // rather than a URL; 8 is the length of "file:///".
        if (s.startsWith("file:///")) {
            s = s.substring(8);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Undoes exactly the two escapes enc() emits. Anything else stays as
            // written, so a stray '%' cannot be mistaken for an escape and a
            // truncated escape at the end of the string is copied through.
            if (c == '%' && i + 2 < s.length()) {
                String h = s.substring(i + 1, i + 3);
                if (h.equals("20")) {
                    b.append(' ');
                    i += 2;
                    continue;
                }
                if (h.equals("25")) {
                    b.append('%');
                    i += 2;
                    continue;
                }
            }
            b.append(c);
        }
        return b.toString();
    }
}
