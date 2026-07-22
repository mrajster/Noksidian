package nok.sync;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import nok.core.Json;
import nok.core.Merge;
import nok.core.MergeResult;
import nok.core.Path;
import nok.core.VaultCrypto;
import nok.sys.Config;
import nok.sys.Files;

/**
 * Sync engine: reconciles the local vault with a GitHub repo.
 *
 * Owns exactly ONE worker thread (all file + network IO happens there)
 * and ONE java.util.Timer (periodic syncs, 20s save debounce, failure
 * backoff retries at 60s / 2m / 5m / 15m). requestSync() is coalesced
 * via a pending flag and wakes any backoff wait early.
 *
 * State file .noksidian/state.json:
 *   {"files":{"<path>":{"sha":"...","bin":{"sz":123,"mt":456}?}},"last":millis}
 * Markdown entries carry only the sha plus an exact base copy of the
 * last-synced bytes under .noksidian/base/<path> (used for 3-way merge).
 * Binary entries record size/mtime as a modification heuristic.
 *
 * The dotted spelling above is historical: the directory is really
 * "noksidian/" with no leading dot, for the reason spelled out at
 * STATE_PATH below.
 *
 * Threading: the entire public API (start/stop/requestSync/noteSaved/
 * isSyncing/lastSummary/setCrypto) is safe to call from the MIDP event
 * thread. requestSync/noteSaved/isSyncing/lastSummary/setCrypto return at
 * once; start() and stop() do NOT - both poll a previous worker's isAlive
 * (bounded to 15s and 4s respectively), so neither belongs in a paint or
 * keypress path. Everything from workerLoop() down runs on the worker
 * thread ONLY - it is the sole place that touches Files or the network.
 * Progress reaches the UI through SyncListener, which is therefore always
 * invoked off the event thread.
 *
 * Encrypted vaults (CONTRACTS-FEATURES.md section 4): when crypto is set
 * every merge happens on PLAINTEXT. scope=all keeps ciphertext in the
 * repo (bytes flow as stored, merged results are encrypted once for the
 * push, the local write and the base copy); scope=local keeps the repo
 * plaintext (decrypt before push, encrypt after pull) while local files
 * and base copies stay ciphertext, so change detection remains a plain
 * local-vs-base byte compare in both scopes. The state sha always tracks
 * the REMOTE blob. _vault.nkv is synced raw both ways, never merged and
 * never encrypted/decrypted. A pass never runs while the vault is locked.
 */
public final class Sync {

    // NKV1 vault descriptor at the vault root. The reason it is exempted from
    // every transform below: it carries the salt and KDF parameters the key is
    // DERIVED from, so encrypting it under that same key would be circular and
    // would leave the vault permanently unopenable.
    private static final String VAULT_DESC = "_vault.nkv";
    // State dir has NO leading dot: Symbian JSR-75 Connector.open rejects any
    // path segment beginning with '.' ("url is not valid"), so ".noksidian"
    // cannot be created on a real E71 (only MicroEmulator tolerated it).
    private static final String STATE_PATH = "noksidian/state.json";
    private static final String STATE_TMP = "noksidian/state.tmp";
    private static final String BASE_DIR = "noksidian/base/";
    private static final String TRASH_DIR = "noksidian/trash/";
    // Size ceiling, in bytes, for anything that crosses the wire. A blob is
    // resident in the ~2MB heap several times over during a transfer (raw
    // bytes, the base64 text of the JSON body, the decoded copy), so ~1.5MB
    // is roughly where a pull stops fitting. Oversize files are skipped in
    // place: never pulled, never pushed, never deleted, state left as is.
    private static final long MAX_BLOB = 1500000L;
    // Delay measured from the LAST save, not the first: a burst of edits
    // across several notes collapses into one pass instead of one push each.
    private static final long DEBOUNCE_MS = 20000L;
    // Grace period after start() before the first pass, so opening the vault
    // and painting the first Library screen are not fighting the radio.
    private static final long INITIAL_MS = 5000L;
    // Retry ladder after a failed pass: 60s, 2m, 5m, 15m, then stuck at 15m
    // until a pass succeeds (which resets backoffIdx to 0).
    private static final long[] BACKOFF = {60000L, 120000L, 300000L, 900000L};

    private final Files files;
    private final SyncListener listener;
    private final GitHub github;

    // Encrypted-vault seam (CONTRACTS-FEATURES.md section 4): set by the
    // midlet after unlock / settings change; volatile because the worker
    // thread reads them mid-pass.
    private volatile VaultCrypto crypto = null;
    private volatile boolean scopeAll = true;

    /**
     * Installs the vault key and the repo scope. Called by the midlet once
     * the vault is unlocked, and again from Settings and CryptoSetup
     * whenever crypt.scope or the key itself changes. A null key
     * (unencrypted vault, or one still locked) turns every transform below
     * into a pass-through. encryptRemote true means scope "all" (the repo
     * holds ciphertext); false means scope "local".
     */
    public void setCrypto(VaultCrypto c, boolean encryptRemote) {
        crypto = c;
        scopeAll = encryptRemote;
    }

