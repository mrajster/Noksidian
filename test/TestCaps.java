import nok.core.Caps;

/**
 * Desktop tests for nok.core.Caps - the sentence-start rule behind the
 * editor's auto-capitalization. Java 1.3 syntax, CLDC-safe APIs only.
 *
 * <p>Each case is one keypress in the editor: sentenceStart(buf, caret) is
 * consulted before every inserted letter, and a wrong answer either SHOUTS
 * mid-sentence or leaves a sentence uncapitalized - both instantly visible
 * to the user, so the boundary is pinned here rather than found on device.
 *
 * <p>Failure is a thrown RuntimeException, so the first bad assertion aborts
 * the run and test.sh (set -e) stops there.
 */
public class TestCaps {

    static int n = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new RuntimeException("FAIL: " + name);
        n++;
    }

    /** sentenceStart over text with the caret at its end. */
    static boolean at(String before) {
        StringBuffer b = new StringBuffer(before);
        return Caps.sentenceStart(b, b.length());
    }

    public static void main(String[] args) {
        testStarts();
        testSentenceMarks();
        testNonStarts();
        testMarkers();
        testEdges();
        System.out.println("ALL PASS " + n);
    }

    /** Field start and line start arm the shift, exactly like the FEP. */
    static void testStarts() {
        check(at(""), "empty buffer");
        check(at("para one\n"), "after newline");
        check(at("a\n\n"), "after blank line");
        check(at("   "), "only spaces");
        check(at("\t"), "only a tab");
        check(at("x\n  "), "newline then indent");
    }

    /** Sentence mark + at least one space, the core FEP rule. */
    static void testSentenceMarks() {
        check(at("End. "), "period + space");
        check(at("Really! "), "bang + space");
        check(at("Why? "), "question + space");
        check(at("End.  "), "period + two spaces");
        check(at("End.\t"), "period + tab");
        // The FEP arms after any dot+space, abbreviations included: "e.g. "
        // capitalizes next on a real E71 too. Faithfully replicated.
        check(at("e.g. "), "abbreviation dot arms (native behavior)");
    }

    /** Everywhere else letters pass through as typed. */
    static void testNonStarts() {
        check(!at("mid"), "mid-word");
        check(!at("End."), "period without space yet");
        check(!at("3.14"), "decimal point");
        check(!at("word "), "plain space after word");
        check(!at("a, "), "comma is not a sentence mark");
        check(!at("a; "), "semicolon is not a sentence mark");
        check(!at("(x) "), "closing paren");
        check(!at("a: "), "colon is not a sentence mark");
    }

    /**
     * The markdown extension: the first word of a fresh list/quote item is a
     * sentence start, judged by MdList - so Enter's auto-continued marker
     * arms the shift for the item text that follows it.
     */
    static void testMarkers() {
        check(at("- "), "bullet marker");
        check(at("stuff\n- "), "bullet on a later line");
        check(at("1. "), "numbered marker");
        check(at("  - "), "indented marker");
        check(at("> "), "quote marker");
        check(at("- [ ] "), "task marker");
        check(!at("- x"), "item with text already");
        check(!at("x - "), "dash mid-line is not a marker");
        check(!at("--- "), "thematic break is not a marker");
    }

    static void testEdges() {
        check(Caps.sentenceStart(null, 0), "null buffer defaults to start");
        StringBuffer b = new StringBuffer("End. x");
        check(!Caps.sentenceStart(b, 6), "caret after the letter");
        check(Caps.sentenceStart(b, 5), "caret before the letter");
        // Clamping lands the caret after the trailing letter, so the answer
        // must match caret == length, not throw.
        check(!Caps.sentenceStart(b, 99), "caret past the end clamps");
        // A long line prefix cannot be a marker; the scan must stay bounded.
        StringBuffer big = new StringBuffer();
        for (int i = 0; i < 100; i++) {
            big.append('x');
        }
        check(!Caps.sentenceStart(big, big.length()), "long prefix is not a marker");
        // The whitespace walk is bounded: thousands of spaces answer false
        // fast instead of rescanning the run on every keystroke and repaint.
        StringBuffer pad = new StringBuffer("End.");
        for (int i = 0; i < 5000; i++) {
            pad.append(' ');
        }
        check(!Caps.sentenceStart(pad, pad.length()), "huge space run bails out");
    }
}
