#!/bin/sh
# xvfb-tour.sh <script-file> <shots-dir> — drive MicroEmulator on DISPLAY=:99
# (isolated Xvfb, XTEST keys; no interference with the user's session).
# Same script grammar as tools/emu/tour.sh: key/sleep/shot lines, but key
# takes xdotool key NAMES (Return, Down, Up, Left, Right, F1, F2).
#
# WHY a separate :99 driver instead of tour.sh: tour.sh drives the emulator
# with ydotool, which injects at the uinput level, so keystrokes land in
# whatever the COMPOSITOR has focused. Under a Wayland session that is
# usually the user's terminal, not the emulator - wmctrl/xdotool only move
# Xwayland focus and `xdotool getactivewindow` reports the wrong window. The
# whole tour then silently types into the launcher (or worse, into the user's
# apps). An isolated Xvfb has exactly one client, so XTEST delivery is
# deterministic and stray keys cannot escape into the real session.
#
# Use F1/F2 (soft keys, -6/-7) where a desktop app would use Enter: FIRE /
# SELECT / Enter is never delivered to the MIDlet Canvas by MicroEmulator, so
# "open this item" must be a soft key. Enter IS honoured by the Swing MIDlet
# launcher below, which is a different widget entirely.
#
# PRECONDITION: this script ATTACHES to an already-running emulator - it does
# not start Xvfb, does not launch run-emu.sh, and does not kill anything on
# exit. The caller owns `Xvfb :99` plus `DISPLAY=:99 ./run-emu.sh ...` and
# their teardown (including any RMS/fsroot reset, which tour.sh does itself).
set -u
export DISPLAY=:99
SCRIPT="$1"; SHOTS="$2"
mkdir -p "$SHOTS"

# Anchored '^MicroEmulator$': the emulator owns several windows (the AWT
# FocusProxy child, menus), so an unanchored match can return a helper window
# instead of the visible frame - and everything below screenshots $WIN.
WIN=$(xdotool search --name '^MicroEmulator$' | head -1)
[ -z "$WIN" ] && { echo "no emulator window on :99" >&2; exit 1; }
# No window manager runs on :99, so nothing maps the frame for us. An
# unmapped window has no viewable contents and every `import` below would
# fail; force it mapped before touching geometry or pixels.
xdotool windowmap "$WIN" 2>/dev/null || true
# WM-less Xvfb: give AWT focus with a real XTEST click inside the window,
# then pin X input focus; --window key delivery is ignored by AWT.
# Defines X/Y/WIDTH/HEIGHT in this shell. Must run before the first click,
# and the values are reused verbatim by the post-launch re-click further
# down - with no WM the window is never moved or resized, so one probe holds
# for the whole run.
eval "$(xdotool getwindowgeometry --shell "$WIN")"
# A synthetic focus request alone does not convince AWT it is active; a real
# XTEST button press in the middle of the canvas does. The click itself is
# harmless to the app: the E71 device profile has haspointerevents=false, so
# it focuses the Swing component but is never delivered as a MIDlet event.
xdotool mousemove --sync $((X + WIDTH / 2)) $((Y + HEIGHT / 2)) click 1
sleep 0.5
xdotool windowfocus --sync "$WIN" 2>/dev/null || true
# AWT parks a hidden 'FocusProxy' child window that is the real keyboard
# sink; with no WM to route focus, the top-level alone still drops keys.
# It is looked up fresh (not cached) because AWT recreates it. Everything is
# best-effort: on some MicroEmulator builds no proxy exists at all.
FOCUSPROXY=$(xdotool search --name '^FocusProxy$' | head -1)
[ -n "$FOCUSPROXY" ] && xdotool windowfocus --sync "$FOCUSPROXY" 2>/dev/null || true

# frame() is the synchronization primitive for the whole script: a hash of
# the current window pixels. Fixed sleeps are unreliable here because a
# parallel build can starve the emulator JVM for seconds, so every step waits
# for the picture to actually change instead of guessing a duration. import
# is targeted at $WIN rather than the root window so the hash covers only the
# emulator (and so captures work at all on a WM-less, wallpaper-less :99).
frame() { import -window "$WIN" png:- 2>/dev/null | md5sum | cut -d' ' -f1; }
shot()  { import -window "$WIN" "$1" 2>/dev/null; }

# launch the MIDlet: Noksidian preselected in launcher, Enter starts it;
# poll until the frame leaves the launcher (keys during init are dropped).
# Return works HERE (Swing launcher list) even though it is a no-op once the
# MIDlet Canvas owns the screen - do not "simplify" this to F2, which maps to
# Exit on several screens. Baseline the launcher frame BEFORE the keypress,
# otherwise the comparison below races the repaint.
LF=$(frame)
xdotool key Return
i=0
# Poll rather than sleep: keys sent while the MIDlet is still initializing are
# dropped wholesale (verified - fixed sleeps plus sacrificial keys all landed
# in the dead window), so the only safe signal is the launcher pixels going
# away. Budget is 24 * 0.5s = ~12s, which covers a cold JVM under load.
while [ "$i" -lt 24 ]; do
    sleep 0.5
    F=$(frame)
    [ -n "$F" ] && [ "$F" != "$LF" ] && break
    i=$((i + 1))
