import java.util.Vector;

import nok.core.Md;
import nok.core.MdBlock;
import nok.core.MdSpan;

/**
 * Desktop-JVM tests for the Md parser (Java 1.3 syntax, CLDC-safe APIs only).
 *
 * Md is the one substantial piece of Noksidian with no UI attached: nok.core.* is
 * forbidden from importing javax.*, precisely so the parser can be compiled and
 * exercised by test.sh on the desktop JDK instead of round-tripping a JAR onto the
 * phone for every edit. There is no test harness on the E71, so everything provable
 * off-device is proved here.
 *
 * The expectations come from the "Block rules (Obsidian flavor)" and "Inline rules"
 * tables in CONTRACTS.md, which in turn transcribe Obsidian's own behavior. Where
 * Obsidian and CommonMark disagree (callouts, wikilinks, ==highlight==, %% comments,
 * #tags) Obsidian wins, because the vault is authored in Obsidian on the desktop and
 * the phone is the second screen. The blocksXxx and inlineXxx suites are contract
 * coverage; regressions() is a different animal - every vector in it is a past parser
 * defect, kept as a literal so it cannot come back.
 *
 * Assertions are fail-fast (see check): test.sh runs the mains under "set -e", so the
 * first bad label aborts the build instead of burying itself in a pass/fail tally.
 */
public final class TestMd {

    /**
     * Assertions executed so far, printed as the trailing "ALL PASS <n>" line that
     * CONTRACTS.md requires of every suite. The number is part of the signal: a suite
     * dropped from main() still exits 0, and only the changed count gives it away.
     */
    static int count = 0;

    /**
     * Fails the entire run on the first bad assertion, using name as the exception
     * message. Deliberately not a tally - a parser defect usually cascades into dozens
     * of downstream failures, and the first label is the one that identifies it.
     */
    static void check(boolean cond, String name) {
        count++;
        if (!cond) {
            throw new RuntimeException(name);
        }
    }

    /**
     * Vector element as an MdBlock. CLDC 1.1 predates generics, so every read out of a
     * Vector is a hand-written cast; b and sp keep that noise out of the assertions,
     * which is the difference between a readable table of cases and a wall of casts.
     */
    static MdBlock b(Vector v, int i) {
        return (MdBlock) v.elementAt(i);
    }

    /** Vector element as an MdSpan; see b. */
    static MdSpan sp(Vector v, int i) {
        return (MdSpan) v.elementAt(i);
    }

    /**
     * Blocks before inline, because inline() is fed block text: an inline failure is
     * far easier to read once block splitting is known good. regressions() runs last -
     * it is the slowest (two timing suites) and the least likely to be the culprit.
     */
    public static void main(String[] args) {
        blocksBasic();
        blocksCode();
        blocksLists();
        blocksQuotes();
        blocksTablesImages();
        blocksMisc();
        inlineEmphasis();
        inlineCode();
        inlineLinks();
        inlineTagsAuto();
        inlineEdges();
        regressions();
        System.out.println("Md: blocks + inline OK");
        System.out.println("ALL PASS " + count);
    }

    // ---------------- blocks ----------------

    /**
     * Document-level splitting: empty and null input, YAML frontmatter, ATX headings,
     * paragraph joining, CRLF.
     *
     * parse() must hand back an empty Vector rather than null for empty and null input;
     * Viewer.setNote() stores it and lays it out with no guard, so null would be a crash
     * on an unreadable note rather than a blank screen.
     *
     * The heading cases pin the two ways a '#' is not a heading: seven of them, and one
     * with no space after it - and that second one must survive into inline() as a #tag
     * instead, which is why the test reaches across into Md.inline mid-suite. "# C#" is
     * the opposite check: a hash glued to text inside a heading stays literal. An
     * unclosed leading "---" degrades to HR plus paragraph, so a note that merely opens
     * with a horizontal rule renders instead of vanishing into frontmatter.
     *
     * CRLF is here because notes arrive over the GitHub sync path from desktop editors;
     * line splitting can never assume bare \n.
     */
    static void blocksBasic() {
        Vector v = Md.parse("");
        check(v != null && v.size() == 0, "empty -> 0 blocks");
        v = Md.parse(null);
        check(v != null && v.size() == 0, "null -> 0 blocks");

        v = Md.parse("---\ntitle: x\ntags: [a]\n---\nBody");
        check(v.size() == 2, "frontmatter block count");
        check(b(v, 0).type == MdBlock.FRONTMATTER, "frontmatter type");
        check(b(v, 0).text.equals("title: x\ntags: [a]"), "frontmatter raw yaml");
        check(b(v, 1).type == MdBlock.PARA && b(v, 1).text.equals("Body"), "frontmatter body para");

        v = Md.parse("---\nabc");
        check(v.size() == 2, "unclosed frontmatter count");
        check(b(v, 0).type == MdBlock.HR, "unclosed frontmatter -> HR");
        check(b(v, 1).type == MdBlock.PARA && b(v, 1).text.equals("abc"), "unclosed fm para");

        v = Md.parse("# Hello");
        check(v.size() == 1 && b(v, 0).type == MdBlock.HEADING, "h1 type");
        check(b(v, 0).level == 1 && b(v, 0).text.equals("Hello"), "h1 level/text");

        v = Md.parse("###### six");
        check(b(v, 0).type == MdBlock.HEADING && b(v, 0).level == 6, "h6 level");

        v = Md.parse("####### seven");
        check(b(v, 0).type == MdBlock.PARA, "7 hashes is not a heading");

        v = Md.parse("## Trail ##");
        check(b(v, 0).type == MdBlock.HEADING && b(v, 0).text.equals("Trail"), "trailing hashes stripped");

        v = Md.parse("# C#");
        check(b(v, 0).type == MdBlock.HEADING && b(v, 0).text.equals("C#"), "hash glued to text kept");

        v = Md.parse("#tag not heading");
        check(b(v, 0).type == MdBlock.PARA, "#x without space stays paragraph");
        Vector sps = Md.inline(b(v, 0).text);
        check(sp(sps, 0).kind == MdSpan.T_TAG && "tag".equals(sp(sps, 0).target),
                "#x line inlines as tag");

        // Only a blank line breaks a paragraph; the newline between "a" and "b" is kept
        // in the block text rather than collapsed, because the viewer soft-wraps to the
        // 320px screen and treats an intra-PARA newline as a space at draw time.
        v = Md.parse("a\nb\n\nc");
        check(v.size() == 2, "para split count");
        check(b(v, 0).type == MdBlock.PARA && b(v, 0).text.equals("a\nb"), "para joins with newline");
        check(b(v, 1).text.equals("c"), "second para");

        v = Md.parse("# T\r\npara text\r\n\r\n- item\r\n");
        check(v.size() == 3, "CRLF block count");
        check(b(v, 0).type == MdBlock.HEADING && b(v, 0).text.equals("T"), "CRLF heading");
        check(b(v, 1).type == MdBlock.PARA && b(v, 1).text.equals("para text"), "CRLF para");
        check(b(v, 2).type == MdBlock.BULLET && b(v, 2).text.equals("item"), "CRLF bullet");
    }

