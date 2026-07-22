#!/bin/sh
# tour.sh - Multi-screenshot emulator tour driver for Noksidian.
#   tools/emu/tour.sh <script-file> [shots-dir]
# Launches dist/Noksidian.jar ONCE in MicroEmulator, dismisses the MIDlet
# launcher, then executes a line-based script, capturing as many screenshots
# as the script asks for. Emulator is killed at the end.
#
# Script lines (blank lines and #-comments ignored):
#   key 108 108 28          inject ydotool keycodes in order (0.35s apart)
#   type some text here     type a literal string (ydotool type)
#   click 160 150           mouse-click window-relative X,Y (y offset +28 auto)
#   sleep 1.5               pause
#   shot name               screenshot -> <shots-dir>/name.png
#   raise                   re-focus the emulator window
# Common keycodes: Up=103 Down=108 Left=105 Right=106 Enter=28
#                  F1(LSK)=59 F2(RSK)=60 Backspace=14 Esc=1
#
# X11 ONLY. Keys are injected with ydotool at the uinput level, so they land in
# whatever window the COMPOSITOR has focused. Under Wayland, wmctrl/xdotool only
# move Xwayland focus (and getactivewindow lies), so the whole tour silently
# types into the launcher - or worse, into the user's own apps. On Wayland use
# tools/emu/xvfb-tour.sh instead: isolated Xvfb :99 driven by XTEST.
#
# NOTE on error handling: set -u but deliberately NOT set -e. Almost every step
# here is best-effort (|| true) because a single flaky keypress must not throw
# away a long tour; only capture failures are tracked, via FAILED.
set -u
# All paths below are repo-root relative (run-emu.sh, dist/, tools/emu/...), so
# normalize the working directory first - tours are invoked from anywhere.
cd "$(dirname "$0")/../.."
# absolute base for callers/edits that need one; nothing below uses it today.
ROOT="$(pwd)"
# --reset: restore pristine RMS (vault configured, resume off) -> boot lands in
#          Library root, selection 0. --reset-vault: also restore fsroot fixture.
RESET=0; RESET_VAULT=0
while [ $# -gt 0 ]; do
    case "$1" in
        --reset)       RESET=1; shift ;;
        --reset-vault) RESET=1; RESET_VAULT=1; shift ;;
        *) break ;;
    esac
done
SCRIPT="${1:?usage: tour.sh [--reset|--reset-vault] <script-file> [shots-dir]}"
SHOTS="${2:-tools/emu/shots}"
mkdir -p "$SHOTS"
# import/wmctrl/xdotool all need a display even when the caller has none (cron,
# a detached shell); :0 is the usual desktop session.
export DISPLAY="${DISPLAY:-:0}"
# ydotool and ydotoold must agree on the socket path; there is no shared default
# that works for a manually started daemon, so pin it for both.
export YDOTOOL_SOCKET="${YDOTOOL_SOCKET:-/tmp/.ydotool_socket}"

# ydotool needs a root-owned daemon holding /dev/uinput. Start one on demand.
# --socket-own hands the socket to the invoking user so the unprivileged ydotool
# calls further down can talk to it. sudo -n (non-interactive) makes an
# unattended tour fail fast instead of blocking forever on a password prompt.
# The 2s wait covers socket creation: connecting before the socket exists turns
# every subsequent key into a silent no-op.
if [ ! -S "$YDOTOOL_SOCKET" ]; then
    sudo -n ydotoold --socket-path="$YDOTOOL_SOCKET" --socket-own="$(id -u):$(id -g)" \
        >/tmp/ydotoold.log 2>&1 &
    sleep 2
fi

