# Encrypted vaults

Noksidian can encrypt your vault with a per-vault password: every note and image is stored
as ciphertext on the phone (and, if you choose, in the GitHub repo too), and the app
decrypts on the fly once you unlock at startup. This document is the complete story: what
encryption protects and what it doesn't, the two sync scopes and how to pick one, the
enable / change-password / disable walkthroughs, the unlock flow, what "wrong password"
and "mac mismatch" mean, and the desktop companion tool `tools/nokcrypt.py` that lets a PC
read and write the same formats. The byte-exact format specification lives in
[CONTRACTS-CRYPTO.md](../CONTRACTS-CRYPTO.md); this page is the user-facing version.

## What encryption protects — and what it does not

**Protected: file contents at rest.** Every file in the vault — markdown, images, `.txt`,
anything — is stored as AES-256-CTR ciphertext with an HMAC-SHA256 integrity tag
(encrypt-then-MAC). That covers the copies on the phone's memory card, the merge-base
copies under `.noksidian/base/`, and — with the *Phone + GitHub* scope — the copies in the
GitHub repo. Tampering or corruption is *detected*, not silently decrypted: a modified
file fails its MAC check and is refused; you never get garbage text pretending to be your
note.

**Not protected: names and shape.** File and folder **names stay plaintext**, deliberately
— wikilinks like `[[Home lab]]` must resolve by filename without decrypting the whole
vault, and a repo full of opaque blob names would be unbrowsable. Someone holding your
memory card (or looking at the repo) can see the vault's folder tree, every file name,
every file size (ciphertext is plaintext + 54 bytes) and modification times. They cannot
read any contents without the password.

