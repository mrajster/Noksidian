# Noksidian User Guide

This is the complete manual for using Noksidian on the phone. It assumes the app is already
installed on your Nokia E71 (or another MIDP 2.0 + JSR-75 phone) — see
[Building and installing](install.md) for installation, and the [sync guide](sync.md) for
the GitHub token and the TLS bridge you need for sync.

A quick orientation: Noksidian has seven screens, and you move between them like this:

```
                 +-----------------+
 first run ----> |  Vault wizard   |
                 +-----------------+
                          |
                          v
+----------+      +-----------------+      +----------+
| Settings | <--> |     Library     | <--> |  Search  |
+----------+      +-----------------+      +----------+
                     |           |
                     v           v
              +----------+  +--------------+
              |  Reader  |  | Image viewer |
              +----------+  +--------------+
                     |
                     v
              +----------+
              |  Editor  |
              +----------+
```

Everywhere in the app, the **left softkey** opens the **Menu** (or triggers the primary
command), the **right softkey** goes back, cancels or confirms, and the **D-pad center**
activates the selected thing. There is a full [key reference](#key-reference) at the end.

Every one of those screens — Library, menus, dialogs, Settings, the reader, the editor — is
drawn by Noksidian itself, not by the phone. See [Look and feel](#look-and-feel) for what
that means and how to change it.

## Contents

- [Look and feel: theme, fonts and soft keys](#look-and-feel)
- [First launch: the vault wizard](#first-launch-the-vault-wizard)
- [Unlocking an encrypted vault](#unlocking-an-encrypted-vault)
- [The library](#the-library)
- [Notes and folders](#notes-and-folders)
- [Reading notes](#reading-notes)
- [Editing notes](#editing-notes)
- [Search](#search)
- [Images](#images)
- [Sync](#sync)
- [Settings, field by field](#settings-field-by-field)
- [Key reference](#key-reference)
- [What Noksidian does not do](#what-noksidian-does-not-do)

## Look and feel

Noksidian draws its **entire** interface itself — the library, every menu and dialog,
Settings, the password prompt, the reader and the editor. Nothing you see is a stock
Symbian list, form or text box; the phone's own widgets never show through. That is what
makes a single **Theme** and **Font size** setting apply everywhere, consistently:

- **Theme** (Settings → Theme): **Dark** is true black (`#000000`) with an Obsidian-purple
  accent, matching desktop Obsidian's dark theme as closely as a 320x240 screen allows.
  **Light** mirrors Obsidian's light theme. Whichever you pick colors every screen — title
  bars, lists, the popup menu, dialogs, text fields, the editor, and the reader — not just
  the note view. Switching Theme in Settings repaints the Settings screen itself immediately,
  so you can see the new palette before you even save.
- **Font size** (Settings → Font size): a list of pixel sizes, each shown as "**N px**" —
  the phone's three native crisp sizes plus a few integer upscales (2x, 3x, 4x) of the
  largest native size for readers who want bigger text without blur. Every option renders
  pixel-perfect: the native sizes are the phone's own fonts, and the upscaled sizes are
  built by scaling each native glyph by a whole number, so edges stay crisp instead of going
  soft the way stretched text usually does. The **reader** honors the full choice, upscale
  included; the **editor** and the library list use the same setting but top out at the
  phone's largest native size, since only the reader's renderer does the pixel upscaling.
- **Soft keys.** Every screen has the same two-key layout: the **left softkey opens Menu**
  (a themed popup listing that screen's commands) and the **right softkey runs the primary
  action** for that screen (Open, Save, OK — shown in the accent color so it stands out from
  the muted Menu label). The D-pad center key usually does the same thing as the primary
  action.

## First launch: the vault wizard

The first time you start Noksidian it has no vault yet, so after a short welcome it drops
you into the **vault picker** — a folder browser that starts at the phone's storage roots:

- `C:/` — phone memory (a few MB free, shared with everything else)
- `E:/` — the microSD card (**recommended**: more space, and you can pop the card into a PC)

Only folders are shown. Move with the D-pad and use:

| Command | What it does |
|---|---|
| **Open** (D-pad center) | Enter the selected folder |
| **Use this folder** | Make the folder you are currently *in* the vault |
| **New folder** | Create a folder here (type a name, e.g. `Vault`) |
| **Back** | Go up one level |
| **Cancel** | Abort vault selection |

A typical first run: open `E:/`, choose **New folder**, name it `Vault`, **Open** it, then
**Use this folder**. An empty folder is fine — the first sync will fill it from GitHub.

Right after you pick the vault, Noksidian offers to open **Settings** so you can enter your
GitHub details (see [Settings](#settings-field-by-field)). You can skip this and use the app
fully offline; sync simply stays off until it is configured.

Noksidian creates a hidden `.noksidian/` folder inside the vault for its sync bookkeeping
(state, merge bases, trash). Leave it alone; it is never uploaded to GitHub.

## Unlocking an encrypted vault

If the vault is [encrypted](encryption.md), one screen comes before everything else: a
masked **Vault password** box. Type the password and choose **Unlock** — a short
"Unlocking…" wait follows (deliberate: the key derivation is slow by design), then the
app opens normally. A wrong password shows "That password does not open this vault." and
asks again; **Exit** quits the app, because a locked vault cannot be browsed at all.

After a successful unlock Noksidian asks once whether to **remember the password on this
phone**. Answer **No** to be prompted at every start (the safe default), or **Yes** to
store the key on the phone and skip the prompt from then on — which means anyone holding
your unlocked phone can read the vault. The trade-off, and the **Forget password**
command that undoes a Yes, are covered in
[the encryption guide](encryption.md#unlocking-remembering-and-forgetting).

Once unlocked, everything in this guide works exactly as for an unencrypted vault —
notes decrypt as you open them and encrypt as you save; you never see ciphertext.
Vaults that were never encrypted skip this screen entirely.

## The library

The library is the home screen: the contents of one vault folder.

```
 +--------------------------------+
 | Noksidian     < pulled 3 >     |   <- ticker: live sync status
 |--------------------------------|
 |  ..                            |   <- up one folder (not shown at root)
 |  Daily/                        |   <- folders first, with trailing /
 |  Projects/                     |
 |  Home lab.md                   |   <- then files, sorted
 |  Reading list.md               |
 |  vault-map.png                 |
 |                                |
 | Menu                      Open |
 +--------------------------------+
```

- **Folders are listed first**, then files; `..` at the top takes you up (it is absent at the
  vault root).
- Only relevant files are shown: markdown (`.md`, `.markdown`), images (`.png`, `.jpg`,
  `.jpeg`, `.gif`, `.bmp`) and `.txt`. Anything else in the folder is hidden, and files or
  folders whose name starts with `.` never appear. Set **Settings → Show all files** to
  *On* to list every file type instead (the encryption descriptor `_vault.nkv` stays
  hidden regardless).
- The **ticker** across the title scrolls live sync status ("syncing...",
  "±3 pulled, 1 pushed, 0 merged", error messages). No ticker means sync is idle.

**Type-ahead.** Start typing a name and the selection jumps to it — `re` lands on `README.md`.
A small **find:** pill at the corner of the list shows what you have typed so far, and **Clear**
takes the last letter back. Noksidian prefers a name that *starts* with what you typed, then one
where a later word starts with it (`sync` finds `Obsidian sync setup.md`), then any name that
merely contains it; case never matters, and folders match on their name without the trailing `/`.
The search forgets itself two seconds after your last keystroke, and any deliberate action —
moving with the D-pad, opening the selection, opening the Menu — ends it too, so the next letter
you type starts a fresh search.

Pressing the D-pad center **opens** the selection: a folder opens as a new library view, a
`.md` file opens in the [reader](#reading-notes), an image opens in the
[image viewer](#images). `.txt` files open in the reader too.

The full **Menu**:

| Command | What it does |
|---|---|
| **Open** | Open the selected folder / note / image (same as D-pad center) |
| **New note** | Ask for a name, create `<name>.md` in this folder, open the editor |
| **New folder** | Ask for a name, create the folder here |
| **Today** | Open today's daily note, creating it if needed ([details](#notes-and-folders)) |
| **Rename** | Rename the selected file or folder (same folder — this is not a move) |
| **Delete** | Delete the selected item, after a Yes/No confirmation |
| **Search** | Filename + full-text search across the vault ([details](#search)) |
| **Sync now** | Ask the sync engine for an immediate pass ([details](#sync)) |
| **Settings** | Open [Settings](#settings-field-by-field) |
| **About** | Version and credits |
| **Exit** | Quit Noksidian |

## Notes and folders

**Creating a note.** *Menu → New note*, type a name (no extension — `.md` is added for
you), and the editor opens with an empty note. Typing `2026-07-01` creates
`2026-07-01.md` in the current folder. The note reaches GitHub about 20 seconds after you
save it (see [Sync](#sync)).

**Creating a folder.** *Menu → New folder*, type a name. Empty folders exist only on the
phone — Git cannot represent them — so a new folder appears in the repo once it contains a
file.

**The daily note.** *Menu → Today* opens today's note at
`<daily folder>/YYYY-MM-DD.md` (e.g. `Daily/2026-07-02.md`), creating it with a
`# 2026-07-02` heading first if it doesn't exist yet — one keypress from anywhere in the
library to today's page, just like Obsidian's daily note. The folder is `Daily` by
default and configurable via **Settings → Daily notes folder**, so it can match whatever
your desktop Obsidian daily-notes plugin uses.

**Renaming.** *Menu → Rename* asks for the new name of the selected file or folder. Two
things to know:

- Rename **cannot move** an item to a different folder; it only changes the name in place.
- To sync, a rename is a delete + add: the old path is removed from the repo and the new one
  pushed. Wikilinks pointing at the old name are **not** rewritten — fix them by hand.

**Deleting.** *Menu → Delete* shows a Yes/No confirmation. Deleting a note here deletes
it from GitHub on the next sync pass. Folders can only be deleted when they are **empty** —
delete their contents first.

Deletion in the other direction is gentler: when a file was deleted *on GitHub*, sync does
not hard-delete your local copy — it moves it to `.noksidian/trash/` inside the vault, where
you can fish it out with any file manager.

## Reading notes

Opening a `.md` file shows the rendered note in your chosen theme — true-black background
with light text in **Dark**, white with dark text in **Light** (see
[Look and feel](#look-and-feel)); **Font size** picks the exact pixel height the body text
renders at. On top of that comes Obsidian-style formatting — headings in larger bold type,
**bold**, *italic*, ~~strike~~,
`==highlight==` on yellow, inline code and fenced code blocks on gray monospace, blockquotes
with a purple left bar, callouts (`> [!note]`) as purple boxes, bullet / numbered / task
lists with nesting and real checkboxes, horizontal rules, tables as raw monospace rows, and
YAML frontmatter collapsed into a gray `--- properties ---` box. `%% comments %%` are
hidden entirely.

**Scrolling.** D-pad **up/down** scrolls in 48 px steps. A scrollbar thumb
on the right edge shows where you are. Press **0** (or *Menu → Top*) to jump back to the
top of the note.

**Following links — the focus model.** Notes can contain many tappable things: wikilinks
(purple, underlined), web links (blue, underlined), `#tags` (purple pills), and inline
images. D-pad **left/right** moves a purple rounded outline — the *focus* — from one such
item to the next among those currently visible on screen, wrapping around at the ends.
Scroll to bring more links into view, then cycle to the one you want and press the
**D-pad center** to activate it:

| Focused item | What activation does |
|---|---|
| `[[Wikilink]]` | Opens the target note in the reader |
| `[[Missing note]]` | Offers to **create** it — see below |
| `[text](http://…)`, autolink | Opens the URL in the phone's web browser (Symbian may ask permission) |
| `#tag` | Runs a vault-wide search for `#tag` — see [Search](#search) |
| Inline image | Opens it in the full-screen [image viewer](#images) |

Wikilink flavors all work: `[[Note]]`, `[[Note|shown text]]`, `[[Note#Heading]]`,
`[[folder/Note]]`. Resolution is case-insensitive and matches by basename like Obsidian, so
`[[Home lab]]` finds `Projects/Home lab.md` from anywhere; if several notes share a
basename, the one with the shortest path wins. A `#Heading` part opens the right note but
does **not** jump to the heading — you land at the top.

**Following a wikilink to a note that doesn't exist** works like Obsidian: a Yes/No prompt
asks whether to create it. Choose **Yes** and the editor opens on the new, empty
`<target>.md`; save it and the link turns valid everywhere. Choose **No** to stay put.

The reader's **Menu**:

| Command | What it does |
|---|---|
| **Edit** | Open this note in the [editor](#editing-notes) |
| **Links** | A list of every link in the note — pick one to activate it. Faster than scroll-and-cycle in long notes |
| **Top** | Jump to the top (same as the **0** key) |
| **Info** | Path, file size, and word count |
| **Back** | Return to the library |

## Editing notes

*Menu → Edit* in the reader (or saving a brand-new note) opens the **editor**: a themed
full-screen text canvas, titled with the file name and following the same Theme as the
reader (Font size too, up to the phone's largest native size — see
[Look and feel](#look-and-feel)). Typing on the E71 QWERTY, cursor movement with the D-pad,
and a blinking caret all work as you'd expect; you are editing the raw markdown text.

- **Save** writes the file (UTF-8), re-indexes the vault so new wikilinks/tags resolve
  immediately, returns you to the rendered reader, and **schedules a sync roughly 20 seconds
  after your last save** — quick consecutive saves coalesce into one push.
- **Cancel** discards your changes and goes back.

**Lists continue themselves.** Press **Enter** at the end of `- milk` and the next line already
starts with `- `. Every marker the reader renders works: `- `, `* `, `+ `, numbered items (`3.`
gives you `4.`), task lists (`- [x] done` opens an empty `- [ ] ` box, never a pre-ticked one) and
blockquotes (`> `), with the indentation — and any `>` nesting — of the line you were on preserved.
To *leave* a list, press **Enter** on an item you haven't typed anything into yet: the empty marker
is removed and you are back on a plain line. Either way the marker is ordinary text, so **Clear**
rubs it out like any other character if you didn't want it. Inside a fenced code block
(``` or ~~~) nothing is added: a `- ` there is code, not a list, so Enter just breaks the line.

**Typing characters the keyboard doesn't have.** **Hold the Space bar** for a moment, or choose
*Menu → Insert symbol*, and a grid of about 70 special characters opens over the note. (Why not
Ctrl or Chr? The E71 never passes them to Java apps — Ctrl is not even a real key there, it is
the phone's Fn+Chr combination, handled entirely by the OS — and Shift is reserved for
capitals. Holding Space was free, so that is the shortcut.) The grid holds:
markdown punctuation (`# * _ ~ [ ] | \` and the backtick), dashes and typographic quotes,
`• § °`, `€ £ ¥ ¢`, `± × ÷ ≈ ≠ ≤ ≥ ½ µ π` and arrows. Move with the D-pad, **Insert** places the character at the cursor, **Cancel** closes
the grid. It learns as you go: the characters you insert most often move to the front, so after a
while what you want is in the first row or two.

There is no styling toolbar and no preview-while-typing; you type markdown, then see it
rendered the moment you save.

**Size limit:** the editor holds about 200,000 characters. A note bigger than that opens
read-only with a "too large to edit" alert — split it on the desktop side. (For plain
*note-taking* on a phone you will never hit this; it exists to protect the phone's small
Java heap.)

## Search

*Menu → Search* in the library asks for a query, then searches **file names and full note
text** across the whole vault and shows the matches as a list; select one to open it in the
reader.

- The search is a plain substring match — no operators, no regex.
- **Tag search:** activate any `#tag` pill in the reader and Noksidian runs a search for
  `#project/homelab` (or whatever the tag is) for you — effectively "show every note with
  this tag". You can also type a `#tag` query into Search by hand. Nested tags like
  `#project/homelab` are matched as written.

## Images

**Inline.** `![[vault-map.png]]` and `![alt](vault-map.png)` render the image inside the
note, scaled down to fit the text column. Image targets resolve through the note index just
like wikilinks, so the bare file name works from any folder. What you should expect:

- Formats: whatever the phone decodes — PNG, JPEG and GIF on the E71. (`.bmp` files are
  listed and attempted, but the E71 typically can't decode them.)
- Images over ~400 KB are not decoded inline; you get an "image too large" placeholder
  (focus it and press center to try the full-screen viewer).
- A broken or undecodable image shows a `[image: name]` placeholder box instead of crashing.
- Remote images (`![x](https://…)`) are **not** downloaded; they render as a `[img: alt]`
  link that opens in the phone browser.

**Full-screen viewer.** Opening an image from the library, or activating an inline image in
the reader, shows it full-screen scaled to fit 320x240. Press the **D-pad center** to toggle
between *fit-to-screen* and *1:1 pixels*; at 1:1, the **D-pad arrows pan** around the image.
**Back** returns you to where you came from.

## Sync

Sync keeps the vault and a GitHub repository identical in both directions. Configure it once
in [Settings](#settings-field-by-field); after that it needs no attention.

**When it runs** (any of these, whichever comes first):

- ~5 seconds after the app starts (when *Auto sync* is on),
- ~20 seconds after your last **Save** in the editor,
- every *Sync interval* minutes (5/15/30/60) while the app is open,
- immediately when you choose **Sync now** in the library,
- automatic retries after a failure, backing off 1 min → 2 min → 5 min → 15 min until a
  pass succeeds. **Sync now** also cuts a backoff wait short.

**What you see:** the library **ticker** scrolls the current status while a pass runs and
ends with a summary like `±2 pulled, 1 pushed, 1 merged`, or an error message ("HTTP 401
Bad credentials", "repo or branch not found", ...). Sync never blocks the UI — keep reading
and editing while it works.

**What a pass does**, per file: new on GitHub → pulled to the phone; new on the phone →
pushed (each push is its own commit, e.g. `Noksidian: update Daily/2026-07-01.md`); changed
on one side → that side wins; **changed on both sides → a real line-based 3-way merge**,
written locally *and* pushed back, so both devices converge immediately. If the same lines
changed on both sides, the *Conflicts* setting decides:

- **Keep both** (default) — git-style markers land in the note; nothing is ever lost:

  ```
  <<<<<<< phone
  the line you wrote on the E71
  =======
  the line from GitHub
  >>>>>>> github
  ```

  Just edit the note, keep what you want, delete the markers, save.
- **Prefer phone** — your phone's lines win for the conflicting region.
- **Prefer GitHub** — the repo's lines win.

**Deletions** propagate both ways, but remote deletions are soft on the phone: the file
moves to `.noksidian/trash/` instead of vanishing.

**Binary files** (images, `.txt` — anything that is not markdown) are never merged.
Changed on one side → copied over; changed on
both → your file is pushed and the GitHub version is saved next to it as
`name (remote).png` so you can compare.

**What sync skips:** remote files over 1.5 MB; remote paths under `.git`, `.github/`, `.obsidian/`
and `.noksidian/` (your desktop Obsidian config stays remote-only); local dotfiles; a note
too huge to diff (>1500 lines / 150,000 chars) is not merged line-by-line — one whole side
wins per your Conflicts policy, and it is flagged as a conflict.

**First contact:** if the phone vault and the repo *both* already have a file at the same
path, the two versions are merged (with your Conflicts policy) rather than either being
clobbered. The common cases are simpler: empty phone vault pulls the whole repo; empty repo
receives the whole vault.

## Settings, field by field

*Library → Menu → Settings.* Everything sync needs lives here.

| Field | Meaning | Default |
|---|---|---|
| **Owner** | The GitHub user or org that owns the vault repo (`octocat` in `octocat/vault`) | — |
| **Repo** | The repository name (`vault` in `octocat/vault`) | — |
| **Branch** | Branch to sync with | `main` |
| **Token** | A GitHub personal access token. Use a **fine-grained PAT** limited to this one repo with **Contents: Read and write**. The field is deliberately *not* masked — masked fields break paste on Symbian, and you will want to paste this | — |
| **API URL** | Where GitHub's REST API is reached. On the E71 this must be your TLS bridge, e.g. `http://192.168.1.50:8180` — the phone's TLS 1.0 cannot reach `https://api.github.com` directly (see [the sync guide's bridge section](sync.md#step-2-the-tls-bridge)) | `https://api.github.com` |
| **Auto sync** | On/Off. Off = sync only when you choose **Sync now** | On |
| **Sync interval** | Minutes between automatic passes: 5 / 15 / 30 / 60 | 15 |
| **Conflicts** | *Keep both* / *Prefer phone* / *Prefer GitHub* — what happens when both sides changed the same lines (see [Sync](#sync)) | Keep both |
| **Theme** | *Light* / *Dark* (true black) — colors the **whole app**: library, menus, dialogs, Settings, the editor and the reader, since every screen is drawn by Noksidian itself (see [Look and feel](#look-and-feel)) | Light |
| **Font size** | The exact reading/editing text height, listed as "**N px**": the phone's 3 native sizes plus a few integer-scaled larger sizes, all pixel-crisp | smallest native size |
| **Show all files** | On = the library lists every file type, not just markdown / images / `.txt` | Off |
| **Open last note on start** | On = startup reopens the note you last had open (after unlock, if the vault is encrypted) instead of the library | Off |
| **Daily notes folder** | Where the **Today** command creates and finds daily notes (see [Notes and folders](#notes-and-folders)) | `Daily` |
| **Encryption** (status line) | Shows *Encryption: off*, *on (phone+GitHub)* or *on (phone only)* — controlled by the commands below and the scope choice | off |
| **Encrypted sync scope** | *Phone + GitHub* = the repo holds ciphertext too; *Phone only* = the repo stays plaintext and desktop Obsidian reads it directly. Applies while encryption is on — [which one do I want?](encryption.md#the-two-scopes-which-one-do-i-want) | Phone + GitHub |

Changes to Theme and Font size take effect the moment you **Save** — an open note
re-renders in the new colors when you return to it.

Commands on the Settings screen:

| Command | What it does |
|---|---|
| **Save** | Persist all fields (survives restarts) and go back |
| **Test** | Contact the API with your settings, in the background, and pop up the verdict — run this once after setup; it catches bad tokens, typos and bridge problems before the first sync does |
| **Change vault** | Reopen the [vault wizard](#first-launch-the-vault-wizard) to point Noksidian at a different folder. Notes are not moved; each vault carries its own sync state in its `.noksidian/` |
| **Set password** | *(shown while encryption is off)* Encrypt the vault: choose a password and every file is re-written encrypted, with a progress screen — [full walkthrough](encryption.md#enabling-encryption) |
| **Change password** | *(shown while encryption is on)* Re-encrypt the whole vault under a new password in one pass ([details](encryption.md#changing-the-password)) |
| **Decrypt vault** | *(shown while encryption is on)* Turn encryption off: every file is decrypted in place ([details](encryption.md#disabling-encryption)) |
| **Forget password** | *(shown while a password is remembered)* Delete the key stored by "remember on this phone" — you'll be prompted at the next start ([why you might want this](encryption.md#unlocking-remembering-and-forgetting)) |
| **Back** | Leave without saving |

Sync stays silently disabled ("not configured") until Owner, Repo **and** Token are all
filled in — so an offline-only vault just means leaving Settings empty.

## Key reference

Every themed screen uses the same convention: **left softkey = Menu, right softkey = the
primary action** (shown in the accent color). The exact primary action and what Menu
contains varies by screen:

| Key | Library | Reader | Image viewer | Editor |
|---|---|---|---|---|
| D-pad up / down | Move selection | Scroll (48 px) | Pan (at 1:1) | Move cursor |
| D-pad left / right | — | Cycle link focus (wraps) | Pan (at 1:1) | Move cursor |
| D-pad center | Open selection | Activate focused link | Toggle fit ↔ 1:1 | New line, continuing any list marker |
| Left softkey | Menu | Menu (Edit, Links, Top, Info, Back) | Fit ↔ 1:1 | Menu (Save, Cancel, Insert symbol, Word wrap, Go to top) |
| Right softkey | Open | Edit | Back | Save |
| **Clear** | Shorten the type-ahead search | — | — | Delete before the cursor |
| **Space (hold)** | — | — | — | Open the symbol grid |
| **0** | — | Jump to top | — | Types `0` |
| QWERTY | Type-ahead: jump to a name | — | — | Type markdown |

## What Noksidian does not do

Honest limits, so you don't go hunting for features that aren't there:

- **No Obsidian Sync.** The official protocol needs TLS 1.2+ and modern crypto the E71
  doesn't have — in any configuration. GitHub sync plus the *obsidian-git* plugin on your
  other devices achieves the same converged vault.
- **No plugins, no engine-level markdown:** Mermaid, LaTeX math, Dataview, embeds/transclusion
  (`![[Note]]` of a *note* renders as an image placeholder, not the note's content).
  Footnotes render literally; tables render as raw monospace rows, not a grid.
- **No heading jump:** `[[Note#Heading]]` opens the note at the top.
- **No link rewriting on rename** — renaming a note does not update wikilinks that point to it.
- **No moving files between folders** (Rename is same-folder only) and **no deleting
  non-empty folders**.
- **No editing of huge notes** (over ~200,000 characters — read-only) and **no syncing of
  huge files** (over 1.5 MB — skipped).
- **No background sync when the app is closed** — J2ME apps only run while open. Leave
  Noksidian running (it is light) or open it and let the startup sync run.
- **No touch** — the E71 has no touchscreen; everything is D-pad and keys by design.
