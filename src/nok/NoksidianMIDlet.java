package nok;

import java.io.IOException;
import java.util.Calendar;
import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.midlet.MIDlet;

import nok.core.Base64;
import nok.core.Hmac;
import nok.core.NoteIndex;
import nok.core.Path;
import nok.core.Utf8;
import nok.core.VaultCrypto;
import nok.sync.Sync;
import nok.sync.SyncListener;
import nok.sys.Config;
import nok.sys.Files;
import nok.sys.IndexStore;
import nok.ui.ImageView;
import nok.ui.Library;
import nok.ui.Settings;
import nok.ui.Theme;
import nok.ui.Ui;
import nok.ui.UiDialog;
import nok.ui.UiDialogOwner;
import nok.ui.UiEditor;
import nok.ui.UiInput;
import nok.ui.UiInputOwner;
import nok.ui.UiList;
import nok.ui.UiListOwner;
import nok.ui.UiScreen;
import nok.ui.VaultPicker;
import nok.ui.Viewer;

/**
 * Application shell: lifecycle, navigation, vault bootstrap, search,
 * and the SyncListener bridge into the UI. Every screen it presents is a
 * themed custom Canvas (nok.ui.*) so the whole app honors nok.ui.Theme; no
 * native Alert / TextBox / List / Form is used in the app flow.
 *
 * <p>J2ME background for readers coming from desktop Java: a MIDlet is the
 * application entry point but has no main(). The phone's Java runtime
 * constructs this class and drives it through startApp / pauseApp /
 * destroyApp, and a user-driven quit ends the app only because it calls
 * notifyDestroyed() itself (see exit()). There is one Display per MIDlet, and
 * whatever
 * Displayable is handed to Display.setCurrent IS the visible screen. So
 * navigation here is literally "build the next screen and set it current" -
 * the platform keeps no back stack, which is why lastDir / curLib /
 * backTarget() exist to reconstruct where "back" should land.
 *
 * <p>Threading rules that shape nearly every method below: the runtime owns a
 * single UI event thread that delivers key events and paint calls. Blocking
 * it visibly freezes the phone, so anything slower than one file read - PBKDF2
 * unlock, vault scans, search, image decode, all network work - runs on a
 * throwaway Thread, usually behind a Busy screen.
 * Display.setCurrent is safe from any thread; mutating
 * the model of a screen that is already visible is not, hence the callSerially
 * in syncDone.
 *
 * <p>The dominant non-obvious cost on the target device is the JSR-75
 * permission prompt: an untrusted (unsigned) MIDlet asks the user "allow read
 * user data?" on essentially every FileConnection.open. A full-vault scan is
 * therefore not merely slow, it is a storm of ~159 modal prompts. Almost every
 * cache and shortcut in this file - the RMS index cache, the desktop-generated
 * "noksidian/index" file, the directory-listing cache, the read-without-a-
 * preceding-exists() pattern - exists to spend fewer opens, not fewer
 * milliseconds.
 */
public class NoksidianMIDlet extends MIDlet implements SyncListener {

    // ---- display, plus the vault-scoped state that initVault() rebuilds ----

    /**
     * The MIDlet's one and only Display. Public because a few nok.ui screens
     * need more than show(): callSerially hand-offs (ImageView) and
     * getCurrent() snapshots (UiDialog, VaultPicker).
     */
    public Display disp;
    /**
     * Raw vault file IO (list, exists, read, write, scan). Note CONTENT must go
     * through readBytes/writeBytes instead, so encryption stays transparent.
     */
    public Files files;          // null until vault chosen
    public Sync sync;            // null until vault chosen
    public NoteIndex index;      // built lazily; see ensureIndex()
    private volatile boolean indexBuilt; // has the index been built this session?
    private int indexEpoch;      // bumped on vault switch / invalidate (guards races)
    /** Guards indexBuilt/indexEpoch and the commit step of a finished scan. */
    private final Object indexLock = new Object();
    /**
     * Per-session in-memory cache of directory listings (dirRel -> Vector of
     * child names), so re-entering a folder in the same session is prompt-free.
     * Conservatively cleared whole on any vault mutation or switch.
     */
    private final Object dirCacheLock = new Object();
    private Hashtable dirCache = new Hashtable();
    public VaultCrypto crypto;   // null when vault unencrypted or still locked

    // ---- navigation / session bookkeeping ----

    /** Guards the one-time boot in startApp(), which also runs on every resume. */
    private boolean started;
    private boolean firstRunOfferPending; // offer Settings after unlock
    /** Vault-relative dir of the last Library shown; stands in for a back stack. */
    private String lastDir = "";
    /** Live Library instance, so sync callbacks can push status into the screen. */
    private Library curLib;
    /** One reused Viewer: rebuilding it per note would churn the ~2MB heap. */
    private Viewer viewer;
    /**
     * One dialog per run of sync failures. A flaky link retries on a timer, and
     * without this a lost connection would stack a dialog per pass. Cleared on
     * the next success so a later outage is reported again.
     */
    private boolean syncFailAlerted;

    /**
     * MIDP entry point, called on launch AND again after every resume from
     * pause (an incoming call is enough), so the boot work is behind the
     * 'started' latch. A vault that is encrypted does not land on a screen
     * here: initVault() hands the display to the async unlock flow, which
     * calls showStartScreen() itself once the key is in RAM.
     */
    protected void startApp() {
        if (disp == null) {
            disp = Display.getDisplay(this);
        }
        if (started) {
            return; // resumed from pause; display state is kept
        }
        started = true;
        Config.load();
        Theme.load();
        String vault = Config.get("vault", "");
        if (vault.length() > 0) {
            initVault();
            if (!vaultLocked()) {
                showStartScreen();
            }
            // else the unlock flow started by initVault() navigates on.
        } else {
            showWelcome();
        }
    }

    /**
     * Deliberately empty: nothing is torn down on a pause. The Sync engine
     * keeps its Timer and worker thread running, and the 'started' latch in
     * startApp() means a resume simply keeps the screen that was current.
     */
    protected void pauseApp() {
    }

    /**
     * Shutdown requested by the runtime rather than the user (task manager,
     * low memory, install/uninstall). Only Sync needs stopping (it owns a
     * Timer and a worker thread); Config.set() already persisted every setting
     * to RMS, so there is nothing to flush. The 'unconditional' flag is ignored
     * because refusing to die is never worth it here, and stop()'s exception is
     * swallowed for the same reason.
     */
    protected void destroyApp(boolean unconditional) {
        Sync s = sync;
        if (s != null) {
            try {
                s.stop();
            } catch (Exception e) {
            }
        }
    }

