package nok.core;

/**
 * Markdown list-marker arithmetic for the editor's Enter key.
 *
 * <p>Md.parse() recognizes the same bullets, tasks, numbers and quotes, but it
 * throws the marker away: a block keeps only its level, its number label and
 * its text, which is all a renderer needs. The editor needs the opposite - the
 * literal characters that opened the line, spacing included - so that Enter
 * inside a list can reopen the same marker on the next line the way a desktop
 * markdown editor does. That is what this class computes.
 *
 * <p>Recognized markers, each optionally nested under a '&gt;' quote run and
 * any leading indent (both are reproduced verbatim on the next line):
 * <ul>
 *   <li>bullets "- ", "* ", "+ "</li>
 *   <li>tasks "- [ ] " / "- [x] " (the next line always starts unchecked)</li>
 *   <li>numbers "1. " / "1) " (the next line carries the number plus one)</li>
 *   <li>quotes "&gt; ", "&gt;&gt; " with or without a list marker inside</li>
 * </ul>
 *
 * <p>Deliberately NOT recognized: a thematic break ("---", "- - -"), which
 * opens no list, and a token with no space after it ("-word", "1.word"), which
 * is ordinary text. Both rules mirror Md.isHr() and Md.listItem(), so the
 * editor does not continue something the viewer would render as plain text.
 *
 * <p>The rules are LINE-LOCAL: this class is handed one line and knows nothing
 * about the block around it, so a bullet inside a fenced code block still
 * reads as a bullet here even though the viewer renders it verbatim. Judging
 * that is the caller's job - UiEditor.onSelect suppresses the continuation
 * while the caret is inside a fence.
 *
 * <p>Pure string arithmetic with no MIDP dependency (CONTRACTS.md keeps
 * nok.core javax-free), so every rule above is exercised by TestMdList on a
 * desktop JVM instead of by hand on the handset.
 */
public final class MdList {

    /**
     * Longest digit run still treated as a list number. Anything longer is
     * left alone as text, which also keeps the Integer.parseInt below well
     * inside int range whatever the note contains.
     */
    private static final int MAX_DIGITS = 9;

    private MdList() {
    }

    /**
     * Length of the marker line opens with - leading indent, quote run, bullet
     * or number and the spaces that separate it from the text - or 0 when the
     * line opens no list or quote item. Indentation on its own is not a marker:
     * a spaces-only prefix belongs to a paragraph, not to a list.
     */
    public static int prefixLen(String line) {
        if (line == null) {
            return 0;
        }
        int n = line.length();
        int i = ws(line, 0);
        if (i >= n || isBreak(line, i)) {
            return 0;
        }
        int q = quote(line, i);
        int p = ws(line, q);
        int b = bullet(line, p);
        if (b > p) {
            return task(line, b);
        }
        // No list token: a quote run is still a marker of its own ("> text"),
        // bare indent is not.
        return (q > i) ? q : 0;
    }

    /**
     * The marker to open the NEXT line with, or "" when line opens no list or
     * quote item. Not simply a copy of this line's marker: an ordered item
     * advances its number, and a task box always reopens unchecked (copying
     * "[x] " would tick the new, empty item before it has been done).
     *
     * <p>The indent, the quote run and the author's own spacing after the
     * bullet or number ARE copied verbatim, so a two-space-indented list stays
     * indented and "1.  wide" keeps its double space.
     */
    public static String nextPrefix(String line) {
        if (line == null) {
            return "";
        }
        int n = line.length();
        int i = ws(line, 0);
        if (i >= n || isBreak(line, i)) {
            return "";
        }
        int q = quote(line, i);
        int p = ws(line, q);
        int b = bullet(line, p);
        if (b == p) {
            // Quote without a list inside continues as a quote; anything else
            // (a plain or merely indented paragraph) continues as nothing.
            return (q > i) ? line.substring(0, q) : "";
        }
        int t = task(line, b);
        StringBuffer sb = new StringBuffer(t + 4);
        sb.append(line.substring(0, p));      // indent + quote run + inner indent
        char c = line.charAt(p);
        if (c >= '0' && c <= '9') {
            int d = p;
            while (d < n && line.charAt(d) >= '0' && line.charAt(d) <= '9') {
                d++;
            }
            sb.append(number(line, p, d) + 1);
            sb.append(line.substring(d, b));  // "." or ")" plus its spacing
        } else {
            sb.append(line.substring(p, b));  // "- " / "* " / "+ " plus spacing
        }
        if (t > b) {
            sb.append("[ ] ");
        }
        return sb.toString();
    }

