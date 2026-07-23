# Noksidian emulator bug ledger

Status: open → fixing → fixed → verified (or invalid).
Evidence screenshots live in tools/emu/shots/.

| ID | Area | Status | Description | Evidence |
|----|------|--------|-------------|----------|
| B1 | viewer/md | VERIFIED | Space before styled inline span dropped mid-line: "your **vault home**" renders "yourvault home"; "a ==320x240" renders "a320x240" | 09,11 |
| B2 | viewer | VERIFIED | Frontmatter properties panel clips long value ("Welcome to Noksidia") at right edge, no ellipsis/wrap | 01 |
| B3 | viewer | VERIFIED | Partial next-line pixels painted at body bottom edge (purple slivers above soft bar) — verify intended | 01,06 |
| B4 | files/viewer | VERIFIED | Opening "Formatting Showcase.md" (1778 bytes on disk) from Library shows "This note is empty"; files.exists() also fails on it at boot. Space in filename breaks JSR-75 file URL (unescaped). Agent: fix-b4-fileurl | 22,23,24 |
| B5 | viewer | VERIFIED | Body content drawn via scaledRun() (Viewer.java:918-1074) renders at ~56% brightness: text #7C7C7D vs Theme.text #DCDDDE, H1 #4E338B vs accent #8B5CF6, highlight box dimmed too. Chrome drawn directly is correct. Agent: fix-b1-space | 09,10,11 |
| B6 | ui-chrome | VERIFIED | UiMenu (and likely UiDialog) backdrop is solid Theme.bg fill instead of the contract's dimmed back-screen frame (UiMenu.java paint comment admits the shortcut). Agent: fix-chrome | 04,07 |
| B7 | ui-chrome | VERIFIED | Primary-action soft-key label drawn in Theme.accent instead of contract's Theme.dimText, app-wide (UiScreen soft bar). Agent: fix-chrome | 01,12 |
| B8 | editor | VERIFIED | Editor body text flush against left screen edge (zero left padding). Agent: fix-chrome | 12 |
| B9 | editor | verified-ok | Caret IS drawn (thin vertical bar visible in 265 mid-word; earlier miss was blink phase). NOT A BUG | 265 |
| B11 | library | VERIFIED | New note name ending with '.' creates "name..md" (double dot); validName accepts trailing dots | 265 |

| B10 | vaultpicker | VERIFIED | VaultPicker lists folders in raw FS order (Notes, Projects, Daily, attachments) while Library sorts them — inconsistent | s03 |
| B12 | theme/viewer | VERIFIED | Light theme: inline-code chip bg (181,181,183) vs callout bg (181,177,190) — invisible; `code` in callouts reads as plain text with phantom gap. Agent: fix-b1 | 211 |
| B13 | ui-chrome | VERIFIED | UiDialog backdrop still solid fill — apply same dimSnapshot as UiMenu. Agent: fix-chrome | 215 |
| B14 | editor | VERIFIED | Editor menu missing contract item "Word wrap? (n/a)" (has only Save/Cancel/Go to top). Agent: fix-chrome | 267 |
| B15 | viewer | VERIFIED | ==highlight== and aliased wikilinks spanning spaces render per-word chips/underlines with gaps instead of one continuous span. Agent: fix-b1 | 228 |
| B16 | viewer | VERIFIED | Links list shows raw "[[Welcome]]" brackets, no wikilink styling. Agent: fix-b1 | 212 |
| B17 | ui-chrome | VERIFIED | Library menu 10 items, 8 visible, NO overflow indication (no scrollbar/chevron/fade). Agent: fix-chrome | 220,221 |
| B18 | library | VERIFIED | Unselected folder glyphs dark gray; contract says filled square Theme.wikilink. Agent: fix-chrome | 227,230 |
| B19 | ui-chrome | VERIFIED | Horizontally-scrolled input draws clipped glyph fragment against field left border. Agent: fix-chrome | 263 |
| B20 | ui-chrome | VERIFIED | UiInput draws no prompt line (Library passes ""); contract requires prompt line + field. Agent: fix-chrome | 229 |
| B21 | viewer/md | INVALID | NOT a bug. Soft-break test note ("Alpha ends here\nBeta...") renders "Alpha ends here Beta..." with correct space (probe psb01). Shot 268's merge was because the emulator ate Enter so the saved file had NO newline — faithful render of a 1-line file. | psb01 |
| B22 | imageview | VERIFIED | ImageView uses a native Command("Back") + is a raw Canvas (not UiScreen); F2 did not exit (298==297). Should handle -6/-7/FIRE keys directly + themed soft bar like the rest of the app. Agent: fix-imageview | 297,298 |
| EDIT-ENTER | editor | SUPERSEDED | Old claim ("MicroEmulator delivers NO select event") is WRONG: instrumenting UiScreen.keyPressed shows `xdotool key Return` arrives as ONE keyPressed(-5) (ME maps VK_ENTER->SELECT, device XML gives SELECT -5). Delivery is intermittent - dropped after a burst of arrow keys - so a single missing newline in a tour is a harness artifact, not an app bug. Editor Enter (one newline + list continuation + bare-marker removal) verified by screenshot 2026-07-23. | h03,h05,i01 |
| ENTER-DOUBLE | editor | FIXED | Reported on the real E71: one Enter inserted TWO newlines. Cause: S60 hands one confirm press to the Canvas twice (Enter code AND the selection key). Fix: shared static filter UiScreen.confirmAccepted, consulted by every screen incl. the raw Canvases (UiMenu/UiDialog/Viewer/ImageView). NOT reproducible in the emulator (one event per press there) - needs on-device confirmation. | n/a |
| SHIFT-GRID | editor | FIXED | Real-E71 report (v1.2.0): SHIFT opened the symbol grid. Cause: E71 QWERTY delivers Shift as the S60 Edit key (-50), which fell into UiEditor.onKeyOther's any-unclaimed-key trigger — so every capital letter popped the grid. Fix: exclude system keys -10/-11/-26/-36/-37/-50; key triggers are LONG-PRESS SPACE (keyRepeated; residue space deleted; emulator-verified, jar 1.2.2) and legacy code 9 (E61-gen Ctrl+I / emulator Tab). Deep-research verdict: on the E71 Ctrl is not a physical key (OS Fn+Chr plane) and the Symbian FEP consumes it before Java — no chord can arrive; Ctrl+I device-tested dead on 510.21.009. Menu > Insert symbol is the guaranteed path. | lp02 |

