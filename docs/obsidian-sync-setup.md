# Noksidian + official Obsidian Sync

You pay for [Obsidian Sync](https://obsidian.md/sync), your laptop, work machine and modern
phone all sync through it — and you want the Nokia E71 in on the same vault. This document
is the honest, complete answer: **directly, never; through a one-desktop git relay,
completely.** It explains why the direct route is physically impossible, then gives the
supported topology, the step-by-step obsidian-git setup, the end-to-end conflict story, the
encrypted-vault variant, and a final checklist.

## Why the E71 can never speak Obsidian Sync directly

Obsidian Sync is a **proprietary, end-to-end encrypted protocol over WebSockets**. Every
piece of that sentence is independently fatal on this hardware:

- **Proprietary** — there is no public specification and no sanctioned third-party client.
  Nothing to implement against, and nothing GitHub-REST-shaped to point the phone at.
- **End-to-end encrypted** — your notes are encrypted with modern AES-GCM on the client
  before they ever leave it. The E71's JVM (CLDC 1.1, effectively Java 1.3) ships no AES,
  no GCM, and no crypto API to hang them on.
- **WebSockets over TLS 1.2/1.3** — the phone has no WebSocket support, and its Symbian
  TLS stack tops out at **TLS 1.0**. The handshake fails before any protocol talk begins.

And crucially, the `ghproxy.py` trick from [GitHub sync](sync.md) **cannot save us here**.
The TLS bridge works for GitHub because GitHub's API is plain REST: the bridge relays bytes
and never needs to understand them. A hypothetical Obsidian Sync bridge would have to
*terminate the end-to-end encryption* — hold your Sync password and decrypt your vault in
the middle — which is exactly what E2EE exists to prevent, using an unpublished protocol
with no API. There is no honest version of that bridge.

So the phone will never be an Obsidian Sync client. It doesn't need to be.

## The supported topology: one desktop as the relay

The trick is that a folder on disk can belong to **two sync systems at once**. Pick one
desktop (best: one that runs a lot, ideally always-on) and make its vault folder both an
Obsidian-Sync vault *and* a git clone of your vault repo:

```
        Obsidian Sync (proprietary E2EE)                    plain git + REST
┌──────────┐      ┌───────────┐      ┌──────────────────┐      ┌─────────┐      ┌───────────┐
│ modern   │◄────►│ Obsidian  │◄────►│  RELAY DESKTOP   │◄────►│ GitHub  │◄────►│ Nokia E71 │
│ phone /  │      │   Sync    │      │ Obsidian + Sync  │ git  │ vault   │ REST │ Noksidian │
│ tablet   │      │  servers  │      │ + obsidian-git   │      │  repo   │  via │  MIDlet   │
└──────────┘      └───────────┘      │  on ONE folder   │      └─────────┘bridge└───────────┘
┌──────────┐           ▲             └──────────────────┘
│ laptop   │◄──────────┘
└──────────┘   ...any number of Obsidian-Sync devices
```

- **Obsidian Sync** links the relay desktop with every modern device, exactly as it does
  today. Nothing about your Sync setup changes.
- On the relay desktop, the community **obsidian-git** plugin auto-commits the vault folder
  and auto-pushes/pulls it against a **GitHub repo** on a short interval.
- The **E71 syncs that repo** with Noksidian, through the TLS bridge, per
  [GitHub sync](sync.md).

Every edit, wherever it is born, flows through the relay desktop's folder and reaches every
device. Only *one* desktop should run obsidian-git in this topology — Obsidian Sync
already distributes changes to the others, and a second git relay on the same vault would
just race the first.

## Step-by-step: obsidian-git on the relay desktop

Prerequisites: `git` installed; the vault repo exists on GitHub (the same owner/repo/branch
Noksidian is configured for); `git push` works non-interactively from a terminal (SSH key
or credential helper — obsidian-git cannot type passwords for you).

### 1. Make the vault folder a git clone

Two starting points:

- **The vault already lives in Obsidian Sync** and the repo is empty: turn the existing
  vault folder into the clone.

  ```
  cd ~/Vault
  git init -b main
  git remote add origin git@github.com:you/vault.git
  git add -A && git commit -m "initial vault import"
  git push -u origin main
  ```

- **The repo already has content** (e.g. the phone synced first): clone it, open the folder
  as a vault in Obsidian, then connect that vault to your remote Obsidian-Sync vault
  (Settings → *Sync*). Obsidian Sync merges the folder's contents with the remote vault on
  first connect.

### 2. Add a `.gitignore` at the vault root

```gitignore
.obsidian/workspace*
.trash/
```

Why exactly these:

- `.obsidian/workspace*` (workspace.json, workspace-mobile.json) churns on practically
  every click — pane layout, cursor positions. Unignored, it would manufacture a commit
  every interval forever.
- `.trash/` is Obsidian's own per-device trash; it has no business in shared history.

Nothing here affects the phone either way: Noksidian already ignores the whole `.obsidian/`
tree in **both** directions and never scans dot-folders locally (see
[GitHub sync](sync.md)). The ignore rules exist purely to keep the repo history quiet.

### 3. Install and configure obsidian-git

1. Obsidian → **Settings → Community plugins** → turn off Restricted mode → **Browse** →
   search **"Git"** (obsidian-git) → **Install** → **Enable**.
2. In the plugin's settings, set (option names move around between plugin versions, but
   these all exist):
   - **Auto commit-and-sync interval: 5 minutes.** Each cycle commits all changes, pulls,
     and pushes.
   - **Auto pull interval: 5 minutes** (and **Pull on startup**: on) — so the phone's
     pushes land on the desktop promptly even when you are not editing there.
   - **Commit message**: something like `desktop: {{date}}` — the phone's commits are
     always `Noksidian: update|add|delete <path>`, so `git log` stays a readable journal
     of who did what.
   - Disable the per-commit notification popups unless you enjoy them.
