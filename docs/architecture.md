# Noksidian Architecture

This is the developer document. It assumes you are fluent in Java but have never shipped
J2ME code. It explains what the Nokia E71 does to your Java, how the codebase is layered so
that most of it never touches the phone APIs, how a note gets from the microSD card to
pixels, how a sync pass works end to end, what `build.sh` actually does, and how to extend
the app without breaking any of it.

Authoritative signatures for every class live in [`CONTRACTS.md`](../CONTRACTS.md) at the
repo root. If this document and CONTRACTS.md ever disagree, CONTRACTS.md wins.

---

## 1. The target, and what it does to your Java

### 1.1 Hardware and platform

| | |
|---|---|
| Device | Nokia E71 (Symbian S60 3rd Edition FP1, 2008) |
| Java stack | **MIDP 2.0** profile on **CLDC 1.1** configuration, plus **JSR-75** (FileConnection) |
| Screen | 320x240 landscape |
| Input | QWERTY keyboard, D-pad, two softkeys. No touch. |
| Heap | A few MB of Java heap, total |
| Storage | Phone memory `C:/`, microSD at `E:/` |
| TLS | **TLS 1.0 only** — cannot talk to `api.github.com` directly (see `tools/ghproxy.py`) |

CLDC 1.1 is not "old Java SE". It is a separate, tiny platform: a Java-1.3-era language
level, a small fixed class library, a different bytecode verifier, and no way to load
anything the phone's VM doesn't already know about.

### 1.2 Language level: Java 1.3 source, class file version 47

The E71's VM rejects class files newer than **major version 47** (the `-target 1.3`
format). That single fact drives the whole compiler setup:

- `build.sh` compiles with `-source 1.3 -target 1.3`. `-source 1.3` rejects newer *syntax*
  at compile time: `assert` (1.4), generics/enums/varargs/autoboxing/for-each/annotations/
  static imports (5), String switch / try-with-resources / diamond (7), lambdas (8).
- Even where newer syntax could theoretically be desugared, the *library types* it relies
  on (`java.lang.Iterable`, `StringBuilder`, boxing caches, `java.lang.annotation.*`) do
  not exist on CLDC 1.1. So the restriction is real at both the syntax and the API level.
- We pin **JDK 8** in `tools/jdk8` because it is the last JDK whose `javac` still accepts
  `-source 1.3 -target 1.3` (JDK 9 dropped everything below 1.6). `-Xlint:-options`
  silences the "1.3 is obsolete" warning.

The second enforcement mechanism is the **bootclasspath**: `build.sh` replaces the JDK's
`rt.jar` with the actual CLDC 1.1 + MIDP 2.0 + JSR-75 API jars. If you call
`String.split()` (a Java 1.4 *library* addition that `-source 1.3` alone would not catch),
the device build fails to compile instead of crashing on the phone with
`NoSuchMethodError`.

### 1.3 CLDC preverification

