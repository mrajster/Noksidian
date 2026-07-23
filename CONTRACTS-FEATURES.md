# Noksidian — Feature Round 2 Contracts (addendum; CONTRACTS.md + CONTRACTS-CRYPTO.md still apply)

## 1. Theme system — new class `nok.ui.Theme`

```java
public final class Theme {
    public static boolean dark;
    public static int bg, text, dimText, link, wikilink, tagText, tagBg, codeBg, codeText,
                      quoteBar, quoteText, calloutBg, calloutTitle, highlightBg, highlightText,
                      hr, focus, scrollbar, placeholderBg, placeholderText;
    public static int bodySize;      // Font.SIZE_SMALL/MEDIUM/LARGE from config
    public static void load();       // reads Config "ui.theme" ("light"|"dark") and "ui.font"
}
```
Light palette = the colors already in CONTRACTS.md (bg white 0xFFFFFF, text black, link 0x1A4FBF,
wikilink/focus 0x7C3AED, tag 0x7C3AED on 0xEFE7FD, code bg 0xF2F2F2, quote bar 0xB39DDB,
callout 0xEFE7FD, highlight 0xFFF176 with black text, hr/scrollbar grays).
Dark palette: bg 0x121212, text 0xE6E6E6, dimText 0x9E9E9E, link 0x8AB4F8, wikilink 0xB794F6,
tagText 0xD6BCFA, tagBg 0x2E2440, codeBg 0x1E1E1E, codeText 0xD4D4D4, quoteBar 0x6D5A9E,
quoteText 0xB0B0B0, calloutBg 0x241B38, calloutTitle 0xD6BCFA, highlightBg 0x5C4D00,
highlightText 0xFFF3B0, hr 0x3A3A3A, focus 0xB794F6, scrollbar 0x555555, placeholderBg 0x2A2A2A,
placeholderText 0x8A8A8A.
Viewer + ImageView must use ONLY Theme colors (no hardcoded palette) and Theme.bodySize for the
body font; Theme.load() called from midlet startup and after Settings save; open Viewer re-layouts
(midlet re-calls setNote or Viewer exposes its existing relayout path).
Native lcdui screens (List/Form/TextBox) follow the S60 system theme — out of our control; docs
say so.

## 2. New Config keys (all with defaults; Settings must expose every one)

| key           | values                | default | meaning |
|---------------|-----------------------|---------|---------|
| ui.theme      | "light"/"dark"        | light   | canvas theme |
| ui.font       | "small"/"medium"/"large" | small | viewer body font size |
| ui.resume     | "1"/"0"               | 0       | reopen last note on startup |
| ui.showall    | "1"/"0"               | 0       | library shows all file types (not just md/img/txt) |
| daily.folder  | folder rel path       | "Daily" | daily-note folder |
| crypt.scope   | "all"/"local"         | all     | encrypted vault: also encrypt repo, or phone-only |
| net.prompt    | "1"/"0"               | 0       | informational only; docs explain AP selection is OS-level |

`ui.last` (rel path of last opened note) is written by NoksidianMIDlet.openNote — not shown in UI.

## 3. Settings screen additions (same class, same Save/Test/Back commands)

- ChoiceGroup "Theme": Light / Dark.
- ChoiceGroup "Font size": Small / Medium / Large.
- ChoiceGroup "Show all files": Off / On.
- ChoiceGroup "Open last note on start": Off / On.
- TextField "Daily notes folder" (daily.folder).
- Encryption block:
  - StringItem status line: "Encryption: off" / "Encryption: on (phone+GitHub)" / "on (phone only)".
  - ChoiceGroup "Encrypted sync scope": "Phone + GitHub" / "Phone only" (visible always, applies
    when encryption is on).
  - Command "Set password" (when off) -> Encrypt-vault flow; "Change password" and
    "Decrypt vault" (when on). These flows live in a new class (below).
- Saving applies Theme.load() immediately.

## 4. Encryption UI + engine glue — new class `nok.ui.CryptoSetup` + midlet/Sync hooks

