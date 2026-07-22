package nok.core;

import java.util.Vector;

/**
 * Obsidian-flavor markdown parser. parse() does the block pass,
 * inline() does the span pass. Java 1.3 / CLDC 1.1 only.
 *
 * <p>The two passes are deliberately kept apart. parse() walks the note once,
 * line by line, and leaves every block's text as raw markdown; it never builds
 * spans. Viewer calls inline() later, per block, as it lays each one out, so a
 * long note never has the span objects for the whole document resident at once
 * in the ~2MB heap.
 *
 * <p>CLDC 1.1 has no java.util.regex and no collections beyond Vector, so every
 * rule here is a hand-written character scan over String/StringBuffer. The
 * scans are written to stay near-linear on adversarial input (long delimiter
 * runs, unclosed brackets) because a note that takes seconds to lay out is
 * indistinguishable from a hung phone: see the noCloser memo in scan() and the
 * run-length cap in emphasis().
 *
 * <p>The parser is total: no input throws and no rule fails hard. An
 * unterminated fence, quote or emphasis degrades to the plain text it was
 * written as. Text is only discarded on purpose in a few places: the %%
 * comment (which swallows to its closer or to the end of the input), the
 * table alignment row, and the optional link title in urlOf().
 */
public final class Md {

    private Md() {
    }

    // ------------------------------------------------------------------
    // Block pass
    // ------------------------------------------------------------------

    /** Parse text into a Vector of MdBlock. Never returns null. */
    public static Vector parse(String text) {
        Vector out = new Vector();
        if (text == null || text.length() == 0) {
            return out;
        }
        // The BOM has to go before anything else looks at charAt(0), or the
        // frontmatter probe and every first-line block rule see U+FEFF instead
        // of the character the author typed.
        if (text.charAt(0) == '\ufeff') { // strip UTF-8 BOM
            text = text.substring(1);
            if (text.length() == 0) {
                return out;
            }
        }
        Vector lines = splitLines(text);
        int n = lines.size();
        int i = tryFrontmatter(lines, out);
        // Block-pass state. para accumulates consecutive plain lines; lastList
        // and callout point at the block a following indented or '>' line is
        // still allowed to append to. Every rule that starts a different kind
        // of block must flush and null the ones it interrupts, or a later
        // continuation line would be appended to a block that already ended -
        // hence the repetitive flush/null preamble in each branch below.
        StringBuffer para = new StringBuffer();
        StringBuffer contQ = new StringBuffer(); // callout continuation text
        StringBuffer contL = new StringBuffer(); // list continuation text
        MdBlock lastList = null;
        MdBlock callout = null;
        while (i < n) {
            String raw = (String) lines.elementAt(i);
            String t = raw.trim();
            // A blank line ends a paragraph and a callout but deliberately
            // leaves lastList/contL alone: indented text after a blank line is
            // still content of the open list item (a "loose" list).
            if (t.length() == 0) {
                flushPara(para, out);
                flushCont(callout, contQ);
                callout = null;
                i++;
                continue;
            }
            // A line that opens %% without closing it on the same line starts a
            // multi-line comment. "%%inline%%" is left for inline() to strip so
            // that the rest of that line still renders.
            if (t.startsWith("%%") && t.indexOf("%%", 2) < 0) { // block comment
                flushPara(para, out);
                flushCont(callout, contQ);
                callout = null;
                flushCont(lastList, contL);
                lastList = null;
                i++;
                while (i < n && ((String) lines.elementAt(i)).indexOf("%%") < 0) {
                    i++;
                }
                // Unterminated: i == n and the remainder of the note is
                // swallowed rather than shown as literal %%.
                if (i < n) {
                    i++;
                }
                continue;
            }
            int cols = indentCols(raw);
            // 4+ columns of indent means indented-code context, where ``` is
            // just three literal backticks, so the fence gate is indent-limited.
            if (cols < 4 && isFenceOpen(t)) {
                flushPara(para, out);
                flushCont(callout, contQ);
                callout = null;
                flushCont(lastList, contL);
                lastList = null;
                i = readFence(lines, i, out);
                continue;
            }
            // Indented code only when nothing is open: inside a list item or
            // mid-paragraph the same indent means continuation, not code.
            if (cols >= 4 && lastList == null && para.length() == 0) {
                flushCont(callout, contQ);
                callout = null;
                i = readIndented(lines, i, out);
                continue;
            }
            if (t.charAt(0) == '>') {
                flushPara(para, out);
                flushCont(lastList, contL);
                lastList = null;
                callout = handleQuote(t, out, callout, contQ);
                i++;
                continue;
            }
            flushCont(callout, contQ);
            callout = null;
            MdBlock li = listItem(t, cols);
            if (li != null) {
                flushPara(para, out);
                flushCont(lastList, contL);
                out.addElement(li);
                lastList = li;
                i++;
                continue;
            }
            MdBlock h = heading(t);
            if (h != null) {
                flushPara(para, out);
                flushCont(lastList, contL);
                lastList = null;
                out.addElement(h);
                i++;
                continue;
            }
            if (isHr(t)) {
                flushPara(para, out);
                flushCont(lastList, contL);
                lastList = null;
                out.addElement(new MdBlock(MdBlock.HR));
                i++;
                continue;
            }
            MdBlock img = imageLine(t);
            if (img != null) {
                flushPara(para, out);
                flushCont(lastList, contL);
                lastList = null;
                out.addElement(img);
                i++;
                continue;
            }
            // Table detection is the last block rule on purpose: " | " turns up
            // in ordinary prose, so every stricter rule gets first refusal. The
            // alignment row carries no content and is dropped outright.
            if (isTableRow(t)) {
                flushPara(para, out);
                flushCont(lastList, contL);
                lastList = null;
                if (!isTableSep(t)) {
                    MdBlock b = new MdBlock(MdBlock.TABLE_ROW);
                    b.text = t;
                    out.addElement(b);
                }
                i++;
                continue;
            }
            if (cols >= 4 && lastList != null) {
                // indented continuation line inside a list item
                // Seeded with the item's own first line, because flushCont()
                // later overwrites text outright rather than appending.
                if (contL.length() == 0) {
                    contL.append(lastList.text);
                }
                contL.append('\n').append(t);
                i++;
                continue;
            }
            // Nothing matched: plain paragraph text. Lines are stored trimmed
            // and \n-joined; the viewer soft-wraps and renders \n as a space.
            if (para.length() > 0) {
                para.append('\n');
            }
            para.append(t);
            flushCont(lastList, contL);
            lastList = null;
            i++;
        }
        flushPara(para, out);
        flushCont(callout, contQ);
        flushCont(lastList, contL);
        return out;
    }

