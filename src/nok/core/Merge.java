package nok.core;

import java.util.Vector;

/**
 * Line-based 3-way merge with LCS (dynamic programming) alignment of
 * base-to-local and base-to-remote, in the style of diff3.
 * Java 1.3 / CLDC 1.1 only; no javax imports.
 *
 * <p>The only caller in the app is nok.sync.Sync.mergeBoth: when a markdown
 * note changed BOTH on the phone and in the GitHub repo since the last
 * successful sync, the stored base copy plus the two new versions go through
 * merge3 and the result is pushed, then written locally. Sync drives that from
 * its own worker thread, so nothing here runs on the LCDUI event thread. This
 * class is pure text in / text out and does no file or network IO of its own;
 * for an encrypted vault Sync decrypts all three inputs before calling and
 * encrypts the result afterwards, so nothing here ever sees ciphertext.
 *
 * <p>Everything is aligned by whole lines rather than characters or words.
 * That is partly diff3 tradition, but mostly the heap: lcsMatch builds a full
 * O(n*m) dynamic-programming table, and on a device with roughly 2MB for the
 * entire app a character-level table over a note would be hopeless. Even per
 * line the table dominates a merge - worst case short[MAX_LINES+1][MAX_LINES+1]
 * once prefix/suffix trimming fails to help - which is what MAX_LINES and
 * MAX_CHARS are defending.
 *
 * <p>Line splitting deliberately keeps the empty piece after a trailing
 * newline (see splitLines), so joining the merged lines with \n reproduces the
 * text exactly and "file ends with a newline" is just another line edit
 * instead of a silent normalization that would dirty every note.
 */
public final class Merge {

    // Conflict strategies. Sync maps the Config key "sync.strategy"
    // (both/local/remote, set in Settings) onto these and re-reads it per
    // merge rather than caching it. PREFER_LOCAL / PREFER_REMOTE keep the
    // note free of markers but silently drop the losing side's text, so a
    // conflicting hunk still reports conflict=true under them - the flag
    // means "a hunk really did collide", not "markers were written".
    public static final int KEEP_BOTH = 0, PREFER_LOCAL = 1, PREFER_REMOTE = 2;

    // Size guard for the DP table. lcsMatch is O(n*m) in both time and
    // memory, and a phone-sized heap cannot absorb an accidental merge of a
    // huge generated file, so anything past these limits skips the diff and
    // takes one side whole. Chars is capped as well as lines because a file
    // can sit under the line cap and still be 150KB of very long lines, where
    // the per-line String.equals calls are what hurt.
    private static final int MAX_LINES = 1500;
    private static final int MAX_CHARS = 150000;

    // Conflict markers. Same shape as git's, but labelled "phone" / "github"
    // instead of branch or commit names: these are read in the note itself on
    // a 320x240 screen, where the only useful question is which half came off
    // the handset.
    private static final String MARK_LOCAL = "<<<<<<< phone";
    private static final String MARK_MID = "=======";
    private static final String MARK_REMOTE = ">>>>>>> github";

    private Merge() {
    }