    /**
     * Code blocks: fenced with backticks or tildes, unterminated fences, and 4-space
     * indented code.
     *
     * The "if (a**b) {}" line inside the first fence is the entire point of that case -
     * fence bodies are stored verbatim and never handed to inline(), so stray markdown
     * characters in real source code must not turn into bold. The lang word lands in
     * extra, empty string rather than null when absent; nothing reads it yet, as the
     * viewer draws code blocks unhighlighted.
     *
     * An unterminated fence still yields CODE to end of note: running off the end closes
     * the fence implicitly rather than discarding the block, so a note whose closer has
     * not been typed yet still renders as code instead of reflowing as prose.
     */
    static void blocksCode() {
        Vector v = Md.parse("```java\nint x = 1;\nif (a**b) {}\n```\nafter");
        check(v.size() == 2, "fence count");
        check(b(v, 0).type == MdBlock.CODE, "fence type");
        check(b(v, 0).extra.equals("java"), "fence lang");
        check(b(v, 0).text.equals("int x = 1;\nif (a**b) {}"), "fence body verbatim");
        check(b(v, 1).type == MdBlock.PARA && b(v, 1).text.equals("after"), "after fence");

        v = Md.parse("```\nabc");
        check(v.size() == 1 && b(v, 0).type == MdBlock.CODE, "unterminated fence is code");
        check(b(v, 0).text.equals("abc") && b(v, 0).extra.equals(""), "unterminated fence body");

        v = Md.parse("~~~\ntilde body\n~~~");
        check(v.size() == 1 && b(v, 0).type == MdBlock.CODE, "tilde fence");
        check(b(v, 0).text.equals("tilde body"), "tilde fence body");

        v = Md.parse("    line1\n    line2\npara");
        check(v.size() == 2, "indented code count");
        check(b(v, 0).type == MdBlock.CODE && b(v, 0).text.equals("line1\nline2"), "indented code body");
        check(b(v, 0).extra.equals(""), "indented code lang empty");
        check(b(v, 1).type == MdBlock.PARA, "para after indented code");
    }

