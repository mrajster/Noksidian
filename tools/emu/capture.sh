#!/bin/sh
# Deterministic emulator screenshot helper for Noksidian.
#   tools/emu/capture.sh <out.png> [keyseq]
# Launches dist/Noksidian.jar in MicroEmulator, reliably dismisses the MIDlet
# launcher (clicks + F2), optionally injects a key sequence, then screenshots.
#
# keyseq: space-separated ydotool linux keycodes, "click:X,Y" to mouse-click at
# window-relative X,Y, or "sleep:N" to pause N seconds. Examples:
#   108 108 28        -> Down, Down, Enter
#   click:160,150 59  -> focus-click then F1
# Common keycodes: Up=103 Down=108 Left=105 Right=106 Enter=28 F1=59 F2=60
#
# WHY the odd input split (xdotool for the mouse, ydotool for keys): under a
# Wayland session xdotool can only talk to Xwayland, and synthetic XTEST keys
# never reach the emulator's AWT canvas. ydotool injects at the kernel uinput
# level so keys land in whatever the compositor has focused - hence the mouse
# clicks below, whose only job is to make that focused window the emulator.
# Pointer positioning/clicking still works fine through xdotool, so each tool
# is used for the half it can actually do.
#
# WHY clicks never substitute for keys: the device profile sets
# haspointerevents=false (tools/emu/device-e71/nok/device/e71.xml), so a click
# focuses the Swing component but is never delivered to the MIDlet. And WHY F2
# rather than Enter for "select": MicroEmulator does not deliver FIRE/SELECT/
# Enter to a MIDlet Canvas at all; the soft keys F1(-6)/F2(-7) are the only
# working confirm. Enter IS honoured by the Swing MIDlet launcher, which is a
# different widget - that is why the dismiss loop and the app phase differ.
set -e
# Everything below is written repo-root-relative (run-emu.sh, tools/emu/*.log,
# the default OUT), so the cd must happen before any path is resolved. Note
# the consequence: a *relative* out.png argument lands under the repo root,
# not under the caller's cwd - pass an absolute path if that matters.
cd "$(dirname "$0")/../.."
ROOT="$(pwd)"
OUT="${1:-tools/emu/shot.png}"
KEYSEQ="$2"
# Exported, not just set: xdotool needs DISPLAY and ydotool needs the socket
# path in their own environment, and both are invoked as plain children here.
# Default :0 covers being run from a cron/pipe with no desktop env inherited.
export DISPLAY="${DISPLAY:-:0}"
export YDOTOOL_SOCKET="${YDOTOOL_SOCKET:-/tmp/.ydotool_socket}"

# ensure ydotoold is up (kernel-level input; xdotool keys don't reach XWayland)
# The daemon needs root to open /dev/uinput, so it is started under sudo -n:
# non-interactive on purpose, because this script runs unattended and a
# password prompt would hang it forever - better to fail and lose key
# injection than to block. --socket-own hands the socket to *this* user so the
# unprivileged ydotool calls further down can talk to it. The sleep covers the
# daemon's uinput device settling; keys sent before that are silently dropped.
if [ ! -S "$YDOTOOL_SOCKET" ]; then
    sudo -n ydotoold --socket-path="$YDOTOOL_SOCKET" --socket-own="$(id -u):$(id -g)" \
        >/tmp/ydotoold.log 2>&1 &
    sleep 2
fi

# Match on the main class AND on bin/java: run-emu.sh is a /bin/sh wrapper that
# ends in `exec java ...`, and other helpers (tour.sh, xvfb-tour.sh, greps in
# an editor) carry the same class name in their command line. Requiring both
# strings kills only real emulator JVMs. kill -9 because MicroEmulator traps
# TERM to run its shutdown/RMS-flush path and can sit there for seconds.
kill_emu() {
    for p in $(ps -eo pid,cmd | grep 'org.microemu.app.Main' | grep 'bin/java' \
            | grep -v grep | awk '{print $1}'); do kill -9 "$p" 2>/dev/null || true; done
}

# Always start from zero emulators: a leftover instance from a previous run
# would win the wmctrl name match below and we would screenshot a stale window
# showing stale app state. The sleep lets the X server reap its window so the
# match cannot see it either.
kill_emu
sleep 1
./run-emu.sh dist/Noksidian.jar >tools/emu/gui-run.log 2>&1 &
# Fixed wait, not a poll: nothing to poll on until the window exists. 9s is
# JVM start + MicroEmulator device-jar load + window map on a loaded machine.
# Undershooting is not fatal - the dismiss loop below retries for ~17s more.
sleep 9

# The emulator titles its window "MicroEmulator"; -i/substring match because
# the suffix varies with the loaded suite. head -1 guards against child/utility
# windows (FocusProxy, menus) that repeat the same name.
WIN=$(wmctrl -l 2>/dev/null | grep -i micro | awk '{print $1}' | head -1)
if [ -z "$WIN" ]; then echo "capture: no emulator window" >&2; exit 1; fi

