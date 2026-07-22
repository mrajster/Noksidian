package nok.core;

import java.util.Vector;

/**
 * Index of vault-relative file paths with wikilink resolution.
 *
 * <p>The vault lives on the memory card behind JSR-75, where listing a single
 * directory costs the user a permission prompt on an untrusted MIDlet. A full
 * rescan is therefore not just slow but actively hostile, so this flat
 * in-memory path list is what lets wikilink resolution, search and the
 * "note does not exist, create it?" prompt answer without touching the
 * filesystem at all. NoksidianMIDlet builds it lazily (ensureIndex), persists
 * it to RMS (IndexStore), and then keeps it current from its noteWritten /
 * noteRemoved / noteRenamed hooks via add / removeSubtree / renameSubtree
 * rather than ever rescanning.
 *
 * <p>Storage is a plain Vector of normalized paths, unsorted and duplicate
 * free, and every operation is a linear scan. Real vaults are a few hundred
 * notes, so an O(n) walk over an already-resident Vector is orders of
 * magnitude cheaper than the card I/O it exists to avoid. Paths keep their
 * original case because they have to round-trip back into FileConnection URLs;
 * all comparison is case-insensitive instead.
 *
 * <p>Threading: every public method is synchronized on the instance, but that
 * only makes a single call atomic. A rebuild is clear() followed by hundreds of
 * add() calls, and a reader landing between them would see an empty vault, so
 * callers take this instance's monitor around the whole batch themselves
 * (NoksidianMIDlet.rebuildIndex and loadIndexCache do; resolveTarget and
 * Viewer.resolveImg take the same monitor so a lookup cannot land inside that
 * window). Java monitors are reentrant, so the per-method locks nest inside
 * that harmlessly.
 *
 * Java 1.3 / CLDC 1.1 only; no javax imports.
 */
public final class NoteIndex {

    /**
     * Insertion-ordered, so it mirrors Files.scanAll's directory traversal
     * rather than anything meaningful; all() and markdownFiles() sort on the
     * way out. Order still matters for resolve(), whose basename stage would
     * otherwise pick a different winner after a rescan - hence the explicit
     * tiebreak there.
     */
    private Vector paths; // Vector of String, unsorted, no duplicates

    public NoteIndex() {
        paths = new Vector();
    }

    public synchronized void clear() {
        paths.removeAllElements();
    }

    /**
     * Adds a path, normalizing it first so callers may pass raw scan output or
     * a leading-slash path without producing two entries for one file. Null
     * and empty input is dropped silently rather than thrown on, so a single
     * junk name cannot abort a whole rebuild or cache load.
     *
     * <p>Note the duplicate check is an exact, case-SENSITIVE match on the
     * normalized form, while resolve() matches case-insensitively: a vault
     * holding both "Foo.md" and "foo.md" therefore keeps two entries.
     */
    public synchronized void add(String relPath) {
        if (relPath == null) return;
        String n = Path.normalize(relPath);
        if (n.length() == 0) return;
        // Linear dedupe makes a rebuild or a cache load O(n^2), which is still
        // nothing next to the card I/O either path already paid for.
        for (int i = 0; i < paths.size(); i++) {
            if (n.equals((String) paths.elementAt(i))) return;
        }
        paths.addElement(n);
    }

    /** Removes a path if present (exact, post-normalize match). */
    public synchronized void remove(String relPath) {
        if (relPath == null) return;
        String n = Path.normalize(relPath);
        if (n.length() == 0) return;
        // Returns on the first hit: add() is the normal way in and dedupes, so
        // there is at most one. renameSubtree does not, so a rename onto an
        // existing path can leave a second copy this will not reach.
        for (int i = 0; i < paths.size(); i++) {
            if (n.equals((String) paths.elementAt(i))) {
                paths.removeElementAt(i);
                return;
            }
        }
    }

    /**
     * Removes {@code rel} and everything beneath it, so deleting a file or a
     * whole folder both leave the index consistent.
     *
     * <p>Files.scanAll indexes files only and recurses into directories, so a
     * deleted folder has no entry of its own; the prefix sweep is the only
     * thing that evicts its contents.
     */
    public synchronized void removeSubtree(String rel) {
        if (rel == null) return;
        String o = Path.normalize(rel);
        if (o.length() == 0) return;
        // Match on "dir/" rather than "dir" so deleting "note" cannot take
        // "notes/x.md" with it. The exact-equals arm covers the file case.
        String pfx = o + "/";
        // Backwards: removeElementAt shifts every later element down one, so a
        // forward loop would skip the entry after each removal.
        for (int i = paths.size() - 1; i >= 0; i--) {
            String p = (String) paths.elementAt(i);
            if (p.equals(o) || p.startsWith(pfx)) {
                paths.removeElementAt(i);
            }
        }
    }

    /**
     * Re-prefixes {@code oldRel} and everything beneath it to {@code newRel},
     * so renaming a file or a whole folder both keep the index correct without
     * a rescan.
     *
     * <p>Entries are replaced in place, so the operation is a pure relabel: no
     * element is added or removed and the Vector never shifts. That also means
     * it does not dedupe, so renaming onto a path that is already indexed is
     * the caller's problem.
     */
    public synchronized void renameSubtree(String oldRel, String newRel) {
        if (oldRel == null || newRel == null) return;
        String o = Path.normalize(oldRel);
        String nw = Path.normalize(newRel);
        if (o.length() == 0 || nw.length() == 0) return;
        String pfx = o + "/";
        for (int i = 0; i < paths.size(); i++) {
            String p = (String) paths.elementAt(i);
            if (p.equals(o)) {
                paths.setElementAt(nw, i);
            } else if (p.startsWith(pfx)) {
                // substring from o.length(), not pfx.length(), so the leading
                // '/' survives and the result is "new/child" not "newchild".
                paths.setElementAt(nw + p.substring(o.length()), i);
            }
        }
    }

