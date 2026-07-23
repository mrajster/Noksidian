package nok.core;

/**
 * Sentence-case ("Abc") arithmetic for the editor's auto-capitalization,
 * replicating what the S60 FEP does in a native text field. The FEP
 * auto-shifts the next letter at the start of the field, at the start of a
 * line, and after a sentence-ending mark (. ! ?) followed by at least one
 * space; everywhere else letters arrive as typed. A Canvas MIDlet gets none
 * of that - key events carry the raw character - so the editor re-creates
 * the rule here.
 *
 * <p>One deliberate extension past the native rule: the position right after
 * a bare markdown list or quote marker ("- ", "1. ", "> ") also counts as a
 * sentence start, judged by {@link MdList}. To the FEP a marker is just
 * punctuation, but in a markdown editor the first word of an item is the
 * start of a sentence in every way that matters.
 *
 * <p>Pure string arithmetic, no javax (CONTRACTS.md keeps nok.core
 * javax-free), exercised by TestCaps on a desktop JVM. Takes the editor's
 * live StringBuffer rather than a String so the check costs no copy of a
 * note that can be 200000 characters long.
 */
public final class Caps {

    /**
     * Longest prefix of the current line worth copying out for the marker
     * test. Markers are short (indent + quote run + bullet/number + spaces);
     * anything longer than this cannot be one, so the line-copy below stays
     * O(1) whatever the note holds.
     */
    private static final int MAX_MARKER = 32;

    private Caps() {
    }

    /**
     * True when a letter inserted at caret starts a sentence: buffer start,
     * line start, after '.', '!' or '?' plus at least one space or tab, or
     * right after a bare list/quote marker. Trailing spaces are skipped the
     * way the FEP skips them, so "end.  " arms just like "end. ".
     */
    public static boolean sentenceStart(StringBuffer b, int caret) {
        if (b == null || caret <= 0) {
            return true;               // empty buffer / very first character
        }
        if (caret > b.length()) {
            caret = b.length();
        }
        int i = caret;
        int skipped = 0;
        while (i > 0) {
            char c = b.charAt(i - 1);
            if (c == ' ' || c == '\t') {
                skipped++;
                // Bounded: this runs per keystroke AND per repaint, and a
                // pasted run of thousands of spaces must not turn each into
                // an O(run) rescan. Past any plausible indentation no human
                // sentence is in progress; answer false and stop walking.
                if (skipped > 256) {
                    return false;
                }
                i--;
                continue;
            }
            if (c == '\n') {
                return true;           // start of a line
            }
            if ((c == '.' || c == '!' || c == '?') && skipped > 0) {
                return true;           // sentence mark + the space after it
            }
            break;
        }
        if (i == 0) {
            return true;               // only whitespace before the caret
        }
        // Marker case: everything between the line start and the caret is a
        // bare list/quote marker ("- ", "  1. ", "> "). Bounded copy: a real
        // marker is short, so a long prefix disqualifies itself unscanned.
        int ls = caret;
        while (ls > 0 && b.charAt(ls - 1) != '\n') {
            ls--;
            if (caret - ls > MAX_MARKER) {
                return false;
            }
        }
        if (ls == caret) {
            return true;               // defensive; the '\n' test above caught this
        }
        StringBuffer line = new StringBuffer(caret - ls);
        for (int j = ls; j < caret; j++) {
            line.append(b.charAt(j));
        }
        String s = line.toString();
        return MdList.prefixLen(s) > 0 && MdList.isBare(s);
    }
}