    /**
     * Bullets, tasks and numbered items, including the indent-to-level mapping.
     *
     * indentCols() counts a tab as two columns and listItem() uses cols / 2, so two
     * leading spaces or one tab is one level; the first case walks 0,1,2 and then
     * proves a tab collapses to the same level as two spaces - Obsidian on the desktop
     * emits either depending on editor settings, and the same note must nest identically
     * on the phone. "- [y] nope" stays a bullet: only space, x or X counts, otherwise any
     * bracketed lead-in would draw a checkbox. "12)" normalizes to num "12." so the
     * renderer has exactly one label form to draw.
     *
     * The continuation case is the subtle one. A 4-space indented line is indented CODE
     * at top level (see blocksCode) but a continuation of the previous item inside a
     * list, joined into its text with a newline; the two rules share a trigger and only
     * context separates them.
     *
     * Bullet text keeps its raw markup ("**b** [[N]]") because block and inline parsing
     * are separate passes - the viewer calls Md.inline on block text at draw time, so
     * parse() must leave the markers alone.
     */
    static void blocksLists() {
        Vector v = Md.parse("- a\n  - b\n    - c\n\t- d");
        check(v.size() == 4, "bullet count");
        check(b(v, 0).type == MdBlock.BULLET && b(v, 0).level == 0, "bullet lvl0");
        check(b(v, 1).level == 1 && b(v, 1).text.equals("b"), "bullet lvl1");
        check(b(v, 2).level == 2 && b(v, 2).text.equals("c"), "bullet lvl2");
        check(b(v, 3).level == 1 && b(v, 3).text.equals("d"), "tab indent = lvl1");

        v = Md.parse("* star\n+ plus");
        check(b(v, 0).type == MdBlock.BULLET && b(v, 0).text.equals("star"), "star bullet");
        check(b(v, 1).type == MdBlock.BULLET && b(v, 1).text.equals("plus"), "plus bullet");

        v = Md.parse("- [ ] todo\n- [x] done\n- [X] DONE\n- [y] nope");
        check(b(v, 0).type == MdBlock.TASK && !b(v, 0).checked, "task unchecked");
        check(b(v, 0).text.equals("todo"), "task text");
        check(b(v, 1).type == MdBlock.TASK && b(v, 1).checked, "task x checked");
        check(b(v, 2).type == MdBlock.TASK && b(v, 2).checked, "task X checked");
        check(b(v, 3).type == MdBlock.BULLET && b(v, 3).text.equals("[y] nope"), "bad task is bullet");

        v = Md.parse("  - [ ] sub");
        check(b(v, 0).type == MdBlock.TASK && b(v, 0).level == 1, "nested task level");

        v = Md.parse("1. one\n12) twelve\n  2. sub");
        check(b(v, 0).type == MdBlock.NUMBERED, "numbered type");
        check("1.".equals(b(v, 0).num) && b(v, 0).text.equals("one"), "numbered 1.");
        check("12.".equals(b(v, 1).num) && b(v, 1).text.equals("twelve"), "numbered 12)");
        check(b(v, 2).level == 1 && "2.".equals(b(v, 2).num), "numbered nested");

        v = Md.parse("- a\n\n- b");
        check(v.size() == 2, "empty line in list keeps 2 blocks");
        check(b(v, 0).type == MdBlock.BULLET && b(v, 1).type == MdBlock.BULLET, "both bullets");

        v = Md.parse("- a\n    cont");
        check(v.size() == 1 && b(v, 0).type == MdBlock.BULLET, "indented cont stays in list");
        check(b(v, 0).text.equals("a\ncont"), "list continuation text");

        v = Md.parse("- **b** [[N]]");
        check(b(v, 0).type == MdBlock.BULLET && b(v, 0).text.equals("**b** [[N]]"),
                "bullet keeps inline markup");
    }

    /**
     * Quotes and Obsidian callouts, which share the '>' marker but accumulate opposite
     * ways.
     *
     * Each plain "> " line becomes its own QUOTE block - CONTRACTS.md calls that
     * simplification acceptable, and Viewer.layoutQuote() draws one bar per block
     * anyway. A callout is the opposite: "> [!note] Title" opens a CALLOUT and every
     * following "> " line appends to its text, because Viewer.layoutCallout() draws it
     * as one tinted box whose height is only known after the whole body has flowed. A
     * blank line closes it.
     *
     * The callout word is lowercased into extra as CONTRACTS.md specifies, so consumers
     * compare without case-folding, and "> [not] a callout" pins that the bang, not the
     * bracket, is what
     * chooses the callout path - bracketed prose at the start of a quote is common.
     */
    static void blocksQuotes() {
        Vector v = Md.parse("> hi");
        check(v.size() == 1 && b(v, 0).type == MdBlock.QUOTE, "quote type");
        check(b(v, 0).level == 1 && b(v, 0).text.equals("hi"), "quote depth/text");

        v = Md.parse(">> nested\n> > spaced");
        check(b(v, 0).level == 2 && b(v, 0).text.equals("nested"), "quote depth 2");
        check(b(v, 1).level == 2 && b(v, 1).text.equals("spaced"), "quote depth 2 spaced");

        v = Md.parse("> a\n> b");
        check(v.size() == 2, "quote lines are separate blocks");
        check(b(v, 0).text.equals("a") && b(v, 1).text.equals("b"), "quote line texts");

        v = Md.parse("> [!NOTE] Heads\n> more\n> lines");
        check(v.size() == 1 && b(v, 0).type == MdBlock.CALLOUT, "callout type");
        check("note".equals(b(v, 0).extra), "callout word lowercased");
        check(b(v, 0).text.equals("Heads\nmore\nlines"), "callout body appended");
        check(b(v, 0).level == 1, "callout depth");

        v = Md.parse("> [!warning]\n> body");
        check(b(v, 0).type == MdBlock.CALLOUT && "warning".equals(b(v, 0).extra), "callout no title");
        check(b(v, 0).text.equals("body"), "callout titleless body");

        v = Md.parse("> [!tip] T\n\n> plain");
        check(v.size() == 2, "blank ends callout");
        check(b(v, 0).type == MdBlock.CALLOUT && b(v, 1).type == MdBlock.QUOTE, "callout then quote");

        v = Md.parse("> [not] a callout");
        check(b(v, 0).type == MdBlock.QUOTE && b(v, 0).text.equals("[not] a callout"),
                "bracket without bang stays quote");
    }

