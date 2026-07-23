# GitHub Sync

Noksidian keeps your vault and a GitHub repository converged — automatically, in both
directions, with real 3-way merging when both sides changed the same note. This document
is the complete reference: what sync is (and is not), how to set it up from a blank
GitHub account to a syncing phone, exactly what every pass does to every file, and how to
fix it when it goes wrong.

```
 Nokia E71 (WLAN)              your LAN                     the internet
┌──────────────┐  plain HTTP  ┌─────────────┐  HTTPS/TLS 1.2+  ┌────────────────┐
│  Noksidian   │ ───────────► │ ghproxy.py  │ ───────────────► │ api.github.com │
│   MIDlet     │ ◄─────────── │   :8180     │ ◄─────────────── │   REST v3      │
└──────────────┘              └─────────────┘                  └────────────────┘
```

## The concept: REST, not git

Noksidian is **not a git client**. There is no clone on the phone, no local history, no
branches, no staging area. Instead it talks to GitHub's plain REST API:

- `GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1` — list every file (blob)
  in the branch, with its blob SHA and size.
- `GET /repos/{owner}/{repo}/git/blobs/{sha}` — download one file (base64).
- `PUT /repos/{owner}/{repo}/contents/{path}` — create or update one file. This is the
  Contents API: **every write is its own commit**.
- `DELETE /repos/{owner}/{repo}/contents/{path}` — delete one file (also one commit).

Consequences you should know up front:

- **One commit per changed file.** Save five notes, get five commits. History is chatty
  but complete — and because everything lands in git, *nothing you push ever destroys
  data on GitHub*; the previous version is always one commit back.
- Blob SHAs (not timestamps) are the source of truth for "did the remote change".
- The phone never downloads history, only the current state of one branch.

### What sync does NOT do

- No branches, tags, rebases, or commit batching. One branch, configured in Settings.
- No Git LFS, no submodules; only regular files (tree entries of type `blob`).
- Repos whose recursive tree listing is **truncated by GitHub** (roughly 100,000 files)
  are rejected — the pass fails with `repo too large`.
- Remote files **larger than 1,500,000 bytes are skipped** (never pulled, left alone).
- `.git`, `.github/`, `.obsidian/` and `.noksidian/` paths in the repo are ignored —
  they stay remote-only. On the phone, `.noksidian/` and anything starting with a dot
  is never scanned, so it is never pushed.