# Captures the whole emulator client area, which is 320x280: the 240px device
# screen plus ~40px of MicroEmulator chrome (menu strip + status/soft bar).
# Crop downstream if you need just the device screen.
shot() { import -window "$WIN" "$1" 2>/dev/null; }
# Mean saturation*1000: the grayscale MIDlet launcher is ~0; the themed app
# (purple accents) is clearly >0. Far more robust than a raw color count.
# HSL channel g is the S plane; mean is 0..1 so *1000 gives an integer that
# POSIX `test -gt` can compare (sh has no float arithmetic). Both commands are
# silenced: the pipeline legitimately fails while the window is being remapped.
sat() {
    import -window "$WIN" png:- 2>/dev/null \
        | magick - -colorspace HSL -channel g -separate +channel \
              -format '%[fx:int(mean*1000)]' info: 2>/dev/null
}

# Dismiss the MIDlet launcher: click Start (right soft) + F2 until the screen
# gains color (the app is themed; the launcher is grayscale).
#
# Ordering matters: sat() is tested BEFORE acting, never after. Once the app is
# up, F2 is the app's own right soft key (often Exit/Back), so one extra press
# past the finish line would close the very screen we came to photograph.
# Test-then-act guarantees the loop stops without a trailing keystroke.
#
# The two clicks are different jobs. 300,296 is the launcher's Start button in
# the bottom-right chrome strip - these are ABSOLUTE screen coordinates and
# assume the emulator window sits at the top-left of the display (no geometry
# lookup here, unlike xvfb-tour.sh). 160,150 is the middle of the canvas and
# exists purely to give the emulator compositor focus, so the ydotool F2 that
# follows is delivered to it and not to whatever the user last touched.
#
# 14 iterations at ~1.2s each is the retry budget (~17s). Falling out without
# ever reaching color is deliberately NOT an error: some boot screens really
# are low-saturation, and a captured launcher shot is easier to diagnose than
# a hard failure. If shots come back gray, this loop is where to look.
i=0
while [ "$i" -lt 14 ]; do
    S=$(sat)
    # 2>/dev/null swallows "Illegal number" when sat() returned garbage rather
    # than an integer; the -n guard alone does not cover a non-numeric magick
    # error string. Note this is safe under set -e: the && list is not the
    # loop's last command, so a false test does not abort the script.
    [ -n "$S" ] && [ "$S" -gt 8 ] 2>/dev/null && break
    xdotool mousemove --sync 300 296 click 1 2>/dev/null || true
    sleep 0.5
    xdotool mousemove --sync 160 150 click 1 2>/dev/null || true
    ydotool key 60:1 60:0 2>/dev/null || true
    sleep 0.7
    i=$((i + 1))
done
# The MIDlet paints its first real frame slightly after the color flip that
# broke the loop; give it a beat so keys below hit the live canvas, not the
# still-initializing one (keys sent during init are dropped wholesale).
sleep 1

# optional key sequence
if [ -n "$KEYSEQ" ]; then
    # Re-focus: the launcher's Swing component is swapped out for the MIDlet
    # canvas during startup, and the old focus goes with it. Without this click
    # the whole sequence is injected into a dead window and silently lost.
    xdotool mousemove --sync 160 150 click 1 2>/dev/null || true  # focus canvas
    sleep 0.4
    # Unquoted $KEYSEQ on purpose: word-splitting is the token parser.
    for tok in $KEYSEQ; do
        case "$tok" in
            # +28 converts window-relative Y to screen Y by skipping the window
            # manager title bar; X needs no adjustment because the window is
            # assumed flush with the left screen edge (see the loop above).
            # Remember haspointerevents=false: a click only moves focus, it is
            # never seen by the MIDlet - use it to reach emulator chrome, not
            # to press things inside the app.
            click:*) xy=${tok#click:}; x=${xy%,*}; y=${xy#*,}
                     xdotool mousemove --sync "$x" $((28 + y)) click 1 2>/dev/null || true ;;
            sleep:*) sleep "${tok#sleep:}" ;;
            # Explicit :1/:0 press+release pair rather than a bare keycode, so
            # the key is guaranteed to be released even if the app repaints
            # mid-press (hasrepeatevents=true would otherwise auto-repeat).
            *)       ydotool key "$tok":1 "$tok":0 2>/dev/null || true ;;
        esac
        # Fixed inter-key gap. tour.sh polls the frame md5 instead, which is
        # more robust under parallel-build CPU load; this script trades that
        # for simplicity since it only fires a handful of keys. Bump it or use
        # explicit sleep:N tokens if a step is racing.
        sleep 0.35
    done
    # Let the final key's repaint land before the screenshot.
    sleep 1
fi

shot "$OUT"
# %k (unique color count) is the cheap sanity signal: a handful of colors means
# we photographed the grayscale launcher or a blank frame, not the themed app.
# Note the emulator is deliberately NOT killed here - it stays up so you can
# poke at the state a capture landed in; the next run's kill_emu clears it.
echo "capture: wrote $OUT ($(identify -format '%wx%h %k colors' "$OUT" 2>/dev/null))"
