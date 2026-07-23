package nok.ui;

import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import nok.NoksidianMIDlet;
import nok.core.Json;
import nok.core.Path;
import nok.sys.Config;
import nok.sys.Http;
import nok.sys.HttpResp;

/**
 * GitHub + app configuration, rendered as a fully themed vertical form
 * (a {@link UiScreen}, not a native Form) so it honors {@link Theme} on
 * every repaint. Each row is a text field, a cyclable choice, an action,
 * or an info line:
 *
 * <ul>
 *   <li>text rows open a {@link UiInput} on FIRE (token masked) that writes
 *       the typed value back into the row's pending buffer;</li>
 *   <li>choice rows cycle their value on FIRE / LEFT / RIGHT (cycling the
 *       Theme row applies {@link Theme#load} immediately so this very screen
 *       repaints in the new palette);</li>
 *   <li>action rows run Test, the encryption flows, Change vault, or Save.</li>
 * </ul>
 *
 * Save persists every Config key exactly as before, reloads the Theme and
 * pushes the current crypto scope into the sync engine. Test checks the
 * on-screen values directly over HTTP without touching Config, so unsaved
 * fields never persist. Leaving via Back without Save reverts a
 * live-previewed theme change.
 *
 * <p>Editing model: every row keeps its own pending buffer, seeded from
 * Config when the screen is built and not written back until Save. The one
 * exception is the Theme row, which writes Config immediately so the
 * palette can be previewed and is reverted by Back (see menuSelect). The
 * pending buffers are what make Back cheap and Test meaningful on unsaved
 * values. Config is a single RMS record, so persist() uses setQuiet for
 * every key and commits once with flush.</p>
 *
 * <p>Threading: everything here runs on the MIDP event thread except the
 * two explicitly spawned workers (the Test HTTP probe and the sync engine
 * restart), both of which would block the UI for seconds if run inline.
 * Neither touches the rows Vector: Test works from values snapshotted into
 * finals and reports through a UiDialog, the restart returns nothing.</p>
 */