# Kill only the emulator JVM. The pattern needs BOTH the MicroEmulator main
# class and 'bin/java': matching the class alone would also hit the run-emu.sh
# wrapper shell, this script's own command line and the grep pipeline itself.
# Exactly one emulator may be alive at a time - a stale instance keeps the RMS
# store open and owns the window title that WIN is resolved from below.
kill_emu() {
    for p in $(ps -eo pid,cmd | grep 'org.microemu.app.Main' | grep 'bin/java' \
            | grep -v grep | awk '{print $1}'); do kill -9 "$p" 2>/dev/null || true; done
}

# Kill BEFORE restoring state: a live emulator owns the RMS record stores and
# would flush them back over the pristine copy when it exits. The 1s lets the
# JVM's file handles disappear before the tree is swapped underneath it.
kill_emu
sleep 1
# RMS state lives in me-home/.microemulator (config2.xml, the fsroot symlink,
# and the suite-Noksidian record stores). We restore the whole directory rather
# than deleting it, because the snapshot has the vault ALREADY configured and
# resume turned off: a reset boot therefore lands in the Library root with
# selection 0, which is what lets tour scripts count keypresses from a known
# state. Missing snapshot is a warning, not an error - the tour still runs, it
# just starts from whatever the previous run left behind.
if [ "$RESET" = 1 ]; then
    if [ -d tools/emu/me-home-pristine ]; then
        rm -rf tools/emu/me-home/.microemulator
        cp -a tools/emu/me-home-pristine tools/emu/me-home/.microemulator
    else
        echo "tour: WARNING no me-home-pristine snapshot; skipping RMS reset" >&2
    fi
fi
# fsroot is the JSR-75 file system the MIDlet sees as file:///E/ (run-emu.sh
# maps it). RMS alone is not enough for tours that create, rename or delete
# notes - those mutate the vault on disk, so the fixture has to be rolled back
# as well or the next run sees leftover files and its keypress counts drift.
# Skipped silently when no backup exists.
if [ "$RESET_VAULT" = 1 ] && [ -d tools/emu/fsroot.bak ]; then
    rm -rf tools/emu/fsroot
    cp -a tools/emu/fsroot.bak tools/emu/fsroot
fi
# Launch ONCE and keep it alive for the whole script: restarting per step would
# throw away all in-app navigation state, which is the thing tours exercise.
# Output is captured because MIDlet stack traces only appear on the emulator's
# stdout/stderr, and they are the only post-mortem when a tour goes wrong.
# 9s covers JVM start + device-e71.xml parsing + AWT window mapping; this is
# only the coarse "does a window exist" wait, all fine-grained synchronisation
# below is frame-hash driven.
./run-emu.sh dist/Noksidian.jar >tools/emu/gui-run.log 2>&1 &
sleep 9

# Resolve the window id once: every capture and focus call downstream is
# window-relative (import -window, wmctrl -ia). Title match is a loose
# case-insensitive "micro" because the window is titled "MicroEmulator".
# No window means the emulator died during startup - see tools/emu/gui-run.log.
WIN=$(wmctrl -l 2>/dev/null | grep -i micro | awk '{print $1}' | head -1)
if [ -z "$WIN" ]; then echo "tour: no emulator window" >&2; kill_emu; exit 1; fi
wmctrl -ia "$WIN" 2>/dev/null || true

# Grabs the whole window, window-manager title bar included - that is why click
# coordinates below get a +28 y offset to stay screen-relative.
shot() { import -window "$WIN" "$1" 2>/dev/null; }
# sat: mean HSL saturation of the frame, scaled x1000 to an integer so shell
# comparisons work without floating point. A cheap numeric answer to "did the
# theme/colour actually change", used during the LCD-dim and dark-theme work
# instead of eyeballing PNGs. No tour command invokes it today; kept for ad-hoc
# colour regressions.
sat() {
    import -window "$WIN" png:- 2>/dev/null \
        | magick - -colorspace HSL -channel g -separate +channel \
              -format '%[fx:int(mean*1000)]' info: 2>/dev/null
}