done
# Hard fail instead of continuing: a tour that never left the launcher would
# otherwise "succeed" and write a directory full of identical screenshots.
[ "$i" -ge 24 ] && { echo "MIDlet never started (frame stuck at launcher)" >&2; exit 1; }
# The first differing frame is often a half-painted splash; settle before
# taking focus so the click lands on the finished canvas.
sleep 1.5
# The focus dance above is NOT redundant with this one: launching swaps the
# launcher list out for the MIDlet canvas, a brand-new component, and AWT
# recreates its FocusProxy along with it. Skipping this block leaves focus on
# a widget that no longer exists and every subsequent key vanishes.
# re-focus the swapped-in canvas (same as tour.sh): click + refocus proxy
xdotool mousemove --sync $((X + WIDTH / 2)) $((Y + HEIGHT / 2)) click 1
sleep 0.5
xdotool windowfocus --sync "$WIN" 2>/dev/null || true
FOCUSPROXY=$(xdotool search --name '^FocusProxy$' | head -1)
[ -n "$FOCUSPROXY" ] && xdotool windowfocus --sync "$FOCUSPROXY" 2>/dev/null || true
# Unconditional forensic capture: when a tour later produces nonsense, this
# is the frame that tells you whether the app actually booted or whether the
# run was stuck in the launcher the whole time.
shot "$SHOTS/debug-postlaunch.png"

# input-liveness probe: Down until the frame moves, then Up to restore.
# Proves the whole XTEST -> X focus -> AWT -> MIDlet chain is live before any
# real script step runs. Without it a broken focus chain is invisible: the
# tour completes "successfully" with every shot showing the same screen.
# Arrows are used because the d-pad IS delivered reliably, unlike FIRE.
# CAVEAT: this assumes the boot screen reacts to Down (a Library list). On a
# non-list boot screen such as Welcome nothing moves and the probe reports a
# false "input DEAD" - tour.sh treats the same situation as a harmless
# fallthrough, this script exits 1.
PF=$(frame)
i=0
while [ "$i" -lt 6 ]; do
    xdotool key Down
    sleep 0.7
    F=$(frame)
    [ -n "$F" ] && [ "$F" != "$PF" ] && break
    i=$((i + 1))
done
[ "$i" -ge 6 ] && { echo "input DEAD on :99" >&2; exit 1; }
# Only reached when the probe succeeded (the check above exits otherwise), so
# exactly one Down needs undoing: restore selection 0 to hand the tour script
# a deterministic starting state.
xdotool key Up
sleep 0.7

# Script interpreter. FAILED accumulates across the run instead of aborting
# on the first bad step, so one unwritable shot still leaves the rest of the
# tour captured for inspection; it becomes the exit status at the end.
FAILED=0
# `IFS= read -r` keeps leading whitespace and backslashes literal; the
# `|| [ -n "$line" ]` tail runs the final line of a script file that has no
# trailing newline, which read would otherwise discard.
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|\#*) continue ;; esac
    # POSIX split into first word + remainder. The guard matters for
    # argument-less commands: when $line has no space, ${line#* } expands to
    # the whole line, so `rest` would wrongly repeat the command name.
    cmd=${line%% *}; rest=${line#* }; [ "$rest" = "$line" ] && rest=""
    case "$cmd" in
        key)
            # $rest is deliberately unquoted: one `key` line may list several
            # key names, each stepped and verified independently.
            for k in $rest; do
                # Re-baseline before every key - the previous key's repaint
                # must already be part of B or the wait below exits instantly.
                B=$(frame)
                # Plain XTEST, no --window: AWT ignores the XSendEvent-based
                # `xdotool key --window` delivery entirely, so keys must go
                # through the focused window established above. xdotool's own
                # press/release pair is long enough here; the explicit ~90ms
                # hold tour.sh needs is a ydotool workaround, not an AWT one.
                xdotool key "$k"
                j=0
                # Frame-driven stepping instead of a fixed sleep (CPU
                # contention from parallel builds makes those flaky). Keys
                # that legitimately change nothing - a Down at the end of a
                # list - just fall through after the ~2.5s budget.
                while [ "$j" -lt 10 ]; do
                    sleep 0.25
                    F=$(frame)
                    [ -n "$F" ] && [ "$F" != "$B" ] && break
                    j=$((j + 1))
                done
                # The loop breaks on the FIRST changed pixel, which may be a
                # partial repaint; let it finish before the next baseline.
                sleep 0.2
            done ;;
        # Escape hatch for waits the frame-hash cannot express: animations,
        # or a screen that repaints identically after the key that triggered it.
        sleep) sleep "$rest" ;;
        shot)
            # Settle before capturing - `key` returns on the first changed
            # frame, so without this the shot can catch a mid-repaint screen.
            sleep 0.4
            if shot "$SHOTS/$rest.png"; then echo "shot $SHOTS/$rest.png"
            else echo "SHOT FAILED $rest" >&2; FAILED=1; fi ;;
    # NOTE: unlike tour.sh there is no default case, so a typo'd command (or
    # a tour.sh-only `type`/`click`/`raise` line) is skipped in silence.
    esac
# Redirect on `done`, not a pipe: a pipeline would run this loop in a
# subshell and the FAILED accumulated inside it would be lost at exit.
done < "$SCRIPT"
echo "xvfb-tour: done (failed=$FAILED)"
# Propagate as exit status so callers can gate a build/CI step on the tour.
# Note the emulator is intentionally left running - see PRECONDITION above -
# so several tours can share one boot; the caller tears :99 down.
exit "$FAILED"
