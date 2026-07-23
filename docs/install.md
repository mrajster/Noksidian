# Building and installing Noksidian

This page takes you from a fresh clone to a running MIDlet on the Nokia E71:
build on your PC, copy two small files to the phone, tune three Symbian
permissions, done. Configuring GitHub sync afterwards is covered in the other
docs — this one is only about getting the app onto the phone.

## Prerequisites

None, beyond the repo itself and a Linux x86-64 machine to build on. You do
**not** need a system JDK, the ancient Sun WTK, or any Nokia SDK. Everything
the build needs is checked in under `tools/`:

| Path | What it is | Why it's needed |
|---|---|---|
| `tools/jdk8/` | A full JDK 8 (Temurin 1.8.0_492, Linux amd64) | JDK 8 is the last JDK whose `javac` still accepts `-source 1.3 -target 1.3`. The E71's KVM only runs Java 1.3-era bytecode; newer compilers refuse to emit it. The same JDK also runs ProGuard, `jar`, and the desktop tests. |
| `tools/proguard/` | ProGuard | Used purely as the **CLDC preverifier** (`-microedition` flag). CLDC class files need extra StackMap attributes that plain `javac` doesn't write; without them the phone rejects every class at load time. ProGuard replaces Sun's long-dead `preverify` binary. No shrinking or obfuscation is done. |
| `tools/lib/` | Real CLDC 1.1 / MIDP 2.0 / JSR-75 API jars (`cldcapi11-2.0.4.jar`, `midpapi20-2.0.4.jar`, `microemu-jsr-75-2.0.4.jar`) | Used as the `-bootclasspath`, so the compiler sees exactly the API the phone has — no `java.util.ArrayList`, no `StringBuilder`, but `javax.microedition.*` and the JSR-75 `FileConnection` API. Compiling against a desktop `rt.jar` would silently let phone-incompatible code through. |

The only script that needs anything else is the optional TLS bridge
(`tools/ghproxy.py`), which needs Python 3 — on the always-on LAN machine that
runs it, not on the build box or the phone.

> **Note:** the bundled JDK is a Linux amd64 build, and `build.sh` uses GNU
> `stat -c`. Building on macOS or Windows is not supported as-is.

## Building

```
./build.sh
```

Takes a few seconds and prints one line per stage:

1. **`== compiling ==`** — collects every `src/**/*.java` and runs
   `tools/jdk8/bin/javac -source 1.3 -target 1.3 -bootclasspath <CLDC+MIDP+JSR-75>`,
   writing classes to `build/classes/`. Any use of an API the phone lacks fails
   right here instead of at runtime on the device.
2. **`== preverifying (ProGuard -microedition) ==`** — runs ProGuard with
   `-microedition -dontoptimize -dontobfuscate -dontshrink` over
   `build/classes/`, emitting preverified classes (with CLDC StackMap
   attributes) into `build/pre/`. Classes are passed through 1:1
   (`-keep class * { *; }`).
3. **`== packaging ==`** — copies `res/icon.png` to the jar root, writes the
   MIDlet manifest (`MIDlet-1: Noksidian, /icon.png, nok.NoksidianMIDlet`,
   `CLDC-1.1` / `MIDP-2.0`), jars everything into `dist/Noksidian.jar`, then
   generates `dist/Noksidian.jad` with `MIDlet-Jar-Size` set to the jar's exact
   byte size.

The app icon is a Nokia keypad phone cradling the Obsidian crystal on a rounded
true-black tile with transparent corners. `res/icon.png` is the 64&times;64 PNG
embedded in the jar (the phone scales it down for the app grid); the full size set
(16&rarr;512, transparent background) lives in `res/icons/` for other uses, and the
256&times;512 versions double as the project/repo icon.

On success:

```
OK: dist/Noksidian.jar (NNNNN bytes) + dist/Noksidian.jad
```

`build.sh` deletes and recreates `build/` and `dist/` every run, so builds are
always clean.

To run the desktop unit tests for the pure core (markdown parser, 3-way merge,
JSON, Base64, paths, note index — everything under `src/nok/core/`):

```
./test.sh
```

This compiles `src/nok/core/**` plus `test/**` with the bundled JDK and runs
the three test mains (`TestBase64Json`, `TestMd`, `TestMergePath`) on the
desktop JVM, ending with `ALL TEST SUITES PASSED`. No phone or emulator
involved.

## The dist/ artifacts: .jar vs .jad

```
dist/
├── Noksidian.jar   the actual application (classes + icon + manifest)
└── Noksidian.jad   a tiny text descriptor pointing at the jar
```

- **`Noksidian.jar`** is the complete app. If you send *only this file* to the
  phone (Bluetooth, microSD), Symbian installs it directly — the JAD is not
  needed.
- **`Noksidian.jad`** is a plain-text descriptor: name, version, vendor, and
  `MIDlet-Jar-URL: Noksidian.jar` + `MIDlet-Jar-Size`. It exists for
  installers that read the descriptor first and then fetch the jar — Nokia
  PC Suite installs work this way. **The .jad and .jar must sit in the same
  folder**, and the sizes must match: after every rebuild use the freshly
  generated pair, never an old .jad with a new .jar (the phone will refuse
  with a jar-size mismatch).