```java
public final class CryptoSetup {
    public static void startEnable(NoksidianMIDlet m);   // password+confirm form -> migrate walk
    public static void startChange(NoksidianMIDlet m);   // old+new+confirm -> re-encrypt walk
    public static void startDisable(NoksidianMIDlet m);  // password -> decrypt walk
}
```
- Migration walk runs on a background thread with a progress Form ("Encrypting 12/87 ..."),
  processes every file from files.scanAll("") except `_vault.nkv`, skips files already in the
  target state (magic sniff), writes `_vault.nkv` FIRST on enable (descriptor from
  VaultCrypto.newDescriptor with caller-built salt per CONTRACTS-CRYPTO), deletes it LAST on
  disable, clears `.noksidian/base/` + state.json (forces re-baseline next sync) after any
  migration, then requests a sync.
- NoksidianMIDlet gains: `public VaultCrypto crypto;` (null when vault not encrypted or locked),
  `public boolean vaultLocked();`, unlock flow in initVault(): if `_vault.nkv` exists ->
  masked-password TextBox (TextField.PASSWORD) -> VaultCrypto.open -> keep in RAM; wrong password
  -> alert + retry; "Cancel" exits the app. Optional ChoiceGroup "Remember on this phone" stores
  the 64-byte dk base64 in Config key `crypt.dk` (checked before prompting; "Change/Disable"
  and "Forget password" (new Settings command when crypt.dk set) clear it).
- Codec seam (midlet or a tiny helper): `readText(rel)` / `writeText(rel, text)` used by
  openNote/Editor/search/Viewer-images: bytes with NKE1 magic are decrypted via crypto (locked ->
  alert "vault locked"); writes encrypt when crypto != null (images/binaries too on migration and
  on new-file writes when scope=all... binaries: encrypt on migration; new binary writes are rare
  (conflict copies) — encrypt them too).
- Sync integration (edits in Sync.java):
  - scope=all: bytes flow as stored (ciphertext both sides). Merge branch: decrypt base/local/
    remote plaintext -> merge -> re-encrypt for local write AND push. Pulled files are written
    as received (ciphertext from repo; plaintext-from-repo files stay plaintext until migration).
  - scope=local: before PUSH decrypt (plaintext lands in repo); after PULL encrypt before local
    write; base copies stay ciphertext (decrypt for compare/merge). state sha refers to REMOTE
    (plaintext) blob as before.
  - `_vault.nkv` syncs as a plain binary file, never merged, never encrypted, and is EXEMPT from
    the dotfile visibility rule only in Sync (scanAll already returns it since no leading dot).
  - Local-change detection for md when encrypted: ciphertext-vs-base byte compare still works
    (files are only rewritten on save). Keep it.
- Library hides `_vault.nkv` from listings. Viewer/Editor never see ciphertext (codec seam).

## 5. Daily note + resume

- Library gains command "Today": opens `<daily.folder>/YYYY-MM-DD.md` (zero-padded, from
  Calendar), creating it (with `# YYYY-MM-DD` heading line) if missing, then m.openNote.
- NoksidianMIDlet.startApp: after initVault (and unlock), if ui.resume=1 and ui.last set and file
  exists -> openNote(ui.last) instead of library.

## 6. Bridge hardening — tools/ghproxy.py

- New optional `--secret WORD` flag: only paths starting with `/WORD/` are forwarded (prefix
  stripped before upstream call); anything else -> 403 with json message. App side needs NO code
  change (user sets API URL to `http://host:8180/WORD`; GitHub.apiBase already keeps the path).
- Startup banner prints both the with-secret and without-secret usage lines.
- This enables *mobile data* sync: expose the bridge to the internet (router port-forward or a
  $3 VPS) with --secret; docs explain the risk honestly (token still cleartext HTTP; recommend a
  throwaway fine-grained single-repo PAT, IP allowlisting where possible) and that on WiFi at
  home none of this is needed. Access-point choice itself (WiFi vs 3G) is a Symbian OS prompt /
  default-AP setting — document under "Sync on WiFi or mobile data".

## 7. Docs to add/update in the SAME round

- docs/encryption.md (new): threat model, format summary (link CONTRACTS-CRYPTO.md), enable/
  change/disable walkthroughs, remember-password tradeoff, scope=all vs local (desktop
  readability!), nokcrypt.py usage incl. desktop decrypt/encrypt around Obsidian editing.
