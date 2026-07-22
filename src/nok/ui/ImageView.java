package nok.ui;

import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import nok.NoksidianMIDlet;
import nok.core.ImageNav;
import nok.core.Path;
import nok.img.ImgProbe;

/**
 * Full-screen image viewer. Opens fit-to-screen (nearest-neighbor downscale);
 * FIRE toggles between fit and 1:1; arrows pan when 1:1 and the image is bigger
 * than the screen. At fit, LEFT/RIGHT flips to the previous/next image of the
 * same folder (listing order, wrapping) and the soft bar shows the n/m
 * position. A decode failure aborts construction so the caller lands the
 * user back on the library with an error.
 *
 * <p>Like every other screen it owns the whole canvas (full-screen mode),
 * handles the S60 soft keys itself (right-soft "Back", left-soft / select
 * toggle fit) and paints its own themed soft-key bar, so it is usable on this
 * device where the native command bar is parked offscreen. The native BACK
 * {@link Command} is kept as a harmless real-hardware fallback.</p>
 *
 * <p>Memory is the whole design. A decoded Image on this device costs roughly
 * width*height*4 bytes of the ~2MB heap, so a full-res photo is never allowed
 * to exist unnecessarily: the header probe rejects oversized originals before
 * the file is read whole, a desktop-generated preview sidecar is preferred
 * over the original when one exists, and a folder flip releases the current
 * image BEFORE decoding the next one. At most {@code orig} plus the downscaled
 * {@code fitImg} are live at once, and {@code fitImg} aliases {@code orig}
 * outright when the photo already fits the screen.</p>
 *
 * <p>Threading: the constructor does blocking file I/O and decoding, so it
 * must NOT run on the LCDUI event thread - {@code NoksidianMIDlet.openImage}
 * spawns it on a worker and turns a thrown exception into a failToLibrary
 * error. Once constructed, every field belongs to the event thread; the
 * LEFT/RIGHT flip worker touches no fields itself and publishes its result
 * through {@code Display.callSerially}.</p>
 */
public final class ImageView extends Canvas implements CommandListener {

    /**
     * Pixel budget (~1280x960). Above this a photo would OOM the ~2MB E71 heap
     * when the native decoder inflates it to a full-res bitmap, so the header
     * probe refuses it and the viewer shows a placeholder instead of decoding.
     * Tunable on the real device.
     */
    private static final int PIX_MAX = 1200000;
    /** Vault-relative prefix (no dot-dir) for desktop-generated preview sidecars. */
    private static final String THUMB_DIR = "noksidian/thumbs/";

    private final NoksidianMIDlet m;
    private final Command cmdBack;

    // Decoded image + the fit-view cache. Null orig means "nothing to draw":
    // either the placeholder is up, or a flip is in flight (see showSibling).
    private Image orig;
    /**
     * Cached nearest-neighbor downscale of {@code orig} for the fit view, or
     * {@code orig} itself when the photo already fits (no needless copy). Only
     * ever rebuilt lazily in paintFit; {@code apply} nulls it on every load so
     * a new photo can never be painted through the old photo's scaled bitmap.
     */
    private Image fitImg;
    // Content-area size fitImg was built for. The cache is keyed on these
    // because rebuilding it costs a full getRGB sweep of the source; paintFit
    // rescales only when the area actually changed size.
    private int fitW;
    private int fitH;
    /** True = fit-to-screen view (the opening state); false = 1:1 with panning. */
    private boolean fit = true;
    // Viewport origin in image pixels at 1:1. keyPressed may step it out of
    // range, but clampPan immediately pulls it back into 0..(size - viewport).
    // Reset to 0,0 on every load and on every fit toggle.
    private int panX;
    private int panY;

    /** True when no image is decoded and the "too large" placeholder is shown. */
    private boolean placeholder;
    /** Headline of the placeholder ("Image too large" / "unsupported" / ...). */
    private String phMsg;
    /** Probed pixel dimensions for the placeholder text (0 = unknown). */
    private int phW;
    private int phH;
    /** True when {@code orig} is a small desktop preview sidecar, not the original. */
    private boolean preview;

    /** Vault-relative path of the shown image. */
    private String rel;
    /** Image names of rel's folder (listing order), null = nav disabled. */
    private Vector sibs;
    /** Position of rel in sibs (for the n/m indicator), -1 when unknown. */
    private int idx = -1;
    /** True while a LEFT/RIGHT load is in flight; drops further nav keys. */
    private boolean loading;