    // Monitor for every scheduling field below AND the worker's park/wake
    // (lock.wait / lock.notifyAll). Deliberately never held across a file or
    // network call, so the UI thread can always request or stop a sync.
    private final Object lock = new Object();
    private final Object passLock = new Object(); // serializes sync passes
    private boolean running = false;
    private volatile boolean stopped = false;  // aborts a pass between per-file ops
    // Coalescing flag: any number of requestSync() calls collapse into at
    // most one further pass, so bursts of saves or taps cannot queue up.
    private boolean pending = false;
    // Written only by the worker; isSyncing() reads it WITHOUT taking lock so
    // a caller can never block behind a pass. Volatile is what makes that safe.
    private volatile boolean syncing = false;
    private String lastSummary = "";
    private Thread worker = null;
    private Thread stale = null;   // worker of a previous start/stop cycle
    private Timer timer = null;
    private TimerTask debounceTask = null;
    private TimerTask retryTask = null;
    // Index into BACKOFF; advanced by scheduleRetry, reset by a good pass.
    private int backoffIdx = 0;

    // per-pass working data (worker thread only)
    // stateFiles: vault path -> Json Hashtable {"sha":..,"bin":{..}?}, i.e.
    // the parsed "files" member of state.json. Mutated in place across a pass
    // and flushed by saveState(); mutations counts state changes so far in
    // THIS pass (mutated() flushes on every 10th), passTotal is the union size
    // and serves only as the progress denominator.
    private Hashtable stateFiles = null;
    private int cPulled, cPushed, cMerged, cConflicts, mutations, passTotal;

    public Sync(Files files, SyncListener listener) {
        this.files = files;
        this.listener = listener;
        this.github = new GitHub();
    }

    // ------------------------------------------------------------------
    // lifecycle / public API
    // ------------------------------------------------------------------