public final class Settings extends UiScreen
        implements UiInputOwner, UiMenuOwner, UiDialogOwner {

    // Row kinds. T_INFO is label-only: it can still take focus, it simply
    // does nothing on FIRE, and it opens a new visual section (see the
    // hairline rule in paintBody).
    private static final int T_TEXT = 0;
    private static final int T_CHOICE = 1;
    private static final int T_ACTION = 2;
    private static final int T_INFO = 3;

    // Action row ids, dispatched by doAction.
    private static final int A_TEST = 1;
    private static final int A_SETPW = 2;
    private static final int A_CHANGEPW = 3;
    private static final int A_DECRYPT = 4;
    private static final int A_FORGET = 5;
    private static final int A_VAULT = 6;
    private static final int A_SAVE = 7;

    /** Public GitHub endpoint; also the value substituted for a cleared
     *  API URL field, so Enterprise users can point elsewhere but nobody
     *  can end up with an empty base URL. */
    private static final String API_DEFAULT = "https://api.github.com";

    // Choice tables. Each LABELS array is index-parallel with its VALS array:
    // choiceIdx indexes both, labels are shown, vals are what Config stores.
    // ON_OFF and OFF_ON exist as separate pairs so that each setting's own
    // default ("1" for sync.auto, "0" for the ui.* flags) sits at index 0.
    private static final String[] ON_OFF = { "On", "Off" };
    private static final String[] VAL_ON_OFF = { "1", "0" };
    private static final String[] OFF_ON = { "Off", "On" };
    private static final String[] VAL_OFF_ON = { "0", "1" };
    private static final String[] INT_LABELS =
            { "5 min", "15 min", "30 min", "60 min" };
    private static final String[] INT_VALS = { "5", "15", "30", "60" };
    private static final String[] STRAT_LABELS =
            { "Keep both", "Prefer phone", "Prefer GitHub" };
    private static final String[] STRAT_VALS = { "both", "local", "remote" };
    private static final String[] THEME_LABELS = { "Light", "Dark" };
    private static final String[] THEME_VALS = { "light", "dark" };
    // Font size options: every crisp size this handset can render - the 3
    // native heights plus pixel-perfect integer upscales of the LARGE font
    // at x2/x3/x4. Measured at runtime so the list is correct on any device;
    // values are stored in Config "ui.font" as pixel-height strings.
    private static final int FONT_SMALL_H = fontH(Font.SIZE_SMALL);
    private static final int FONT_MEDIUM_H = fontH(Font.SIZE_MEDIUM);
    private static final int FONT_LARGE_H = fontH(Font.SIZE_LARGE);
    private static final String[] FONT_VALS = buildFontVals();
    private static final String[] FONT_LABELS = buildFontLabels();

    /** Measured pixel height of a native SIZE_* face. MIDP only names three
     *  sizes and never states their heights, so they must be probed. */
    private static int fontH(int size) {
        return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                size).getHeight();
    }

    /** Sorted, de-duplicated px option values as strings. */
    private static String[] buildFontVals() {
        int[] opt = new int[6];
        opt[0] = FONT_SMALL_H;
        opt[1] = FONT_MEDIUM_H;
        opt[2] = FONT_LARGE_H;
        opt[3] = FONT_LARGE_H * 2;
        opt[4] = FONT_LARGE_H * 3;
        opt[5] = FONT_LARGE_H * 4;
        // Insertion sort by hand: CLDC 1.1 ships no java.util.Arrays, and at
        // six elements the O(n^2) is free.
        for (int i = 1; i < opt.length; i++) {
            int v = opt[i];
            int j = i - 1;
            while (j >= 0 && opt[j] > v) {
                opt[j + 1] = opt[j];
                j--;
            }
            opt[j + 1] = v;
        }
        // Collapse duplicates (now adjacent). Handsets are free to map two
        // of the three named sizes onto the same pixel height - and an
        // upscale can land on a native height too - which would otherwise
        // put the same "N px" entry in the list twice, one of them
        // unreachable because the lookup in fontRow stops at the first
        // match. Runs over the sorted array, so one pass suffices.
        int n = 0;
        int[] uniq = new int[opt.length];
        for (int i = 0; i < opt.length; i++) {
            if (n == 0 || uniq[n - 1] != opt[i]) {
                uniq[n++] = opt[i];
            }
        }
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = Integer.toString(uniq[i]);
        }
        return out;
    }

    private static String[] buildFontLabels() {
        String[] out = new String[FONT_VALS.length];
        for (int i = 0; i < FONT_VALS.length; i++) {
            out[i] = FONT_VALS[i] + " px";
        }
        return out;
    }

    /** Maps legacy "small"/"medium"/"large" to the native px string. */
    private static String migrateFont(String v) {
        if ("small".equals(v)) {
            return Integer.toString(FONT_SMALL_H);
        }
        if ("medium".equals(v)) {
            return Integer.toString(FONT_MEDIUM_H);
        }
        if ("large".equals(v)) {
            return Integer.toString(FONT_LARGE_H);
        }
        return v;
    }
    // crypt.scope: "all" keeps ciphertext in the repo too, "local" decrypts
    // on push (Sync.toPlain) so the GitHub copy stays readable. The row is
    // shown even when encryption is off, so the choice is already made when
    // a password is set later.
    private static final String[] SCOPE_LABELS =
            { "Phone + GitHub", "Phone only" };
    private static final String[] SCOPE_VALS = { "all", "local" };

    private final Vector rows = new Vector();
    private int sel;
    /** Index of the first visible row. Recomputed and clamped every paint,
     *  so nothing outside paintBody has to keep it in range. */
    private int scroll;

    private Row editingRow;   // text row whose UiInput is open
    private Row encInfoRow;   // dynamic "Encryption: ..." status line
    private Row forgetRow;    // present only while crypt.dk is set

    /** Re-entry guard for the Test action: cleared by the worker thread, so
     *  it is written off the event thread. Only ever gates a user-initiated
     *  keypress, so a stale read costs at worst one duplicate probe. */
    private boolean testing;
    /** ui.theme value when this screen opened; the Theme row live-previews
     *  by persisting immediately, so Back without Save restores this. */
    private final String themeAtOpen;
    /** True once Save committed the on-screen values. */
    private boolean committed;

    public Settings(NoksidianMIDlet m) {
        super(m, "Settings");
        this.leftLabel = "Menu";
        this.rightLabel = "Save";
        this.themeAtOpen = Config.get("ui.theme", "light");
        buildRows();
    }

    // ------------------------------------------------------------------
    // Row model
    // ------------------------------------------------------------------

    /**
     * One form line. A bare mutable struct with no accessors and no equals:
     * encInfoRow and forgetRow are references into the Vector and are matched
     * by identity (paintRow's == test, Vector.removeElement). Only the fields
     * relevant to the row's type are populated - the rest stay null/0.
     */
    private static final class Row {
        int type;
        String label;
        String key;
        boolean masked;
        String emptyDefault;    // text: substituted when the trimmed value is ""
        String pending;         // text: current buffer
        String[] choiceLabels;
        String[] choiceVals;
        int choiceIdx;
        int action;
    }

    /**
     * Free-text row. Two distinct fallbacks: def is what Config.get yields
     * when the key was never written, emptyDefault is what replaces a value
     * the user has explicitly blanked (both on Save and in Test). Rows that
     * have a real default pass the same string for both; the rows where an
     * empty value is meaningful (owner, repo, token) pass null, so blank is
     * a valid, persisted value there. masked hides the value behind
     * "(set)"/"(none)" and opens the UiInput with bullets.
     */
    private Row textRow(String label, String key, String def,
            String emptyDefault, boolean masked) {
        Row r = new Row();
        r.type = T_TEXT;
        r.label = label;
        r.key = key;
        r.masked = masked;
        r.emptyDefault = emptyDefault;
        r.pending = Config.get(key, def);
        return r;
    }

    /**
     * Cyclable row over index-parallel label/value tables. A stored value
     * outside vals (older build, hand-edited RMS) silently selects index 0,
     * which Save then writes back - a choice row can never persist a value
     * the UI is unable to display.
     */
    private Row choiceRow(String label, String key, String[] labels,
            String[] vals, String def) {
        Row r = new Row();
        r.type = T_CHOICE;
        r.label = label;
        r.key = key;
        r.choiceLabels = labels;
        r.choiceVals = vals;
        String cur = Config.get(key, def);
        int idx = 0;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i].equals(cur)) {
                idx = i;
                break;
            }
        }
        r.choiceIdx = idx;
        return r;
    }

    /**
     * Font size choice row. The stored value is a px string; legacy word
     * values are migrated (in-row only, persisted on Save) to the native
     * height they used to select, so old installs keep their size.
     */
    private Row fontRow() {
        Row r = new Row();
        r.type = T_CHOICE;
        r.label = "Font size";
        r.key = "ui.font";
        r.choiceLabels = FONT_LABELS;
        r.choiceVals = FONT_VALS;
        String cur = migrateFont(Config.get("ui.font", "small"));
        int idx = 0;
        for (int i = 0; i < FONT_VALS.length; i++) {
            if (FONT_VALS[i].equals(cur)) {
                idx = i;
                break;
            }
        }
        r.choiceIdx = idx;
        return r;
    }

    private static Row actionRow(String label, int action) {
        Row r = new Row();
        r.type = T_ACTION;
        r.label = label;
        r.action = action;
        return r;
    }

    /**
     * Populates the form. Insertion order is the whole layout: it fixes the
     * visual order, and paintBody derives the section hairlines from it
     * (before the info row, and before the first of a run of actions), so
     * moving a row also moves a separator. Called once from the constructor;
     * the encryption rows therefore reflect the vault state at open time.
     */
    private void buildRows() {
        rows.removeAllElements();
        rows.addElement(textRow("GitHub owner", "gh.owner", "", null, false));
        rows.addElement(textRow("Repository", "gh.repo", "", null, false));
        rows.addElement(textRow("Branch", "gh.branch", "main", "main", false));
        rows.addElement(textRow("Access token", "gh.token", "", null, true));
        rows.addElement(textRow("API URL", "gh.api", API_DEFAULT,
                API_DEFAULT, false));
        rows.addElement(choiceRow("Auto sync", "sync.auto", ON_OFF,
                VAL_ON_OFF, "1"));
        rows.addElement(choiceRow("Sync interval", "sync.interval", INT_LABELS,
                INT_VALS, "15"));
        rows.addElement(choiceRow("Conflicts", "sync.strategy", STRAT_LABELS,
                STRAT_VALS, "both"));
        rows.addElement(choiceRow("Theme", "ui.theme", THEME_LABELS,
                THEME_VALS, "light"));
        rows.addElement(fontRow());
        rows.addElement(choiceRow("Show all files", "ui.showall", OFF_ON,
                VAL_OFF_ON, "0"));
        rows.addElement(choiceRow("Open last note", "ui.resume", OFF_ON,
                VAL_OFF_ON, "0"));
        // Native S60 fields default to sentence case ("Abc"); the editor
        // replicates that, and this is its off switch.
        rows.addElement(choiceRow("Auto-capitalise", "edit.autocap", ON_OFF,
                VAL_ON_OFF, "1"));
        rows.addElement(textRow("Daily notes folder", "daily.folder", "Daily",
                "Daily", false));
        // The label set here is only a seed: paintRow recomputes the text
        // for this exact row on every frame, so the flows below can change
        // the vault state without anyone rebuilding the form.
        Row info = new Row();
        info.type = T_INFO;
        info.label = encStatusText();
        encInfoRow = info;
        rows.addElement(info);
        rows.addElement(choiceRow("Encrypted sync scope", "crypt.scope",
                SCOPE_LABELS, SCOPE_VALS, "all"));
        // Enable and change/disable are mutually exclusive, mirroring the
        // preconditions CryptoSetup.startEnable/startChange enforce anyway -
        // offering the impossible one would only produce an error dialog.
        if (m.vaultEncrypted()) {
            rows.addElement(actionRow("Change password", A_CHANGEPW));
            rows.addElement(actionRow("Decrypt vault", A_DECRYPT));
        } else {
            rows.addElement(actionRow("Set password", A_SETPW));
        }
        // Only offer "Forget" when there is actually a remembered key to
        // forget; crypt.dk holds the derived key, not the password.
        forgetRow = null;
        if (Config.get("crypt.dk", "").length() > 0) {
            forgetRow = actionRow("Forget password", A_FORGET);
            rows.addElement(forgetRow);
        }
        rows.addElement(actionRow("Test", A_TEST));
        rows.addElement(actionRow("Change vault", A_VAULT));
        rows.addElement(actionRow("Save", A_SAVE));
        // Dead in practice (the only caller is the constructor, where sel is
        // still 0), but it keeps buildRows safe to re-run after the optional
        // rows above have changed the row count.
        if (sel >= rows.size()) {
            sel = rows.size() - 1;
        }
        if (sel < 0) {
            sel = 0;
        }
    }

    /** Status line: off / on (phone+GitHub) / on (phone only). */
    private String encStatusText() {
        if (!m.vaultEncrypted()) {
            return "Encryption: off";
        }
        // Reads the persisted scope, not the pending choice row, so the line
        // describes how the vault is stored right now rather than what Save
        // would change it to.
        return "all".equals(Config.get("crypt.scope", "all"))
                ? "Encryption: on (phone+GitHub)"
                : "Encryption: on (phone only)";
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    /**
     * Draws the visible slice of the row list. Chrome deliberately uses the
     * fixed native SMALL face rather than Theme.bodyPx: the font-size setting
     * governs note reading only, and at the 4x option a form row would be
     * tall enough to make the setting hard to reach and change back.
     */
    protected void paintBody(Graphics g, int cx, int cy, int cw, int ch) {
        Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Font.SIZE_SMALL);
        g.setFont(f);
        int rowH = f.getHeight() + 8;
        int n = rows.size();
        // Partial rows are not drawn, so visRows floors; force at least one
        // so a pathologically short body still shows the selection.
        int visRows = ch / rowH;
        if (visRows < 1) {
            visRows = 1;
        }
        // Scroll follows the selection rather than the other way round: the
        // navigation code only moves sel, and every clamp lives here.
        if (sel < scroll) {
            scroll = sel;
        }
        if (sel >= scroll + visRows) {
            scroll = sel - visRows + 1;
        }
        int maxScroll = n - visRows;
        if (maxScroll < 0) {
            maxScroll = 0;
        }
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
        if (scroll < 0) {
            scroll = 0;
        }

        // Reserve the scrollbar gutter only when there is something to
        // scroll, so a short form uses the full width.
        boolean overflow = n > visRows;
        int listW = cw - (overflow ? 6 : 0);
        int y = cy;
        for (int i = 0; i < visRows; i++) {
            int idx = scroll + i;
            if (idx >= n) {
                break;
            }
            Row r = (Row) rows.elementAt(idx);
            boolean selRow = (idx == sel);
            // A hairline opens a new section before the encryption status
            // line and before the first of a run of action rows.
            if (idx > 0) {
                Row prev = (Row) rows.elementAt(idx - 1);
                if (r.type == T_INFO
                        || (r.type == T_ACTION && prev.type != T_ACTION)) {
                    g.setColor(Theme.hr);
                    g.drawLine(cx + 2, y, cx + listW - 2, y);
                }
            }
            if (selRow) {
                // Modern selection: subtle tint fill + a 3px accent left bar.
                g.setColor(Theme.selBg);
                g.fillRect(cx, y, listW, rowH);
                g.setColor(Theme.accent);
                g.fillRect(cx, y, 3, rowH);
            }
            paintRow(g, f, r, cx + 6, y + 4, listW - 10, selRow);
            y += rowH;
        }
        if (overflow) {
            Ui.drawScrollbar(g, cx + cw - 4, cy, ch, n, visRows, scroll);
        }
    }

    /**
     * Paints one row as "label ... value", the value right-aligned. Identity
     * comparison against encInfoRow makes the encryption status self-updating
     * without anyone having to rebuild the form after a crypto flow.
     */
    private void paintRow(Graphics g, Font f, Row r, int x, int y, int w,
            boolean selRow) {
        String label = (r == encInfoRow) ? encStatusText() : r.label;
        int labelColor;
        if (selRow) {
            labelColor = Theme.selText;
        } else if (r.type == T_ACTION) {
            labelColor = Theme.link;
        } else if (r.type == T_INFO) {
            labelColor = Theme.dimText;
        } else {
            labelColor = Theme.text;
        }
        String val = rowValue(r);
        if (val == null) {
            g.setColor(labelColor);
            g.drawString(Ui.clip(label, f, w), x, y,
                    Graphics.TOP | Graphics.LEFT);
            return;
        }
        // The value may take at most two thirds of the row; the label then
        // gets whatever width the clipped value actually left over. The 20px
        // floor keeps a long value (a repo URL, say) from squeezing the label
        // out entirely and leaving an unidentifiable line - at that point the
        // two are allowed to collide instead.
        String valDisp = Ui.clip(val, f, (w * 2) / 3);
        int vW = f.stringWidth(valDisp);
        int labMax = w - vW - 8;
        if (labMax < 20) {
            labMax = 20;
        }
        g.setColor(labelColor);
        g.drawString(Ui.clip(label, f, labMax), x, y,
                Graphics.TOP | Graphics.LEFT);
        // Choice values signal the active setting in the accent color;
        // free-text values stay muted.
        g.setColor(r.type == T_CHOICE ? Theme.accent : Theme.dimText);
        g.drawString(valDisp, x + w, y, Graphics.TOP | Graphics.RIGHT);
    }

    /** Right-hand value for a row, or null when the row is label-only. */
    private String rowValue(Row r) {
        if (r.type == T_CHOICE) {
            return r.choiceLabels[r.choiceIdx];
        }
        if (r.type == T_TEXT) {
            // A masked value is never rendered, not even clipped or as
            // asterisks whose count leaks the token length.
            if (r.masked) {
                return (r.pending != null && r.pending.length() > 0)
                        ? "(set)" : "(none)";
            }
            return (r.pending != null) ? r.pending : "";
        }
        // Actions and the info line carry no value; paintRow uses the null
        // to give their label the whole row width.
        return null;
    }

    // ------------------------------------------------------------------
    // Navigation / activation
    // ------------------------------------------------------------------

    protected void onUp() {
        move(-1);
    }

    protected void onDown() {
        move(1);
    }

    // LEFT/RIGHT are free on this screen (no horizontal panning), so they
    // become the natural bidirectional cycle for choice rows; FIRE can only
    // ever go forwards.
    protected void onLeftArrow() {
        Row r = focused();
        if (r != null && r.type == T_CHOICE) {
            cycleChoice(r, -1);
        }
    }

    protected void onRightArrow() {
        Row r = focused();
        if (r != null && r.type == T_CHOICE) {
            cycleChoice(r, 1);
        }
    }

    protected void onSelect() {
        Row r = focused();
        if (r == null) {
            return;
        }
        if (r.type == T_TEXT) {
            openInput(r);
        } else if (r.type == T_CHOICE) {
            cycleChoice(r, 1);
        } else if (r.type == T_ACTION) {
            doAction(r.action);
        }
    }

    /** Back lives in the menu rather than on a soft key because the right
     *  soft key is spent on Save and this handset has only two. */
    protected void onLeftSoft() {
        String[] items = { "Save", "Back" };
        new UiMenu(m, this, items, this).show();
    }

    protected void onRightSoft() {
        save();
    }

    private Row focused() {
        if (sel < 0 || sel >= rows.size()) {
            return null;
        }
        return (Row) rows.elementAt(sel);
    }

    /** Moves the cursor by d rows, wrapping at both ends - Save sits last,
     *  so one UP from the top row reaches it without scrolling the form. */
    private void move(int d) {
        int n = rows.size();
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

    /** Advances a choice row by d, wrapping. The +n before the modulo is
     *  needed because Java's % keeps the sign of the dividend, so a plain
     *  (idx - 1) % n at index 0 would yield -1. */
    private void cycleChoice(Row r, int d) {
        int n = r.choiceVals.length;
        r.choiceIdx = (r.choiceIdx + d + n) % n;
        if ("ui.theme".equals(r.key)) {
            // Live preview: Theme reads Config's in-memory table, so update it
            // (without a flash commit) then reload; Save flushes the final value.
            Config.setQuiet("ui.theme", r.choiceVals[r.choiceIdx]);
            Theme.load();
        }
        repaint();
    }

    /**
     * Hands the row over to the full-screen UiInput editor. UiInput is a
     * Displayable of its own, so it replaces this screen and reports back
     * through inputOk/inputCancel; editingRow is what remembers where the
     * result belongs across that round trip.
     */
    private void openInput(Row r) {
        editingRow = r;
        m.show(new UiInput(m, r.label, r.label + ":",
                r.pending != null ? r.pending : "", r.masked, this));
    }

    /**
     * Runs an action row. The three CryptoSetup flows end in the Library
     * rather than here, so any pending (unsaved) edits on the form are lost
     * once one of them is started; VaultPicker by contrast snapshots this
     * screen as its Cancel target, so backing out of it returns to this same
     * instance with the pending buffers intact.
     */
    private void doAction(int action) {
        if (action == A_TEST) {
            test();
        } else if (action == A_SETPW) {
            CryptoSetup.startEnable(m);
        } else if (action == A_CHANGEPW) {
            CryptoSetup.startChange(m);
        } else if (action == A_DECRYPT) {
            CryptoSetup.startDisable(m);
        } else if (action == A_FORGET) {
            forgetPassword();
        } else if (action == A_VAULT) {
            m.show(new VaultPicker(m));
        } else if (action == A_SAVE) {
            save();
        }
    }

    // ------------------------------------------------------------------
    // UiInput / UiMenu / UiDialog owner callbacks
    // ------------------------------------------------------------------

    /** Stores the typed text in the row's pending buffer only; nothing
     *  reaches Config until Save. Stored verbatim - trimming and the
     *  empty-default substitution happen in persist and pendingText. */
    public void inputOk(String value) {
        if (editingRow != null) {
            editingRow.pending = value;
            editingRow = null;
        }
        m.show(this);
    }

    public void inputCancel() {
        editingRow = null;
        m.show(this);
    }

    public void menuSelect(String item, int index) {
        if ("Save".equals(item)) {
            save();
        } else if ("Back".equals(item)) {
            // The Theme row persists immediately for live preview; leaving
            // without Save must not keep that unsaved change.
            if (!committed
                    && !themeAtOpen.equals(Config.get("ui.theme", "light"))) {
                // Persist the revert durably (not setQuiet): otherwise an
                // interleaved full Config.set elsewhere would flush the
                // un-saved preview theme to RMS and this revert would never
                // reach disk, so the unsaved theme survived a restart.
                Config.set("ui.theme", themeAtOpen);
                Theme.load();
            }
            m.back();
        }
    }

    /** The Save confirmation dialog uses this screen as its owner. */
    public void dialogResult(boolean positive) {
        m.back();
    }

    // ------------------------------------------------------------------
    // Forget / Save / Test
    // ------------------------------------------------------------------

    /** Clears the remembered key (Config crypt.dk); prompts next start. */
    private void forgetPassword() {
        Config.set("crypt.dk", null);
        if (forgetRow != null) {
            rows.removeElement(forgetRow);
            forgetRow = null;
            if (sel >= rows.size()) {
                sel = rows.size() - 1;
            }
        }
        UiDialog.info(m, this, "Forget password",
                "Stored key removed. You will be asked for the vault"
                        + " password on the next start.");
    }

    /** Writes every field into Config. Skips label-only rows. */
    private void persist() {
        for (int i = 0; i < rows.size(); i++) {
            Row r = (Row) rows.elementAt(i);
            // The trim and empty-default substitution below must stay
            // identical to pendingText's, or Test would probe values other
            // than the ones Save is about to store.
            if (r.type == T_TEXT) {
                String v = (r.pending != null) ? r.pending.trim() : "";
                if (v.length() == 0 && r.emptyDefault != null) {
                    v = r.emptyDefault;
                }
                Config.setQuiet(r.key, v);
            } else if (r.type == T_CHOICE) {
                Config.setQuiet(r.key, r.choiceVals[r.choiceIdx]);
            }
        }
        // One flash commit for the whole Settings save instead of ~14.
        Config.flush();
    }

    /**
     * Commits the form: Config, then Theme, then the sync engine. Ends on a
     * confirmation dialog whose dismissal (dialogResult) leaves the screen,
     * so Save is always terminal - there is no "saved, keep editing" state.
     */
    private void save() {
        // Set first: from here on the previewed theme IS the saved theme, so
        // a later Back must not revert it.
        committed = true;
        persist();
        // Re-derive the palette and the body font metrics from the values
        // just written, so the confirmation dialog below already draws in
        // the saved theme.
        Theme.load();
        if (m.sync != null) {
            // Apply the (possibly changed) scope live; crypto may be null
            // for an unencrypted vault, which setCrypto handles.
            m.sync.setCrypto(m.crypto,
                    "all".equals(Config.get("crypt.scope", "all")));
            // stop() can block for many seconds waiting on a running
            // pass, so restart the engine off the UI event thread
            // (Sync guards its own lifecycle).
            new Thread(new Runnable() {
                public void run() {
                    try {
                        m.sync.stop();
                        m.sync.start();
                    } catch (Exception e) {
                    }
                }
            }).start();
        }
        new UiDialog(m, this, "Settings", "Saved.", UiDialog.OK, this).show();
    }

    /**
     * One-shot GitHub connectivity probe against the on-screen credentials.
     * Runs on its own thread because a GPRS/3G round trip can take tens of
     * seconds and the MIDP event thread must stay responsive; the title bar
     * carries the progress and a UiDialog delivers the verdict.
     */
    private void test() {
        if (testing) {
            return;
        }
        testing = true;
        // Test the on-screen (possibly unsaved) values directly; nothing
        // is persisted, so a crash mid-test can never leave unsaved
        // fields in Config. Persistence belongs to Save alone.
        final String owner = pendingText("gh.owner", "");
        final String repo = pendingText("gh.repo", "");
        final String branch = pendingText("gh.branch", "main");
        final String token = pendingText("gh.token", "");
        final String api = pendingText("gh.api", API_DEFAULT);
        // Snapshotting into finals is both a Java 1.3 requirement (an inner
        // class can only capture finals) and the reason the worker never
        // touches the rows Vector, which belongs to the event thread.
        title = "Settings (testing...)";
        repaint();
        new Thread(new Runnable() {
            public void run() {
                String err;
                try {
                    err = testConnection(owner, repo, branch, token, api);
                } catch (Throwable t) {
                    // Belt and braces: testConnection already swallows
                    // everything, but an escaping Throwable here would kill
                    // the thread with testing stuck true and Test dead for
                    // the life of the screen.
                    err = t.toString();
                }
                testing = false;
                title = "Settings";
                boolean ok = (err == null);
                UiDialog.info(m, Settings.this, "Test",
                        ok ? "Success! GitHub connection works." : err);
            }
        }).start();
    }

    /** Pending (on-screen) value of a text row, trimmed, with the row's
     *  empty-default applied; falls back to def when the row is absent. */
    private String pendingText(String key, String def) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = (Row) rows.elementAt(i);
            if (r.type == T_TEXT && key.equals(r.key)) {
                String v = (r.pending != null) ? r.pending.trim() : "";
                if (v.length() == 0 && r.emptyDefault != null) {
                    v = r.emptyDefault;
                }
                return v;
            }
        }
        return def;
    }

    /**
     * Connectivity/credentials check against explicit values (same
     * request and error mapping as GitHub.test(), which reads Config
     * and therefore cannot test unsaved fields). Returns null on
     * success, else a short error message. Never throws.
     */
    private static String testConnection(String owner, String repo,
            String branch, String token, String api) {
        if (branch.length() == 0) {
            branch = "main";
        }
        // Strip every trailing slash (a pasted "https://host/api/v3//") so
        // the concatenation below cannot produce "//repos".
        while (api.endsWith("/")) {
            api = api.substring(0, api.length() - 1);
        }
        if (api.length() == 0) {
            api = API_DEFAULT;
        }
        if (owner.length() == 0 || repo.length() == 0
                || token.length() == 0) {
            return "not configured (owner, repo and token required)";
        }
        try {
            // A branch read is the cheapest request that exercises all four
            // things at once: reachability, token validity, repo visibility
            // and the branch name. Only the branch is encoded (owner and repo
            // cannot contain anything that needs it); urlEncodePath keeps the
            // '/' of a "feature/x" name and escapes spaces and non-ASCII.
            String url = api + "/repos/" + owner + "/" + repo
                    + "/branches/" + Path.urlEncodePath(branch);
            Hashtable h = new Hashtable();
            h.put("Authorization", "token " + token);
            h.put("Accept", "application/vnd.github+json");
            HttpResp r = Http.request("GET", url, h, null);
            if (r.code >= 200 && r.code < 300) {
                return null;
            }
            if (r.code == 401) {
                return "bad token (HTTP 401)";
            }
            if (r.code == 403) {
                return "access denied or rate limited (HTTP 403)";
            }
            if (r.code == 404) {
                // GitHub also answers 404 for a private repo the token
                // cannot see, so the wording stays deliberately vague.
                return "repo or branch not found (HTTP 404)";
            }
            // Anything else: surface GitHub's own "message" field if the
            // body parses, since the status code alone rarely explains it.
            String msg = null;
            try {
                Hashtable o = Json.obj(Json.parse(r.bodyText()));
                msg = Json.str(o, "message");
            } catch (Throwable t) {
                // body not json; fall through
            }
            return (msg == null || msg.length() == 0)
                    ? "HTTP " + r.code
                    : "HTTP " + r.code + " " + msg;
        } catch (Throwable t) {
            return "connection failed: " + t.toString();
        }
    }
}
