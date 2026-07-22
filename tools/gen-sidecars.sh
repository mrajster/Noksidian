#!/bin/sh
# gen-sidecars.sh <vault-dir> [max-px] [box]
#
# Generates downscaled JPEG "sidecar" previews for every image in a Noksidian
# vault that is too large to decode on the Nokia E71 (~2MB Java heap). The
# phone-side ImageView/Viewer prefer  <vault>/noksidian/thumbs/<rel>.jpg  when it
# exists, so big photos display instantly at good quality instead of showing an
# "Image too large" placeholder.
#
#   <vault-dir>  vault root (e.g. a mounted card: /run/claude-e71/My awsume vault)
#   max-px       only images with w*h > this get a sidecar   (default 1200000)
#   box          sidecar fits inside box x box px             (default 640)
#
# Sidecars mirror the vault-relative path under noksidian/thumbs/ and append
# ".jpg" (so "a/b.png" -> "noksidian/thumbs/a/b.png.jpg"), matching the phone's
# lookup exactly. Regenerated only when the source is newer than its sidecar.
# The vault's original images are never modified. Safe to re-run (idempotent).
#
# Requires ImageMagick (identify + convert/magick). NOTE: dot-directories are
# avoided on purpose - Symbian JSR-75 rejects path segments starting with '.'.
#
# The max-px default is not arbitrary: 1200000 is the same number as
# Viewer.PIX_MAX / ImageView.PIX_MAX on the phone, i.e. exactly the header-probe
# gate that decides "placeholder or decode". Keep the three in sync - a larger
# value here leaves photos the phone will refuse without a sidecar, a smaller
# one burns card space on sidecars nothing needed.
#
# Order relative to gen-index.sh does not matter: sidecars live under noksidian/,
# and gen-index.sh excludes that subtree, so they never leak into the note index
# and can never be resolved as wiki-link / ![[img.png]] targets themselves.
#
# set -u only, deliberately no set -e: a single corrupt or exotic image in a
# vault of hundreds must be counted and skipped, not abort the whole run.
set -u

VAULT="${1:?usage: gen-sidecars.sh <vault-dir> [max-px] [box]}"
MAXPX="${2:-1200000}"
# 640 is ~2x the E71's 320px long screen edge: the fit view still has pixels to
# spare, and the viewer's 1:1 pan mode shows real detail instead of mush, while
# a 640px baseline JPEG still decodes comfortably inside the ~2MB heap.
BOX="${3:-640}"

[ -d "$VAULT" ] || { echo "gen-sidecars: not a directory: $VAULT" >&2; exit 1; }
command -v identify >/dev/null 2>&1 || { echo "gen-sidecars: ImageMagick 'identify' missing" >&2; exit 1; }
# ImageMagick 7 renamed the tool to 'magick' and ships 'convert' only as a
# deprecated shim (warns, and is dropped in some distro packages), so prefer
# 'magick' when present and fall back to IM6's 'convert'.
if command -v magick >/dev/null 2>&1; then CONV="magick"; else CONV="convert"; fi

THUMBS="$VAULT/noksidian/thumbs"
made=0; skipped=0; small=0; failed=0

# Walk images with a NUL-safe loop so spaces/emoji in names survive.
# (Vault cards are mounted vfat with iocharset=utf8, so emoji folder names are
# real and routine here; word-splitting on IFS would shred them.)
# The noksidian/ prune is load-bearing, not tidiness: without it a second run
# would find the sidecars themselves, re-thumbnail them, and grow
# thumbs/noksidian/thumbs/... on every invocation. find's stderr is dropped
# because a mounted phone card readily produces permission/IO noise mid-walk.
find "$VAULT" -type f \
    \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) \
    ! -path "$VAULT/noksidian/*" -print0 2>/dev/null |