    private void showWelcome() {
        disp.setCurrent(new WelcomeScreen(this));
    }

    /** Persists the vault URL, boots it, and on first run offers Settings. */
    public void vaultChosen(String fileUrl) {
        if (fileUrl == null || fileUrl.length() == 0) {
            return;
        }
        String url = fileUrl;
        if (!url.endsWith("/")) {
            url = url + "/";
        }
        // Probe writability BEFORE persisting so a read-only root is
        // never saved as the vault. (File IO on the UI thread is ok-ish.)
        try {
            Files probe = new Files(url);
            probe.mkdirs("noksidian");
        } catch (Throwable t) {
            show(new VaultPicker(this));
            alertErr("Vault", "Cannot write to vault: " + t.toString());
            return;
        }
        boolean firstRun = Config.get("vault", "").length() == 0;
        // Store the slash-terminated form. Files appends the slash itself, so
        // this is purely about keeping the persisted value canonical - it is
        // read back verbatim by initVault() and displayed by the About screens.
        Config.set("vault", url);
        lastDir = "";
        initVault();
        firstRunOfferPending = firstRun;
        if (vaultLocked()) {
            return; // unlock flow owns the screen and finishes first run
        }
        firstRunOfferPending = false;
        if (firstRun) {
            offerSettings();
        } else {
            showLibrary("");
        }
    }

    /**
     * First-run nudge into Settings, so sync credentials get entered before
     * the user is dropped into a Library that will silently never sync.
     */
    private void offerSettings() {
        UiDialogOwner ow = new UiDialogOwner() {
            public void dialogResult(boolean positive) {
                if (positive) {
                    openSettings();
                } else {
                    showLibrary("");
                }
            }
        };
        new UiDialog(this, null, "Vault ready",
                "Vault saved. Configure GitHub sync now?", UiDialog.YES_NO,
                ow).show();
    }

    /**
     * Creates Files/NoteIndex/Sync for the configured vault. Unencrypted
     * (or remembered-key) vaults start sync immediately; a locked vault
     * defers to the async unlock flow, which finishes startup itself.
     */
    public void initVault() {
        String vault = Config.get("vault", "");
        if (vault.length() == 0) {
            return;
        }
        // Detach the outgoing engine before anything else can reach it; the
        // actual stop() is deferred to the worker below because it blocks.
        final Sync oldSync = sync;
        sync = null;
        crypto = null; // keys never survive a vault switch
        files = new Files(vault);
        encFlag = -1;  // re-probe the descriptor for the new vault
        invalidateDirCache(); // old vault's listings must never leak across
        index = new NoteIndex();
        synchronized (indexLock) {
            indexBuilt = false; // fresh vault: index must be (re)built lazily
            indexEpoch++;       // invalidate any in-flight rebuild of the old vault
        }
        new Thread(new Runnable() {
            public void run() {
                // stop() can block for seconds; never run it on the UI
                // thread. The replacement Sync is a fresh object, so the
                // late stop only flags the old worker.
                if (oldSync != null) {
                    try {
                        oldSync.stop();
                    } catch (Exception e) {
                    }
                }
                // The vault is NOT scanned here. On an untrusted MIDlet every
                // FileConnection.open under user-data prompts, so an eager
                // full-vault scan would fire one prompt per directory the
                // instant the vault opens. The index is built lazily on first
                // search / wikilink use (ensureIndex()); the state dir is
                // created lazily by the first state write (Files.write
                // auto-creates parents).
            }
        }).start();
        // Constructed now but NOT started: the unlock flow resumes on another
        // thread and needs an engine to hand the key to, while a still-locked
        // vault must never start syncing (it would push/merge ciphertext it
        // cannot read). finishVaultInit() does the start in both paths.
        sync = new Sync(files, this);
        if (vaultEncrypted() && !tryStoredKey()) {
            promptUnlock(); // async; ends in finishVaultInit()+showStartScreen()
            return;
        }
        finishVaultInit();
    }

    /** Applies crypto to the sync engine (when unlocked) and starts it. */
    private void finishVaultInit() {
        Sync s = sync;
        if (s == null) {
            return;
        }
        if (crypto != null) {
            s.setCrypto(crypto, "all".equals(Config.get("crypt.scope", "all")));
        }
        s.start();
    }

    /** Startup landing: resume the last note when configured, else library. */
    private void showStartScreen() {
        String last = Config.get("ui.last", "");
        if ("1".equals(Config.get("ui.resume", "0")) && last.length() > 0
                && files != null) {
            // Read directly instead of exists()+read: openNote falls back to
            // the library on any failure, so a since-deleted last note costs
            // no extra startup prompt.
            openNote(last);
        } else {
            showLibrary("");
        }
    }

    // ---- unlock flow (CONTRACTS-FEATURES.md section 4) ----
    //
    // The whole vault is guarded by one password. The 60-byte descriptor
    // _vault.nkv at the vault root holds everything needed to check it
    // (CONTRACTS-CRYPTO.md):
    //   0..4   magic "NKV1"       4    version   5    kdf id
    //   6..10  iterations, int32 big-endian
    //   10     salt length (16)   11..27 salt
    //   27     check length (32)  28..60 check = HMAC(macKey, check-msg)
    // dk = PBKDF2-HMAC-SHA256(password, salt, iters, 64 bytes), split into a
    // 32-byte AES key and a 32-byte MAC key. The 'check' field is what lets a
    // wrong password be rejected up front, without opening a single note.

    /** ASCII "noksidian-check-v1" (descriptor check-field message). */
    private static byte[] checkMsg() {
        String s = "noksidian-check-v1";
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }

    /** True when HMAC(dk[32..64), check-msg) equals descriptor bytes 28..60. */
    private static boolean checkMatches(byte[] desc, byte[] dk) {
        if (desc == null || desc.length < 60 || dk == null || dk.length != 64) {
            return false;
        }
        byte[] mk = new byte[32];
        System.arraycopy(dk, 32, mk, 0, 32);
        byte[] check = Hmac.sha256(mk, checkMsg());
        // Constant-time compare (OR of XORs, no early exit): a timing side
        // channel here would leak how much of a guessed password was right.
        int diff = 0;
        for (int i = 0; i < 32; i++) {
            diff |= check[i] ^ desc[28 + i];
        }
        return diff == 0;
    }