3. Sanity check: edit a note on the E71 → **Options → Sync now** → within one plugin
   interval it appears in desktop Obsidian → within seconds Obsidian Sync has it on every
   other device. Then edit on a Sync device and watch it arrive on the phone the same way
   in reverse.

Why ~5 minutes: the shorter the window between syncs on *any* hop, the fewer real
conflicts ever exist. Five minutes keeps the repo history sane while making the worst-case
end-to-end latency (phone interval + git interval + Sync's seconds) a coffee break, not an
afternoon.

## How conflicts resolve end-to-end

Suppose the same note is edited on the E71 *and* on your laptop (an Obsidian-Sync device)
in the same window:

1. **Laptop → relay desktop**: Obsidian Sync propagates the laptop's edit to the relay
   desktop's folder within seconds. obsidian-git commits and pushes it on its next cycle.
2. **Phone**: Noksidian's next pass sees the note changed on both sides and runs its real
   line-based **3-way merge** (see [GitHub sync](sync.md)): non-overlapping edits from both
   sides survive; same-line collisions follow your conflict policy (*Keep both* emits
   `<<<<<<< phone` / `>>>>>>> github` markers — nothing is ever lost). The merged note is
   written to the phone **and pushed back** in the same pass.
3. **Repo → relay desktop**: obsidian-git's next pull brings down the phone's merged
   commit. If the desktop committed something in the meantime, ordinary `git merge`
   machinery reconciles at the commit level — same 3-way idea, executed by git.
4. **Relay desktop → everywhere**: Obsidian Sync notices the changed file in the folder
   and fans it out to every Sync device. Convergence.

If you run the *Keep both* policy and a real same-line conflict happened, the marker block
travels to every device like any other text. Fix it on whichever device is most
comfortable (desktop Obsidian, obviously) — the fix syncs back out through the same
pipeline. Searching the vault for `<<<<<<<` once in a while is a good habit.

Two racing writers on the repo itself are also safe: if the phone pushes against a stale
SHA because obsidian-git committed mid-pass, GitHub answers `409`, Noksidian backs off,
re-lists and merges next pass. That is the API's safety net working as designed.

## The encrypted-vault variant

If the phone vault uses [Noksidian encryption](encryption.md), the **Encrypted sync
scope** setting (`crypt.scope`) decides what the repo — and therefore the relay desktop —
gets to see:

| Scope (Settings)   | Repo contains        | Works with this topology?                      |
|--------------------|----------------------|------------------------------------------------|
| **Phone only** (`local`) | plaintext markdown | **Yes, unchanged — recommended**              |
| **Phone + GitHub** (`all`) | `NKE1` ciphertext | only with a manual decrypt/encrypt dance      |

**Recommendation for Obsidian Sync users: choose *Phone only*.** The vault on the phone
stays encrypted at rest (lost/stolen E71 leaks nothing), while the repo stays plain
markdown — desktop Obsidian, obsidian-git and Obsidian Sync all just work with zero extra
steps. The trade you are accepting is that GitHub stores plaintext; if you already trust
GitHub with the vault repo, nothing changed.

With **Phone + GitHub** scope the repo holds ciphertext, so the relay desktop's working
tree is ciphertext too: Obsidian cannot render it, and worse, Obsidian Sync would happily
fan the encrypted bytes out to all your other devices as garbage. To edit on the desktop
at all you must wrap every session in `tools/nokcrypt.py`:

```
python3 tools/nokcrypt.py decrypt ~/Vault     # prompts for the vault password
# ... edit in Obsidian ...
python3 tools/nokcrypt.py encrypt ~/Vault
git add -A && git commit -m "desktop edits" && git push
```

— with obsidian-git's **auto commit disabled** (it would commit plaintext mid-session) and
Obsidian Sync **disconnected from this vault** (it would replicate whichever state the
folder happens to be in). At that point you have given up everything this document is
about. *Phone + GitHub* scope is the right choice for phone-and-GitHub-only setups where
the repo itself must be opaque; it is the wrong choice for an Obsidian-Sync relay. See
[encryption.md](encryption.md) for the full threat-model discussion.

## Setup checklist

- [ ] GitHub repo for the vault; fine-grained PAT scoped to that one repo, *Contents:
      Read and write* ([GitHub sync, step 1](sync.md))
- [ ] TLS bridge running and reachable from the phone ([GitHub sync, step 2](sync.md))
- [ ] Noksidian Settings filled in (owner/repo/branch/token/API URL), **Test** passes,
      first sync completed
- [ ] Relay desktop: vault folder is *both* the Obsidian-Sync vault *and* a git clone
      with `origin` pointing at the vault repo
- [ ] `git push` from that folder works without prompting for credentials
- [ ] `.gitignore` with `.obsidian/workspace*` and `.trash/`
- [ ] obsidian-git installed: auto commit-and-sync ≈ 5 min, auto pull ≈ 5 min, pull on
      startup, distinctive commit message
- [ ] Only ONE desktop runs obsidian-git on this vault
- [ ] All other devices connected via Obsidian Sync as before
- [ ] Encrypted vault? **Encrypted sync scope = Phone only**
- [ ] End-to-end test both directions: phone edit reaches a Sync device; Sync-device edit
      reaches the phone

That's the whole story: Obsidian Sync keeps doing what you pay it for, git is the neutral
meeting point, and a 2008 candybar phone becomes just another device on your vault.
