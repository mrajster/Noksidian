#!/bin/sh
# run-emu.sh - Run a J2ME MIDlet under MicroEmulator 2.0.4 for desktop smoke testing.
#
# Usage:
#   ./run-emu.sh [--headless] [path/to/MIDlet.jar]
#
#   (no jar)      defaults to dist/Noksidian.jar
#   --headless    launch with no window (org.microemu.app.Headless), capture
#                 stdout/stderr to tools/emu/last-run.log, wait ~20s, then report
#                 whether any exception was thrown during MIDlet startup.
#   (default)     launch the graphical MicroEmulator window (org.microemu.app.Main).
#
# Device:  320x240 (E71 landscape) via --resizableDevice.
# JSR-75:  FileConnection enabled; file:///E/ maps to tools/emu/fsroot/E/.
set -u

# No "set -e" on purpose. The pass/fail logic below is built out of grep exit codes
# (grep returning 1 for "no match" is the PASS case) and out of reaping a JVM we just
# killed, both of which errexit would treat as fatal. Every failure path here reports
# and exits explicitly instead.

# CDPATH= is a guard: with a CDPATH set in the environment, "cd -- somedir" can land in
# an entirely different directory and also echoes the resolved path to stdout, which
# would be captured into ROOT. The subshell leaves the caller's working directory
# untouched - that matters, because a relative jar argument is resolved against it below.
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
EMU_DIR="$ROOT/tools/emu"
MICROEMU_JAR="$EMU_DIR/microemulator.jar"
JSR75_JAR="$EMU_DIR/lib/microemu-jsr-75-2.0.4.jar"
# ME_HOME is a repo-local fake $HOME. MicroEmulator reads its entire configuration from
# $HOME/.microemulator/config2.xml, and that file - not this script - is what actually
# maps file:///E/ onto FSROOT (the "fsRoot" property of the FileSystem extension),
# registers the E71 device and holds the RMS record stores. Pinning user.home here keeps
# runs reproducible and keeps emulator state out of the developer's real ~/.microemulator.
ME_HOME="$EMU_DIR/me-home"
# FSROOT is only used for the human-readable banner. Editing it alone will NOT move the
# JSR-75 mapping; the authoritative value is the fsRoot property in config2.xml above.
FSROOT="$EMU_DIR/fsroot"
LOG="$EMU_DIR/last-run.log"
# MicroEmulator 2.0.4 is a 2010 build and is only known-good on the bundled JDK 8 - the
# same JDK build.sh compiles the MIDlet with. The system java is typically far newer.
JDK8="$ROOT/tools/jdk8/bin/java"
DEVICE_W=320
DEVICE_H=240
# Upper bound only. The poll loop in run_headless breaks as soon as the JVM exits, so a
# MIDlet that crashes on startup is reported in about a second, not after the full window.
WAIT_SECS=20

# usage() reprints this file's own header block, so the comments at the top are the single
# source of truth for --help. NR>=2 skips the shebang and the first non-comment line
# (set -u) ends the block - keep the header a contiguous run of "#" lines, since a blank
# line inserted in there would silently truncate the help output.
usage() {
    awk 'NR>=2 && /^#/ {sub(/^# ?/,""); print; next} NR>=2 {exit}' "$0"
}

# --- parse arguments -------------------------------------------------------
# Options and the jar may appear in either order, and a bare word is always taken as the
# jar (last one wins). Unknown --options are rejected rather than silently accepted as a
# jar path, which would otherwise surface much later as a confusing "jar not found".
HEADLESS=0
JAR=""
for arg in "$@"; do
    case "$arg" in
        --headless) HEADLESS=1 ;;
        -h|--help)  usage; exit 0 ;;
        --*)        echo "run-emu.sh: unknown option: $arg" >&2; exit 2 ;;
        *)          JAR="$arg" ;;
    esac
done