    /**
     * Splits the 64-byte derived key into its two halves: dk[0..32) is the
     * AES-256 content key, dk[32..64) the HMAC-SHA256 key.
     */
    private static VaultCrypto splitDk(byte[] dk) {
        byte[] ek = new byte[32];
        byte[] mk = new byte[32];
        System.arraycopy(dk, 0, ek, 0, 32);
        System.arraycopy(dk, 32, mk, 0, 32);
        return new VaultCrypto(ek, mk);
    }

    /** Validates a remembered dk (Config crypt.dk) against the descriptor. */
    private boolean tryStoredKey() {
        String b64 = Config.get("crypt.dk", "");
        if (b64.length() == 0) {
            return false;
        }
        try {
            byte[] dk = Base64.decode(b64);
            byte[] desc = files.read(VAULT_DESC);
            if (checkMatches(desc, dk)) {
                crypto = splitDk(dk);
                return true;
            }
        } catch (Throwable t) {
            // corrupt store entry: fall through to the password prompt
        }
        return false; // stale/invalid dk: ignore it and prompt
    }

    /** Masked-password prompt; Cancel exits (vault unusable while locked). */
    private void promptUnlock() {
        disp.setCurrent(buildUnlockInput());
    }

    /**
     * A fresh prompt per attempt (initial text ""), so a retry after a wrong
     * password starts from an empty field instead of the rejected one.
     */
    private UiInput buildUnlockInput() {
        UiInputOwner ow = new UiInputOwner() {
            public void inputOk(String value) {
                unlockWith(value);
            }

            public void inputCancel() {
                exit();
            }
        };
        return new UiInput(this, "Vault password",
                "Enter the password to unlock this vault:", "", true, ow);
    }

    /**
     * Reports a failed attempt and loops back to a blank prompt. Reached from
     * the unlock worker thread; it only builds new screens and calls
     * setCurrent, so no hand-off to the UI thread is needed.
     */
    private void unlockFail(String title, String msg) {
        UiDialogOwner ow = new UiDialogOwner() {
            public void dialogResult(boolean positive) {
                disp.setCurrent(buildUnlockInput());
            }
        };
        new UiDialog(this, null, title, msg, UiDialog.OK, ow).show();
    }

    /** PBKDF2 on a worker thread; derives dk ONCE and checks it manually. */
    private void unlockWith(final String pw) {
        disp.setCurrent(new Busy(this, "Unlocking..."));
        new Thread(new Runnable() {
            public void run() {
                try {
                    // The two length bytes (salt 16 at [10], check 32 at [27])
                    // are pinned, mirroring VaultCrypto.open: a descriptor from
                    // another format version is rejected outright instead of
                    // being misparsed into a bogus "wrong password".
                    byte[] desc = files.read(VAULT_DESC);
                    if (desc == null || desc.length < 60
                            || !VaultCrypto.isDescriptor(desc)
                            || desc[10] != 16 || desc[27] != 32) {
                        unlockFail("Vault", "Bad vault descriptor (_vault.nkv).");
                        return;
                    }
                    // Hand-assembled big-endian int32: CLDC has no ByteBuffer,
                    // and each byte needs & 0xff to undo sign extension.
                    int iters = ((desc[6] & 0xff) << 24)
                            | ((desc[7] & 0xff) << 16)
                            | ((desc[8] & 0xff) << 8) | (desc[9] & 0xff);
                    byte[] salt = new byte[16];
                    System.arraycopy(desc, 11, salt, 0, 16);
                    // Thousands of PBKDF2 rounds: seconds of solid CPU on this
                    // phone, which is why this runs off the UI thread. The
                    // check field is compared by hand rather than by calling
                    // VaultCrypto.open, because open() returns only the built
                    // VaultCrypto and drops the raw dk that offerRemember()
                    // needs to store.
                    byte[] dk = VaultCrypto.deriveKeys(pw, salt, iters);
                    if (!checkMatches(desc, dk)) {
                        unlockFail("Wrong password",
                                "That password does not open this vault.");
                        return;
                    }
                    crypto = splitDk(dk);
                    offerRemember(dk);
                } catch (Throwable t) {
                    unlockFail("Unlock failed", t.toString());
                }
            }
        }).start();
    }

    /** Optional crypt.dk storage, then finishes startup. */
    private void offerRemember(final byte[] dk) {
        UiDialogOwner ow = new UiDialogOwner() {
            public void dialogResult(boolean positive) {
                if (positive) {
                    // The derived key is stored, not the password: it is all
                    // the app needs, skips the multi-second PBKDF2 on every
                    // launch, and a stolen phone does not yield a password
                    // the owner may have reused elsewhere.
                    Config.set("crypt.dk", Base64.encode(dk));
                }
                finishVaultInit();
                if (firstRunOfferPending) {
                    firstRunOfferPending = false;
                    offerSettings();
                } else {
                    showStartScreen();
                }
            }
        };
        new UiDialog(this, null, "Vault unlocked",
                "Remember the password on this phone? (Stores the key on the"
                        + " phone - anyone holding it can read the vault.)",
                UiDialog.YES_NO, ow).show();
    }

    /**
     * Rescans the whole vault into the note index and refreshes the on-disk
     * cache. Safe from any thread; blocks while scanning (~one permission
     * prompt per directory on an untrusted MIDlet), so call it off the UI
     * thread. An epoch check makes a rebuild that started before a vault switch
     * or invalidate discard its now-stale result instead of marking an empty or
     * outdated index "ready".
     */
    public void rebuildIndex() {
        Files f = files;
        NoteIndex ix = index;
        if (f == null || ix == null) {
            return;
        }
        int epoch;
        synchronized (indexLock) {
            epoch = indexEpoch;
        }
        Vector v = new Vector();
        f.scanAll("", v);
        boolean committed = false;
        synchronized (indexLock) {
            if (epoch == indexEpoch && ix == index) {
                synchronized (ix) {
                    ix.clear();
                    for (int i = 0; i < v.size(); i++) {
                        ix.add((String) v.elementAt(i));
                    }
                }
                indexBuilt = true;
                committed = true;
            }
        }
        if (committed) {
            saveIndexCachePaths(f, v);
        }
    }

    /**
     * Makes the note index usable, cheaply. If it is already built this
     * session, returns at once. Otherwise it first tries the on-disk cache (one
     * file read) and only falls back to a full rescan when no valid cache
     * exists - so the expensive per-directory prompt storm happens at most once
     * ever, not once per session. Blocks; call off the UI thread.
     */
    public void ensureIndex() {
        if (indexBuilt) {
            return;
        }
        if (loadIndexCache()) {
            return;
        }
        rebuildIndex();
    }

