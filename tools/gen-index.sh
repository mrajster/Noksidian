#!/bin/sh
# gen-index.sh <vault-dir>
#
# Writes <vault-dir>/noksidian/index : one vault-relative file path per line,
# matching what the app's in-vault scan (nok.sys.Files.scanAll) would produce.
# The Noksidian MIDlet loads this instead of scanning the vault itself, so the
# phone never has to open every directory (each open = a permission prompt on an
# untrusted MIDlet - a ~159-prompt storm on this vault). The index lets notes'
# wiki-links and by-basename image embeds (![[img.png]]) resolve to their real
# subfolder path.
#
# Rules mirror scanAll: every file EXCEPT anything under a path segment starting
# with '.' (dotfiles/dot-dirs) and anything under the noksidian/ dir itself.
# Regenerate whenever the vault's file set changes. Safe to re-run.
#
# On the phone this file is only the SECOND-tier cache. NoksidianMIDlet first
# tries the RMS copy (nok.sys.IndexStore), which needs no JSR-75 permission at
# all, and seeds RMS from this file on the first load after an install. RMS
# does not survive a reinstall; the memory card does, which is exactly why the
# on-card file has to exist. Reading it costs one prompt, once.
#
# Deliberately NOT for encrypted vaults: a plaintext list of every path must
# not sit on the card, so the phone skips this file entirely for an encrypted
# vault (and CryptoSetup deletes any stale copy). Running this against one just
# leaks path names.
#
# Kept to POSIX sh with no bashisms - it gets run from whatever shell happens
# to be on the machine that has the card mounted.
#
# set -u guards the expansions below: an unset variable silently becoming ""
# here would turn a prefix strip into a root walk rather than fail loudly.
set -u

# ${1:?} rather than defaulting to '.': indexing the wrong directory is not a
# harmless mistake, because the phone trusts a non-empty index wholesale and
# stops scanning once one loads. A missing argument must be fatal.
VAULT="${1:?usage: gen-index.sh <vault-dir>}"
# Catches the usual slip of pointing at an unmounted card. Without this the
# find below would just print nothing (its errors are silenced) and the mv
# would happily replace a good index with an empty one.
[ -d "$VAULT" ] || { echo "gen-index: not a directory: $VAULT" >&2; exit 1; }

# "noksidian", NOT ".noksidian": Symbian JSR-75 on the real E71 rejects any
# path segment beginning with '.' (IllegalArgumentException "url is not
# valid"), so the app's own directory cannot be hidden the usual way. The
# emulator accepts dot-dirs, which hid this until it hit real hardware.
OUT="$VAULT/noksidian/index"
# The dir may not exist yet on a freshly copied card; it is also where the app
# keeps sync state and where gen-sidecars.sh writes thumbs/.
mkdir -p "$VAULT/noksidian"
# Alongside OUT on purpose, so the mv below is a same-filesystem rename.
TMP="$OUT.tmp"

# Files only; drop any path containing a '/.'-segment (dotfiles/dirs) and the
# noksidian/ subtree; strip the "$VAULT/" prefix to make paths vault-relative.
#
# Why each piece, since all of it has to agree with Files.scanAll:
#   -type f      the index is a flat list of FILES; scanAll recurses into
#                directories rather than emitting them, so a dir entry here
#                would be an index entry no lookup could ever match.
#   ! -path "$VAULT/noksidian/*"
#                anchored at the vault ROOT deliberately. scanAll skips
#                "noksidian/" only at the root, so a user's own note folder
#                named "noksidian" further down still gets indexed - this
#                pattern reproduces that exactly. It also keeps this index and
#                the gen-sidecars.sh thumbs out of the list, which matters
#                because the index is what resolves ![[img.png]] embeds by
#                basename: a sidecar in there would be a second candidate for
#                every image.
#   ! -path '*/.*'
#                dot paths are skipped by scanAll AND unopenable through JSR-75
#                on the device, so an entry for one could never be followed.
#                Caveat: this matches the whole path, not just the part below
#                the vault, so a vault living under a dot-directory on this
#                machine filters down to an empty index.
#   2>/dev/null  an unreadable or vanishing entry on a mounted card is routine;
#                the walk is best-effort and must not be derailed by noise.
#   sed "s|...|" '|' is the delimiter because pattern and paths are both full of
#                '/'. Paths MUST end up vault-relative: the phone joins them
#                onto its own vault URL, which has nothing to do with this
#                machine's mount point. ($VAULT lands inside a regex unescaped,
#                so a vault path containing regex metacharacters or a literal
#                '|' would need quoting - real vault names have not.)
#   LC_ALL=C     plain byte order, so the output is reproducible whatever
#                locale generated it (a UTF-8 collation orders accented and
#                emoji names differently). Order is cosmetic to the phone -
#                NoteIndex holds paths unsorted and sorts on the way out - but
#                a stable order keeps re-runs diffable and groups each folder
#                together when eyeballing the file.
#
# The pipeline is line-oriented, with no -print0 (unlike gen-sidecars.sh),
# because the on-card format IS one path per line: the phone's reader splits on
# '\r'/'\n', so a filename containing a newline is unrepresentable by design.
# Spaces and emoji survive fine - nothing here word-splits.
find "$VAULT" -type f \
    ! -path "$VAULT/noksidian/*" \
    ! -path '*/.*' -print 2>/dev/null |
    sed "s|^$VAULT/||" |
    LC_ALL=C sort > "$TMP"

# Write-then-rename instead of redirecting straight into OUT: the card is
# usually mounted live and may be yanked mid-run, and a truncated index is
# worse than no index at all - the phone accepts any non-empty index and stops
# scanning, so missing lines show up as silently absent notes and dead links.
mv "$TMP" "$OUT"
# "< $OUT" rather than "wc -l $OUT" so wc emits only the number and not the
# filename; tr drops the leading padding some wc implementations add.
n=$(wc -l < "$OUT" | tr -d ' ')
# The count is the sanity check worth reading: it should track the vault's file
# count, and a sudden drop almost always means the wrong dir or a stale mount.
echo "gen-index: wrote $n paths -> $OUT"