**One password per vault.** The password is turned into keys with PBKDF2 and never stored
anywhere by default (see [the unlock flow](#unlocking-remembering-and-forgetting) for the
opt-in exception). There is **no recovery** — no reset, no back door. Forget the password
and the ciphertext is permanently unreadable; see
[the troubleshooting entry](troubleshooting.md#forgot-the-vault-password) before you rely
on your memory alone.

The descriptor file `_vault.nkv` (60 bytes) sits at the vault root: it holds the KDF salt
and iteration count plus a check value that lets the app detect a wrong password *before*
touching any file. It contains no key material, is never itself encrypted, syncs to the
repo as a plain binary file, and is hidden from the Library. Leave it alone — deleting it
does not decrypt anything, it just locks you out of the ciphertext.

## The two scopes: which one do I want?

**Settings → Encrypted sync scope** decides what the *repo* holds. The phone-side files
are ciphertext either way.

| | **Phone + GitHub** (default) | **Phone only** |
|---|---|---|
| Config value | `crypt.scope` = `all` | `crypt.scope` = `local` |
| Files on the phone | ciphertext | ciphertext |
| Files in the GitHub repo | **ciphertext** | **plaintext** |
| Can GitHub (or anyone with repo access) read your notes? | No | Yes |
| Desktop Obsidian on a clone of the repo | needs `nokcrypt.py decrypt` first (and `encrypt` before pushing) — see [the desktop workflow](#the-desktop-workflow-obsidian-on-an-encrypted-repo) | just works — the repo is ordinary markdown |
| GitHub's web UI, diffs, history | opaque binary blobs | readable markdown diffs |
| Pick it when… | you don't trust the repo host / the repo is shared / the notes are sensitive everywhere | you only care about the phone or memory card being lost or stolen |

In short: *Phone + GitHub* extends the protection to the cloud at the cost of desktop
convenience; *Phone only* keeps the repo a normal, Obsidian-friendly markdown repo and
protects just the physical device. You can switch the scope in Settings at any time — the
next sync passes write files in the new form as they change (switching does not itself
rewrite the whole repo).

Merging still works in both scopes: sync decrypts base/local/remote to plaintext in
memory, runs the normal 3-way merge, and re-encrypts the result for the local write (and
for the push, when the scope is *Phone + GitHub*). Conflict policies, markers, trash — all
[sync behavior](sync.md) is unchanged.

## Enabling encryption

*Library → Options → Settings*, then the **Set password** command (shown while encryption
is off):

1. Type the password twice (minimum 4 characters — but use a real passphrase; the
   [security notes](#honest-security-notes) explain why length matters here).
2. Noksidian pauses sync, derives your keys ("Deriving keys…" — a few seconds of PBKDF2),
   and writes `_vault.nkv` *first*, so a descriptor always exists before any ciphertext
   does (crash-safe ordering).
3. The **migration walk** runs on a progress screen — "Encrypting 12/87 …" — re-writing
   every file in the vault into encrypted form. Files already encrypted are skipped
   (nothing is ever double-encrypted; the app sniffs the `NKE1` magic bytes).
4. The sync baseline (`.noksidian/base/` and `state.json`) is cleared, forcing a **full
   re-baseline**: the next pass treats every file as first contact and pushes the new
   ciphertext (or, with scope *Phone only*, re-verifies the plaintext repo). A remembered
   key (`crypt.dk`, if any) is cleared too.
5. Sync restarts and a pass is requested immediately. A summary alert reports
   `N encrypted, M skipped, K failed`.

Expect the first sync after enabling with *Phone + GitHub* scope to push the entire vault
— every file's bytes changed. That is one commit per file, as always.

## Changing the password

*Settings → Change password*: old password, new password, confirm. The old password is
verified against the descriptor before anything is touched — a wrong old password stops
the flow with no changes. Then a single walk decrypts each file with the old keys and
re-encrypts it with the new ones ("Encrypting 12/87 …"), and the **new descriptor is
written last** — so if the phone dies mid-walk, the vault is healed by simply re-running
*Change password* with the old password again. The baseline reset and forced re-sync
happen exactly as for enabling; with *Phone + GitHub* scope the whole repo is re-pushed
under the new key.

## Disabling encryption

*Settings → Decrypt vault*: enter the password, and the walk decrypts every file in place
("Decrypting 12/87 …"). `_vault.nkv` is deleted **last**, after all files are plaintext
again. Baseline cleared, full re-sync follows — with *Phone + GitHub* scope this pushes
the plaintext over the repo's ciphertext, so make sure that is what you want before you
run it.

## Unlocking, remembering, and forgetting

When Noksidian starts and finds `_vault.nkv` in the vault, it shows a masked **Vault
password** box before anything else. **Unlock** derives the keys (a "Unlocking…" wait of a
few seconds — that's 8192 rounds of PBKDF2 on a 2008 CPU, and it is *supposed* to be
slow); **Exit** quits the app — a locked vault is unusable by design. A wrong password
shows "That password does not open this vault." and asks again. The keys live in RAM only
and evaporate when the app exits.

After a successful unlock, Noksidian asks once: **"Remember the password on this
phone?"**

- **No** (the safe answer): you type the password at every app start. Nothing key-like is
  ever written to storage.
- **Yes**: the derived key is stored in the app's private RMS store on the phone
  (config key `crypt.dk`), and startup skips the prompt. Understand the trade: this
  **reduces the vault's security to device-access level** — anyone who can run Noksidian
  on your unlocked phone can read every note, exactly as if the vault were not encrypted.
  The memory card alone is still safe (RMS lives in phone memory, not on the card), but a
  stolen *phone* is not. It also does not weaken the repo: the stored key never syncs.

**Forget password** (a Settings command that appears while a key is remembered) deletes
the stored key, returning you to the prompt-at-startup regime. Changing the password or
decrypting the vault clears it automatically.

## "Wrong password" and "mac mismatch"

Two different errors, two different meanings:

- **Wrong password** — the descriptor's check value did not match the password you typed.
  Nothing was read or written; just retype it. You'll see it at unlock and at the start of
  the Change/Decrypt flows. (The check is in `_vault.nkv`, so this is detected instantly,
  before any file is touched.)
- **mac mismatch** — a specific *file's* integrity tag failed: the ciphertext was
  modified, truncated, or encrypted with a *different key* than the one you unlocked with.
  Noksidian refuses to decrypt it — this is the tamper detection working, never a silent
  pile of garbage. In the reader you get a placeholder; in the ticker sync logs
  `sync: mac mismatch <path>` and falls back to safe per-file behavior instead of merging
  unreadable bytes. The usual real-world cause is not an attacker but a **key mismatch
  between devices** — e.g. the repo was re-encrypted with a new password while the phone
  still held ciphertext under the old one. See
  [the troubleshooting entry](troubleshooting.md#sync-mac-mismatch-path) for the fix
  ladder.

## nokcrypt.py — the desktop companion tool

`tools/nokcrypt.py` implements the exact same formats on a PC: Python 3, standard library
only (it uses the `cryptography` package as a fast AES path when installed, and falls back
to an embedded pure-Python AES when not — same bytes either way). Wrong password or
corrupt input exits with status 2 and a one-line message, never a traceback.

```
usage: nokcrypt [-h] COMMAND ...

  init      create _vault.nkv in DIR
  encrypt   encrypt all vault files in DIR (in place)
  decrypt   decrypt all vault files in DIR (in place)
  cat       decrypt one file to stdout
  enc       encrypt a single file in place
  dec       decrypt a single file in place
  selftest  run all contract test vectors
```

Every command that needs the password prompts for it interactively (input hidden);
`--password PW` skips the prompt for scripting — but remember it lands in your shell
history.

**`init` — create a vault descriptor from the desktop:**

```
python3 tools/nokcrypt.py init ~/vault
python3 tools/nokcrypt.py init ~/vault --iterations 20000   # stronger KDF (default 8192)
```

Creates `~/vault/_vault.nkv` (refusing to overwrite an existing one) with a random salt
from `os.urandom`. Use it to encrypt a vault desktop-first: `init`, then `encrypt`, then
commit and push — the phone will pull the descriptor and ciphertext and prompt for the
password. Higher `--iterations` makes password guessing proportionally harder *and* makes
the phone's unlock proportionally slower; 8192 is the phone-friendly default.

**`encrypt` / `decrypt` — whole-vault, in place, recursive:**

```
python3 tools/nokcrypt.py encrypt ~/vault
python3 tools/nokcrypt.py decrypt ~/vault
```

Walks every file under the directory, skipping `.noksidian/`, `.obsidian/`, anything
starting with `.git`, and `_vault.nkv` itself, and skipping files already in the target
state (so both commands are safely re-runnable after an interruption). Writes are atomic
(temp file + rename); a per-file summary and a final
`encrypted 87 file(s), skipped 2, failed 0` line are printed. A wrong password stops
before any file is touched — it is checked against the descriptor first.

**`cat` — read one note without touching the file:**

```
python3 tools/nokcrypt.py cat ~/vault/Daily/2026-07-02.md
python3 tools/nokcrypt.py cat ~/vault/Daily/2026-07-02.md --password hunter2 | less
```

Decrypts to stdout. The vault root (for `_vault.nkv`) is found by walking up from the
file's directory. A file that is not encrypted passes through unchanged, with a note on
stderr — same magic-sniffing semantics as the app.

**`enc` / `dec` — one file, in place:**

```
python3 tools/nokcrypt.py dec ~/vault/Projects/antenna.md    # decrypt, edit it, then:
python3 tools/nokcrypt.py enc ~/vault/Projects/antenna.md
```

Handy for touching a single note without a whole-vault walk. `enc` refuses to
double-encrypt; `dec` refuses files that are not `NKE1`. (Both also accept a hidden
`--vault DIR` when the file lives outside a directory tree containing `_vault.nkv`, and
`enc` accepts a hidden `--iv HEX` — those exist for the cross-implementation test suite,
not for daily use.)

**`selftest` — trust, then verify:**

```
python3 tools/nokcrypt.py selftest
```

Asserts every test vector from the contract — SHA-256, HMAC (RFC 4231), PBKDF2, the
FIPS-197 AES block, the SP 800-38A CTR vectors, descriptor round-trips, wrong-password
and corruption rejection, and a full bit-flip sweep (every single flipped bit in a
ciphertext must be caught by the MAC). Ends with `ALL SELFTESTS PASS`. Run it once on any
machine before trusting it with your vault.

### The desktop workflow: Obsidian on an encrypted repo

With scope *Phone + GitHub* the repo holds ciphertext, so desktop Obsidian needs a
decrypt/encrypt sandwich around editing sessions:

```
git pull
python3 tools/nokcrypt.py decrypt ~/vault      # repo clone -> readable markdown
# ... edit in Obsidian ...
python3 tools/nokcrypt.py encrypt ~/vault      # back to ciphertext
git add -A && git commit -m "desktop edits" && git push
```

Never commit while the clone is decrypted — plaintext would land in git history, and
history is forever. If that trade-off sounds tedious, that is exactly what the
*Phone only* scope is for: plaintext repo, no sandwich, and only the phone-side copies
are encrypted.

## Format summary

The full byte-level specification with required test vectors is
[CONTRACTS-CRYPTO.md](../CONTRACTS-CRYPTO.md); the shape in one table:

| Piece | Value |
|---|---|
| Key derivation | PBKDF2-HMAC-SHA256(password, 16-byte salt, iterations) → 64 bytes: 32-byte AES key + 32-byte HMAC key |
| Default iterations | 8192 (stored in the descriptor; the desktop tool can pick higher) |
| Descriptor `_vault.nkv` | 60 bytes: magic `NKV1`, version, KDF id, iterations, salt, and an HMAC check value for instant wrong-password detection |
| Encrypted file | magic `NKE1`, version, flags, 16-byte IV, AES-256-CTR ciphertext, HMAC-SHA256 over everything before it (encrypt-then-MAC) |
| Size overhead | 54 bytes per file (22-byte header + 32-byte MAC) |
| Decrypt order | MAC verified first, in constant time; only then is anything decrypted |
| IVs | phone: derived deterministically, SIV-style, from the MAC key, plaintext hash, path, time and a counter (CLDC has no secure RNG); desktop: `os.urandom`. Both are format-legal |
| Interop | `tools/nokcrypt.py` and the phone produce byte-identical output for the same key, IV and plaintext — enforced by cross-implementation tests |

## Honest security notes

This is real, tested crypto (AES-256, HMAC-SHA256, PBKDF2, encrypt-then-MAC, constant-time
comparisons) built within the limits of a 2008 phone. Know the limits:

- **8192 PBKDF2 iterations is low by 2026 standards** (OWASP suggests hundreds of
  thousands for PBKDF2-SHA256). It is what an E71's ARM11 can do in a few seconds at
  unlock; a desktop GPU rig guesses against it fast. The fix is in your hands: **use a
  long passphrase**, not a short password — every extra word costs the attacker more than
  any iteration count could. If the vault is desktop-initialized and phone unlock time
  doesn't bother you, `nokcrypt.py init --iterations` lets you raise it.
- **IVs are deterministic on the phone** (SIV-style, derived from the plaintext hash,
  path, time and a session counter) because CLDC 1.1 has no secure random source. The
  practical consequence is minimal — a repeat could only happen for identical
  plaintext at the same path at the same millisecond within one session — but it is a
  weaker guarantee than true random IVs, so it is stated here rather than hidden.
- **Metadata is visible.** A stolen memory card yields the folder tree, all file and
  folder names, file sizes and mtimes, plus `_vault.nkv` (salt and iteration count — a
  perfect offline password-guessing target, which is again why the passphrase matters).
  Contents, including every note body and image, stay unreadable without the password.
- **"Remember on this phone" trades security for convenience** — device-access level, as
  described [above](#unlocking-remembering-and-forgetting). Card-only theft stays safe;
  phone theft does not.
- **The GitHub token is a separate story.** It lives unencrypted in the app's RMS store
  and crosses your LAN in plain HTTP to the bridge regardless of vault encryption — see
  [the sync guide's security caveats](sync.md#security-caveats--read-this). Vault
  encryption protects your *notes*, not your *token*.
- **No deniability, no key rotation history.** One key per vault; changing the password
  re-encrypts everything under the new key, but old ciphertext remains in git history
  (encrypted under the old key — still safe as long as the old password was strong).