    /**
     * 3-way merge of base/local/remote. Newlines are normalized to \n first.
     * Trivial cases short-circuit without a diff; oversized inputs
     * (more than 1500 lines or 150000 chars) fall back to picking one side.
     *
     * <p>Nulls are coerced to empty rather than rejected; no current caller
     * passes one, but an empty base is a normal input - Sync uses base="" both
     * for first contact (a file that exists on both sides but has never been
     * synced) and when the stored base copy will not decrypt, and an empty
     * base simply makes every changed hunk conflict rather than throwing on a
     * phone with no console.
     *
     * <p>conflict=true means either walk hit a hunk both sides changed
     * differently, or the size guard fired. fellBack narrows it to the second
     * case: no diff ran and one whole side was discarded. Sync currently reads
     * only conflict, so a fallback shows up as an ordinary conflict count and
     * the dropped text is not recoverable from the merged output.
     */
    public static MergeResult merge3(String base, String local, String remote, int strategy) {
        if (base == null) base = "";
        if (local == null) local = "";
        if (remote == null) remote = "";
        base = normalizeNewlines(base);
        local = normalizeNewlines(local);
        remote = normalizeNewlines(remote);
        // trivial fast paths
        // Order matters only in that these are checked before the size guard:
        // two identical 5000-line files must merge cleanly, not trip the cap
        // and get reported as a conflict. Each case is also exactly the answer
        // diff3 would produce, so short-circuiting changes nothing but cost.
        if (local.equals(remote)) return new MergeResult(local, false, false);
        if (base.equals(local)) return new MergeResult(remote, false, false);
        if (base.equals(remote)) return new MergeResult(local, false, false);
        // size guard: skip the diff entirely
        // Bailing out is a data-loss event (one side's edits vanish), hence
        // conflict=true as well as fellBack=true: the caller must be able to
        // tell the user something happened. Default is to keep the phone's
        // copy, since that is the one the user just typed and cannot get back.
        if (tooBig(base) || tooBig(local) || tooBig(remote)) {
            String pick = (strategy == PREFER_REMOTE) ? remote : local;
            return new MergeResult(pick, true, true);
        }
        String[] b = splitLines(base);
        String[] l = splitLines(local);
        String[] r = splitLines(remote);
        // Two independent alignments against the SAME base, which is what
        // makes this diff3 rather than a pair of two-way diffs: ml[i] / mr[i]
        // say where base line i landed in local / remote, or -1 when the LCS
        // paired it with nothing (changed or deleted on that side). A base
        // line matched in both is a point where the two histories still agree.
        int[] ml = lcsMatch(b, l);
        int[] mr = lcsMatch(b, r);
        Vector out = new Vector();
        boolean conflict = walk(b, l, r, ml, mr, strategy, out);
        // Join with \n BETWEEN lines, never after the last one. splitLines
        // kept the empty piece that follows a trailing newline, so the final
        // element supplies (or withholds) the trailing newline by itself.
        // Appending one here would silently add a byte to every note that did
        // not already end in a newline, i.e. rewrite files the merge did not
        // actually change.
        StringBuffer sb = new StringBuffer();
        for (int k = 0; k < out.size(); k++) {
            if (k > 0) sb.append('\n');
            sb.append((String) out.elementAt(k));
        }
        return new MergeResult(sb.toString(), conflict, false);
    }

    /**
     * diff3-style walk: lines of base matched in BOTH sides at the current
     * cursors are stable and emitted once; everything between two stable
     * points forms a chunk that is resolved per side / per strategy.
     * Returns true if any conflict hunk was seen.
     */
    private static boolean walk(String[] b, String[] l, String[] r,
                                int[] ml, int[] mr, int strategy, Vector out) {
        boolean conflict = false;
        int bn = b.length, ln = l.length, rn = r.length;
        int i = 0, li = 0, ri = 0;
        while (i < bn || li < ln || ri < rn) {
            if (i < bn && ml[i] == li && mr[i] == ri) {
                // stable line: identical in all three at the cursors
                out.addElement(b[i]);
                i++;
                li++;
                ri++;
                continue;
            }
            // find the next base line that is stable in both sides
            int j = i;
            while (j < bn && (ml[j] < 0 || mr[j] < 0)) j++;
            int lo = (j < bn) ? ml[j] : ln;
            int ro = (j < bn) ? mr[j] : rn;
            // chunk: base[i..j) vs local[li..lo) vs remote[ri..ro)
            boolean localSame = rangesEqual(b, i, j, l, li, lo);
            boolean remoteSame = rangesEqual(b, i, j, r, ri, ro);
            if (localSame && remoteSame) {
                appendRange(out, b, i, j); // nothing really changed
            } else if (localSame) {
                appendRange(out, r, ri, ro); // only remote changed
            } else if (remoteSame) {
                appendRange(out, l, li, lo); // only local changed
            } else if (rangesEqual(l, li, lo, r, ri, ro)) {
                appendRange(out, l, li, lo); // identical change on both sides
            } else {
                conflict = true;
                if (strategy == PREFER_LOCAL) {
                    appendRange(out, l, li, lo);
                } else if (strategy == PREFER_REMOTE) {
                    appendRange(out, r, ri, ro);
                } else {
                    out.addElement(MARK_LOCAL);
                    appendRange(out, l, li, lo);
                    out.addElement(MARK_MID);
                    appendRange(out, r, ri, ro);
                    out.addElement(MARK_REMOTE);
                }
            }
            i = j;
            li = lo;
            ri = ro;
        }
        return conflict;
    }

