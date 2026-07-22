package nok.core;

import java.util.Vector;

/**
 * Pure helpers for flipping through the images of one vault folder
 * (ImageView's LEFT/RIGHT navigation). Operates on plain name Vectors as
 * returned by Files.list so it stays desktop-testable.
 * <p>The split from ImageView exists for testability: CONTRACTS forbids
 * nok.core from touching javax.*, so everything here runs unchanged under
 * test.sh (TestImageNav) on a desktop JVM while ImageView, being a Canvas,
 * cannot. All three methods are pure functions of their arguments - no state,
 * no caching, no I/O. ImageView calls images() once from its constructor
 * (initSibs) and holds the resulting Vector in its sibs field for the life of
 * the screen; the later sibling/indexOf calls all happen on the event thread,
 * so nothing here needs to be thread-safe.
 * <p>Ordering is inherited, not imposed: Files.list already sorts its entries
 * (dirs first, then case-insensitive by name), so preserving listing order
 * here is what makes LEFT/RIGHT walk the photos in the same sequence the
 * Library displays them.
 * Java 1.3 / CLDC 1.1 only; no javax imports.
 */
public final class ImageNav {

    private ImageNav() {
    }

    /**
     * Filters a directory listing down to image file names, keeping listing
     * order. Drops directories (trailing '/'), empty and dot-names, and
     * anything {@link Path#isImage} rejects. Never returns null.
     * <p>Elements are bare file names, not vault-relative paths - ImageView
     * re-joins the chosen one onto Path.parent of the image currently on
     * screen. A null names argument is tolerated (empty result) so callers
     * need no separate guard.
     * <p>Skipping dot-names is not cosmetic tidiness: the real E71's JSR-75
     * refuses to open any path segment starting with '.', so such an entry is a
     * photo that could never be decoded anyway (and would strand the flip).
     */
    public static Vector images(Vector names) {
        Vector out = new Vector();
        if (names == null) {
            return out;
        }
        for (int i = 0; i < names.size(); i++) {
            String n = (String) names.elementAt(i);
            // The trailing '/' is how JSR-75 (and so Files.list) marks a
            // directory entry, and it is the only signal available here: this
            // Vector carries names alone, and nok.core may not touch javax.*
            // to ask a FileConnection.isDirectory().
            if (n == null || n.length() == 0 || n.charAt(0) == '.'
                    || n.endsWith("/") || !Path.isImage(n)) {
                continue;
            }
            out.addElement(n);
        }
        return out;
    }

    /** Position of {@code cur} in {@code imgs}, or -1. */
    public static int indexOf(Vector imgs, String cur) {
        if (imgs == null || cur == null) {
            return -1;
        }
        // Exact, case-sensitive equals is safe because both sides carry the
        // real on-disk spelling: the list comes straight from Files.list, and
        // ImageView's rel is either a listing entry or NoteIndex.resolve's
        // answer, which matches links case-insensitively but returns the
        // indexed path. A miss is therefore a genuine "not in this folder" and
        // only leaves navigation disabled; it can never select a wrong photo.
        for (int i = 0; i < imgs.size(); i++) {
            if (cur.equals(imgs.elementAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Name of the image {@code dir} steps (+1 next / -1 prev) from
     * {@code cur}, wrapping at both ends. Null when {@code cur} is not in the
     * list or has no distinct sibling to move to.
     */
    public static String sibling(Vector imgs, String cur, int dir) {
        int i = indexOf(imgs, cur);
        // A one-image folder answers null instead of naming itself: ImageView
        // releases the current image before loading the target, so a self-flip
        // would blank the screen and re-decode the same file on every arrow
        // press - seconds of nothing on the E71.
        if (i < 0 || imgs.size() < 2) {
            return null;
        }
        int n = imgs.size();
        // Java's % keeps the sign of the dividend, so stepping left off index 0
        // yields -1 rather than n-1; the extra "+ n, % n" folds any negative
        // remainder back into [0, n) and gives the wrap at both ends.
        int j = ((i + dir) % n + n) % n;
        return (String) imgs.elementAt(j);
    }
}