Verified FIXED: B4 — "Formatting Showcase.md" (281), "Tables and Tasks.md" (288), Notes/"Wikilinks Demo.md" (292) all open with full content. Spaced filenames work.
Verified PASS: B5 dark theme true-black + bright text (device LCD tint corrected to ffffff); soft-break render correct; caret present (blink); nav soft keys correct/dimText on non-Viewer screens.

Status updates: B1 FIXED+verified (spaces correct in 223/228). B6 FIXED+verified for UiMenu (dimmed snapshot pixel-confirmed); dialogs pending (B13). B7 fixed for UiScreen screens; Viewer's own soft bar still accent — reassigned to fix-b1. B5: app-side createRGBImage double-dim fixed; remaining uniform 75.3% display tint = emulator LCD filter from device.xml background c0c0c0 (harness fix: set ffffff, rebuild device jar).

Notes: B2/B3 CONFIRMED by pixel measurement (batch-1 analysis). "~75% global dim of the whole MIDlet display" reported as likely emulator LCD-pipeline artifact — treat as capture calibration, not an app bug. Viewer having no title bar treated as design intent (fullscreen canvas).

## Final verification (independent pixel pass over rA/rB/rC/pdc, jar 187970)
FIXED (pixel-confirmed): B3, B5, B6, B7, B8, B11, B12, B14, B15, B16, B17, B18, B19, B20;
  + typed-text fidelity, save round-trip, live Light<->Dark theme switch, Settings values readable.
B13: PARTIAL — dialog dims over Settings (rC03) but NOT over the raw-Canvas Viewer (rA07 solid white).
  Root cause: Viewer passes back=null to UiDialog (needs Displayable back). Reassigned to fix-chrome.
B2: fixed in source (Ui.clip on frontmatter rows, Viewer.java:395) — capturing Welcome.md to confirm (reg-D).
B4/B22/B10: verified separately (B4 shots 281/288/292; B22 pie01/pie02; B10 source sort()).

Remaining COSMETIC (non-blocking, logged not fixed):
- Selected list rows use lavender-fill + dark text + accent bar, not the contract's inverted focus fill.
- Dark callout inline-code chip bg only ~8/channel from callout bg (legible via glyphs; distinct on body).
- Callout type titles all single-accent (no per-type color/icon) — consistent with single-accent design.
- Editor body left margin (~6px) vs viewer body (~14px): ~8px jump on save->view.
- Dialog OK on LEFT soft key vs input OK on RIGHT — S60-defensible but inconsistent.
- Code-chip left padding reads as a double space before `code` in a callout.
Not re-exercised post-fix (low risk, unchanged by fixes): password/Test/Change-vault Settings rows,
  Rename/Today flows, VaultPicker themed browser, embedded ![[image]] in-viewer, long input hscroll.
