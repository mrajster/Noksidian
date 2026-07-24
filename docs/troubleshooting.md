# Troubleshooting & FAQ

Something is broken? Find your symptom below. Sync status is shown in the Library
ticker (the scrolling line at the top of the file list); the last error or summary
from a sync pass is what you see there and in the end-of-sync message, e.g.
`±3 pulled, 1 pushed, 0 merged` on success, or an error like `HTTP 401 Bad credentials`.

Quick index:

| Symptom | Section |
|---|---|
| Sync says "not configured" | [Sync says "not configured"](#sync-says-not-configured) |
| `HTTP 401 Bad credentials` | [401 Bad credentials](#http-401-bad-credentials) |
| `repo or branch not found` / 404 | [404 repo or branch not found](#repo-or-branch-not-found--http-404) |
| `conflict: <path>` | [conflict: path errors](#conflict-path-errors) |
| Nothing syncs, every attempt fails | [Nothing syncs at all (it's TLS)](#nothing-syncs-at-all-its-tls) |
| Bridge set up but unreachable | [Bridge unreachable](#bridge-unreachable) |
| `repo too large` | ["repo too large"](#repo-too-large) |
| Some files just never sync | [Files that never sync](#files-that-never-sync) |
| `<<<<<<< phone` appeared in a note | [Conflict markers in a note](#conflict-markers-appeared-in-a-note) |
| "vault locked" in the ticker or reader | ["vault locked"](#vault-locked) |
| "Wrong password" at unlock | [Wrong password](#wrong-password) |
| `sync: mac mismatch <path>` | [mac mismatch on sync](#sync-mac-mismatch-path) |
| Forgot the vault password | [Forgot the vault password](#forgot-the-vault-password) |
| Permission prompt on every action | [Permission prompts every time](#permission-prompts-on-every-action) |
| App ignores my phone's theme / dark mode | [The app doesn't follow my phone's theme](#the-app-doesnt-follow-my-phones-theme) |
| Font size shows "18 px" instead of Small/Medium/Large | [The app doesn't follow my phone's theme](#the-app-doesnt-follow-my-phones-theme) |
| Out of memory / image placeholders | [Out of memory, big notes and images](#out-of-memory-on-big-notes-or-images) |
| "too large to edit" | [Note too large to edit](#note-too-large-to-edit) |
| Weird characters in notes | [Weird characters](#weird-characters--encoding) |
| Emoji show blank / missing | [Emoji show as blank or missing](#emoji-show-as-blank-or-missing) |
| Reset sync from scratch | [Resetting sync state safely](#resetting-sync-state-safely) |
| Change the vault folder | [Moving a vault](#moving-a-vault) |
| Battery drains / data usage | [Battery and data usage](#battery-and-data-usage) |

---

## Sync problems

### Sync says "not configured"

A sync pass ends immediately with `not configured` when any of **owner**, **repo**
or **token** is empty. Nothing was contacted; this is a local check.

Fix: open **Settings** from the Library's Menu, fill in the GitHub owner
(your username or org), repo name, branch (default `main`), and your token. Press
**Test** — it runs a real request in the background and tells you exactly what
GitHub thinks. Then **Save** and use **Sync now**.

The API URL only needs changing if you use the TLS bridge (see below); the default
is `https://api.github.com`.

### HTTP 401 Bad credentials

GitHub rejected your token. Causes, in order of likelihood:

- **Typo in the token.** Tokens are long; re-paste it. The token field in Settings
  is deliberately *not* masked so you can see what you pasted (masked input on
  Symbian breaks paste).
- **Token expired.** Fine-grained PATs have an expiry date. Create a new one at
  github.com → *Settings → Developer settings → Fine-grained tokens*.
- **Stray whitespace** picked up while copying (a trailing space or newline).

Noksidian sends the token as `Authorization: token <your-token>` — you enter only
the token itself, never the word `token`.

Note the distinction: a valid token *without access to the repo* usually produces
a **404**, not a 401 (GitHub hides repos you can't see). See the next section.

### "repo or branch not found" / HTTP 404

The tree listing came back 404. Check, in order:

1. **Owner and repo spelling** in Settings. It's `owner` + `repo` as in
   `github.com/owner/repo`, no `.git` suffix, no URL.
2. **Branch name.** Default is `main`; older repos use `master`. The branch must
   already exist — Noksidian does not create branches. A brand-new repo with zero
   commits has *no* branches at all: push at least one commit (even just a README)
   from a PC first.
3. **Token scope.** A fine-grained PAT must explicitly list the vault repo under
   *Repository access* with **Contents: Read and write**. If the repo isn't
   granted, GitHub answers 404 — it looks exactly like a typo.

**Test** in Settings reproduces this without waiting for a sync pass.

### "conflict: path" errors

A push was rejected with `conflict: <path>` (GitHub HTTP 409/422). It means the
file changed *on GitHub* between the moment Noksidian listed the tree and the
moment it pushed — someone (or obsidian-git on your desktop) committed in that
window. Nothing was corrupted; the push was simply refused.

Fix: **Sync now**. The next pass fetches the new remote version, 3-way merges it
with your local edit, and pushes the merged result. If it keeps happening, another
device is committing continuously — let it finish, then sync.

### Nothing syncs at all (it's TLS)

Symptom: sync never succeeds, **Test** fails with a connection/IO error, yet the
phone's browser works and the settings are correct.

Almost certainly: you pointed the API URL directly at `https://api.github.com`.
**That cannot work on an E71.** GitHub requires TLS 1.2+; the E71's stack tops out
at TLS 1.0, so the handshake fails before a single byte of HTTP is exchanged.

Fix: run the bundled TLS bridge (`tools/ghproxy.py`) on any always-on machine on
your LAN and set **Settings → API URL** to `http://<machine-ip>:8180`. Full setup
is in [the sync guide's bridge section](sync.md#step-2-the-tls-bridge) (short
version in the README's *"The TLS catch"* section). Any other TLS-1.2-terminating
reverse proxy (nginx, Caddy) pointed at `https://api.github.com` works too.

### Bridge unreachable

The bridge is running but the phone still can't sync. Work through the chain:

```
 [E71] --WiFi--> [router] --LAN--> [bridge PC :8180] --TLS 1.2--> api.github.com
   1                2                    3                              4
```

**3 — Is the bridge actually up?** On the bridge machine itself:

```
curl -i http://localhost:8180/rate_limit
```

You should get `HTTP/1.1 200` and a JSON rate-limit object (this endpoint works
without a token). If not, the bridge isn't running — start it with
`python3 tools/ghproxy.py` and check it prints its startup banner.

**2/3 — Reachable across the LAN?** From *another* PC or laptop on the same network:

```
curl -i http://<bridge-ip>:8180/rate_limit
```

- Works on localhost but not from another machine → the bridge host's **firewall**
  is blocking port 8180. Open it (`ufw allow 8180`, `firewall-cmd --add-port=8180/tcp`,
  or Windows Defender Firewall → inbound rule).
- Make sure you're using the machine's LAN IP (`ip addr` / `ipconfig`), not
  127.0.0.1, and that the IP hasn't changed (give the bridge host a DHCP
  reservation or static IP).

**1/2 — Can the phone reach the LAN at all?** Many routers and virtually all
guest-WiFi networks enable **AP/client isolation**: wireless clients can reach
the internet but not other LAN devices. The phone browser loading google.com
while the bridge times out is the classic sign. Disable client/AP isolation for
that SSID, or put the phone on the main (non-guest) network. Also confirm the
phone's access point in Symbian settings is your WLAN, not a 3G/GPRS profile —
on cellular there is no route to `192.168.x.x`.

**4 — Bridge up but GitHub down from it?** If the phone gets a JSON body like
`{"message":"proxy error: ..."}` (HTTP 502), the phone→bridge hop is *fine* — the
bridge itself failed to reach `api.github.com`. Check the bridge machine's
internet connection and DNS.

Full end-to-end check with your real token, from any PC:

```
curl -i -H "Authorization: token <YOUR_PAT>" \
     -H "Accept: application/vnd.github+json" \
     "http://<bridge-ip>:8180/repos/<owner>/<repo>/branches/main"
```

`200` here means everything past the phone works; anything left is step 1/2.

### "repo too large"

Sync aborts with `repo too large`. Noksidian lists the whole repo in one
recursive tree call; when GitHub marks that listing *truncated* (roughly 100k
entries / a 7 MB response), Noksidian refuses to sync rather than silently miss
files.

There is no workaround inside the app. Use a **dedicated repo for the vault**
instead of a folder inside some monorepo. Vaults with thousands of notes are
fine; vaults inside repos with hundreds of thousands of files are not.

### Files that never sync

Some exclusions are by design and cannot be changed:

- **Dotfiles and dot-folders** on the phone (any name starting with `.`) are
  never scanned, so they are never pushed.
- **`.noksidian/`** is Noksidian's own state (see
  [Resetting sync state](#resetting-sync-state-safely)) — never pushed, and a
  same-named folder on the remote is never pulled.
- **`.obsidian/`, `.git`, `.github/`** on the remote stay remote-only. Your
  desktop Obsidian config and workflows never land on the phone — deliberately;
  they are useless there and can be large.
- **Remote files over 1,500,000 bytes** are skipped (logged during the pass).
  The E71 has a few MB of Java heap total; a 5 MB PDF would kill the app.

Everything else — markdown, images, `txt`, any other regular file — syncs both
ways. If a normal-looking file doesn't sync, check whether some *parent folder*
starts with a dot.

### Conflict markers appeared in a note

You opened a note and found this:

```
<<<<<<< phone
the line as you edited it on the E71
=======
the line as it was changed on GitHub
>>>>>>> github
```

This is not corruption — it is the **Keep both** conflict policy doing its job.
You and another device edited the *same lines* of the same note since the last
sync. Rather than pick a winner, Noksidian kept both versions: everything between
`<<<<<<< phone` and `=======` is your phone's version of the region; everything
between `=======` and `>>>>>>> github` is the remote version. Lines only one side
touched were merged silently — markers appear only around genuinely overlapping
edits.

Clean-up: open the note, **Edit**, keep the lines you want, delete the three
marker lines, **Save**. The cleaned note syncs like any other edit. (The merged
result including markers was already pushed to GitHub — that's intentional, so no
version is ever lost; you can also clean it up on the desktop.)

If you'd rather never see markers, change **Settings → Conflicts**:

- **Keep both** (default) — markers as above; nothing is ever lost.
- **Prefer phone** — overlapping regions take the phone's lines.
- **Prefer GitHub** — overlapping regions take the remote lines.

With either "prefer" policy the losing lines are discarded for that region, so
only switch if you understand the trade-off. The policy applies per conflicting
region, not per file — non-conflicting edits from both sides always merge.

---

## Encrypted vault problems

Background reading for all of these: [the encryption guide](encryption.md).

### "vault locked"

A sync pass ends with `vault locked` in the ticker, or a note shows a "locked"
placeholder instead of its content. Meaning: the vault contains encrypted files (or the
descriptor `_vault.nkv`), but the keys are not in memory — the vault was never
unlocked in this session. Sync deliberately refuses to run and encrypted files
refuse to open until it is.

Fix: restart Noksidian and enter the vault password at the **Vault password**
prompt. If no prompt appears yet ciphertext exists (for example, another device
encrypted the repo and a sync pulled `NKE1` files into a vault that has no
`_vault.nkv` yet), sync once more so `_vault.nkv` arrives, then restart — the
prompt keys off the descriptor. Tired of the prompt on a phone only you handle?
Answer **Yes** to "remember the password on this phone" after an unlock — with
[the security trade-off that implies](encryption.md#unlocking-remembering-and-forgetting).

### Wrong password

"That password does not open this vault." at the unlock prompt (or "Wrong
password." at the start of a Change password / Decrypt vault flow). The
password you typed does not match this vault's descriptor — this is detected
against a check value in `_vault.nkv` before any file is touched, so nothing
was read, written or harmed.

Check, in order:

- **Typos.** The field is masked (unlike the token field), so you cannot see
  what you typed. Retype slowly; watch for a stuck Fn/shift state on the E71
  keyboard — digits vs. letters is the classic miss.
- **Right vault, right password?** The password is *per vault*. If the vault
  was re-encrypted elsewhere (desktop `nokcrypt.py`, or another phone) with a
  new password, the *new* password is the one that opens it once the new
  `_vault.nkv` has synced over.
- **A stale remembered key** never causes this — an invalid stored key is
  ignored and you simply get the prompt.

Genuinely can't remember it? See
[Forgot the vault password](#forgot-the-vault-password) — and read it before
trying anything clever.

### `sync: mac mismatch <path>`

A specific file failed its integrity check during a sync pass (the same error
can appear when opening a note). The file's encrypted bytes do not verify under
the key you unlocked with. This is the tamper detection working as designed —
Noksidian will never hand you garbage decryption output — and the pass falls
back to safe per-file behavior for that path rather than merging unreadable
bytes.

Real-world causes, most likely first:

1. **Key mismatch between devices.** The vault password was changed (or the
   repo re-encrypted) on one device while another still holds ciphertext under
   the old key. Fix: make sure every device uses the *current* password — on
   the phone, if you unlocked with the old one, restart and unlock with the
   new; a re-baseline sync then converges everything. On the desktop,
   `python3 tools/nokcrypt.py cat <file>` tells you which password a given
   copy actually decrypts under.
2. **Two different vaults/passwords syncing to one repo.** Each `_vault.nkv`
   has its own salt and keys; ciphertext from vault A can never verify in
   vault B even with the same password string. One repo, one descriptor —
   don't mix.
3. **Actual corruption or truncation** — a dying memory card, an interrupted
   copy, a file mangled outside Noksidian. Restore the affected file from git
   history (`git log -- <path>` on the desktop, check out a good version,
   push), or from `.noksidian/trash/` if it landed there.

Nothing about a mac mismatch destroys data: the offending bytes stay where they
are, and the repo's history still holds every previously pushed version.

### Forgot the vault password

Straight answer: **the notes are unrecoverable without it.** There is no reset,
no recovery key, no back door — the password is the only thing the keys are
derived from, and nobody (not the app, not this project, not GitHub) can
decrypt the vault without it. The ciphertext in the repo is perfectly safe —
and perfectly unreadable. Before giving up, check every door that might still
be open:

- **A still-unlocked or remembering device.** If the phone still opens the
  vault (a remembered key, or the app has been running since an unlock), your
  notes are readable *right now* — get them out before anything resets that.
  Note that **Settings → Decrypt vault** will *not* help: it re-asks for the
  password. Instead, set **Encrypted sync scope** to *Phone only* and sync —
  plaintext lands in the repo, where a PC can clone it. Do this before
  touching **Forget password**, changing vaults, or reinstalling.
- **Scope was *Phone only*.** Then the repo is plaintext already — clone it on
  a PC and your notes are right there; only the phone-side copies are locked.
- **Git history.** If the vault lived in the repo *before* encryption was
  enabled (or during a plaintext-scope period), those old commits still
  contain readable versions of the notes as of that date.
- **Password managers, keyboards, muscle memory.** Worth an honest hour before
  declaring the data gone.

When all of that fails, the ciphertext is cryptographically out of reach. To
keep using the vault folder: delete the encrypted files and `_vault.nkv` (phone
File manager or card reader), point the repo at a fresh start, and pick a
password you store in a password manager this time.

---

## Phone problems

### Permission prompts on every action

Symbian asks "Allow application to read user data?" (or network access) on every
single file open or sync. That is the default security setting for unsigned
MIDlets — fix it once in the App Manager:

*Menu → Installations → App. mgr.* → select **Noksidian** → *Options → Settings*
(on some firmware: *Suite settings*) → set **Read user data**, **Edit user data**
and **Network access** to **Ask first time only**.

After that you'll confirm each permission once per app launch at most. If "Ask
first time only" is greyed out, your operator firmware locks it down for
untrusted apps; there is no in-app workaround.

### The app doesn't follow my phone's theme

**Switching the phone between light/dark mode (or any Symbian theme) does nothing to
Noksidian, in either direction.** This is by design, not a bug: every screen — library,
menus, dialogs, Settings, the editor, the reader — is drawn by the app itself on a bare
`Canvas`, not built from the phone's native list/form/text-box widgets. Since there are no
native widgets on screen, there is nothing for the system theme to recolor; Noksidian reads
its own **Settings → Theme** (*Light* / *Dark*) instead, and that is the only thing that
changes its colors.

**Font sizes are shown as "18 px", "24 px", etc. instead of Small/Medium/Large.** The
options in **Settings → Font size** are the exact pixel height the text renders at: the
phone's own three native font sizes (whatever heights they measure to on your specific
device), plus a few larger sizes built by scaling the largest native size by a whole
number (2x, 3x, 4x). Because the scale factor is always a whole number, every option stays
pixel-crisp — no soft, blurry, stretched-looking text at any size, unlike a plain image or
font resize would produce. Pick whichever "N px" reads best for you; the number is just a
measurement, not a setting you need to calculate yourself.

### Out of memory on big notes or images

The E71 gives Java a few MB of heap, so Noksidian enforces hard limits instead of
crashing:

- **Images over 400,000 bytes are not decoded.** The viewer shows an
  `image too large` placeholder instead of rendering it inline (the file still
  syncs and still opens on your other devices). Fix: resize before committing —
  on a PC, e.g. `magick photo.jpg -resize 640x480 photo.jpg`. Anything at or
  below screen size (320×240) is ideal.
- **Remote files over 1,500,000 bytes are skipped entirely** during sync (see
  [Files that never sync](#files-that-never-sync)).
- **Very large notes merge coarsely.** The 3-way merge falls back to whole-file
  resolution when a note exceeds 1,500 lines or 150,000 characters: instead of a
  line-by-line merge, one side wins whole (per your conflict policy) and the pass
  counts it as a conflict. Nothing is deleted from GitHub history, but line-level
  merging stops at that size.

If the app itself dies with an out-of-memory error, the usual culprit is many
large images referenced from one note. Split the note, shrink the images, and
restart the app (the per-note image cache is dropped when you leave a note).

Practical rule: keep notes under ~60 KB and images under ~100 KB and you will
never hit any of this.

### Note too large to edit

Opening a note for editing shows a "too large to edit" alert and drops you back
into the read-only viewer. Noksidian's editor caps at about 200,000 characters, a
guard against the phone's small Java heap rather than a platform limit. Notes beyond
the cap can still be *viewed*, searched, linked to and synced — just not edited on
the phone.

Fix: split the note into smaller ones on the desktop (they'll sync down), or trim
it there. Long daily-notes files and clipped web articles are the usual offenders.

### Weird characters / encoding

Noksidian reads and writes **UTF-8**, no exceptions. If you see garbage:

- **A stray character at the very start of a note** (often shown as a hollow box
  or `ï»¿`): the file was saved on Windows as "UTF-8 with BOM". Noksidian does
  not strip the byte-order mark, so it renders as one junk character at the top.
  It is harmless, but to get rid of it re-save the file as *UTF-8 without BOM*
  (Notepad++: *Encoding → UTF-8*; VS Code: change encoding in the status bar).
- **Accented characters turn into two junk characters each** (`Ã©` instead of
  `é`): the file isn't UTF-8 at all — it was saved as Windows-1252/Latin-1.
  Re-save as UTF-8.
- **Correct-looking text but empty boxes for some symbols**: the character is
  fine, the E71's fonts just have no glyph for it (exotic scripts, rare
  dingbats). Nothing to fix; the bytes are intact and sync correctly. Most
  emoji are not in this bucket any more — see
  [Emoji show as blank or missing](#emoji-show-as-blank-or-missing) if it's
  an emoji specifically.

Line endings are not a problem — the parser handles both `\n` and `\r\n`.

### Emoji show as blank or missing

Two different symptoms, two different causes:

- **Every emoji in every note is blank or missing**, not just a rare one: the
  installed JAR was built without the emoji glyph pack. `res/emoji/` (124
  strip PNGs + `index.bin`) must be present in the repo checkout before
  `./build.sh` runs — the build copies it into the jar unconditionally, but a
  jar built without that folder falls back to `nok.core.Emoji`'s permanent
  no-emoji mode: notes still open fine, emoji are just stripped silently, the
  same as before this feature existed. Fix: confirm `res/emoji/index.bin`
  exists, then rebuild. The jar size itself tells you which one you have — a
  jar with the pack is roughly 647 KB; one without it is roughly 219 KB.
- **A handful of specific symbols are blank**, everything else renders fine:
  expected — see
  [Weird characters / encoding](#weird-characters--encoding).

In **MicroEmulator only** (not the real E71), emoji drawn at a **Font size**
above the phone's native pixel heights (an upscaled "N px" option) look
slightly dimmed compared to the real device. This is the emulator's own
color-filter artifact on upscaled images, not a bug in the glyph pack — the
real E71 draws the same glyphs at full brightness at every size.

---

## Maintenance

### Resetting sync state safely

Sync bookkeeping lives inside the vault, in `.noksidian/`:

```
.noksidian/state.json   file -> blob sha (+ size/mtime for binaries) at last sync
.noksidian/base/        last-synced copy of every markdown file (the merge base)
.noksidian/trash/       local files removed because they were deleted remotely
```

To reset (e.g. after restoring the vault from a backup, or if state looks wrong):

1. Set **Settings → Auto sync** to **Off** and exit Noksidian (or just make sure
   no sync is running — the ticker is idle).
2. With the phone's File manager (or the card in a PC reader), delete
   `.noksidian/state.json` and the whole `.noksidian/base/` folder. Leave
   `trash/` alone if it holds anything you might want.
3. Start Noksidian, re-enable Auto sync, **Sync now**.

This is safe by design: with no state, the next pass treats every file as *first
contact*. Files identical on both sides pair up with no changes; files that exist
only on one side are copied over; files that differ are 3-way merged with an
empty base — under the default **Keep both** policy that means both versions are
preserved (with markers where they overlap). No data is destroyed; worst case is
some marker clean-up.

Do **not** delete `.noksidian/` while a sync pass is running.

### Moving a vault

The vault location is a setting, not baked in:

- **Switch to a different folder:** **Settings → Change vault**, navigate, **Use
  this folder**. Picking an empty folder means the next sync pulls the entire
  repo into it fresh.
- **Physically move the current vault** (say `E:/Notes` → `E:/Vault/Notes`): copy
  the folder *including the hidden `.noksidian/` directory* with the File manager
  or a card reader, then *Change vault* to the new location. Because
  `.noksidian/` travelled along, sync continues exactly where it left off — no
  re-download, no first-contact merge. If you forget `.noksidian/`, nothing is
  lost; the next pass simply re-baselines as described in the reset section
  above.

Prefer the microSD (`E:/`) over phone memory: more space, and you can service the
vault from a PC with a card reader.

### Battery and data usage

Every automatic pass makes at least one API call (the full recursive tree
listing) even when nothing changed, and that response grows with your repo size.
Tuning knobs, all in **Settings**:

- **Interval** — 5/15/30/60 minutes. 15 is the default; 60 is fine for a vault
  you mostly *read* on the phone. Edits still sync fast regardless: a save
  triggers a sync about 20 seconds after you stop saving.
- **Auto sync Off** — the manual regime. Nothing runs in the background; you
  press **Sync now** when you want it. Best battery and best on metered 3G.
- When the network is down, retries back off automatically (1 → 2 → 5 → 15
  minutes), so a dead WiFi link won't drain the battery hammering the radio.

On 3G remember the bridge is on your home LAN — sync only works where the phone
can reach the bridge machine, so on cellular there's no data usage *and* no sync
(unless you expose a TLS-terminating proxy publicly, which means your token in
plain HTTP over the internet — don't).

---

## General FAQ

**Is my token safe?**
Reasonably, if you follow the setup advice — but know the model. The token is
stored in the MIDlet's private RMS record store on the phone, unencrypted (J2ME
offers no keystore); it is not readable by other Java apps, but anyone with
physical access to an unlocked phone could see it in Settings. It is sent on
every API call, and on the phone→bridge hop that is **plain HTTP on your LAN**.
That's why you should use a **fine-grained PAT** restricted to the single vault
repo with only *Contents: Read and write* — worst case, an attacker on your LAN
can touch that one repo, nothing else. Revoke the token on GitHub if the phone is
lost.

**Can I use GitLab (or Gitea, Codeberg, ...)?**
No. Noksidian speaks the GitHub REST v3 API specifically — `git/trees`,
`git/blobs` and the `contents` endpoints, with GitHub's exact JSON shapes and
`Authorization: token` header. GitLab's API has different paths, payloads and
auth; pointing the API URL at it will just produce 404s. The only way it could
ever work is a proxy that *translates* the GitHub API to your host's API — the
bundled `ghproxy.py` does not do that, it only forwards to GitHub. GitHub is the
supported backend, full stop.

**Can I have multiple vaults?**
One vault at a time. There is a single set of settings (one vault path, one
owner/repo/branch/token), switchable via **Settings → Change vault**. Each vault
folder keeps its own `.noksidian/` sync state, so switching back and forth is
safe — but the GitHub settings are global, so if the two vaults sync to
different repos you must re-enter owner/repo each time you switch. For genuinely
parallel vaults, keep one of them desktop-only.

**A file disappeared from my phone — where did it go?**
If it was deleted on GitHub (or by another device), Noksidian moved your local
copy to `.noksidian/trash/<path>` instead of hard-deleting it. Fish it out with
the File manager if you still need it.

**Why does GitHub show one commit per file?**
That's the Contents API model — each pushed file is its own commit
(`Noksidian: update Daily/2026-07-01.md`). Chatty history, but every change is
individually attributable and revertable.

**Does sync work while I'm reading or editing?**
Yes — all sync I/O runs on a background worker thread. If a pass pulls a new
version of the note you currently have open, you'll see it after you reopen the
note; your in-progress edit is merged on the pass after you save.
