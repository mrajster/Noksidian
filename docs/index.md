# Noksidian documentation

Noksidian is an Obsidian-style markdown **vault** app for the Nokia E71 (and any MIDP 2.0 +
JSR-75 phone): browse, read, edit and **wikilink** notes on the phone, and sync the whole
vault with a GitHub repository — through a small TLS **bridge** (`tools/ghproxy.py`, port
8180) because the E71's TLS 1.0 cannot reach `api.github.com` directly. The docs below are
ordered user-first: start with the README for the pitch, install the app, learn the screens,
set up sync, keep the two reference docs handy, and read the architecture doc only if you
hack on the code. Where documents disagree, [CONTRACTS.md](../CONTRACTS.md) wins.

## All documents

| Doc | What it covers | Read it when |
|---|---|---|
| [README](../README.md) | Project overview: features, the TLS catch, build/install/first-run quick start, limitations | First contact with the project |
| [Building & installing](install.md) | The vendored toolchain, `./build.sh` / `./test.sh`, getting the .jar onto the E71, Symbian permission tuning, upgrade/uninstall, install-time errors | Putting the app on a phone |
| [User guide](user-guide.md) | The complete manual: vault wizard, library, reader, editor, search, images, sync behavior, every Settings field, key reference, honest limits | Using the app day to day |
| [GitHub sync](sync.md) | Fine-grained PAT setup, the TLS bridge (`ghproxy.py` incl. `--secret`, nginx/Caddy, systemd), sync over mobile data, config keys and defaults, the full per-file decision table, 3-way merge and the *Keep both* / *Prefer phone* / *Prefer GitHub* policies, limits, multi-device with desktop Obsidian | Setting up or understanding sync |
| [Obsidian Sync setup](obsidian-sync-setup.md) | Why the phone can never speak official Obsidian Sync (proprietary E2EE WebSocket, TLS 1.2+), the supported relay topology (one desktop runs Obsidian Sync **and** obsidian-git on the same folder), step-by-step obsidian-git config, the end-to-end conflict story, the encrypted-vault variant, setup checklist | You use (or want) official Obsidian Sync on your other devices |
| [Encryption](encryption.md) | Threat model, on-disk format (`_vault.nkv` / `NKE1`), enable/change/disable walkthroughs, the remember-password tradeoff, *Phone + GitHub* vs *Phone only* scope, desktop `nokcrypt.py` usage | Protecting the vault with a password |
| [Troubleshooting & FAQ](troubleshooting.md) | Symptom-indexed fixes: sync errors (401/404/409, "repo too large", bridge unreachable), phone issues (permissions, memory, encoding), resetting sync state, moving a vault, battery/data | Something is broken |
| [Markdown reference](markdown-reference.md) | Every construct the parser understands (blocks, inline, wikilinks, tags, callouts, images) with on-phone rendering notes, plus the explicit not-supported list | Checking how a note will render |
| [Architecture](architecture.md) | Developer deep-dive: CLDC 1.1 / Java 1.3 constraints, preverification, module map, note/sync data flow, build pipeline, test strategy, toolchain provenance, how to extend | Changing the code |
| [CONTRACTS.md](../CONTRACTS.md) | Authoritative public signatures and behavioral contracts for every class; language constraints; build rules | Implementing or reviewing any class |

## Which doc do I need?

- **"What is this?"** → [README](../README.md)
- **"How do I build it / get it on the phone?"** → [Building & installing](install.md)
- **"The installer refuses / certificate error / permission prompts"** → [Building & installing](install.md) (install-time table), then [Troubleshooting](troubleshooting.md)
- **"How do I use screen X / what does this key do?"** → [User guide](user-guide.md)
- **"How do I set up GitHub sync / the token / the bridge?"** → [GitHub sync](sync.md), steps 1–3
- **"How do I sync away from home, over mobile data?"** → [GitHub sync](sync.md), *Sync over mobile data*
- **"Can I use official Obsidian Sync? My other devices already do."** → [Obsidian Sync setup](obsidian-sync-setup.md)
- **"How do I encrypt the vault / what does the password actually protect?"** → [Encryption](encryption.md)
- **"What exactly does a sync pass do to my files?"** → [GitHub sync](sync.md), the decision table
- **"Sync fails / `<<<<<<< phone` markers appeared / files won't sync"** → [Troubleshooting](troubleshooting.md)
- **"Why does my note render like that / is construct X supported?"** → [Markdown reference](markdown-reference.md)
- **"I want to change or extend the code"** → [Architecture](architecture.md) + [CONTRACTS.md](../CONTRACTS.md)
- **"What are the exact method signatures / config keys / limits?"** → [CONTRACTS.md](../CONTRACTS.md)
