package nok.ui;

import java.util.Vector;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import nok.NoksidianMIDlet;
import nok.core.Sha256;
import nok.core.VaultCrypto;
import nok.sys.Config;
import nok.sys.Files;
import nok.sys.IndexStore;

/**
 * Vault encryption flows (CONTRACTS-FEATURES.md section 4): enable,
 * change password, disable. Each flow chains masked {@link UiInput}
 * screens (fully themed, no native Form/TextField), validates input,
 * then re-writes every vault file on a background thread behind a themed
 * {@link Progress} screen ("Encrypting 12/87 ...").
 *
 * Raw Files.read/write + VaultCrypto are used ON PURPOSE (not the
 * midlet codec seam) because files are deliberately transformed
 * between states here.
 *
 * Crash-safety ordering: enable writes _vault.nkv FIRST (a descriptor
 * always exists while any ciphertext does), disable deletes it LAST,
 * and change persists the NEW descriptor to a _vault.nkv.new sidecar
 * BEFORE the walk and promotes it to _vault.nkv only after a fully
 * clean walk, so a crashed or partial change is healed by re-running
 * it with the old password + the SAME new password (files already
 * re-keyed by the crashed walk decrypt via the sidecar keys). When any
 * file fails the descriptor is never swapped or deleted, so old-key
 * files are never orphaned.
 *
 * After any walk the sync baseline (.noksidian/base/ + state.json) is
 * cleared to force a full re-baseline, crypt.dk is cleared, the sync
 * engine gets the new keys and a sync is requested.
 *
 * Threading: the start* entry points and the whole UiInput chain run on
 * the LCDUI event thread, but the migration walk gets its own Thread -
 * re-reading and re-writing an entire vault takes minutes on E71-class
 * flash and would freeze the UI if done inline. The walk touches the UI
 * only through Display.setCurrent (via the dialog helpers) and
 * Canvas.repaint(), both of which MIDP allows from any thread.
 */
public final class CryptoSetup {

    // Flow mode. One Flow class drives all three migrations: they differ only
    // in which fields are prompted for and in a handful of branches in walk().
    private static final int ENABLE = 0;
    private static final int CHANGE = 1;
    private static final int DISABLE = 2;

    /** Sidecar holding the NEW descriptor while a change walk runs. */
    private static final String NEW_DESC =
            NoksidianMIDlet.VAULT_DESC + ".new";

    private CryptoSetup() {
    }

    /**
     * Settings row "Set password". Settings only offers this row on a
     * plaintext vault, so both guards are backstops: no vault open yet
     * (m.files null) is a silent no-op and an already-encrypted vault gets
     * an explanation rather than a second migration walk over ciphertext.
     */
    public static void startEnable(NoksidianMIDlet m) {
        if (m.files == null) {
            return;
        }
        if (m.vaultEncrypted()) {
            m.alertErr("Encryption", "Vault is already encrypted.");
            return;
        }
        new Flow(m, ENABLE).start();
    }

    /**
     * Settings row "Change password". Only the presence of a descriptor is
     * checked, not whether the vault is unlocked: the old password is
     * re-entered and re-verified by the walk regardless of any keys in RAM.
     */
    public static void startChange(NoksidianMIDlet m) {
        if (m.files == null) {
            return;
        }
        if (!m.vaultEncrypted()) {
            m.alertErr("Encryption", "Vault is not encrypted.");
            return;
        }
        new Flow(m, CHANGE).start();
    }

    public static void startDisable(NoksidianMIDlet m) {
        if (m.files == null) {
            return;
        }
        if (!m.vaultEncrypted()) {
            m.alertErr("Encryption", "Vault is not encrypted.");
            return;
        }
        new Flow(m, DISABLE).start();
    }

    // ------------------------------------------------------------------
    // shared

    /** Screen title for a mode, reused as the dialog title in failBack. */
    private static String title(int mode) {
        if (mode == ENABLE) {
            return "Encrypt vault";
        }
        if (mode == CHANGE) {
            return "Change password";
        }
        return "Decrypt vault";
    }

    // ==================================================================
    // Input flow: one masked UiInput per field, chained via inputOk.
    // ==================================================================

    /**
     * One password-entry session and the migration it launches. Every step
     * shows a brand new UiInput screen, so the Flow instance is what survives
     * across them: it is simultaneously the owner callback, the state machine
     * (mode + step), and the carrier of the entered passwords. It is also the
     * receiver of the background walk, hence the fields stay live until the
     * final dialog.
     */
    private static final class Flow implements UiInputOwner {

