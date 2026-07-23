# Noksidian — Custom Canvas UI Toolkit (whole-app theming)

Goal: EVERY screen is a `Canvas` we draw ourselves, so `nok.ui.Theme` (dark/light) colors the
entire app — background, text, lists, menus, dialogs, text inputs and the editor — not just the
note viewer. No native `List`/`Form`/`TextBox`/`Alert` anywhere in the app flow.

All CONTRACTS.md language rules still apply (Java 1.3 / CLDC 1.1 / MIDP 2.0, ASCII source, no
generics/StringBuilder/regex, Vector/Hashtable, methods < ~150 lines). New classes live in
`nok.ui`. They MAY import `javax.microedition.lcdui.*`. Theme statics are read at paint time.

## Soft keys & keys (S60 / Nokia convention, also configured in the emulator device)

Canvas `keyPressed(int)` receives:
- Left soft key  = -6, Right soft key = -7, Select/middle = -5  (Nokia S60).
- Also accept getGameAction(): UP/DOWN/LEFT/RIGHT/FIRE for the d-pad, and Canvas.FIRE.
- Printable text: any keyCode in [32,126] (and >=160) is treated as a typed character
  (E71 has a full QWERTY; MicroEmulator delivers the char code). keyCode 8 = backspace,
  10/13 = enter, keyCode -8 (Nokia clear) = backspace too.
- Number keys KEY_NUM0..9 available for shortcuts.
Helper (in Ui): `Ui.isChar(int k)` -> boolean; `Ui.LSK=-6, RSK=-7, MSK=-5`.
Every Screen must work if -6/-7 are unavailable by ALSO accepting FIRE for the right action and a
long-press fallback is NOT required.

## nok.ui.Ui  (shared helpers + constants)

```java
public final class Ui {
    public static final int LSK = -6, RSK = -7, MSK = -5;
    public static boolean isChar(int keyCode);          // printable text key
    public static char toChar(int keyCode);             // keyCode -> char (assumes isChar)
    // word-wrap: returns Vector of String lines fitting width w using font f (breaks on spaces,
    // hard-breaks over-long words; preserves explicit '\n').
    public static Vector wrap(String text, Font f, int w);
    public static void drawScrollbar(Graphics g, int x, int y, int h, int total, int shown, int off);
    public static String clip(String s, Font f, int w);  // truncate with ".." to fit width
}
```

## nok.ui.UiScreen  (abstract base — title bar + body + soft-key bar)

```java
public abstract class UiScreen extends Canvas {
    protected final NoksidianMIDlet m;
    protected String title;
    protected String leftLabel, rightLabel;    // soft-key captions (null hides)
    public UiScreen(NoksidianMIDlet m, String title);
    // Layout: paints Theme.bg fill, a title bar (Theme.codeBg strip, bold title, Theme.text),
    //   the body via paintBody, and a soft-key bar at the bottom (labels left/right, Theme.dimText).
    protected abstract void paintBody(Graphics g, int cx, int cy, int cw, int ch); // content rect
    protected void repaintBody();               // convenience -> repaint()
    protected int bodyX(); protected int bodyY(); protected int bodyW(); protected int bodyH();
    // key handling -> override these (defaults empty):
    protected void onLeftSoft();                // default: nothing
    protected void onRightSoft();               // default: nothing
    protected void onSelect();                  // FIRE / MSK
    protected void onUp(); protected void onDown(); protected void onLeftArrow(); protected void onRightArrow();
    protected void onChar(char c);              // typed printable
    protected void onBackspace();
    protected void onKeyOther(int keyCode);
    // final keyPressed() dispatches to the above; subclasses normally don't override keyPressed.
}
```
Title bar height = bold small font height + 4; soft-key bar height = small font height + 4.
Body rect is the space between them. Right label is right-aligned, left label left-aligned.

Duplicate-confirm filter: one physical confirm press can reach a Canvas as TWO keyPressed events on
S60 (the QWERTY Enter arrives as 10/13 AND as MSK), which inserted two newlines in the editor.
`static boolean UiScreen.confirmAccepted(int keyCode)` is the shared guard. A twin must be all
three of: the PARTNER code of the press just accepted (the confirm codes -5 / 10 / 13 pair with
each other), within 150ms, and the FIRST such event - one press owes at most one partner, so it is
spent when it fires. Otherwise a lone d-pad centre press shortly after an Enter, or any confirm
that arrived on a third code (a number key the raw canvases read as FIRE), would be eaten. The
SAME code within 60ms is also dropped (a bounce); two real taps of one key are never swallowed.
A backwards wall-clock step is treated as "long ago" so it cannot wedge the filter, and only
accepted presses advance the window. The state is static
because the twin arrives on whatever screen the first event opened. UiScreen routes MSK, 10/13 and
FIRE through it before calling onSelect(); every raw-Canvas confirm path (UiMenu, UiDialog,
UiSymbols, Viewer, ImageView) MUST consult the same filter.