- Only `.md` / `.markdown` files are merged. Everything else — images **and `.txt`** —
  is treated as binary (see [Binary files](#binary-files-images-txt-everything-not-markdown)).

## Step 1: create a fine-grained PAT

Sync authenticates with a personal access token. Use a **fine-grained** token scoped to
the single vault repo — this matters for security later (the bridge hop is cleartext).

1. On github.com, click your avatar (top right) → **Settings**.
2. Left sidebar, bottom: **Developer settings**.
3. **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
4. Fill in:
   - **Token name**: e.g. `noksidian-e71`.
   - **Expiration**: your call — note that sync dies with HTTP 401 when it expires.
   - **Resource owner**: you (or the org owning the vault repo).
   - **Repository access**: **Only select repositories** → pick the vault repo. Just
     that one.
   - **Permissions → Repository permissions → Contents: Read and write.**
     (GitHub adds *Metadata: Read-only* automatically. Nothing else is needed.)
5. **Generate token** and copy the `github_pat_…` string. You will type or beam it into
   the phone's Settings; the token field is deliberately a plain visible text field,
   because Symbian's masked input breaks pasting.

## Step 2: the TLS bridge

### Why the phone cannot talk to GitHub directly

`api.github.com` requires **TLS 1.2 or newer**. The E71's Symbian TLS stack tops out at
**TLS 1.0** — no amount of configuration on the phone fixes that; the ciphers and
protocol versions simply do not exist in its firmware. So a direct
`https://api.github.com` API URL will fail to handshake.

The fix is a tiny bridge on your LAN: the phone speaks plain HTTP to a machine you own,
and that machine speaks modern TLS to GitHub.

### ghproxy.py

The repo ships one at `tools/ghproxy.py`. It needs nothing but Python 3 — no pip, no
config. It forwards every request verbatim (method, path, headers including
`Authorization`, body) to `https://api.github.com` and relays the response back.

```
python3 tools/ghproxy.py                    # listens on 0.0.0.0:8180
python3 tools/ghproxy.py 9000               # custom port
python3 tools/ghproxy.py --secret WORD      # require a /WORD/ path prefix (see below)
```

On start it prints the reminder:

```
Noksidian GitHub bridge on 0.0.0.0:8180 -> https://api.github.com
Usage: python3 ghproxy.py [port]                 (open bridge, default port 8180)
       python3 ghproxy.py [port] --secret WORD   (only /WORD/... paths forwarded)
Point the phone's Settings -> API URL at http://<this-machine-LAN-ip>:8180
```

With `--secret WORD`, only requests whose path starts with `/WORD/` are forwarded (the
prefix is stripped before the upstream call); everything else is answered with
`403 {"message":"forbidden"}`. The phone needs no app change — just append the word to
the API URL: `http://<machine-ip>:8180/WORD`. On a trusted home LAN the open bridge is
fine; the secret matters the moment the bridge is reachable from the internet — see
[Sync over mobile data](#sync-over-mobile-data).

Then in Noksidian → **Settings** → **API URL** enter `http://192.168.1.50:8180`
(your machine's LAN IP, not a hostname — the phone's DNS can be flaky). Everything
else — owner, repo, token — stays exactly as it would for direct access.

If the upstream is unreachable, the bridge answers `502` with a JSON body like
`{"message":"proxy error: …"}`, which Noksidian surfaces as `HTTP 502 proxy error: …`.

### Run it permanently: systemd unit

On a Pi, NAS or home server, drop this into `/etc/systemd/system/ghproxy.service`:

```ini
[Unit]
Description=Noksidian GitHub TLS bridge
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 /opt/noksidian/ghproxy.py 8180
Restart=on-failure
RestartSec=5
DynamicUser=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes

[Install]
WantedBy=multi-user.target
```

```
sudo cp tools/ghproxy.py /opt/noksidian/
sudo systemctl enable --now ghproxy
```

### Security caveats — read this

- The **phone → bridge hop is plain, unencrypted HTTP**. Your GitHub token crosses your
  LAN (and your WLAN) in cleartext on every request. That is the whole reason step 1
  insists on a **fine-grained PAT scoped to the one vault repo with Contents
  read/write only**: if the token leaks, the blast radius is that repo's contents, not
  your account.
- Run the bridge **only on a network you trust** (your home LAN with WPA2+ WiFi). Do
  not port-forward 8180 to the internet as-is — if you need sync away from home, use
  `--secret` and read [Sync over mobile data](#sync-over-mobile-data) first.
- Without `--secret`, the bridge is an open relay to `api.github.com` for anyone who
  can reach the port — it holds no credentials itself, but firewall it to your LAN
  anyway.
- Expired token beats leaked token: give the PAT an expiration and rotate it.

### Alternative: nginx or Caddy

Any TLS-1.2-terminating reverse proxy pointed at `https://api.github.com` works
identically. Keep the listener **plain HTTP** — the phone cannot handshake modern TLS,
which is the point of the exercise.

nginx:

```nginx
server {
    listen 8180;
    location / {
        proxy_pass              https://api.github.com;
        proxy_set_header        Host api.github.com;
        proxy_ssl_server_name   on;
        proxy_http_version      1.1;
        proxy_set_header        Connection "";
    }
}
```

Caddy:

```
http://:8180 {
    reverse_proxy https://api.github.com {
        header_up Host api.github.com
    }
}
```

## Sync over mobile data

At home, phone and bridge share a LAN and everything above just works. Away from home,
the E71 can sync over 3G/GPRS — Noksidian doesn't care which bearer carries its HTTP —
but then the mobile network has to be able to reach your bridge, so the bridge must be
**internet-reachable**.

One thing first: **choosing WiFi vs mobile data is the phone's job, not Noksidian's.**
Symbian either prompts you for an access point on the first connection or uses the
default destination/AP configured under *Menu → Tools → Settings → Connection* on the
E71. Noksidian just opens an HTTP connection to the API URL; the OS decides how it
travels.

### Making the bridge reachable

Two workable options:

1. **Port-forward on your home router**: forward WAN port 8180 to the bridge machine's
   LAN IP, port 8180. If your ISP doesn't give you a stable address, use a dynamic-DNS
   hostname.
2. **A tiny VPS**: the cheapest tier of any provider runs `ghproxy.py` with room to
   spare (it's stdlib Python, no dependencies). Copy the script over and use the same
   systemd unit as above.

### Always run an internet-facing bridge with `--secret`

Without a secret, an exposed bridge is an anonymous open relay to `api.github.com` for
the entire internet. With `--secret`, only paths under your secret word are forwarded;
everything else — including every scanner probing `/` — gets a flat
`403 {"message":"forbidden"}`.

Pick a long random word (it must be non-empty and contain no `/`):

```
python3 tools/ghproxy.py 8180 --secret "$(openssl rand -hex 8)"
```

or with a fixed word:

```
python3 tools/ghproxy.py 8180 --secret x7rk2mqv
```

The banner tells you exactly what to type on the phone:

```
Noksidian GitHub bridge on 0.0.0.0:8180 -> https://api.github.com
Usage: python3 ghproxy.py [port]                 (open bridge, default port 8180)
       python3 ghproxy.py [port] --secret WORD   (only /WORD/... paths forwarded)
Secret prefix enabled: only paths under /x7rk2mqv/ are forwarded; all else -> 403
Enter this API URL on the phone (Settings -> API URL):
    http://203.0.113.7:8180/x7rk2mqv
```

So the phone's **API URL takes the form `http://host:8180/WORD`** — e.g.
`http://203.0.113.7:8180/x7rk2mqv` or `http://vault.example.com:8180/x7rk2mqv`. (A real
public DNS name is fine here; the "raw IP only" advice earlier is about LAN hostname
resolution, which mobile carriers don't have a problem with.) Nothing else changes on
the phone: the GitHub client keeps whatever path is in the API URL, so the secret
prefix rides along on every request, and the bridge strips it before forwarding.

### The honest risk paragraph

Everything in the [LAN security caveats](#security-caveats--read-this) applies here,
amplified: your GitHub token — and the full text of every note you sync — rides **plain,
unencrypted HTTP** across the carrier network and the public internet on every request.
The secret word is an *access gate* for the bridge, **not encryption**: it stops
strangers from using your relay, it hides nothing from anyone positioned to capture the
traffic. Live with that honestly:

- Use a **fine-grained PAT scoped to the single vault repo** with *Contents: Read and
  write* only, and give it an expiration. If it leaks, the blast radius is that one
  repo's contents, and it dies on schedule anyway.
- Don't put anything in this vault you couldn't stand a network middlebox reading.
- On a VPS, add an IP allowlist in the firewall if your carrier's ranges are practical
  to pin down; at minimum, watch the bridge's stderr log for surprise clients.
- **Prefer WiFi + the LAN bridge whenever you're home.** The internet exposure is for
  travel; some people only enable the router port-forward while actually traveling.

Data usage, for the record, is modest: a no-op pass is a single tree-listing request,
and a busy pass adds roughly one request per changed file.

## Step 3: Settings

Open the Library → **Options** → **Settings**. The fields, their stored config keys and
defaults:

| Settings field | Config key      | Default                  | Notes |
|----------------|-----------------|--------------------------|-------|
| Owner          | `gh.owner`      | *(empty)*                | GitHub user or org name |
| Repo           | `gh.repo`       | *(empty)*                | the vault repository |
| Branch         | `gh.branch`     | `main`                   | older repos may default to `master` |
| Token          | `gh.token`      | *(empty)*                | the fine-grained PAT; shown in plain text so paste works on Symbian |
| API URL        | `gh.api`        | `https://api.github.com` | set to your bridge, e.g. `http://192.168.1.50:8180` |
| Auto sync      | `sync.auto`     | On (`1`)                 | governs the startup sync and the interval timer |
| Interval       | `sync.interval` | 15 min                   | choices: 5 / 15 / 30 / 60 minutes |
| Conflicts      | `sync.strategy` | Keep both (`both`)       | *Keep both* / *Prefer phone* / *Prefer GitHub* |

Commands on the form: **Save** (persists everything, immediately), **Test** (runs a
connectivity/auth check against the API in the background and shows the result — use it
after every settings change), **Change vault**, **Back**.

Sync considers itself configured only when **owner, repo and token are all non-empty**;
until then every pass ends immediately with "not configured".

## When syncs happen

All sync work runs on one background worker thread; the UI never blocks on the network.

- **Startup** — with Auto sync On, an initial pass runs about **5 seconds** after the
  vault opens, then repeats on the interval timer (5/15/30/60 min).
- **After saving a note** — every save schedules a pass **~20 seconds after the last
  save** (debounced: edit five notes in a burst, get one pass).
- **Manually** — Library → **Options** → **Sync now**.
- **Retry with backoff** — a failed pass (network down, HTTP error) reschedules itself
  at **60 s → 2 min → 5 min → 15 min**, staying at 15 min until a pass succeeds, which
  resets the ladder. **Sync now** wakes a sleeping retry immediately.
- **Coalescing** — if a pass is requested while one is running, a pending flag is set
  and the worker runs one more pass right after. Requests never queue up beyond that.

Progress lines ("pulling Daily/2026-07-01.md", …) show in the Library ticker. Each pass
ends with a summary such as:

```
±3 pulled, 1 pushed, 1 merged
```

## Inside `.noksidian/`

Sync keeps its bookkeeping in a hidden folder at the vault root. It is never pushed to
GitHub, and a same-named folder in the repo is never pulled.

```
Vault/
└── .noksidian/
    ├── state.json          what was known at the last sync
    ├── base/               last-synced copy of every markdown file (the merge base)
    │   └── Daily/2026-07-01.md
    └── trash/              local files displaced by remote deletions (never auto-emptied)
        └── old-note.md
```

`state.json` maps every synced path to the blob SHA GitHub reported for it, plus — for
binary files — the local size and mtime recorded at that moment:

```json
{
  "files": {
    "Daily/2026-07-01.md": {"sha": "a94a8fe5cc..."},
    "img/antenna.png":     {"sha": "b3c1e2f0aa...", "bin": {"sz": 48211, "mt": 1751371200000}}
  },
  "last": 1751374800000
}
```

- **Markdown entries** carry only the SHA; the merge base is the byte-exact copy under
  `base/<path>`. "Did I change this note locally?" is answered by comparing the vault
  file's bytes against its base copy — no timestamps involved.
- **Binary entries** carry the SHA plus recorded size/mtime; "changed locally?" means
  size or mtime differ from the record.
- The file is rewritten at the end of every pass **and after every 10 mutations**
  mid-pass, so a battery pull mid-sync loses at most a few records — the next pass
  re-derives the rest safely.

Delete `state.json` and `base/` and the next pass behaves like first contact for every
file (see the table below) — safe, but expect merge activity.

## The decision table

Each pass: list the remote tree, scan local files, load `state.json`, then classify
**every path in the union of the three**. This is the complete rulebook:

| # | Local | Remote | In state? | Meaning | Markdown action | Binary action |
|---|-------|--------|-----------|---------|-----------------|---------------|
| 1 | —     | yes    | no        | new on GitHub | **Pull**: write file + base copy, record SHA | **Pull**: write file, record SHA + size/mtime |
| 2 | —     | yes    | yes       | you deleted it on the phone | **Delete on GitHub** (`Noksidian: delete …`), drop state | same |
| 3 | yes   | —      | no        | new on the phone | **Push** (create), record | same |
| 4 | yes   | —      | yes       | deleted on GitHub | move local file to `.noksidian/trash/<path>`, drop state + base | move to trash, drop state |
| 5 | yes   | yes    | no        | first contact (both exist, never synced) | `merge3(base="", local, remote)` per policy; write merged locally, push it (with remote SHA) | keep local, save remote bytes as sibling `name (remote).ext`, push local (with remote SHA) |
| 6 | yes   | yes    | yes, remote SHA **unchanged** | only the phone may have changed | local bytes == base copy → nothing; else **push**, update base + state | size/mtime match record → nothing; else **push** |
| 7 | yes   | yes    | yes, remote SHA **changed** | GitHub changed; phone maybe too | local == base → **pull** (overwrite local + base); local != base → pull remote, **3-way merge**, write merged locally **and push it back**, base := merged | local unmodified → **pull**; modified → save remote as `name (remote).ext`, push local |

Notes on the table:

- Row 4: remote deletions **never hard-delete** on the phone — the file goes to
  `.noksidian/trash/<path>`. Empty the trash yourself with the phone's File manager.
  Local deletions (row 2) do delete on GitHub, but git history keeps the file.
- Row 5, markdown: with an empty base, identical files converge silently; differing
  files are treated as a both-sides change and resolved by your conflict policy.
- Row 5/7, the `name (remote).ext` sibling is an ordinary vault file — the *next* pass
  sees it as "new on the phone" (row 3) and pushes it, so both versions end up in the
  repo and on all devices.
- Row 7, markdown merge: the merged text is pushed immediately (using the remote SHA it
  was merged against), so phone and repo converge in the same pass.
- Every push/pull updates `state.json`, keeping the next pass a no-op until something
  actually changes.
- Any operation-level I/O error **aborts the whole pass**; already-completed operations
  are recorded, the failure is reported, and the backoff retry finishes the job.

## 3-way merge and the conflict policies

Markdown merging is a real line-based 3-way merge, like `git merge`:

1. Split base, local (phone) and remote (GitHub) into lines.
2. Align base↔local and base↔remote with an LCS diff.
3. Walk the chunks: a change on one side only is taken; the identical change on both
   sides is taken once; **different changes to the same region are a conflict**,
   resolved by the policy from Settings → *Conflicts*:

| Settings choice | Config value | Behavior on a conflicting hunk |
|-----------------|--------------|--------------------------------|
| Keep both       | `both`       | emit git-style conflict markers with both versions — nothing is ever lost |
| Prefer phone    | `local`      | keep the phone's lines |
| Prefer GitHub   | `remote`     | keep GitHub's lines |

Whatever the policy, non-conflicting changes from *both* sides always survive, and the
merged result is written locally *and* pushed, so every device converges on it.

### Example: clean merge (no policy involved)

Base (last synced):

```markdown
# Shopping
- milk
- eggs
```

On the phone you change line 2; on GitHub (desktop Obsidian) someone appends a line.
Merged output — both edits, no conflict:

```markdown
# Shopping
- oat milk
- eggs
- coffee beans
```

### Example: KEEP_BOTH marker output

Both sides edit the **same** line. With *Keep both*, the merged note (written to the
phone and pushed to GitHub) contains:

```markdown
# Shopping
<<<<<<< phone
- oat milk
=======
- almond milk
>>>>>>> github
- eggs
```

The markers are always `<<<<<<< phone`, `=======`, `>>>>>>> github`. Clean them up on
whichever device is more comfortable — the phone's editor works, but desktop Obsidian
is nicer; either way the fix syncs back like any other edit.

### Oversized-note fallback

The diff is skipped for any input over **1,500 lines or 150,000 characters** (the E71
has a few MB of heap; an LCS table over that would not fit). In that case the merge
"falls back": the result is the **whole local file** (or the whole remote file when the
policy is *Prefer GitHub*), flagged as a conflict. The overwritten side is not lost —
it is one commit back in the repo history.

## Binary files (images, .txt, everything not markdown)

Anything that is not `.md`/`.markdown` never goes through the merge:

- Change detection is **recorded size + mtime** vs. the current file (local side) and
  blob SHA (remote side).
- When both sides changed — or on first contact — **your local copy wins the repo**,
  and the remote copy is preserved next to it as:

  ```
  vault-map.png          <- yours, pushed
  vault-map (remote).png <- GitHub's version, saved as a sibling
  ```

  Pattern: base name + ` (remote)` + original extension. The sibling is then synced as
  a normal new file, so it appears everywhere. Keep the one you want, delete the other.

## Limits and skipped things, in one place

| Limit | Value | What happens |
|-------|-------|--------------|
| Remote file size | > 1,500,000 bytes | never pulled; skip is logged; file stays remote-only |
| Repo tree listing | truncated by GitHub (~100k files) | pass fails: `repo too large` |
| Merge input | > 1,500 lines or > 150,000 chars | no diff; whole-file fallback per policy, marked as conflict |
| Remote paths | `.noksidian/`, `.git`, `.github/`, `.obsidian/` | ignored, stay remote-only |
| Local paths | `.noksidian/`, any name starting with `.` | never scanned, never pushed |

## Commit messages

Every write is one commit, message format `Noksidian: update|add|delete <path>`:

```
Noksidian: update Daily/2026-07-01.md
Noksidian: add Projects/antenna.md
Noksidian: delete Inbox/old-idea.md
```

`git log --oneline --author-date-order` on the desktop gives you a readable journal of
what the phone did and when.

## Troubleshooting

First move for any problem: **Settings → Test**. It checks connectivity, auth and repo
access in one shot and shows a human-readable result.

| Symptom | Cause | Fix |
|---------|-------|-----|
| "not configured" | owner, repo or token empty | fill in Settings, Save, Test |
| `HTTP 401 Bad credentials` | token mistyped, revoked, or **expired** (fine-grained PATs expire) | regenerate the PAT, paste it in, Test |
| `repo or branch not found` / `HTTP 404` | owner/repo typo; branch wrong (old repos default to `master`); or the fine-grained token does not include this repo — GitHub answers 404, *not* 403, for repos the token cannot see | check spelling and branch; re-check the token's "Only select repositories" list |
| `conflict: <path>` (HTTP 409/422) | the SHA sent with a write no longer matches — something committed to the repo between the tree listing and the write (e.g. desktop obsidian-git auto-pushed mid-pass) | nothing; the pass aborts, backoff retries, the next pass re-lists and merges. This is the API's safety net working |
| `repo too large` | GitHub truncated the recursive tree listing (~100k files) | not supported; use a smaller/dedicated vault repo |
| `HTTP 403 …rate limit…` | authenticated limit is 5,000 requests/hour; a pass costs 1 tree list + ~1 request per pulled/pushed file, so only a *first* sync of a very large vault can hit it | wait — the backoff retries will pick up when the hourly window resets |
| `HTTP 502 proxy error: …` | ghproxy can't reach GitHub (bridge machine offline/DNS) | check the bridge machine's internet connection |
| phone can't reach the bridge at all | wrong WLAN, firewall, hostname instead of IP | same WLAN as the bridge; open port 8180; use the raw LAN IP in API URL |
| images/txt re-push for no reason, or `(remote)` siblings appear unexpectedly | **clock skew / mtime churn**: binary change detection is size+mtime, so changing the phone's clock, copying files on via card reader or Bluetooth, or restoring a backup rewrites mtimes and makes files *look* modified | harmless — the pushes are no-op commits; delete unwanted `(remote)` siblings. To avoid it, let sync settle before mass-copying files |
| a changed image is *not* pushed | the inverse mtime problem: something replaced the file while preserving both size and mtime, so it looks unchanged | re-save or touch the file (rename it and back works) |
| conflict markers in a note | *Keep both* did its job on a real conflict | edit the note, keep the lines you want, delete the `<<<<<<< phone` / `=======` / `>>>>>>> github` lines, save |
| sync seems stuck after failures | it is in the backoff ladder (60 s → 2 m → 5 m → 15 m) | **Options → Sync now** wakes it immediately |

## Multi-device: desktop Obsidian on the same repo

The end game: the E71 is just one more device on a vault that lives in git.

1. Clone the vault repo on your desktop: `git clone git@github.com:you/vault.git`.
2. Open the folder as a vault in Obsidian.
3. Install the community **obsidian-git** plugin and enable auto pull/push on an
   interval (a few minutes works well).

For the full recipe — including running official **Obsidian Sync** on your other devices
alongside this, plugin settings, `.gitignore`, and the encrypted-vault variant — see
[Obsidian Sync setup](obsidian-sync-setup.md).

How the two coexist:

- The phone commits `Noksidian: update …` one file at a time; obsidian-git commits its
  own batches. They interleave in history without stepping on each other — if the phone
  writes against a stale SHA it gets a `409`, backs off, re-lists and merges next pass.
- Concurrent edits to the *same note* are merged: git handles it on the desktop pull,
  Noksidian's 3-way merge handles it on the phone. With *Keep both*, a conflict the
  phone resolved shows up on the desktop as `<<<<<<< phone` markers — search the vault
  for `<<<<<<<` occasionally and clean up in comfort.
- `.obsidian/` (desktop themes, plugins, workspace) never syncs to the phone, so your
  desktop config can't bloat or break the E71, and the phone never fights over
  workspace files. `.noksidian/` never reaches the repo, so the desktop never sees the
  phone's bookkeeping. No `.gitignore` entries needed for either.
- Keep both sides syncing frequently. The shorter the window between syncs, the fewer
  real conflicts exist to resolve — most passes are `±0 pulled, 0 pushed, 0 merged`
  no-ops that cost a single tree-listing request.

That's the whole story: plain markdown in plain folders, one branch on GitHub as the
meeting point, and every device — including a 2008 candybar — converging on it.