        private final NoksidianMIDlet m;
        private final int mode;
        /** Index into this mode's field chain; the chain is per-mode. */
        private int step;
        // Plaintext passwords held between screens. CLDC Strings cannot be
        // wiped, so the only mitigation is lifetime: start() and failBack()
        // null them, and the Flow is unreachable once the walk finishes.
        private String oldPw;
        private String newPw;

        Flow(NoksidianMIDlet m, int mode) {
            this.m = m;
            this.mode = mode;
            this.step = 0;
        }

        /** Show the first field of a fresh flow. */
        void start() {
            step = 0;
            oldPw = null;
            newPw = null;
            showStep();
        }

        /** Prompt label for the current step. */
        private String stepPrompt() {
            if (mode == ENABLE) {
                return (step == 0) ? "Password" : "Confirm password";
            }
            if (mode == CHANGE) {
                if (step == 0) {
                    return "Old password";
                }
                return (step == 1) ? "New password" : "Confirm new password";
            }
            return "Password";
        }

        /**
         * Step at which the NEW password is first entered; a validation
         * failure re-shows this field.
         */
        private int pwStep() {
            return (mode == CHANGE) ? 1 : 0;
        }

        private void showStep() {
            // The 'true' is UiInput's mask flag: password fields never echo.
            m.show(new UiInput(m, title(mode), stepPrompt(), "", true, this));
        }

        public void inputCancel() {
            m.openSettings();
        }

        /**
         * Advances the field chain. The last field of ENABLE and CHANGE is a
         * confirmation and goes through validateAndRun; DISABLE has a single
         * field with nothing to confirm and starts the walk straight away
         * (the password is checked against the descriptor there).
         */
        public void inputOk(String value) {
            if (mode == ENABLE) {
                if (step == 0) {
                    newPw = value;
                    step = 1;
                    showStep();
                } else {
                    validateAndRun(value);
                }
            } else if (mode == CHANGE) {
                if (step == 0) {
                    oldPw = value;
                    step = 1;
                    showStep();
                } else if (step == 1) {
                    newPw = value;
                    step = 2;
                    showStep();
                } else {
                    validateAndRun(value);
                }
            } else {
                oldPw = value;
                runWalk();
            }
        }

        /** Min length 4 + matching confirm, then start the migration. */
        private void validateAndRun(String confirm) {
            if (newPw == null || newPw.length() < 4) {
                warn("Password must be at least 4 characters.");
                return;
            }
            if (!newPw.equals(confirm)) {
                warn("Passwords do not match.");
                return;
            }
            runWalk();
        }

        /** Validation error: themed dialog, then re-show the password field. */
        private void warn(String msg) {
            // Rewind before building the screen: its label comes from
            // stepPrompt(), which reads step.
            step = pwStep();
            UiInput back = new UiInput(m, title(mode), stepPrompt(), "", true,
                    this);
            UiDialog.info(m, back, "Encryption", msg);
        }

        /** Wrong password during the walk: error, then a fresh flow. */
        private void failBack(String msg) {
            // Reached from the walk thread, where the current screen is the
            // Progress one, so the dialog is handed an explicitly rebuilt
            // UiInput to return to rather than whatever is on screen. Both
            // passwords are dropped: the retry re-enters the whole chain.
            step = 0;
            oldPw = null;
            newPw = null;
            UiInput back = new UiInput(m, title(mode), stepPrompt(), "", true,
                    this);
            UiDialog.info(m, back, title(mode), msg);
        }

        // --------------------------------------------------------------
        // migration walk

        /**
         * Shows the Progress screen and hands the migration to a worker
         * thread. The walk re-reads and re-writes every file in the vault,
         * which on phone flash is far too long to sit on the LCDUI event
         * thread. Progress is the current screen for the whole walk and has
         * no soft keys, so nothing else can be driven meanwhile.
         */
        private void runWalk() {
            final Progress prog = new Progress(m, title(mode));
            m.show(prog);
            final Flow self = this;
            new Thread(new Runnable() {
                public void run() {
                    self.walk(prog);
                }
            }).start();
        }