## nok.ui.UiList  (themed scrollable, selectable list — replaces List)

```java
public final class UiList extends UiScreen {
    public UiList(NoksidianMIDlet m, String title, UiListOwner owner);
    public void setItems(Vector items);         // items: String labels
    public void setItems(Vector labels, Vector marks); // marks: Integer MARK_* per row (icons)
    public int selectedIndex();
    public void setSelected(int i);
    public void setCommands(String left, String right); // soft-key captions
    public void setMenu(String[] menuItems);    // left-soft opens a UiMenu of these; owner.listMenu(cmd)
}
public interface UiListOwner {
    void listSelect(int index);                 // FIRE / right-soft default open
    void listMenu(String command);              // a menu item chosen (or a direct command)
    void listBack();                            // clear/back (right-soft when set to Back)
}
```
Row: MARK_NONE=0, MARK_FOLDER=1, MARK_NOTE=2, MARK_IMAGE=3, MARK_UP=4 (draw a small themed glyph
left of the label; folder = filled square Theme.wikilink, note = doc glyph, image = image glyph,
up = up-arrow). Selected row: filled Theme.focus background, Theme.bg text. Others Theme.text on
Theme.bg. Scroll to keep selection visible; draw Ui scrollbar when overflowing. UP/DOWN move
selection (wrap), FIRE/right-soft -> owner.listSelect, left-soft -> menu (if set) else nothing.

## nok.ui.UiMenu  (themed popup command menu — replaces the platform Options menu)

```java
public final class UiMenu {
    public UiMenu(NoksidianMIDlet m, UiScreen back, String[] items, UiMenuOwner owner);
    public void show();                         // setCurrent(this menu screen)
}
public interface UiMenuOwner { void menuSelect(String item, int index); }
```
Draws `back`'s current frame dimmed, then a bottom-anchored themed panel listing items (selected row
Theme.focus). UP/DOWN select, FIRE/left-soft choose -> owner.menuSelect + return to `back`;
right-soft = Cancel -> return to `back`.

## nok.ui.UiDialog  (themed modal — replaces Alert / confirmations)

```java
public final class UiDialog {
    public static final int OK = 0, YES_NO = 1, OK_CANCEL = 2;
    public UiDialog(NoksidianMIDlet m, UiScreen back, String title, String message,
                    int kind, UiDialogOwner owner);
    public void show();
}
public interface UiDialogOwner { void dialogResult(boolean positive); } // positive=OK/Yes
```
Centered themed box (Theme.calloutBg bg, Theme.focus border, wrapped message Theme.text). OK ->
positive true. YES_NO: left-soft "Yes"(true)/right-soft "No"(false). OK_CANCEL similar. FIRE = the
positive action. Returns to `back` (or owner navigates). A plain informational alert uses OK and an
owner that just returns; provide `UiDialog.info(m, back, title, msg)` static helper that shows an
OK dialog which simply re-shows `back`.

## nok.ui.UiInput  (themed single-line input — replaces name/search/token/password prompts)

```java
public final class UiInput extends UiScreen {
    public UiInput(NoksidianMIDlet m, String title, String prompt, String initial,
                   boolean masked, UiInputOwner owner);
    public String value();
}
public interface UiInputOwner {
    void inputOk(String value);                 // right-soft "OK" / FIRE
    void inputCancel();                         // left-soft "Cancel"
}
```
Draws prompt line + a bordered text field (Theme.codeBg bg) with the current value and a blinking
cursor (caret between chars). masked -> render each char as '*' but keep real value. Typed chars
insert at caret; backspace deletes; LEFT/RIGHT move caret; horizontal scroll when value wider than
field. Right-soft/FIRE -> inputOk(value); left-soft -> inputCancel.

## nok.ui.UiEditor  (themed multi-line note editor — replaces the native TextBox editor)

```java
public final class UiEditor extends UiScreen {
    public UiEditor(NoksidianMIDlet m, String rel, String content);
    public String text();
}
```
Full-screen themed editor:
- Model: a StringBuffer of the whole text + an int caret index. (Keep it simple + correct; edits
  are O(n) buffer ops — fine for phone-sized notes.)
