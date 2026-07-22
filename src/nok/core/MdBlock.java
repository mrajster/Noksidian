package nok.core;

/**
 * One block produced by Md.parse().
 *
 * <p>A paragraph, heading, list item, quote, code fence, image line, table row,
 * frontmatter chunk or callout - all of them this one type.
 *
 * <p>A single wide struct with public mutable fields rather than a class per
 * block kind. CLDC 1.1 has no enums and every extra class costs both JAR size
 * and a classload on a phone that boots the MIDlet in seconds, so the kind is
 * an int and the per-kind payload shares five fields. Which fields carry meaning
 * depends entirely on type; the rest keep their constructor defaults, and
 * readers of extra/num must null-check because "unused" here means null.
 *
 * <p>Md fills the fields after construction instead of through a wide
 * constructor - most block kinds set two of them, and Md.parse() is the only
 * writer in the tree. Blocks are parsed once per note and then read many times:
 * Viewer.setNote() stores the Vector and relayout() re-walks it whenever the
 * canvas size changes, so nothing downstream may mutate a block in place.
 */
public final class MdBlock {

    // Block kinds. PARA doubles as the fallback: Viewer.layoutBlock() switches on
    // type and its default case lays the block out as a paragraph, so an unknown
    // kind still renders its text instead of disappearing.
    public static final int PARA = 0;
    public static final int HEADING = 1;
    public static final int BULLET = 2;
    public static final int NUMBERED = 3;
    public static final int TASK = 4;
    public static final int QUOTE = 5;
    public static final int CODE = 6;
    public static final int HR = 7;
    public static final int IMAGE = 8;
    public static final int TABLE_ROW = 9;
    public static final int FRONTMATTER = 10;
    public static final int CALLOUT = 11;

    /** One of the type constants above. */
    public int type;
    // List depth is indent columns / 2 with a tab counted as 2 columns, so one tab
    // and two spaces are the same level. Quote depth counts '>' markers and is
    // therefore 1-based, unlike list depth - and a callout only continues while the
    // following quote lines carry the identical depth.
    /** HEADING: 1..6; BULLET/NUMBERED/TASK: indent depth 0..; QUOTE/CALLOUT: depth 1.. */
    public int level;
    /** TASK only. */
    public boolean checked;
    // The parser normalizes "3)" to "3." so the renderer has a single label form,
    // and keeps the author's number verbatim rather than renumbering the run -
    // a list that starts at 12 stays at 12.
    /** NUMBERED: display label like "3." else null. */
    public String num;
    /**
     * Inline-parseable text; CODE: full body (\n-joined); IMAGE: src;
     * TABLE_ROW: raw row; FRONTMATTER: raw yaml. Never null.
     *
     * <p>Empty string rather than null when absent, which spares every renderer a
     * null check on the hot layout path. Only PARA/HEADING/list/QUOTE/CALLOUT text
     * is handed to Md.inline() whole; CODE and FRONTMATTER are drawn verbatim, and
     * TABLE_ROW keeps its pipes because Viewer groups a whole run of rows, splits
     * the cells itself and runs inline() per cell.
     *
     * <p>CALLOUT packs its whole body in here: the title line first, then the
     * continuation quote lines joined with \n.
     */
    public String text;
    // Null for every other kind, so readers must guard. CODE and IMAGE always set
    // it: a fence with no language word, an indented code block and a wiki image
    // with no alt all yield "" rather than null.
    /** CODE: language or ""; IMAGE: alt; CALLOUT: callout type lowercase. */
    public String extra;

    /**
     * A block of the given kind with every payload field at its neutral default;
     * the caller sets whichever ones its kind actually uses. text is the one that
     * matters: it starts as "" rather than the JVM's null so a block Md never got
     * around to filling in is still safe to render.
     */
    public MdBlock(int type) {
        this.type = type;
        this.level = 0;
        this.checked = false;
        this.num = null;
        this.text = "";
        this.extra = null;
    }
}