while IFS= read -r -d '' src; do
    # Vault-relative key. This string is the contract with the phone: it has to
    # match ImageView.sidecarPath()'s input byte-for-byte or the lookup misses.
    rel="${src#$VAULT/}"
    # dimensions -> pixels
    # head -1 because multi-frame/multi-layer inputs make identify print one
    # line per frame; frame 0 is what gets converted below.
    dim=$(identify -format '%w %h' "$src" 2>/dev/null | head -1)
    w=$(printf '%s' "$dim" | cut -d' ' -f1)
    h=$(printf '%s' "$dim" | cut -d' ' -f2)
    # Must run BEFORE the multiply: if identify failed (truncated file, unknown
    # format) $w/$h are empty or text, and $(( )) on that is a fatal shell error
    # in dash/ash - it would kill the loop instead of skipping one image. One
    # concatenated test covers both operands and the empty case.
    case "$w$h" in *[!0-9]*|'') failed=$((failed+1)); echo "  ?? unreadable: $rel" >&2; continue ;; esac
    if [ "$((w * h))" -le "$MAXPX" ]; then
        small=$((small+1)); continue   # small enough to decode on-device as-is
    fi
    # ".jpg" is APPENDED, never substituted for the real extension: "a/b.png"
    # -> "a/b.png.jpg". That keeps a.png and a.jpg from colliding on one sidecar
    # and is precisely the THUMB_DIR + rel + ".jpg" the phone builds.
    out="$THUMBS/$rel.jpg"
    # up-to-date? (sidecar exists and is newer than source)
    # Freshness is mtime-only, so changing BOX or -quality does NOT invalidate
    # anything already rendered: to re-render at a new size, delete
    # noksidian/thumbs/ first. Also note FAT stores mtimes at 2s granularity, so
    # an edit within the same 2s tick as the last run can look "not newer".
    if [ -f "$out" ] && [ ! "$src" -nt "$out" ]; then
        skipped=$((skipped+1)); continue
    fi
    # Recreate the source's subdirectory chain under thumbs/ so the mirrored
    # relative path exists before convert writes into it.
    mkdir -p "$(dirname "$out")"
    # downscale to fit BOXxBOX, flatten alpha to white, strip metadata, sRGB,
    # progressive OFF (baseline only - the phone decodes baseline JPEG).
    #
    # Every flag below earns its place; the order of the first two matters:
    #   "$src[0]"        frame/layer selector. Without it a multi-layer PNG or
    #                    multi-frame input makes ImageMagick write out-0.jpg,
    #                    out-1.jpg, ... and never the plain "$out" the phone
    #                    looks for - the sidecar would silently not exist.
    #   -auto-orient     bakes EXIF Orientation into actual pixels. MIDP's
    #                    Image.createImage ignores the EXIF tag, so phone-camera
    #                    portrait shots would otherwise show up sideways. Must
    #                    come before -strip, which throws the tag away, and
    #                    before -resize so the box applies to the final axes.
    #   -resize "NxN>"   the '>' is shrink-only; small-but-over-budget images
    #                    (e.g. very tall panoramas) are never upscaled.
    #   -background white -flatten
    #                    JPEG has no alpha channel; transparent PNG regions
    #                    would decode as black without this. White matches the
    #                    viewer's background so flattening is invisible.
    #   -strip           drops EXIF/ICC/embedded thumbnails: smaller files on a
    #                    slow card, and no huge APP1 blocks for ImgProbe's
    #                    header scan to skip before it reaches the SOFn.
    #   -colorspace sRGB -strip removed the ICC profile, so the pixels must
    #                    already be sRGB; CMYK or wide-gamut sources would
    #                    otherwise render with shifted colors on the phone.
    #   -interlace none  the critical one: forces baseline (SOF0). ImgProbe
    #                    classifies progressive JPEG (SOF2) as KIND_UNSUPPORTED
    #                    and the E71's native decoder cannot read it at all, so
    #                    a progressive sidecar is a guaranteed placeholder.
    #   -quality 85      visually transparent at 640px while keeping sidecars
    #                    small enough that a whole vault fits comfortably.
    # convert's stderr is dropped because ImageMagick warns loudly about benign
    # profile/depth issues; the exit status is the real success signal, which is
    # why this is an if-test (there is no set -e to catch it).
    if "$CONV" "$src[0]" \
            -auto-orient -resize "${BOX}x${BOX}>" \
            -background white -flatten -strip \
            -colorspace sRGB -interlace none -quality 85 \
            "$out" 2>/dev/null; then
        made=$((made+1))
        printf '  ok  %s  (%sx%s -> %s)\n' "$rel" "$w" "$h" "$(identify -format '%wx%h' "$out" 2>/dev/null)"
    else
        failed=$((failed+1)); echo "  !! convert failed: $rel" >&2
    fi
done

# The subshell above ran in a pipeline, so counters don't survive; recompute a
# summary from the filesystem for an accurate final line.
# (The usual fixes - process substitution or a lastpipe shopt - are bash-only,
# and this script stays /bin/sh so it runs under dash/ash/busybox too.)
# Note this counts every sidecar PRESENT, including ones earlier runs made, not
# just the ones created now; that is the number worth knowing before unmounting.
n=$(find "$THUMBS" -type f -name '*.jpg' 2>/dev/null | wc -l)
echo "gen-sidecars: done. $n sidecar(s) present in $THUMBS"