        /** Background walk. Never throws; reports via dialogs. */
        private void walk(Progress prog) {
            Files files = m.files;
            // Tracks whether the catch block still owes the sync engine a
            // restart; cleared the instant the happy path restarts it.
            boolean stoppedSync = false;
            try {
                VaultCrypto oldC = null;
                VaultCrypto newC = null;
                // Keys of a crashed earlier change walk, recovered from the
                // sidecar; newDesc doubles as the "resume that walk" flag.
                VaultCrypto recovery = null;
                byte[] newDesc = null;
                if (mode != ENABLE) {
                    // Verify the old password against the descriptor FIRST.
                    prog.setStatus("Checking password...");
                    byte[] desc = files.read(NoksidianMIDlet.VAULT_DESC);
                    try {
                        oldC = VaultCrypto.open(desc,
                                (oldPw == null) ? "" : oldPw);
                    } catch (IllegalArgumentException e) {
                        // open() reports a failed check-value compare and a
                        // malformed/short descriptor with the same exception
                        // type; the message is the only way to tell a wrong
                        // password from a damaged _vault.nkv.
                        failBack("wrong password".equals(e.getMessage())
                                ? "Wrong password." : "Bad vault descriptor.");
                        return;
                    }
                }
                if (mode == CHANGE) {
                    // A sidecar left by a crashed change holds the keys some
                    // files may already be re-encrypted with; opening it with
                    // the entered NEW password lets this re-run heal them.
                    byte[] side = readQuiet(files, NEW_DESC);
                    if (side != null) {
                        try {
                            recovery = VaultCrypto.open(side, newPw);
                            newDesc = side; // resume with the SAME salt/keys
                        } catch (IllegalArgumentException e) {
                            recovery = null; // other pw / corrupt: ignore
                        }
                    }
                }
                if (m.sync != null) {
                    // No sync pass may pull/push while files change state.
                    prog.setStatus("Pausing sync...");
                    m.sync.stop();
                    stoppedSync = true;
                }
                if (mode != DISABLE) {
                    // PBKDF2 at DEFAULT_ITERATIONS takes seconds on an E71,
                    // hence the status line before it.
                    prog.setStatus("Deriving keys...");
                    if (newDesc == null) {
                        // No resumable sidecar: fresh salt, fresh descriptor.
                        byte[] salt = makeSalt(newPw);
                        newDesc = VaultCrypto.newDescriptor(newPw, salt,
                                VaultCrypto.DEFAULT_ITERATIONS);
                        newC = split(VaultCrypto.deriveKeys(newPw, salt,
                                VaultCrypto.DEFAULT_ITERATIONS));
                    } else {
                        newC = recovery; // resume the crashed change's keys
                    }
                    if (mode == ENABLE) {
                        // Descriptor FIRST: it must exist before any ciphertext.
                        files.write(NoksidianMIDlet.VAULT_DESC, newDesc);
                        m.vaultDescChanged();
                    } else {
                        // Sidecar FIRST: the new salt is on disk before any
                        // file is re-keyed, so a crashed walk stays healable.
                        files.write(NEW_DESC, newDesc);
                    }
                }
                int[] counts = migrate(files, oldC, newC, recovery, prog);
                // Failed files still carry the OLD keys: swapping or deleting
                // the descriptor would orphan them, so keep it (and the
                // sidecar) and warn instead.
                boolean partial = (mode != ENABLE) && counts[2] > 0;
                if (mode == CHANGE && !partial) {
                    // Promote the sidecar LAST: only a fully clean walk
                    // commits the new password.
                    files.write(NoksidianMIDlet.VAULT_DESC, newDesc);
                    m.vaultDescChanged();
                    deleteQuiet(files, NEW_DESC);
                } else if (mode == DISABLE && !partial) {
                    files.delete(NoksidianMIDlet.VAULT_DESC); // LAST
                    m.vaultDescChanged();
                    deleteQuiet(files, NEW_DESC);
                }
                prog.setStatus("Resetting sync state...");
                clearSyncState(files);
                // Keys the rest of the app must use from here on. A partial
                // walk left the OLD descriptor in place, so the OLD keys are
                // the ones that still match what is on disk.
                VaultCrypto active = partial ? oldC
                        : ((mode == DISABLE) ? null : newC);
                m.crypto = active;
                // The remembered derived key belongs to the old password (or
                // to no password at all now). Startup would reject it anyway
                // (tryStoredKey re-checks it against the descriptor), so this
                // is about not leaving a dead key sitting in RMS.
                Config.set("crypt.dk", null);
                if (m.sync != null) {
                    m.sync.setCrypto(active,
                            "all".equals(Config.get("crypt.scope", "all")));
                    m.sync.start();
                    stoppedSync = false;
                    m.sync.requestSync("crypto");
                }
                finishDialog(title(mode),
                        partial ? partialSummary(counts) : summary(counts));
            } catch (Throwable t) {
                // Throwable, not Exception: key derivation and the descriptor
                // read can raise OutOfMemoryError on this heap (per-file
                // failures are already absorbed inside migrate). Sync must
                // still come back up and the user must still get a dialog
                // instead of being stranded on the Progress screen.
                if (stoppedSync && m.sync != null) {
                    try {
                        m.sync.start();
                    } catch (Throwable t2) {
                        // keep reporting the original failure
                    }
                }
                finishDialog(title(mode) + " failed", t.toString());
            }
        }

