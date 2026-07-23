# ImageView folder navigation — design

2026-07-03

## Goal

When a photo is open in `nok.ui.ImageView`, LEFT/RIGHT shows the previous/next
photo in the same vault folder, so a folder of images can be flipped through
without going back to the Library between each one.

## Behavior

- **Fit mode (the default view):** LEFT shows the previous image, RIGHT the
  next, **wrapping** at both ends (matches the Library's wrap-around selection).
- **1:1 mode:** arrows keep panning, exactly as today. FIRE / left-soft still
  toggles fit ↔ 1:1. To flip photos from 1:1, toggle back to fit first.
- **Order:** the same order the Library shows — `Files.list` (case-insensitive
  by name), files only, dot-names skipped, `Path.isImage` extensions only
  (png/jpg/jpeg/gif/bmp).
- **Indicator:** when the folder holds ≥ 2 images, the soft-key bar's free
  center shows `n/m` (e.g. `2/7`); while a navigation load is in flight it
  shows `...` instead.
- Each newly shown photo opens **fit-to-screen with pan reset** (same as a
  fresh open).
- Works no matter where the image was opened from (Library row, embedded image
  in a note, search result) — siblings are derived from the image's own path.

## Approach

Chosen: **in-place swap inside ImageView** (vs. re-calling `m.openImage` per
step, or a new gallery screen). No new Displayable per photo, `Back` semantics
untouched (still lands on the Library), no launcher flicker, single-file UI
change.

- `ImageView` keeps `rel` (no longer just consumed in the constructor) and
  computes the sibling image-name Vector once in the constructor. The
  constructor already runs on a background thread (`openImage` wraps it), so
  the extra directory list is off the paint/event thread. A listing failure
  (or the current name missing from the listing) leaves siblings `null` and
  navigation silently disabled — the viewer behaves exactly as before.
- LEFT/RIGHT in fit mode starts a loader thread: `m.readBytes` + decode →
  swap `orig`, drop the cached fit image, reset fit/pan, `repaint()`. A
  `loading` flag drops further nav presses while in flight (no queueing, no
  key-repeat fly-through — decodes are slow on the E71).
- Decode/read failure of a sibling: themed error dialog (`m.alertErr`), and
  the current photo stays up (dialog dismiss returns to the viewer). It does
  **not** kick to the Library like a failed initial open.
- Pure logic — filtering a directory listing to image names and picking the
  wrapped sibling — lives in **`nok.core.ImageNav`** (Java 1.3 / CLDC 1.1, no
  javax imports) so it runs under `test.sh` on the desktop JVM:
  - `public static Vector images(Vector names)` — listing → image file names.
  - `public static String sibling(Vector imgs, String cur, int dir)` — wrapped
    prev/next name, `null` when `cur` is absent or has no distinct sibling.
- New `test/TestImageNav.java` main, added to the `test.sh` run list.

## Contracts

`CONTRACTS.md` § nok.ui.ImageView gains: LEFT/RIGHT at fit browse to the
prev/next image of the same folder (wrapping, listing order); soft bar center
shows `n/m`; arrows still pan at 1:1.

## Testing

- Desktop: `TestImageNav` covers filtering (dirs, dot-names, non-images
  skipped), wrap in both directions, single-image and absent-current cases.
- Emulator: tour script opens `attachments/banner.png` from the Library and
  flips RIGHT/RIGHT/LEFT, screenshotting each step (expect banner → diagram →
  logo → diagram, indicator 1/3 → 2/3 → 3/3 → 2/3).