[ -n "$JAR" ] || JAR="$ROOT/dist/Noksidian.jar"
# resolve a relative jar path against the caller's working directory
# MicroEmulator converts this argument into a file:// URL before loading it (the
# "openJar [file:///...]" line in the log) and does not resolve it against the process
# working directory, so it has to be made absolute here or the load silently misses.
case "$JAR" in
    /*) ;;
    *)  JAR="$(pwd)/$JAR" ;;
esac

# --- sanity checks ---------------------------------------------------------
# Worth checking up front, because a missing jar is not a fast failure inside the
# emulator: it surfaces as a ClassNotFoundException for the FileConnection impl deep in
# the log, which reads like an app bug. Note the skin jar added to CP below is NOT checked
# here - when it goes missing the symptom is "Cannot find descriptor at: nok/device/e71.xml".
[ -f "$MICROEMU_JAR" ] || { echo "run-emu.sh: missing $MICROEMU_JAR" >&2; exit 1; }
[ -f "$JSR75_JAR" ]    || { echo "run-emu.sh: missing $JSR75_JAR" >&2; exit 1; }
if [ ! -f "$JAR" ]; then
    echo "run-emu.sh: MIDlet jar not found: $JAR" >&2
    [ "$JAR" = "$ROOT/dist/Noksidian.jar" ] && echo "  (build it first, or pass a jar path)" >&2
    exit 1
fi

# --- select java (prefer bundled jdk8, fall back to system java) -----------
# The -x test alone is not enough: a half-extracted or wrong-arch JDK is still marked
# executable, so actually probe it with -version. JAVA_LABEL exists only so the report
# states which JVM produced the result - a PASS on a fallback JVM is weaker evidence than
# a PASS on the JDK 8 the MIDlet was built against.
JAVA_LABEL=""
if [ -x "$JDK8" ] && "$JDK8" -version >/dev/null 2>&1; then
    JAVA="$JDK8"; JAVA_LABEL="tools/jdk8 (1.8)"
else
    JAVA="$(command -v java 2>/dev/null || true)"; JAVA_LABEL="system java"
fi
[ -n "$JAVA" ] || { echo "run-emu.sh: no usable java found" >&2; exit 1; }

# microemu jars MUST come first on the classpath
# device-e71.jar carries no code - only nok/device/e71.xml and its skin PNG, which
# DeviceImpl.loadDeviceDescriptor looks up as a classpath *resource*; that is why --device
# below takes a resource path rather than a filename. It is appended last so nothing in
# the skin jar can shadow the emulator's own org/microemu/device resources.
# We ship our own device XML mainly for its <background>ffffff: MicroEmulator multiplies
# every emitted pixel by that "LCD backlight" colour, and the stock c0c0c0 dimmed every
# screenshot to ~75% brightness, masking real colour bugs during visual verification.
CP="$MICROEMU_JAR:$JSR75_JAR:$EMU_DIR/device-e71.jar"

# patterns that indicate a startup failure in the emulator log
# MicroEmulator's logger prefixes genuine errors with a lowercase "error ", and it also
# echoes a call-location line after every single message - indented exactly like a stack
# frame but WITHOUT the "at " keyword. Hence the literal "at " in the second alternative:
# it is what keeps ordinary log decoration from being reported as a crash.
# SMOKE_FILECONN_FAIL is an app-side sentinel a smoke build can print when its
# FileConnection probe fails; it is not an exception, so it must be listed explicitly.
FAIL_PATTERN='^error |^[[:space:]]+at [[:alnum:]_.$]|[[:alnum:]_.]+Exception|[[:alnum:]_.]+Error|SMOKE_FILECONN_FAIL'
# JVM-level failures that justify retrying with a different java
# These are launcher/verifier failures: the JVM never got far enough to say anything about
# the MIDlet, so re-running on another java is meaningful. Any other failure is a real
# result about the app and must NOT trigger a retry.
JVM_FAIL='UnsupportedClassVersionError|Unsupported major.minor|Could not create the Java Virtual Machine|A JNI error'

run_headless() {
    # $1 = java binary to use
    # Truncate before launching: the verdict is a grep over this file, so a stale
    # exception from an earlier run would otherwise be reported as this run's failure.
    # The retry path re-enters this function and re-truncates for the same reason.
    : > "$LOG"
    # java.awt.headless=true is required even for org.microemu.app.Headless: it still
    # initialises AWT for font metrics and the offscreen frame buffer, and without this
    # it tries to reach an X display and dies on a bare terminal or in CI.
    # --rms memory keeps record stores in RAM. A smoke run must not persist into
    # ME_HOME/.microemulator/suite-Noksidian/*.rs, or a half-written vault path or config
    # from one run leaks into the next and the test stops being reproducible. Graphical
    # mode below deliberately omits the flag so settings survive interactive sessions.
    "$1" -Djava.awt.headless=true -Duser.home="$ME_HOME" \
        -cp "$CP" org.microemu.app.Headless \
        --device nok/device/e71.xml \
        --impl org.microemu.cldc.file.FileSystem \
        --rms memory "$JAR" >"$LOG" 2>&1 &
    jpid=$!
    # Background it and poll rather than blocking on wait: POSIX sh has no timeout on
    # wait, and polling notices a JVM that dies immediately (kill -0 fails) instead of
    # sitting out the whole WAIT_SECS window before looking at the log.
    i=0
    while [ "$i" -lt "$WAIT_SECS" ]; do
        kill -0 "$jpid" 2>/dev/null || break
        sleep 1
        i=$((i + 1))
    done
    if kill -0 "$jpid" 2>/dev/null; then
        # still running after the wait window == it did not crash out; stop it
        # There is no "started OK" exit code to wait for - surviving the window IS the
        # success signal. SIGTERM first so the MIDlet's shutdown path can run and flush
        # whatever it has open; SIGKILL a second later only for a JVM that ignored it.
        kill "$jpid" 2>/dev/null
        sleep 1
        kill -9 "$jpid" 2>/dev/null
    fi
    # Reap the child so the shell does not print its own "Terminated" job message into
    # the middle of the report. The status is intentionally ignored - we just killed it.
    wait "$jpid" 2>/dev/null
}

if [ "$HEADLESS" = 1 ]; then
    run_headless "$JAVA"

    # if the bundled jdk8 blew up at the JVM level, retry with system java
    # Guarded on JAVA = JDK8 so the fallback can happen at most once and never retries
    # the system java against itself.
    if [ "$JAVA" = "$JDK8" ] && grep -Eq "$JVM_FAIL" "$LOG"; then
        SYS="$(command -v java 2>/dev/null || true)"
        if [ -n "$SYS" ] && [ "$SYS" != "$JDK8" ]; then
            echo "run-emu.sh: jdk8 failed at the JVM level; retrying with system java" >&2
            JAVA="$SYS"; JAVA_LABEL="system java (fallback from jdk8)"
            run_headless "$JAVA"
        fi
    fi

    echo "=== MicroEmulator headless smoke test ==="
    echo "Java:    $JAVA_LABEL"
    echo "MIDlet:  $JAR"
    echo "Device:  ${DEVICE_W}x${DEVICE_H} (resizable)"
    echo "FS root: file:///E/ -> $FSROOT/E/"
    echo "Log:     $LOG"
    echo

    # head -20 keeps the report readable when a single exception cascades into hundreds
    # of matching lines; the full log path is printed right after, so nothing is lost.
    if grep -Eq "$FAIL_PATTERN" "$LOG"; then
        echo "RESULT: FAIL - exception(s) detected during MIDlet startup:"
        grep -nE "$FAIL_PATTERN" "$LOG" | head -20 | sed 's/^/  /'
        echo
        echo "See full log: $LOG"
        exit 1
    fi

    echo "RESULT: PASS - MIDlet started, no exception in the log."
    echo "Startup log (call-location traces omitted):"
    # Drops the logger's indented call-location lines described at FAIL_PATTERN (one per
    # message) and then blank lines, so the summary shows actual startup messages instead
    # of alternating with org.microemu.app.Common frames.
    grep -vE '^[[:space:]]+[[:alnum:]]' "$LOG" | grep . | head -20 | sed 's/^/  /'
    exit 0
fi

# --- graphical mode --------------------------------------------------------
# Checked explicitly because MicroEmulator's Swing UI otherwise dies with a raw
# HeadlessException / X connection error that says nothing about the real cause; --headless
# is the mode that works over SSH or in CI. The ":-" defaults are mandatory under set -u,
# which would abort on an unset DISPLAY instead of taking this branch.
if [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
    echo "run-emu.sh: no DISPLAY/WAYLAND_DISPLAY - graphical mode needs a desktop session." >&2
    echo "  Run headless instead:  $0 --headless \"$JAR\"" >&2
    exit 1
fi

# exec rather than a plain call: tools/emu/tour.sh and tools/emu/capture.sh start this
# script in the background and then drive and kill it by that PID, so the JVM has to
# replace the shell instead of hiding behind a wrapper process that would absorb the
# signal and leave an orphaned emulator window behind.
echo "Launching MicroEmulator GUI: $JAR  ($JAVA_LABEL, ${DEVICE_W}x${DEVICE_H}, file:///E/ -> $FSROOT/E/)"
exec "$JAVA" -Duser.home="$ME_HOME" \
    -cp "$CP" org.microemu.app.Main \
    --device nok/device/e71.xml \
    --impl org.microemu.cldc.file.FileSystem \
    "$JAR"
