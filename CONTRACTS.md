# Noksidian — Architecture Contracts

Obsidian-style markdown vault app for Nokia E71 (Symbian S60 3rd FP1) as a J2ME MIDlet.
**MIDP 2.0 + CLDC 1.1 + JSR-75 (FileConnection).** Screen: 320x240 landscape, QWERTY, softkeys.

Every class below MUST be implemented with EXACTLY these public signatures — other agents
compile against them sight-unseen. Private internals are up to the implementer.

## HARD LANGUAGE CONSTRAINTS (Java 1.3 source / CLDC 1.1 API — violations break the build)

FORBIDDEN (do not use, they don't exist on this platform / language level):
- Generics, for-each loops, autoboxing, varargs, enums, annotations, static imports,
  `assert`, try-with-resources, diamond, lambdas, String switch.
- `StringBuilder` → use `StringBuffer`.
- `java.util.List/ArrayList/HashMap/Map/Set/Iterator/Collections/Arrays` → use
  `Vector` (elementAt/addElement/insertElementAt/removeElementAt/setElementAt/size/isEmpty/contains/indexOf),
  `Hashtable` (get/put/remove/keys/containsKey/size), `Stack`, `Enumeration`.
- `String.split / replaceAll / matches / replace(String,String) / format`, all regex → hand-rolled loops.
  `String.replace(char,char)`, `indexOf`, `lastIndexOf`, `substring`, `trim`, `startsWith`, `endsWith`,
  `toLowerCase`, `toUpperCase`, `charAt`, `equalsIgnoreCase`, `compareTo`, `getBytes("UTF-8")`,
  `new String(bytes, off, len, "UTF-8")` ARE available.
- `Character.isWhitespace` does NOT exist → test `c==' '||c=='\t'||c=='\n'||c=='\r'`.
  `Character.isDigit` exists.
- `Integer.parseInt`, `Long.parseLong`, `Double.parseDouble`, `Integer.toString(i)`,
  `Long.toString(l)` exist. NO `Integer.valueOf(int)` → `new Integer(i)`.
- NO `java.text` (no SimpleDateFormat) → format dates by hand with `java.util.Calendar`.
- NO exception chaining (`new IOException(msg, cause)` doesn't exist) → include cause.toString() in message.
- NO `System.arraycopy`? — it EXISTS in CLDC. `Math.min/max/abs` exist.
- Inner classes and anonymous classes ARE allowed (Java 1.1+).
- String concatenation with `+` is fine (javac 1.3 emits StringBuffer).
- Available java.util: Vector, Stack, Hashtable, Enumeration, Date, Calendar, TimeZone, Random, Timer, TimerTask.
- Available java.io: InputStream, OutputStream, DataInputStream, DataOutputStream, ByteArrayInputStream,
  ByteArrayOutputStream, Reader, Writer, InputStreamReader, OutputStreamWriter, IOException.
- MIDP 2.0: javax.microedition.lcdui.*, javax.microedition.rms.*, javax.microedition.midlet.*,
  javax.microedition.io.* (HttpConnection, Connector). JSR-75: javax.microedition.io.file.*.
- Keep every method under ~150 lines (old verifiers choke on huge methods).
- Files must be ASCII-only source. Package root: `src/`.

## Package layout

```
src/nok/NoksidianMIDlet.java      (group G)
src/nok/core/Base64.java          (group A)
src/nok/core/Json.java            (group A)
src/nok/core/Md.java              (group B)
src/nok/core/MdBlock.java         (group B)
src/nok/core/MdList.java          (group B)
src/nok/core/MdSpan.java          (group B)
src/nok/core/Merge.java           (group C)
src/nok/core/MergeResult.java     (group C)
src/nok/core/Path.java            (group C)
src/nok/core/ImageNav.java        (group C)
src/nok/core/NoteIndex.java       (group C)
src/nok/sys/Files.java            (group D)
src/nok/sys/Config.java           (group D)
src/nok/sys/Http.java             (group D)
src/nok/sys/HttpResp.java         (group D)
src/nok/sync/GitHub.java          (group E)
src/nok/sync/GhEntry.java         (group E)
src/nok/sync/Sync.java            (group E)
src/nok/sync/SyncListener.java    (group E)
src/nok/ui/Viewer.java            (group F)
src/nok/ui/ImageView.java         (group F)
src/nok/ui/Library.java           (group G)
src/nok/ui/VaultPicker.java       (group G)
src/nok/ui/Editor.java            (group G)
src/nok/ui/Settings.java          (group G)
test/TestBase64Json.java          (group A)
test/TestMd.java                  (group B)
test/TestMdList.java              (group B)
test/TestMergePath.java           (group C)
test/TestImageNav.java            (group C)
```

`nok.core.*` classes MUST NOT import any `javax.*` — they are unit-tested on desktop JVM.
Test classes: plain `public static void main(String[] args)`, use a local
`static void check(boolean cond, String name)` that throws `RuntimeException(name)` on failure,
print one line per group and "ALL PASS <n>" at the end. Also Java 1.3 syntax.

---

## nok.core.Base64

```java
public final class Base64 {
    public static String encode(byte[] data);           // standard alphabet, with padding, no line breaks
    public static byte[] decode(String s);              // MUST skip \r \n space \t; tolerate missing padding
}
```

## nok.core.Utf8

```java
public final class Utf8 {
    public static String decode(byte[] b);                    // whole array; "" for null/empty
    public static String decode(byte[] b, int off, int len);  // window; "" for len <= 0
    public static byte[] encode(String s);                    // "" for null/empty
}
```
The single note-content, crypto and transport byte<->String codec. Every note
read/write (`NoksidianMIDlet.readText/writeText`, the desktop-index read), the
sync merge and `state.json` paths (`Sync.utf8s/utf8b`), the RMS path cache
(`IndexStore`), the Info byte-count and `%`-escape decoder (`Viewer`), BOTH
key-derivation inputs (`VaultCrypto` path key, `CryptoSetup` passphrase), and
the GitHub HTTP/JSON transport (`HttpResp.bodyText`, and `GitHub.utf8` — which
also encodes the literal note text on `getBlob`'s `encoding=="utf-8"` branch)
route through it instead of `getBytes("UTF-8")` / `new String(b,"UTF-8")`. It
exists because some Symbian CLDC VMs speak CESU-8, so the platform codec cannot
be trusted for astral emoji in either direction — and tree-listing paths are
emoji FILENAMES, which are identity, not cosmetic metadata.

Guarantees:
- **Byte-identical to J2SE** `getBytes("UTF-8")` / `new String(b,"UTF-8")` for
  any WELL-FORMED input — unit-tested against the desktop JDK across a corpus,
  which is what keeps the device interoperable with `tools/nokcrypt.py` and git.
- **CESU-8 tolerant decode**: a 3-byte form whose value lands in U+D800..U+DFFF
  is emitted as that surrogate half unchanged (NOT rejected), so a CESU-8
  high+low pair reassembles into the astral char for free (a Java String is
  UTF-16); re-encoding then yields spec 4-byte UTF-8.
- **Never throws on malformed content.** decode() substitutes U+FFFD for any
  malformed byte (bad or missing continuation, overlong form, value past
  U+10FFFF, 0xF8+ lead, lone continuation) and resyncs at the next lead byte —
  one U+FFFD per bad run (the brief's rule, not Unicode's stricter
  maximal-subpart split). encode() substitutes U+FFFD's bytes (EF BF BD) for an
  unpaired surrogate half (its only divergence from J2SE, and only on ill-formed
  UTF-16). An out-of-range `off`/`len` window handed to `decode` is a caller bug,
  not content, and can still throw ArrayIndexOutOfBounds.
- 4-byte forms above U+FFFF are split into a surrogate pair by hand (no
  `Character.codePointAt`); both directions size their output in one pass.

The one codec left on its own path is `Path.encodeSegment`, which keeps its
inline percent-encoder: it emits a percent triplet per byte as it goes and
never materialises the `byte[]` `Utf8.encode` would return, so routing it
through Utf8 would only add an intermediate array. It shares the
surrogate-pairing arithmetic and must stay in sync.

## nok.core.Json

Values are represented as: `Hashtable` (object), `Vector` (array), `String`, `Long`, `Double`,
`Boolean`, and `Json.NULL` (sentinel, because Hashtable can't hold null).

```java
public final class Json {
    public static final Object NULL;                     // sentinel singleton
    public static Object parse(String s);                // throws IllegalArgumentException on bad input
    public static String write(Object v);                // objects/arrays/strings(escaped)/Long/Double/Boolean/NULL
    // typed helpers (return def / null when key absent or wrong type):
    public static Hashtable obj(Object v);               // v as Hashtable or null
    public static Vector arr(Object v);                  // v as Vector or null
    public static String str(Hashtable o, String key);   // or null
    public static long num(Hashtable o, String key, long def);  // accepts Long or Double values
    public static boolean bool(Hashtable o, String key, boolean def);
}
```
Parser: full JSON incl. nested structures, `\uXXXX` escapes, exponent numbers (parse via
Double.parseDouble; integral numbers without . / e / E become Long). Writer escapes `" \ \n \r \t`
and control chars < 0x20 as `\u00XX`.

## nok.core.MdSpan

```java
public final class MdSpan {
    public static final int T_TEXT = 0, T_LINK = 1, T_WIKILINK = 2, T_TAG = 3, T_IMAGE = 4;
    public static final int B_BOLD = 1, B_ITALIC = 2, B_CODE = 4, B_STRIKE = 8, B_HIGHLIGHT = 16;
    public int kind;        // T_*
    public String text;     // display text (never null)
    public String target;   // href / wikilink target (before # or |) / tag name without # / image src; null for T_TEXT
    public int style;       // bitmask of B_*
    public MdSpan(int kind, String text, String target, int style);
}
```

## nok.core.MdBlock

```java
public final class MdBlock {
    public static final int PARA = 0, HEADING = 1, BULLET = 2, NUMBERED = 3, TASK = 4,
                            QUOTE = 5, CODE = 6, HR = 7, IMAGE = 8, TABLE_ROW = 9,
                            FRONTMATTER = 10, CALLOUT = 11;
    public int type;
    public int level;       // HEADING: 1..6; BULLET/NUMBERED/TASK: indent depth 0..; QUOTE: depth 1..
    public boolean checked; // TASK only
    public String num;      // NUMBERED: display label like "3." else null
    public String text;     // inline-parseable text; CODE: full body (\n-joined); IMAGE: src; TABLE_ROW: raw row; FRONTMATTER: raw yaml
    public String extra;    // CODE: language or ""; IMAGE: alt; CALLOUT: callout type lowercase e.g. "note"
    public MdBlock(int type);
}
```

## nok.core.Md  (parser — the heart; be thorough)

```java
public final class Md {
    public static Vector parse(String text);   // -> Vector of MdBlock, never null
    public static Vector inline(String text);  // -> Vector of MdSpan, never null
}
```
Block rules (Obsidian flavor):
- Frontmatter: if line 0 is `---`, consume until closing `---` → one FRONTMATTER block.
- ATX headings `#`..`######` + space → HEADING level n, text = rest (strip trailing #s).
- Fenced code: ``` or ~~~ opener (optional language word) until matching closer (or EOF) → CODE.
  Content is verbatim, NOT inline-parsed. Indented code (>=4 spaces, outside lists) also CODE.
- HR: line of only 3+ `-` `*` or `_` (with optional spaces) → HR. (Check heading/list first: `- x` is a bullet.)
- Bullets: `- ` `* ` `+ ` (indent: every 2 leading spaces or 1 tab = +1 level) → BULLET.
- Task: `- [ ] ` / `- [x] ` / `- [X] ` → TASK with checked flag.
- Numbered: `12. ` or `12) ` → NUMBERED, num = "12.".
- Quote: one or more leading `>` → QUOTE with depth; `> [!note] Title` (any word) starts CALLOUT
  (extra = word lowercased, text = Title; following `>` lines append to text with \n... simpler:
  each subsequent `>` line becomes its own QUOTE block with same depth — acceptable).
- Table rows: line starting with `|` or containing ` | ` with 2+ cells → TABLE_ROW (raw). Separator
  rows (only `|-: ` chars) are dropped.
- Line that is entirely `![alt](src)` or `![[src]]` (trailing spaces ok) → IMAGE block (src, alt/extra).
- Blank line separates paragraphs. Consecutive plain lines join into one PARA with `\n` kept
  (viewer soft-wraps; treat \n inside PARA as space at render).
- `%% ... %%` Obsidian comments: strip (block form: skip lines between `%%` markers; inline handled in inline()).

Inline rules (must handle nesting and adjacency):
- Escapes: `\` before punctuation emits the char literally.
- `` `code` `` (no inline parsing inside; backticks may be doubled `` ``x`` ``).
- `**bold**`, `__bold__`, `*italic*`, `_italic_`, `***both***`, `~~strike~~`, `==highlight==`.
  Style flags COMBINE (nesting like `**bold *it* bold**` → styles merge in inner span).
- `[text](url)` → T_LINK (text inline-styled; target=url; title-in-quotes after url ignored).
- `![alt](src)` and `![[src]]` inline → T_IMAGE span (text=alt or filename, target=src).
- `[[Target]]`, `[[Target|Display]]`, `[[Target#Heading]]`, `[[Target#Heading|Display]]`
  → T_WIKILINK, target = part before # and |, text = Display or full inner text.
- `#tag` (at start or after whitespace/punct; tag chars: letters digits `-` `_` `/`; must contain
  a non-digit) → T_TAG, target = tag without #, text = "#tag".
- Autolink: `http://` or `https://` run up to whitespace → T_LINK; `<http://x>` too.
- `%%comment%%` → dropped.
- Everything else → T_TEXT spans carrying current style bitmask.

## nok.core.MdList  (list-marker arithmetic for the editor's Enter key)

```java
public final class MdList {
    public static int prefixLen(String line);      // length of indent + quote run + bullet/number
                                                   // + its trailing spaces; 0 if the line opens no item
    public static String nextPrefix(String line);  // marker to open the NEXT line with; "" if none
    public static String nextPrefixAt(String line, int col);  // nextPrefix, gated on col >= prefixLen
    public static boolean isBare(String line);     // line is a marker and nothing else (spaces ok)
}
```
Recognizes exactly what Md.parse() recognizes, so the editor never continues something the viewer
would not render: bullets `- ` `* ` `+ `, tasks `- [ ] ` / `- [x] `, numbers `12. ` / `12) `
(max 9 digits), and a leading `>` quote run (one optional space consumed per level). A space after
the token is required (`-word`, `1.5x` are text) and a thematic break (`---`, `- - -`) is not a
bullet. nextPrefix copies the indent, the quote run and the author's own spacing verbatim, advances
a number by one, and always reopens a task box UNCHECKED. nextPrefixAt adds the caret rule the
editor needs: Enter moves everything right of the caret onto the new line, so with the caret AT or
INSIDE the marker that text already carries one and no continuation may be added ("- milk" at
column 0 would otherwise become "- - milk"); it answers "" there and nextPrefix otherwise.
The rules are LINE-LOCAL — this class sees one line, never the block around it, so a bullet inside
a fenced code block still reads as a bullet and it is the caller that must suppress the
continuation (UiEditor.onSelect does). Pure strings, no javax — tested by test/TestMdList.java.

## nok.core.MergeResult

```java
public final class MergeResult {
    public String text;
    public boolean conflict;   // true if any conflicting hunk was encountered
    public boolean fellBack;   // true if input too large for diff -> strategy fallback applied
    public MergeResult(String text, boolean conflict, boolean fellBack);
}
```

## nok.core.Merge

```java
public final class Merge {
    public static final int KEEP_BOTH = 0, PREFER_LOCAL = 1, PREFER_REMOTE = 2;
    public static MergeResult merge3(String base, String local, String remote, int strategy);
}
```
Line-based 3-way merge: split all three into lines; LCS (dynamic programming) alignment
base↔local and base↔remote; walk chunks: change on one side only → take it; identical change →
take once; different changes to same region → conflict hunk resolved per strategy:
KEEP_BOTH emits git-style markers:
```
<<<<<<< phone
(local lines)
=======
(remote lines)
>>>>>>> github
```
PREFER_LOCAL / PREFER_REMOTE take that side (conflict flag still set).
Guard: if any input > 1500 lines or > 150000 chars, skip diff: return
(strategy==PREFER_REMOTE ? remote : local) with conflict=true, fellBack=true.
Trivial cases: local.equals(remote) → no conflict; base.equals(local) → remote; base.equals(remote) → local.

## nok.core.Path

```java
public final class Path {
    public static String join(String a, String b);        // "a/b", handles empty a
    public static String parent(String p);                // "" if top-level
    public static String name(String p);                  // last segment
    public static String baseName(String p);              // name minus final .ext
    public static String ext(String p);                   // lowercase, no dot, "" if none
    public static boolean isMarkdown(String p);           // md | markdown
    public static boolean isImage(String p);              // png jpg jpeg gif bmp
    public static String normalize(String p);             // strip lead/trail '/', collapse "//", "\" -> "/"
    public static String urlEncodePath(String p);         // %-encode UTF-8 bytes of each segment for URLs; keep '/'; encode space %20, keep unreserved A-Za-z0-9 - _ . ~
}
```

## nok.core.NoteIndex

```java
public final class NoteIndex {
    public NoteIndex();
    public void clear();
    public void add(String relPath);
    public Vector all();                       // sorted Vector of String (all files)
    public Vector markdownFiles();             // sorted, only markdown
    public String resolve(String wikiTarget);  // null if no match
}
```
resolve(): input may still contain `#...` or `|...` → strip them first; try (case-insensitive):
exact relpath match; relpath + ".md"; basename match (target "Foo" matches "dir/Foo.md");
if several basename matches, return shortest path. Sorting: simple case-insensitive compareTo.

---

## nok.sys.Config  (RMS-backed key-value store; RecordStore name "nokcfg")

```java
public final class Config {
    public static void load();                            // safe to call repeatedly
    public static String get(String key, String def);
    public static int getInt(String key, int def);
    public static void set(String key, String value);     // persists immediately
}
```
Serialization: single record, DataOutputStream: writeInt(count) then writeUTF(key)/writeUTF(value) pairs.
Known keys: `vault` (file URL, trailing '/'), `gh.owner`, `gh.repo`, `gh.branch` (default "main"),
`gh.token`, `gh.api` (default "https://api.github.com"), `sync.auto` ("1"/"0", default "1"),
`sync.interval` (minutes, default "15"), `sync.strategy` ("both"|"local"|"remote", default "both").

## nok.sys.Files  (JSR-75 wrapper; `rel` paths are vault-relative, '/'-separated, no leading '/')

```java
public final class Files {
    public Files(String vaultUrl);                 // e.g. "file:///E:/Notes/" — MUST end with '/'
    public String vaultUrl();
    public byte[] read(String rel) throws IOException;
    public void write(String rel, byte[] data) throws IOException;   // auto-creates parent dirs, truncates
    public boolean exists(String rel);
    public boolean isDir(String rel);
    public void delete(String rel) throws IOException;               // file or empty dir
    public void mkdirs(String rel) throws IOException;
    public Vector list(String rel) throws IOException;   // child names, dirs end with '/'; "" = vault root; sorted dirs-first
    public long modified(String rel);                    // 0 on error
    public long size(String rel);                        // -1 on error
    public void rename(String rel, String newName) throws IOException; // newName = plain name, same dir
    public void scanAll(String rel, Vector out);         // recursive; adds rel paths of FILES only; skips ".noksidian/" and names starting with "."
    public static Vector listRoots();                    // Vector of String like "E:/" (from FileSystemRegistry)
}
```
File URL building: vaultUrl + percent-encoded rel (encode space as %20 and '%' as %25 per segment,
leave other chars incl. non-ASCII as-is). Every FileConnection MUST be closed in finally.
write(): open with Connector.READ_WRITE, create() if !exists, truncate(0), then OutputStream.

## nok.sys.HttpResp

```java
public final class HttpResp {
    public int code;
    public byte[] body;          // never null (empty array ok)
    public Hashtable headers;    // lowercase header name -> String value (subset is fine)
    public String bodyText();    // body as UTF-8 String ("" if empty)
}
```

## nok.sys.Http

```java
public final class Http {
    public static HttpResp request(String method, String url, Hashtable headers, byte[] body)
        throws IOException;
}
```
- Connector.open(url, Connector.READ_WRITE, true) cast to HttpConnection (works for https too).
- setRequestMethod, setRequestProperty for each header; always set "User-Agent: Noksidian/1.0"
  and "Connection: close". If body != null set "Content-Length" and write it.
- Read full response body (getLength() when >=0 else read to EOF, 4KB chunks).
- Capture response headers: etag, location, content-type, x-ratelimit-remaining.
- Follow up to 3 redirects (301/302/303/307) for GET only (re-open with Location).
- Close connection + streams in finally.

---

## nok.sync.GhEntry

```java
public final class GhEntry {
    public String path;
    public String sha;
    public long size;
    public GhEntry(String path, String sha, long size);
}
```

## nok.sync.GitHub  (REST v3 client; reads Config each call — no cached fields)

```java
public final class GitHub {
    public GitHub();
    public boolean configured();                       // owner+repo+token all non-empty
    public String apiBase();                           // Config gh.api trimmed of trailing '/'
    public Vector listTree() throws IOException;       // Vector of GhEntry (blobs only)
    public byte[] getBlob(String sha) throws IOException;
    public String putFile(String path, byte[] content, String knownSha, String message)
        throws IOException;                            // returns NEW blob sha
    public void deleteFile(String path, String sha, String message) throws IOException;
    public String test();                              // null if OK else human-readable error
}
```
- listTree: GET {base}/repos/{o}/{r}/git/trees/{branch}?recursive=1 ; parse "tree" array,
  keep entries with "type"=="blob"; if "truncated" is true → throw IOException("repo too large").
  404 → throw IOException("repo or branch not found").
- getBlob: GET {base}/repos/{o}/{r}/git/blobs/{sha} → json "content" base64 (may contain \n) → decode.
- putFile: PUT {base}/repos/{o}/{r}/contents/{urlEncodedPath} body {"message","content"(b64),"branch","sha"?}
  knownSha null → omit "sha" (create). Parse response "content"."sha". 409/422 → IOException("conflict: <path>").
- deleteFile: DELETE .../contents/{path} with {"message","sha","branch"}.
- Headers on every call: Authorization: "token " + gh.token ; Accept: "application/vnd.github+json".
- Non-2xx: parse json "message" if possible, throw IOException("HTTP <code> <message>").

## nok.sync.SyncListener

```java
public interface SyncListener {
    void syncStatus(String msg);                 // short progress line, any thread
    void syncDone(boolean ok, String summary);   // end of a sync pass
}
```

## nok.sync.Sync  (engine; owns ONE worker thread + one java.util.Timer)

```java
public final class Sync {
    public Sync(Files files, SyncListener listener);
    public void start();                    // starts worker; if sync.auto=1 schedule periodic + initial (5s) sync
    public void stop();
    public void requestSync(String reason); // async, coalesced; no-op if unconfigured
    public void noteSaved(String rel);      // debounce: sync ~20s after last save
    public boolean isSyncing();
    public String lastSummary();            // "" initially
}
```
State file `.noksidian/state.json`:
`{"files":{"<path>":{"sha":"...","bin":{"sz":123,"mt":456}?}},"last":<millis>}`
(md entries: sha only + base copy exists; binary entries: sha + recorded size/mtime).
Base copies of markdown files at `.noksidian/base/<path>` (exact remote/base bytes at last sync).

Sync pass algorithm (worker thread):
1. If !github.configured() → done("not configured").
2. remote = listTree(); drop paths under ".noksidian/", ".git", ".github/", ".obsidian/" (those stay
   remote-only), and remote blobs > 1,500,000 bytes (log skip).
3. local = files.scanAll("") (already skips .noksidian + dotfiles).
4. state = parse state.json (missing → empty).
5. For each path in union(remote, local, state):
   - remote only, no state  → PULL (write file + base copy if md; record sha [+bin sz/mt]).
   - remote only, in state  → was deleted locally → gh.deleteFile, drop state.
   - local only, no state   → PUSH (create), record.
   - local only, in state   → deleted remotely → move local to ".noksidian/trash/<path>", drop state+base.
   - both, no state (first contact): md → merge3(base="", local, remote, strategy); write merged
     locally, PUSH with remote sha; binary → keep local, save remote bytes as sibling
     "<base> (remote).<ext>", PUSH local with remote sha.
   - both + state:
     * remoteSha == state.sha:
         md: local bytes == base copy → nothing; else PUSH (sha=state.sha), update base+state.
         bin: size/mtime == recorded → nothing; else PUSH.
     * remoteSha != state.sha:
         md: local == base → PULL (overwrite local + base, update sha).
             local != base → PULL remote bytes, merge3(base, local, remote, strategy);
             write merged locally + PUSH merged (sha=remoteSha); if result.conflict, count it;
             update base to merged, state to new sha.
         bin: local unmodified → PULL; modified → save remote as "<base> (remote).<ext>", PUSH local.
6. Save state.json (also after every 10 mutations). Commit messages: "Noksidian: update|add|delete <path>".
7. Failures: an op-level IOException aborts the pass; report via syncDone(false, msg) and schedule
   retry with backoff 60s → 2m → 5m → 15m (reset on success). Success → syncDone(true, "±N pulled, N pushed, N merged").
Strategy mapping from Config sync.strategy: "both"→KEEP_BOTH, "local"→PREFER_LOCAL, "remote"→PREFER_REMOTE.
Coalescing: boolean pending flag; worker loops while pending. All file+network IO on worker thread only.
Text ops use UTF-8. requestSync also wakes backoff wait early.

---

## UI common notes
- Obtain Display via midlet.disp (public field). Colors: Obsidian purple accent 0x7C3AED.
- All Displayable classes get a reference to `NoksidianMIDlet` in the constructor.
- Long operations (file IO ok-ish, network NEVER) must not run on the UI event thread.

## nok.NoksidianMIDlet

```java
public class NoksidianMIDlet extends MIDlet implements SyncListener {
    public Display disp;
    public Files files;          // null until vault chosen
    public Sync sync;            // null until vault chosen
    public NoteIndex index;      // rebuilt by rebuildIndex()
    protected void startApp();   // Config.load(); vault set ? initVault+showLibrary : welcome->VaultPicker
    protected void pauseApp();
    protected void destroyApp(boolean unconditional);
    public void vaultChosen(String fileUrl);       // save config, initVault(), first-run: offer Settings
    public void initVault();                       // create Files/Sync/NoteIndex, mkdirs .noksidian, sync.start()
    public void rebuildIndex();                    // files.scanAll -> index
    public void showLibrary(String dirRel);
    public void openNote(String rel);              // read file; Viewer (alerts on error)
    public void editNote(String rel);              // Editor (new files: empty content)
    public void openImage(String rel);
    public void openSettings();
    public void showAbout();
    public void searchNotes(String query);         // filename+content search -> results list -> openNote
    public void alertErr(String title, String msg);
    public void show(javax.microedition.lcdui.Displayable d);
    public void back();                            // return to library at last dir
    public void exit();
    // SyncListener impl forwards status to current Library ticker / title.
}
```

## nok.ui.VaultPicker

```java
public final class VaultPicker extends List implements CommandListener {
    public VaultPicker(NoksidianMIDlet m);         // starts at FileSystemRegistry roots
}
```
Navigate roots/dirs (List IMPLICIT). Commands: "Open" (default), "Use this folder" (calls
m.vaultChosen(currentDirUrl)), "New folder", "Back"(up), "Cancel". Show only directories.

## nok.ui.Library

```java
public final class Library extends List implements CommandListener {
    public Library(NoksidianMIDlet m, String dirRel);
    public void refresh();
    public void setStatus(String s);               // shows via setTicker
}
```
Entries: ".." (unless root), folders "name/", then files (only .md + images + txt shown).
Commands: Open (IMPLICIT default), "New note" (asks name → creates "<name>.md" → Editor),
"New folder", "Rename", "Delete" (confirm via Alert with Yes/No), "Search", "Sync now"
(m.sync.requestSync), "Settings", "About", "Exit". Open on folder → m.showLibrary(child);
on .md → m.openNote; on image → m.openImage. New note name input via TextBox screen.

## nok.ui.Editor

```java
public final class Editor extends TextBox implements CommandListener {
    public Editor(NoksidianMIDlet m, String rel, String content);
}
```
TextBox(Path.name(rel), content, max(65536, fallback to getMaxSize()), TextField.ANY).
If content longer than maxSize → alert "too large to edit" and show read-only viewer instead
(constructor can't easily bail — Library/midlet checks length BEFORE constructing; still guard).
Commands: "Save" (files.write UTF-8, sync.noteSaved(rel), m.rebuildIndex(), m.openNote(rel)),
"Cancel" (m.back()).

## nok.ui.Settings

```java
public final class Settings extends Form implements CommandListener {
    public Settings(NoksidianMIDlet m);
}
```
Fields: TextField owner, repo, branch, token (TextField.ANY — token visible; masked input on
Symbian breaks paste), api url, ChoiceGroup "Auto sync" (On/Off), ChoiceGroup interval
(5/15/30/60 min), ChoiceGroup conflicts ("Keep both", "Prefer phone", "Prefer GitHub").
Commands: "Save" (Config.set all, alert "Saved", m.back()), "Test" (background thread →
new GitHub().test() → alert result), "Change vault" (new VaultPicker), "Back".

## nok.ui.Viewer  (the renderer — be thorough)

```java
public final class Viewer extends Canvas implements CommandListener {
    public Viewer(NoksidianMIDlet m);
    public void setNote(String rel, String text);   // parse + layout + repaint, reset scroll
}
```
- Layout pass builds a Vector of draw items (text runs with x,y,font,color,flags; images; rects)
  by word-wrapping spans with Font.stringWidth against width (240 in portrait? use getWidth()
  dynamically — E71 is 320x240).
- Fonts: body Font.getFont(FACE_PROPORTIONAL, PLAIN, SIZE_SMALL); bold/italic via STYLE flags;
  code FACE_MONOSPACE; H1/H2 SIZE_LARGE bold, H3/H4 SIZE_MEDIUM bold, H5/H6 SIZE_SMALL bold.
- Visuals: white bg; black text; T_LINK 0x1A4FBF underlined; T_WIKILINK 0x7C3AED underlined;
  T_TAG 0x7C3AED on 0xEFE7FD rounded pill; inline code / CODE blocks on 0xF2F2F2 (monospace,
  CODE block with left padding + border); QUOTE: 3px 0xB39DDB left bar, gray text; CALLOUT:
  0xEFE7FD box with bold title; HIGHLIGHT bg 0xFFF176; STRIKE line through middle; TASK: draw
  checkbox square, filled + check for checked; HR gray line; HEADING extra top margin;
  TABLE_ROW monospace small; FRONTMATTER: collapsed gray "--- properties ---" box (raw lines, small gray).
- IMAGE blocks & T_IMAGE spans: resolve src — if http(s) show as link text "[img: alt]" (T_LINK);
  else via index.resolve or direct rel path; load bytes files.read, Image.createImage(b,0,b.length)
  inside try/catch(Throwable) → on fail show "[image: name]" placeholder box. Downscale to fit
  content width using Image.getRGB + nearest-neighbor into new mutable Image. Skip decode if
  file > 400000 bytes (placeholder "image too large"). Cache Hashtable src→Image for current note only.
- Scroll: UP/DOWN game actions scroll 48px, clamp [0, contentH-viewH]; scrollbar thumb on right edge.
- Links: layout collects boxes {x,y,w,h,span}. LEFT/RIGHT cycles focus among boxes in view
  (wrap); focused box drawn with 0x7C3AED rounded outline; FIRE activates:
  T_WIKILINK → r=m.index.resolve(target); r!=null ? m.openNote(r) : Alert offer? → create:
  m.editNote(target+".md") after confirm Alert(Yes/No). T_LINK http → m.platformRequest(url)
  wrapped in try/catch, T_IMAGE → m.openImage(resolved), T_TAG → m.searchNotes("#"+tag).
- Commands: "Edit" (m.editNote(rel)), "Back" (m.back()), "Links" (List of all links in note → activate),
  "Top", "Info" (Alert: path, size, words). Key '0' → top. pointer events optional (E71 has none).

## nok.ui.ImageView

```java
public final class ImageView extends Canvas implements CommandListener {
    public ImageView(NoksidianMIDlet m, String rel);
}
```
Fit-to-screen (nearest-neighbor scale), FIRE toggles 1:1 ↔ fit, arrows pan at 1:1,
"Back" command. try/catch(Throwable) on decode → alert + back.
At fit, LEFT/RIGHT flips to the prev/next image of the same folder (nok.core.ImageNav
over Files.list order: images only, dot-names/dirs skipped, wraps at the ends); loads on
a background thread (one at a time), each photo opens fit with pan reset; soft-bar center
shows "n/m" ("..." while loading); a failed sibling load alerts and keeps the current
photo (no kick to library). Listing failure just disables navigation.

---

## Build

`build.sh` compiles src with `-source 1.3 -target 1.3 -bootclasspath` CLDC+MIDP+JSR75 stubs,
preverifies via ProGuard `-microedition`, jars with manifest + `res/icon.png` at jar root, emits JAD.
`test.sh` compiles `nok/core/**` + `test/**` with tools/jdk8 javac (default target) and runs the
three Test mains on the desktop JVM.
