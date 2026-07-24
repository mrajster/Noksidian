import nok.core.Emoji;

/**
 * Desktop-JVM tests for nok.core.Emoji, the glyph-index loader and greedy raw
 * emoji matcher the Viewer drives during text layout.
 *
 * <p>Unlike the other core suites this one is not self-contained: it loads the
 * real committed res/emoji/index.bin off the classpath (test.sh runs with
 * -cp build/test:res so /emoji/index.bin resolves), so it doubles as an
 * end-to-end check that the generator (tools/gen-emoji.py) and the loader agree
 * on the byte layout. The concrete glyph ids are never hard-coded - every claim
 * is either a length, an alias equality between two spellings of one emoji, or
 * an inequality between two distinct emoji - so a regenerated pack that shuffles
 * ids keeps the suite green.
 *
 * <p>The matcher's contract is pure greedy longest RAW match with no
 * normalization: a fully-qualified sequence, its FE0F-stripped alias and its
 * bare single all resolve to the SAME glyph, a skin-tone sequence resolves to a
 * DIFFERENT glyph than its base, an RGI ZWJ cluster is consumed whole, and a
 * non-RGI ZWJ cluster decomposes into its component glyphs with the joiner
 * silently dropped. Everything the E71 font cannot draw and that is not part of
 * a matched glyph is classified INVISIBLE (consume, draw nothing), replicating
 * Ui.isUndrawable; text the font CAN draw (en dash, ASCII) returns 0.
 *
 * <p>No JUnit, no javax: a plain main() with a local check() that throws on the
 * first failure and an "ALL PASS n" line at the end, run under the vendored
 * JDK 8. All emoji are \\uXXXX escapes because the source stays ASCII-only.
 */