- docs/obsidian-sync-setup.md (new): the honest Obsidian Sync story — direct connection
  impossible (proprietary E2EE WebSocket, TLS 1.2+); the supported topology: Obsidian Sync links
  desktop+mobile; ONE desktop runs obsidian-git auto-commit/push/pull against the vault repo; the
  E71 syncs the same repo via Noksidian. Step-by-step obsidian-git config (auto pull/push
  intervals, .gitignore for .obsidian/workspace), how conflicts resolve end-to-end, and the
  encrypted-vault variant (scope=all + nokcrypt on desktop, or scope=local for plaintext repo).
- docs/user-guide.md: Today command, resume setting, theme/font settings, unlock flow section.
- docs/sync.md: mobile-data section (bridge exposure + --secret), scope=all/local table.
- docs/troubleshooting.md: "vault locked", "wrong password", "mac mismatch" rows; mobile-data
  bridge reachability row.
- README.md: feature bullets for encryption, themes, daily notes, Obsidian-Sync-relay setup
  pointer.

## 8. Editor + library input polish (addendum to CONTRACTS-UI.md)

- New `nok.core.MdList` (`prefixLen` / `nextPrefix` / `nextPrefixAt` / `isBare`, unit-tested by
  `test/TestMdList.java`): the literal list/quote marker a line opens with, and the one the next
  line should open with. Signatures and rules in CONTRACTS.md.
- UiEditor Enter continues the list: ONE newline plus `MdList.nextPrefixAt(line, caret column)`
  (`- `, `* `, `+ `, task -> `- [ ] ` unchecked, `1. ` -> `2. `, `> `), indent and quote run kept.
  The caret must be past the marker, or the text moving to the new line would already carry one
  and it would double. Enter at the end of a bare marker deletes the marker and ends the list
  (a CRLF note's `\r` is kept). Inside a ``` / ~~~ fenced code block neither rule applies -
  UiEditor.inFence mirrors Md.readFence's open/close rules - and Enter is a plain newline. The
  marker is plain text the user can delete. Editor menu becomes
  {"Save","Cancel","Insert symbol","Word wrap? (n/a)","Go to top"}.
- New `nok.ui.UiSymbols` + `nok.ui.UiSymbolOwner`: popup grid of ~70 special characters over the
  dimmed editor, soft keys "Insert"/"Cancel", opened by "Insert symbol" or by any unclaimed device
  key EXCEPT the system keys -10/-11/-26/-36/-37/-50 (E71: Shift = S60 Edit -50; -26/-36/-37 are
  Sony Ericsson codes, excluded defensively), by LONG-PRESS SPACE (keyRepeated; the typed space
  is deleted first), and by key code 9 (the E61-generation Ctrl+I chord; emulator Tab). On the
  E71, Ctrl/Chr never reach Java (Ctrl is the OS's Fn+Chr plane, consumed by the FEP) - the menu
  and the space-hold are the real triggers. Ordered most-used-first
  from Config `editor.symfreq` — an internal
  key written by the picker, NOT shown in Settings (like `ui.last`).
- `UiScreen.confirmAccepted(int)`: shared static filter that drops the second key event S60 sends
  for one physical confirm press (one Enter used to insert two newlines). A twin is only ever the
  PARTNER code of the press just accepted (-5 with 10/13), within 150ms, and only the FIRST such
  event - so pressing Enter and then the d-pad centre still gives two actions. Every raw-Canvas
  confirm path consults it; soft keys never do.
- Editor native-input replication (S60 FEP): pencil/Shift(-50)-held selection + Menu Select,
  app-internal Copy/Cut/Paste, long-press-Shift Copy/Paste soft keys, sentence-case auto-cap
  (nok.core.Caps, Settings edit.autocap, TestCaps), Shift-tap one-shot / double-tap caps lock,
  Shift+Clear forward delete, long-press Fn plane (E71 phone-pad digit cluster). Details in
  CONTRACTS-UI.md; long-press Space keeps the symbol grid.
- Library type-ahead: typed letters jump to the matching row (prefix, then word-start, then
  substring), a "find:" pill shows the search, Clear shortens it, 2s idle or any deliberate action
  resets it.
- docs/user-guide.md gains: list continuation + symbol picker under "Editing notes", type-ahead
  under "The library", and the updated editor row in the key reference.
