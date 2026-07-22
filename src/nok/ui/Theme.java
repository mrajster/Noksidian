package nok.ui;

import javax.microedition.lcdui.Font;

import nok.sys.Config;

/**
 * Canvas color theme (CONTRACTS-FEATURES.md section 1), Obsidian palette.
 *
 * Dark mode is TRUE BLACK (0x000000) by preference, with Obsidian's purple
 * accent and muted greys layered just above black for panels/code. Light
 * mode mirrors Obsidian's light theme. Statics are (re)filled by load()
 * from Config "ui.theme" / "ui.font"; call after any Settings change.
 * Native lcdui screens are gone, so every app pixel honours this class.
 *
 * <p>The dark palette below deliberately departs from the one written down in
 * CONTRACTS-FEATURES.md section 1 (bg 0x121212, text 0xE6E6E6, ...): true
 * black was asked for instead of the contract's charcoal, and the greys were
 * re-picked around it. The constants in this file, not the contract, are the
 * source of truth for dark mode.
 *
 * <p>The contract also fixes the shape - bare public statics plus a load(),
 * no instance and no getters. The cost is global mutable state: load()
 * rewrites every field in place, so call it from the UI thread and repaint
 * afterwards. The callers are NoksidianMIDlet.startApp and three paths in
 * Settings (theme live-preview, Back revert, and Save). Nothing under
 * nok.sync touches this class.
 */
public final class Theme {

    // Obsidian brand purple ramp (accent, lighter hover, dark tint).
    // Only these two survive as constants; the "dark tint" end of the ramp is
    // mode-specific (tagBg / calloutBg / selBg) and is inlined per branch below.
    private static final int OBS_ACCENT = 0x8B5CF6;
    private static final int OBS_ACCENT_HI = 0xA78BFA;

    /**
     * True when "ui.theme" is dark. Part of the contract's public surface and
     * set by load(), though nothing outside this class currently reads it -
     * screens consume the resolved colors instead of branching on the mode.
     */
    public static boolean dark;
    // Palette. All 0xRRGGBB, which is exactly what Graphics.setColor consumes:
    // it ignores any alpha byte, so anything that looks translucent in this
    // design (tag pills, callout panels, selection) is a pre-blended opaque
    // color, not a real composite.
    public static int bg, bg2, text, dimText, faint, link, wikilink, tagText,
            tagBg, codeBg, codeText, quoteBar, quoteText, calloutBg,
            calloutTitle, highlightBg, highlightText, hr, focus, accent,
            accentHi, selBg, selText, titleBar, softBar, scrollbar,
            placeholderBg, placeholderText;
    // Body font, described three ways because MIDP names only three sizes and
    // that is not enough granularity for a readable "larger text" setting on a
    // 320x240 screen. Chrome (Library, UiList, UiEditor, VaultPicker) takes
    // bodySize, the largest native size not above the request, and draws with
    // it directly; Viewer instead draws at bodyBase and integer-upscales by
    // bodyFactor, which is how sizes above the largest native font exist at
    // all. Invariant restored by loadFont(): bodyPx == heightOf(bodyBase) *
    // bodyFactor, with bodyFactor >= 1.
    /** Native SIZE_* nearest to (not exceeding) bodyPx; used by chrome. */
    public static int bodySize = Font.SIZE_SMALL;
    /** Exact reading size in pixels (the height note text renders at). */
    public static int bodyPx;
    /** Native SIZE_* constant used as the integer-scaling base. */
    public static int bodyBase = Font.SIZE_MEDIUM;
    /** Integer upscale factor (>= 1); bodyPx == heightOf(bodyBase)*this. */
    public static int bodyFactor = 1;

    private Theme() {
    }

    /** The exact note-reading text height in pixels. */
    public static int scaledHeight() {
        return bodyPx;
    }