        /**
         * Re-writes every vault file (scanAll minus _vault.nkv and the
         * change sidecar) into the target state, skipping files already
         * there (magic sniff). In CHANGE mode files the old key cannot
         * decrypt fall back to the recovery key of a crashed prior walk.
         * Returns {changed, skipped, failed}.
         */
        private int[] migrate(Files files, VaultCrypto oldC, VaultCrypto newC,
                VaultCrypto recovery, Progress prog) {
            Vector v = new Vector();
            // scanAll already drops dotfiles and the private noksidian/ dir,
            // so only the two descriptor files are left to filter out; both
            // must stay plaintext (see CONTRACTS-CRYPTO). Walked backwards
            // because removeElementAt shifts every later index down.
            files.scanAll("", v);
            for (int i = v.size() - 1; i >= 0; i--) {
                Object name = v.elementAt(i);
                if (NoksidianMIDlet.VAULT_DESC.equals(name)
                        || NEW_DESC.equals(name)) {
                    v.removeElementAt(i);
                }
            }
            int total = v.size();
            String verb = (mode == DISABLE) ? "Decrypting " : "Encrypting ";
            int changed = 0;
            int skipped = 0;
            int failed = 0;
            for (int i = 0; i < total; i++) {
                String rel = (String) v.elementAt(i);
                prog.setProgress(verb + (i + 1) + "/" + total + " ...",
                        i + 1, total);
                try {
                    byte[] b = files.read(rel);
                    // Magic sniff, not a filename or state table: this is what
                    // makes a re-run after a crash cheap and idempotent, since
                    // files already in the target state are just counted.
                    boolean enc = VaultCrypto.isEncrypted(b);
                    if (mode == ENABLE) {
                        if (enc) {
                            skipped++;
                            continue;
                        }
                        // CLDC has no usable RNG, so encrypt() derives the IV
                        // SIV-style from what distinguishes the write instead
                        // (CONTRACTS-CRYPTO); path and clock are two of those
                        // inputs and have to be passed in from here.
                        files.write(rel, newC.encrypt(b, rel,
                                System.currentTimeMillis()));
                    } else if (mode == DISABLE) {
                        if (!enc) {
                            skipped++;
                            continue;
                        }
                        files.write(rel, oldC.decrypt(b));
                    } else {
                        byte[] plain;
                        if (!enc) {
                            plain = b;
                        } else {
                            try {
                                plain = oldC.decrypt(b);
                            } catch (RuntimeException e) {
                                if (recovery == null) {
                                    throw e;
                                }
                                // Re-keyed by a crashed change walk.
                                plain = recovery.decrypt(b);
                            }
                        }
                        files.write(rel, newC.encrypt(plain, rel,
                                System.currentTimeMillis()));
                    }
                    changed++;
                } catch (Throwable t) {
                    // One unreadable or oversized file must not abort the
                    // walk; the rest of the vault still migrates. A non-zero
                    // count is what makes the caller treat the walk as
                    // partial and leave the descriptor alone.
                    failed++;
                }
            }
            return new int[] {changed, skipped, failed};
        }

        private String summary(int[] c) {
            String head;
            String verb;
            if (mode == ENABLE) {
                head = "Encryption enabled.";
                verb = " encrypted, ";
            } else if (mode == CHANGE) {
                head = "Password changed.";
                verb = " re-encrypted, ";
            } else {
                head = "Encryption disabled.";
                verb = " decrypted, ";
            }
            return head + "\n" + c[0] + verb + c[1] + " skipped, " + c[2]
                    + " failed.\nA full re-sync will run next.";
        }

        /** failed > 0: the descriptor was NOT swapped; explain the state. */
        private String partialSummary(int[] c) {
            String head;
            String fix;
            if (mode == CHANGE) {
                head = "Password NOT changed (old password stays active).";
                fix = "Re-run Change password with the SAME new password.";
            } else {
                head = "Encryption NOT disabled (vault stays encrypted).";
                fix = "Re-run Decrypt vault to finish.";
            }
            return head + "\n" + c[0] + " done, " + c[1] + " skipped, "
                    + c[2] + " failed.\n" + fix;
        }