    /**
     * Table rows and whole-line images.
     *
     * Table rows are stored raw (b.text is the trimmed line, bars and padding included)
     * and split into cells at render time, so the assertions compare the untouched line.
     * Separator rows carry no content and are dropped outright, so a note that is
     * nothing but a separator yields zero blocks. A line with a pipe in the middle and
     * no leading bar still counts; table detection is the parser's last block rule
     * precisely because " | " turns up in ordinary prose.
     *
     * A line becomes an IMAGE block only when it is entirely an image, trailing spaces
     * aside; "![a](b.png) tail" stays a paragraph, since an IMAGE block has nowhere to
     * put the tail and dropping it would be silent data loss. The "title" form is
     * accepted and the title discarded by urlOf(), one of the few places Md throws text
     * away on purpose.
     */
    static void blocksTablesImages() {
        Vector v = Md.parse("| a | b |\n|---|---|\n| 1 | 2 |");
        check(v.size() == 2, "separator row dropped");
        check(b(v, 0).type == MdBlock.TABLE_ROW && b(v, 0).text.equals("| a | b |"), "table row raw");
        check(b(v, 1).text.equals("| 1 | 2 |"), "table data row");

        v = Md.parse("cell a | cell b");
        check(b(v, 0).type == MdBlock.TABLE_ROW, "pipe-in-middle is table row");

        v = Md.parse("|:---:|---|");
        check(v.size() == 0, "lone separator dropped entirely");

        v = Md.parse("![alt](img.png)");
        check(v.size() == 1 && b(v, 0).type == MdBlock.IMAGE, "image block");
        check(b(v, 0).text.equals("img.png") && b(v, 0).extra.equals("alt"), "image src/alt");

        v = Md.parse("![[pic.jpg]]  ");
        check(b(v, 0).type == MdBlock.IMAGE, "wiki image trailing spaces ok");
        check(b(v, 0).text.equals("pic.jpg") && b(v, 0).extra.equals(""), "wiki image src");

        v = Md.parse("![a](i.png \"title\")");
        check(b(v, 0).type == MdBlock.IMAGE && b(v, 0).text.equals("i.png"), "image title ignored");

        v = Md.parse("![a](b.png) tail");
        check(b(v, 0).type == MdBlock.PARA, "image with tail is a paragraph");
    }

    /**
     * Horizontal rules and %% block comments.
     *
     * HR shares its markers with bullets and with the frontmatter fence, so this walks
     * every marker, the spaced forms, the two-dash near miss, and "- x" which must stay
     * a bullet. listItem() defers to isHr() for "- - -" while "- x" never reaches the HR
     * rule; these cases are what pin that interplay in place.
     *
     * An unterminated "%%" swallows the rest of the note rather than showing it as
     * literal "%%". That looks like data loss, but the opposite failure - revealing text
     * the author deliberately hid - is the worse one on a phone handed to other people.
     */
    static void blocksMisc() {
        Vector v = Md.parse("---");
        check(v.size() == 1 && b(v, 0).type == MdBlock.HR, "dash hr");
        v = Md.parse("***");
        check(b(v, 0).type == MdBlock.HR, "star hr");
        v = Md.parse("___");
        check(b(v, 0).type == MdBlock.HR, "underscore hr");
        v = Md.parse("*  *  *");
        check(b(v, 0).type == MdBlock.HR, "spaced hr");
        v = Md.parse("--");
        check(b(v, 0).type == MdBlock.PARA, "two dashes not hr");
        v = Md.parse("- - -");
        check(b(v, 0).type == MdBlock.HR, "spaced dash hr");
        v = Md.parse("- x");
        check(b(v, 0).type == MdBlock.BULLET && b(v, 0).text.equals("x"),
                "dash+text is bullet not hr");

        v = Md.parse("vis\n%%\nhidden1\nhidden2\n%%\nafter");
        check(v.size() == 2, "block comment removed");
        check(b(v, 0).text.equals("vis") && b(v, 1).text.equals("after"), "comment neighbors kept");

        v = Md.parse("%%\nnever closed");
        check(v.size() == 0, "unterminated block comment eats rest");
    }

    // ---------------- inline ----------------

    /**
     * Emphasis, and the fact that styles combine rather than nest.
     *
     * Viewer.flowSpans() draws a flat list of spans - there is no span tree to walk -
     * so "**bold *it* tail**" comes back as three spans
     * whose style bitmasks are BOLD, BOLD|ITALIC, BOLD. Every nesting case in here
     * exists to prove the inner span carries the union of the flags instead of replacing
     * them, in both orders and across strike and code.
     *
     * inline() shares parse()'s never-null contract for empty and null input.
     */
    static void inlineEmphasis() {
        Vector v = Md.inline("");
        check(v != null && v.size() == 0, "inline empty");
        v = Md.inline(null);
        check(v != null && v.size() == 0, "inline null");

        v = Md.inline("plain");
        check(v.size() == 1, "plain size");
        check(sp(v, 0).kind == MdSpan.T_TEXT && sp(v, 0).style == 0, "plain kind/style");
        check(sp(v, 0).text.equals("plain") && sp(v, 0).target == null, "plain text/target");

        v = Md.inline("**b**");
        check(v.size() == 1 && sp(v, 0).style == MdSpan.B_BOLD, "bold star");
        check(sp(v, 0).text.equals("b"), "bold star text");
        v = Md.inline("__b__");
        check(v.size() == 1 && sp(v, 0).style == MdSpan.B_BOLD, "bold underscore");

        v = Md.inline("*i*");
        check(v.size() == 1 && sp(v, 0).style == MdSpan.B_ITALIC, "italic star");
        v = Md.inline("_i_");
        check(v.size() == 1 && sp(v, 0).style == MdSpan.B_ITALIC, "italic underscore");

        v = Md.inline("***bi***");
        check(v.size() == 1, "bold-italic single span");
        check(sp(v, 0).style == (MdSpan.B_BOLD | MdSpan.B_ITALIC), "bold-italic star flags");
        v = Md.inline("___bi___");
        check(v.size() == 1 && sp(v, 0).style == (MdSpan.B_BOLD | MdSpan.B_ITALIC),
                "bold-italic underscore flags");

        v = Md.inline("**bold *it* tail**");
        check(v.size() == 3, "nested emph span count");
        check(sp(v, 0).text.equals("bold ") && sp(v, 0).style == MdSpan.B_BOLD, "nested outer 1");
        check(sp(v, 1).text.equals("it")
                && sp(v, 1).style == (MdSpan.B_BOLD | MdSpan.B_ITALIC), "nested inner combines");
        check(sp(v, 2).text.equals(" tail") && sp(v, 2).style == MdSpan.B_BOLD, "nested outer 2");

        v = Md.inline("*out **in** out*");
        check(v.size() == 3, "italic-wrapping-bold count");
        check(sp(v, 0).style == MdSpan.B_ITALIC, "iwb outer style");
        check(sp(v, 1).style == (MdSpan.B_ITALIC | MdSpan.B_BOLD), "iwb inner combines");

        v = Md.inline("pre **b** post");
        check(v.size() == 3, "pre/post count");
        check(sp(v, 0).text.equals("pre ") && sp(v, 0).style == 0, "pre text");
        check(sp(v, 2).text.equals(" post") && sp(v, 2).style == 0, "post text");

        v = Md.inline("~~gone~~");
        check(v.size() == 1 && sp(v, 0).style == MdSpan.B_STRIKE, "strike");
        v = Md.inline("==mark==");
        check(v.size() == 1 && sp(v, 0).style == MdSpan.B_HIGHLIGHT, "highlight");

        v = Md.inline("**a ~~b~~ c**");
        check(v.size() == 3, "strike in bold count");
        check(sp(v, 1).style == (MdSpan.B_BOLD | MdSpan.B_STRIKE), "strike+bold combine");

        v = Md.inline("**a**_b_");
        check(v.size() == 2, "adjacent emphasis count");
        check(sp(v, 0).style == MdSpan.B_BOLD && sp(v, 1).style == MdSpan.B_ITALIC,
                "adjacent emphasis styles");
    }

