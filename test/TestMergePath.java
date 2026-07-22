import java.util.Vector;

import nok.core.Merge;
import nok.core.MergeResult;
import nok.core.NoteIndex;
import nok.core.Path;

/**
 * Desktop tests for nok.core.Merge / MergeResult / Path / NoteIndex.
 * Java 1.3 syntax, CLDC-safe APIs only (Vector, no ArrayList).
 *
 * <p>These three classes are the pure-text core of the app: no MIDP, no javax,
 * no filesystem, so they can be exercised on a desktop JDK instead of in the
 * emulator. test.sh compiles src/nok/core plus test/ with -source 1.3
 * -target 1.3 and runs the suites it lists as plain mains, which is why there
 * is no JUnit here and no package declaration. Keeping the test dialect
 * identical to the CLDC dialect of the code under test means a language
 * construct that compiles here cannot fail to compile for the phone.
 *
 * <p>Expected values come from CONTRACTS.md, sections "nok.core.Merge",
 * "nok.core.Path" and "nok.core.NoteIndex". Where the contract only describes
 * behaviour in prose (diff3 chunk resolution, resolve() stage order) the
 * vectors below pin the exact character-for-character output, because merge
 * output is written straight into the user's note and the discarded side is
 * not recoverable if it drifts.
 *
 * <p>Failure is a thrown RuntimeException, so the first bad assertion aborts
 * the run and test.sh (set -e) stops there. Non-ASCII test data is written as
 * unicode escapes so the file compiles under any platform default encoding.
 */
public class TestMergePath {