    /**
     * LCS via dynamic programming. Returns match[i] = index in b that a[i]
     * pairs with, or -1. Matches are strictly increasing. Common prefix and
     * suffix are trimmed first to keep the DP table small.
     */
    private static int[] lcsMatch(String[] a, String[] b) {
        int an = a.length, bn = b.length;
        int[] match = new int[an];
        for (int i = 0; i < an; i++) match[i] = -1;
        int pre = 0;
        while (pre < an && pre < bn && a[pre].equals(b[pre])) {
            match[pre] = pre;
            pre++;
        }
        int suf = 0;
        while (suf < an - pre && suf < bn - pre
                && a[an - 1 - suf].equals(b[bn - 1 - suf])) {
            match[an - 1 - suf] = bn - 1 - suf;
            suf++;
        }
        int am = an - pre - suf, bm = bn - pre - suf;
        if (am == 0 || bm == 0) return match;
        // len[i][j] = LCS length of a-middle[i..am) vs b-middle[j..bm)
        short[][] len = new short[am + 1][bm + 1];
        for (int i = am - 1; i >= 0; i--) {
            String ai = a[pre + i];
            short[] row = len[i];
            short[] next = len[i + 1];
            for (int j = bm - 1; j >= 0; j--) {
                if (ai.equals(b[pre + j])) {
                    row[j] = (short) (next[j + 1] + 1);
                } else {
                    row[j] = (next[j] >= row[j + 1]) ? next[j] : row[j + 1];
                }
            }
        }
        int i = 0, j = 0;
        while (i < am && j < bm) {
            if (a[pre + i].equals(b[pre + j])) {
                match[pre + i] = pre + j;
                i++;
                j++;
            } else if (len[i + 1][j] >= len[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return match;
    }

    /** Replaces \r\n and lone \r with \n. */
    private static String normalizeNewlines(String s) {
        if (s.indexOf('\r') < 0) return s;
        StringBuffer sb = new StringBuffer(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                sb.append('\n');
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean tooBig(String s) {
        if (s.length() > MAX_CHARS) return true;
        return countLines(s) > MAX_LINES;
    }

    /** Number of lines; a trailing \n does not start an extra line. */
    private static int countLines(String s) {
        int slen = s.length();
        if (slen == 0) return 0;
        int n = 1;
        for (int i = 0; i < slen; i++) {
            if (s.charAt(i) == '\n') n++;
        }
        if (s.charAt(slen - 1) == '\n') n--;
        return n;
    }

    /**
     * Splits on \n keeping ALL pieces: "a\nb\n" gives ["a","b",""] and
     * "a\nb" gives ["a","b"], so joining with \n restores the text exactly
     * and trailing-newline differences merge like ordinary line edits.
     */
    private static String[] splitLines(String s) {
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        String[] a = new String[count];
        int start = 0, k = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                a[k++] = s.substring(start, i);
                start = i + 1;
            }
        }
        a[k] = s.substring(start);
        return a;
    }

    private static boolean rangesEqual(String[] a, int a0, int a1,
                                       String[] b, int b0, int b1) {
        if (a1 - a0 != b1 - b0) return false;
        for (int k = 0; k < a1 - a0; k++) {
            if (!a[a0 + k].equals(b[b0 + k])) return false;
        }
        return true;
    }

    private static void appendRange(Vector out, String[] a, int from, int to) {
        for (int k = from; k < to; k++) out.addElement(a[k]);
    }
}
