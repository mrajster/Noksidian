import java.util.Vector;

import nok.core.ImageNav;

/**
 * Desktop tests for nok.core.ImageNav (folder photo navigation).
 * Java 1.3 syntax, CLDC-safe APIs only (Vector, no ArrayList).
 * <p>ImageNav is the half of ImageView's LEFT/RIGHT flip that CONTRACTS keeps
 * javax-free precisely so it can be exercised here: ImageView itself is a
 * Canvas and cannot run on a desktop JVM, so every rule about which files
 * count as flippable photos and where the arrows land is decided by the three
 * pure functions here and pinned by this suite. Run by test.sh under
 * tools/jdk8.
 * <p>The test vectors are not sampled from a real vault; each entry is written
 * to hit exactly one clause of the ImageView contract ("images only,
 * dot-names/dirs skipped, wraps at the ends", CONTRACTS.md, nok.ui.ImageView)
 * or one Path.ext edge case, so a failure names the broken rule directly.
 * <p>Suites: testImagesFilter (every accept/reject rule of images(), plus
 * listing order); testImagesEmptyAndNull (the never-null guarantee);
 * testSiblingWrap (both wrap directions); testSiblingEdge (the cases that must
 * answer null instead of a photo, plus indexOf for the "n/m" indicator).
 */
public class TestImageNav {