- Layout: word-wrap the text to body width with the monospace-or-proportional body font at
  Theme.bodySize; compute lines + caret's (line,col) each repaint (cache until text changes).
- Draw: Theme.bg, Theme.text, a caret (thin Theme.focus vertical bar) at the caret position,
  blinking via a TimerTask toggling every ~500ms (repaint). Current line may be highlighted faintly.
- Keys: printable -> insert at caret; backspace (8 / -8) -> delete before caret; enter (10/13) ->
  one newline + list marker (below); LEFT/RIGHT -> move caret by one; UP/DOWN -> move caret to the
  same column on the prev/next visual line (or scroll); scroll to keep caret visible; a scrollbar
  when overflowing.
- Soft keys: left = "Menu" -> UiMenu {"Save","Cancel","Insert symbol","Word wrap? (n/a)","Go to top"}
  ; right = "Save". Save -> m.saveEdited(rel, text()); Cancel -> confirm-if-modified via UiDialog
  then m.back().
- Enter (onSelect, exactly once per press thanks to UiScreen.confirmAccepted): insert ONE '\n'
  followed by `MdList.nextPrefixAt(current line, caret column)` — the line's list/quote marker
  continued (`- `, `* `, `+ `, a task gives `- [ ] `, `1. ` gives `2. `, `> ` gives `> `), with
  indent and quote run preserved. The caret must be PAST the marker for it to be continued, or the
  text moving to the new line would already carry one and it would double. If the caret is at the
  end of a line that is a bare marker (`MdList.isBare`) the marker is DELETED instead and the list
  ends. Inside a ``` / ~~~ fenced code block neither rule applies (markers there are literal text
  the viewer prints verbatim): Enter is a plain newline. The inserted marker is ordinary text —
  Clear rubs it out.
- Any device key the dispatcher could not place (onKeyOther with keyCode < 0) opens UiSymbols,
  EXCEPT the system keys: -10/-11 Send/End, -50 S60 Edit — the E71 QWERTY delivers SHIFT as Edit
  before every capital letter (real-device finding, v1.2.0) — plus -26/-36/-37 (Sony Ericsson
  camera/volume, excluded defensively). The WORKING key gesture is LONG-PRESS SPACE: UiEditor
  overrides keyRepeated, and a held space deletes the space its own keyPressed typed and opens
  the grid (holding space previously did nothing, so the gesture costs no existing behavior).
  Key code 9 is also accepted for the E61-generation Ctrl+I chord and the emulator's Tab key.
  On the real E71 nothing else is reachable: Ctrl is not a physical key (it is the OS-level
  Fn+Chr plane) and Symbian's FEP consumes it before Java — device-tested v1.2.1, research-
  confirmed. The Menu item "Insert symbol" remains the guaranteed trigger. UiEditor implements UiSymbolOwner: symbolPicked(c)
  inserts c at the caret exactly like a typed character.
- Guard huge notes: if content length > 200000, show a UiDialog "note too large to edit" and call
  m.openNote(rel) instead (m/Library checks before constructing too).
- No native TextBox anywhere.

## nok.ui.UiSymbols  (themed special-character picker — popup grid)

```java
public final class UiSymbols {
    public static final String KEY = "editor.symfreq";   // Config key holding the usage counters
    public UiSymbols(NoksidianMIDlet m, Displayable back, UiSymbolOwner owner);
    public void show();
}
public interface UiSymbolOwner { void symbolPicked(char c); }
```
Same popup idiom as UiMenu: draws `back`'s current frame dimmed, then a bottom-anchored themed panel
— here a grid of ~70 characters the E71 thumbboard cannot type (markdown punctuation, then ordinary
punctuation, typography, currency, maths, arrows). All non-ASCII entries are \uXXXX escapes, and
nothing is taken from a range the E71 system font draws as a box (no emoji/dingbats). D-pad moves
the selected cell (selected cell = Theme.focus fill); soft keys are left "Insert" ->
owner.symbolPicked(c) + return to `back`, right "Cancel" (also Clear 8 / -8) -> return to `back`.
MSK / 10 / 13 / FIRE also insert, and those DO go through UiScreen.confirmAccepted; the soft key
itself must not, because a soft key never arrives as the twin of a confirm press and filtering it
would only arm the window against the user's next Enter. No cancel callback exists.
Order is most-used-first: every pick bumps that character's counter, persisted in Config under
`editor.symfreq` as `<char><count>` tokens each terminated by U+0001 (zero counters omitted, counts
capped at 9999, unknown characters ignored on read), and the grid is a stable insertion sort by
counter descending, so untouched characters keep the built-in order.

## Screen conversions (rewrite these; keep the SAME public constructors so MIDlet is unchanged)

- `nok.ui.Library`  : now `extends UiScreen implements UiListOwner, UiMenuOwner` (or wraps a UiList).
  Same constructor `Library(NoksidianMIDlet m, String dirRel)`, `refresh()`, `setStatus(String)`.
  Rows: ".."(MARK_UP unless root), folders (MARK_FOLDER), files md/img/txt (or all if ui.showall).
  Hides `_vault.nkv`. Left-soft "Menu" -> UiMenu {New note, New folder, Rename, Delete, Search,
  Today, Sync now, Settings, About, Exit}; right-soft "Open" -> open selection. Delete -> UiDialog
  YES_NO. New note/folder/Rename/Search -> UiInput. Status line shown under the title (ticker area).
  Type-ahead: onChar appends to a live search string and jumps the selection to match(), which tries
  prefix, then word-start (after ' ', '-' or '_'), then substring, case-insensitively, over the row
  labels ("..'" skipped, a folder's trailing '/' ignored). A character that matches nothing is
  retried as a fresh one-character search and otherwise dropped. The search is painted as a small
  "find: <q>" pill over the bottom-right of the list; onBackspace shortens it; it expires 2000ms
  after the last keystroke and is cleared by any deliberate action (UP/DOWN, select, either soft
  key).
- `nok.ui.VaultPicker` : `extends UiScreen`, same constructor. Themed dir browser (folders only),
  left-soft Menu {Use this folder, New folder, Up, Cancel}, right-soft Open.
- `nok.ui.Settings` : `extends UiScreen`, same constructor. A themed vertical FORM: rows are
  fields — text fields (open a UiInput on select), choice rows (FIRE cycles the value or opens a
  small UiMenu), and action rows (Set/Change/Decrypt password, Test, Change vault, Save). UP/DOWN
  move the focused row; FIRE activates it. Persist all Config keys exactly as before on Save.
  Encryption command rows call CryptoSetup.* as before. Theme choice cycling updates a preview live
  (call Theme.load() after Save; the Settings screen itself uses Theme so Dark shows immediately on
  next repaint after a change if you Theme.load() on cycle).
- `nok.ui.Editor` : REPLACED by delegating to `UiEditor` — keep constructor
  `Editor(NoksidianMIDlet m, String rel, String content)` but make it `extends UiEditor` (call
  super) OR have MIDlet construct `UiEditor` directly (update MIDlet.editNote). Prefer: MIDlet uses
  UiEditor; keep Editor.java as a thin subclass for contract stability.
- `nok.ui.Viewer` / `nok.ui.ImageView` : already themed Canvases — additionally make Viewer's
  "Links" list use `UiList` (was a native List) and any Alerts use `UiDialog`. Keep them working.

## NoksidianMIDlet glue changes

- Replace every `Alert`/`TextBox`/`List` usage with `UiDialog`/`UiInput`/`UiList`:
  `alertErr(title,msg)` -> UiDialog.info; welcome/offerSettings/confirm flows -> UiDialog;
  search prompt + results -> UiInput + UiList; unlock password prompt -> UiInput(masked=true);
  remember-password + wrong-password + create-note-from-wikilink confirms -> UiDialog.
- Add `public void saveEdited(String rel, String text)` used by UiEditor: writeText, sync.noteSaved,
  rebuildIndex, openNote(rel). (Keep existing editNote flow; it now shows UiEditor.)
- `back()` unchanged (returns to Library at last dir).
- No behavioral changes to sync/crypto/parse — UI layer only.

## Emulator device (tools/emu/device-e71/nok/device/e71.xml)

Add keyboard key codes so the Canvas receives S60 soft keys and select:
- softbutton SOFT1 -> also emits key code -6 ; SOFT2 -> -7 ; SELECT button -> -5.
- Ensure LEFT/RIGHT/UP/DOWN/SELECT game buttons deliver getGameAction properly (they do by name).
- Rebuild device-e71.jar. (On a real E71 these codes are native.)

## Acceptance

- `./build.sh` clean (preverifies). `./test.sh` still green (core untouched).
- In the emulator with Theme=Dark: Library, its Options menu, Settings, a New-note name prompt,
  a delete confirm, the note editor, and search are ALL black background / light text. With
  Theme=Light they are white/black. Switching the Theme in Settings and returning repaints in the
  new theme. Screenshot each to verify.