    /**
     * Bumps the index epoch so a full rebuild that is already mid-scan (e.g.
     * from a sync pass) discards its now-stale snapshot on commit instead of
     * clobbering the incremental change made here.
     */
    private void bumpEpoch() {
        synchronized (indexLock) {
            indexEpoch++;
        }
    }

    /**
     * Follows a wikilink: makes the index ready (lazily, off the UI thread
     * behind a progress screen, from cache when possible), resolves the target,
     * then opens the note or offers to create it.
     */
    public void openWikilink(final String target) {
        if (indexBuilt) {
            String r = resolveTarget(target);
            if (r != null) {
                openNote(r);
            } else {
                confirmCreate(target);
            }
            return;
        }
        disp.setCurrent(new Busy(this, "Indexing vault..."));
        new Thread(new Runnable() {
            public void run() {
                try {
                    ensureIndex();
                    final String r = resolveTarget(target);
                    disp.callSerially(new Runnable() {
                        public void run() {
                            if (r != null) {
                                openNote(r);
                            } else {
                                confirmCreate(target);
                            }
                        }
                    });
                } catch (Throwable t) {
                    failToLibrary("Open link", target + ": " + t.toString());
                }
            }
        }).start();
    }

    /**
     * Resolves a wikilink target to a vault-relative path, or null. NoteIndex
     * synchronizes each method individually, but a rebuild clears and then
     * re-adds under the index's own monitor; taking that monitor here means a
     * lookup can never observe the half-empty window mid-rebuild.
     */
    private String resolveTarget(String target) {
        NoteIndex ix = index;
        if (ix == null) {
            return null;
        }
        synchronized (ix) {
            return ix.resolve(target);
        }
    }

    // ------------------------------------------------------------------
    // Index persistence + incremental maintenance
    //
    // The index cache lets search / wikilinks skip the full-vault rescan (and
    // its per-directory permission prompts) on later sessions. To stay correct
    // it must track file changes: when the index is already in memory we update
    // it and rewrite the cache (both O(1) file ops); when it is not, we drop
    // the on-disk cache so the next ensureIndex() rebuilds from scratch rather
    // than trusting a stale file.
    // ------------------------------------------------------------------

    /** A note file was created or overwritten. */
    public void noteWritten(String rel) {
        if (rel == null) {
            return;
        }
        invalidateDirCache(); // a new/changed file must show on the next listing
        if (indexBuilt) {
            NoteIndex ix = index;
            if (ix != null) {
                synchronized (ix) {
                    ix.add(rel);
                }
                bumpEpoch();
                saveIndexCache();
            }
        } else {
            deleteIndexCache();
        }
    }

    /** A note or folder was deleted (removes it and any children). */
    public void noteRemoved(String rel) {
        if (rel == null) {
            return;
        }
        invalidateDirCache(); // a removed file must vanish from the next listing
        if (indexBuilt) {
            NoteIndex ix = index;
            if (ix != null) {
                synchronized (ix) {
                    ix.removeSubtree(rel);
                }
                bumpEpoch();
                saveIndexCache();
            }
        } else {
            deleteIndexCache();
        }
    }

    /** A note or folder was renamed (re-prefixes it and any children). */
    public void noteRenamed(String oldRel, String newRel) {
        invalidateDirCache(); // rename changes the listing on both ends
        if (indexBuilt) {
            NoteIndex ix = index;
            if (ix != null) {
                synchronized (ix) {
                    ix.renameSubtree(oldRel, newRel);
                }
                bumpEpoch();
                saveIndexCache();
            }
        } else {
            deleteIndexCache();
        }
    }

    /**
     * Loads the index from its RMS cache. Returns false if unavailable. RMS is
     * prompt-free even for an untrusted suite, so this pays NO JSR-75 permission
     * prompt (unlike the FileConnection sidecar it replaces). The stored cache
     * is keyed by vault URL: a cache belonging to a different vault is rejected
     * (IndexStore.load returns null), so a vault switch cleanly rebuilds.
     */
    private boolean loadIndexCache() {
        Files f = files;
        NoteIndex ix = index;
        if (f == null || ix == null || vaultEncrypted()) {
            return false; // encrypted vaults never persist a plaintext path list
        }
        int epoch;
        synchronized (indexLock) {
            epoch = indexEpoch;
        }
        Vector v = IndexStore.load(f.vaultUrl());
        boolean fromRms = (v != null && !v.isEmpty());
        if (!fromRms) {
            // Fall back to a pre-built index FILE on the card at
            // "noksidian/index" (one vault-relative path per line). The desktop
            // tool generates it at copy time, so the phone NEVER has to scan the
            // vault itself (~159 directory opens = a permission-prompt storm).
            // Costs a single read prompt; it is then mirrored into RMS so later
            // loads are prompt-free, and it survives an app reinstall (which
            // wipes RMS) because the file lives on the SD card.
            v = readIndexFile(f);
        }
        if (v == null || v.isEmpty()) {
            return false;
        }
        synchronized (indexLock) {
            if (epoch != indexEpoch || ix != index) {
                return false; // vault switched while we were reading
            }
            synchronized (ix) {
                ix.clear();
                for (int i = 0; i < v.size(); i++) {
                    ix.add((String) v.elementAt(i));
                }
            }
            indexBuilt = true;
        }
        if (!fromRms) {
            // Seed RMS from the file so subsequent loads this install are
            // prompt-free (best-effort; a failure just means one read next time).
            try {
                IndexStore.save(f.vaultUrl(), v);
            } catch (Throwable t) {
            }
        }
        return true;
    }