    /**
     * Builds the viewer for the vault-relative image {@code rel}, doing the
     * read and decode inline. Blocking by design: the only caller,
     * {@code NoksidianMIDlet.openImage}, already runs it on a worker thread and
     * catches whatever escapes, so a half-built ImageView is never handed to
     * Display. A locked vault is the one condition that throws; an unreadable,
     * unsupported or oversized file degrades to a placeholder so the screen
     * still opens and Back / folder flip keep working.
     */
    public ImageView(NoksidianMIDlet m, String rel) {
        this.m = m;
        this.rel = rel;
        // Guarded like every other screen here: an implementation may refuse
        // full-screen mode outright, and losing the extra rows is survivable
        // where an exception escaping the constructor is not.
        try {
            setFullScreenMode(true);
        } catch (Throwable t) {
        }
        cmdBack = new Command("Back", Command.BACK, 1);
        addCommand(cmdBack);
        setCommandListener(this);
        // Prefer sidecar, else probe+gate, else guarded decode. Never OOMs on a
        // big original: the pixel gate refuses it before readBytes. A locked
        // vault is still kicked back to the library (the one case that throws).
        LoadResult res = computeLoad(rel);
        if (res.locked) {
            throw new RuntimeException("vault locked");
        }
        apply(res, rel);
        initSibs();
    }

    /**
     * Sidecar-first, header-gated image load - shared by the constructor and the
     * LEFT/RIGHT flip. Touches no fields (so the flip can run it off the event
     * thread), returning a small result the caller publishes. Never throws
     * except a "vault locked" RuntimeException.
     */
    private LoadResult computeLoad(String r) {
        LoadResult res = new LoadResult();
        // 1) A pre-scaled desktop preview (<=640x480) is small and safe: show it.
        String side = sidecarPath(r);
        if (side != null && m.files != null && m.files.exists(side)) {
            Image s = null;
            try {
                s = decode(side);
            } catch (Throwable t) {
                s = null; // sidecars are plaintext; a "locked" here is impossible
            }
            if (s != null) {
                res.img = s;
                res.preview = true;
                return res;
            }
        }
        // 2) Probe just the header of the original - no whole-file read.
        int[] pr = (m.files != null) ? m.files.probeImage(r) : null;
        if (pr != null && pr[2] == ImgProbe.KIND_UNSUPPORTED) {
            res.placeholder = true;
            res.msg = "Image unsupported";
            res.w = pr[0];
            res.h = pr[1];
            return res;
        }
        if (pr != null && pr[2] == ImgProbe.KIND_OK
                && (long) pr[0] * pr[1] > PIX_MAX) {
            res.placeholder = true;
            res.msg = "Image too large";
            res.w = pr[0];
            res.h = pr[1];
            return res;
        }
        int pw = (pr != null) ? pr[0] : 0;
        int ph = (pr != null) ? pr[1] : 0;
        // 3) Passed the gate (or an unprobed format: gif/bmp/ciphertext).
        Image img = decode(r); // may throw RuntimeException("vault locked")
        if (img != null) {
            res.img = img;
        } else {
            res.placeholder = true;
            res.msg = "Cannot display image";
            res.w = pw;
            res.h = ph;
        }
        return res;
    }

    /**
     * Reads {@code r} and decodes it with an out-of-memory safety net: on OOME
     * (or any decode failure) it releases the byte[] FIRST, runs gc(), and
     * returns null so the caller falls to the placeholder - it allocates nothing
     * new. Only "vault locked" propagates (as a RuntimeException).
     */
    private Image decode(String r) {
        byte[] b = null;
        try {
            b = m.readBytes(r);
            Image img = Image.createImage(b, 0, b.length);
            b = null; // release the compressed bytes the moment we have the image
            return img;
        } catch (OutOfMemoryError oom) {
            b = null;
            System.gc();
            return null;
        } catch (Throwable t) {
            b = null;
            if ("vault locked".equals(t.getMessage())) {
                throw new RuntimeException("vault locked");
            }
            return null;
        }
    }