    /** All files, sorted case-insensitively. */
    // A fresh snapshot, not a view: the two callers (NoksidianMIDlet.doSearch
    // and saveIndexCache) can iterate the result for as long as they like
    // without holding the index lock or risking a concurrent rebuild pulling
    // elements out from under them. doSearch in particular opens files while
    // walking it, which would otherwise pin the lock for many seconds.
    public synchronized Vector all() {
        return sortedCopy(false);
    }

    /** Only markdown files, sorted case-insensitively. */
    // Also a fresh snapshot; see all(). Currently unused - kept as the
    // counterpart of all() for note-only listings.
    public synchronized Vector markdownFiles() {
        return sortedCopy(true);
    }

    /**
     * Resolves a wikilink target to an indexed path, or null.
     * Strips "#heading" and "|alias" first, then tries case-insensitively:
     * exact relpath; relpath + ".md"; basename match ("Foo" matches
     * "dir/Foo.md"); several basename matches return the shortest path.
     *
     * <p>The stage order is what makes a literal path authoritative: an
     * extensionless file actually named "Bar" wins over "Bar.md", and
     * "a/Qux" resolves to "a/Qux.md" rather than being hijacked by a
     * shorter-but-unrelated "Qux.md" elsewhere in the vault.
     */
    public synchronized String resolve(String wikiTarget) {
        if (wikiTarget == null) return null;
        String t = wikiTarget;
        // Md.wikiLink already splits these off, but resolve is also reached
        // from raw markdown link targets and image srcs (Viewer.openLink /
        // Viewer.openLocal / resolveImg), so strip defensively rather than
        // trusting every caller.
        int cut = t.indexOf('#');
        if (cut >= 0) t = t.substring(0, cut);
        cut = t.indexOf('|');
        if (cut >= 0) t = t.substring(0, cut);
        t = Path.normalize(t.trim());
        if (t.length() == 0) return null;
        String tl = t.toLowerCase();
        // 1) exact relpath match
        for (int i = 0; i < paths.size(); i++) {
            String p = (String) paths.elementAt(i);
            if (p.toLowerCase().equals(tl)) return p;
        }
        // 2) relpath + ".md"
        // The usual [[Note]] / [[dir/Note]] form, where the author omits the
        // extension entirely.
        String tlmd = tl + ".md";
        for (int i = 0; i < paths.size(); i++) {
            String p = (String) paths.elementAt(i);
            if (p.toLowerCase().equals(tlmd)) return p;
        }
        // 3) basename match; shortest path wins
        // Obsidian lets [[Note]] refer to a note anywhere in the vault, so the
        // folder part of the link is optional.
        String best = null;
        for (int i = 0; i < paths.size(); i++) {
            String p = (String) paths.elementAt(i);
            // Full file name first ("Image.png" finds "attachments/Image.png").
            boolean hit = Path.name(p).toLowerCase().equals(tl);
            // Extension-stripped matching is restricted to markdown so a bare
            // [[Other]] cannot land on "Other.png" instead of "sub/Other.md".
            if (!hit && Path.isMarkdown(p)) {
                hit = Path.baseName(p).toLowerCase().equals(tl);
            }
            if (hit) {
                // Shortest path wins as the "closest to the vault root" proxy.
                // The cmpCI tiebreak on equal lengths keeps the answer stable:
                // Vector order follows directory traversal, so without it the
                // same link could resolve differently after a rescan.
                if (best == null || p.length() < best.length()
                        || (p.length() == best.length() && cmpCI(p, best) < 0)) {
                    best = p;
                }
            }
        }
        return best;
    }

    /**
     * Builds a sorted copy, optionally markdown-only. CLDC 1.1's java.util
     * ships neither Collections nor Arrays, so the sort is hand-rolled here:
     * insert-into-position, O(n^2) but allocating nothing beyond the result
     * Vector, which suits both the small n and the ~2MB heap. Presizing to
     * paths.size() avoids Vector's grow-and-copy even when nothing is filtered.
     */
    private Vector sortedCopy(boolean mdOnly) {
        Vector v = new Vector(paths.size());
        for (int i = 0; i < paths.size(); i++) {
            String s = (String) paths.elementAt(i);
            if (mdOnly && !Path.isMarkdown(s)) continue;
            // "<= 0" walks past equal keys, so ties keep insertion order and
            // the sort stays stable.
            int j = 0;
            while (j < v.size() && cmpCI((String) v.elementAt(j), s) <= 0) j++;
            v.insertElementAt(s, j);
        }
        return v;
    }

    /**
     * Case-insensitive ordering. CLDC 1.1's String has no
     * compareToIgnoreCase, so both sides are lowercased per comparison. The
     * two temporary Strings that costs are accepted because the only users are
     * sortedCopy (reached from all(), i.e. a search or a cache write) and
     * resolve()'s equal-length tiebreak, none of them per-keystroke paths.
     */
    private static int cmpCI(String a, String b) {
        return a.toLowerCase().compareTo(b.toLowerCase());
    }
}