    /**
     * The marker for the next line when Enter is pressed at column col of
     * line, which is {@link #nextPrefix} gated on the caret standing PAST the
     * marker.
     *
     * <p>The gate is what keeps Enter from doubling a marker. Splitting a line
     * moves everything from the caret rightwards onto the new line, so with
     * the caret at (or inside) the marker that text still carries the original
     * marker: adding a continuation would give "- - milk" from "- milk" with
     * the caret at 0, and "-\n-  milk" with it between the dash and the space.
     * Column 0 of a list line is two keystrokes away - "Go to top", or UP onto
     * a longer line from a short one - so this is a position users reach.
     *
     * @param col caret offset within line, clamped internally
     * @return the marker to open the next line with, or "" for none
     */
    public static String nextPrefixAt(String line, int col) {
        if (line == null) {
            return "";
        }
        int pl = prefixLen(line);
        if (pl == 0 || col < pl) {
            return "";
        }
        return nextPrefix(line);
    }

    /**
     * True when line is a marker and nothing else (trailing spaces allowed).
     * The editor uses this for the "Enter on an empty item ends the list" rule:
     * without it a list could only ever be left by deleting the marker by hand.
     */
    public static boolean isBare(String line) {
        int len = prefixLen(line);
        if (len == 0) {
            return false;
        }
        for (int i = len; i < line.length(); i++) {
            char c = line.charAt(i);
            // '\r' counts as terminator, not content: a note synced from
            // GitHub can carry CRLF, and without this the "Enter on an empty
            // item ends the list" rule would never fire on such a file.
            if (c != ' ' && c != '\t' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Scanners. Each takes the index to start at and returns the index just
    // past whatever it matched, or the index it was given when it matched
    // nothing, so they compose left to right without any null checks.
    // ------------------------------------------------------------------

    private static int ws(String s, int i) {
        int n = s.length();
        while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /**
     * End of a '&gt;' quote run. Each level may carry one space of its own
     * padding ("&gt; &gt; x" and "&gt;&gt; x" are both depth 2), and only that
     * one space is consumed per level so the rest stays available as the inner
     * indent of a nested list.
     */
    private static int quote(String s, int i) {
        int n = s.length();
        int j = i;
        while (j < n && s.charAt(j) == '>') {
            j++;
            if (j < n && s.charAt(j) == ' ') {
                j++;
            }
        }
        return j;
    }

    /**
     * End of a "- " / "* " / "+ " / "12. " / "12) " token including the spaces
     * that follow it. The required space is what separates a list from text
     * that merely starts with the same character ("-word", "1.5x"), and it
     * matches Md.listItem()'s rule so the editor and the viewer agree.
     */
    private static int bullet(String s, int i) {
        int n = s.length();
        if (i >= n) {
            return i;
        }
        char c = s.charAt(i);
        int j;
        if (c == '-' || c == '*' || c == '+') {
            j = i + 1;
        } else if (c >= '0' && c <= '9') {
            int d = i;
            while (d < n && s.charAt(d) >= '0' && s.charAt(d) <= '9') {
                d++;
            }
            if (d - i > MAX_DIGITS || d >= n) {
                return i;
            }
            char delim = s.charAt(d);
            if (delim != '.' && delim != ')') {
                return i;
            }
            j = d + 1;
        } else {
            return i;
        }
        if (j >= n || s.charAt(j) != ' ') {
            return i;
        }
        return ws(s, j);
    }

    /**
     * End of a "[ ] " / "[x] " task box including its trailing spaces, or i
     * when the bullet carries no box. The space (or end of line) after the
     * closing bracket keeps "- [x](url)" - a bullet holding a link - from
     * being read as a checked task.
     */
    private static int task(String s, int i) {
        int n = s.length();
        if (i + 2 >= n || s.charAt(i) != '[' || s.charAt(i + 2) != ']') {
            return i;
        }
        char m = s.charAt(i + 1);
        if (m != ' ' && m != 'x' && m != 'X') {
            return i;
        }
        int j = i + 3;
        if (j < n && s.charAt(j) != ' ') {
            return i;
        }
        return ws(s, j);
    }

    /**
     * True when everything from i on is a thematic break: three or more of the
     * same -, * or _ with optional spaces between. Checked before the bullet
     * scanner, which is what makes "- - -" a rule rather than a bullet whose
     * text is "- -". Mirrors Md.isHr().
     */
    private static boolean isBreak(String s, int i) {
        char d = 0;
        int cnt = 0;
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            // '\r' skipped for the same reason as in isBare: on a CRLF note
            // "- - -\r" is still the thematic break the viewer draws.
            if (c == ' ' || c == '\t' || c == '\r') {
                continue;
            }
            if (c != '-' && c != '*' && c != '_') {
                return false;
            }
            if (d == 0) {
                d = c;
            } else if (d != c) {
                return false;
            }
            cnt++;
        }
        return cnt >= 3;
    }

    /**
     * The digit run [from,to) as an int, 0 if it will not parse. MAX_DIGITS has
     * already bounded the run, so the catch only ever covers a caller passing an
     * empty range - but an exception escaping here would come out of a keypress.
     */
    private static int number(String s, int from, int to) {
        try {
            return Integer.parseInt(s.substring(from, to));
        } catch (Throwable t) {
            return 0;
        }
    }
}