Desktop JVMs verify bytecode with an expensive dataflow analysis at class-load time. CLDC
VMs don't have the RAM or CPU for that, so the spec moves the work to build time:
a **preverifier** analyzes each method and embeds a `StackMap` attribute (the J2ME
ancestor of Java 6's `StackMapTable`) into the class file. The phone then runs a cheap
linear one-pass check against it. A class file **without** StackMap attributes is
rejected by the phone at install or load time — compiling with `-target 1.3` is necessary
but not sufficient.

We preverify with **ProGuard's `-microedition` mode** (stage 2 of `build.sh`) instead of
Sun's original `preverify` binary, which is a 32-bit executable from WTK 2.5.2 that won't
run on a modern 64-bit Linux without multilib contortions.

Two related rules from CONTRACTS.md exist because of old verifiers:

- **Keep every method under ~150 lines.** Huge methods produce huge StackMap frames and
  some device verifiers choke on them.
- **Source files are ASCII-only** (avoids any encoding ambiguity across the toolchain).
  Package root is `src/`.

### 1.4 The API cheat sheet

This is the full forbidden/allowed list from CONTRACTS.md. Violations break the build (the
bootclasspath catches API misuse; `-source 1.3` catches syntax).

**Forbidden — does not exist on this platform / language level:**

| Forbidden | Use instead |
|---|---|
| Generics, for-each, autoboxing, varargs, enums, annotations, static imports, `assert`, try-with-resources, diamond, lambdas, String switch | Plain Java 1.3 syntax |
| `StringBuilder` | `StringBuffer` |
| `java.util.List` / `ArrayList` / `HashMap` / `Map` / `Set` / `Iterator` / `Collections` / `Arrays` | `Vector` (`elementAt` / `addElement` / `insertElementAt` / `removeElementAt` / `setElementAt` / `size` / `isEmpty` / `contains` / `indexOf`), `Hashtable` (`get` / `put` / `remove` / `keys` / `containsKey` / `size`), `Stack`, `Enumeration` |
| `String.split` / `replaceAll` / `matches` / `replace(String,String)` / `format`, all regex | Hand-rolled `charAt` loops |
| `Character.isWhitespace` | `c==' ' \|\| c=='\t' \|\| c=='\n' \|\| c=='\r'` |
| `Integer.valueOf(int)` | `new Integer(i)` |
| `java.text` (no `SimpleDateFormat`) | Format dates by hand with `java.util.Calendar` |
| Exception chaining (`new IOException(msg, cause)`) | Append `cause.toString()` to the message |

**Available — safe to use:**

- `String`: `replace(char,char)`, `indexOf`, `lastIndexOf`, `substring`, `trim`,
  `startsWith`, `endsWith`, `toLowerCase`, `toUpperCase`, `charAt`, `equalsIgnoreCase`,
  `compareTo`, `getBytes("UTF-8")`, `new String(bytes, off, len, "UTF-8")`.
- `Character.isDigit`. `Integer.parseInt`, `Long.parseLong`, `Double.parseDouble`,
  `Integer.toString(i)`, `Long.toString(l)`.
- `System.arraycopy` (yes, it exists in CLDC), `Math.min/max/abs`.
- String concatenation with `+` (javac 1.3 emits `StringBuffer` code for it).
- Inner classes and anonymous classes (Java 1.1 features — fine).
- `java.util`: `Vector`, `Stack`, `Hashtable`, `Enumeration`, `Date`, `Calendar`,
  `TimeZone`, `Random`, `Timer`, `TimerTask`.
- `java.io`: `InputStream`, `OutputStream`, `DataInputStream`, `DataOutputStream`,
  `ByteArrayInputStream`, `ByteArrayOutputStream`, `Reader`, `Writer`,
  `InputStreamReader`, `OutputStreamWriter`, `IOException`.
- MIDP 2.0: `javax.microedition.lcdui.*`, `javax.microedition.rms.*`,
  `javax.microedition.midlet.*`, `javax.microedition.io.*` (`HttpConnection`,
  `Connector`). JSR-75: `javax.microedition.io.file.*`.

### 1.5 Memory and size budgets

A few MB of heap means every unbounded input needs a guard. These are contractual, not
optional:

| Guard | Where | Value |
|---|---|---|
| 3-way merge falls back (no diff, `fellBack=true`) | `Merge.merge3` | any input > 1500 lines or > 150 000 chars |
| Remote blobs skipped entirely | `Sync` pass step 2 | > 1 500 000 bytes |
| Image decode skipped (placeholder shown) | `Viewer` | file > 400 000 bytes |
| Editor capacity | `UiEditor` | `MAX_LEN = 200000` chars; oversized notes open read-only |
| HTTP body read | `Http` | streamed in 4 KB chunks, never one giant allocation |
| Emoji index resident memory | `Emoji.ensureLoaded` | ~86 KB of primitive arrays, loaded once lazily, never freed |
| Emoji page cache eviction (LRU) | `Viewer` `pageCache` | capped at 8 pages, ~256 KB decoded |

The `Viewer` image cache (`Hashtable` src → `Image`) is scoped to the current note only
and dropped when you navigate away.

---

## 2. Module map

### 2.1 Dependency direction

```
            nok.NoksidianMIDlet          (composition root, owns lifecycle)
                    |
                 nok.ui                  (javax.microedition.lcdui — ALL screens)
                /       \
          nok.sync       |               (engine + GitHub client; NO lcdui)
           /     \       |
      nok.sys     \      |               (javax.microedition.io / .file / .rms)
           \       \     |
            +---- nok.core              (NO javax.* AT ALL — pure Java 1.3)
```

Rules, in order of importance:

1. **`nok.core` imports no `javax.*` whatsoever.** Only the CLDC subsets of `java.lang`,
   `java.util`, `java.io`. This is what makes the parser, merge, JSON, and path logic
   unit-testable on a desktop JVM (`test.sh`). If a change to `nok.core` needs a phone
   API, the change is wrong — move the phone-facing half up a layer.
2. **`nok.sys` is the only place that touches JSR-75, RMS and `HttpConnection`.** It knows
   nothing about markdown, GitHub, or screens.
3. **`nok.sync` depends downward only** (on `core` and `sys`). It never imports `lcdui`;
   it talks upward exclusively through the `SyncListener` interface, which the MIDlet
   implements.
4. **`nok.ui` owns `lcdui`** and may use everything below it.
5. **Ownership:** the MIDlet owns the single `Files`, `Sync`, and `NoteIndex` instances
   (public fields, created in `initVault()`). `Config` is static and owned by `nok.sys`;
   `GitHub` re-reads `Config` on every call and caches nothing. `Sync` owns exactly one
   worker thread and one `java.util.Timer` — all file + network IO for syncing happens on
   that worker, never on the LCDUI event thread.

### 2.2 `nok.core` — pure logic, desktop-tested

**`Base64`** — RFC 4648 encoder/decoder. The decoder skips `\r \n space \t` anywhere and
tolerates missing padding, because the GitHub blob API returns base64 with embedded
newlines. Static-only, no state.

**`Utf8`** — the single byte<->`String` codec for note content, crypto and transport, used
instead of `getBytes("UTF-8")` / `new String(b,"UTF-8")` everywhere: note read/write, the
sync merge and `state.json` paths, the RMS path cache, both key-derivation inputs, and the
GitHub HTTP/JSON transport. Byte-identical to the J2SE codec for well-formed input, but also
tolerant of CESU-8 (some Symbian CLDC VMs encode astral characters — including emoji — as two
3-byte surrogate runs instead of one 4-byte sequence) and never throws on malformed bytes
(substitutes `U+FFFD` and resyncs). It exists because the platform codec cannot be trusted
for astral text on either the note-content or the GitHub-filename path.

**`Json`** — recursive-descent JSON parser and writer over a dynamic representation:
`Hashtable` (object), `Vector` (array), `String`, `Long`, `Double`, `Boolean`, and the
`Json.NULL` sentinel (needed because `Hashtable` cannot hold `null`). Handles nesting,
`\uXXXX` escapes, exponent numbers (integral numbers without `.`/`e`/`E` become `Long`).
Typed helpers (`obj`, `arr`, `str`, `num`, `bool`) return the caller's default instead of
throwing when a key is absent or the wrong type, which keeps `GitHub` response handling
short. There are no generics, so everything is `Object` and you cast — the helpers exist
to centralize those casts.

**`Md`** — the markdown parser, and the heart of the app. Two entry points:
`parse(String) -> Vector of MdBlock` (block structure) and
`inline(String) -> Vector of MdSpan` (styled runs within a block's text). Implements the
Obsidian flavor: frontmatter, ATX headings, fenced + indented code, HR, bullets, tasks,
numbered lists, quotes and `> [!note]` callouts, table rows, image lines, `%% %%`
comments; inline bold/italic/code/strike/`==highlight==` with nesting (style bitmasks
combine), escapes, `[text](url)`, `![alt](src)`, `[[wikilinks]]` with `|alias` and
`#heading`, `#tags`, and autolinks. All hand-rolled character scanning — no regex exists
on this platform. `Md` does **no layout**: it never measures text or touches fonts; that
is `Viewer`'s job.

**`MdBlock`** — dumb struct for one block: `type` (PARA, HEADING, BULLET, NUMBERED, TASK,
QUOTE, CODE, HR, IMAGE, TABLE_ROW, FRONTMATTER, CALLOUT), `level` (heading level / list
indent / quote depth), `checked` (tasks), `num` (numbered-list label), `text`, `extra`
(code language / image alt / callout type).

**`MdSpan`** — dumb struct for one inline run: `kind` (T_TEXT, T_LINK, T_WIKILINK, T_TAG,
T_IMAGE), display `text` (never null), `target` (href / wikilink target / tag name / image
src; null for plain text), and `style` — a bitmask of B_BOLD, B_ITALIC, B_CODE, B_STRIKE,
B_HIGHLIGHT.

**`Merge`** — line-based 3-way merge (`merge3(base, local, remote, strategy)`). Splits all
three inputs into lines, computes LCS alignments (dynamic programming) base↔local and
base↔remote, then walks the chunks: a change on one side is taken; identical changes are
taken once; different changes to the same region are a conflict resolved per strategy —
`KEEP_BOTH` emits git-style `<<<<<<< phone` / `=======` / `>>>>>>> github` markers,
`PREFER_LOCAL` / `PREFER_REMOTE` pick a side (the conflict flag is still set either way).
Inputs over 1500 lines / 150 000 chars skip the O(n·m) diff entirely and fall back to
whole-file pick with `fellBack=true` — the DP table would blow the heap. Trivial
shortcuts: `local.equals(remote)` → no conflict; `base.equals(local)` → take remote;
`base.equals(remote)` → take local.

**`MergeResult`** — struct: merged `text`, `conflict` flag, `fellBack` flag.

**`Path`** — string-only path helpers for vault-relative `/`-separated paths: `join`,
`parent`, `name`, `baseName`, `ext` (lowercase, no dot), `isMarkdown` (md/markdown),
`isImage` (png/jpg/jpeg/gif/bmp), `normalize` (strip leading/trailing `/`, collapse `//`,
`\` → `/`), and `urlEncodePath` (percent-encodes the UTF-8 bytes of each segment for
GitHub URLs, keeping `/` and unreserved chars, space as `%20`). No `java.io.File` here —
that class doesn't even exist on CLDC.

**`NoteIndex`** — the wikilink resolver. Holds a sorted list of all vault file paths
(fed by `Files.scanAll` via `NoksidianMIDlet.rebuildIndex()`); `resolve(wikiTarget)`
strips `#heading` / `|alias`, then tries case-insensitively: exact relpath, relpath +
`.md`, then basename match (`Foo` matches `dir/Foo.md`), preferring the shortest path on
ties. Returns `null` when nothing matches — which is how the Viewer knows to offer
"create this note".

**`Emoji`** — loads the committed glyph pack (`res/emoji/index.bin`, generated offline by
`tools/gen-emoji.py` from Google's Noto Emoji font — see section 6) once, lazily, and does
greedy longest-match over raw UTF-16 code units to find inline emoji: `maybe()` is a cheap,
allocation-free gate the Viewer runs on every character; `match()` returns a packed
(units consumed, glyph id) result, or `INVISIBLE` for zero-width joiners, variation
selectors and unknown sequences that should be silently dropped. There is no Unicode
normalization at runtime — every alias (fully-qualified, unqualified, skin-tone, ZWJ) is
pre-baked into the index by the generator, so matching is two binary searches with zero
per-call allocation. A missing or corrupt index leaves the class in a permanent no-emoji
mode (`match()` returns 0 for everything) rather than crashing the paint loop.

### 2.3 `nok.sys` — the device layer

**`Files`** — the JSR-75 wrapper. Constructed with the vault URL
(e.g. `file:///E:/Notes/`, trailing `/` required); all methods take vault-relative paths.
`read` / `write` (auto-creates parent dirs, truncates via `truncate(0)` after
`create()`), `exists` / `isDir` / `delete` / `mkdirs` / `list` (sorted dirs-first, dirs
suffixed `/`) / `modified` / `size` / `rename`, plus `scanAll` — the recursive file
enumeration that skips `.noksidian/` and any name starting with `.`. Static
`listRoots()` wraps `FileSystemRegistry` for the vault picker. Every `FileConnection` is
closed in `finally`; Symbian leaks handles otherwise and eventually refuses to open
files. File URLs are built by percent-encoding only space (`%20`) and `%` (`%25`) per
segment.

**`Config`** — static RMS-backed key-value store in RecordStore `"nokcfg"`, serialized as
one record: `writeInt(count)` then `writeUTF(key)`/`writeUTF(value)` pairs. `load()` is
idempotent and never throws (a corrupt store just means defaults); `set()` persists
immediately. Known keys and defaults: `vault` (file URL with trailing `/`), `gh.owner`,
`gh.repo`, `gh.branch` (`"main"`), `gh.token`, `gh.api` (`"https://api.github.com"`),
`sync.auto` (`"1"`), `sync.interval` (minutes, `"15"`), `sync.strategy`
(`"both"|"local"|"remote"`, default `"both"`). There is no schema — defaults live at the
call site.

**`Http`** — one static method,
`request(method, url, headers, body) -> HttpResp`. Opens via
`Connector.open(url, READ_WRITE, true)` cast to `HttpConnection` (the same call handles
`https://` — on the E71 that means TLS 1.0, hence the bridge). Always sends
`User-Agent: Noksidian/1.0` and `Connection: close`; sets `Content-Length` and writes the
body when present; reads the full response (via `getLength()` or to EOF in 4 KB chunks);
captures `etag`, `location`, `content-type`, `x-ratelimit-remaining` response headers;
follows up to 3 redirects (301/302/303/307) **for GET only**; closes connection and
streams in `finally`.

**`HttpResp`** — struct: `code`, `body` (never null), `headers` (lowercase-keyed
`Hashtable`), and `bodyText()` decoding the body as UTF-8.

### 2.4 `nok.sync` — GitHub client and engine

**`GhEntry`** — struct for one remote blob: `path`, `sha`, `size`.

**`GitHub`** — GitHub REST v3 client. Deliberately stateless: it re-reads `Config` on
every call so a settings change applies to the very next request without any re-wiring.
`listTree()` = `GET /repos/{o}/{r}/git/trees/{branch}?recursive=1`, keeping only
`"type":"blob"` entries; a `"truncated":true` response throws `IOException("repo too
large")`, a 404 throws `"repo or branch not found"`. `getBlob(sha)` fetches and
base64-decodes blob content. `putFile` = `PUT /repos/{o}/{r}/contents/{urlEncodedPath}`
with `{"message","content"(b64),"branch"}` plus `"sha"` when updating (omitted =
create); returns the new blob sha from the response; 409/422 →
`IOException("conflict: <path>")`. `deleteFile` = `DELETE .../contents/{path}` with
`{"message","sha","branch"}`. Every call sends `Authorization: token <gh.token>` and
`Accept: application/vnd.github+json`; non-2xx responses surface as
`IOException("HTTP <code> <message>")` with the parsed JSON `"message"` when available.
`test()` returns `null` on success or a human-readable error (used by the Settings
"Test" command). `configured()` = owner+repo+token all non-empty.

**`SyncListener`** — the upward-facing interface: `syncStatus(String)` (short progress
line, may arrive on any thread) and `syncDone(boolean ok, String summary)`. The MIDlet
implements it and forwards to the Library's ticker/title. This interface is the *only*
coupling from sync to UI.

**`Sync`** — the engine. Owns one worker thread and one `java.util.Timer`. `start()`
schedules the periodic sync (per `sync.interval`) plus an initial pass 5 s after launch
when `sync.auto=1`; `requestSync(reason)` sets a coalescing `pending` flag and wakes the
worker (multiple requests during a running pass collapse into exactly one follow-up
pass); `noteSaved(rel)` debounces — a pass fires ~20 s after the *last* save. Failures
abort the pass, report `syncDone(false, msg)`, and schedule retries with backoff
60 s → 2 m → 5 m → 15 m (reset on success; `requestSync` wakes a backoff wait early).
The full pass algorithm is in section 3.2. All text is UTF-8.

### 2.5 `nok.ui` — the UI layer: a custom Canvas toolkit

Every screen in Noksidian is a `Canvas` the app paints itself — there is no native `List`,
`Form`, `TextBox` or `Alert` anywhere in the app flow (only `javax.microedition.lcdui.Canvas`
/ `Font` / `Graphics` / `Image`, which the whole layer is allowed to import). The reason is
theming: MIDP's native widgets have no color/theme API at all — a `List` or `Form` is drawn
by the platform's own S60 skin, and nothing in `javax.microedition.lcdui` lets an app
recolor it. Wanting one real dark theme (true black) and one light theme across the *whole*
app, not just the note view, means owning every pixel. That is what `nok.ui` is: a small,
shared toolkit of themed screen types, plus `Theme`, the palette they all read from. The
authoritative spec is [`CONTRACTS-UI.md`](../CONTRACTS-UI.md).

**`Theme`** — static color and font tokens, reloaded by `load()` from `Config` keys
`ui.theme` (`"light"|"dark"`) and `ui.font` (see below). Every other `nok.ui` class reads
`Theme.*` fields at paint time and never hardcodes a color, so a Settings change applies on
the very next repaint. Dark is true black (`bg=0x000000`) with an Obsidian-purple accent
(`0x8B5CF6`) and near-black panel/code tones layered just above it; Light mirrors Obsidian's
light palette. Tokens cover background, two text weights, links/wikilinks/tags, code and
quote/callout colors, highlight, hairlines, the focus/accent color, selection, the title
and soft-key bars, and the scrollbar — everything any screen needs, so no class improvises
its own palette.

**`Ui`** — stateless shared helpers used by every screen: the S60 soft-key codes (`LSK=-6`,
`RSK=-7`, `MSK=-5`), `isChar`/`toChar` for QWERTY key dispatch, `wrap` (pixel-width word
wrap, preserving `\n`, hard-breaking overlong words), `clip` (truncate a string to a pixel
width with a trailing `..`), and `drawScrollbar`.

**`UiScreen`** — abstract base for every full screen (`Library`, `VaultPicker`, `Settings`,
`UiList`, `UiInput`, `UiEditor`). Paints a title bar (accent dot + bold title), delegates the
content rect to the subclass's `paintBody`, and paints a soft-key bar with `leftLabel` /
`rightLabel` captions (`null` hides a side; the right label is drawn in the accent color as
the primary action). `keyPressed` is `final`: it normalizes LSK/RSK/MSK, the d-pad game
actions, printable QWERTY, backspace and enter into overridable `onLeftSoft`/`onRightSoft`/
`onSelect`/`onUp`/`onDown`/`onLeftArrow`/`onRightArrow`/`onChar`/`onBackspace`/`onKeyOther`
so subclasses never touch raw key codes.

**`UiList`** — themed scrollable, selectable list (the `List` replacement), used directly
for search results and the reader's link list. Rows carry an optional `MARK_*` glyph
(folder / note / image / up-arrow) drawn left of the label; selection is a tinted row plus
a 3px accent bar, pixel-scrolled to stay visible, with a `Ui` scrollbar when it overflows.
`Library` paints its own directory rows the same visual way (it reuses `UiList`'s `MARK_*`
constants) rather than embedding a `UiList` instance, since it also needs a status band
under the title.

**`UiMenu`** — the themed popup command menu that replaces the platform Options menu: a
bottom-anchored, rounded panel over a themed backdrop, listing `String[]` items; UP/DOWN
selects, FIRE/left-soft chooses (`UiMenuOwner.menuSelect`), right-soft cancels back to the
calling screen.

**`UiDialog`** — themed modal replacing `Alert`/confirmations: a centered card (kind `OK`,
`YES_NO` or `OK_CANCEL`) with a word-wrapped message; FIRE is always the positive action.
`UiDialog.info(m, back, title, msg)` is the one-line helper for a plain informational alert
that simply returns to `back`.

**`UiInput`** — themed single-line text entry replacing every name/search/token/password
prompt: a prompt line above a bordered field with a blinking caret, optional `masked`
rendering (bullet characters, real value kept), horizontal scroll when the value outgrows
the field. Right-soft/FIRE confirm (`UiInputOwner.inputOk`), left-soft cancels.

**`UiEditor`** — the themed full-screen multi-line note editor that replaces the native
`TextBox` editor. Model is a `StringBuffer` + caret index; word-wrap into line spans is
cached and only recomputed when the text or the body font actually changes, not on every
keystroke. Guards notes over `MAX_LEN = 200000` characters by refusing to open them
read-write (the caller falls back to the read-only `Viewer`) — a heap guard, not a platform
limit, now that the editor is ours to size.

**Why `Editor` still exists**: it is a two-line shim class kept only so
`nok.NoksidianMIDlet`'s original constructor signature never had to change; the MIDlet
actually constructs `UiEditor` directly. `Editor.java` itself is dead code.

**Font size and integer-crisp scaling.** `Theme` stores the reading size four ways:
`bodyPx` (the exact pixel height notes render at), `bodySize` (the nearest native
`Font.SIZE_*`, used where only a native font will do, e.g. chrome), `bodyBase` (the native
size used as the scaling *source*), and `bodyFactor` (a whole-number upscale, ≥ 1). Settings
presents every crisp size the handset supports — the three native heights it measures at
startup (`Font.SIZE_SMALL/MEDIUM/LARGE`), plus `LARGE` height × 2, × 3 and × 4 — sorted,
de-duplicated, and labelled with their exact pixel height (`"18 px"`, `"36 px"`, ...); the
stored `Config` value is that pixel-height integer as a string, not a word, so the option
list — and any device-specific measured heights — can change without breaking old installs
(a legacy `"small"/"medium"/"large"` value still migrates to its old height). `Viewer` reads
`Theme.bodyFactor`: at factor 1 it draws text normally; at factor > 1 each text run is
rasterized once at the native `bodyBase` size, upscaled with **nearest-neighbor** (every
source pixel becomes a `factor` × `factor` block — perfectly crisp, unlike a stretched/
antialiased scale, which would blur), cached per run (keyed on text/font/color/background/
factor) so steady-state repaints cost one `drawImage`, not a re-rasterize.

**`Library`** — the vault browser, `extends UiScreen implements UiListOwner, UiMenuOwner`.
Rows: `..` (unless at root), folders, then files (only `.md`, images and `.txt`, or every
type when `ui.showall=1`); left-soft **Menu** (New note, New folder, Rename, Delete, Search,
Today, Sync now, Settings, About, Exit), right-soft **Open** (same as D-pad center/FIRE).
`setStatus` draws a status band under the title — that's where "±2 pulled, 1 pushed"
appears. File mutations run on a background thread so painting never blocks.

**`VaultPicker`** — first-run wizard, `extends UiScreen`, browsing `FileSystemRegistry`
roots, directories only. Left-soft **Menu** (Use this folder → `m.vaultChosen(url)`, New
folder, Up, Cancel), right-soft **Open**.

**`Settings`** — `extends UiScreen`, a themed vertical form built from a `Row` model (text /
choice / action / info rows) instead of a native `Form`. Text rows open a `UiInput` on
select (the token field is deliberately unmasked — masked fields break paste on Symbian);
choice rows cycle their value on FIRE/LEFT/RIGHT (cycling **Theme** calls `Theme.load()`
immediately so the Settings screen itself repaints in the new palette before you even Save);
action rows run Test, the encryption flows, Change vault, and Save. Left-soft **Menu**
(Save, Back), right-soft **Save**.

**`Viewer`** — the markdown renderer, a plain themed `Canvas` (not a `UiScreen` subclass,
but it self-draws a matching title-less soft-key bar: "Menu" / "Edit", in the same colors).
`setNote(rel, text)` runs `Md.parse`, then a layout pass that word-wraps every span with `Font.stringWidth`
against `getWidth()` (never a hardcoded 320) into a flat `Vector` of draw items (positioned
text runs, images, rects), reading every color from `Theme` and the body font from
`Theme.bodyBase`/`bodyFactor` (see above). Images resolve through `NoteIndex` or the direct
path, load via `Files.read` + `Image.createImage` inside `try/catch(Throwable)` (a decoder
failure must never kill the screen), and are downscaled to content width with
`Image.getRGB` + nearest-neighbor; files over 400 000 bytes show a placeholder without
decoding. D-pad UP/DOWN scrolls 48 px with a scrollbar thumb; LEFT/RIGHT cycles link focus
among on-screen link boxes; FIRE activates: wikilink → `index.resolve` → `openNote`, or an
offer to create the missing note; http link → `platformRequest`; image → `openImage`; tag →
`searchNotes("#"+tag)`. Left-soft Menu: Edit, Links (opens a `UiList` of every link in the
note — this one used to be a native `List`), Top, Info, Back. Right-soft/key `0`:
Edit / jump to top.

**`ImageView`** — full-screen image `Canvas`, also `Theme`-colored (background, and a
rounded hint pill for "Fit (fire: 1:1)" / "1:1 (fire: fit)"), but the one screen that still
carries a single native `Command("Back")` rather than a drawn soft-key bar — MIDP places a
lone `BACK`-type command on a soft key for you, so it costs nothing to leave as-is. Fit
(nearest-neighbor downscale) is the default; FIRE toggles 1:1, arrows pan at 1:1. Decode
wrapped in `try/catch(Throwable)` → the caller lands back on the library with an error.

### 2.6 `nok.NoksidianMIDlet` — composition root

The `MIDlet` subclass and the only class that wires layers together. `startApp()` runs
`Config.load()` and either restores the saved vault (`initVault()` + `showLibrary`) or
shows the welcome → `VaultPicker` flow. `initVault()` creates the `Files` / `Sync` /
`NoteIndex` trio, ensures `.noksidian/` exists, and calls `sync.start()`. It exposes the
navigation surface every screen uses (`showLibrary`, `openNote`, `editNote`, `openImage`,
`openSettings`, `searchNotes`, `back`, `alertErr`, `show`, `exit`) and implements
`SyncListener`, forwarding status lines to the current Library ticker.

---

## 3. Data flow

### 3.1 Opening a note

```
Library (List)  ── user picks "Daily/2026-07-01.md", Open ──┐
                                                            v
                                   NoksidianMIDlet.openNote(rel)
                                                            |
                          Files.read(rel)                   |  JSR-75 FileConnection
                          -> byte[]                         |  (closed in finally)
                                                            v
                          new String(bytes, "UTF-8")  -> String text
                                                            |
                                                            v
                                   Viewer.setNote(rel, text)
                                       |
              +------------------------+
              v
        Md.parse(text)                       nok.core — pure, no measurement
          -> Vector of MdBlock
              |   per non-CODE block:
              |   Md.inline(block.text) -> Vector of MdSpan
              v
        Viewer layout pass                   nok.ui — owns fonts & geometry
          - word-wrap spans via Font.stringWidth against getWidth()
          - emit draw items {x, y, font, color, flags, text}
          - collect link boxes {x, y, w, h, span}
          - images: index.resolve(src) -> Files.read -> Image.createImage
            (skip decode > 400 KB; try/catch(Throwable) -> placeholder)
              |
              v
        paint(Graphics)                      scroll offset, focus outline,
                                             scrollbar thumb
```

The split matters: `Md` produces a device-independent block/span tree (testable on the
desktop, see section 5), and `Viewer` alone knows about pixels, fonts, and the screen
width. `\n` inside a PARA block is a soft break — the parser keeps it, the renderer
treats it as a space and re-wraps.

Editing follows the reverse path: `Editor` Save → `Files.write(rel, utf8)` →
`Sync.noteSaved(rel)` (starts the ~20 s debounce) → `rebuildIndex()` → `openNote(rel)`
to re-render.

### 3.2 A sync pass, end to end

Triggers — all of them funnel into one coalesced flag on one worker thread:

```
   Timer (sync.interval min)      noteSaved(rel) + ~20 s debounce
   start() + 5 s (auto)           "Sync now" command / requestSync(reason)
             \                       /
              v                     v
          pending = true, wake worker          (no-op if GitHub not configured)
```

The pass itself (worker thread only — the UI thread never does IO here):

```
 1. remote = GitHub.listTree()                 GET /git/trees/{branch}?recursive=1
      drop: .noksidian/  .git  .github/  .obsidian/   (stay remote-only)
      drop: blobs > 1,500,000 bytes                    (logged skip)
 2. local  = Files.scanAll("")                 skips .noksidian/ and dotfiles
 3. state  = Json.parse(.noksidian/state.json)         (missing -> empty)
 4. for each path in union(remote, local, state):

      in remote?  in local?  in state?   action
      ---------   --------   --------    ------------------------------------
      yes         no         no          PULL  (write file; +base copy if md)
      yes         no         yes         deleted on phone -> gh.deleteFile
      no          yes        no          PUSH  (create), record sha
      no          yes        yes         deleted on GitHub -> move local file
                                         to .noksidian/trash/<path>
      yes         yes        no          first contact:
                                           md  -> merge3(base="", local, remote)
                                                  write merged + PUSH
                                           bin -> keep local, save remote copy
                                                  as "<name> (remote).<ext>", PUSH
      yes         yes        yes         see change matrix below

 5. write state.json (also flushed every 10 mutations mid-pass)
    commit messages: "Noksidian: update|add|delete <path>"
```

The both-sides-known change matrix (step 4, last row) is where the merge lives:

```
                        remoteSha == state.sha         remoteSha != state.sha
                        (GitHub unchanged)             (GitHub changed)
 markdown ─ local       nothing to do                  PULL (overwrite local
   == base copy                                        + base, update sha)
 markdown ─ local       PUSH (sha=state.sha)           PULL remote bytes, then
   != base copy         update base + state              merge3(base, local, remote)
                                                         write merged locally
                                                         PUSH merged (sha=remoteSha)
                                                         base <- merged
 binary ─ size/mtime    nothing to do                  PULL
   match state
 binary ─ changed       PUSH                           save remote as
                                                       "<name> (remote).<ext>",
                                                       PUSH local
```

Support files under `.noksidian/` (never synced, filtered on both sides):

- `state.json` — `{"files":{"<path>":{"sha":"...","bin":{"sz":123,"mt":456}?}},"last":<millis>}`.
  Markdown entries carry only the sha (their base copy is the second half of the state);
  binary entries carry sha + recorded size/mtime, because hashing a 1 MB JPEG on this CPU
  is not happening — size+mtime *is* the change detector for binaries.
- `base/<path>` — the exact bytes of each markdown file as of the last sync: the **base**
  input of `merge3`. "Local dirty" literally means `local bytes != base copy bytes`.
- `trash/<path>` — remote deletions land here instead of being hard-deleted on the phone.

Strategy mapping: `sync.strategy` `"both"` → `Merge.KEEP_BOTH`, `"local"` →
`PREFER_LOCAL`, `"remote"` → `PREFER_REMOTE`. Merged results (conflicted or not) are
pushed back immediately, so GitHub converges in the same pass.

Endings:

```
 success  -> syncDone(true,  "±N pulled, N pushed, N merged") -> Library ticker
 IOException on any op -> pass aborts -> syncDone(false, msg)
                       -> retry backoff 60 s -> 2 m -> 5 m -> 15 m (reset on success;
                          requestSync wakes the backoff wait early)
```

---

## 4. The build pipeline (`build.sh`), stage by stage

```
 src/**/*.java
      | (1) javac  -source 1.3 -target 1.3 -bootclasspath CLDC+MIDP+JSR75
      v
 build/classes/          plain class files, major version 47, NOT loadable on phone yet
      | (2) ProGuard -microedition            adds CLDC StackMap attributes
      v
 build/pre/              preverified class files
      | (3) jar cfm + MANIFEST.MF + res/icon.png at jar root
      v
 dist/Noksidian.jar  +  dist/Noksidian.jad     (JAD generated with the real jar size)
```

### Stage 1 — compile

```sh
tools/jdk8/bin/javac -source 1.3 -target 1.3 -Xlint:-options \
    -bootclasspath tools/lib/cldcapi11-2.0.4.jar:tools/lib/midpapi20-2.0.4.jar:tools/lib/microemu-jsr-75-2.0.4.jar \
    -d build/classes @build/sources.txt
```

- `-source 1.3` / `-target 1.3`: language level and class file version 47 (section 1.2).
- `-Xlint:-options`: suppresses JDK 8's warning about the obsolete target.
- `-bootclasspath`: **replaces** `rt.jar` with the real device API. This is the line that
  turns "you used an API the phone doesn't have" from a runtime crash into a compile
  error. The three jars are the CLDC 1.1 API, the MIDP 2.0 API, and the JSR-75
  FileConnection API (see section 6 for exactly which artifacts and the naming trap).
- `@build/sources.txt`: a `find src -name '*.java'` file list — no build system, no
  dependency resolution, the whole tree compiles in one shot in well under a second.

### Stage 2 — preverify

```sh
tools/jdk8/bin/java -jar tools/proguard/lib/proguard.jar \
    -microedition -dontoptimize -dontobfuscate -dontshrink -dontnote \
    -libraryjars "$BOOT" -injars build/classes -outjars build/pre \
    -keep 'class * { *; }'
```

- `-microedition` is the whole point: ProGuard targets CLDC and writes the `StackMap`
  preverification attribute into every method (section 1.3). Without this stage the E71
  rejects the classes.
- `-dontoptimize -dontobfuscate -dontshrink -keep 'class * { *; }'`: ProGuard is used
  *purely* as a preverifier. Nothing is renamed or removed, so on-device stack traces
  still show real class and method names. If the jar ever outgrows the phone, shrinking
  is the first knob to turn — but turn it deliberately, with proper `-keep` rules for the
  MIDlet class.
- `-libraryjars "$BOOT"`: ProGuard needs the same API jars to resolve references while it
  analyzes the bytecode.

### Stage 3 — package

`res/icon.png` and `res/emoji/` (the emoji glyph pack: 124 strip PNGs `p0..p123` plus
`index.bin` — section 2.2) are both copied to the *root* of the jar (as `/icon.png` and
`/emoji/*`), then the jar is built with this manifest:

| Attribute | Value | What the phone does with it |
|---|---|---|
| `MIDlet-1` | `Noksidian, /icon.png, nok.NoksidianMIDlet` | Registers MIDlet #1: menu label, menu icon, and the class the AMS instantiates on launch. One MIDlet per suite here. |
| `MIDlet-Name` | `Noksidian` | With Vendor+Version, forms the suite identity. |
| `MIDlet-Vendor` | `winsucker` | Same Name+Vendor = "same app": installing again is an upgrade, not a duplicate. |
| `MIDlet-Version` | `1.0.0` | Bump it for releases — an in-place upgrade **preserves the RMS record store**, i.e. your settings and vault path survive updates. |
| `MIDlet-Icon` | `/icon.png` | Suite icon in the app manager. |
| `MIDlet-Description` | short blurb | Shown during install. |
| `MicroEdition-Configuration` | `CLDC-1.1` | AMS refuses to install on a CLDC 1.0-only device (we need `long`-heavy code paths and CLDC 1.1 classes). |
| `MicroEdition-Profile` | `MIDP-2.0` | Same gate for the profile. |

The **JAD** duplicates these and adds `MIDlet-Jar-URL: Noksidian.jar` and
`MIDlet-Jar-Size: <bytes>`. `build.sh` `stat`s the finished jar to fill the size in,
because Symbian's installer verifies it: a stale size = "Attribute mismatch, installation
failed". The JAD is only needed for PC Suite / OTA installs; a Bluetooth-sent bare `.jar`
installs fine on its own.

There is no signing stage. The MIDlet runs in the untrusted third-party domain, which is
why the README tells users to flip file/network permissions to "Ask first time only" in
the Symbian app manager — the alternative is a permission prompt on every single
`FileConnection` and HTTP request.

---

## 5. Test strategy (`test.sh`)

```sh
find src/nok/core -name '*.java' > build/testsources.txt
find test         -name '*.java' >> build/testsources.txt
tools/jdk8/bin/javac -source 1.3 -target 1.3 -d build/test @build/testsources.txt
for T in TestBase64Json TestMd TestMergePath; do
    tools/jdk8/bin/java -cp build/test "$T"
done
```

- **Only `nok.core` is compiled** — which is exactly why the "core imports no `javax.*`"
  rule exists. The markdown parser and the 3-way merge are the two most intricate,
  regression-prone pieces of the app; they run and are tested on a desktop JVM with
  sub-second turnaround instead of a deploy-to-phone loop.
- **No JUnit.** Test classes are plain `public static void main(String[])` with a local
  `check(boolean cond, String name)` that throws `RuntimeException(name)` on failure,
  printing one line per group and `ALL PASS <n>` at the end. They are written in Java 1.3
  syntax too, so the same files compile under the device toolchain if ever needed.
- **Know the gap:** `test.sh` compiles *without* the CLDC bootclasspath, against JDK 8's
  full `rt.jar`. A core class could therefore call a JDK-only API and still pass tests.
  `./build.sh` (with the bootclasspath) is the gate that catches that — always run both.
  Green means: `./test.sh` prints `ALL TEST SUITES PASSED` *and* `./build.sh` prints
  `OK: dist/Noksidian.jar (...)`.
- `nok.sys`, `nok.sync`, and `nok.ui` have no automated tests: they are thin wrappers
  around device APIs (`Files`, `Http`, `Config`) or glue whose correctness is dominated
  by LCDUI behavior. The sync *decision logic* is exercised indirectly through
  `TestMergePath` (merge + path), and its riskiest pure parts (`Merge`, `Json`, `Path`,
  `Base64`) all live in core, by design.

---

## 6. Toolchain provenance

Everything is vendored under `tools/` — no system installs, no network needed at build
time. Exact inventory:

| Component | Where | What it is |
|---|---|---|
| JDK 8 (Temurin 1.8.0_492) | `tools/jdk8/` | Last javac that accepts `-source/-target 1.3`; also runs ProGuard and the desktop tests. |
| ProGuard **7.4.2** | `tools/proguard/` | From Guardsquare's GitHub release; only `lib/proguard.jar` is used, in `-microedition` preverifier mode. |
| `cldcapi11-2.0.4.jar` | `tools/lib/` | Maven Central `org.microemu:cldcapi11:2.0.4` — the **CLDC 1.1 API**: real `java.lang`, `java.util`, `java.io`, `javax.microedition.io` stubs. Bootclasspath member. |
| `midpapi20-2.0.4.jar` | `tools/lib/` | `org.microemu:midpapi20:2.0.4` — the **MIDP 2.0 API**: `lcdui`, `rms`, `midlet`, `HttpConnection`. Bootclasspath member. |
| `microemu-jsr-75-2.0.4.jar` | `tools/lib/` | `org.microemu:microemu-jsr-75:2.0.4` — `javax.microedition.io.file.*` (FileConnection API) plus MicroEmulator's implementation classes. Bootclasspath member. |
| `microemu-cldc-2.0.4.jar` | `tools/lib/` | MicroEmulator **runtime** connector plumbing. **NOT on the bootclasspath — see the trap below.** |
| `microemu-midp-2.0.4.jar` | `tools/lib/` | MicroEmulator MIDP runtime. Kept for optional desktop-emulator experiments; unused by `build.sh`. |

### The `microemu-cldc` trap

When hunting Maven for CLDC jars, `org.microemu:microemu-cldc` looks like the obvious
pick — the name says "cldc". **It is not an API jar.** It contains zero `java.lang`
classes (verify: `unzip -l tools/lib/microemu-cldc-2.0.4.jar | grep java/lang` matches
nothing) — only `org.microemu.cldc.*` connection-factory plumbing and a couple of
`javax.microedition.io` interfaces used by the emulator at runtime. Put it on
`-bootclasspath` and javac has no `java.lang.Object`; you get a wall of baffling
"package java.lang does not exist" errors.

The actual API stubs are the artifacts named after the *specs*, not after microemu:
**`cldcapi11`** and **`midpapi20`** (MicroEmulator's republication of the WTK API
classes). JSR-75 is the odd one out: there is no `jsr75api` artifact, so
`microemu-jsr-75` — which *does* bundle the real `javax.microedition.io.file.*`
interfaces alongside its implementation — serves as both API and would-be runtime, and it
sits on the bootclasspath legitimately.

`tools/ghproxy.py` (the TLS 1.0 → TLS 1.2 bridge, plain Python 3 stdlib:
`http.server` + `http.client`, threaded, port 8180 by default) is part of the toolchain
story too, but it runs at *use* time on the LAN, not at build time. It forwards every
request verbatim to `https://api.github.com` — including the `Authorization` header, which
is why the README insists on a fine-grained single-repo PAT and a trusted LAN.

`tools/gen-emoji.py` (Python 3 + Pillow with `raqm`, rendering Google's Noto Color Emoji
font — Apache License 2.0 — into `res/emoji/`) is the same kind of dev-machine-only script:
it runs when the emoji pack needs regenerating (a new emoji-test.txt from unicode.org, a
Pillow/font update), not during `build.sh`. Its output — 124 strip PNGs plus `index.bin` —
is committed like any other resource and simply copied into the jar at packaging time
(section 4); the phone never sees Pillow, raqm, or the source font.

---

## 7. How to extend

### 7.1 Adding a markdown construct, end to end

Example: support Obsidian block quotes with a new inline style `%%sup%%`-like construct
— say, superscript `x^2^` as a new style bit. The path is always the same five steps:

1. **Model** (`nok.core`): add the constant. Inline style → new `MdSpan.B_*` bit
   (`public static final int B_SUP = 32;`). New block shape → new `MdBlock` type constant
   plus whichever of `level/text/extra` it needs. Keep them dumb structs.
2. **Parse** (`nok.core.Md`): extend `inline()` (or `parse()` for a block construct) with
   a hand-rolled scanner — remember there is no regex, no `String.split`. Respect the
   existing invariants: styles are bitmasks that *combine* under nesting, escapes win,
   `` `code` `` suppresses inline parsing, `Md` never measures or draws.
3. **Test** (`test/TestMd.java`): add `check(...)` cases first — plain text in, expected
   block/span structure out. `./test.sh` gives you the whole red/green loop on the
   desktop; you should not need a phone or emulator until step 4 is done.
4. **Render** (`nok.ui.Viewer`): map the new bit/type to a font, color, or draw item in
   the layout pass (superscript = smaller font + raised baseline; a new block = its own
   layout branch, like CALLOUT). Measure with `Font.stringWidth`, never guess widths.
5. **Interact** (only if clickable): emit a link box in the layout pass and handle it in
   the FIRE dispatch, alongside T_WIKILINK/T_LINK/T_TAG/T_IMAGE.

Then run `./test.sh` **and** `./build.sh` (section 5 explains why both). If your parser
change grew a method past ~150 lines, split it — the verifier limit is real.

### 7.2 Adding a Settings key

`Config` is schemaless, so a new key is three small edits:

1. **Pick the name and default**, and document it in CONTRACTS.md next to the known keys
   (`vault`, `gh.*`, `sync.*`). Dotted lowercase, e.g. `view.fontsize`.
2. **Consume it at the point of use** with the default inline:
   `int sz = Config.getInt("view.fontsize", 1);` — absent key = default, so existing
   installs need no migration. `Config.set` persists to RMS immediately; there is no
   "apply" step. If the consumer caches derived state (like `Sync`'s timer interval),
   make it re-read on the next natural boundary, the way `GitHub` re-reads config on
   every call.
3. **Expose it in `nok.ui.Settings`**: add a `textRow`/`choiceRow` to `buildRows()`
   (section 2.5), which reads the current value from `Config.get` for you; `persist()`
   (called from Save) writes every row back automatically, so there is nothing extra to
   wire up there.

Remember the RMS store survives app upgrades (same MIDlet-Name/Vendor), so never change
the *meaning* of an existing key's values — add a new key instead.

### 7.3 Adding a sync backend

`Sync` consumes exactly six operations, currently provided by `GitHub`:

```java
boolean configured();
Vector  listTree()  throws IOException;                 // Vector of GhEntry(path, sha, size)
byte[]  getBlob(String sha) throws IOException;
String  putFile(String path, byte[] content, String knownSha, String message) throws IOException;
void    deleteFile(String path, String sha, String message) throws IOException;
String  test();                                         // null = OK
```

To add, say, Gitea/GitLab/WebDAV:

1. **Extract the interface** (call it `nok.sync.Backend`) with those six methods — plain
   Java 1.3 interfaces are fully supported — and make `GitHub` implement it. `Sync` takes
   a `Backend` instead of constructing `GitHub` directly.
2. **Honor the sha contract.** `state.json` stores one opaque *version token* per file
   and the engine's entire change detection is "token changed vs token same". Your
   backend must return a token that is stable for identical content/revision (git blob
   sha, an ETag, a revision id — anything deterministic). `putFile` must return the *new*
   token, and a mid-air collision on put/delete must throw
   `IOException("conflict: <path>")` so the pass aborts and retries after re-listing.
3. **Keep the filtering semantics**: never surface `.noksidian/` or VCS/housekeeping
   directories from `listTree`, and keep the 1.5 MB blob skip unless your transport can
   stream.
4. **Wire selection through `Config`** (e.g. `sync.backend` = `"github"` | `"gitea"`,
   plus the backend's own keys) and add the fields to the Settings form; `test()` powers
   the Test button for free.
5. **Mind the device constraints**: whatever protocol you speak must survive the E71's
   HTTP stack — `nok.sys.Http` only (no raw sockets in this codebase), TLS 1.0 ceiling
   (so plan for a bridge like `ghproxy.py`, or plain HTTP to a LAN server), redirects
   followed for GET only, and responses that fit in a few MB of heap.

The engine, the merge, the state file, the trash/`(remote)` conflict copies — none of
that changes. That separation is the point of the layering.

---

## 8. What is deliberately NOT here

Knowing the non-goals keeps contributions honest:

- **No build system** (no Maven/Gradle/Ant): two shell scripts and a vendored toolchain
  compile the entire tree in one javac invocation. Do not add one.
- **No third-party libraries on the device.** Everything in the jar is this repo's code;
  there is no dependency mechanism on MIDP anyway.
- **No obfuscation/shrinking** (yet): ProGuard runs preverify-only so stack traces stay
  readable.
- **No signing**: untrusted domain, permissions handled via the phone's app manager.
- **No regex, no `java.text`, no collections framework, no NIO, no reflection tricks** —
  see the cheat sheet (1.4).
- **No git objects on the phone.** Sync speaks the GitHub *Contents/Trees/Blobs* REST
  API; there is no local `.git`, no packfiles, no commit graph. One commit per changed
  file is a consequence of that model.
- **No plugin-style markdown** (Mermaid, LaTeX, Dataview) and no WYSIWYG editing — `UiEditor`
  is a plain themed text canvas on purpose, with no styling toolbar or preview-while-typing.
- **No background sync while the app is closed**: MIDlets don't get background execution;
  the Timer only runs while Noksidian is open.