    /**
     * Inline code spans.
     *
     * Backtick content is emitted verbatim: "`**x**`" keeps its stars, which is the
     * whole reason code spans exist in a notes app full of markdown snippets about
     * markdown. A run of n backticks is closed by a run of exactly n, so the double form
     * lets a span contain a backtick; one padding space on each side is then stripped,
     * which is the only way to write a literal backtick, so "`` `tick ``" yields "`tick"
     * rather than a padded string.
     *
     * An unterminated backtick stays literal text with style 0, in line with the
     * parser's rule that every unclosed delimiter degrades to the text it was written
     * as rather than restyling the remainder of the line.
     */
    static void inlineCode() {
        Vector v = Md.inline("`code`");
        check(v.size() == 1 && sp(v, 0).style == MdSpan.B_CODE, "code span");
        check(sp(v, 0).text.equals("code"), "code text");

        v = Md.inline("`**x**`");
        check(v.size() == 1 && sp(v, 0).text.equals("**x**"), "code not inline-parsed");
        check(sp(v, 0).style == MdSpan.B_CODE, "code style only");

        v = Md.inline("``a `b` c``");
        check(v.size() == 1 && sp(v, 0).text.equals("a `b` c"), "double backtick");
        check(sp(v, 0).style == MdSpan.B_CODE, "double backtick style");

        v = Md.inline("`` `tick ``");
        check(v.size() == 1 && sp(v, 0).text.equals("`tick"), "double backtick space strip");

        v = Md.inline("`abc");
        check(v.size() == 1 && sp(v, 0).text.equals("`abc") && sp(v, 0).style == 0,
                "unterminated backtick literal");

        v = Md.inline("a `x` b");
        check(v.size() == 3, "code amid text count");
        check(sp(v, 1).style == MdSpan.B_CODE && sp(v, 1).text.equals("x"), "code amid text");

        v = Md.inline("**`c`**");
        check(v.size() == 1 && sp(v, 0).style == (MdSpan.B_BOLD | MdSpan.B_CODE),
                "code inside bold combines");
    }

    /**
     * Markdown links, inline images and wikilinks.
     *
     * A link label is itself inline-parsed, so "[a **b**](u)" splits into two spans and
     * both must keep kind T_LINK and the same target: the viewer decides tappability per
     * span, and a styled fragment that lost its target would be a dead zone inside a
     * live link. Link titles are dropped, same reasoning as image titles.
     *
     * Wikilink target is the part before '#' and '|', while the display text keeps a
     * heading but is replaced by an alias. That asymmetry is Obsidian's, not an
     * oversight, and the four combination cases pin it. For "![[img/shot.png]]" the
     * target keeps the vault-relative path the loader needs to resolve the file, while
     * the span text is the bare filename - span text is what the viewer shows when the
     * image cannot be decoded, so it must never be empty.
     *
     * An unclosed "[broken](never" degrades to literal text instead of consuming to end
     * of line.
     */
    static void inlineLinks() {
        Vector v = Md.inline("[text](http://a.com)");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_LINK, "md link kind");
        check(sp(v, 0).text.equals("text") && "http://a.com".equals(sp(v, 0).target), "md link parts");

        v = Md.inline("[t](http://u \"title\")");
        check("http://u".equals(sp(v, 0).target), "link title ignored");