    /**
     * Splits on LF, CRLF and lone CR, since notes arrive from whatever editor
     * wrote them, and always emits a final element for the text after the last
     * terminator. CLDC has no String.split().
     */
    private static Vector splitLines(String text) {
        Vector v = new Vector();
        int n = text.length();
        int st = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n') {
                v.addElement(text.substring(st, i));
                i++;
                st = i;
            } else if (c == '\r') {
                v.addElement(text.substring(st, i));
                if (i + 1 < n && text.charAt(i + 1) == '\n') {
                    i += 2;
                } else {
                    i++;
                }
                st = i;
            } else {
                i++;
            }
        }
        v.addElement(text.substring(st));
        return v;
    }

    /**
     * Consumes YAML frontmatter when the note opens with "---" and returns the
     * index of the first body line. Returns 0 when there is none, including the
     * case of an opening "---" with no closer anywhere in the file: that line
     * then falls through to the HR rule, so a note that merely starts with a
     * horizontal rule still renders instead of vanishing into frontmatter.
     */
    private static int tryFrontmatter(Vector lines, Vector out) {
        if (lines.size() == 0) {
            return 0;
        }
        if (!((String) lines.elementAt(0)).trim().equals("---")) {
            return 0;
        }
        int n = lines.size();
        int close = -1;
        for (int j = 1; j < n; j++) {
            if (((String) lines.elementAt(j)).trim().equals("---")) {
                close = j;
                break;
            }
        }
        if (close < 0) {
            return 0;
        }
        StringBuffer sb = new StringBuffer();
        for (int j = 1; j < close; j++) {
            if (j > 1) {
                sb.append('\n');
            }
            sb.append((String) lines.elementAt(j));
        }
        MdBlock b = new MdBlock(MdBlock.FRONTMATTER);
        b.text = sb.toString();
        out.addElement(b);
        return close + 1;
    }

    private static void flushPara(StringBuffer para, Vector out) {
        if (para.length() == 0) {
            return;
        }
        MdBlock b = new MdBlock(MdBlock.PARA);
        b.text = para.toString();
        out.addElement(b);
        para.setLength(0);
    }

    /**
     * Leading indent measured in columns, counting a tab as 2. listItem() sets
     * level = cols / 2, so one tab or two spaces is exactly one nesting level.
     */
    private static int indentCols(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ' ') {
                c++;
            } else if (ch == '\t') {
                c += 2;
            } else {
                break;
            }
        }
        return c;
    }

    private static boolean isFenceOpen(String t) {
        return t.startsWith("```") || t.startsWith("~~~");
    }

    /**
     * A closer is a line of nothing but the opener's own fence character, at
     * least as long as the opener. Demanding the same character keeps a ```
     * run inside a ~~~ fence - a code sample that shows markdown - from ending
     * the block early.
     */
    private static boolean isFenceClose(String t, char fc, int flen) {
        if (t.length() < flen) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != fc) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads a fenced code block and returns the index of the line after it. The
     * body is taken from the raw lines rather than the trimmed ones so that
     * indentation inside the sample survives. The info string is cut at the
     * first space and kept in extra as the bare language word; no renderer
     * reads it today - the viewer draws code blocks unhighlighted. Running
     * off the end closes the fence implicitly instead of discarding the block,
     * so a note that is still being typed keeps rendering.
     */
    private static int readFence(Vector lines, int i, Vector out) {
        String t = ((String) lines.elementAt(i)).trim();
        char fc = t.charAt(0);
        int flen = 0;
        while (flen < t.length() && t.charAt(flen) == fc) {
            flen++;
        }
        String lang = t.substring(flen).trim();
        int sp = 0;
        while (sp < lang.length() && lang.charAt(sp) != ' ' && lang.charAt(sp) != '\t') {
            sp++;
        }
        lang = lang.substring(0, sp);
        int n = lines.size();
        StringBuffer body = new StringBuffer();
        boolean first = true;
        int j = i + 1;
        while (j < n) {
            String lt = ((String) lines.elementAt(j)).trim();
            if (isFenceClose(lt, fc, flen)) {
                j++;
                break;
            }
            if (!first) {
                body.append('\n');
            }
            body.append((String) lines.elementAt(j));
            first = false;
            j++;
        }
        MdBlock b = new MdBlock(MdBlock.CODE);
        b.text = body.toString();
        b.extra = lang;
        out.addElement(b);
        return j;
    }

    /**
     * Reads a 4-column-indented code block and returns the index of the line
     * after it. A blank line terminates the run, so a sample containing blank
     * lines becomes several CODE blocks rather than one.
     */
    private static int readIndented(Vector lines, int i, Vector out) {
        int n = lines.size();
        StringBuffer body = new StringBuffer();
        boolean first = true;
        int j = i;
        while (j < n) {
            String raw = (String) lines.elementAt(j);
            if (raw.trim().length() == 0 || indentCols(raw) < 4) {
                break;
            }
            if (!first) {
                body.append('\n');
            }
            body.append(dedent4(raw));
            first = false;
            j++;
        }
        MdBlock b = new MdBlock(MdBlock.CODE);
        b.text = body.toString();
        b.extra = "";
        out.addElement(b);
        return j;
    }

    /** Strips up to 4 columns of indent, counting a tab as 2 as indentCols does. */
    private static String dedent4(String s) {
        int i = 0;
        int col = 0;
        while (i < s.length() && col < 4) {
            char c = s.charAt(i);
            if (c == ' ') {
                col++;
                i++;
            } else if (c == '\t') {
                col += 2;
                i++;
            } else {
                break;
            }
        }
        return s.substring(i);
    }

    /**
     * Flushes accumulated continuation text into its block. Continuation
     * lines are gathered in a StringBuffer (not by String concatenation)
     * so a large block costs O(n) instead of O(n^2) character copies.
     */
    private static void flushCont(MdBlock b, StringBuffer cont) {
        if (cont.length() > 0) {
            if (b != null) {
                b.text = cont.toString();
            }
            // b is null when there is no open block to attach to; the buffer
            // still has to be reset or its text would leak into the next one.
            cont.setLength(0);
        }
    }

    /**
     * Handles one quote line. Returns the active CALLOUT block (for
     * continuation lines) or null if the line became a plain QUOTE.
     * Continuation text accumulates in cont; parse() flushes it into
     * the block via flushCont() when the callout closes.
     */
    private static MdBlock handleQuote(String t, Vector out, MdBlock callout, StringBuffer cont) {
        int len = t.length();
        int pos = 0;
        int depth = 0;
        // Count the '>' markers tolerating spaces between them, so ">>" and
        // "> >" both come out as depth 2.
        while (true) {
            int q = pos;
            while (q < len && (t.charAt(q) == ' ' || t.charAt(q) == '\t')) {
                q++;
            }
            if (q < len && t.charAt(q) == '>') {
                depth++;
                pos = q + 1;
            } else {
                break;
            }
        }
        // Exactly one space after the last '>' is the marker's own padding;
        // anything past that is real indentation and is kept.
        if (pos < len && t.charAt(pos) == ' ') {
            pos++;
        }
        String content = t.substring(pos);
        String word = calloutWord(content);
        if (word != null) {
            flushCont(callout, cont);
            MdBlock b = new MdBlock(MdBlock.CALLOUT);
            b.level = depth;
            b.extra = word.toLowerCase();
            b.text = content.substring(content.indexOf(']') + 1).trim();
            out.addElement(b);
            return b;
        }
        // Quote lines at the same depth extend the open callout; a different
        // depth is a different construct and ends it. The buffer is seeded with
        // the callout's title line because flushCont() overwrites text.
        if (callout != null && callout.level == depth) {
            if (cont.length() == 0 && callout.text.length() > 0) {
                cont.append(callout.text);
            }
            if (cont.length() > 0) {
                cont.append('\n');
            }
            cont.append(content);
            return callout;
        }
        flushCont(callout, cont);
        MdBlock b = new MdBlock(MdBlock.QUOTE);
        b.level = depth;
        b.text = content;
        out.addElement(b);
        return null;
    }

    /**
     * The word inside a "[!note]" callout marker, or null when this is an
     * ordinary quote. The character set is restricted so prose that happens to
     * start with a bracket stays a plain quote; rb &lt; 3 rejects the empty
     * "[!]" for the same reason.
     */
    private static String calloutWord(String content) {
        if (!content.startsWith("[!")) {
            return null;
        }
        int rb = content.indexOf(']');
        if (rb < 3) {
            return null;
        }
        for (int i = 2; i < rb; i++) {
            char c = content.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return content.substring(2, rb);
    }

    /**
     * Recognizes bullet, task and numbered items, or returns null. t is the
     * trimmed line while cols is the indent of the raw line: the nesting level
     * has to come from indentation that trimming already removed. "12)" is
     * normalized to num "12." so the renderer only has one label form to draw.
     */
    private static MdBlock listItem(String t, int cols) {
        char c0 = t.charAt(0);
        if (isHr(t)) {
            return null; // "- - -" / "* * *" are thematic breaks, not bullets
        }
        if (c0 == '-' || c0 == '*' || c0 == '+') {
            if (t.length() < 2 || t.charAt(1) != ' ') {
                return null;
            }
            String rest = t.substring(2).trim();
            if (rest.length() >= 3 && rest.charAt(0) == '[' && rest.charAt(2) == ']') {
                char m = rest.charAt(1);
                // A space (or end of line) must follow the box, so a bullet
                // that merely starts with a bracket - "- [x](url)" - is not
                // mistaken for a checkbox.
                if ((m == ' ' || m == 'x' || m == 'X')
                        && (rest.length() == 3 || rest.charAt(3) == ' ')) {
                    MdBlock b = new MdBlock(MdBlock.TASK);
                    b.level = cols / 2;
                    b.checked = (m == 'x' || m == 'X');
                    b.text = (rest.length() > 4) ? rest.substring(4).trim() : "";
                    return b;
                }
            }
            MdBlock b = new MdBlock(MdBlock.BULLET);
            b.level = cols / 2;
            b.text = rest;
            return b;
        }
        if (Character.isDigit(c0)) {
            int j = 0;
            while (j < t.length() && Character.isDigit(t.charAt(j))) {
                j++;
            }
            if (j < t.length() && (t.charAt(j) == '.' || t.charAt(j) == ')')
                    && j + 1 < t.length() && t.charAt(j + 1) == ' ') {
                MdBlock b = new MdBlock(MdBlock.NUMBERED);
                b.level = cols / 2;
                b.num = t.substring(0, j) + ".";
                b.text = t.substring(j + 2).trim();
                return b;
            }
        }
        return null;
    }

    /**
     * ATX heading, or null. Whitespace is required after the hashes so that
     * "#tag" stays a tag, and the level is capped at 6.
     */
    private static MdBlock heading(String t) {
        int h = 0;
        while (h < t.length() && t.charAt(h) == '#') {
            h++;
        }
        if (h < 1 || h > 6 || h >= t.length()) {
            return null;
        }
        char after = t.charAt(h);
        if (after != ' ' && after != '\t') {
            return null;
        }
        String rest = t.substring(h + 1).trim();
        int e = rest.length();
        while (e > 0 && rest.charAt(e - 1) == '#') {
            e--;
        }
        // A trailing hash run is only a closing sequence when a space precedes
        // it; without that test "# C#" would lose its sharp.
        if (e < rest.length()) {
            if (e == 0) {
                rest = "";
            } else if (rest.charAt(e - 1) == ' ') {
                rest = rest.substring(0, e).trim();
            }
        }
        MdBlock b = new MdBlock(MdBlock.HEADING);
        b.level = h;
        b.text = rest;
        return b;
    }

    /**
     * True for a thematic break: 3 or more of the same -, * or _ with optional
     * spaces between. listItem() consults this first, which is what makes
     * "- - -" a break rather than a bullet whose text is "- -".
     */
    private static boolean isHr(String t) {
        char d = 0;
        int cnt = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ' ' || c == '\t') {
                continue;
            }
            if (c == '-' || c == '*' || c == '_') {
                if (d == 0) {
                    d = c;
                } else if (d != c) {
                    return false;
                }
                cnt++;
            } else {
                return false;
            }
        }
        return cnt >= 3;
    }

    /**
     * A line that is nothing but an image becomes a standalone IMAGE block so
     * the viewer can give it the full screen width; an image with any text
     * around it stays inline and is handled by imageSpan() instead. The
     * endsWith tests reject trailing text, and the "]]" position test plus the
     * bracket/paren rejections reject leading or embedded text, so a line like
     * "![a](b.png) (see above)" falls through to a paragraph.
     */
    private static MdBlock imageLine(String t) {
        if (t.startsWith("![[") && t.endsWith("]]")) {
            if (t.indexOf("]]") != t.length() - 2) {
                return null;
            }
            String inner = t.substring(3, t.length() - 2);
            int pipe = inner.indexOf('|');
            MdBlock b = new MdBlock(MdBlock.IMAGE);
            b.text = ((pipe >= 0) ? inner.substring(0, pipe) : inner).trim();
            b.extra = (pipe >= 0) ? inner.substring(pipe + 1).trim() : "";
            return b;
        }
        if (t.startsWith("![") && t.endsWith(")")) {
            int m = t.indexOf("](");
            if (m < 0) {
                return null;
            }
            String alt = t.substring(2, m);
            if (alt.indexOf(']') >= 0) {
                return null;
            }
            String src = t.substring(m + 2, t.length() - 1);
            if (src.indexOf(')') >= 0 || src.indexOf('(') >= 0) {
                return null;
            }
            MdBlock b = new MdBlock(MdBlock.IMAGE);
            b.text = urlOf(src);
            b.extra = alt;
            return b;
        }
        return null;
    }

    /**
     * Cheap table probe: a leading pipe, or a spaced pipe anywhere. The spaced
     * form is what catches tables written without leading pipes, and is also
     * why parse() only reaches this after every stricter block rule.
     */
    private static boolean isTableRow(String t) {
        return t.charAt(0) == '|' || t.indexOf(" | ") >= 0;
    }

    /** Alignment row - only -, |, : and blanks, with at least one dash. Dropped. */
    private static boolean isTableSep(String t) {
        boolean dash = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '-') {
                dash = true;
            } else if (c != '|' && c != ':' && c != ' ' && c != '\t') {
                return false;
            }
        }
        return dash;
    }

    /** First word of s; strips surrounding &lt;...&gt; if present. */
    private static String urlOf(String s) {
        s = s.trim();
        if (s.startsWith("<")) {
            int g = s.indexOf('>');
            return (g >= 0) ? s.substring(1, g) : s.substring(1);
        }
        // Anything past the first space is the optional title in
        // [text](url "title") form, which has nowhere to go on this screen.
        int i = 0;
        while (i < s.length() && s.charAt(i) != ' ' && s.charAt(i) != '\t') {
            i++;
        }
        return s.substring(0, i);
    }

    // ------------------------------------------------------------------
    // Inline pass
    // ------------------------------------------------------------------

    /** Parse one block's text into a Vector of MdSpan. Never returns null. */
    public static Vector inline(String text) {
        Vector out = new Vector();
        if (text == null || text.length() == 0) {
            return out;
        }
        scan(text, 0, text.length(), 0, out);
        return out;
    }

    /**
     * Scans s[start,end) and appends spans to out. style is the inherited
     * bitmask of MdSpan.B_* flags; emphasis() recurses back into scan() with
     * extra bits set, which is how nested markup ends up with the combined
     * flags sitting on the innermost span.
     */
    private static void scan(String s, int start, int end, int style, Vector out) {
        StringBuffer buf = new StringBuffer();
        // Memo of failed closer scans, per delimiter {* _ ~ =} and count
        // 1..3 (slot = delim * 3 + count - 1). A value >= 0 means: no
        // candidate closer run for that (delimiter, count) exists at or
        // after that position up to this scan window's end; -1 = unknown.
        // Keeps pathological delimiter-heavy blocks O(n) instead of O(n^2).
        int[] noCloser = new int[12];
        for (int k = 0; k < 12; k++) {
            noCloser[k] = -1;
        }
        int i = start;
        while (i < end) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 < end && isPunct(s.charAt(i + 1))) {
                    buf.append(s.charAt(i + 1));
                    i += 2;
                } else {
                    buf.append('\\');
                    i++;
                }
                continue;
            }
            // Each helper returns the index to resume at, or -1 meaning "not
            // actually a construct", in which case the character is emitted
            // literally. That backtracking is why none of them may touch out or
            // buf until they have committed to a match.
            int nx = -1;
            if (c == '`') {
                nx = codeSpan(s, i, end, style, out, buf);
            } else if (c == '%' && i + 1 < end && s.charAt(i + 1) == '%') {
                nx = comment(s, i, end);
            } else if (c == '*' || c == '_' || c == '~' || c == '=') {
                nx = emphasis(s, i, end, style, out, buf, noCloser);
            } else if (c == '[') {
                if (i + 1 < end && s.charAt(i + 1) == '[') {
                    nx = wikiLink(s, i, end, style, out, buf);
                } else {
                    nx = mdLink(s, i, end, style, out, buf);
                }
            } else if (c == '!') {
                nx = imageSpan(s, i, end, style, out, buf);
            } else if (c == '#') {
                nx = tagSpan(s, i, end, style, out, buf);
            } else if (c == '<') {
                nx = angleLink(s, i, end, style, out, buf);
            } else if (c == 'h') {
                nx = autoLink(s, i, end, style, out, buf);
            }
            if (nx >= 0) {
                i = nx;
            } else {
                buf.append(c);
                i++;
            }
        }
        flush(buf, style, out);
    }

    /**
     * Emits the pending literal text as a span. Every construct calls this
     * before adding its own span, which is what keeps the output in source
     * order.
     */
    private static void flush(StringBuffer buf, int style, Vector out) {
        if (buf.length() == 0) {
            return;
        }
        out.addElement(new MdSpan(MdSpan.T_TEXT, buf.toString(), null, style));
        buf.setLength(0);
    }

    private static int runLen(String s, int i, int end, char c) {
        int j = i;
        while (j < end && s.charAt(j) == c) {
            j++;
        }
        return j - i;
    }

    /**
     * Inline code: a run of n backticks closed by a run of exactly n, so
     * ``a`b`` can contain a backtick. The content is never parsed further; it
     * just gets B_CODE added to the style already in force.
     */
    private static int codeSpan(String s, int i, int end, int style, Vector out, StringBuffer buf) {
        int n = runLen(s, i, end, '`');
        int close = findCodeClose(s, i + n, end, n);
        if (close < 0) {
            return -1;
        }
        String content = s.substring(i + n, close);
        // One padding space on each side is stripped, which is how a literal
        // backtick gets written (`` ` ``). A span that is nothing but spaces
        // keeps them all, so it does not collapse to nothing.
        if (content.length() >= 2 && content.charAt(0) == ' '
                && content.charAt(content.length() - 1) == ' ') {
            boolean allSpace = true;
            for (int k = 0; k < content.length(); k++) {
                if (content.charAt(k) != ' ') {
                    allSpace = false;
                    break;
                }
            }
            if (!allSpace) {
                content = content.substring(1, content.length() - 1);
            }
        }
        flush(buf, style, out);
        out.addElement(new MdSpan(MdSpan.T_TEXT, content, null, style | MdSpan.B_CODE));
        return close + n;
    }

    /**
     * Finds a backtick run of length exactly n, stepping over longer runs whole
     * so they are never mistaken for a short closer.
     */
    private static int findCodeClose(String s, int from, int end, int n) {
        int j = from;
        while (j < end) {
            if (s.charAt(j) == '`') {
                int rl = runLen(s, j, end, '`');
                if (rl == n) {
                    return j;
                }
                j += rl;
            } else {
                j++;
            }
        }
        return -1;
    }

    /** Skips an inline %%...%% comment; nothing is emitted for it. */
    private static int comment(String s, int i, int end) {
        int j = i + 2;
        while (j + 1 < end) {
            if (s.charAt(j) == '%' && s.charAt(j + 1) == '%') {
                return j + 2;
            }
            j++;
        }
        return end; // unterminated: drop the rest
    }

    /**
     * Handles the *, _, ~ and = openers. For * and _ the run length is tried
     * longest first and stepped down on failure, so "***x*" still emits italic
     * text instead of nothing at all.
     */
    private static int emphasis(String s, int i, int end, int style, Vector out,
            StringBuffer buf, int[] noCloser) {
        char d = s.charAt(i);
        // Only the values 1..4 of the run length matter; cap the scan so a
        // huge delimiter run is not rescanned in full at every position.
        int run = 0;
        while (run < 4 && i + run < end && s.charAt(i + run) == d) {
            run++;
        }
        // Strikethrough and highlight are doubled-only. A single ~ or = shows
        // up far too often in prose, paths and code to be treated as markup.
        if ((d == '~' || d == '=') && run < 2) {
            return -1;
        }
        int use = (d == '~' || d == '=') ? 2 : ((run > 3) ? 3 : run);
        int di = (d == '*') ? 0 : ((d == '_') ? 1 : ((d == '~') ? 2 : 3));
        while (use >= 1) {
            int inStart = i + use;
            // An opener may not be followed by whitespace, and '_' may not
            // follow an alphanumeric - without that, snake_case_identifiers
            // would come out italic.
            boolean openOk = inStart < end && !isSpace(s.charAt(inStart))
                    && !(d == '_' && i > 0 && isAlnum(s.charAt(i - 1)));
            if (openOk) {
                int slot = di * 3 + use - 1;
                int known = noCloser[slot];
                if (known < 0 || inStart < known) {
                    int close = findCloser(s, inStart, end, d, use, noCloser, slot);
                    if (close >= 0) {
                        flush(buf, style, out);
                        scan(s, inStart, close, style | emphFlags(d, use), out);
                        return close + use;
                    }
                }
            }
            if (d == '*' || d == '_') {
                use--;
            } else {
                break;
            }
        }
        return -1;
    }

    private static int emphFlags(char d, int use) {
        if (d == '~') {
            return MdSpan.B_STRIKE;
        }
        if (d == '=') {
            return MdSpan.B_HIGHLIGHT;
        }
        if (use == 1) {
            return MdSpan.B_ITALIC;
        }
        if (use == 2) {
            return MdSpan.B_BOLD;
        }
        return MdSpan.B_BOLD | MdSpan.B_ITALIC;
    }

    /**
     * Locates the run that closes an emphasis opener, or -1. Nesting is handled
     * with pending: a run that could itself open emphasis is banked, and later
     * runs pay that debt down before any of them is allowed to close us. Code
     * spans are stepped over whole because backticks may contain delimiters,
     * and a backslash always eats the following character. When the walk sees
     * no run that could ever close this (delimiter, count) at all, the start
     * position is memoized into noCloser so that later openers of the same
     * class at or after it skip the walk entirely. A failure that did see a
     * candidate is not memoized, since a later opener may still match it.
     */
    private static int findCloser(String s, int from, int end, char d, int count,
            int[] noCloser, int slot) {
        int j = from;
        int pending = 0; // nested same-delimiter openers not yet closed
        boolean candidate = false; // saw any run that could ever close us
        while (j < end) {
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == '`') {
                int rl = runLen(s, j, end, '`');
                int cl = findCodeClose(s, j + rl, end, rl);
                j = (cl >= 0) ? (cl + rl) : (j + rl);
                continue;
            }
            if (c == d) {
                int rl = runLen(s, j, end, d);
                // A closer may not be preceded by whitespace, and the '_' form
                // may not be followed by an alphanumeric; both mirror the
                // opener test in emphasis().
                boolean closerOk = j > from && !isSpace(s.charAt(j - 1));
                if (closerOk && d == '_') {
                    int after = j + rl;
                    if (after < end && isAlnum(s.charAt(after))) {
                        closerOk = false;
                    }
                }
                if (closerOk && rl >= count) {
                    candidate = true;
                    if (pending == 0) {
                        return j;
                    }
                }
                int taken = 0;
                if (closerOk && pending > 0) {
                    taken = (pending < rl) ? pending : rl;
                    pending -= taken;
                    if (pending == 0 && rl - taken >= count) {
                        return j + taken; // leftover of this run closes us
                    }
                }
                if (taken < rl) {
                    // remaining delimiters may open a nested emphasis
                    int after = j + rl;
                    boolean openerOk = after < end && !isSpace(s.charAt(after))
                            && !(d == '_' && j > 0 && isAlnum(s.charAt(j - 1)));
                    if (openerOk) {
                        pending += rl - taken;
                    }
                }
                j += rl;
                continue;
            }
            j++;
        }
        if (!candidate) {
            // No run in (from, end) can ever close (d, count); remember so
            // later delimiters of the same class skip this scan entirely.
            noCloser[slot] = from;
        }
        return -1;
    }

    /**
     * [[Target]], [[Target|Display]] and [[Target#Heading]]. The span's target
     * keeps neither the alias nor the heading anchor, so it is a bare vault
     * path for NoteIndex.resolve() (which strips both again defensively); the
     * display text keeps the anchor, since that is what the author wrote.
     */
    private static int wikiLink(String s, int i, int end, int style, Vector out, StringBuffer buf) {
        int close = indexOfStr(s, "]]", i + 2, end);
        if (close < 0) {
            return -1;
        }
        String inner = s.substring(i + 2, close);
        if (inner.length() == 0) {
            return -1;
        }
        int pipe = inner.indexOf('|');
        String display = (pipe >= 0) ? inner.substring(pipe + 1) : inner;
        String tp = (pipe >= 0) ? inner.substring(0, pipe) : inner;
        int hash = tp.indexOf('#');
        String target = (hash >= 0) ? tp.substring(0, hash) : tp;
        flush(buf, style, out);
        out.addElement(new MdSpan(MdSpan.T_WIKILINK, display, target.trim(), style));
        return close + 2;
    }

    /**
     * [text](url). The label is scanned recursively into a scratch Vector so
     * markup inside it survives, and the resulting spans are then retagged as
     * links. Only text and link spans get retagged: an image or tag written
     * inside a label keeps its own kind so the viewer still renders it.
     */
    private static int mdLink(String s, int i, int end, int style, Vector out, StringBuffer buf) {
        int close = findBracketClose(s, i + 1, end);
        if (close < 0 || close + 1 >= end || s.charAt(close + 1) != '(') {
            return -1;
        }
        int p = findParenClose(s, close + 2, end);
        if (p < 0) {
            return -1;
        }
        String url = urlOf(s.substring(close + 2, p));
        flush(buf, style, out);
        Vector tmp = new Vector();
        scan(s, i + 1, close, style, tmp);
        if (tmp.size() == 0) {
            out.addElement(new MdSpan(MdSpan.T_LINK, "", url, style));
        } else {
            for (int k = 0; k < tmp.size(); k++) {
                MdSpan sp = (MdSpan) tmp.elementAt(k);
                if (sp.kind == MdSpan.T_TEXT || sp.kind == MdSpan.T_LINK) {
                    sp.kind = MdSpan.T_LINK;
                    sp.target = url;
                }
                out.addElement(sp);
            }
        }
        return p + 1;
    }

    /**
     * Inline ![[src]] and ![alt](src). The span text is what the viewer shows
     * when the image cannot be decoded or is remote, so it must never be
     * empty: the ![alt](src) form falls back to the file name when alt is
     * absent, and the ![[src]] form always uses the file name (any |size hint
     * after the pipe is dropped rather than shown).
     */
    private static int imageSpan(String s, int i, int end, int style, Vector out, StringBuffer buf) {
        if (i + 1 >= end || s.charAt(i + 1) != '[') {
            return -1;
        }
        if (i + 2 < end && s.charAt(i + 2) == '[') {
            int close = indexOfStr(s, "]]", i + 3, end);
            if (close < 0) {
                return -1;
            }
            String inner = s.substring(i + 3, close);
            int pipe = inner.indexOf('|');
            String src = ((pipe >= 0) ? inner.substring(0, pipe) : inner).trim();
            flush(buf, style, out);
            out.addElement(new MdSpan(MdSpan.T_IMAGE, fileName(src), src, style));
            return close + 2;
        }
        int close = findBracketClose(s, i + 2, end);
        if (close < 0 || close + 1 >= end || s.charAt(close + 1) != '(') {
            return -1;
        }
        int p = findParenClose(s, close + 2, end);
        if (p < 0) {
            return -1;
        }
        String alt = s.substring(i + 2, close);
        String src = urlOf(s.substring(close + 2, p));
        flush(buf, style, out);
        out.addElement(new MdSpan(MdSpan.T_IMAGE,
                (alt.length() > 0) ? alt : fileName(src), src, style));
        return p + 1;
    }

    /**
     * #tag. It must not follow an alphanumeric or another '#', which rules out
     * "C#" and any heading markup that reached the inline pass. It must also
     * contain a non-digit, so "#1" and "#2026" stay literal issue numbers and
     * years instead of becoming tags.
     */
    private static int tagSpan(String s, int i, int end, int style, Vector out, StringBuffer buf) {
        if (i > 0) {
            char pc = s.charAt(i - 1);
            if (isAlnum(pc) || pc == '#') {
                return -1;
            }
        }
        int j = i + 1;
        boolean nonDigit = false;
        while (j < end && isTagChar(s.charAt(j))) {
            if (!Character.isDigit(s.charAt(j))) {
                nonDigit = true;
            }
            j++;
        }
        if (j == i + 1 || !nonDigit) {
            return -1;
        }
        String tg = s.substring(i + 1, j);
        flush(buf, style, out);
        out.addElement(new MdSpan(MdSpan.T_TAG, "#" + tg, tg, style));
        return j;
    }

    /**
     * &lt;http://...&gt; autolink. Only http and https are recognized here;
     * Viewer can hand mailto:, tel: and sms: to platformRequest, but those are
     * never auto-detected - they have to be written as an explicit [](...).
     */
    private static int angleLink(String s, int i, int end, int style, Vector out, StringBuffer buf) {
        if (!hasAt(s, i + 1, end, "http://") && !hasAt(s, i + 1, end, "https://")) {
            return -1;
        }
        int j = i + 1;
        while (j < end && s.charAt(j) != '>') {
            if (isSpace(s.charAt(j))) {
                return -1;
            }
            j++;
        }
        if (j >= end) {
            return -1;
        }
        String url = s.substring(i + 1, j);
        flush(buf, style, out);
        out.addElement(new MdSpan(MdSpan.T_LINK, url, url, style));
        return j + 1;
    }

    /**
     * Bare http(s) URL. scan() dispatches here on the letter 'h' alone, so this
     * is entered at every 'h' in the note; the scheme test comes first
     * precisely so the common case bails after one character comparison.
     */
    private static int autoLink(String s, int i, int end, int style, Vector out, StringBuffer buf) {
        int scheme;
        if (hasAt(s, i, end, "http://")) {
            scheme = 7;
        } else if (hasAt(s, i, end, "https://")) {
            scheme = 8;
        } else {
            return -1;
        }
        if (i > 0 && isAlnum(s.charAt(i - 1))) {
            return -1;
        }
        int j = i;
        while (j < end) {
            char c = s.charAt(j);
            if (isSpace(c) || c == '<' || c == '>') {
                break;
            }
            j++;
        }
        // Trim trailing punctuation that almost certainly belongs to the
        // sentence rather than the URL ("see http://x/y."). A ')' is only
        // trimmed when the URL closes more parens than it opens, so
        // Wikipedia-style links such as http://x/Foo_(bar) keep theirs.
        int k = j;
        while (k > i + scheme) {
            char c = s.charAt(k - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!'
                    || c == '?' || c == '\'' || c == '"') {
                k--;
            } else if (c == ')') {
                int open = 0;
                int closep = 0;
                for (int m = i; m < k; m++) {
                    char pc = s.charAt(m);
                    if (pc == '(') {
                        open++;
                    } else if (pc == ')') {
                        closep++;
                    }
                }
                if (closep > open) {
                    k--;
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        if (k <= i + scheme) {
            return -1;
        }
        String url = s.substring(i, k);
        flush(buf, style, out);
        out.addElement(new MdSpan(MdSpan.T_LINK, url, url, style));
        return k;
    }

    /**
     * The matching ']' at depth 0, so nested link labels work. Escapes and code
     * spans are skipped because a bracket inside either is not structural.
     */
    private static int findBracketClose(String s, int from, int end) {
        int depth = 1;
        int j = from;
        while (j < end) {
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == '`') {
                int rl = runLen(s, j, end, '`');
                int cl = findCodeClose(s, j + rl, end, rl);
                j = (cl >= 0) ? (cl + rl) : (j + rl);
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return j;
                }
            }
            j++;
        }
        return -1;
    }

    /** The matching ')' at depth 0, so a URL may contain balanced parentheses. */
    private static int findParenClose(String s, int from, int end) {
        int depth = 1;
        int j = from;
        while (j < end) {
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return j;
                }
            }
            j++;
        }
        return -1;
    }

    /**
     * indexOf bounded on the right. CLDC's String only offers a start index, so
     * the first hit is found normally and rejected if it would cross end; that
     * is sound because any later hit would cross end too.
     */
    private static int indexOfStr(String s, String find, int from, int end) {
        int idx = s.indexOf(find, from);
        return (idx >= 0 && idx + find.length() <= end) ? idx : -1;
    }

    private static String fileName(String p) {
        int sl = p.lastIndexOf('/');
        String nm = (sl >= 0) ? p.substring(sl + 1) : p;
        return (nm.length() > 0) ? nm : p;
    }

    /** Literal match of w at i that must fit entirely inside the scan window. */
    private static boolean hasAt(String s, int i, int end, String w) {
        return i + w.length() <= end && s.startsWith(w, i);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * Everything above ASCII is accepted wholesale so non-Latin tags work:
     * CLDC 1.1's Character class has no isLetter() to ask instead.
     */
    private static boolean isTagChar(char c) {
        return isAlnum(c) || c == '-' || c == '_' || c == '/' || c > 127;
    }

    /**
     * The four ASCII punctuation ranges, i.e. every character from '!' to '~'
     * that is not alphanumeric. Only these may be backslash-escaped; a
     * backslash before anything else stays a literal backslash.
     */
    private static boolean isPunct(char c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@')
                || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }
}