    /** Starts the worker; with sync.auto=1 schedules periodic + initial sync. */
    public void start() {
        Thread old;
        synchronized (lock) {
            if (running) {
                return;
            }
            old = stale;
            stale = null;
        }
        // Bounded wait (outside the lock) for a stopped worker that may
        // still be mid-pass, so two passes never run concurrently. CLDC
        // has no join(millis), so poll isAlive(). If it outlasts the
        // bound we proceed anyway: the identity check in workerLoop()
        // guarantees the straggler exits after its pass without ever
        // consuming pending or starting another pass.
        if (old != null && old != Thread.currentThread()) {
            long deadline = System.currentTimeMillis() + 15000L;
            try {
                while (old.isAlive()
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200L);
                }
            } catch (InterruptedException e) {
                // proceed
            }
        }
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            stopped = false;
            timer = new Timer();
            worker = new Thread(new Runnable() {
                public void run() {
                    workerLoop();
                }
            });
            worker.start();
            if ("1".equals(Config.get("sync.auto", "1"))) {
                int mins = Config.getInt("sync.interval", 15);
                if (mins < 1) {
                    mins = 1;
                }
                long period = mins * 60000L;
                try {
                    timer.schedule(new TimerTask() {
                        public void run() {
                            requestSync("auto");
                        }
                    }, period, period);
                    timer.schedule(new TimerTask() {
                        public void run() {
                            requestSync("startup");
                        }
                    }, INITIAL_MS);
                } catch (Throwable t) {
                    // timer already cancelled; ignore
                }
            }
        }
    }

    /**
     * Stops the worker thread and cancels the timer. Sets the stopped
     * flag (doPass aborts between per-file operations), interrupts the
     * worker and waits (bounded to 4s so a hung network call cannot
     * block exit) for it to quiesce, so notifyDestroyed() never tears
     * the VM down mid-write (Files.write truncates before writing).
     */
    public void stop() {
        Timer t;
        Thread w;
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            stopped = true;
            pending = false;
            w = worker;
            stale = worker;    // start() joins it before spawning a new one
            worker = null;
            t = timer;
            timer = null;
            debounceTask = null;
            retryTask = null;
            lock.notifyAll();
        }
        if (t != null) {
            try {
                t.cancel();
            } catch (Throwable th) {
                // ignore
            }
        }
        if (w != null && w != Thread.currentThread()) {
            try {
                w.interrupt();
            } catch (Throwable th) {
                // ignore
            }
            // CLDC 1.1 has no join(millis); poll isAlive with a deadline.
            long deadline = System.currentTimeMillis() + 4000L;
            try {
                while (w.isAlive() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50L);
                }
            } catch (InterruptedException e) {
                // proceed
            }
        }
    }

    /** Requests a sync pass (async, coalesced). No-op when unconfigured. */
    public void requestSync(String reason) {
        // reason is diagnostic only and currently unread; it stays in the
        // signature because every call site names its trigger ("auto",
        // "startup", "saved", "retry", "manual", "crypto"), which is the
        // fastest way to tell from the call graph what can kick a sync.
        if (!github.configured()) {
            return;
        }
        synchronized (lock) {
            if (!running) {
                return;
            }
            if (retryTask != null) {
                // wake any backoff wait early
                try {
                    retryTask.cancel();
                } catch (Throwable t) {
                    // ignore
                }
                retryTask = null;
            }
            pending = true;
            lock.notifyAll();
        }
    }

    /** Debounced after-save sync: fires ~20s after the LAST save. */
    public void noteSaved(String rel) {
        // rel is unused: a pass reconciles the whole vault regardless of what
        // was saved, so one vault-wide debounce timer replaces per-file
        // bookkeeping (and costs one TimerTask instead of N).
        synchronized (lock) {
            if (!running || timer == null) {
                return;
            }
            if (debounceTask != null) {
                try {
                    debounceTask.cancel();
                } catch (Throwable t) {
                    // ignore
                }
            }
            debounceTask = new TimerTask() {
                public void run() {
                    requestSync("saved");
                }
            };
            try {
                timer.schedule(debounceTask, DEBOUNCE_MS);
            } catch (Throwable t) {
                // timer cancelled; ignore
            }
        }
    }

    /** True while a sync pass is executing on the worker thread. */
    public boolean isSyncing() {
        return syncing;
    }

    /** Summary of the last successful pass; "" initially. */
    public String lastSummary() {
        synchronized (lock) {
            return lastSummary;
        }
    }

    // ------------------------------------------------------------------
    // worker thread
    // ------------------------------------------------------------------

    /**
     * The worker's entire life: park on lock until pending is set, run one
     * pass, repeat forever. The only exits are running going false or this
     * thread no longer being the current worker, which is how a superseded
     * thread from an earlier start/stop cycle retires itself.
     */
    private void workerLoop() {
        while (true) {
            synchronized (lock) {
                // Identity check: a worker resurrected by a stop()/start()
                // cycle (stop() doesn't join) must exit instead of parking
                // or stealing the new worker's pending request.
                while (running && !pending
                        && worker == Thread.currentThread()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        // spurious wakeup; loop
                    }
                }
                if (!running || worker != Thread.currentThread()) {
                    return;
                }
                pending = false;
            }
            if (!github.configured()) {
                done(false, "not configured");
                continue;
            }
            // Encrypted vault still locked: skip the pass entirely. No
            // retry/backoff escalation (like "not configured"); periodic
            // syncs keep reporting it and the unlock flow calls
            // requestSync itself.
            if (files.exists(VAULT_DESC) && crypto == null) {
                done(false, "vault locked");
                continue;
            }
            // passLock serializes passes across stop()/start() cycles: even
            // when start()'s bounded wait expires while a superseded worker
            // is still mid-pass, the new worker blocks here until that pass
            // ends, so two passes can never mutate shared per-pass state or
            // the state file concurrently.
            synchronized (passLock) {
                synchronized (lock) {
                    if (!running || worker != Thread.currentThread()) {
                        return; // superseded while queued; never run a pass
                    }
                }
                syncing = true;
                boolean ok = false;
                String msg;
                try {
                    msg = doPass();
                    ok = true;
                } catch (IOException e) {
                    String m = e.getMessage();
                    msg = "sync failed: " + (m == null ? e.toString() : m);
                } catch (Throwable t) {
                    msg = "sync failed: " + t.toString();
                }
                syncing = false;
                if (ok) {
                    synchronized (lock) {
                        backoffIdx = 0;
                        lastSummary = msg;
                    }
                    done(true, msg);
                } else {
                    done(false, msg);
                    scheduleRetry();
                }
            }
        }
    }

    private void scheduleRetry() {
        long delay;
        synchronized (lock) {
            if (!running || timer == null) {
                return;
            }
            delay = BACKOFF[backoffIdx];
            if (backoffIdx < BACKOFF.length - 1) {
                backoffIdx++;
            }
            if (retryTask != null) {
                try {
                    retryTask.cancel();
                } catch (Throwable t) {
                    // ignore
                }
            }
            retryTask = new TimerTask() {
                public void run() {
                    requestSync("retry");
                }
            };
            try {
                timer.schedule(retryTask, delay);
            } catch (Throwable t) {
                retryTask = null;
                return;
            }
        }
        status("sync: retry in " + (delay / 1000) + "s");
    }

    // ------------------------------------------------------------------
    // one sync pass (worker thread only)
    // ------------------------------------------------------------------

    /**
     * One full reconciliation: build the union of remote, local and state
     * paths, drive every path through handlePath, then persist state.
     * Returns the summary line for syncDone. Any IOException aborts the
     * pass part-way and escalates the retry backoff, so each per-file step
     * must leave state.json consistent with what actually reached flash and
     * GitHub - a resumed pass simply re-derives the rest.
     */
    private String doPass() throws IOException {
        cPulled = 0;
        cPushed = 0;
        cMerged = 0;
        cConflicts = 0;
        mutations = 0;
        status("sync: listing remote...");
        Vector tree = github.listTree();
        Hashtable remoteMap = new Hashtable();
        Hashtable remoteOversize = new Hashtable();
        int tn = tree.size();
        for (int i = 0; i < tn; i++) {
            GhEntry e = (GhEntry) tree.elementAt(i);
            String p = Path.normalize(e.path);
            if (badPath(p) || skipRemote(p)) {
                continue;
            }
            // Too big to bring into the heap. Recorded in a side table rather
            // than simply dropped: a path missing from remoteMap looks exactly
            // like a remote DELETION to handlePath, which would trash the
            // perfectly good local copy of a file we merely cannot fetch.
            if (e.size > MAX_BLOB) {
                status("sync: skip large " + p);
                remoteOversize.put(p, Boolean.TRUE);
                continue;
            }
            remoteMap.put(p, e);
        }
        Vector localList = new Vector();
        files.scanAll("", localList);
        Hashtable localHas = new Hashtable();
        int ln = localList.size();
        for (int i = 0; i < ln; i++) {
            localHas.put(localList.elementAt(i), Boolean.TRUE);
        }
        // Re-read from flash on every pass rather than cached in the field: a
        // crypto migration (CryptoSetup wipes state.json and base/ to force a
        // re-baseline) or a hand-edited state file must be picked up here.
        loadState();
        // Union of remote + local + state paths, remote first.
        Vector union = new Vector();
        Hashtable seen = new Hashtable();
        Enumeration rk = remoteMap.keys();
        while (rk.hasMoreElements()) {
            String p = (String) rk.nextElement();
            seen.put(p, Boolean.TRUE);
            union.addElement(p);
        }
        for (int i = 0; i < ln; i++) {
            String p = (String) localList.elementAt(i);
            if (!seen.containsKey(p)) {
                seen.put(p, Boolean.TRUE);
                union.addElement(p);
            }
        }
        // State-only paths must be in the union too: a path recorded in state
        // yet present on neither side means both sides deleted it, and only
        // this third source can surface it so the stale entry gets dropped.
        Enumeration sk = stateFiles.keys();
        while (sk.hasMoreElements()) {
            String p = (String) sk.nextElement();
            if (!seen.containsKey(p)) {
                seen.put(p, Boolean.TRUE);
                union.addElement(p);
            }
        }
        passTotal = union.size();
        for (int i = 0; i < passTotal; i++) {
            if (stopped) {
                throw new IOException("stopped");
            }
            String p = (String) union.elementAt(i);
            if (remoteOversize.containsKey(p)) {
                // present remotely but oversize: pure no-op (never treated
                // as a remote deletion; local copy, base and state stay)
                continue;
            }
            handlePath(p, (GhEntry) remoteMap.get(p), localHas.containsKey(p), i + 1);
        }
        if (stopped) {
            throw new IOException("stopped");
        }
        saveState();
        String sum = cPulled + " pulled, " + cPushed + " pushed, " + cMerged + " merged";
        if (cConflicts > 0) {
            sum = sum + ", " + cConflicts + " conflicts";
        }
        return sum;
    }

    /**
     * Routes one path through the 2x2 table (exists remotely? exists
     * locally?) crossed with "do we hold sync state for it?". The state
     * entry is the only thing that separates a brand-new file from a
     * deleted one: with NO state a one-sided file was just created there
     * (pull or push it), with state it existed on both sides last time and
     * has since been deleted on the empty side (propagate the delete).
     * idx is the 1-based position within the pass, used only for progress.
     */
    private void handlePath(String path, GhEntry re, boolean hasLocal, int idx)
            throws IOException {
        Hashtable st = Json.obj(stateFiles.get(path));
        // Same ceiling applied outbound. A huge local file is left entirely
        // alone - state untouched - rather than half-pushed or forgotten.
        if (hasLocal && files.size(path) > MAX_BLOB) {
            status("sync: skip large " + path);
            return;
        }
        if (re != null && !hasLocal) {
            if (st == null) {
                pullNew(path, re, idx);
            } else {
                deleteOnRemote(path, re, idx);
            }
        } else if (re == null && hasLocal) {
            if (st == null) {
                pushNew(path, idx);
            } else {
                trashLocal(path, idx);
            }
        } else if (re != null && hasLocal) {
            if (st == null) {
                firstContact(path, re, idx);
            } else {
                reconcile(path, re, st, idx);
            }
        } else if (st != null) {
            // gone on both sides: drop stale state + base copy
            stateFiles.remove(path);
            dropBase(path);
            mutated();
        }
    }

    /** Remote only, no state: pull the file down (+ base copy for md). */
    private void pullNew(String path, GhEntry re, int idx) throws IOException {
        status("sync: pulling " + idx + "/" + passTotal);
        byte[] b = toLocal(path, github.getBlob(re.sha));
        if (files.exists(path)) {
            // created locally during the network fetch; do not clobber it,
            // it will be reconciled as first contact on the next pass
            status("sync: skip changed " + path);
            return;
        }
        files.write(path, b);
        boolean md = Path.isMarkdown(path);
        if (md) {
            files.write(BASE_DIR + path, b);
        }
        putState(path, re.sha, md ? null : binInfo(path, b.length));
        cPulled++;
    }

    /** Remote only, in state: it was deleted locally -> delete on GitHub. */
    private void deleteOnRemote(String path, GhEntry re, int idx) throws IOException {
        status("sync: pushing " + idx + "/" + passTotal);
        github.deleteFile(path, re.sha, "Noksidian: delete " + path);
        stateFiles.remove(path);
        dropBase(path);
        mutated();
        cPushed++;
    }

    /** Local only, no state: brand new file -> push (create). */
    private void pushNew(String path, int idx) throws IOException {
        status("sync: pushing " + idx + "/" + passTotal);
        byte[] b = files.read(path);
        byte[] pb = toRemote(path, b);
        if (pb == null) {
            // local ciphertext fails its mac: keep it, skip the push
            // (logged + retried every pass, never crashes the pass)
            status("sync: mac mismatch " + path);
            return;
        }
        String sha = github.putFile(path, pb, null, "Noksidian: add " + path);
        boolean md = Path.isMarkdown(path);
        if (md) {
            files.write(BASE_DIR + path, b);
        }
        putState(path, sha, md ? null : binInfo(path, b.length));
        cPushed++;
    }

    /** Local only, in state: deleted remotely -> move local into trash. */
    private void trashLocal(String path, int idx) throws IOException {
        status("sync: pulling " + idx + "/" + passTotal);
        byte[] b = files.read(path);
        boolean saved = false;
        try {
            files.write(uniqueTrashPath(TRASH_DIR + path), b);
            saved = true;
        } catch (IOException e) {
            // structural collision (an old trash FILE where a parent
            // directory is now needed); retry with a flattened name
        }
        if (!saved) {
            try {
                files.write(uniqueTrashPath(TRASH_DIR + path.replace('/', '~')), b);
                saved = true;
            } catch (IOException e) {
                // give up on trashing this file; keep the pass alive
            }
        }
        if (!saved) {
            status("sync: trash failed " + path);
            return; // local file + state kept; retried next pass
        }
        files.delete(path);
        stateFiles.remove(path);
        dropBase(path);
        mutated();
        cPulled++;
    }

    /** First free trash target: t, then t.1, t.2, ... (never overwrites). */
    private String uniqueTrashPath(String t) {
        if (!files.exists(t)) {
            return t;
        }
        int n = 1;
        while (files.exists(t + "." + n)) {
            n++;
        }
        return t + "." + n;
    }

    /** Both sides exist, no state: first contact. */
    private void firstContact(String path, GhEntry re, int idx) throws IOException {
        boolean md = Path.isMarkdown(path);
        byte[] lb = files.read(path);
        byte[] rb = github.getBlob(re.sha);
        // Compare in the remote domain (plaintext when scope=local, raw
        // stored bytes otherwise); base copies always hold LOCAL bytes so
        // change detection stays a plain byte compare.
        byte[] lr = toRemote(path, lb);
        if (lr != null && bytesEqual(lr, rb)) {
            if (md) {
                files.write(BASE_DIR + path, lb);
            }
            putState(path, re.sha, md ? null : binInfo(path, lb.length));
            return;
        }
        if (md && lr != null) {
            mergeBoth(path, null, re, lb, rb, idx);
        } else {
            // binary file, _vault.nkv, or local mac mismatch (lr == null)
            if (lr == null) {
                status("sync: mac mismatch " + path);
            }
            binaryConflict(path, re, lb, rb, md, idx);
        }
    }

    /**
     * Both-changed markdown merge (baseBytes == null on first contact).
     * Merges PLAINTEXT: base/local/remote are decrypted-if-NKE1 first.
     * scope=all: the merged text is encrypted ONCE and that same
     * ciphertext is pushed, written locally and kept as the base copy.
     * scope=local: plaintext is pushed; the local write and the base copy
     * share one fresh ciphertext. Local/remote mac mismatch falls back to
     * binary-conflict handling (rule 6: never crash the pass); a corrupt
     * base copy degrades to an empty-base merge (keeps both sides).
     */
    private void mergeBoth(String path, byte[] baseBytes, GhEntry re,
            byte[] lb, byte[] rb, int idx) throws IOException {
        byte[] pl = toPlain(lb);
        byte[] pr = toPlain(rb);
        if (pl == null || pr == null) {
            status("sync: mac mismatch " + path);
            binaryConflict(path, re, lb, rb, true, idx);
            return;
        }
        if (bytesEqual(pl, pr)) {
            // same content on both sides (ciphertext bytes may differ):
            // keep the local bytes, adopt the remote sha
            files.write(BASE_DIR + path, lb);
            putState(path, re.sha, null);
            return;
        }
        byte[] pb = toPlain(baseBytes);
        String baseText = pb == null ? "" : utf8s(pb);
        status("sync: merging " + idx + "/" + passTotal);
        MergeResult mr = Merge.merge3(baseText, utf8s(pl), utf8s(pr), strategy());
        byte[] mb = utf8b(mr.text);
        // PUT first: if it fails, local/base/state stay untouched and the
        // retry re-merges the original inputs (idempotent, no nested
        // conflict markers from a half-committed merge).
        String sha;
        byte[] out; // bytes for BOTH the local write and the base copy
        if (bytesEqual(mb, pr)) {
            // remote already holds the merge result: behave like a pull
            sha = re.sha;
            out = toLocal(path, rb);
        } else if (crypto != null && scopeAll) {
            // encrypt merged ONCE; the same ciphertext goes to the PUT,
            // the local write and the base copy
            out = crypto.encrypt(mb, path, System.currentTimeMillis());
            sha = github.putFile(path, out, re.sha, "Noksidian: update " + path);
        } else {
            // plaintext push (scope=local repo, or vault not encrypted)
            sha = github.putFile(path, mb, re.sha, "Noksidian: update " + path);
            out = crypto == null ? mb
                    : crypto.encrypt(mb, path, System.currentTimeMillis());
        }
        if (!localStillIs(path, lb)) {
            // saved by the editor during merge/push; keep the fresh save
            // and reconcile it as a local edit next pass
            status("sync: skip changed " + path);
            return;
        }
        files.write(path, out);
        files.write(BASE_DIR + path, out);
        putState(path, sha, null);
        cMerged++;
        if (mr.conflict) {
            cConflicts++;
        }
    }

    /**
     * Keep local, save the remote bytes as a "<name> (remote).<ext>"
     * sibling (written via the same transform as pulls), push local.
     * Also the fallback for decrypt failures (mac mismatch): a local
     * mac failure pushes the raw local bytes so no data is lost.
     */
    private void binaryConflict(String path, GhEntry re, byte[] lb, byte[] rb,
            boolean md, int idx) throws IOException {
        status("sync: pushing " + idx + "/" + passTotal);
        String cp = remoteCopyPath(path);
        files.write(cp, toLocal(cp, rb));
        byte[] pb = toRemote(path, lb);
        if (pb == null) {
            status("sync: mac mismatch " + path);
            pb = lb;
        }
        String sha = github.putFile(path, pb, re.sha, "Noksidian: update " + path);
        if (md) {
            files.write(BASE_DIR + path, lb);
        }
        putState(path, sha, md ? null : binInfo(path, lb.length));
        cPushed++;
        cConflicts++;
    }

    /** Both sides exist and we have sync state: full reconciliation. */
    private void reconcile(String path, GhEntry re, Hashtable st, int idx)
            throws IOException {
        String stSha = Json.str(st, "sha");
        if (Path.isMarkdown(path)) {
            reconcileMd(path, re, stSha, idx);
        } else {
            reconcileBin(path, re, st, stSha, idx);
        }
    }

    /**
     * Markdown reconciliation. "Did the local side change?" is answered by
     * a byte compare against the base copy, which holds the exact
     * LOCAL-domain bytes as of the last sync. That works verbatim on
     * ciphertext only because sync always writes the identical byte array to
     * the file and to its base copy, so the two can diverge only through a
     * real save - re-encrypting would produce a fresh IV and a spurious
     * mismatch. A missing or unreadable base copy reads as dirty, which
     * costs one redundant push but can never silently drop an edit.
     */
    private void reconcileMd(String path, GhEntry re, String stSha, int idx)
            throws IOException {
        byte[] lb = files.read(path);
        byte[] bb = readBase(path);
        boolean localClean = bb != null && bytesEqual(lb, bb);
        if (re.sha.equals(stSha)) {
            if (localClean) {
                return; // nothing changed anywhere
            }
            status("sync: pushing " + idx + "/" + passTotal);
            byte[] pb = toRemote(path, lb);
            if (pb == null) {
                // local ciphertext fails its mac: keep it, skip the push
                status("sync: mac mismatch " + path);
                return;
            }
            String sha = github.putFile(path, pb, stSha, "Noksidian: update " + path);
            files.write(BASE_DIR + path, lb);
            putState(path, sha, null);
            cPushed++;
            return;
        }
        // remote changed since last sync
        if (localClean) {
            status("sync: pulling " + idx + "/" + passTotal);
            byte[] rb = toLocal(path, github.getBlob(re.sha));
            if (!localStillIs(path, lb)) {
                // saved by the editor during the network fetch; do not
                // clobber it, reconcile it as a local edit next pass
                status("sync: skip changed " + path);
                return;
            }
            files.write(path, rb);
            files.write(BASE_DIR + path, rb);
            putState(path, re.sha, null);
            cPulled++;
            return;
        }
        // both changed -> 3-way merge
        status("sync: merging " + idx + "/" + passTotal);
        byte[] rb = github.getBlob(re.sha);
        if (bytesEqual(lb, rb)) {
            // both made the same change (identical stored bytes)
            files.write(BASE_DIR + path, rb);
            putState(path, re.sha, null);
            return;
        }
        mergeBoth(path, bb, re, lb, rb, idx);
    }

    /**
     * Binary reconciliation. Binaries get no base copy - keeping a second
     * copy of every image would roughly double flash use - so local
     * modification is inferred from size+mtime against the values recorded
     * at the last sync. Bytes can never be merged, so both-sides-changed
     * degrades to the conflict-copy path instead of a 3-way merge.
     */
    private void reconcileBin(String path, GhEntry re, Hashtable st, String stSha, int idx)
            throws IOException {
        Hashtable bin = Json.obj(st.get("bin"));
        // -2 sentinel rather than -1 or 0: Files.size returns -1 and
        // Files.modified returns 0 for a file it cannot open, so a state
        // entry with no "bin" block must not be able to compare EQUAL to an
        // unreadable file and declare it clean.
        long sz = Json.num(bin, "sz", -2L);
        long mt = Json.num(bin, "mt", -2L);
        boolean localClean = files.size(path) == sz && files.modified(path) == mt;
        if (re.sha.equals(stSha)) {
            if (localClean) {
                return;
            }
            status("sync: pushing " + idx + "/" + passTotal);
            byte[] lb = files.read(path);
            byte[] pb = toRemote(path, lb);
            if (pb == null) {
                // local ciphertext fails its mac: keep it, skip the push
                status("sync: mac mismatch " + path);
                return;
            }
            String sha = github.putFile(path, pb, stSha, "Noksidian: update " + path);
            putState(path, sha, binInfo(path, lb.length));
            cPushed++;
            return;
        }
        // remote changed
        if (localClean) {
            status("sync: pulling " + idx + "/" + passTotal);
            byte[] rb = toLocal(path, github.getBlob(re.sha));
            if (files.size(path) != sz || files.modified(path) != mt) {
                // replaced locally during the network fetch; do not
                // clobber it, reconcile it as a local edit next pass
                status("sync: skip changed " + path);
                return;
            }
            files.write(path, rb);
            putState(path, re.sha, binInfo(path, rb.length));
            cPulled++;
            return;
        }
        // both changed: keep local, save remote as conflict copy, push local
        status("sync: pushing " + idx + "/" + passTotal);
        byte[] rb = github.getBlob(re.sha);
        byte[] lb = files.read(path);
        byte[] lr = toRemote(path, lb);
        if (lr != null && bytesEqual(lr, rb)) {
            // same content in the remote domain: adopt the remote sha
            putState(path, re.sha, binInfo(path, lb.length));
            return;
        }
        binaryConflict(path, re, lb, rb, false, idx);
    }

    // ------------------------------------------------------------------
    // state file
    // ------------------------------------------------------------------

    /**
     * Populates stateFiles from state.json, falling back to an empty table.
     * An empty table is always safe, never wrong: every path then looks like
     * first contact and gets re-baselined by content compare, so the worst
     * case is a slow pass and some conflict copies, not lost notes.
     */
    private void loadState() {
        stateFiles = new Hashtable();
        Hashtable f = readStateTable(STATE_PATH);
        if (f == null) {
            // A crash between saveState's delete and rename (or mid-way
            // through the non-atomic fallback write) can leave state.tmp
            // holding the only complete copy: recover from it.
            f = readStateTable(STATE_TMP);
            if (f != null) {
                try {
                    if (files.exists(STATE_PATH)) {
                        files.delete(STATE_PATH);
                    }
                    files.rename(STATE_TMP, "state.json");
                } catch (Throwable t) {
                    // best-effort on-disk repair; state adopted regardless
                }
            }
        }
        if (f != null) {
            stateFiles = f;
        }
    }

    /** Parses {"files":{...}} from rel; null when missing or corrupt. */
    private Hashtable readStateTable(String rel) {
        try {
            if (!files.exists(rel)) {
                return null;
            }
            byte[] b = files.read(rel);
            Hashtable root = Json.obj(Json.parse(utf8s(b)));
            return root == null ? null : Json.obj(root.get("files"));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Persists stateFiles via write-temp-then-rename, so an interruption
     * leaves either the old state or the new one rather than a half-written
     * file (Files.write creates and truncates to zero before it streams a
     * single byte). Note the rename target is the bare name "state.json", not
     * STATE_PATH: JSR-75 FileConnection.rename takes a NAME and always stays
     * in the current directory. If any step of the dance throws it degrades
     * to a direct non-atomic write over STATE_PATH; state.tmp is then still
     * on disk holding a complete copy, which is precisely what loadState
     * falls back to when state.json turns out to be missing or unparsable.
     */
    private void saveState() throws IOException {
        Hashtable root = new Hashtable();
        root.put("files", stateFiles);
        root.put("last", new Long(System.currentTimeMillis()));
        byte[] b = utf8b(Json.write(root));
        boolean done = false;
        try {
            // atomic-ish: temp file then rename over the old state
            files.write(STATE_TMP, b);
            if (files.exists(STATE_PATH)) {
                files.delete(STATE_PATH);
            }
            files.rename(STATE_TMP, "state.json");
            done = true;
        } catch (Throwable t) {
            done = false;
        }
        if (!done) {
            files.write(STATE_PATH, b);
        }
    }

    /**
     * Records the sha of the REMOTE blob for path (never a hash of the local
     * bytes - under scope=local the two differ, since the repo holds
     * plaintext while the phone holds ciphertext). bin is non-null for
     * binaries only; markdown relies on its base copy instead.
     */
    private void putState(String path, String sha, Hashtable bin) throws IOException {
        Hashtable e = new Hashtable();
        e.put("sha", sha);
        if (bin != null) {
            e.put("bin", bin);
        }
        stateFiles.put(path, e);
        mutated();
    }

    /** Persists state every 10 mutations so a crash loses little work. */
    private void mutated() throws IOException {
        mutations++;
        if (mutations % 10 == 0) {
            saveState();
        }
    }

    /**
     * size+mtime snapshot for a binary state entry. Both values are re-stat'ed
     * from flash rather than taken from the in-memory array, so the recorded
     * mtime is the one the filesystem actually assigned and a later stat can
     * compare equal. fallbackLen covers a size() that fails outright (-1);
     * modified() has no fallback and simply records its 0. Either way a wrong
     * value only makes the next pass see the file as dirty and re-push it.
     */
    private Hashtable binInfo(String path, int fallbackLen) {
        Hashtable h = new Hashtable();
        long sz = files.size(path);
        if (sz < 0) {
            sz = fallbackLen;
        }
        h.put("sz", new Long(sz));
        h.put("mt", new Long(files.modified(path)));
        return h;
    }

    // ------------------------------------------------------------------
    // encrypted-vault transforms (CONTRACTS-FEATURES.md section 4)
    // ------------------------------------------------------------------

    /**
     * Transform for bytes arriving FROM the remote before any local
     * write (pulls and conflict copies). scope=all stores bytes as
     * received (the repo already holds ciphertext; plaintext-from-repo
     * files stay plaintext until migration); scope=local encrypts
     * plaintext so every local file is ciphertext. The vault descriptor
     * and bytes already carrying NKE1/NKV1 magic pass through raw.
     */
    private byte[] toLocal(String path, byte[] b) {
        VaultCrypto c = crypto;
        if (c == null || scopeAll || VAULT_DESC.equals(path)) {
            return b;
        }
        if (VaultCrypto.isEncrypted(b) || VaultCrypto.isDescriptor(b)) {
            return b;
        }
        return c.encrypt(b, path, System.currentTimeMillis());
    }

    /**
     * Transform for local bytes headed TO the remote (pushes and
     * remote-domain compares). scope=all pushes bytes as stored;
     * scope=local decrypts NKE1 payloads so plaintext lands in the repo.
     * The vault descriptor always goes raw. Returns null on mac mismatch
     * (caller skips with a log line or takes the conflict-copy path).
     */
    private byte[] toRemote(String path, byte[] b) {
        if (crypto == null || scopeAll || VAULT_DESC.equals(path)) {
            return b;
        }
        return toPlain(b);
    }

    /** Decrypt-if-NKE1; raw pass-through otherwise; null on mac mismatch. */
    private byte[] toPlain(byte[] b) {
        VaultCrypto c = crypto;
        if (b == null || c == null || !VaultCrypto.isEncrypted(b)) {
            return b;
        }
        try {
            return c.decrypt(b);
        } catch (Throwable t) {
            return null; // mac mismatch / bad header: caller decides
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private byte[] readBase(String path) {
        try {
            String bp = BASE_DIR + path;
            // read() -> openExisting throws IOException("not found") on a miss,
            // which the catch converts to null - same result as an exists()
            // pre-check but with one flash open instead of two.
            return files.read(bp);
        } catch (Throwable t) {
            return null;
        }
    }

    private void dropBase(String path) {
        try {
            String bp = BASE_DIR + path;
            if (files.exists(bp)) {
                files.delete(bp);
            }
        } catch (Throwable t) {
            // best effort
        }
    }

    /**
     * Conflict policy handed to merge3, re-read from Config at every merge
     * rather than cached, so changing it in Settings takes effect on the
     * very next merge without restarting the app.
     */
    private int strategy() {
        String s = Config.get("sync.strategy", "both");
        if ("local".equals(s)) {
            return Merge.PREFER_LOCAL;
        }
        if ("remote".equals(s)) {
            return Merge.PREFER_REMOTE;
        }
        return Merge.KEEP_BOTH;
    }

    /** True when the live local file still holds exactly these bytes. */
    private boolean localStillIs(String path, byte[] expected) {
        try {
            // A missing file makes read() throw, which the catch maps to false
            // - same as the old exists() guard, one flash open instead of two.
            return bytesEqual(files.read(path), expected);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Rejects untrusted remote tree paths that could escape the vault
     * (".." segments) or hit Symbian drive syntax (":").
     */
    private static boolean badPath(String p) {
        return p.length() == 0
                || p.equals("..")
                || p.startsWith("../")
                || p.endsWith("/..")
                || p.indexOf("/../") >= 0
                || p.indexOf(':') >= 0;
    }

    /** Remote paths that stay remote-only (never pulled or touched). */
    private static boolean skipRemote(String p) {
        // Both spellings of the app's own bookkeeping dir are listed: current
        // builds write "noksidian/", but a repo previously synced by an older
        // build (or shared with a desktop that has the dotted tree) can still
        // carry ".noksidian/". Pulling either would import stale base copies
        // and state on top of this phone's own, corrupting change detection.
        return p.equals(".git") || p.startsWith(".git/")
                || p.equals(".github") || p.startsWith(".github/")
                || p.equals(".obsidian") || p.startsWith(".obsidian/")
                || p.equals("noksidian") || p.startsWith("noksidian/")
                || p.equals(".noksidian") || p.startsWith(".noksidian/");
    }

    /** Conflict-copy sibling name: "<base> (remote).<ext>". */
    private static String remoteCopyPath(String path) {
        String par = Path.parent(path);
        String bn = Path.baseName(path);
        String ex = Path.ext(path);
        String n = bn + " (remote)";
        if (ex.length() > 0) {
            n = n + "." + ex;
        }
        return Path.join(par, n);
    }

    private static boolean bytesEqual(byte[] a, byte[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    // UTF-8 is the vault's on-disk encoding everywhere, and the only encoding
    // merge results and state.json are ever written in. The catch clauses are
    // belt and braces: MIDP 2.0 requires UTF-8 support, but a stack that
    // throws UnsupportedEncodingException must not take down a whole pass -
    // the default-charset fallback mangles non-ASCII, which still beats
    // aborting the sync.
    private static String utf8s(byte[] b) {
        if (b == null || b.length == 0) {
            return "";
        }
        try {
            return new String(b, 0, b.length, "UTF-8");
        } catch (Exception e) {
            return new String(b, 0, b.length);
        }
    }

    private static byte[] utf8b(String s) {
        if (s == null) {
            return new byte[0];
        }
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    private void status(String msg) {
        try {
            listener.syncStatus(msg);
        } catch (Throwable t) {
            // never let a UI exception abort a pass
        }
    }

    private void done(boolean ok, String summary) {
        try {
            listener.syncDone(ok, summary);
        } catch (Throwable t) {
            // ignore
        }
    }
}