public class TestEmoji {

    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) {
            throw new RuntimeException(name);
        }
        passed++;
    }

    // Packed match() result is (unitsConsumed << 16) | glyphField; these mirror
    // the two halves the Viewer unpacks so the assertions read in the same terms
    // the contract is stated in.
    static int units(int r) {
        return (r >> 16) & 0xFFFF;
    }

    static int gid(int r) {
        return r & 0xFFFF;
    }

    public static void main(String[] args) {
        int before = passed;
        testBasic();
        System.out.println("Basic: " + (passed - before) + " checks OK");
        before = passed;
        testAliasQualification();
        System.out.println("AliasQualification: " + (passed - before) + " checks OK");
        before = passed;
        testKeycap();
        System.out.println("Keycap: " + (passed - before) + " checks OK");
        before = passed;
        testFlagAndSkin();
        System.out.println("FlagAndSkin: " + (passed - before) + " checks OK");
        before = passed;
        testZwj();
        System.out.println("Zwj: " + (passed - before) + " checks OK");
        before = passed;
        testInvisibleAndText();
        System.out.println("InvisibleAndText: " + (passed - before) + " checks OK");
        before = passed;
        testMaybe();
        System.out.println("Maybe: " + (passed - before) + " checks OK");
        before = passed;
        testGeometry();
        System.out.println("Geometry: " + (passed - before) + " checks OK");
        System.out.println("ALL PASS " + passed);
    }

    // ==================================================================
    // basic single-emoji match
    // ==================================================================

    /**
     * The simplest case the Viewer relies on: a 2-unit surrogate-pair emoji
     * (U+1F600) resolves to one glyph whose id is a real slot in the pack, not
     * the INVISIBLE sentinel. Nothing here pins the id, only that it is a usable
     * glyph, so the check survives a pack regeneration.
     */
    static void testBasic() {
        int r = Emoji.match("\uD83D\uDE00", 0); // U+1F600 grinning face
        check(units(r) == 2, "grin units");
        check(gid(r) != Emoji.INVISIBLE, "grin not invisible");
        check(gid(r) >= 0 && gid(r) < Emoji.glyphCount(), "grin gid in range");
        check(r != 0, "grin nonzero");
    }

    // ==================================================================
    // qualification aliasing (bare / fully-qualified same glyph)
    // ==================================================================

    /**
     * The whole reason the generator indexes minimally-qualified and unqualified
     * variants as aliases: the matcher does no FE0F normalization, so bare
     * U+2600 (unqualified) and U+2600 U+FE0F (fully-qualified) must be two keys
     * pointing at one glyph. The bare form consumes 1 unit, the qualified form 2,
     * and both land on the same id - that equality is the contract, the id
     * itself is not.
     */
    static void testAliasQualification() {
        int bare = Emoji.match("\u2600", 0);       // U+2600 sun, unqualified
        int qual = Emoji.match("\u2600\uFE0F", 0); // U+2600 U+FE0F, fully-qualified
        check(units(bare) == 1, "sun bare units");
        check(units(qual) == 2, "sun qual units");
        check(gid(bare) != Emoji.INVISIBLE && gid(qual) != Emoji.INVISIBLE,
              "sun not invisible");
        check(gid(bare) == gid(qual), "sun alias same glyph");
    }

    // ==================================================================
    // keycap sequences + bare bases
    // ==================================================================

    /**
     * Keycaps are the one place a plain ASCII char (#, *, digit) starts an emoji,
     * and only when a combining member follows: "#" U+FE0F U+20E3 (3 units) and
     * its alias "#" U+20E3 (2 units) are the same glyph, while a lone "#" or "5"
     * is just text (0). This is also what forces maybe()'s ASCII-lookahead arm to
     * exist - without it the gate would never let the matcher see these.
     */
    static void testKeycap() {
        int full = Emoji.match("#\uFE0F\u20E3", 0); // # FE0F 20E3
        int alias = Emoji.match("#\u20E3", 0);      // # 20E3
        check(units(full) == 3, "keycap full units");
        check(units(alias) == 2, "keycap alias units");
        check(gid(full) != Emoji.INVISIBLE, "keycap not invisible");
        check(gid(full) == gid(alias), "keycap alias same glyph");
        // Bare base characters are ordinary text, not emoji.
        check(Emoji.match("#", 0) == 0, "bare hash is text");
        check(Emoji.match("5", 0) == 0, "bare digit is text");
        // The maybe() gate: only a base+member pair opens the door.
        check(!Emoji.maybe("#x", 0), "maybe #x false");
        check(Emoji.maybe("#\u20E3", 0), "maybe #keycap true");
        check(!Emoji.maybe("a", 0), "maybe a false");
    }

    // ==================================================================
    // flags (regional indicators) + skin-tone modifiers
    // ==================================================================

    /**
     * Two multi-unit shapes the greedy longest rule has to get right. A flag is
     * a pair of regional-indicator surrogate pairs (4 units) that must match as
     * one unit, not as two half-flags. A skin-tone modifier appended to a base
     * (thumbs-up + medium, 4 units) is a DISTINCT glyph from the bare base, so
     * the matcher must prefer the longer key and the two ids must differ - the
     * proof that longest-match is real and not just base+dropped-modifier.
     */
    static void testFlagAndSkin() {
        // Flag SI: D83C DDF8 D83C DDEE (two regional indicators).
        int si = Emoji.match("\uD83C\uDDF8\uD83C\uDDEE", 0);
        check(units(si) == 4, "flag units");
        check(gid(si) != Emoji.INVISIBLE, "flag not invisible");

        // Thumbs-up + medium skin tone: D83D DC4D D83C DFFD.
        int skin = Emoji.match("\uD83D\uDC4D\uD83C\uDFFD", 0);
        int base = Emoji.match("\uD83D\uDC4D", 0); // bare thumbs
        check(units(skin) == 4, "skin units");
        check(units(base) == 2, "thumbs base units");
        check(gid(skin) != Emoji.INVISIBLE && gid(base) != Emoji.INVISIBLE,
              "thumbs not invisible");
        check(gid(skin) != gid(base), "skin differs from base");
    }

    // ==================================================================
    // ZWJ clusters: RGI whole vs non-RGI component fallback
    // ==================================================================

    /**
     * The two ends of the ZWJ contract. An RGI family (man-woman-girl, 8 units
     * with two joiners) is a single indexed key and must be consumed whole in one
     * match. A non-RGI cluster (grinning ZWJ grinning) is NOT indexed, so the
     * matcher decomposes it: the grinning component matches, the bare joiner in
     * the middle classifies INVISIBLE (1 unit, drawn as nothing), and the second
     * grinning matches again - the sanctioned graceful degradation for clusters
     * the pack never rendered.
     */
    static void testZwj() {
        // RGI family: D83D DC68 200D D83D DC69 200D D83D DC67.
        int fam = Emoji.match(
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", 0);
        check(units(fam) == 8, "family whole units");
        check(gid(fam) != Emoji.INVISIBLE, "family not invisible");

        // Non-RGI: grinning ZWJ grinning (D83D DE00 200D D83D DE00).
        String non = "\uD83D\uDE00\u200D\uD83D\uDE00";
        int grin = Emoji.match("\uD83D\uDE00", 0);
        int first = Emoji.match(non, 0);
        check(units(first) == 2 && gid(first) == gid(grin), "nonrgi first grin");
        int joiner = Emoji.match(non, 2); // the ZWJ at index 2
        check(units(joiner) == 1 && gid(joiner) == Emoji.INVISIBLE,
              "nonrgi joiner invisible");
        int second = Emoji.match(non, 3); // grinning again at index 3
        check(units(second) == 2 && gid(second) == gid(grin), "nonrgi second grin");
    }

    // ==================================================================
    // INVISIBLE classification vs drawable text
    // ==================================================================

    /**
     * The 0-vs-INVISIBLE decision for anything the matcher did not turn into a
     * glyph, replicating Ui.isUndrawable so the emoji path strips exactly what
     * the plain-text path already stripped. A stray variation selector, a bare
     * ZWJ, a well-formed but non-emoji astral pair, and a lone/malformed
     * surrogate half all classify INVISIBLE (consume 2 for the pair, 1 for the
     * rest); an en dash and plain ASCII draw fine on the E71 and must return 0.
     */
    static void testInvisibleAndText() {
        int fe0f = Emoji.match("\uFE0F", 0); // lone variation selector-16
        check(units(fe0f) == 1 && gid(fe0f) == Emoji.INVISIBLE, "lone fe0f invisible");

        int zwj = Emoji.match("\u200D", 0); // lone ZWJ
        check(units(zwj) == 1 && gid(zwj) == Emoji.INVISIBLE, "lone zwj invisible");

        int keycapMark = Emoji.match("\u20E3", 0); // lone combining keycap
        check(units(keycapMark) == 1 && gid(keycapMark) == Emoji.INVISIBLE,
              "lone keycap mark invisible");

        // Well-formed non-emoji astral pair (U+10000 Linear B): unknown astral is
        // stripped whole, 2 units.
        int astral = Emoji.match("\uD800\uDC00", 0);
        check(units(astral) == 2 && gid(astral) == Emoji.INVISIBLE, "astral pair invisible");

        // Lone high surrogate (no low following) and lone low surrogate: 1 unit each.
        int loneHigh = Emoji.match("\uD83D", 0);
        check(units(loneHigh) == 1 && gid(loneHigh) == Emoji.INVISIBLE, "lone high invisible");
        int highThenText = Emoji.match("\uD83Dx", 0); // high then non-low char
        check(units(highThenText) == 1 && gid(highThenText) == Emoji.INVISIBLE,
              "high-then-text invisible");
        int loneLow = Emoji.match("\uDC00", 0);
        check(units(loneLow) == 1 && gid(loneLow) == Emoji.INVISIBLE, "lone low invisible");

        // A drawable-punctuation char must NOT strip: en dash and ellipsis render
        // fine on the E71 font and are ordinary text.
        int endash = Emoji.match("\u2013", 0); // en dash
        check(endash == 0, "en dash is text");
        int ellipsis = Emoji.match("\u2026", 0); // horizontal ellipsis
        check(ellipsis == 0, "ellipsis is text");
        check(Emoji.match("a", 0) == 0, "ascii a is text");
        check(Emoji.match("hello", 2) == 0, "ascii mid is text");
    }

    // ==================================================================
    // maybe() cheap gate
    // ==================================================================

    /**
     * maybe() is the allocation-free, no-load pre-filter the Viewer runs on every
     * character before paying for match(). It is deliberately an over-estimate:
     * true for every code unit >= 0x2000 (all symbol/surrogate ranges, some of
     * which match() then rejects with 0), plus the keycap-base lookahead. It must
     * never miss a real emoji start, and must stay false for pure ASCII text so
     * the common path is a single compare.
     */
    static void testMaybe() {
        check(Emoji.maybe("\uD83D\uDE00", 0), "maybe surrogate true");
        check(Emoji.maybe("\u2600", 0), "maybe 2600 true");
        check(Emoji.maybe("\u2013", 0), "maybe endash true (overestimate)");
        check(Emoji.maybe("#\uFE0F", 0), "maybe hash+fe0f true");
        check(Emoji.maybe("*\u20E3", 0), "maybe star+keycap true");
        check(Emoji.maybe("9\uFE0F", 0), "maybe digit+fe0f true");
        check(!Emoji.maybe("#", 0), "maybe bare hash false (no lookahead)");
        check(!Emoji.maybe("#x", 0), "maybe hash+other false");
        check(!Emoji.maybe("A", 0), "maybe ascii false");
        check(!Emoji.maybe("hello world", 3), "maybe mid-ascii false");
    }

    // ==================================================================
    // glyph geometry accessors
    // ==================================================================

    /**
     * The page/slot arithmetic the Viewer uses to find a glyph inside its strip
     * PNG. pageOf/slotOf must be the exact inverse of the row-major id layout
     * (id == page*perPage + slot) at the page boundaries (0, perPage-1, perPage)
     * and at the last real glyph, and every glyph must land on a page inside
     * pageCount(). The header constants are pinned to the generator's values.
     */
    static void testGeometry() {
        check(Emoji.glyphPx() == 16, "glyphPx 16");
        check(Emoji.perPage() == 32, "perPage 32");
        check(Emoji.pageCount() == 124, "pageCount 124");
        check(Emoji.glyphCount() == 3944, "glyphCount 3944");

        int per = Emoji.perPage();
        int last = Emoji.glyphCount() - 1;
        int[] ids = { 0, per - 1, per, last };
        for (int k = 0; k < ids.length; k++) {
            int g = ids[k];
            check(Emoji.pageOf(g) * per + Emoji.slotOf(g) == g, "page/slot inverse " + g);
            check(Emoji.slotOf(g) >= 0 && Emoji.slotOf(g) < per, "slot in range " + g);
            check(Emoji.pageOf(g) >= 0 && Emoji.pageOf(g) < Emoji.pageCount(),
                  "page in range " + g);
        }
        check(Emoji.pageOf(0) == 0 && Emoji.slotOf(0) == 0, "glyph 0 at 0,0");
        check(Emoji.pageOf(per) == 1 && Emoji.slotOf(per) == 0, "glyph per at 1,0");
        check(Emoji.pageOf(per - 1) == 0 && Emoji.slotOf(per - 1) == per - 1,
              "glyph per-1 at 0,per-1");
    }
}