# Start the MIDlet from the launcher list: "Noksidian" is preselected, one
# Enter launches it. Deterministic - no clicks, no F2 (F2 = Exit on some screens).
# (that describes the launch sequence a few lines down; frame() comes first
# because it is the clock the whole driver runs on.)
#
# frame: md5 of the window's current pixels. Everything here synchronises on
# "the hash changed" rather than fixed sleeps, because emulator repaint latency
# swings wildly under CPU contention from parallel builds - fixed sleeps were
# measurably flaky and produced screenshots of the previous screen.
frame() { import -window "$WIN" png:- 2>/dev/null | md5sum | cut -d' ' -f1; }

# A real pointer click is the only reliable way to hand the AWT canvas keyboard
# focus; wmctrl activation alone leaves keys going nowhere. Safe to click
# anywhere on the screen: the device XML sets haspointerevents=false, so the
# click focuses the Swing component but is never delivered to the MIDlet.
xdotool mousemove --sync 160 150 click 1 2>/dev/null || true  # focus window
sleep 0.5
# baseline hash of the launcher screen, so we can detect when it goes away
LAUNCHER_FRAME=$(frame)
# This is the ONE place Enter works: the launcher is a plain Swing list. Once
# the MIDlet canvas is up, Enter/FIRE/SELECT is never delivered to it at all
# (verified for 28, KP_Enter, space, KP5), so tour scripts must use the soft
# keys F1(59) / F2(60) to open or confirm anything.
ydotool key 28:1 28:0 2>/dev/null || true   # Enter starts the selected MIDlet
# Keys sent while the MIDlet is still initializing are dropped wholesale
# (verified: fixed sleeps + sacrificial keys all landed in the dead window).
# Poll until the frame visibly changes from the launcher, then settle.
i=0
while [ "$i" -lt 24 ]; do
    sleep 0.5
    F=$(frame)
    [ -n "$F" ] && [ "$F" != "$LAUNCHER_FRAME" ] && break
    i=$((i + 1))
done
# NOTE: unlike xvfb-tour.sh, exhausting the retries here is NOT treated as a
# failure - if the MIDlet never started, the rest of the script is typed blindly
# into the launcher and the shots quietly capture the wrong screen.
# The hash flips on the very first paint; this extra settle covers the initial
# vault/index scan and the first full repaint before anything is captured.
sleep 1.5
# re-focus the swapped-in canvas.
# The MIDlet display replaces the launcher component inside the same window, and
# the new canvas does not inherit keyboard focus - without this second click
# every key below lands in a dead component.
xdotool mousemove --sync 160 150 click 1 2>/dev/null || true
sleep 0.5
# input-liveness probe: press Down until the frame actually changes (list
# selection moves), then Up to restore selection 0. Proves the key path is
# live before the script runs. On non-list boot screens (Welcome) nothing
# changes - we fall through after the retries, which is harmless there.
# Down/Up is chosen because it is non-destructive and self-reversing: it only
# moves a list cursor, so a probe that fires on an unexpected screen cannot
# mutate the vault or open a dialog and desynchronise the script that follows.
PF=$(frame)
i=0
while [ "$i" -lt 6 ]; do
    ydotool key 108:1 108:0 2>/dev/null || true
    sleep 0.7
    F=$(frame)
    [ -n "$F" ] && [ "$F" != "$PF" ] && break
    i=$((i + 1))
done
if [ "$i" -lt 6 ]; then
    ydotool key 103:1 103:0 2>/dev/null || true
    sleep 0.7
fi

