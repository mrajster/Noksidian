#!/bin/sh
# Noksidian build: javac (Java 1.3 bytecode) -> ProGuard CLDC preverify -> MIDlet jar + jad
#
# Why this pipeline instead of a plain JDK build:
#   - CLDC 1.1 on the E71 uses a split verifier. Class files must be major
#     version 47 (Java 1.3) and carry CLDC StackMap attributes; the
#     StackMapTable frames javac emits from -target 1.6 onward are rejected
#     on device.
#   - The classic Sun WTK "preverify" binary is a 32-bit x86 relic. ProGuard's
#     -microedition mode performs the same preverification as a pure-Java
#     step, so the build stays reproducible on a modern 64-bit Linux box with
#     no ia32 runtime libraries installed.
set -e
# set -e is load-bearing: a failed javac/ProGuard run must not leave the
# previous dist/Noksidian.jar in place. That jar gets copied straight to the
# phone, and silently flashing a stale build is very hard to spot on device.
cd "$(dirname "$0")"
ROOT="$(pwd)"
# ROOT must be absolute: the packaging step below runs in a subshell that cd's
# into build/pre, so the jar output path and the manifest path both have to
# survive that directory change.
JDK="$ROOT/tools/jdk8/bin"
# The vendored JDK 8 is not interchangeable with the system JDK. JDK 8 is the
# last release whose javac still accepts -source/-target 1.3; JDK 9 dropped
# support for everything below 6, so a newer toolchain cannot produce class
# files this device will load.
LIB="$ROOT/tools/lib"
BOOT="$LIB/cldcapi11-2.0.4.jar:$LIB/midpapi20-2.0.4.jar:$LIB/microemu-jsr-75-2.0.4.jar"
# BOOT is the complete API surface the sources are allowed to touch. Passing it
# as -bootclasspath (rather than -classpath) replaces the desktop java.lang, so
# anything absent from CLDC 1.1 -- String.split, collections, reflection,
# java.io.File -- fails at compile time here instead of throwing
# NoClassDefFoundError on the handset.
# The JSR-75 jar is MicroEmulator's; it is needed only to compile the
# FileConnection calls in nok/sys/Files.java and nok/ui/VaultPicker.java. It is
# a library, never bundled -- the phone supplies its own implementation.

rm -rf build dist
# Full wipe every time. ProGuard writes into build/pre without pruning it, and
# "jar cfm" only adds entries, so classes belonging to deleted or renamed
# sources would otherwise keep riding along in the shipped jar.
mkdir -p build/classes build/pre dist

echo "== compiling =="
find src -name '*.java' > build/sources.txt
# The @argfile indirection exists because the source list is long enough to be
# awkward on one command line, and it keeps the file set explicit rather than
# relying on javac's implicit sourcepath discovery.
"$JDK/javac" -source 1.3 -target 1.3 -Xlint:-options -bootclasspath "$BOOT" \
    -d build/classes @build/sources.txt
# -Xlint:-options silences the three unavoidable warnings JDK 8 emits for this
# configuration ("source value 1.3 is obsolete", the matching target warning,
# and the bootclasspath advisory). javac itself names this exact flag as the
# remedy, and suppressing them keeps genuine warnings visible.

echo "== preverifying (ProGuard -microedition) =="
"$JDK/java" -jar "$ROOT/tools/proguard/lib/proguard.jar" \
    -microedition -dontoptimize -dontobfuscate -dontnote \
    -libraryjars "$BOOT" \
    -injars build/classes \
    -outjars build/pre \
    -keep 'public class nok.NoksidianMIDlet { *; }' >/dev/null
# -microedition switches the preverifier to CLDC StackMap output; this is the
#   step that makes the class files loadable by the device verifier.
# -dontoptimize / -dontobfuscate: optimization has a history of miscompiling
#   against the CLDC runtime, and obfuscated names turn on-device stack traces
#   into noise. Shrinking stays ON, which is why the -keep below is needed.
# -keep on the MIDlet is the sole shrinking root: everything reachable from the
#   entry class survives, the rest is dropped to hold the jar down for the
#   E71's install budget. Safe only because the code has no reflective entry
#   points -- if a class ever gets loaded via Class.forName, it must be added
#   here or it will vanish from the build.
# -libraryjars (not -injars) for BOOT keeps the CLDC/MIDP/JSR-75 classes out of
#   the output; bundling them would bloat the jar and shadow the phone's own
#   implementations.
# -dontnote drops ProGuard's advisory notes (the CLDC and MIDP jars overlap and
#   generate duplicate-definition chatter); >/dev/null drops the remaining
#   progress output. Real errors still go to stderr and still trip set -e.

echo "== packaging =="
cp res/icon.png build/pre/icon.png
# The icon is injected after preverification, not kept in build/classes, so it
# never passes through ProGuard as an input resource. It lands at the jar root
# because the MIDlet-Icon attribute below refers to it as "/icon.png".
cp -r res/emoji build/pre/emoji
# The emoji glyph pack (124 strip PNGs p0..p123 + index.bin) is injected the
# same way, landing at the jar root as "/emoji/*" so nok.core.Emoji can load
# "/emoji/index.bin" and the Viewer can decode "/emoji/pN.png". Kept out of
# build/classes for the same reason as the icon: it is a resource, not code, so
# it must not pass through ProGuard's shrinker/preverifier as an input.
cat > build/MANIFEST.MF <<EOF
MIDlet-1: Noksidian, /icon.png, nok.NoksidianMIDlet
MIDlet-Name: Noksidian
MIDlet-Vendor: winsucker
MIDlet-Version: 1.4.0
MIDlet-Icon: /icon.png
MIDlet-Description: Obsidian-style markdown vault with GitHub sync
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
EOF
# MIDlet-1 is positional: "display name, icon path, main class" -- the order is
# fixed by the MIDP spec, not a convention.
# MicroEdition-Configuration/-Profile must match what the handset advertises or
# the S60 installer refuses the suite outright.
# Note the version, name and vendor are duplicated in the JAD below; Symbian
# aborts installation if the two disagree, so all three fields have to be
# edited in lockstep whenever the release is bumped.
(cd build/pre && "$JDK/jar" cfm "$ROOT/dist/Noksidian.jar" "$ROOT/build/MANIFEST.MF" .)
# The subshell cd is what makes archive paths relative to build/pre, so entries
# are stored as "nok/..." and "icon.png" rather than "build/pre/nok/...". A
# leading "build/pre" in the paths would leave the main class unfindable.

SIZE=$(stat -c %s dist/Noksidian.jar)
cat > dist/Noksidian.jad <<EOF
MIDlet-1: Noksidian, /icon.png, nok.NoksidianMIDlet
MIDlet-Name: Noksidian
MIDlet-Vendor: winsucker
MIDlet-Version: 1.4.0
MIDlet-Icon: /icon.png
MIDlet-Description: Obsidian-style markdown vault with GitHub sync
MIDlet-Jar-URL: Noksidian.jar
MIDlet-Jar-Size: $SIZE
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
EOF
# Ordering requirement: the JAD can only be written after the jar exists,
# because MIDlet-Jar-Size must be the exact byte count. The E71 checks it
# against the downloaded jar and rejects the install on any mismatch, so the
# size can never be hardcoded or computed ahead of packaging.
# MIDlet-Jar-URL is relative, which is why the jad and jar have to be kept
# side by side when copying to the phone or serving over OTA.
echo "OK: dist/Noksidian.jar ($SIZE bytes) + dist/Noksidian.jad"