        v = Md.inline("**[x](u)**");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_LINK, "link inside bold kind");
        check(sp(v, 0).style == MdSpan.B_BOLD && "u".equals(sp(v, 0).target), "link inside bold style");

        v = Md.inline("[a **b**](u)");
        check(v.size() == 2, "styled link text count");
        check(sp(v, 0).kind == MdSpan.T_LINK && sp(v, 0).text.equals("a ")
                && sp(v, 0).style == 0, "styled link part1");
        check(sp(v, 1).kind == MdSpan.T_LINK && sp(v, 1).text.equals("b")
                && sp(v, 1).style == MdSpan.B_BOLD && "u".equals(sp(v, 1).target),
                "styled link part2");

        v = Md.inline("~~[a](u)~~");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_LINK
                && sp(v, 0).style == MdSpan.B_STRIKE, "link in strike");

        v = Md.inline("[a](u1) mid [b](u2)");
        check(v.size() == 3, "two links count");
        check("u1".equals(sp(v, 0).target) && "u2".equals(sp(v, 2).target), "two link targets");
        check(sp(v, 1).kind == MdSpan.T_TEXT && sp(v, 1).text.equals(" mid "), "text between links");

        v = Md.inline("see ![alt](i.png)!");
        check(v.size() == 3, "inline image count");
        check(sp(v, 1).kind == MdSpan.T_IMAGE, "inline image kind");
        check(sp(v, 1).text.equals("alt") && "i.png".equals(sp(v, 1).target), "inline image parts");

        v = Md.inline("![[img/shot.png]]");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_IMAGE, "wiki image kind");
        check(sp(v, 0).text.equals("shot.png") && "img/shot.png".equals(sp(v, 0).target),
                "wiki image filename text");

        v = Md.inline("[[Note]]");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_WIKILINK, "wikilink kind");
        check("Note".equals(sp(v, 0).target) && sp(v, 0).text.equals("Note"), "wikilink plain");

        v = Md.inline("[[Note|Alias]]");
        check("Note".equals(sp(v, 0).target) && sp(v, 0).text.equals("Alias"), "wikilink alias");

        v = Md.inline("[[Note#Sec]]");
        check("Note".equals(sp(v, 0).target) && sp(v, 0).text.equals("Note#Sec"), "wikilink heading");

        v = Md.inline("[[Note#Sec|A]]");
        check("Note".equals(sp(v, 0).target) && sp(v, 0).text.equals("A"), "wikilink heading+alias");

        v = Md.inline("go [[A B]] now");
        check(v.size() == 3 && sp(v, 1).kind == MdSpan.T_WIKILINK
                && "A B".equals(sp(v, 1).target), "wikilink amid text");

        v = Md.inline("**[[N]]**");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_WIKILINK
                && sp(v, 0).style == MdSpan.B_BOLD, "wikilink in bold");

        v = Md.inline("[broken](never");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_TEXT
                && sp(v, 0).text.equals("[broken](never"), "unclosed link literal");
    }

    /**
     * #tags and bare-URL autolinking - both are boundary problems, not syntax problems.
     *
     * A tag may not follow an alphanumeric or another '#', so "x#y" stays text and "C#"
     * in prose or leftover heading markup cannot become one. A tag must also contain a
     * non-digit, which keeps "#123" (issue numbers, years) plain while still allowing
     * "#1a". Tag characters are letters, digits, '-', '_', '/' and, so non-Latin tags
     * work, everything above ASCII - '/' is what lets Obsidian's nested tags survive.
     *
     * Autolinks run to whitespace but give back trailing punctuation, which belongs to
     * the sentence far more often than to the URL; on this device a mis-scoped link
     * means fetching the wrong page over a slow metered connection. A ')' is only given
     * back when the URL closes more parens than it opens, so "http://x/Foo_(bar)" keeps
     * its own. Both cases assert the punctuation lands in its own text span. A bare
     * "http://" with nothing after it is not a link at all.
     */
    static void inlineTagsAuto() {
        Vector v = Md.inline("#tag");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_TAG, "tag kind");
        check(sp(v, 0).text.equals("#tag") && "tag".equals(sp(v, 0).target), "tag parts");

        v = Md.inline("a #x1 b");
        check(v.size() == 3 && sp(v, 1).kind == MdSpan.T_TAG
                && "x1".equals(sp(v, 1).target), "tag after space");

        v = Md.inline("#123");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_TEXT, "all-digit tag rejected");

        v = Md.inline("#1a");
        check(sp(v, 0).kind == MdSpan.T_TAG && "1a".equals(sp(v, 0).target), "digit+letter tag ok");

        v = Md.inline("x#y");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_TEXT
                && sp(v, 0).text.equals("x#y"), "no tag after alnum");

        v = Md.inline("(#t)");
        check(v.size() == 3 && sp(v, 1).kind == MdSpan.T_TAG, "tag after punct");

        v = Md.inline("#a/b-c_d");
        check(sp(v, 0).kind == MdSpan.T_TAG && "a/b-c_d".equals(sp(v, 0).target), "tag charset");

        v = Md.inline("go http://ex.com now");
        check(v.size() == 3, "autolink count");
        check(sp(v, 1).kind == MdSpan.T_LINK && "http://ex.com".equals(sp(v, 1).target),
                "autolink target");
        check(sp(v, 2).text.equals(" now"), "autolink tail");

        v = Md.inline("https://a.io.");
        check(v.size() == 2, "autolink trailing dot split");
        check("https://a.io".equals(sp(v, 0).target) && sp(v, 1).text.equals("."),
                "autolink trailing dot trimmed");

        v = Md.inline("(see http://x.io)");
        check(v.size() == 3, "autolink in parens count");
        check("http://x.io".equals(sp(v, 1).target) && sp(v, 2).text.equals(")"),
                "autolink paren trimmed");

        v = Md.inline("<https://x.y>");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_LINK
                && "https://x.y".equals(sp(v, 0).target), "angle autolink");

        v = Md.inline("http:// nothing");
        check(sp(v, 0).kind == MdSpan.T_TEXT, "bare scheme not a link");
    }

    /**
     * Escapes, unterminated delimiters, prose that must stay literal, and one full mixed
     * line.
     *
     * A backslash escapes ASCII punctuation only (isPunct), so "a\b" keeps its
     * backslash: notes are full of Windows paths and regexes, and eating a backslash
     * before a letter would quietly corrupt them. Every unterminated delimiter form
     * degrades to literal text with style 0, the parser's blanket rule that no input
     * throws and nothing fails hard.
     *
     * The literal cases are drawn from real prose and code notes that a naive scanner
     * mangles: space-flanked stars used as bullets or math, a lone "==" from a code
     * comparison, intraword underscores in identifiers (by far the most common), a
     * leading underscore on snake_case, and a percent sign that is not a comment marker.
     *
     * The last case is the integration check. The span count of 11 is exact and the
     * indices are load-bearing: markup lands on even indices and the single separating
     * spaces on odd ones, so one extra or dropped span shifts everything after it and
     * fails loudly instead of silently checking the wrong span.
     */
    static void inlineEdges() {
        Vector v = Md.inline("\\*lit\\*");
        check(v.size() == 1 && sp(v, 0).text.equals("*lit*") && sp(v, 0).style == 0,
                "escaped stars literal");

        v = Md.inline("\\[[x]]");
        check(v.size() == 1 && sp(v, 0).text.equals("[[x]]"), "escaped bracket kills wikilink");

        v = Md.inline("\\`no`");
        check(v.size() == 1 && sp(v, 0).text.equals("`no`"), "escaped backtick literal");

        v = Md.inline("a\\b");
        check(v.size() == 1 && sp(v, 0).text.equals("a\\b"), "backslash before letter kept");

        v = Md.inline("\\\\");
        check(v.size() == 1 && sp(v, 0).text.equals("\\"), "escaped backslash");

        v = Md.inline("*a\\*b*");
        check(v.size() == 1 && sp(v, 0).text.equals("a*b")
                && sp(v, 0).style == MdSpan.B_ITALIC, "escape inside emphasis");

        v = Md.inline("**abc");
        check(v.size() == 1 && sp(v, 0).text.equals("**abc") && sp(v, 0).style == 0,
                "unterminated bold literal");

        v = Md.inline("a * b * c");
        check(v.size() == 1 && sp(v, 0).text.equals("a * b * c"),
                "space-flanked stars stay literal");

        v = Md.inline("a == b");
        check(v.size() == 1 && sp(v, 0).text.equals("a == b"), "lone == literal");

        v = Md.inline("foo_bar_baz");
        check(v.size() == 1 && sp(v, 0).text.equals("foo_bar_baz"),
                "intraword underscores literal");

        v = Md.inline("_snake_case");
        check(v.size() == 1 && sp(v, 0).text.equals("_snake_case"),
                "underscore into word literal");

        v = Md.inline("*i*.");
        check(v.size() == 2 && sp(v, 0).style == MdSpan.B_ITALIC
                && sp(v, 1).text.equals("."), "emphasis before punctuation");

        // The comment is removed but its surrounding spaces are not, hence "a  b" with
        // two spaces: collapsing whitespace here would mean rewriting text the author
        // typed, and the viewer's soft wrap makes the extra space invisible anyway.
        v = Md.inline("a %%c%% b");
        check(v.size() == 1 && sp(v, 0).text.equals("a  b"), "inline comment dropped");

        v = Md.inline("a %%ccc");
        check(v.size() == 1 && sp(v, 0).text.equals("a "), "unterminated comment drops rest");

        v = Md.inline("%% all %%");
        check(v.size() == 0, "comment-only yields no spans");

        v = Md.inline("![x] no");
        check(v.size() == 1 && sp(v, 0).text.equals("![x] no"), "bang without image literal");

        v = Md.inline("100% sure");
        check(v.size() == 1 && sp(v, 0).text.equals("100% sure"), "single percent literal");

        // full mixed line
        v = Md.inline("**B** `c` [[W|w]] #t http://u.v ~~s~~");
        check(v.size() == 11, "mixed line span count");
        check(sp(v, 0).style == MdSpan.B_BOLD, "mixed bold");
        check(sp(v, 2).style == MdSpan.B_CODE, "mixed code");
        check(sp(v, 4).kind == MdSpan.T_WIKILINK && sp(v, 4).text.equals("w"), "mixed wikilink");
        check(sp(v, 6).kind == MdSpan.T_TAG, "mixed tag");
        check(sp(v, 8).kind == MdSpan.T_LINK, "mixed autolink");
        check(sp(v, 10).style == MdSpan.B_STRIKE, "mixed strike");
    }

    // ---------------- regressions ----------------

    /**
     * Vectors taken from past parser defects. Each block here is a bug, not a rule, so
     * nothing in it should be "simplified away" as redundant with the suites above: the
     * contract cases these overlap with all pass without pinning these shapes.
     *
     * BOM: notes written by desktop editors and pulled down through GitHub sync can
     * start with a UTF-8 BOM, which defeated frontmatter detection because charAt(0) was
     * no longer '-'. Md now strips it before anything else inspects the first character.
     *
     * Multi-paragraph %% comments: the block-comment skipper stopped at the blank line
     * and leaked the hidden paragraphs back into the rendered note. The companion case
     * pins the split of responsibility - a comment that opens and closes on one line is
     * left for inline() to drop, so parse() must not touch it.
     *
     * [url-text](url): when the label itself looked like a URL the autolink scanner won
     * the race and retargeted the span at the display text, silently sending the reader
     * to the wrong site. Both the bare and angle-bracketed label forms are pinned.
     *
     * Continuation buffers: list and callout continuations accumulate into a
     * StringBuffer that has to be flushed at exactly the right moments. These three
     * cases are the flush points that were wrong - several continuations in a row, a new
     * list item, and a second callout opening immediately after the first.
     *
     * The two timing blocks at the end guard complexity, not speed; see their comments.
     */
    static void regressions() {
        // UTF-8 BOM must not defeat frontmatter detection
        Vector v = Md.parse("\ufeff---\ntitle: x\n---\nbody");
        check(v.size() == 2, "BOM frontmatter count");
        check(b(v, 0).type == MdBlock.FRONTMATTER && b(v, 0).text.equals("title: x"),
                "BOM frontmatter parsed");
        check(b(v, 1).type == MdBlock.PARA && b(v, 1).text.equals("body"), "BOM body para");
        v = Md.parse("\ufeff");
        check(v.size() == 0, "BOM-only note empty");

        // multi-paragraph %% comment (opener line with trailing text)
        v = Md.parse("%% private\n\nsecret para\n\n%%\nafter");
        check(v.size() == 1, "multi-para comment hidden");
        check(b(v, 0).type == MdBlock.PARA && b(v, 0).text.equals("after"),
                "text after multi-para comment kept");
        v = Md.parse("a %%x%% b");
        check(v.size() == 1 && b(v, 0).type == MdBlock.PARA
                && b(v, 0).text.equals("a %%x%% b"), "one-line comment left to inline");

        // [url-looking-text](real-url) must target the parenthesized url
        v = Md.inline("[http://old.example.com](http://new.example.com)");
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_LINK, "url-text link kind");
        check(sp(v, 0).text.equals("http://old.example.com"), "url-text link display");
        check("http://new.example.com".equals(sp(v, 0).target), "url-text link target");
        v = Md.inline("[<http://a.b>](http://c.d)");
        check(v.size() == 1 && sp(v, 0).text.equals("http://a.b")
                && "http://c.d".equals(sp(v, 0).target), "angle link text targets paren url");

        // continuation accumulation stays correct across flush points
        v = Md.parse("- a\n    c1\n    c2");
        check(v.size() == 1 && b(v, 0).text.equals("a\nc1\nc2"), "multi list continuation");
        v = Md.parse("- a\n    c\n- b");
        check(v.size() == 2 && b(v, 0).text.equals("a\nc") && b(v, 1).text.equals("b"),
                "list continuation flushed on next item");
        v = Md.parse("> [!note] A\n> x\n> [!tip] B\n> y");
        check(v.size() == 2 && b(v, 0).text.equals("A\nx") && b(v, 1).text.equals("B\ny"),
                "callout continuation flushed on next callout");

        // large callout accumulates in linear time with exact text
        // 4000 lines is far past any real note, but that is the point: accumulating each
        // continuation by String concatenation instead of the StringBuffer parse() uses
        // makes this O(n^2), and on the E71's JVM quadratic is not "slow", it is a phone
        // that appears hung with no way to
        // cancel. The bound below cannot model device speed - it is only sharp enough to
        // catch the shape change from linear to quadratic on the desktop. The
        // exact-text assertion carries equal weight: an accumulator is trivially made
        // fast by dropping content, so both must be checked together.
        StringBuffer big = new StringBuffer("> [!quote] T");
        StringBuffer want = new StringBuffer("T");
        for (int k = 0; k < 4000; k++) {
            big.append("\n> quoted continuation line number ").append(k);
            want.append("\nquoted continuation line number ").append(k);
        }
        long t0 = System.currentTimeMillis();
        v = Md.parse(big.toString());
        long dt = System.currentTimeMillis() - t0;
        check(v.size() == 1 && b(v, 0).type == MdBlock.CALLOUT, "big callout one block");
        check(b(v, 0).text.equals(want.toString()), "big callout text");
        check(dt < 2000, "big callout bounded time");

        // pathological delimiter-heavy input must stay near-linear
        // Two adversarial shapes: many short unmatched runs (" *a" repeated) and one
        // 64KB run of a single delimiter. Both make a naive scanner search forward to
        // end-of-window for a closer at every delimiter it meets, which is quadratic.
        // Md defeats that with scan()'s noCloser memo - 12 slots, one per (delimiter,
        // run length 1..3) pair - so each pair pays for that forward search once per
        // scan window. Both inputs must come back as exactly one unstyled text span,
        // since neither contains a run that can legally close an opener.
        StringBuffer sb = new StringBuffer();
        while (sb.length() < 65536) {
            sb.append(" *a");
        }
        String stars1 = sb.toString();
        sb.setLength(0);
        for (int k = 0; k < 65536; k++) {
            sb.append('*');
        }
        String stars2 = sb.toString();
        t0 = System.currentTimeMillis();
        v = Md.inline(stars1);
        Vector v2 = Md.inline(stars2);
        dt = System.currentTimeMillis() - t0;
        check(v.size() == 1 && sp(v, 0).kind == MdSpan.T_TEXT
                && sp(v, 0).text.equals(stars1), "64KB unmatched stars one text span");
        check(v2.size() == 1 && sp(v2, 0).kind == MdSpan.T_TEXT
                && sp(v2, 0).text.equals(stars2), "64KB star run one text span");
        check(dt < 1500, "pathological emphasis bounded time");
    }
}