    /** Publishes a load result into the view fields (fit view, scroll reset). */
    private void apply(LoadResult res, String r) {
        rel = r;
        fitImg = null;
        fit = true;
        panX = 0;
        panY = 0;
        if (res.img != null) {
            orig = res.img;
            placeholder = false;
            preview = res.preview;
            phMsg = null;
        } else {
            orig = null;
            placeholder = true;
            preview = false;
            phMsg = (res.msg != null) ? res.msg : "Cannot display image";
            phW = res.w;
            phH = res.h;
        }
    }

    /** Vault-relative preview path mirroring the original under thumbs/. */
    private static String sidecarPath(String r) {
        if (r == null || r.length() == 0) {
            return null;
        }
        return THUMB_DIR + r + ".jpg";
    }

    /**
     * Folder image list for LEFT/RIGHT navigation. Runs in the constructor,
     * which openImage already keeps off the event thread, so the directory
     * listing never blocks a paint. Any failure (or rel missing from its own
     * listing) just leaves navigation disabled.
     */
    private void initSibs() {
        try {
            Vector imgs = ImageNav.images(m.files.list(Path.parent(rel)));
            int i = ImageNav.indexOf(imgs, Path.name(rel));
            if (i >= 0) {
                sibs = imgs;
                idx = i;
            }
        } catch (Throwable t) {
            sibs = null;
            idx = -1;
        }
    }