        /**
         * End-of-walk dialog that navigates explicitly on dismissal (no
         * getCurrent()-based back target) so the user always lands on a
         * Library with working keys, never the key-less Progress screen.
         */
        private void finishDialog(String title, String msg) {
            new UiDialog(m, null, title, msg, UiDialog.OK,
                    new UiDialogOwner() {
                        public void dialogResult(boolean positive) {
                            m.showLibrary("");
                        }
                    }).show();
        }
    }

    // ==================================================================
    // Themed progress screen (replaces the native progress Form).
    // ==================================================================

    /**
     * Minimal themed screen: title bar + a live status line + a progress
     * fraction bar. Repainted from the migration worker thread via
     * {@link #setStatus}/{@link #setProgress}. No soft keys (the walk cannot
     * be cancelled), matching the old command-less progress Form.
     */
    private static final class Progress extends UiScreen {

        private String status;
        private int done;
        // File count for the bar. Stays 0 during the status-only phases
        // (password check, key derivation), where bar and fraction are
        // skipped entirely rather than drawn empty.
        private int total;

        Progress(NoksidianMIDlet m, String title) {
            super(m, title);
            this.status = "Preparing...";
            this.done = 0;
            this.total = 0;
            this.leftLabel = null;
            this.rightLabel = null;
        }

        // Both setters are called from the migration worker thread. They only
        // assign fields and ask for a repaint, which MIDP explicitly permits
        // off the event thread; the actual painting still happens on it.

        void setStatus(String s) {
            this.status = s;
            repaint();
        }

        void setProgress(String s, int done, int total) {
            this.status = s;
            this.done = done;
            this.total = total;
            repaint();
        }

        protected void paintBody(Graphics g, int cx, int cy, int cw, int ch) {
            Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                    Font.SIZE_SMALL);
            g.setFont(f);
            int lh = f.getHeight();
            // The worker can swap status between the field read and the
            // draw, so read it once into a local.
            String s = (status != null) ? status : "";

            int pad = 8;
            int cardX = cx + 6;
            int cardW = cw - 12;
            if (cardW < 16) {
                // floor so the card rect and the wrap width below stay
                // non-negative on an implausibly narrow body
                cardW = 16;
            }
            Vector lines = Ui.wrap(s, f, cardW - pad * 2);

            // Measure content so the card hugs it and centers in the body.
            int barH = lh + 2;
            int contentH = lines.size() * lh;
            if (total > 0) {
                contentH += 10 + barH + 4 + lh;
            }
            int cardH = pad * 2 + contentH;
            int cardY = cy + (ch - cardH) / 2;
            if (cardY < cy + 6) {
                cardY = cy + 6;
            }

            // Titled card: a panel just above black with a 1px hairline border.
            g.setColor(Theme.codeBg);
            g.fillRoundRect(cardX, cardY, cardW, cardH, 8, 8);
            g.setColor(Theme.hr);
            // MIDP outline rects span width+1 pixels, so -1 keeps the border
            // inside the fill instead of one pixel past it.
            g.drawRoundRect(cardX, cardY, cardW - 1, cardH - 1, 8, 8);