    // Assertions actually executed. main prints it, so a suite that silently
    // returns early shows up as a smaller count instead of a clean pass.
    static int n = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new RuntimeException("FAIL: " + name);
        n++;
    }

    /**
     * String equality assertion. A null result always fails, even against a
     * null expectation, so the "no match" cases below assert with
     * check(x == null, ...) rather than checkEq.
     */
    static void checkEq(String got, String want, String name) {
        if (got == null || !got.equals(want)) {
            throw new RuntimeException("FAIL: " + name + " got=<" + got + "> want=<" + want + ">");
        }
        n++;
    }

    public static void main(String[] args) {
        testMergeTrivial();
        testMergeOneSided();
        testMergeCleanBoth();
        testMergeConflicts();
        testMergeInsertDelete();
        testMergeEmptyAndFirstContact();
        testMergeNewlines();
        testMergeFallback();
        testPathBasics();
        testPathNormalize();
        testPathUrlEncode();
        testNoteIndex();
        System.out.println("ALL PASS " + n);
    }

    // ---------------------------------------------------------------- Merge

    /**
     * The three short-circuits merge3 takes before any diff runs, plus the
     * null coercion. local==remote must win even when both moved away from
     * base: that is the "the other side already applied my change" case Sync
     * hits constantly, and treating it as a collision would flood the vault
     * with conflict markers.
     */
    static void testMergeTrivial() {
        MergeResult m;
        m = Merge.merge3(null, null, null, Merge.KEEP_BOTH);
        checkEq(m.text, "", "trivial null inputs");
        check(!m.conflict && !m.fellBack, "trivial null flags");

        m = Merge.merge3("a\n", "a\n", "a\n", Merge.KEEP_BOTH);
        checkEq(m.text, "a\n", "trivial all equal");
        check(!m.conflict && !m.fellBack, "trivial all equal flags");

        // local == remote but both differ from base: no conflict
        m = Merge.merge3("a\nb\n", "x\ny\n", "x\ny\n", Merge.KEEP_BOTH);
        checkEq(m.text, "x\ny\n", "trivial local==remote");
        check(!m.conflict, "trivial local==remote flag");

        // base == local: take remote
        m = Merge.merge3("a\n", "a\n", "new\n", Merge.KEEP_BOTH);
        checkEq(m.text, "new\n", "trivial base==local -> remote");
        check(!m.conflict, "trivial base==local flag");

        // base == remote: take local
        m = Merge.merge3("a\n", "changed\n", "a\n", Merge.KEEP_BOTH);
        checkEq(m.text, "changed\n", "trivial base==remote -> local");
        check(!m.conflict, "trivial base==remote flag");
    }

    /**
     * Chunks changed on exactly one side: the walk takes that side whole. The
     * last vector covers both sides making the SAME deletion, which must be
     * taken once rather than applied twice.
     */
    static void testMergeOneSided() {
        // local edits a middle line, remote appends a line: both applied
        MergeResult m = Merge.merge3("1\n2\n3\n", "1\ntwo\n3\n", "1\n2\n3\n4\n",
                Merge.KEEP_BOTH);
        checkEq(m.text, "1\ntwo\n3\n4\n", "one-sided edit + append");
        check(!m.conflict && !m.fellBack, "one-sided flags");

        // only local deleted a line, remote edited another: both applied
        m = Merge.merge3("a\nb\nc\nd\n", "a\nc\nd\n", "a\nb\nc\nDD\n", Merge.KEEP_BOTH);
        checkEq(m.text, "a\nc\nDD\n", "delete one side, edit other region");
        check(!m.conflict, "delete/edit disjoint flag");

        // both deleted the same line, remote also appended
        m = Merge.merge3("a\nb\nc\n", "a\nc\n", "a\nc\nd\n", Merge.KEEP_BOTH);
        checkEq(m.text, "a\nc\nd\n", "same deletion taken once plus append");
        check(!m.conflict, "same deletion flag");
    }

    /**
     * Both sides changed, but in chunks separated by stable lines, so nothing
     * collides. The last two vectors exercise the identical-change arm of the
     * walk (same insertion, then the same whole-chunk rewrite on both sides)
     * combined with a one-sided change elsewhere - a remote edit in the first,
     * a local append in the second - proving a chunk resolved one way does not
     * poison its neighbour.
     */
    static void testMergeCleanBoth() {
        // both changed DIFFERENT regions separated by stable lines: clean merge
        MergeResult m = Merge.merge3("1\n2\n3\n4\n5\n", "ONE\n2\n3\n4\n5\n",
                "1\n2\n3\n4\nFIVE\n", Merge.KEEP_BOTH);
        checkEq(m.text, "ONE\n2\n3\n4\nFIVE\n", "clean both-sided merge");
        check(!m.conflict && !m.fellBack, "clean both-sided flags");

        // identical insertion at the same spot, plus a one-sided edit elsewhere
        m = Merge.merge3("a\nb\nc\n", "a\nX\nb\nc\n", "a\nX\nb\nCC\n", Merge.KEEP_BOTH);
        checkEq(m.text, "a\nX\nb\nCC\n", "identical insertion taken once");
        check(!m.conflict, "identical insertion flag");

        // identical whole-chunk rewrite on both sides plus local-only append
        m = Merge.merge3("k\nold\nz\n", "k\nnew\nz\ntail\n", "k\nnew\nz\n",
                Merge.KEEP_BOTH);
        checkEq(m.text, "k\nnew\nz\ntail\n", "identical rewrite + local append");
        check(!m.conflict, "identical rewrite flag");
    }

    /**
     * Real collisions. The marker text is asserted literally because it is not
     * cosmetic: the "phone" / "github" labels are what the user reads inside
     * the note on a 320x240 screen, and they are the only clue about which
     * half came off the handset.
     *
     * <p>PREFER_LOCAL / PREFER_REMOTE still report conflict=true - the flag
     * means a hunk really collided, not that markers were written - and the
     * mixed vector shows the strategy applies per chunk, so picking a side
     * discards only the colliding hunk and keeps the other side's clean edits.
     *
     * <p>The final vector has no trailing newline in any of the three inputs,
     * pinning that merge3 joins lines with a newline BETWEEN them and never
     * appends one; otherwise every such note would come back one byte longer
     * and be pushed as changed.
     */
    static void testMergeConflicts() {
        String base = "l1\nl2\nl3\n";
        String local = "l1\nLOCAL\nl3\n";
        String remote = "l1\nREMOTE\nl3\n";

        MergeResult m = Merge.merge3(base, local, remote, Merge.KEEP_BOTH);
        checkEq(m.text,
                "l1\n<<<<<<< phone\nLOCAL\n=======\nREMOTE\n>>>>>>> github\nl3\n",
                "conflict KEEP_BOTH markers");
        check(m.conflict, "conflict KEEP_BOTH flag");
        check(!m.fellBack, "conflict KEEP_BOTH no fallback");

        m = Merge.merge3(base, local, remote, Merge.PREFER_LOCAL);
        checkEq(m.text, "l1\nLOCAL\nl3\n", "conflict PREFER_LOCAL text");
        check(m.conflict, "conflict PREFER_LOCAL flag still set");

        m = Merge.merge3(base, local, remote, Merge.PREFER_REMOTE);
        checkEq(m.text, "l1\nREMOTE\nl3\n", "conflict PREFER_REMOTE text");
        check(m.conflict, "conflict PREFER_REMOTE flag still set");

        // different insertions at the same spot: conflict
        m = Merge.merge3("a\nb\n", "a\nX\nb\n", "a\nY\nb\n", Merge.KEEP_BOTH);
        checkEq(m.text,
                "a\n<<<<<<< phone\nX\n=======\nY\n>>>>>>> github\nb\n",
                "insert same spot conflict");
        check(m.conflict, "insert same spot flag");

        // different appends at the end: conflict at tail
        m = Merge.merge3("a\n", "a\nx\n", "a\ny\n", Merge.KEEP_BOTH);
        checkEq(m.text,
                "a\n<<<<<<< phone\nx\n=======\ny\n>>>>>>> github\n",
                "tail append conflict");
        check(m.conflict, "tail append flag");

        // mixed: one conflicting region and one clean remote change
        m = Merge.merge3("s\na\nm\nb\ne\n", "s\nA1\nm\nb\ne\n",
                "s\nA2\nm\nB2\ne\n", Merge.KEEP_BOTH);
        checkEq(m.text,
                "s\n<<<<<<< phone\nA1\n=======\nA2\n>>>>>>> github\nm\nB2\ne\n",
                "mixed conflict + clean change");
        check(m.conflict, "mixed flag");

        m = Merge.merge3("s\na\nm\nb\ne\n", "s\nA1\nm\nb\ne\n",
                "s\nA2\nm\nB2\ne\n", Merge.PREFER_LOCAL);
        checkEq(m.text, "s\nA1\nm\nB2\ne\n", "mixed PREFER_LOCAL keeps clean remote part");
        check(m.conflict, "mixed PREFER_LOCAL flag");

        // no trailing newline anywhere
        m = Merge.merge3("a", "b", "c", Merge.KEEP_BOTH);
        checkEq(m.text, "<<<<<<< phone\nb\n=======\nc\n>>>>>>> github",
                "conflict without trailing newline");
        check(m.conflict, "no trailing newline flag");
    }

    /**
     * Deletion against edit, and edits with no stable line between them.
     *
     * <p>A line deleted locally and edited remotely conflicts with an EMPTY
     * local half inside the markers. That empty half is deliberate output, not
     * a mangled hunk: it is what tells the user their deletion lost to someone
     * else's edit.
     *
     * <p>The adjacent-edit vector is the diff3 behaviour people find
     * surprising. Base is "1","2"; local rewrites the first line, remote the
     * second, and since neither of those two base lines is matched in BOTH
     * alignments the two independent edits fall into one chunk and conflict.
     * The last two vectors are the same effect at file scale: a one-sided
     * deletion whose chunk stretches far enough to swallow the region the
     * other side appended to or edited.
     */
    static void testMergeInsertDelete() {
        // deletion vs edit of the SAME line: conflict with empty local side
        MergeResult m = Merge.merge3("a\nb\nc\n", "a\nc\n", "a\nB\nc\n", Merge.KEEP_BOTH);
        checkEq(m.text,
                "a\n<<<<<<< phone\n=======\nB\n>>>>>>> github\nc\n",
                "delete vs edit KEEP_BOTH");
        check(m.conflict, "delete vs edit flag");

        m = Merge.merge3("a\nb\nc\n", "a\nc\n", "a\nB\nc\n", Merge.PREFER_LOCAL);
        checkEq(m.text, "a\nc\n", "delete vs edit PREFER_LOCAL");
        check(m.conflict, "delete vs edit PREFER_LOCAL flag");

        m = Merge.merge3("a\nb\nc\n", "a\nc\n", "a\nB\nc\n", Merge.PREFER_REMOTE);
        checkEq(m.text, "a\nB\nc\n", "delete vs edit PREFER_REMOTE");

        // adjacent-line edits (no stable line between): conflict like diff3
        m = Merge.merge3("1\n2\n", "X\n2\n", "1\nY\n", Merge.KEEP_BOTH);
        check(m.conflict, "adjacent edits conflict");
        checkEq(m.text,
                "<<<<<<< phone\nX\n2\n=======\n1\nY\n>>>>>>> github\n",
                "adjacent edits chunk content");

        // pure one-sided deletion of whole tail
        m = Merge.merge3("a\nb\nc\n", "a\n", "a\nb\nc\nd\n", Merge.KEEP_BOTH);
        check(m.conflict, "tail delete vs tail append conflict");

        // one-sided deletion only (remote untouched apart from other region)
        m = Merge.merge3("h\nx\ny\nt\n", "h\nt\n", "h\nx\ny\nT2\n", Merge.KEEP_BOTH);
        check(m.conflict, "delete overlapping edited region conflicts");
    }

    /**
     * base="" - the input Sync passes on first contact (a note that exists on
     * both sides but has never been synced) and when the stored base copy will
     * not decrypt. With no base every line of both sides counts as added, so
     * two non-identical files collide as one whole-file conflict; the
     * shared-prefix vector shows that even a common first line does not
     * rescue it, because a base with no lines has nothing to be stable
     * against. The one-sided cases still resolve cleanly, which is what keeps
     * first contact painless whenever only one side actually has content.
     */
    static void testMergeEmptyAndFirstContact() {
        // everything empty
        MergeResult m = Merge.merge3("", "", "", Merge.KEEP_BOTH);
        checkEq(m.text, "", "all empty");
        check(!m.conflict && !m.fellBack, "all empty flags");

        // empty base, only remote has content (first contact, local empty)
        m = Merge.merge3("", "", "hello\n", Merge.KEEP_BOTH);
        checkEq(m.text, "hello\n", "empty base local empty -> remote");
        check(!m.conflict, "empty base local empty flag");

        // empty base, only local has content
        m = Merge.merge3("", "mine\n", "", Merge.KEEP_BOTH);
        checkEq(m.text, "mine\n", "empty base remote empty -> local");
        check(!m.conflict, "empty base remote empty flag");

        // empty base, both added different content: conflict
        m = Merge.merge3("", "hello\n", "world\n", Merge.KEEP_BOTH);
        checkEq(m.text,
                "<<<<<<< phone\nhello\n=======\nworld\n>>>>>>> github\n",
                "first contact conflict markers");
        check(m.conflict && !m.fellBack, "first contact flags");

        m = Merge.merge3("", "hello\n", "world\n", Merge.PREFER_REMOTE);
        checkEq(m.text, "world\n", "first contact PREFER_REMOTE");
        check(m.conflict, "first contact PREFER_REMOTE flag");

        // empty base, both share a stable-looking first line but base has none:
        // whole thing is one chunk -> conflict containing both versions
        m = Merge.merge3("", "# T\nphone\n", "# T\ngithub\n", Merge.KEEP_BOTH);
        check(m.conflict, "first contact shared prefix still conflicts");
        check(m.text.indexOf("phone") >= 0 && m.text.indexOf("github") >= 0,
                "first contact both sides present");

        // local emptied the file while remote edited: conflict
        m = Merge.merge3("data\n", "", "data2\n", Merge.KEEP_BOTH);
        check(m.conflict, "emptied vs edited conflict");
    }

    /**
     * Line-ending handling. CRLF and lone CR are folded to LF before the diff,
     * so a note authored on a desktop and one saved on the phone align instead
     * of every single line reading as changed. The merged output is always LF,
     * never the input's original endings.
     *
     * <p>The last two vectors cover splitLines keeping the empty piece that
     * follows a trailing newline: adding or removing the final newline is then
     * an ordinary one-sided line edit that merges cleanly against an unrelated
     * edit, instead of a silent normalization that would dirty and re-push
     * every note in the vault.
     */
    static void testMergeNewlines() {
        // \r\n inputs are normalized to \n
        MergeResult m = Merge.merge3("a\r\nb\r\n", "a\r\nb\r\n", "a\r\nc\r\n",
                Merge.KEEP_BOTH);
        checkEq(m.text, "a\nc\n", "crlf normalized, remote taken");
        check(!m.conflict, "crlf flag");

        // mixed line endings still align: local edits with \n, base/remote \r\n
        m = Merge.merge3("a\r\nb\r\nc\r\n", "a\nB\nc\n", "a\r\nb\r\nc\r\n",
                Merge.KEEP_BOTH);
        checkEq(m.text, "a\nB\nc\n", "mixed crlf/lf alignment");
        check(!m.conflict, "mixed crlf/lf flag");

        // lone \r treated as newline
        m = Merge.merge3("a\rb", "a\rb", "a\rB", Merge.KEEP_BOTH);
        checkEq(m.text, "a\nB", "lone cr normalized");

        // trailing newline added by local only: kept
        m = Merge.merge3("a\nb", "a\nb\n", "a\nb", Merge.KEEP_BOTH);
        checkEq(m.text, "a\nb\n", "local adds trailing newline");
        check(!m.conflict, "trailing newline add flag");

        // trailing newline removed by remote while local edits first line: both apply
        m = Merge.merge3("a\nb\n", "A\nb\n", "a\nb", Merge.KEEP_BOTH);
        checkEq(m.text, "A\nb", "remote strips trailing newline, local edit kept");
        check(!m.conflict, "trailing newline strip flag");
    }

    /**
     * The size guard protecting the LCS table. lcsMatch allocates an O(n*m)
     * short[][], so on a ~2MB heap an oversized input has to skip the diff
     * entirely and take one side whole.
     *
     * <p>Both boundaries are pinned on purpose: 1600 lines falls back, exactly
     * 1500 does NOT (countLines does not count a trailing newline as starting
     * an extra line), and 150001 chars falls back even though it is a single
     * line, because a note can sit far under the line cap and still be 150KB
     * of very long lines.
     *
     * <p>Discarding a whole side is a data-loss event, hence conflict=true as
     * well as fellBack=true; Sync reads only conflict, so a fallback surfaces
     * to the user as an ordinary conflict count. The huge local==remote vector
     * proves the trivial fast paths are checked BEFORE the guard, so two
     * identical large files still merge cleanly instead of being reported as a
     * conflict and having one side thrown away.
     */
    static void testMergeFallback() {
        // more than 1500 lines: strategy fallback, no diff
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < 1600; i++) sb.append("line").append(i).append('\n');
        String bigBase = sb.toString();
        String bigLocal = "L\n" + bigBase;
        String bigRemote = "R\n" + bigBase;

        MergeResult m = Merge.merge3(bigBase, bigLocal, bigRemote, Merge.KEEP_BOTH);
        check(m.fellBack, "line fallback fellBack");
        check(m.conflict, "line fallback conflict");
        checkEq(m.text, bigLocal, "line fallback KEEP_BOTH takes local");

        m = Merge.merge3(bigBase, bigLocal, bigRemote, Merge.PREFER_LOCAL);
        checkEq(m.text, bigLocal, "line fallback PREFER_LOCAL takes local");
        check(m.fellBack && m.conflict, "line fallback PREFER_LOCAL flags");

        m = Merge.merge3(bigBase, bigLocal, bigRemote, Merge.PREFER_REMOTE);
        checkEq(m.text, bigRemote, "line fallback PREFER_REMOTE takes remote");
        check(m.fellBack && m.conflict, "line fallback PREFER_REMOTE flags");

        // trivial fast path wins even over huge inputs
        m = Merge.merge3(bigBase, bigLocal, bigLocal, Merge.KEEP_BOTH);
        check(!m.fellBack && !m.conflict, "huge but local==remote no fallback");
        checkEq(m.text, bigLocal, "huge but local==remote text");

        // more than 150000 chars on a single line: fallback too
        StringBuffer big = new StringBuffer();
        for (int i = 0; i < 150001; i++) big.append('a');
        String huge = big.toString();
        m = Merge.merge3(huge, "x", "y", Merge.KEEP_BOTH);
        check(m.fellBack && m.conflict, "char fallback flags");
        checkEq(m.text, "x", "char fallback keeps local");
        m = Merge.merge3(huge, "x", "y", Merge.PREFER_REMOTE);
        checkEq(m.text, "y", "char fallback PREFER_REMOTE");

        // exactly 1500 lines: NO fallback, real merge
        sb = new StringBuffer();
        for (int i = 0; i < 1500; i++) sb.append("n").append(i).append('\n');
        String base1500 = sb.toString();
        // Replace the first line locally and the last line remotely, keeping
        // the line COUNT at exactly 1500 on all three sides: appending instead
        // would push local/remote to 1501 and trip the guard, which is the
        // very thing this vector is trying to prove does not happen.
        String local1500 = "TOP\n" + base1500.substring(base1500.indexOf('\n') + 1);
        // 6 == length of the last line "n1499\n"; the loop above stops at 1499.
        String remote1500 = base1500.substring(0, base1500.length() - 6) + "END\n";
        m = Merge.merge3(base1500, local1500, remote1500, Merge.KEEP_BOTH);
        check(!m.fellBack, "1500 lines no fallback");
        check(!m.conflict, "1500 lines clean merge");
        check(m.text.startsWith("TOP\n") && m.text.endsWith("END\n"),
                "1500 lines both edits applied");
    }

    // ----------------------------------------------------------------- Path

    /**
     * The path accessors, on the shapes a real vault produces. The rules worth
     * pinning are Path's two "dot at index 0" tests: a leading dot marks a
     * hidden file, so ".gitignore" keeps its whole name and reports no
     * extension, and only the LAST dot splits, so "a.tar.gz" is baseName
     * "a.tar" plus ext "gz". ext() lowercases, which is what lets isMarkdown
     * and isImage compare against bare lowercase literals instead of repeating
     * the case rule at every call site. "dir.d/file" checks the dot search is
     * confined to the last segment, so a dotted folder name cannot invent an
     * extension for an extensionless file.
     */
    static void testPathBasics() {
        checkEq(Path.join("a", "b"), "a/b", "join a b");
        checkEq(Path.join("", "b"), "b", "join empty a");
        checkEq(Path.join("a", ""), "a", "join empty b");
        checkEq(Path.join("a/", "b"), "a/b", "join trailing slash a");
        checkEq(Path.join("a/b", "c/d"), "a/b/c/d", "join nested");
        checkEq(Path.join(null, "b"), "b", "join null a");

        checkEq(Path.parent("a/b/c"), "a/b", "parent nested");
        checkEq(Path.parent("a"), "", "parent top-level");
        checkEq(Path.parent(""), "", "parent empty");

        checkEq(Path.name("a/b/c.md"), "c.md", "name nested");
        checkEq(Path.name("c.md"), "c.md", "name flat");
        checkEq(Path.name("a/b/"), "", "name trailing slash");

        checkEq(Path.baseName("a/b/c.md"), "c", "baseName nested");
        checkEq(Path.baseName("noext"), "noext", "baseName no ext");
        checkEq(Path.baseName("a/.gitignore"), ".gitignore", "baseName dotfile");
        checkEq(Path.baseName("a.b.c"), "a.b", "baseName multi dot");

        checkEq(Path.ext("a/B.MD"), "md", "ext lowercased");
        checkEq(Path.ext("noext"), "", "ext none");
        checkEq(Path.ext(".gitignore"), "", "ext dotfile");
        checkEq(Path.ext("a.tar.gz"), "gz", "ext last only");
        checkEq(Path.ext("dir.d/file"), "", "ext dot in dir only");

        check(Path.isMarkdown("x.md"), "isMarkdown md");
        check(Path.isMarkdown("x.markdown"), "isMarkdown markdown");
        check(Path.isMarkdown("X.MD"), "isMarkdown uppercase");
        check(!Path.isMarkdown("x.txt"), "isMarkdown txt no");
        check(!Path.isMarkdown("md"), "isMarkdown bare no");

        check(Path.isImage("x.png"), "isImage png");
        check(Path.isImage("x.jpg"), "isImage jpg");
        check(Path.isImage("x.JPEG"), "isImage jpeg upper");
        check(Path.isImage("x.gif"), "isImage gif");
        check(Path.isImage("x.bmp"), "isImage bmp");
        check(!Path.isImage("x.svg"), "isImage svg no");
        check(!Path.isImage("x.md"), "isImage md no");
    }

    /**
     * The canonical form every stored path key is held in - NoteIndex entries,
     * Sync's remote-vs-local comparison, the GitHub contents URL. Backslashes
     * are in scope because users author on a desktop and the E71's own file
     * browser shows Windows-style paths, so the same file referenced either
     * way has to compare equal. "///" collapsing to "" is the degenerate case
     * NoteIndex.add leans on: it tests only the normalized length, so any
     * all-separator junk name is dropped by that one guard.
     */
    static void testPathNormalize() {
        checkEq(Path.normalize("/a/b/"), "a/b", "normalize lead trail");
        checkEq(Path.normalize("a//b"), "a/b", "normalize double slash");
        checkEq(Path.normalize("a\\b\\c"), "a/b/c", "normalize backslashes");
        checkEq(Path.normalize("///"), "", "normalize only slashes");
        checkEq(Path.normalize(""), "", "normalize empty");
        checkEq(Path.normalize("a"), "a", "normalize plain");
        checkEq(Path.normalize("\\a\\\\b/"), "a/b", "normalize mixed separators");
    }

    /**
     * Percent-encoding for JSR-75 file URLs and GitHub REST URLs, which
     * deliberately share one encoder so a vault whose names survive on the
     * memory card also round-trips through the API. The vectors cover one
     * character per UTF-8 length class: ASCII, e-acute / a-umlaut / c-caron
     * for the 2-byte form, and CJK for the 3-byte form.
     *
     * <p>'/' stays literal while a '%' or a space inside a segment must be
     * escaped, which is the entire reason encoding is done per segment rather
     * than over the whole string; the RFC 3986 unreserved set (A-Za-z0-9 plus
     * - _ . ~) still passes through untouched, as the "a-b_c.d~e" vector pins.
     * '+', '&amp;' and the brackets are escaped even though
     * they are legal in a path: JSR-75 stacks and the GitHub API disagree
     * about which reserved characters are safe unescaped, and escaping them is
     * accepted by both.
     *
     * <p>Gap worth knowing: there is no 4-byte (astral / emoji) vector here,
     * so encodeSegment's surrogate-pair path - the one that exists because
     * some Symbian CLDC VMs emit CESU-8 - is not exercised by this suite.
     */
    static void testPathUrlEncode() {
        checkEq(Path.urlEncodePath("dir with space/file.md"),
                "dir%20with%20space/file.md", "urlenc spaces");
        checkEq(Path.urlEncodePath("a-b_c.d~e"), "a-b_c.d~e", "urlenc unreserved kept");
        checkEq(Path.urlEncodePath("a/b/c"), "a/b/c", "urlenc slashes kept");
        checkEq(Path.urlEncodePath("caf\u00E9.md"), "caf%C3%A9.md", "urlenc 2-byte utf8");
        checkEq(Path.urlEncodePath("\u010D"), "%C4%8D", "urlenc c-caron");
        checkEq(Path.urlEncodePath("\u65E5\u672C"), "%E6%97%A5%E6%9C%AC",
                "urlenc 3-byte utf8");
        checkEq(Path.urlEncodePath("100%.md"), "100%25.md", "urlenc percent");
        checkEq(Path.urlEncodePath("a+b"), "a%2Bb", "urlenc plus");
        checkEq(Path.urlEncodePath("(x) & [y]"), "%28x%29%20%26%20%5By%5D",
                "urlenc punctuation");
        checkEq(Path.urlEncodePath(""), "", "urlenc empty");
        checkEq(Path.urlEncodePath("notes/my note \u00E4.md"),
                "notes/my%20note%20%C3%A4.md", "urlenc segment mix");
    }

    // ------------------------------------------------------------ NoteIndex

    /**
     * NoteIndex end to end: add with dedupe and normalization, the two sorted
     * snapshots, and every stage of wikilink resolution.
     *
     * <p>The fixture is built so the resolve stages can be told apart. "Bar"
     * and "Bar.md" both exist, so an exact extensionless hit must beat the
     * ".md" completion; "a/Qux.md" and "Qux.md" both exist, so "a/Qux" must
     * complete to the path the author actually wrote instead of being hijacked
     * by the shorter basename match. Note the gap: "Note.md" exists at three
     * depths but resolve("Note") never reaches the shortest-path tiebreak,
     * because stage 2 completes it to the root "Note.md" outright - nothing in
     * this suite has two candidates arriving at stage 3 together.
     *
     * <p>"A.md" sorting before "a/Qux.md" is not arbitrary: cmpCI lowercases
     * both sides and compares, and '.' (0x2E) precedes '/' (0x2F) in ASCII.
     * The Image &lt; lead &lt; Note ordering check would fail under a plain
     * compareTo, which sorts every uppercase name ahead of every lowercase one
     * and would scatter a mixed-case vault in the Library list.
     *
     * <p>resolve("image") must MISS: extension-stripped matching is restricted
     * to markdown, so a bare wikilink can never silently land on an image.
     */
    static void testNoteIndex() {
        NoteIndex idx = new NoteIndex();
        idx.add("Note.md");
        idx.add("dir/Note.md");
        idx.add("zzz/deep/Note.md");
        idx.add("dir/sub/Other.md");
        idx.add("Image.png");
        idx.add("b.md");
        idx.add("A.md");
        idx.add("Bar");
        idx.add("Bar.md");
        idx.add("a/Qux.md");
        idx.add("Qux.md");
        idx.add("Note.md"); // duplicate, ignored
        idx.add("/lead.md"); // normalized

        // 13 add() calls above, one an exact repeat; "/lead.md" is NOT a
        // duplicate, it normalizes to a new distinct entry. Hence 12.
        Vector all = idx.all();
        check(all.size() == 12, "index size after dup add");
        checkEq((String) all.elementAt(0), "A.md", "sorted first");
        checkEq((String) all.elementAt(1), "a/Qux.md", "sorted second");
        // case-insensitive order: Image.png before lead.md before Note.md
        int iImage = all.indexOf("Image.png");
        int iLead = all.indexOf("lead.md");
        int iNote = all.indexOf("Note.md");
        check(iImage >= 0 && iLead >= 0 && iNote >= 0, "sorted contains all");
        check(iImage < iLead && iLead < iNote, "case-insensitive sort order");

        Vector md = idx.markdownFiles();
        check(md.size() == 10, "markdownFiles filters non-md");
        check(!md.contains("Image.png"), "markdownFiles excludes image");
        check(!md.contains("Bar"), "markdownFiles excludes extensionless");
        check(md.contains("dir/sub/Other.md"), "markdownFiles keeps nested");

        // resolve: exact relpath beats everything
        checkEq(idx.resolve("Note.md"), "Note.md", "resolve exact");
        checkEq(idx.resolve("NOTE.MD"), "Note.md", "resolve exact case-insensitive");
        checkEq(idx.resolve("Bar"), "Bar", "resolve exact extensionless beats Bar.md");
        checkEq(idx.resolve("Image.png"), "Image.png", "resolve exact image");

        // resolve: relpath + .md
        checkEq(idx.resolve("dir/Note"), "dir/Note.md", "resolve relpath+.md");
        checkEq(idx.resolve("DIR/note"), "dir/Note.md", "resolve relpath+.md ci");
        checkEq(idx.resolve("a/Qux"), "a/Qux.md",
                "resolve relpath+.md beats shorter basename match");

        // resolve: basename match, shortest path wins
        checkEq(idx.resolve("Note"), "Note.md", "resolve basename shortest");
        checkEq(idx.resolve("note"), "Note.md", "resolve basename ci");
        checkEq(idx.resolve("Other"), "dir/sub/Other.md", "resolve basename nested");
        checkEq(idx.resolve("OTHER"), "dir/sub/Other.md", "resolve basename nested ci");

        // resolve: #heading / |alias stripped
        checkEq(idx.resolve("Other#Some Heading"), "dir/sub/Other.md",
                "resolve strips #heading");
        checkEq(idx.resolve("Other|Alias Text"), "dir/sub/Other.md",
                "resolve strips |alias");
        checkEq(idx.resolve("Other#Head|Alias"), "dir/sub/Other.md",
                "resolve strips both");
        checkEq(idx.resolve("dir/Note#x"), "dir/Note.md", "resolve path with heading");

        // resolve misses
        check(idx.resolve("missing") == null, "resolve missing null");
        check(idx.resolve("image") == null, "resolve image without ext null");
        check(idx.resolve("") == null, "resolve empty null");
        check(idx.resolve("#only-heading") == null, "resolve heading-only null");
        check(idx.resolve(null) == null, "resolve null null");

        // normalized add
        checkEq(idx.resolve("lead"), "lead.md", "resolve normalized add");

        // clear
        idx.clear();
        check(idx.all().size() == 0, "clear empties all()");
        check(idx.markdownFiles().size() == 0, "clear empties markdownFiles()");
        check(idx.resolve("Note") == null, "clear empties resolve");

        System.out.println("merge+path+index sections done");
    }
}
