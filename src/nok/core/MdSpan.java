package nok.core;

/**
 * One inline span produced by Md.inline(). A span is either plain text,
 * a link, a wikilink, a tag or an inline image; style is a bitmask of B_*
 * flags that COMBINE for nested emphasis.
 *
 * <p>Deliberately a bare mutable record: public fields, no getters, no
 * equals/hashCode. A whole note can expand into hundreds of these on a ~2MB
 * heap, so accessor calls and defensive copies are overhead with nothing to
 * buy. Spans are consumed once per relayout, where Viewer turns them into
 * positioned draw items; paint() blits those and never touches spans again.
 *
 * <p>The fields are non-final because the parser rewrites spans in place:
 * Md.mdLink() scans the label of [text](url) into a scratch Vector and then
 * stamps kind/target onto the resulting spans - but only the T_TEXT and T_LINK
 * ones, so an image or tag written inside a label keeps its own kind. That is
 * how a link keeps its internal bold/italic runs without the span list ever
 * having to become a tree; the output stays a flat Vector in source order.
 *
 * <p>Spans are also hand-built outside the parser. Viewer repackages a
 * standalone image block as a synthetic T_IMAGE span so block and inline images
 * share one layout path, and substitutes a T_LINK for any http image, since the
 * inline decoder only reads local vault files - the URL is drawn as a tappable
 * link instead. Consumers must tolerate such spans, not just parser output.
 */
public final class MdSpan {

    // Span kinds, stored in `kind`. Only T_TEXT leaves `target` null; the other
    // four are all "activatable" and Viewer.activate() dispatches on kind (open
    // note / hand the URL to the phone browser / open image / search the tag).
    public static final int T_TEXT = 0;
    public static final int T_LINK = 1;
    public static final int T_WIKILINK = 2;
    public static final int T_TAG = 3;
    public static final int T_IMAGE = 4;

    // Style flags, OR-ed into `style`. Powers of two rather than separate
    // booleans so nesting is a plain bitwise-or as the parser recurses:
    // `**bold *it* bold**` yields an inner span with B_BOLD|B_ITALIC. B_CODE is
    // set on the whole span of a `...` run - its content is never inline-parsed,
    // but the flag still combines with emphasis carried in from outside.
    public static final int B_BOLD = 1;
    public static final int B_ITALIC = 2;
    public static final int B_CODE = 4;
    public static final int B_STRIKE = 8;
    public static final int B_HIGHLIGHT = 16;

    /** One of T_*. */
    public int kind;
    /** Display text; never null. */
    public String text;
    /** href / wikilink target / tag without # / image src; null for T_TEXT. */
    // For [[Note#Heading|Display]] this is just "Note": the parser drops the
    // |display half into `text` and cuts the #anchor, since nothing navigates
    // to a heading. NoteIndex.resolve() strips both again anyway, because it is
    // also reached from raw markdown links that never went through the parser.
    public String target;
    /** Bitmask of B_*. */
    public int style;

    public MdSpan(int kind, String text, String target, int style) {
        this.kind = kind;
        // Normalize null text once here so the layout pass can measure and
        // substring it without a null check at every step; a null reaching
        // Font.stringWidth() would abort the whole relayout, not just one span.
        this.text = (text == null) ? "" : text;
        this.target = target;
        this.style = style;
    }
}
