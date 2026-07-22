package nok.core;

/**
 * Result of a 3-way merge (see Merge.merge3).
 * Java 1.3 / CLDC 1.1 only; no javax imports.
 *
 * <p>A plain mutable struct rather than an immutable object with accessors:
 * merge3 has to hand back text plus two facts about how it was produced, and
 * CLDC offers no tuples. Public fields keep it to one class in
 * the JAR with no getter methods - both count against a MIDlet's size and
 * against the interpreter's per-call cost - and the object is short-lived,
 * built by Merge.merge3 and consumed a few lines later by Sync.mergeBoth.
 * Nothing mutates the fields after construction; treat them as final.
 *
 * <p>Invariant: fellBack=true implies conflict=true. The size-guard bailout
 * throws one whole side away, which is a data-loss event, so it is reported as
 * a conflict too - a caller that only checks conflict (Sync does exactly that)
 * must not silently miss it.
 *
 * <p>Created and read on Sync's worker thread only, never on the LCDUI event
 * thread, so no synchronization is needed on the fields.
 */
public final class MergeResult {

    // Never null - merge3 coerces null inputs to "" - but may be "" when both
    // sides emptied the note. CRLF/CR are already collapsed to \n by merge3,
    // so Sync UTF-8 encodes this as-is with no newline pass of its own (it may
    // still encrypt the bytes before the PUT). When fellBack is set this is one
    // whole side verbatim, NOT a merge: the losing side's text is not in here
    // and cannot be recovered from this object.
    /** Merged text (newlines normalized to \n). */
    public String text;

    // Means "a hunk genuinely collided, or the size guard fired" - not
    // "markers were written". Under PREFER_LOCAL / PREFER_REMOTE the text
    // comes back marker-free yet this is still true, because the user did
    // lose one side's edits and Sync needs to say so in its summary count.
    /** True if any conflicting hunk was encountered. */
    public boolean conflict;

    // Narrows conflict to the size-guard case: no diff ran at all (input past
    // Merge's MAX_LINES / MAX_CHARS would have blown the O(n*m) LCS table on a
    // ~2MB heap). No caller reads it yet; it exists so one can distinguish
    // "merged with markers" from "one side dropped whole".
    /** True if input too large for diff and a strategy fallback was applied. */
    public boolean fellBack;

    public MergeResult(String text, boolean conflict, boolean fellBack) {
        this.text = text;
        this.conflict = conflict;
        this.fellBack = fellBack;
    }
}