    /** Assertions passed so far; printed as the contract-mandated "ALL PASS <n>" line. */
    static int n = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new RuntimeException("FAIL: " + name);
        n++;
    }

    // Unlike TestMergePath's String checkEq, this one accepts a null "want"
    // and demands got be null too. sibling() answers null as an ordinary
    // result ("no other photo here"), not as a failure, so half the
    // assertions below would be unwritable with a null-hostile comparison.
    static void checkEq(String got, String want, String name) {
        if (want == null ? got != null : (got == null || !got.equals(want))) {
            throw new RuntimeException("FAIL: " + name + " got=<" + got
                    + "> want=<" + want + ">");
        }
        n++;
    }

    public static void main(String[] args) {
        testImagesFilter();
        testImagesEmptyAndNull();
        testSiblingWrap();
        testSiblingEdge();
        System.out.println("ALL PASS " + n);
    }

    /**
     * Wraps a literal array as a Vector, standing in for the java.util.Arrays
     * helpers CLDC does not ship. Vector is also the exact shape Files.list
     * hands ImageNav on the device, so the fixtures differ from production
     * input only in where the names came from.
     */
    static Vector v(String[] a) {
        Vector out = new Vector();
        for (int i = 0; i < a.length; i++) {
            out.addElement(a[i]);
        }
        return out;
    }

    // ------------------------------------------------------------- images()

    /**
     * One entry per rejection rule plus one per accepted extension. "Sub/" is a
     * directory and the trailing '/' is the only marker left once Files.list
     * has closed the FileConnection. ".hidden.png" is the load-bearing case: it
     * IS a png by extension and only the explicit dot-name rule drops it,
     * because the real E71's JSR-75 refuses to open any segment starting with
     * '.' and flipping onto it would strand the viewer. "h.pngx" guards against
     * a startsWith-style extension match, bare "png" against treating a name
     * with no dot as its own extension, "_vault.nkv" is the vault descriptor
     * (never a photo). The five positional checks pin listing order, which is
     * what makes LEFT/RIGHT walk the folder in the same sequence the Library
     * shows - ImageNav imposes no sort of its own, it inherits Files.list's.
     */
    static void testImagesFilter() {
        // dirs (trailing '/'), dot-names, notes and unknown extensions drop;
        // every isImage extension survives in listing order.
        Vector in = v(new String[] {
            "Sub/", ".hidden.png", "a.png", "b.md", "c.JPG", "d.jpeg",
            "e.gif", "f.bmp", "g.txt", "h.pngx", "png", "_vault.nkv"
        });
        Vector out = ImageNav.images(in);
        // c.JPG doubles as the case-insensitivity check: Path.ext lowercases,
        // so uppercase names off a FAT card still count as photos.
        check(out.size() == 5, "filter count");
        checkEq((String) out.elementAt(0), "a.png", "filter keeps a.png");
        checkEq((String) out.elementAt(1), "c.JPG", "filter keeps c.JPG");
        checkEq((String) out.elementAt(2), "d.jpeg", "filter keeps d.jpeg");
        checkEq((String) out.elementAt(3), "e.gif", "filter keeps e.gif");
        checkEq((String) out.elementAt(4), "f.bmp", "filter keeps f.bmp");
    }

    /**
     * images() must answer an empty Vector, never null, for every degenerate
     * input. The null tolerance is the documented caller convenience ("callers
     * need no separate guard") rather than a path ImageView actually takes:
     * Files.list throws on failure, it never hands back null.
     */
    static void testImagesEmptyAndNull() {
        check(ImageNav.images(new Vector()).size() == 0, "filter empty");
        check(ImageNav.images(null).size() == 0, "filter null");
        // "" and ".." exercise the zero-length and dot-name guards, which sit
        // ahead of the isImage test; "notes/" re-checks the directory marker
        // with nothing else in the list, so an all-rejected folder still
        // yields a usable Vector rather than tripping over an empty result.
        Vector weird = v(new String[] { "", "..", "notes/" });
        check(ImageNav.images(weird).size() == 0, "filter no images");
    }

    // ------------------------------------------------------------ sibling()

    /**
     * Three images is the smallest list that has a middle element: the middle
     * proves a plain step, and the two ends prove the arrows roll over instead
     * of dead-ending or clamping. The prev-from-first case is
     * the one that catches Java's sign-preserving % (index -1 rather than n-1).
     */
    static void testSiblingWrap() {
        Vector imgs = v(new String[] { "banner.png", "diagram.png", "logo.png" });
        checkEq(ImageNav.sibling(imgs, "banner.png", 1), "diagram.png",
                "next from first");
        checkEq(ImageNav.sibling(imgs, "diagram.png", 1), "logo.png",
                "next from middle");
        checkEq(ImageNav.sibling(imgs, "logo.png", 1), "banner.png",
                "next wraps to first");
        checkEq(ImageNav.sibling(imgs, "banner.png", -1), "logo.png",
                "prev wraps to last");
        checkEq(ImageNav.sibling(imgs, "logo.png", -1), "diagram.png",
                "prev from last");
    }

    /**
     * Every case where sibling() must answer null rather than name a photo.
     * The single-image folder is the expensive one to get wrong: ImageView
     * frees the current image before loading the target, so a self-flip would
     * blank the screen and re-decode the same file on every arrow press -
     * seconds of black on the E71. The absent-current case is pinned here even
     * though ImageView filters it out earlier (initSibs leaves sibs null
     * unless rel is found in its own listing): the contract is that a miss
     * makes navigation go dead, never that a wrong photo opens.
     */
    static void testSiblingEdge() {
        Vector one = v(new String[] { "only.png" });
        checkEq(ImageNav.sibling(one, "only.png", 1), null,
                "single image has no sibling");
        checkEq(ImageNav.sibling(one, "only.png", -1), null,
                "single image has no prev sibling");
        Vector imgs = v(new String[] { "a.png", "b.png" });
        checkEq(ImageNav.sibling(imgs, "missing.png", 1), null,
                "current absent -> null");
        // The null list below IS reachable: initSibs leaves sibs null when the
        // listing throws, and nav() then calls sibling(sibs, ...) unguarded.
        checkEq(ImageNav.sibling(imgs, null, 1), null, "null current");
        checkEq(ImageNav.sibling(null, "a.png", 1), null, "null list");
        checkEq(ImageNav.sibling(new Vector(), "a.png", 1), null,
                "empty list");
        // index position, for the n/m indicator
        check(ImageNav.indexOf(imgs, "b.png") == 1, "indexOf found");
        check(ImageNav.indexOf(imgs, "zz.png") == -1, "indexOf missing");
        check(ImageNav.indexOf(null, "a.png") == -1, "indexOf null list");
    }
}
