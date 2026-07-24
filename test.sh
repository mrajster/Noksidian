#!/bin/sh
# Desktop tests for the MIDP-free core (nok.core.*) on JDK 8.
#
# Why a desktop run is possible at all: CONTRACTS.md forbids nok.core.* from
# importing javax.*, precisely so the parser/merge/crypto/nav logic can be
# exercised on a normal JVM instead of inside an emulator. Everything else in
# src (ui, sync, sys, the MIDlet) is Canvas/JSR-75 bound and is NOT built here.
# The device build is build.sh; this script is deliberately a different, much
# shorter pipeline (no CLDC bootclasspath, no ProGuard preverify).

# A test signals failure by throwing RuntimeException from main(), i.e. a
# nonzero exit. Without set -e the for-loop below would swallow that, keep
# running the remaining suites, and still print "ALL TEST SUITES PASSED".
set -e
# Every path below is repo-relative, so anchor to the script's own directory
# and let the script be invoked from anywhere.
cd "$(dirname "$0")"
# Pinned JDK 8, not whatever "java" resolves to: the system JDK here is 26 and
# flatly rejects -source/-target 1.3 (support was dropped after 8). JDK 8 is
# the last release that still compiles the Java 1.3 dialect the E71 needs.
JDK="$(pwd)/tools/jdk8/bin"
# Wipe first: javac never deletes, so a renamed or removed test would leave a
# stale .class behind that the loop below would happily keep running (and
# passing) forever. Only build/test is nuked -- build.sh owns build/classes and
# build/pre, and clobbering those would force a full device rebuild.
rm -rf build/test
# javac -d will not create a missing output directory.
mkdir -p build/test
# nok/core only, on purpose: this narrow source set is what enforces the
# "nok.core imports no javax.*" contract. If someone slips a javax.microedition
# import into core, it is unresolvable on a desktop JVM and the compile below
# fails loudly -- which is the point, not an accident.
find src/nok/core -name '*.java' > build/testsources.txt
# Tests appended to the same list so core and tests compile in one pass; javac
# resolves the cross-references itself, so file order in here does not matter.
find test -name '*.java' >> build/testsources.txt
# @argfile rather than an inline glob: mirrors build.sh's convention, keeps the
# command line bounded as the core grows, and leaves the exact source set on
# disk in build/testsources.txt for inspection after a failure.
# -source/-target 1.3 matches build.sh so the language level is identical --
# generics, foreach or autoboxing sneaking into core get rejected here, at test
# time, instead of at the next device build.
# No -bootclasspath (build.sh passes the CLDC/MIDP stubs): the tests must see
# the real desktop rt.jar for System.out and, in TestVault, java.io file I/O --
# none of which the CLDC stubs provide. No ProGuard preverify pass either, as
# desktop HotSpot verifies at class load; preverification is a KVM requirement.
# -Xlint:-options silences the warnings JDK 8 emits for exactly this
# combination: obsolete source value, obsolete target value, and -- unlike
# build.sh, which supplies stubs -- "bootstrap class path not set in
# conjunction with -source 1.3". All three are expected here, and javac names
# this very flag as the remedy. Suppressing them keeps real warnings visible.
"$JDK/javac" -source 1.3 -target 1.3 -Xlint:-options -d build/test @build/testsources.txt
# Explicit allowlist instead of globbing build/test: the suites run in a fixed,
# readable order, and helper/inner classes in build/test are never mistaken for
# entry points. Adding a suite means adding it here as well as to test/.
# The suites are independent -- no shared state, no fixture files -- so the
# order is for reproducible output only, not a dependency chain.
# TestVault is run with no arguments on purpose: argument-less means "run the
# suites", whereas TestVault's main also has an interop CLI mode (desc/enc/
# enciv/dec) used to cross-check the hand-rolled AES/PBKDF2 against Python.
for T in TestBase64Json TestUtf8 TestCaps TestMd TestMdList TestMergePath TestSha TestAes TestVault TestImageNav; do
    # Same JDK 8 runtime as the compile, and a bare build/test classpath: no
    # CLDC jars, no dist/Noksidian.jar, so nothing can accidentally resolve
    # against a stale device build.
    "$JDK/java" -cp build/test "$T"
done
# Only reachable when every suite exited 0 (see set -e above); each suite has
# already printed its own contract-mandated "ALL PASS <n>" line.
echo "ALL TEST SUITES PASSED"