    /**
     * Reads the desktop-generated index file "noksidian/index" from the card:
     * one vault-relative path per line. Returns null if absent/unreadable so the
     * caller falls through. One read = one permission prompt (vs ~159 for a live
     * scan).
     */
    private Vector readIndexFile(Files f) {
        try {
            byte[] b = f.read("noksidian/index");
            if (b == null || b.length == 0) {
                return null;
            }
            // Utf8.decode owns this note-content boundary: CESU-8 tolerant and
            // never throwing, so a card whose index lists an emoji-named note
            // decodes to the exact path the app stored.
            String all = Utf8.decode(b);
            // Hand-rolled line split: CLDC has no String.split and no regex.
            // Both '\r' and '\n' terminate a line and empty ones are dropped,
            // so a CRLF file written by the desktop tool parses identically.
            Vector v = new Vector();
            int start = 0;
            int n = all.length();
            for (int i = 0; i <= n; i++) {
                if (i == n || all.charAt(i) == '\n' || all.charAt(i) == '\r') {
                    if (i > start) {
                        String line = all.substring(start, i);
                        if (line.length() > 0) {
                            v.addElement(line);
                        }
                    }
                    start = i + 1;
                }
            }
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Rewrites the cache from the current in-memory index. */
    private void saveIndexCache() {
        Files f = files;
        NoteIndex ix = index;
        if (f == null || ix == null) {
            return;
        }
        Vector paths;
        synchronized (ix) {
            paths = ix.all();
        }
        saveIndexCachePaths(f, paths);
    }

    /** Writes the given path list to the RMS cache (skipped for encrypted vaults). */
    private void saveIndexCachePaths(Files f, Vector paths) {
        if (f == null || paths == null) {
            return;
        }
        if (vaultEncrypted()) {
            // Never persist a plaintext path list for an encrypted vault. If the
            // vault was just encrypted (e.g. a sync pull flipped it), also drop
            // any stale plaintext index left from before, rather than leaving it
            // in RMS indefinitely.
            deleteIndexCache();
            return;
        }
        IndexStore.save(f.vaultUrl(), paths); // never throws; best-effort
    }

    /** Drops the RMS cache so the next ensureIndex() rebuilds fresh. */
    private void deleteIndexCache() {
        IndexStore.clear();
    }

    // ------------------------------------------------------------------
    // Directory-listing memory cache
    //
    // Every files.list() on an untrusted MIDlet is one "allow read?" prompt, so
    // re-entering a folder would re-prompt. This per-session cache serves a
    // folder's names from memory on revisit. It is cleared WHOLE on any vault
    // mutation (note/folder create, delete, rename, save, sync) and on a vault
    // switch, so a stale listing can never survive a change that alters it.
    // ------------------------------------------------------------------

    /**
     * Directory child-name listing with a per-session memory cache: the first
     * visit lists (one prompt) and caches; later visits are prompt-free. A list
     * failure is not cached. Mirrors {@link Files#list} semantics.
     *
     * <p>What comes back is the cached Vector itself, not a copy: callers must
     * treat it as read-only, or they corrupt every later visit to that folder.
     */
    public Vector listDir(String rel) throws IOException {
        Files f = files;
        if (f == null) {
            return new Vector();
        }
        String key = (rel == null) ? "" : rel;
        Hashtable snap;
        synchronized (dirCacheLock) {
            snap = dirCache;
            Object c = snap.get(key);
            if (c instanceof Vector) {
                return (Vector) c;
            }
        }
        Vector names = f.list(key); // may throw; a failure is left uncached
        synchronized (dirCacheLock) {
            // Only cache when no invalidation swapped the table while we listed;
            // a listing that predates a concurrent mutation must not be stored.
            if (dirCache == snap) {
                dirCache.put(key, names);
            }
        }
        return names;
    }

    /** Clears the directory-listing cache (any mutation / vault switch). */
    public void invalidateDirCache() {
        synchronized (dirCacheLock) {
            dirCache = new Hashtable();
        }
    }

    // ---- encrypted-vault codec seam (CONTRACTS-FEATURES.md section 4) ----
    // Every content read/write in the UI goes through these; files without
    // the NKE1 magic pass through untouched, so mixed vaults keep working.

    /**
     * Vault descriptor at the vault root; its mere presence is what marks a
     * vault as encrypted. Named without a leading dot because the real E71
     * JSR-75 implementation rejects any path segment starting with '.'. It
     * syncs as an ordinary binary, is never itself encrypted, and the Library
     * hides it from listings.
     */
    public static final String VAULT_DESC = "_vault.nkv";

    /** Cached vaultEncrypted() result: -1 unknown, 0 plaintext, 1 encrypted. */
    private volatile int encFlag = -1;

    /**
     * True when the vault carries a descriptor. Cached because it is consulted
     * on every index-cache load/write and on every vaultLocked() test, and an
     * uncached answer costs a FileConnection.open (one permission prompt).
     */
    public boolean vaultEncrypted() {
        if (files == null) {
            return false;
        }
        int e = encFlag;
        if (e < 0) {
            // Probe into a local, then publish once. A concurrent reset to -1
            // (syncDone after a descriptor pull) can't interleave a stale
            // re-cache mid-method: worst case we do one extra probe next call.
            e = files.exists(VAULT_DESC) ? 1 : 0;
            encFlag = e;
        }
        return e == 1;
    }

    /**
     * Invalidates the cached vaultEncrypted() flag so the next call re-probes
     * the descriptor. Call whenever _vault.nkv is written or deleted.
     */
    public void vaultDescChanged() {
        encFlag = -1;
    }

    /** Encrypted but with no key in RAM: content reads will fail until unlock. */
    public boolean vaultLocked() {
        return vaultEncrypted() && crypto == null;
    }

    /**
     * Reads a file, decrypting it when it carries the NKE1 magic. Files
     * without the magic pass through byte-for-byte, which is what lets a
     * half-migrated (mixed plaintext/ciphertext) vault keep working. A decrypt
     * failure means the MAC did not verify - wrong key or tampering - and is
     * surfaced as IOException rather than returning plausible garbage.
     */
    public byte[] readBytes(String rel) throws IOException {
        byte[] b = files.read(rel);
        if (VaultCrypto.isEncrypted(b)) {
            if (crypto == null) {
                throw new IOException("vault locked");
            }
            try {
                return crypto.decrypt(b);
            } catch (RuntimeException e) {
                throw new IOException("decrypt " + rel + ": " + e.getMessage());
            }
        }
        return b;
    }

    public String readText(String rel) throws IOException {
        byte[] b = readBytes(rel);
        return Utf8.decode(b);
    }

    /**
     * Writes a file, encrypting it whenever a key is loaded. The descriptor is
     * exempt: it carries the salt and check field needed to derive that very
     * key, so encrypting it would leave the vault permanently unopenable. The
     * wall clock is passed in because CLDC has no secure RNG - VaultCrypto
     * derives the IV deterministically from path, plaintext hash and time.
     */
    public void writeBytes(String rel, byte[] data) throws IOException {
        if (crypto != null && !VAULT_DESC.equals(rel)) {
            data = crypto.encrypt(data, rel, System.currentTimeMillis());
        }
        files.write(rel, data);
    }

    public void writeText(String rel, String text) throws IOException {
        writeBytes(rel, Utf8.encode(text));
    }

    /**
     * Navigates to a folder listing and records it as the destination that
     * back() and dismissed dialogs return to.
     */
    public void showLibrary(String dirRel) {
        String dir = (dirRel == null) ? "" : dirRel;
        lastDir = dir;
        Library lib = new Library(this, dir);
        curLib = lib;
        disp.setCurrent(lib);
    }

    /** Reads the note on a short background thread, then shows the Viewer. */
    public void openNote(final String rel) {
        if (files == null || rel == null) {
            return;
        }
        disp.setCurrent(new Busy(this, "Loading " + Path.name(rel) + "..."));
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Read directly instead of exists()+read(): that halves the
                    // JSR-75 opens per note-open, and on an untrusted MIDlet each
                    // open is a separate "allow read user data?" prompt. Notes
                    // are only opened from places that know they exist; a genuine
                    // missing/unreadable file falls to the catch below.
                    presentNote(rel, readText(rel));
                } catch (Throwable t) {
                    failToLibrary("Open failed", rel + ": " + t.toString());
                }
            }
        }).start();
    }

    /**
     * Shows the shared Viewer for a note whose text is already in hand and
     * updates the resume marker. Lets a caller that has already read (or just
     * written) the content serve it WITHOUT a second read/open - see today().
     */
    private void presentNote(String rel, String text) {
        // Inline images embedded by basename (Obsidian "![[img.png]]") resolve to
        // their real subfolder path via the note index. Load it from the
        // prompt-free RMS cache if it was already built (by a prior Search), so
        // cross-folder images resolve without any file prompts. Do NOT scan the
        // vault here: scanning opens every directory and each open prompts on an
        // untrusted MIDlet - that turned note-open into a prompt storm. If no
        // cache exists yet, cross-folder images fall back to a "[image: name]"
        // placeholder until the index is built once (running a Search caches it
        // to RMS); after that, image notes resolve silently every session.
        if (text != null && text.indexOf("![") >= 0 && !indexBuilt) {
            loadIndexCache();
        }
        Viewer v = viewer;
        if (v == null) {
            v = new Viewer(this);
            viewer = v;
        }
        v.setNote(rel, text);
        disp.setCurrent(v);
        // Resume marker: only tracked (and only pays the RMS commit) when the
        // "Open last note" preference is on.
        if ("1".equals(Config.get("ui.resume", "0"))) {
            Config.set("ui.last", rel);
        }
    }

    /** Opens the themed editor; missing files start with empty content. */
    public void editNote(final String rel) {
        if (files == null || rel == null) {
            return;
        }
        if (!safeRel(rel)) { // defense in depth for untrusted callers
            alertErr("Editor", "Invalid note name.");
            return;
        }
        disp.setCurrent(new Busy(this, "Opening " + Path.name(rel) + "..."));
        new Thread(new Runnable() {
            public void run() {
                try {
                    // One open on the common readable path. readText also throws
                    // IOException for vault-locked / MAC-failure on files that
                    // EXIST; rethrow those so they reach failToLibrary instead
                    // of silently opening an empty editor over ciphertext.
                    String content;
                    try {
                        content = readText(rel);
                    } catch (IOException e) {
                        if (files.exists(rel)) {
                            throw e;
                        }
                        content = "";
                    }
                    // Length guard: an oversized note is opened read-only
                    // instead of loading a huge buffer into the editor.
                    if (content.length() > 200000) {
                        UiDialogOwner ow = new UiDialogOwner() {
                            public void dialogResult(boolean positive) {
                                openNote(rel);
                            }
                        };
                        new UiDialog(NoksidianMIDlet.this, null, "Editor",
                                "Note is too large to edit on this phone;"
                                        + " opening read-only.", UiDialog.OK,
                                ow).show();
                        return;
                    }
                    disp.setCurrent(new UiEditor(NoksidianMIDlet.this, rel,
                            content));
                } catch (Throwable t) {
                    failToLibrary("Edit failed", rel + ": " + t.toString());
                }
            }
        }).start();
    }

    /**
     * Persists an edit made in the UiEditor, notifies sync, rebuilds the
     * index, then reopens the note in the viewer. Runs the IO off the UI
     * thread; on failure it returns to the editor and reports the error.
     */
    public void saveEdited(final String rel, final String text) {
        if (files == null || rel == null) {
            return;
        }
        disp.setCurrent(new Busy(this, "Saving " + Path.name(rel) + "..."));
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Codec seam: encrypts transparently when the vault is
                    // encrypted and unlocked.
                    writeText(rel, text);
                    if (sync != null) {
                        sync.noteSaved(rel);
                    }
                    noteWritten(rel); // keep index + cache fresh, no rescan
                    openNote(rel);
                } catch (Throwable t) {
                    // Rebuild the editor from the data at hand so the unsaved
                    // text is never dropped (getCurrent() may be stale here).
                    UiEditor ed = new UiEditor(NoksidianMIDlet.this, rel,
                            text);
                    overlayInfo("Save failed", rel + ": " + t.toString(), ed);
                }
            }
        }).start();
    }

    /**
     * Opens today's daily note (&lt;daily.folder&gt;/YYYY-MM-DD.md), creating
     * it with a "# YYYY-MM-DD" heading if missing.
     */
    public void today() {
        if (files == null) {
            return;
        }
        Calendar cal = Calendar.getInstance();
        final String date = cal.get(Calendar.YEAR) + "-"
                + pad2(cal.get(Calendar.MONTH) + 1) + "-"
                + pad2(cal.get(Calendar.DAY_OF_MONTH));
        String folder = Config.get("daily.folder", "Daily").trim();
        // Untrusted config: a folder like "../x" must not escape the vault.
        folder = safeRel(folder) ? Path.normalize(folder) : "Daily";
        final String rel = Path.join(folder, date + ".md");
        disp.setCurrent(new Busy(this, "Opening " + date + ".md ..."));
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Read once and serve the text directly (presentNote): on the
                    // common "already opened today" path that is a single open,
                    // not exists()+open. Only a genuine miss creates the note; an
                    // existing-but-unreadable one (locked / MAC-fail) is reprobed
                    // and rethrown so it is never truncated.
                    String text;
                    try {
                        text = readText(rel);
                    } catch (IOException e) {
                        if (files.exists(rel)) {
                            throw e;
                        }
                        text = "# " + date + "\n";
                        writeText(rel, text);
                        noteWritten(rel);
                    }
                    presentNote(rel, text);
                } catch (Throwable t) {
                    alertErr("Today", rel + ": " + t.toString());
                }
            }
        }).start();
    }

    /** Zero-pads a calendar field to two digits. */
    private static String pad2(int v) {
        return (v < 10) ? "0" + v : String.valueOf(v);
    }

    /**
     * True when a vault-relative path built from untrusted input is safe:
     * no drive/scheme colon, no absolute prefix, and no "." / ".." / empty
     * segments after normalization (so it cannot escape the vault root).
     */
    private static boolean safeRel(String raw) {
        if (raw == null || raw.length() == 0) {
            return false;
        }
        if (raw.indexOf(':') >= 0 || raw.charAt(0) == '/'
                || raw.charAt(0) == '\\') {
            return false;
        }
        String norm = Path.normalize(raw);
        if (norm.length() == 0) {
            return false;
        }
        int start = 0;
        for (int i = 0; i <= norm.length(); i++) {
            if (i == norm.length() || norm.charAt(i) == '/') {
                String seg = norm.substring(start, i);
                if (seg.length() == 0 || seg.equals(".")
                        || seg.equals("..")) {
                    return false;
                }
                start = i + 1;
            }
        }
        return true;
    }

    /**
     * Wikilink-to-missing-note confirm used by the Viewer: asks Yes/No, then
     * creates + edits "&lt;target&gt;.md" on Yes, else returns to the caller.
     */
    public void confirmCreate(final String target) {
        if (!safeRel(target)) {
            alertErr("Create note", "Invalid note name.");
            return;
        }
        // backTarget() skips a transient Busy screen (e.g. the lazy "Indexing
        // vault..." shown while a wikilink is followed) so answering "No" can
        // never strand the user on an inert progress screen.
        final Displayable back = backTarget();
        UiDialogOwner ow = new UiDialogOwner() {
            public void dialogResult(boolean positive) {
                if (positive) {
                    editNote(target + ".md");
                } else if (back != null) {
                    show(back);
                } else {
                    back();
                }
            }
        };
        new UiDialog(this, null, "Create note",
                "\"" + target + "\" does not exist yet. Create it?",
                UiDialog.YES_NO, ow).show();
    }

    /**
     * Opens an image viewer. The work is on a thread because the ImageView
     * constructor itself does the file read and the bitmap decode, and it
     * documents that contract explicitly. It throws a RuntimeException("vault
     * locked") when the key is missing, hence the catch back to the Library.
     */
    public void openImage(final String rel) {
        if (files == null || rel == null) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    disp.setCurrent(new ImageView(NoksidianMIDlet.this, rel));
                } catch (Throwable t) {
                    failToLibrary("Image", rel + ": " + t.toString());
                }
            }
        }).start();
    }

    public void openSettings() {
        disp.setCurrent(new Settings(this));
    }

    public void showAbout() {
        overlayInfo("About Noksidian",
                "Noksidian 1.0\n\nAn Obsidian-style markdown vault for the"
                        + " Nokia E71.\nMarkdown viewer, editor and GitHub"
                        + " sync.\n\nVault: " + Config.get("vault", "(none)"),
                backTarget());
    }

    /** Filename + content search on a background thread with progress. */
    public void searchNotes(String query) {
        if (files == null || index == null) {
            return;
        }
        if (vaultLocked()) {
            alertErr("Search", "Vault is locked - unlock it first.");
            return;
        }
        final String q = (query == null) ? "" : query.trim();
        if (q.length() == 0) {
            back();
            return;
        }
        disp.setCurrent(new Busy(this, "Searching for \"" + q + "\"..."));
        new Thread(new Runnable() {
            public void run() {
                try {
                    doSearch(q);
                } catch (Throwable t) {
                    failToLibrary("Search failed", q + ": " + t.toString());
                }
            }
        }).start();
    }

    private void doSearch(String q) {
        String lq = q.toLowerCase();
        ensureIndex(); // lazily build the index on first search (bg thread)
        NoteIndex ix = index;
        Vector all;
        synchronized (ix) {
            all = ix.all();
        }
        Vector results = new Vector();
        // Two independent budgets keep a search finite on a slow card and a
        // ~2MB heap: the loop stops at 200 hits, and at most 200 files have
        // their CONTENT opened. Name matching is free (the index is already in
        // memory), so only the content scan is rationed.
        int scanned = 0;
        for (int i = 0; i < all.size() && results.size() < 200; i++) {
            String rel = (String) all.elementAt(i);
            boolean hit = rel.toLowerCase().indexOf(lq) >= 0;
            if (!hit && scanned < 200 && isTextFile(rel)) {
                long sz = files.size(rel);
                // 128KB ceiling: decoding a bigger note into a String (plus
                // its lowercased copy) risks exhausting the heap mid-search.
                if (sz >= 0 && sz <= 131072L) {
                    scanned++;
                    try {
                        String body = readText(rel);
                        if (body.toLowerCase().indexOf(lq) >= 0) {
                            hit = true;
                        }
                    } catch (Throwable t) {
                        // unreadable or "vault locked": skip this file
                    }
                }
            }
            if (hit) {
                results.addElement(rel);
            }
        }
        showResults(q, results);
    }

    /**
     * Presents the hits in a themed list. Called from the search worker
     * thread, so it must not touch an already-visible screen's model - it only
     * builds new screens and calls setCurrent.
     */
    private void showResults(final String q, final Vector results) {
        if (results.isEmpty()) {
            // Build the fallback Library up front so dismissing the "no
            // matches" dialog has somewhere concrete to land; the Busy screen
            // that is current right now is not a valid destination.
            final Library lib = new Library(this, lastDir);
            curLib = lib;
            UiDialogOwner ow = new UiDialogOwner() {
                public void dialogResult(boolean positive) {
                    show(lib);
                }
            };
            new UiDialog(this, null, "Search",
                    "No matches for \"" + q + "\".", UiDialog.OK, ow).show();
            return;
        }
        UiListOwner owner = new UiListOwner() {
            public void listSelect(int index) {
                if (index < 0 || index >= results.size()) {
                    return;
                }
                String rel = (String) results.elementAt(index);
                if (Path.isImage(rel)) {
                    openImage(rel);
                } else {
                    openNote(rel);
                }
            }

            public void listMenu(String command) {
                back();
            }

            public void listBack() {
                back();
            }
        };
        UiList res = new UiList(this, "Results (" + results.size() + ")",
                owner);
        res.setItems(results);
        res.setCommands("Menu", "Open");
        String[] menu = new String[1];
        menu[0] = "Back";
        res.setMenu(menu);
        disp.setCurrent(res);
    }

    /**
     * Whether a hit is worth opening for a content search. Everything else -
     * images, PDFs, binaries - is matched on its name only.
     */
    private boolean isTextFile(String rel) {
        return Path.isMarkdown(rel) || "txt".equals(Path.ext(rel));
    }

    /** Error dialog over the current screen; dismissal returns where the user was. */
    public void alertErr(String title, String msg) {
        overlayInfo(title, msg, backTarget());
    }

    /** Info dialog over the current screen (used by CryptoSetup summaries). */
    public void alertInfo(String title, String msg) {
        overlayInfo(title, msg, backTarget());
    }

    /** A themed OK dialog that returns to {@code back} when dismissed. */
    private void overlayInfo(String title, String msg, final Displayable back) {
        UiDialogOwner ow = new UiDialogOwner() {
            public void dialogResult(boolean positive) {
                if (back != null) {
                    show(back);
                }
            }
        };
        new UiDialog(this, null, title, msg, UiDialog.OK, ow).show();
    }

    /**
     * Best screen to return to after a dialog: the current screen when it is a
     * real destination (not a transient Busy), else the Library.
     */
    private Displayable backTarget() {
        Displayable cur = (disp != null) ? disp.getCurrent() : null;
        if (cur != null && !(cur instanceof Busy)) {
            return cur;
        }
        if (curLib != null) {
            return curLib;
        }
        Library lib = new Library(this, lastDir);
        curLib = lib;
        return lib;
    }

    /** Shows an error dialog whose dismissal lands on a fresh Library. */
    private void failToLibrary(String title, String msg) {
        final Library lib = new Library(this, lastDir);
        curLib = lib;
        overlayInfo(title, msg, lib);
    }

    public void show(Displayable d) {
        if (d != null && disp != null) {
            disp.setCurrent(d);
        }
    }

    /** Returns to the Library of the last-visited directory. */
    public void back() {
        showLibrary(lastDir);
    }

    /**
     * User-initiated shutdown. destroyApp() is NOT invoked when a MIDlet ends
     * via notifyDestroyed(), so Sync has to be stopped here as well - nothing
     * else cancels its Timer and worker thread.
     */
    public void exit() {
        Sync s = sync;
        if (s != null) {
            try {
                s.stop();
            } catch (Exception e) {
            }
        }
        notifyDestroyed();
    }

    // ---- SyncListener ----

    /**
     * Progress line from the sync worker thread. Library.setStatus only stores
     * a String and requests a repaint, both of which are safe off the UI
     * thread, so this needs no callSerially hand-off (unlike syncDone's
     * refresh(), which rebuilds the listing).
     */
    public void syncStatus(String msg) {
        Library l = curLib;
        if (l != null) {
            l.setStatus(msg);
        }
    }

    /**
     * End of a sync pass, on the sync worker thread. Everything cached about
     * the vault - encrypted flag, directory listings, note index - may have
     * been invalidated by files the pass pulled or deleted, so this is the one
     * place that has to distrust all of them at once.
     */
    public void syncDone(boolean ok, String summary) {
        // A pulled _vault.nkv could have flipped the vault's encrypted state;
        // re-probe so saveIndexCachePaths never persists a plaintext path list
        // for a now-encrypted vault.
        encFlag = -1;
        // Sync may have created/deleted files on disk; drop the cached listings
        // so the refresh() below (and later folder visits) see the new state.
        invalidateDirCache();
        Library l = curLib;
        if (ok) {
            syncFailAlerted = false;
            if (l != null) {
                l.setStatus("Sync: " + summary);
            }
            // Sync may have changed many files on disk. If the index is built,
            // rescan to reflect them. If not, we must still drop the on-disk
            // cache: otherwise the next lazy build would load the pre-sync
            // cache as authoritative and silently miss pulled notes (search
            // blank, wikilinks offer to "create" notes that now exist).
            if (indexBuilt) {
                rebuildIndex();
            } else {
                deleteIndexCache();
            }
            Display dd = disp;
            if (l != null && dd != null) {
                // refresh() mutates the List; run it on the UI event thread
                // so it cannot race commandAction reading the selection.
                dd.callSerially(new Runnable() {
                    public void run() {
                        Library l2 = curLib;
                        Display d2 = disp;
                        if (l2 != null && d2 != null && d2.getCurrent() == l2) {
                            l2.refresh();
                        }
                    }
                });
            }
        } else {
            if (l != null) {
                l.setStatus("Sync failed: " + summary);
            }
            if (!syncFailAlerted) {
                syncFailAlerted = true;
                overlayInfo("Sync failed", summary, backTarget());
            }
        }
    }

    // ------------------------------------------------------------------
    // Themed shell screens (welcome + transient busy indicator)
    // ------------------------------------------------------------------

    /** First-run landing: choose a vault or exit, fully themed. */
    private static final class WelcomeScreen extends UiScreen {

        WelcomeScreen(NoksidianMIDlet m) {
            super(m, "Noksidian");
            this.leftLabel = "Choose vault";
            this.rightLabel = "Exit";
        }

        protected void paintBody(Graphics g, int cx, int cy, int cw, int ch) {
            Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                    Font.SIZE_SMALL);
            g.setFont(f);
            g.setColor(Theme.text);
            Vector lines = Ui.wrap("Welcome to Noksidian, a markdown vault"
                    + " for your phone.\n\nChoose a folder (for example on the"
                    + " memory card) to use as your note vault.", f, cw - 8);
            int y = cy + 4;
            for (int i = 0; i < lines.size(); i++) {
                g.drawString((String) lines.elementAt(i), cx + 4, y,
                        Graphics.TOP | Graphics.LEFT);
                y += f.getHeight();
            }
        }

        protected void onLeftSoft() {
            m.show(new VaultPicker(m));
        }

        protected void onSelect() {
            m.show(new VaultPicker(m));
        }

        protected void onRightSoft() {
            m.exit();
        }
    }

    /** Transient themed "busy" screen shown during short blocking work. */
    // backTarget() tests for this exact type to recognise a screen that is not
    // a real destination, so dismissing a dialog can never strand the user on
    // an inert progress screen. Purely decorative: it declares no key handlers,
    // which also means a wedged worker leaves no way out but the End key.
    private static final class Busy extends UiScreen {

        private final String msg;

        Busy(NoksidianMIDlet m, String msg) {
            super(m, "Noksidian");
            this.msg = (msg != null) ? msg : "";
        }

        protected void paintBody(Graphics g, int cx, int cy, int cw, int ch) {
            Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                    Font.SIZE_SMALL);
            g.setFont(f);
            g.setColor(Theme.text);
            int tw = f.stringWidth(msg);
            int x = cx + (cw - tw) / 2;
            if (x < cx + 2) {
                x = cx + 2;
            }
            int y = cy + (ch - f.getHeight()) / 2;
            if (y < cy) {
                y = cy;
            }
            g.drawString(msg, x, y, Graphics.TOP | Graphics.LEFT);
        }
    }

}