    /**
     * Repaints the whole canvas: background, then the image (or the
     * placeholder), then the soft-key bar along the bottom. The image area is
     * the canvas minus the bar height; contentH() recomputes the same split for
     * clampPan. Drawing neither image nor placeholder is a valid state - during
     * a folder flip orig is released while placeholder stays false, so only the
     * background and the bar are painted.
     */
    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        Font sk = skFont();
        int sh = sk.getHeight() + 6;
        int ch = h - sh;
        if (ch < 0) {
            ch = 0;
        }
        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);
        if (orig != null) {
            if (fit) {
                paintFit(g, w, ch);
            } else {
                paintFull(g, w, ch);
            }
        } else if (placeholder) {
            paintPlaceholder(g, w, ch);
        }
        drawSoftBar(g, w, h, sh, sk);
    }

    /**
     * Centered "too large / unsupported / cannot display" notice with the file
     * name and its megapixels. Painted instead of an image so LEFT/RIGHT folder
     * flip and Back keep working without a successful decode.
     */
    private void paintPlaceholder(Graphics g, int w, int ch) {
        Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Font.SIZE_SMALL);
        g.setFont(f);
        int fh = f.getHeight();
        String head = (phMsg != null) ? phMsg : "Cannot display image";
        String name = Path.name(rel);
        String dims = (phW > 0 && phH > 0)
                ? (phW + "x" + phH + " (" + mp(phW, phH) + " MP)") : null;
        int lines = 2 + ((dims != null) ? 1 : 0);
        int y = (ch - lines * fh) / 2;
        if (y < 0) {
            y = 0;
        }
        g.setColor(Theme.text);
        drawCentered(g, f, head, w, y);
        y += fh;
        g.setColor(Theme.dimText);
        drawCentered(g, f, name, w, y);
        y += fh;
        if (dims != null) {
            drawCentered(g, f, dims, w, y);
        }
    }

    /**
     * Draws one horizontally centered line, ellipsized to the canvas width less
     * a 4px margin each side. Centering is computed from the CLIPPED string, so
     * a long file name stays centered instead of running off both edges.
     */
    private static void drawCentered(Graphics g, Font f, String s, int w,
            int y) {
        String t = Ui.clip(s, f, w - 8);
        g.drawString(t, (w - f.stringWidth(t)) / 2, y,
                Graphics.TOP | Graphics.LEFT);
    }

    /** One-decimal megapixels, integer-only (rounded to the nearest 0.1 MP). */
    private static String mp(int w, int h) {
        long tenths = ((long) w * h + 50000L) / 100000L;
        return (tenths / 10) + "." + (tenths % 10);
    }

    /** Flat themed soft-key bar, matching UiScreen.drawSoftKeys. */
    private void drawSoftBar(Graphics g, int w, int h, int sh, Font sk) {
        int y = h - sh;
        g.setColor(Theme.softBar);
        g.fillRect(0, y, w, sh);
        g.setColor(Theme.hr);
        g.drawLine(0, y, w, y);
        g.setFont(sk);
        g.setColor(Theme.dimText);
        int ly = y + (sh - sk.getHeight()) / 2;
        int half = w / 2 - 4;
        // Left soft key toggles fit/1:1; caption names the mode it switches to.
        String left = fit ? "1:1" : "Fit";
        g.drawString(Ui.clip(left, sk, half), 4, ly,
                Graphics.TOP | Graphics.LEFT);
        g.drawString(Ui.clip("Back", sk, half), w - 4, ly,
                Graphics.TOP | Graphics.RIGHT);
        // Center: folder position while LEFT/RIGHT can flip photos, "..." while
        // a flip is loading, and a "[preview]" tag when a sidecar is shown.
        String mid = null;
        if (loading) {
            mid = "...";
        } else {
            String pos = (sibs != null && sibs.size() > 1 && idx >= 0)
                    ? (idx + 1) + "/" + sibs.size() : null;
            if (preview) {
                mid = (pos != null) ? "[preview] " + pos : "[preview]";
            } else {
                mid = pos;
            }
        }
        if (mid != null) {
            g.drawString(mid, w / 2, ly, Graphics.TOP | Graphics.HCENTER);
        }
    }

    /**
     * Draws the fit view, rebuilding the scaled bitmap only when it is missing
     * or the content area changed size, then centering it in that area.
     */
    private void paintFit(Graphics g, int w, int ch) {
        if (fitImg == null || fitW != w || fitH != ch) {
            fitImg = buildFit(w, ch);
            fitW = w;
            fitH = ch;
        }
        int iw = fitImg.getWidth();
        int ih = fitImg.getHeight();
        g.drawImage(fitImg, (w - iw) / 2, (ch - ih) / 2,
                Graphics.TOP | Graphics.LEFT);
    }

    /**
     * Draws the 1:1 view, deciding per axis: an axis that fits the content area
     * gets centered and ignores the pan offset, an axis that overflows scrolls
     * by it. Deciding per axis rather than per image is what makes a photo that
     * is only wider than the screen pan horizontally and stay vertically
     * centered.
     */
    private void paintFull(Graphics g, int w, int ch) {
        int iw = orig.getWidth();
        int ih = orig.getHeight();
        int x = (iw <= w) ? (w - iw) / 2 : -panX;
        int y = (ih <= ch) ? (ch - ih) / 2 : -panY;
        g.drawImage(orig, x, y, Graphics.TOP | Graphics.LEFT);
    }

    /**
     * Largest aspect-preserving size of {@code orig} that fits {@code w} x
     * {@code h}, scaled to it. An image already small enough is returned
     * as-is: fitImg then aliases orig, which is the point - a second bitmap of
     * a photo that needs no scaling would just be wasted heap.
     */
    private Image buildFit(int w, int h) {
        int iw = orig.getWidth();
        int ih = orig.getHeight();
        if (iw <= w && ih <= h) {
            return orig;
        }
        int nw = iw;
        int nh = ih;
        // Two passes, not one ratio: fit the width, then re-fit the height in
        // case the first pass left it too tall. All integer math, so every
        // divide truncates - the result can land a pixel short of the box but
        // never a pixel over, which is what keeps it inside the content area.
        if (nw > w) {
            nh = nh * w / nw;
            nw = w;
        }
        if (nh > h) {
            nw = nw * h / nh;
            nh = h;
        }
        // A very wide or very tall source can round an axis to 0; Image
        // creation with a zero dimension throws, so floor both at one pixel.
        if (nw < 1) {
            nw = 1;
        }
        if (nh < 1) {
            nh = 1;
        }
        return scale(nw, nh);
    }

    /**
     * Nearest-neighbor resample of {@code orig} to nw x nh, built one scanline
     * at a time. Row streaming is the whole trick: the peak extra allocation is
     * two int rows (iw + nw ints) instead of the nw*nh destination buffer a
     * whole-image resample needs (the way Viewer.fitWidth does it), which is
     * what keeps a fit-scale affordable next to an already-decoded photo in the
     * ~2MB heap. No interpolation - just integer index math.
     *
     * <p>The target is a MUTABLE image filled through {@code drawRGB} rather
     * than {@code Image.createRGBImage}: row-at-a-time assembly needs a
     * writable surface, and it also sidesteps the MicroEmulator quirk where
     * createRGBImage output comes back dimmed to roughly 0.75x by the emulated
     * E71 skin's LCD filter (harmless on real hardware, but it wrecks
     * screenshot comparisons).</p>
     */
    private Image scale(int nw, int nh) {
        int iw = orig.getWidth();
        int ih = orig.getHeight();
        try {
            Image out = Image.createImage(nw, nh);
            Graphics og = out.getGraphics();
            // A fresh mutable image is all-white per MIDP, and the drawRGB
            // below blends with processAlpha true, so without this the
            // transparent parts of a PNG would come out white instead of
            // matching the screen. Prime it with the theme background.
            og.setColor(Theme.bg);
            og.fillRect(0, 0, nw, nh);
            int[] srow = new int[iw];
            int[] drow = new int[nw];
            int last = -1;
            for (int y = 0; y < nh; y++) {
                int sy = y * ih / nh;
                // Downscaling maps many output rows onto one source row; getRGB
                // is the expensive call here, so only re-fetch when sy moves.
                if (sy != last) {
                    orig.getRGB(srow, 0, iw, 0, sy, iw, 1);
                    last = sy;
                }
                for (int x = 0; x < nw; x++) {
                    drow[x] = srow[x * iw / nw];
                }
                og.drawRGB(drow, 0, nw, 0, y, nw, 1, true);
            }
            return out;
        } catch (Throwable t) {
            // Out of memory mid-scale: fall back to the unscaled image. Safe to
            // hand back here, unlike Viewer.fitWidth which must return null -
            // this screen retains orig anyway for the 1:1 view, so the alias
            // keeps no memory hog alive that was not already live. The fit view
            // just ends up showing the photo clipped rather than blank.
            return orig;
        }
    }

    protected void keyPressed(int key) {
        // Own the S60 soft keys so this screen is escapable even when the
        // native command bar is offscreen: right-soft = Back, left-soft /
        // select = toggle fit. FIRE still toggles fit as well.
        if (key == Ui.RSK) {
            m.back();
            return;
        }
        if (key == Ui.LSK || key == Ui.MSK) {
            toggleFit();
            return;
        }
        int ga;
        try {
            ga = getGameAction(key);
        } catch (Throwable t) {
            ga = 0;
        }
        if (ga == Canvas.FIRE) {
            toggleFit();
            return;
        }
        if (fit) {
            // At fit the arrows are free: LEFT/RIGHT flips through the
            // folder's photos (at 1:1 they keep panning, below).
            if (ga == Canvas.LEFT) {
                nav(-1);
            } else if (ga == Canvas.RIGHT) {
                nav(1);
            }
            return;
        }
        // Pan step in image pixels at 1:1. Fixed rather than proportional so a
        // held arrow scrolls at a predictable speed on any photo size.
        int step = 32;
        if (ga == Canvas.LEFT) {
            panX -= step;
        } else if (ga == Canvas.RIGHT) {
            panX += step;
        } else if (ga == Canvas.UP) {
            panY -= step;
        } else if (ga == Canvas.DOWN) {
            panY += step;
        } else {
            return;
        }
        clampPan();
        repaint();
    }

    private void toggleFit() {
        if (loading || placeholder) {
            return; // no image to toggle; keep LEFT/RIGHT flipping, not panning
        }
        fit = !fit;
        panX = 0;
        panY = 0;
        repaint();
    }

    /**
     * Starts a background load of the photo {@code d} steps away in the
     * folder (wrapping). One load at a time: presses while {@code loading}
     * are dropped, and key repeats never reach here (keyRepeated only pans),
     * so holding an arrow cannot queue slow decodes.
     */
    private void nav(int d) {
        if (loading) {
            return;
        }
        String next = ImageNav.sibling(sibs, Path.name(rel), d);
        if (next == null) {
            return;
        }
        final String nrel = Path.join(Path.parent(rel), next);
        loading = true;
        repaint();
        new Thread(new Runnable() {
            public void run() {
                showSibling(nrel);
            }
        }).start();
    }

    /**
     * Loads a neighbour photo off the event thread. The field swap itself is
     * pushed back onto the event thread with callSerially (the syncDone
     * idiom) so it can never interleave with a paint that is mid-rebuild of
     * the fit cache; {@code loading} stays true until the swap lands, so nav
     * keys stay blocked for the whole flight. A read/decode failure keeps the
     * current photo up behind an error dialog (unlike a failed initial open,
     * which kicks back to the library).
     */
    private void showSibling(final String nrel) {
        // Release the CURRENT image on the event thread BEFORE decoding the next
        // one, so the old orig+fitImg are not live alongside the new bytes+image
        // (that ~2x full-res peak is what OOMs a flip). A failed flip therefore
        // lands on the placeholder, not the old photo - the accepted UX cost.
        releaseCurrentImage();
        final LoadResult res = computeLoadQuiet(nrel);
        m.disp.callSerially(new Runnable() {
            public void run() {
                loading = false;
                if (!isShown()) {
                    return; // user backed out while loading: drop the result
                }
                if (res.locked) {
                    m.alertErr("Image", "Vault is locked - unlock it first.");
                    return;
                }
                apply(res, nrel);
                idx = ImageNav.indexOf(sibs, Path.name(nrel));
                repaint();
            }
        });
    }

    /** computeLoad wrapper that never throws (turns "vault locked" into a flag). */
    private LoadResult computeLoadQuiet(String r) {
        try {
            return computeLoad(r);
        } catch (RuntimeException locked) {
            LoadResult res = new LoadResult();
            res.locked = true;
            return res;
        }
    }

    /**
     * Nulls orig/fitImg on the event thread and blocks this worker until the
     * release actually lands (callSerially is async, so it alone guarantees
     * nothing), then gc()s. The worker holds {@code lock} across posting and
     * waiting, so the posted runnable cannot notify before the wait and no
     * wakeup is lost; a 2s cap prevents any hang.
     */
    private void releaseCurrentImage() {
        final Object lock = new Object();
        final boolean[] done = { false };
        synchronized (lock) {
            m.disp.callSerially(new Runnable() {
                public void run() {
                    orig = null;
                    fitImg = null;
                    repaint();
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            });
            long deadline = System.currentTimeMillis() + 2000;
            while (!done[0]) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    break;
                }
                try {
                    lock.wait(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        System.gc();
    }

    /**
     * Auto-repeat drives panning only. At fit the arrows mean "flip photo", and
     * holding one should not walk the folder a photo at a time, so the fit case
     * returns early instead of leaving it to nav()'s {@code loading} guard.
     */
    protected void keyRepeated(int key) {
        if (fit) {
            return;
        }
        int ga;
        try {
            ga = getGameAction(key);
        } catch (Throwable t) {
            ga = 0;
        }
        if (ga == Canvas.LEFT || ga == Canvas.RIGHT || ga == Canvas.UP
                || ga == Canvas.DOWN) {
            keyPressed(key);
        }
    }

    /**
     * Holds the 1:1 viewport inside the image. Clamped against {@code orig},
     * not the fit bitmap, because panning only exists at 1:1. Silently does
     * nothing while no image is loaded (mid-flip).
     */
    private void clampPan() {
        if (orig == null) {
            return;
        }
        int maxX = orig.getWidth() - getWidth();
        int maxY = orig.getHeight() - contentH();
        // An axis smaller than the viewport has no scroll range at all: pin its
        // max to 0, otherwise the negative maximum below would drag the pan
        // offset negative and push the image off the edge.
        if (maxX < 0) {
            maxX = 0;
        }
        if (maxY < 0) {
            maxY = 0;
        }
        if (panX > maxX) {
            panX = maxX;
        }
        if (panX < 0) {
            panX = 0;
        }
        if (panY > maxY) {
            panY = maxY;
        }
        if (panY < 0) {
            panY = 0;
        }
    }

    /** Canvas height minus the soft-key bar: the area the image draws in. */
    private int contentH() {
        int ch = getHeight() - (skFont().getHeight() + 6);
        return (ch > 0) ? ch : 0;
    }

    private static Font skFont() {
        return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                Font.SIZE_SMALL);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdBack) {
            m.back();
        }
    }

    /** Outcome of a sidecar-first, header-gated load (see computeLoad). */
    private static final class LoadResult {
        Image img;          // non-null => show this (original or sidecar)
        boolean preview;    // img is a desktop preview sidecar
        boolean placeholder; // show the "too large / unsupported" notice
        String msg;         // placeholder headline
        int w;              // probed width for the placeholder (0 = unknown)
        int h;              // probed height for the placeholder
        boolean locked;     // vault locked: caller reports and bails
    }
}
