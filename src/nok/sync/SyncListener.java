package nok.sync;

/**
 * Callback interface for sync progress. Both methods may be invoked
 * from any thread (usually the sync worker thread).
 *
 * This is the only coupling from nok.sync up to the UI: the sync layer
 * imports no lcdui and knows nothing about screens, so everything it has
 * to say about a pass must fit through these two calls. The MIDlet
 * implements them and forwards to the current Library's ticker.
 *
 * "Any thread" is in practice always the Sync worker, never the MIDP
 * event thread, so an implementation may only do work that is safe off
 * that thread - storing a String and asking for a repaint is; rebuilding
 * a List or swapping Displayables is not, and has to be handed back with
 * Display.callSerially.
 *
 * Sync invokes both methods inside catch(Throwable), so a listener that
 * blows up can never abort a sync pass - but the exception is swallowed
 * with no trace, so an implementation must not lean on the caller to
 * surface its own failures. Every attempted pass ends in exactly one
 * syncDone, including the ones that never reach the network ("not
 * configured", "vault locked").
 */
public interface SyncListener {

    /** Short progress line, e.g. "sync: pulling 3/12". Any thread. */
    // Fired per file step of a pass ("pulling i/n", "skip large", "mac
    // mismatch") plus a few non-file lines, and once more after a failed
    // pass to announce the retry delay - so a status line can legitimately
    // arrive AFTER syncDone. Purely a ticker: it carries no pass-lifecycle
    // meaning and may be dropped outright when no screen is up.
    void syncStatus(String msg);

    /** End of a sync pass: ok flag and a human-readable summary. */
    // ok=true: summary is the "N pulled, N pushed, N merged" line (plus
    // ", N conflicts" when any occurred) that Sync.lastSummary() also
    // returns. ok=false: summary is the failure text and the pass is
    // already abandoned; the engine then schedules its own backoff retry
    // (60s -> 2m -> 5m -> 15m) immediately after this call returns, so a
    // listener has no need to answer a failure with requestSync. The two
    // pre-flight refusals - "not configured" and "vault locked" - are the
    // exception: they report ok=false with no retry scheduled at all, and
    // are meant to be re-driven by the setup and unlock flows.
    //
    // Either way the pass has been writing to the vault behind the UI's
    // back - pulls, deletes, trash moves, even a replaced _vault.nkv - so
    // this is the point at which every cached view of the vault (directory
    // listings, note index, encrypted flag) has to be treated as stale.
    void syncDone(boolean ok, String summary);
}