The jar is **unsigned**. That is normal for a homebrew MIDlet; it installs
fine as an "untrusted" app but means Symbian asks about file and network
access — see [permission tuning](#symbian-permission-tuning-do-this) below.

## Getting it onto the E71

Pick whichever transfer you have available. All three end at the same native
installer.

### Option A — Bluetooth

1. Pair the E71 with your PC (`Menu → Connectivity → Bluetooth`).
2. Send `dist/Noksidian.jar` to the phone (e.g. `bluetoothctl` +
   `bluetooth-sendto dist/Noksidian.jar`, or your desktop's "Send via
   Bluetooth").
3. On the phone, open the new message in **Messaging → Inbox** and open the
   attachment — the installer starts. Accept the "untrusted application"
   prompt and install to phone memory or the memory card.

### Option B — microSD card

1. Copy `dist/Noksidian.jar` onto the card (card reader, or the phone in
   *Mass storage* USB mode — it shows up as the `E:` drive).
2. On the phone: **Menu → Office → File manager** (on some firmwares
   **Tools → File mgr.**), switch to the memory card tab, navigate to the
   jar, open it. The installer starts.

### Option C — Nokia PC Suite / Ovi Suite

1. Connect the phone over USB in *PC Suite* mode.
2. In PC Suite, use *Install applications* and pick **`dist/Noksidian.jad`**
   (keep `Noksidian.jar` next to it — the suite reads the descriptor, then
   pushes the jar).
3. Confirm the install prompts on the phone.

## Symbian permission tuning (do this!)

Out of the box, Symbian asks for confirmation on **every single** file read
and network request an untrusted MIDlet makes — which for a vault app that
scans folders and syncs in the background means an unusable wall of prompts.
Fix it once:

```
Menu → Installations → App. mgr.
  → Noksidian → Options → Settings        (on some firmwares: "Suite settings")
```

Set each of these to **Ask first time only**:

- **Read user data**
- **Edit user data**
- **Network access**

After that, Symbian asks once per session per capability and then stays quiet.
(There is no "Always allowed" for unsigned MIDlets on the E71 — "Ask first
time only" is the best available setting.) If you skipped this, Noksidian
still works; you'll just be pressing *Yes* a lot.

While you're in App. mgr., if the installer earlier refused with a security
error, also check `Options → Settings → Software installation` is set to
**All** (not *Signed only*) — see the troubleshooting table.

## Upgrading and uninstalling

- **Upgrade in place:** install the new .jar/.jad over the existing app
  without uninstalling first (the installer offers to replace it). Your
  settings **survive** — vault location, GitHub owner/repo/branch/token, API
  URL, sync options all live in the app's RMS record store (`nokcfg`), which
  Symbian keeps across a same-suite reinstall.
- **Uninstall** (`App. mgr. → Noksidian → Options → Remove`) **wipes the RMS
  store** — you'll re-enter the GitHub settings and re-pick the vault folder
  next time.
- **Your vault is never touched either way.** The notes, images,
  `.noksidian/state.json`, base copies and trash live as plain files in the
  vault folder you chose (e.g. `E:/Vault/`) — the installer/uninstaller has
  nothing to do with them. Reinstall, point the vault picker at the same
  folder, and the first sync pass reconciles state from `.noksidian/` as if
  nothing happened.

## Troubleshooting install-time errors

| Phone says | Likely cause | Fix |
|---|---|---|
| `Invalid jar file` / `File corrupted` | Truncated Bluetooth/USB transfer, or the file was mangled in transit (e.g. mail client re-encoded it) | Check the byte size on the phone matches `dist/Noksidian.jar` on the PC; re-send. Prefer microSD copy — it never corrupts. |
| `Unable to install` right after selecting the .jad | .jad without its .jar next to it, or `MIDlet-Jar-Size` doesn't match the jar (old .jad + new .jar) | Keep both files from the **same** `./build.sh` run in the same folder; or skip the .jad and install the .jar directly. |
| `Certificate error` / `Unable to install. Certificate not on phone or SIM` | App manager is set to accept only signed apps — Noksidian's jar is unsigned | `App. mgr. → Options → Settings → Software installation` → **All**. Also check *Online certificate check* is **Off** and the phone's date is set correctly (a wildly wrong clock trips certificate validation). |
| `Expired certificate` | Phone clock is wrong (common after a battery pull) | Set date/time (`Menu → Tools → Clock`), retry. |
| `Not enough memory` / `Memory full` during install | Target drive (phone memory `C:` or card `E:`) is full | Install to the other drive when prompted, or free space. The jar is small (well under 1 MB); phone memory `C:` on an E71 fills up fast, so prefer `E:`. |
| `Application not compatible with phone` / `Unsupported file format` | Wrong file sent (e.g. you sent the .jad alone over Bluetooth, which the E71 can't use standalone), or a jar built without preverification | Send the .jar over Bluetooth/microSD; always build with `./build.sh` (hand-rolled jars from `build/classes` are **not** preverified and will not run). |
| Installs, but every class fails to load / app exits instantly | Preverification was skipped or a different compiler was used | Rebuild with the stock `./build.sh` — it guarantees `-target 1.3` bytecode plus ProGuard `-microedition` StackMaps. |
| Constant permission prompts after install | Default untrusted-app settings | Not an error — do the [permission tuning](#symbian-permission-tuning-do-this) above. |

## What's next

Launch Noksidian from `Menu → Installations` (Symbian puts new MIDlets there;
you can move the icon later). First run walks you through picking a vault
folder ([user guide](user-guide.md)); then open **Settings** to enter your
GitHub details — and read [the sync guide](sync.md) first, because the E71
cannot talk to `api.github.com` directly (TLS 1.0 vs required TLS 1.2): you'll
point the **API URL** at `tools/ghproxy.py` running on a LAN machine
([bridge setup](sync.md#step-2-the-tls-bridge)).
