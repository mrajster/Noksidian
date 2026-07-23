import nok.core.MdList;

/**
 * Desktop tests for nok.core.MdList - the list-marker rules behind the
 * editor's Enter key. Java 1.3 syntax, CLDC-safe APIs only.
 *
 * <p>MdList is deliberately javax-free (CONTRACTS.md) so the whole of the
 * "Enter continues the list" behaviour can be pinned here rather than by
 * typing into the emulator: every case below is one keypress in the editor,
 * and getting one wrong silently rewrites the user's note.
 *
 * <p>Failure is a thrown RuntimeException, so the first bad assertion aborts
 * the run and test.sh (set -e) stops there.
 */
public class TestMdList {

    static int n = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new RuntimeException("FAIL: " + name);
        n++;
    }

    static void checkEq(String got, String want, String name) {
        if (got == null || !got.equals(want)) {
            throw new RuntimeException("FAIL: " + name + " got=<" + got + "> want=<" + want + ">");
        }
        n++;
    }

    static void checkInt(int got, int want, String name) {
        if (got != want) {
            throw new RuntimeException("FAIL: " + name + " got=" + got + " want=" + want);
        }
        n++;
    }

    /** nextPrefixAt must stay silent at every column in [from,to). */
    static void gateNone(String line, int from, int to, String name) {
        for (int c = from; c < to; c++) {
            checkEq(MdList.nextPrefixAt(line, c), "", name + " col " + c);
        }
    }

    /** nextPrefixAt must return want at every column in [from,to). */
    static void gateAll(String line, int from, int to, String want, String name) {
        for (int c = from; c < to; c++) {
            checkEq(MdList.nextPrefixAt(line, c), want, name + " col " + c);
        }
    }

    public static void main(String[] args) {
        testBullets();
        testTasks();
        testStarPlusTasks();
        testNumbers();
        testNumberBoundary();
        testQuotes();
        testNestedQuotes();
        testNonLists();
        testWhitespaceOnly();
        testMarkerTab();
        testCarriageReturn();
        testStringEdges();
        testBare();
        testCaretGate();
        testCaretGateColumns();
        testConsistency();
        System.out.println("ALL PASS " + n);
    }

    /**
     * The caret gate. Enter splits the line, so everything from the caret
     * rightwards moves to the new line: with the caret at or inside the marker
     * that text already carries one, and adding another would double it
     * ("- milk" at column 0 becoming "- - milk"). Column 0 of a list line is
     * reachable in two keystrokes ("Go to top", or UP from a shorter line),
     * so these are positions users land on, not theoretical ones.
     */
    static void testCaretGate() {
        // Caret past the marker: continues as usual.
        checkEq(MdList.nextPrefixAt("- milk", 6), "- ", "gate end of line");
        checkEq(MdList.nextPrefixAt("- milk", 2), "- ", "gate just past marker");
        checkEq(MdList.nextPrefixAt("- milk", 3), "- ", "gate mid-text");
        checkEq(MdList.nextPrefixAt("1. one", 6), "2. ", "gate number");
        checkEq(MdList.nextPrefixAt("  - x", 5), "  - ", "gate indented");
        // Caret at or inside the marker: no continuation, or it would double.
        checkEq(MdList.nextPrefixAt("- milk", 0), "", "gate line start");
        checkEq(MdList.nextPrefixAt("- milk", 1), "", "gate between dash and space");
        checkEq(MdList.nextPrefixAt("1. one", 0), "", "gate number line start");
        checkEq(MdList.nextPrefixAt("> quoted", 0), "", "gate quote line start");
        checkEq(MdList.nextPrefixAt("- [x] task", 4), "", "gate inside task box");
        checkEq(MdList.nextPrefixAt("  - x", 2), "", "gate after indent only");
        // Non-list lines gate to "" at every column.
        checkEq(MdList.nextPrefixAt("plain", 0), "", "gate paragraph start");
        checkEq(MdList.nextPrefixAt("plain", 5), "", "gate paragraph end");
        checkEq(MdList.nextPrefixAt(null, 0), "", "gate null");
    }

    /**
     * The gate swept over EVERY column of every marker shape, not just the two
     * or three columns a hand-written case remembers. The rule is a single
     * boundary - silent while col &lt; prefixLen(line), the marker from
     * prefixLen(line) on - and the only way to know the boundary sits at the
     * same index for a task box, a quoted number and a nested list is to walk
     * the whole line. UiEditor passes caret - lineStart() here on every Enter,
     * so any column in range is one the user can put the caret on.
     */
    static void testCaretGateColumns() {
        // "- milk": prefixLen 2.
        gateNone("- milk", 0, 2, "sweep dash");
        gateAll("- milk", 2, 7, "- ", "sweep dash");
        // "* milk" / "+ milk": same shape, same boundary.
        gateNone("* milk", 0, 2, "sweep star");
        gateAll("* milk", 2, 7, "* ", "sweep star");
        gateNone("+ milk", 0, 2, "sweep plus");
        gateAll("+ milk", 2, 7, "+ ", "sweep plus");
        // "1. one" / "1) one": the delimiter is inside the marker, so column 2
        // (between "1." and the space) must still be silent.
        gateNone("1. one", 0, 3, "sweep number");
        gateAll("1. one", 3, 7, "2. ", "sweep number");
        gateNone("1) one", 0, 3, "sweep paren");
        gateAll("1) one", 3, 7, "2) ", "sweep paren");
        // "- [ ] task": the box is part of the marker; every column inside it
        // is silent, which is what stops "- [ ] - [ ] task".
        gateNone("- [ ] task", 0, 6, "sweep task");
        gateAll("- [ ] task", 6, 11, "- [ ] ", "sweep task");
        gateNone("- [x] task", 0, 6, "sweep done task");
        gateAll("- [x] task", 6, 11, "- [ ] ", "sweep done task");
        // "> quoted": the quote run is a marker of its own.
        gateNone("> quoted", 0, 2, "sweep quote");
        gateAll("> quoted", 2, 9, "> ", "sweep quote");
        // "  - x": indent counts towards the marker, so column 2 (past the
        // indent, on the dash) is still silent.
        gateNone("  - x", 0, 4, "sweep indented");
        gateAll("  - x", 4, 6, "  - ", "sweep indented");
        // "  12. deep": widest marker of the lot.
        gateNone("  12. deep", 0, 6, "sweep indented number");
        gateAll("  12. deep", 6, 11, "  13. ", "sweep indented number");
        // "> > - x": quote run plus a list inside it.
        gateNone("> > - x", 0, 6, "sweep quoted bullet");
        gateAll("> > - x", 6, 8, "> > - ", "sweep quoted bullet");
        // A non-list line is silent at every column including its end.
        gateNone("plain text", 0, 11, "sweep paragraph");
        gateNone("---", 0, 4, "sweep hr");
        gateNone("    indented", 0, 13, "sweep indent only");
    }

    static void testBullets() {
        checkEq(MdList.nextPrefix("- item"), "- ", "dash continues");
        checkEq(MdList.nextPrefix("* item"), "* ", "star continues");
        checkEq(MdList.nextPrefix("+ item"), "+ ", "plus continues");
        checkInt(MdList.prefixLen("- item"), 2, "dash prefix len");
        // Indent and the author's own spacing are copied verbatim.
        checkEq(MdList.nextPrefix("  - nested"), "  - ", "indent kept");
        checkEq(MdList.nextPrefix("\t- tabbed"), "\t- ", "tab indent kept");
        checkEq(MdList.nextPrefix("-   wide"), "-   ", "wide spacing kept");
        checkInt(MdList.prefixLen("  - nested"), 4, "indented prefix len");
        // Splitting mid-item still opens the marker on the new line.
        checkEq(MdList.nextPrefix("- one two"), "- ", "mid-item split");
    }

    static void testTasks() {
        checkEq(MdList.nextPrefix("- [ ] todo"), "- [ ] ", "task continues");
        // A finished task must not tick the fresh, empty item after it.
        checkEq(MdList.nextPrefix("- [x] done"), "- [ ] ", "checked reopens unchecked");
        checkEq(MdList.nextPrefix("- [X] done"), "- [ ] ", "uppercase X reopens unchecked");
        checkEq(MdList.nextPrefix("  * [ ] deep"), "  * [ ] ", "indented task");
        checkInt(MdList.prefixLen("- [ ] todo"), 6, "task prefix len");
        // "[x](url)" is a link inside a bullet, not a checkbox.
        checkEq(MdList.nextPrefix("- [x](url) link"), "- ", "bracket link is not a task");
    }

    /**
     * Task boxes on the OTHER two bullet characters. Obsidian writes "- [ ] ",
     * but a note imported from another editor (or hand-typed on the handset,
     * where '*' and '+' are one keypress and '-' is three) uses "* [ ] " or
     * "+ [ ] ", and MdList's bullet scanner treats all three alike - so if the
     * task box were somehow dash-only, Enter would silently drop the checkbox
     * and turn a checklist into a plain bullet list.
     */
    static void testStarPlusTasks() {
        checkEq(MdList.nextPrefix("+ [ ] todo"), "+ [ ] ", "plus task continues");
        checkEq(MdList.nextPrefix("* [ ] todo"), "* [ ] ", "star task continues");
        checkEq(MdList.nextPrefix("* [x] done"), "* [ ] ", "star task reopens unchecked");
        checkEq(MdList.nextPrefix("+ [X] done"), "+ [ ] ", "plus task reopens unchecked");
        checkInt(MdList.prefixLen("* [ ] todo"), 6, "star task prefix len");
        checkInt(MdList.prefixLen("+ [ ] todo"), 6, "plus task prefix len");
        // Same link rule as the dash bullet.
        checkEq(MdList.nextPrefix("* [x](url) link"), "* ", "star bracket link is not a task");
        check(MdList.isBare("+ [ ]"), "bare plus task");
        // A star bullet must still lose to the thematic-break rule.
        checkEq(MdList.nextPrefix("* * *"), "", "star hr beats star task");
    }

    static void testNumbers() {
        checkEq(MdList.nextPrefix("1. first"), "2. ", "number increments");
        checkEq(MdList.nextPrefix("9. ninth"), "10. ", "number carries");
        checkEq(MdList.nextPrefix("3) paren"), "4) ", "paren delimiter kept");
        checkEq(MdList.nextPrefix("  12. deep"), "  13. ", "indented number");
        checkEq(MdList.nextPrefix("1.  wide"), "2.  ", "number spacing kept");
        checkInt(MdList.prefixLen("12. x"), 4, "number prefix len");
        // Too long to be a list label; stays text.
        checkEq(MdList.nextPrefix("1234567890123. x"), "", "overlong number is text");
    }

    /**
     * The digit-run boundary, both delimiters, and zero. MdList caps a list
     * number at MAX_DIGITS = 9 digits so Integer.parseInt cannot overflow on a
     * keypress; that cap is an exact boundary (9 digits is a list, 10 is text)
     * and an off-by-one either way is invisible until someone's imported note
     * has a long numeric line. The ')' delimiter and "0." are the shapes a
     * generated table of contents produces, and "00."/"007." pin what happens
     * to zero padding - parseInt drops it, so the continuation is NOT a
     * verbatim copy of the label the way the spacing is.
     */
    static void testNumberBoundary() {
        // 9 digits: still a list, still increments.
        checkEq(MdList.nextPrefix("123456789. x"), "123456790. ", "nine digits is a list");
        checkInt(MdList.prefixLen("123456789. x"), 11, "nine digit prefix len");
        // 10 digits: text, exactly one digit over the cap.
        checkEq(MdList.nextPrefix("1234567890. x"), "", "ten digits is text");
        checkInt(MdList.prefixLen("1234567890. x"), 0, "ten digit prefix len");
        checkEq(MdList.nextPrefix("1234567890) x"), "", "ten digits paren is text");
        // 8 -> 9 digits crosses no boundary.
        checkEq(MdList.nextPrefix("99999999. x"), "100000000. ", "eight digits carry to nine");
        // 9 -> 10 digits does: the marker this returns is one MdList itself no
        // longer recognizes, so the list stops continuing after that line.
        checkEq(MdList.nextPrefix("999999999. x"), "1000000000. ", "nine digits carry to ten");
        checkInt(MdList.prefixLen("1000000000. x"), 0, "carried ten digit label is text");
        // Zero and zero padding.
        checkEq(MdList.nextPrefix("0. x"), "1. ", "zero increments");
        checkEq(MdList.nextPrefix("0) x"), "1) ", "zero paren increments");
        checkEq(MdList.nextPrefix("00. x"), "1. ", "leading zeros dropped");
        checkEq(MdList.nextPrefix("007. x"), "8. ", "zero padded label loses padding");
        checkInt(MdList.prefixLen("0. x"), 3, "zero prefix len");
        check(MdList.isBare("0. "), "bare zero number");
        // ')' as a first-class delimiter, not just '.' with a typo.
        checkEq(MdList.nextPrefix("10) x"), "11) ", "paren carries");
        checkEq(MdList.nextPrefix("9) x"), "10) ", "paren nine carries");
        checkEq(MdList.nextPrefix("  3) deep"), "  4) ", "indented paren");
        checkEq(MdList.nextPrefix("1)  wide"), "2)  ", "paren spacing kept");
        checkInt(MdList.prefixLen("10) x"), 4, "paren prefix len");
        check(MdList.isBare("2) "), "bare paren number");
        // Any other delimiter is not a list at all.
        checkEq(MdList.nextPrefix("1] x"), "", "bracket delimiter is text");
        checkEq(MdList.nextPrefix("1: x"), "", "colon delimiter is text");
    }

    static void testQuotes() {
        checkEq(MdList.nextPrefix("> quoted"), "> ", "quote continues");
        checkEq(MdList.nextPrefix(">> deep"), ">> ", "nested quote continues");
        checkEq(MdList.nextPrefix("> > spaced"), "> > ", "spaced nesting kept");
        checkEq(MdList.nextPrefix("> - bullet"), "> - ", "bullet inside quote");
        checkEq(MdList.nextPrefix("> 2. num"), "> 3. ", "number inside quote");
        checkEq(MdList.nextPrefix("> [!note] Callout"), "> ", "callout head continues as quote");
        checkInt(MdList.prefixLen("> - bullet"), 4, "quoted bullet prefix len");
    }

    /**
     * Quote depth 2 and 3 with a list inside. The quote scanner eats one
     * optional space PER level, so "&gt; &gt; - x" has to end its run at the
     * second "&gt; " and hand the dash to the bullet scanner: get that split
     * wrong and either a level of quoting is lost on the next line (the reply
     * un-nests itself) or the dash is swallowed into the quote run and the
     * inner list stops continuing. Both forms of nesting occur in synced
     * notes - "&gt;&gt;" from mail-style quoting, "&gt; &gt; " from Obsidian.
     */
    static void testNestedQuotes() {
        checkEq(MdList.nextPrefix("> > - x"), "> > - ", "bullet inside double quote");
        checkEq(MdList.nextPrefix(">>- x"), ">>- ", "bullet inside tight double quote");
        checkEq(MdList.nextPrefix("> > > 1) a"), "> > > 2) ", "number inside triple quote");
        checkEq(MdList.nextPrefix("> > x"), "> > ", "double quote text continues");
        checkEq(MdList.nextPrefix(">>> y"), ">>> ", "tight triple quote continues");
        checkEq(MdList.nextPrefix("> - [ ] q"), "> - [ ] ", "task inside quote");
        checkEq(MdList.nextPrefix("> > [x] done"), "> > ", "box without bullet stays a quote");
        checkInt(MdList.prefixLen("> > - x"), 6, "double quoted bullet prefix len");
        checkInt(MdList.prefixLen(">>- x"), 4, "tight quoted bullet prefix len");
        checkInt(MdList.prefixLen("> > > 1) a"), 9, "triple quoted number prefix len");
        checkInt(MdList.prefixLen("> - [ ] q"), 8, "quoted task prefix len");
        check(MdList.isBare("> > "), "bare double quote");
        check(MdList.isBare("> >"), "bare double quote without trailing space");
        check(!MdList.isBare("> > x"), "double quote with text is not bare");
        // Only ONE space per level is consumed, so the rest is inner indent -
        // and inner indent alone (no list token) is not copied.
        checkEq(MdList.nextPrefix(">  x"), "> ", "extra quote padding is not a marker");
    }

    static void testNonLists() {
        checkEq(MdList.nextPrefix("plain text"), "", "paragraph");
        checkEq(MdList.nextPrefix(""), "", "empty line");
        checkEq(MdList.nextPrefix("    indented paragraph"), "", "indent alone is no marker");
        checkEq(MdList.nextPrefix(null), "", "null line");
        // Thematic breaks are not bullets (same rule as Md.parse).
        checkEq(MdList.nextPrefix("---"), "", "hr dashes");
        checkEq(MdList.nextPrefix("- - -"), "", "spaced hr");
        checkEq(MdList.nextPrefix("***"), "", "hr stars");
        // A marker needs its space.
        checkEq(MdList.nextPrefix("-word"), "", "dash without space");
        checkEq(MdList.nextPrefix("1.5 litres"), "", "decimal is not a list");
        checkEq(MdList.nextPrefix("# Heading"), "", "heading");
        checkInt(MdList.prefixLen("plain"), 0, "paragraph prefix len");
    }

    /**
     * Lines that are nothing but whitespace. These are the lines Enter is
     * pressed on most often (the blank line between paragraphs), and they take
     * the one early exit in prefixLen that runs BEFORE the thematic-break test
     * - so if indent were ever mistaken for a marker, every blank line in a
     * note would start emitting one. They must also not be "bare": isBare
     * makes UiEditor DELETE the line, so a true here would eat blank lines.
     */
    static void testWhitespaceOnly() {
        checkEq(MdList.nextPrefix(" "), "", "one space");
        checkEq(MdList.nextPrefix("   "), "", "three spaces");
        checkEq(MdList.nextPrefix("\t"), "", "one tab");
        checkEq(MdList.nextPrefix("\t\t"), "", "two tabs");
        checkEq(MdList.nextPrefix(" \t "), "", "mixed whitespace");
        checkInt(MdList.prefixLen("   "), 0, "spaces prefix len");
        checkInt(MdList.prefixLen(" \t "), 0, "mixed whitespace prefix len");
        check(!MdList.isBare(" "), "one space is not bare");
        check(!MdList.isBare("   "), "spaces are not bare");
        check(!MdList.isBare("\t"), "tab is not bare");
        check(!MdList.isBare(" \t "), "mixed whitespace is not bare");
        checkEq(MdList.nextPrefixAt("   ", 3), "", "gate whitespace end");
        checkEq(MdList.nextPrefixAt("   ", 0), "", "gate whitespace start");
        // An indented paragraph is the same case with text after it.
        checkEq(MdList.nextPrefix("\t\t x"), "", "tab indented paragraph");
        checkInt(MdList.prefixLen("\t\t x"), 0, "tab indented paragraph prefix len");
    }

    /**
     * A tab where the marker's required space should be. The rule is that ONE
     * literal ' ' has to follow the bullet, the number delimiter and the task
     * box - the same rule Md.listItem() applies, so the editor and the viewer
     * agree on what is a list. A tab does not satisfy it: "-\ttext" renders as
     * a paragraph, so Enter must not continue it as a bullet. Once the required
     * space IS there, further tabs are ordinary spacing and get copied
     * verbatim, which is how a tab-aligned list keeps its alignment.
     */
    static void testMarkerTab() {
        // Tab INSTEAD of the required space: not a marker at all.
        checkEq(MdList.nextPrefix("-\titem"), "", "tab after dash is not a bullet");
        checkEq(MdList.nextPrefix("*\titem"), "", "tab after star is not a bullet");
        checkEq(MdList.nextPrefix("+\titem"), "", "tab after plus is not a bullet");
        checkEq(MdList.nextPrefix("1.\tx"), "", "tab after number is not a list");
        checkEq(MdList.nextPrefix("1)\tx"), "", "tab after paren number is not a list");
        checkInt(MdList.prefixLen("-\titem"), 0, "tab after dash prefix len");
        checkInt(MdList.prefixLen("1.\tx"), 0, "tab after number prefix len");
        // Tab AFTER the required space: part of the spacing, copied verbatim.
        checkEq(MdList.nextPrefix("- \titem"), "- \t", "tab spacing kept");
        checkEq(MdList.nextPrefix("-  \tx"), "-  \t", "mixed spacing kept");
        checkEq(MdList.nextPrefix("1. \tx"), "2. \t", "number tab spacing kept");
        checkInt(MdList.prefixLen("- \titem"), 3, "tab spacing prefix len");
        checkInt(MdList.prefixLen("-  \tx"), 4, "mixed spacing prefix len");
        // A tab right after the task box leaves the box unrecognized, so the
        // line falls back to the plain bullet it also is.
        checkEq(MdList.nextPrefix("- [ ]\tx"), "- ", "tab after box is not a task");
        checkInt(MdList.prefixLen("- [ ]\tx"), 2, "tab after box prefix len");
        // With the space present the box IS a task, but the reopened box is
        // written literally as "[ ] " - the box's own trailing spacing is the
        // one part of the marker that is not copied verbatim.
        checkEq(MdList.nextPrefix("- [ ] \tx"), "- [ ] ", "task box reopens with one space");
        checkInt(MdList.prefixLen("- [ ] \tx"), 7, "task tab spacing prefix len");
        // A quote level consumes only a literal space, never a tab, so the tab
        // stays inner indent and is not part of the continuation.
        checkEq(MdList.nextPrefix(">\tx"), ">", "tab after quote is inner indent");
        checkInt(MdList.prefixLen(">\tx"), 1, "tab after quote prefix len");
        // Tab as leading indent is still indent (existing rule, restated here
        // so the three tab positions sit side by side).
        checkEq(MdList.nextPrefix("\t- x"), "\t- ", "tab indent kept");
    }

    /**
     * A '\r' left inside the line. UiEditor splits its buffer on '\n' ONLY and
     * readText does not normalize newlines, so a note authored on Windows or
     * synced down from GitHub with CRLF endings reaches MdList with a trailing
     * '\r' on every line. The continuation rules must survive that: the marker
     * is at the START of the line, so nothing about it changes, and the caret
     * at the end of such a line sits past the '\r' (one column further right
     * than on an LF note) yet must still return the same marker.
     *
     * <p>The last two cases pin a real consequence rather than an intention:
     * isBare() accepts only ' ' and '\t' as trailing filler, so "- \r" is NOT
     * trailing '\r' is treated as part of the line ending rather than as
     * content, so an empty item on a CRLF note still reads as bare and the
     * "Enter on an empty item ends the list" escape fires there too.
     */
    static void testCarriageReturn() {
        checkEq(MdList.nextPrefix("- milk\r"), "- ", "CR does not break a bullet");
        checkEq(MdList.nextPrefix("1. one\r"), "2. ", "CR does not break a number");
        checkEq(MdList.nextPrefix("- [x] done\r"), "- [ ] ", "CR does not break a task");
        checkEq(MdList.nextPrefix("> quoted\r"), "> ", "CR does not break a quote");
        checkEq(MdList.nextPrefix("> > - x\r"), "> > - ", "CR does not break a quoted bullet");
        checkInt(MdList.prefixLen("- milk\r"), 2, "CR line prefix len");
        // Caret at the end of a CRLF line is one column past the visible text.
        checkEq(MdList.nextPrefixAt("- milk\r", 7), "- ", "gate past CR");
        checkEq(MdList.nextPrefixAt("- milk\r", 6), "- ", "gate before CR");
        checkEq(MdList.nextPrefixAt("- milk\r", 0), "", "gate CR line start");
        // A '\r' in the middle of the text is just a character.
        checkEq(MdList.nextPrefix("- a\rb"), "- ", "mid-line CR is text");
        // A '\r' alone is not a marker and not a break character here.
        checkEq(MdList.nextPrefix("\r"), "", "lone CR");
        checkInt(MdList.prefixLen("\r"), 0, "lone CR prefix len");
        checkEq(MdList.nextPrefix("plain\r"), "", "CR paragraph");
        // '\r' IS trailing filler: on a CRLF note (GitHub sync writes them)
        // the empty item "- \r" has to read as bare, or the "Enter on an
        // empty item ends the list" escape would be dead on that whole file.
        check(MdList.isBare("- \r"), "CR after bare bullet is bare");
        check(MdList.isBare("> \r"), "CR after bare quote is bare");
        check(MdList.isBare("- [ ] \r"), "CR after bare task is bare");
        check(MdList.isBare("- \t "), "space and tab still are trailing filler");
        check(!MdList.isBare("- \rx"), "CR mid-line is still content");
        // A CRLF thematic break is still a break, not a bullet.
        checkEq(MdList.nextPrefix("- - -\r"), "", "CR thematic break");
    }

    /**
     * Markers pressed against the very start and the very end of the string.
     * Every scanner in MdList indexes one or two characters ahead (the space
     * after a bullet, the ']' of a task box, the delimiter after the digits),
     * so end-of-string is where an off-by-one turns into a
     * StringIndexOutOfBoundsException raised inside a keypress handler. These
     * are also the lines a user is ON at the moment they press Enter: "- " is
     * exactly what the previous Enter just inserted.
     */
    static void testStringEdges() {
        // Bullet with nothing after it: no space, so no marker.
        checkEq(MdList.nextPrefix("-"), "", "lone dash");
        checkEq(MdList.nextPrefix("*"), "", "lone star");
        checkEq(MdList.nextPrefix("+"), "", "lone plus");
        checkInt(MdList.prefixLen("-"), 0, "lone dash prefix len");
        check(!MdList.isBare("-"), "lone dash is not bare");
        // Bullet whose required space ends the string: the marker Enter itself
        // just wrote.
        checkEq(MdList.nextPrefix("- "), "- ", "marker at end of string");
        checkInt(MdList.prefixLen("- "), 2, "marker at end prefix len");
        checkEq(MdList.nextPrefixAt("- ", 2), "- ", "gate marker at end");
        checkEq(MdList.nextPrefixAt("- ", 1), "", "gate inside marker at end");
        // Numbers need their space even at the end of the string.
        checkEq(MdList.nextPrefix("1."), "", "number delimiter at end");
        checkEq(MdList.nextPrefix("1)"), "", "paren delimiter at end");
        checkEq(MdList.nextPrefix("1"), "", "bare digit");
        checkEq(MdList.nextPrefix("1. "), "2. ", "number marker at end of string");
        checkInt(MdList.prefixLen("1. "), 3, "number marker at end prefix len");
        // A quote run needs no trailing space at all.
        checkEq(MdList.nextPrefix(">"), ">", "lone quote");
        checkEq(MdList.nextPrefix(">>"), ">>", "lone double quote");
        checkInt(MdList.prefixLen(">"), 1, "lone quote prefix len");
        check(MdList.isBare(">"), "lone quote is bare");
        // A task box closing the string: prefixLen stops at the ']' (5) but the
        // reopened marker still carries its space (6), so the two lengths
        // legitimately differ.
        checkEq(MdList.nextPrefix("- [ ]"), "- [ ] ", "task box at end of string");
        checkEq(MdList.nextPrefix("- [x]"), "- [ ] ", "checked box at end reopens unchecked");
        checkInt(MdList.prefixLen("- [ ]"), 5, "task box at end prefix len");
        checkEq(MdList.nextPrefixAt("- [ ]", 5), "- [ ] ", "gate task box at end");
        checkEq(MdList.nextPrefixAt("- [ ]", 4), "", "gate inside task box at end");
        // Truncated boxes must not read as tasks.
        checkEq(MdList.nextPrefix("- ["), "- ", "unclosed box");
        checkEq(MdList.nextPrefix("- [ "), "- ", "unclosed box with space");
        // Columns outside the string are not an exception, just clamped by the
        // comparison: past the end continues, before the start does not.
        checkEq(MdList.nextPrefixAt("- milk", 99), "- ", "gate far past end");
        checkEq(MdList.nextPrefixAt("- milk", -1), "", "gate negative column");
        checkEq(MdList.nextPrefixAt("", 0), "", "gate empty line");
        checkEq(MdList.nextPrefixAt("", 5), "", "gate empty line past end");
        checkInt(MdList.prefixLen(""), 0, "empty prefix len");
        checkInt(MdList.prefixLen(null), 0, "null prefix len");
        check(!MdList.isBare(null), "null is not bare");
    }

    static void testBare() {
        check(MdList.isBare("- "), "bare bullet");
        check(MdList.isBare("  - "), "bare indented bullet");
        check(MdList.isBare("- [ ] "), "bare task");
        check(MdList.isBare("- [ ]"), "bare task without trailing space");
        check(MdList.isBare("1. "), "bare number");
        check(MdList.isBare("> "), "bare quote");
        check(MdList.isBare("- \t "), "bare with trailing whitespace");
        check(!MdList.isBare("- x"), "bullet with text is not bare");
        check(!MdList.isBare("- [x] done"), "task with text is not bare");
        check(!MdList.isBare(""), "empty line is not bare");
        check(!MdList.isBare("plain"), "paragraph is not bare");
    }

    /**
     * The three entry points cross-checked against each other on one table of
     * inputs, so no future edit can leave them disagreeing on a line no
     * hand-written case happens to cover. UiEditor calls all three on the SAME
     * string for one Enter press - isBare decides whether to delete the line,
     * nextPrefixAt decides what to open the next one with - so a line that is
     * "a marker" to one of them and "not a marker" to the other is how Enter
     * ends up both deleting the marker and re-inserting it.
     *
     * <p>Invariants, for every input:
     * <ul>
     *   <li>prefixLen == 0 implies nextPrefix is "" and isBare is false;</li>
     *   <li>prefixLen &gt; 0 implies nextPrefix is non-empty, and the gate
     *       opens exactly at column prefixLen (silent at prefixLen - 1);</li>
     *   <li>isBare implies prefixLen &gt; 0;</li>
     *   <li>prefixLen never runs past the end of the line, so a caret at
     *       line.length() always sees the same answer as nextPrefix.</li>
     * </ul>
     */
    static void testConsistency() {
        String[] table = {
            "", " ", "   ", "\t", " \t ", "\r",
            "plain text", "    indented paragraph", "\t\t x", "# Heading", "1.5 litres", "-word",
            "---", "- - -", "***", "___",
            "-", "*", "+", "- ", "* ", "+ ", "- item", "* item", "+ item", "-   wide",
            "  - nested", "\t- tabbed", "- one two",
            "- [ ]", "- [x]", "- [ ] ", "- [ ] todo", "- [x] done", "- [X] done", "- [x](url) link",
            "- [", "- [ ", "- [ ]\tx", "- [ ] \tx",
            "+ [ ] todo", "* [x] done", "* [ ] ", "+ [ ]",
            "1", "1.", "1)", "1. ", "2) ", "1. first", "1) one", "9. ninth", "1.  wide", "1)  wide",
            "0. x", "0) x", "00. x", "007. x", "10) x", "  12. deep", "1] x", "1: x",
            "123456789. x", "1234567890. x", "999999999. x", "99999999. x",
            ">", ">>", "> ", "> >", "> > ", ">  x", ">\tx", "> quoted", ">> deep", "> > spaced",
            "> - bullet", "> 2. num", "> [!note] Callout", "> > - x", ">>- x", "> > > 1) a",
            "> - [ ] q", "> > [x] done", ">>> y",
            "-\titem", "*\titem", "1.\tx", "1)\tx", "- \titem", "-  \tx", "1. \tx",
            "- milk\r", "1. one\r", "- \r", "> \r", "plain\r", "- a\rb"
        };
        // null is outside the loop because the loop needs length().
        checkInt(MdList.prefixLen(null), 0, "consistency null prefix len");
        checkEq(MdList.nextPrefix(null), "", "consistency null next");
        check(!MdList.isBare(null), "consistency null bare");
        checkEq(MdList.nextPrefixAt(null, 3), "", "consistency null gate");
        for (int i = 0; i < table.length; i++) {
            String l = table[i];
            String tag = "consistency <" + l + ">";
            int pl = MdList.prefixLen(l);
            String np = MdList.nextPrefix(l);
            boolean bare = MdList.isBare(l);
            check(pl >= 0 && pl <= l.length(), tag + " prefix len in range");
            if (pl == 0) {
                checkEq(np, "", tag + " no marker means no continuation");
                check(!bare, tag + " no marker means not bare");
                checkEq(MdList.nextPrefixAt(l, 0), "", tag + " gate silent at 0");
                checkEq(MdList.nextPrefixAt(l, l.length()), "", tag + " gate silent at end");
            } else {
                check(np.length() > 0, tag + " marker means a continuation");
                checkEq(MdList.nextPrefixAt(l, pl), np, tag + " gate opens at prefix len");
                checkEq(MdList.nextPrefixAt(l, pl - 1), "", tag + " gate silent one before");
                checkEq(MdList.nextPrefixAt(l, l.length()), np, tag + " gate open at end");
            }
            if (bare) {
                check(pl > 0, tag + " bare means a marker");
            }
        }
    }
}