            int tx = cardX + pad;
            int tw = cardW - pad * 2;
            int y = cardY + pad;
            g.setColor(Theme.text);
            for (int i = 0; i < lines.size(); i++) {
                g.drawString((String) lines.elementAt(i), tx, y,
                        Graphics.TOP | Graphics.LEFT);
                y += lh;
            }
            if (total > 0) {
                y += 10;
                // Accent progress bar on a subtle bg2 track.
                g.setColor(Theme.bg2);
                g.fillRoundRect(tx, y, tw, barH, 6, 6);
                // done and total are written by the worker one after the
                // other, so paint can catch a new done against a stale total
                // and compute a fraction above 1; hence the clamps. The long
                // cast is belt-and-braces on the same unsynchronised read.
                int fillw = (int) (((long) tw * done) / total);
                if (fillw > tw) {
                    fillw = tw;
                }
                if (fillw < 0) {
                    fillw = 0;
                }
                if (fillw > 0) {
                    g.setColor(Theme.accent);
                    g.fillRoundRect(tx, y, fillw, barH, 6, 6);
                }
                g.setColor(Theme.hr);
                g.drawRoundRect(tx, y, tw - 1, barH - 1, 6, 6);
                y += barH + 4;
                g.setColor(Theme.dimText);
                g.drawString(done + " / " + total, tx, y,
                        Graphics.TOP | Graphics.LEFT);
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers (unchanged crypto glue)

    /**
     * Deletes .noksidian/base/ contents and state.json (+ the state.tmp
     * fallback) so the next sync pass re-baselines everything.
     */
    private static void clearSyncState(Files files) {
        // The dir on disk is "noksidian", not ".noksidian" as the name above
        // suggests: a real E71's JSR-75 rejects any path segment starting
        // with '.' outright (IllegalArgumentException, "url is not valid"),
        // even though emulators accept it.
        deleteTree(files, "noksidian/base");
        deleteQuiet(files, "noksidian/state.json");
        deleteQuiet(files, "noksidian/state.tmp");
        deleteQuiet(files, "noksidian/index"); // legacy file cache, if any lingers
        // The index now lives in RMS; drop it so a newly-encrypted vault never
        // keeps a plaintext path list (and a decrypted one rescans cleanly).
        IndexStore.clear();
    }

    /** Recursively deletes the CONTENTS of rel (never throws). */
    private static void deleteTree(Files files, String rel) {
        Vector kids;
        try {
            kids = files.list(rel);
        } catch (Throwable t) {
            return; // missing dir: nothing to clear
        }
        for (int i = 0; i < kids.size(); i++) {
            String name = (String) kids.elementAt(i);
            boolean dir = name.endsWith("/");
            String child = rel + "/"
                    + (dir ? name.substring(0, name.length() - 1) : name);
            // Depth first: JSR-75 refuses to delete a directory that still
            // has contents, so a subtree must be emptied before its own
            // delete is attempted.
            if (dir) {
                deleteTree(files, child);
            }
            deleteQuiet(files, child);
        }
    }

    private static void deleteQuiet(Files files, String rel) {
        try {
            // Delete directly: a missing file just throws (caught), so this
            // avoids the extra exists() open+prompt before the delete.
            files.delete(rel);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Reads rel, returning null when missing or unreadable (never throws). */
    private static byte[] readQuiet(Files files, String rel) {
        try {
            // Read directly: a missing file throws IOException (caught below),
            // so no separate exists() open+prompt is needed to gate the read.
            return files.read(rel);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Caller-built salt per CONTRACTS-CRYPTO: first 16 bytes of
     * SHA256(int64BE(timeMillis) || int64BE(freeMemory) ||
     * int32BE(objectHash) || UTF8(password)).
     */
    private static byte[] makeSalt(String password) {
        // CLDC 1.1 has no SecureRandom and the phone exposes no entropy
        // source, so the salt is whitened out of the three things that do
        // vary between runs: the clock, the current heap free space, and a
        // fresh object's identity hash. The password is mixed in so two
        // devices that happen to agree on all three still diverge.
        byte[] pw = utf8(password);
        byte[] msg = new byte[8 + 8 + 4 + pw.length];
        long t = System.currentTimeMillis();
        long fm = Runtime.getRuntime().freeMemory();
        int oh = new Object().hashCode();
        // Big-endian packing written out by hand rather than through a
        // DataOutputStream, which would add a stream plus its buffer on top
        // of msg. The layout is pinned by CONTRACTS-CRYPTO; only the phone
        // derives a salt this way (nokcrypt.py has a real RNG).
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) t;
            t >>>= 8;
        }
        for (int i = 7; i >= 0; i--) {
            msg[8 + i] = (byte) fm;
            fm >>>= 8;
        }
        msg[16] = (byte) (oh >>> 24);
        msg[17] = (byte) (oh >>> 16);
        msg[18] = (byte) (oh >>> 8);
        msg[19] = (byte) oh;
        System.arraycopy(pw, 0, msg, 20, pw.length);
        byte[] h = Sha256.hash(msg);
        byte[] salt = new byte[16];
        System.arraycopy(h, 0, salt, 0, 16);
        return salt;
    }

    /** dk (64 bytes) -> VaultCrypto(dk[0..32), dk[32..64)). */
    private static VaultCrypto split(byte[] dk) {
        byte[] ek = new byte[32];
        byte[] mk = new byte[32];
        System.arraycopy(dk, 0, ek, 0, 32);
        System.arraycopy(dk, 32, mk, 0, 32);
        return new VaultCrypto(ek, mk);
    }

    /**
     * UTF-8 bytes. CLDC guarantees the encoding exists, but the checked
     * exception still has to be caught; the default-encoding fallback is
     * unreachable in practice.
     */
    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }
}