    /**
     * Pixel height of a native SIZE_* face. MIDP never states what the three
     * named sizes actually measure - it is a handset decision - so they have to
     * be probed at runtime. FACE_PROPORTIONAL/STYLE_PLAIN must match the face
     * body text is really drawn with, or every size computed from this is off.
     */
    private static int heightOf(int size) {
        return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                size).getHeight();
    }

    /**
     * Fills bodyPx / bodySize / bodyBase / bodyFactor from Config "ui.font".
     * The stored value is a pixel-height integer string (e.g. "16", "36");
     * legacy word values small/medium/large map to their measured native
     * heights. bodyBase/bodyFactor pick the native font and integer factor
     * whose product is exactly bodyPx (largest base wins, factor 1 for the
     * native sizes); anything unmatchable falls back to the nearest native.
     */
    private static void loadFont() {
        int smallH = heightOf(Font.SIZE_SMALL);
        int mediumH = heightOf(Font.SIZE_MEDIUM);
        int largeH = heightOf(Font.SIZE_LARGE);
        String f = Config.get("ui.font", "small");
        int px = -1;
        if ("small".equals(f)) {
            px = smallH;
        } else if ("medium".equals(f)) {
            px = mediumH;
        } else if ("large".equals(f)) {
            px = largeH;
        } else {
            try {
                px = Integer.parseInt(f.trim());
            } catch (Exception e) {
                px = -1;
            }
        }
        // Garbage in the config (hand-edited file, truncated write) must not be
        // able to produce a zero or negative text height and wedge the layout.
        if (px < 1) {
            px = mediumH;
        }
        bodyPx = px;
        // Largest native size that does not overshoot the request (below the
        // smallest native height there is nothing smaller to pick, so SMALL is
        // used anyway). Chrome uses this directly and never upscales, so it
        // errs small on purpose: a row of clipped labels is worse than
        // slightly-small ones.
        if (px >= largeH) {
            bodySize = Font.SIZE_LARGE;
        } else if (px >= mediumH) {
            bodySize = Font.SIZE_MEDIUM;
        } else {
            bodySize = Font.SIZE_SMALL;
        }
        // Factor-major search, so factor 1 is always tried first: an exact
        // native height must resolve to the native font, never to an upscale of
        // a smaller one (2x of SMALL is blocky where MEDIUM is crisp). Within
        // one factor the bases run large -> small, which only matters on
        // handsets that map two of the three named sizes onto the same pixel
        // height; there the larger name wins, and since both render identically
        // the choice is cosmetic.
        // The 4 bound is not arbitrary - Settings only ever writes native
        // heights or LARGE x2/x3/x4, so nothing beyond 4 is reachable from UI.
        boolean found = false;
        for (int fac = 1; fac <= 4 && !found; fac++) {
            if (px == largeH * fac) {
                bodyBase = Font.SIZE_LARGE;
                bodyFactor = fac;
                found = true;
            } else if (px == mediumH * fac) {
                bodyBase = Font.SIZE_MEDIUM;
                bodyFactor = fac;
                found = true;
            } else if (px == smallH * fac) {
                bodyBase = Font.SIZE_SMALL;
                bodyFactor = fac;
                found = true;
            }
        }
        // Unreachable from the UI, but a config carried over from a handset
        // with different font metrics can land on a px no base*factor hits.
        // Snapping bodyPx back onto the chosen native height (rather than
        // keeping the requested value) keeps the
        // bodyPx == heightOf(bodyBase) * bodyFactor invariant true, so bodyPx
        // stays an honest report of what Viewer will actually draw at instead
        // of a size nothing renders.
        if (!found) {
            bodyBase = bodySize;
            bodyFactor = 1;
            bodyPx = heightOf(bodySize);
        }
    }

    /**
     * Re-reads the whole palette and font sizing from Config. Idempotent, and
     * cheap enough that Settings re-runs the lot on a theme keypress rather
     * than diffing keys. Callers own the repaint: this touches statics only
     * and knows nothing about live screens, so a screen already on the display
     * keeps its old pixels until someone repaints it.
     */
    public static void load() {
        dark = "dark".equals(Config.get("ui.theme", "light"));
        loadFont();
        accent = OBS_ACCENT;
        accentHi = OBS_ACCENT_HI;
        if (dark) {
            bg = 0x000000;            // true black, by request
            bg2 = 0x0E0E10;           // panels / cards just above black
            text = 0xDCDDDE;          // Obsidian text-normal
            dimText = 0x8E8E93;       // text-muted
            faint = 0x5A5A5F;         // text-faint
            link = 0x7EA6FF;          // external link (soft blue)
            wikilink = OBS_ACCENT;    // internal link = accent
            tagText = 0xB9A7F7;
            tagBg = 0x1C1530;         // dark purple pill
            codeBg = 0x141417;
            codeText = 0xD6D3E0;
            quoteBar = OBS_ACCENT;
            quoteText = 0xA6A6AD;
            calloutBg = 0x14101F;
            calloutTitle = OBS_ACCENT_HI;
            highlightBg = 0x4A3A00;
            highlightText = 0xFFE58A;
            hr = 0x26262B;
            focus = OBS_ACCENT;
            selBg = 0x1B1330;         // selected row: subtle accent tint
            selText = 0xFFFFFF;
            titleBar = 0x000000;
            softBar = 0x08080A;
            scrollbar = 0x3A3A40;
            placeholderBg = 0x141417;
            placeholderText = 0x77777C;
        } else {
            bg = 0xFFFFFF;
            bg2 = 0xF6F6F8;
            text = 0x1F1F22;          // Obsidian text-normal (light)
            dimText = 0x6B6B70;
            faint = 0x9A9AA0;
            link = 0x2E5CE6;
            wikilink = 0x6D28D9;
            tagText = 0x6D28D9;
            tagBg = 0xEDE7FC;
            codeBg = 0xD9D9E1;        // grey chip that reads on BOTH white bg and light calloutBg (0xF1ECFD)
            codeText = 0x2A2A2E;
            quoteBar = 0x8B5CF6;
            quoteText = 0x5A5A60;
            calloutBg = 0xF1ECFD;
            calloutTitle = 0x5B21B6;
            highlightBg = 0xFFF176;
            highlightText = 0x1A1A00;
            hr = 0xE2E2E6;
            focus = 0x7C3AED;
            selBg = 0xEDE7FC;
            selText = 0x1F1F22;
            titleBar = 0xFFFFFF;
            softBar = 0xF6F6F8;
            scrollbar = 0xBFBFC6;
            placeholderBg = 0xF1F1F4;
            placeholderText = 0x8A8A90;
        }
    }

    // Class-init load, so the statics are a usable palette from the instant
    // Theme is first touched. Without it every color would read 0 - black text
    // on a black page - for any code that paints before a load() has run. In
    // the normal boot this fires on the Theme.load() call in
    // NoksidianMIDlet.startApp, i.e. already after Config.load(), which makes
    // that explicit call redundant; the block is the safety net for any other
    // entry order, where it falls back to Config's built-in defaults.
    static {
        load();
    }
}