# --- script interpreter ----------------------------------------------------
# IFS= plus read -r keeps each line verbatim: no leading/trailing whitespace
# stripping and no backslash mangling, which matters for `type` payloads that
# contain markdown. The `|| [ -n "$line" ]` tail makes a script whose last line
# has no trailing newline still execute that line.
FAILED=0
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|\#*) continue ;; esac
    # split into "cmd" + everything after the first space; for a bare word such
    # as `raise` the ${line#* } expansion returns the line unchanged, so blank
    # it out instead of passing the command name to itself as an argument.
    cmd=${line%% *}; rest=${line#* }; [ "$rest" = "$line" ] && rest=""
    case "$cmd" in
        key)
            # frame-driven stepping: wait for the frame to actually change
            # after each key (CPU contention from parallel builds makes fixed
            # sleeps unreliable). No-op keys fall through after ~2.5s.
            # $rest is intentionally unquoted: it is a whitespace-separated list
            # of Linux input-event keycodes (108, 28, ...), not ASCII/characters,
            # and each is injected in order.
            for k in $rest; do
                B=$(frame)
                # real hold time: zero-duration taps are dropped when the
                # emulator's event queue is busy right after list repaints
                ydotool key "$k":1 2>/dev/null || true
                sleep 0.09
                ydotool key "$k":0 2>/dev/null || true
                j=0
                while [ "$j" -lt 10 ]; do
                    sleep 0.25
                    F=$(frame)
                    [ -n "$F" ] && [ "$F" != "$B" ] && break
                    j=$((j + 1))
                done
                sleep 0.2
            done ;;
        type)
            # Printable characters ARE delivered to the canvas, unlike FIRE.
            # A real newline is NOT typeable here (the editor inserts '\n' from
            # onSelect/FIRE, which never arrives), so a tour that appears to
            # merge two lines is an emulator artifact, not an app bug - confirm
            # by reading the saved .md under tools/emu/fsroot/E/.
            # `--` stops text beginning with '-' being parsed as an option.
            ydotool type -- "$rest" 2>/dev/null || true
            sleep 0.5 ;;
        click)
            # +28 compensates for the window-manager title bar that is part of
            # the captured window, so scripts can use plain screen coordinates
            # read off a shot. Clicks only move host-side focus: with
            # haspointerevents=false the MIDlet never receives them, so this is
            # a focus tool, not a way to tap UI elements.
            x=$(echo "$rest" | awk '{print $1}'); y=$(echo "$rest" | awk '{print $2}')
            xdotool mousemove --sync "$x" $((28 + y)) click 1 2>/dev/null || true
            sleep 0.4 ;;
        sleep)
            # explicit escape hatch for waits the frame-hash poll cannot see,
            # e.g. a repaint-identical background scan or a timed splash.
            sleep "$rest" ;;
        shot)
            # let the last repaint land first: capturing immediately after a key
            # can grab the pre-transition frame even when its hash has changed.
            sleep 0.4
            if shot "$SHOTS/$rest.png"; then
                echo "tour: shot $SHOTS/$rest.png"
            else
                echo "tour: SHOT FAILED $rest" >&2; FAILED=1
            fi ;;
        raise)
            # mid-tour focus recovery for when something steals it (a host
            # notification, the window being restacked). Activation alone does
            # not restore AWT keyboard focus - the click is the part that works,
            # so both are done together.
            wmctrl -ia "$WIN" 2>/dev/null || true
            xdotool mousemove --sync 160 150 click 1 2>/dev/null || true
            sleep 0.4 ;;
        *)
            # reported but not fatal, and deliberately not counted in FAILED: a
            # typo should not discard an otherwise usable tour.
            echo "tour: unknown cmd: $line" >&2 ;;
    esac
done < "$SCRIPT"

# Always tear down: a surviving emulator keeps the RMS store open and its window
# title would be picked up as WIN by the next tour, which would then drive the
# stale instance.
kill_emu
[ "$FAILED" = 0 ] && echo "tour: done ($SCRIPT)" || echo "tour: done WITH FAILURES ($SCRIPT)"
# Exit status tracks capture failures only: a caller can distinguish "the tour
# ran but produced no evidence" from a clean run. It does NOT assert that the
# screenshots show the right screens - that still needs a human or a diff.
exit "$FAILED"
