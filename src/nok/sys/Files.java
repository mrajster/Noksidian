package nok.sys;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;

import nok.core.Path;
import nok.img.ImgProbe;

/**
 * JSR-75 FileConnection wrapper. All 'rel' paths are vault-relative,
 * '/'-separated, with no leading '/'. Every FileConnection is closed in
 * a finally block.
 *
 * File URLs use the RAW rel (vaultUrl + rel). The Nokia S60 3rd JSR-75
 * stack (and MicroEmulator, our test harness) do NOT percent-decode
 * file:// URLs: a '%20' is looked up as a literal '%20' in the file name,
 * so encoding a space breaks every file whose name contains one. For
 * portability with spec-strict stacks that DO decode, read/list/exists
 * fall back to the percent-encoded form (' ' -> %20, '%' -> %25) when the
 * raw form is absent. Creation (write/mkdirs) always uses the real name.
 *
 * JSR-75 is the ONLY file API on MIDP 2.0 - there is no java.io.File, no
 * directory object, no path type. Everything is a URL string handed to
 * Connector.open, and the FileConnection that comes back is a scarce native
 * handle, so nothing here holds one across a method boundary: every public
 * method opens, uses, and closes.
 *
 * Connector.open on an untrusted MIDlet can also block on a user permission
 * prompt ("Allow access to user data?"); the long walks that build on this
 * class (the index rebuild in NoksidianMIDlet, the Sync worker) run on their
 * own threads rather than the MIDP event thread.
 *
 * The failure policy is deliberately split. The query methods (exists, isDir,
 * modified, size, probeImage, scanAll, listRoots) swallow everything and return
 * a miss value, because a denied prompt or a yanked memory card is routine on
 * this device and must never kill a UI paint. The rest (read, write, delete,
 * mkdirs, rename, list) throw IOException so callers can surface a real error.
 *
 * This layer is byte-oriented and knows nothing about encryption; VaultCrypto
 * sits above it (in NoksidianMIDlet's readBytes/writeBytes) and hands down
 * finished ciphertext.
 */
public final class Files {

    /**
     * Absolute JSR-75 base URL of the vault, invariantly ending in '/' so
     * url()/urlEnc() can concatenate a rel with no separator logic. It arrives
     * from the persisted "vault" config key in whatever encoded form VaultPicker
     * proved openable on this device, which is why urlEnc() encodes only the rel
     * part and never re-encodes this.
     */
    private final String vaultUrl;

    /**
     * Memoized image header probes (rel -> int[]{w,h,kind}). Each miss is one
     * JSR-75 open+prompt, so caching lets a repeat probe of the same image
     * (e.g. flipping back to a photo in ImageView) skip the open entirely. The
     * cache is per-Files: a vault switch builds a new Files and starts fresh.
     */
    private final Hashtable probeCache = new Hashtable();

    /** vaultUrl e.g. "file:///E:/Notes/" - MUST end with '/' (appended if missing). */
    public Files(String vaultUrl) {
        if (vaultUrl == null) {
            vaultUrl = "";
        }
        if (!vaultUrl.endsWith("/")) {
            vaultUrl = vaultUrl + "/";
        }
        this.vaultUrl = vaultUrl;
    }

    public String vaultUrl() {
        return vaultUrl;
    }

    /**
     * Reads a whole file into memory as raw bytes (no decryption - that seam
     * lives above this class). fileSize() is used only as a starting capacity
     * for the accumulator: some stacks report 0 or a bogus value for a file
     * they will happily stream, hence the 1024 fallback instead of trusting it.
     *
     * The entire file lands in the ~2MB heap, so callers must gate size
     * themselves. probeImage() exists precisely so the image viewers can refuse
     * a multi-megapixel photo before ever reaching this method.
     */
    public byte[] read(String rel) throws IOException {
        FileConnection fc = null;
        InputStream in = null;
        try {
            fc = openExisting(rel, Connector.READ);
            long sz = fc.fileSize();
            in = fc.openInputStream();
            return readAll(in, sz > 0 ? (int) sz : 1024);
        } finally {
            closeQuiet(in);
            closeQuiet(fc);
        }
    }

    /**
     * Header-probes an image WITHOUT reading the whole file: opens the same
     * raw-then-percent-encoded URL candidates as {@link #read} (so emoji /
     * space names resolve), streams only the leading marker bytes through
     * {@link ImgProbe}, and closes the stream. Returns {@code {w, h, kind}}
     * (see ImgProbe kinds), or {@code null} if the file cannot be opened.
     * Never throws and never allocates the compressed image into memory - the
     * pixel gate the viewers apply must run before any full readBytes().
     */
    public int[] probeImage(String rel) {
        if (rel != null) {
            Object c = probeCache.get(rel);
            if (c instanceof int[]) {
                return (int[]) c; // repeat probe: no open, no prompt
            }
        }
        FileConnection fc = null;
        InputStream in = null;
        try {
            fc = openExisting(rel, Connector.READ);
            in = fc.openInputStream();
            int[] r = ImgProbe.probe(in);
            if (rel != null && r != null) {
                probeCache.put(rel, r); // only memoize a successful probe
            }
            return r;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuiet(in);
            closeQuiet(fc);
        }
    }

    /** Auto-creates parent dirs, creates the file if missing, truncates, streams out. */
    public void write(String rel, byte[] data) throws IOException {
        int slash = rel.lastIndexOf('/');
        if (slash > 0) {
            mkdirs(rel.substring(0, slash));
        }
        FileConnection fc = null;
        OutputStream out = null;
        try {
            // Raw name first (Nokia S60 / MicroEmulator want it), then the full
            // UTF-8 percent-encoded form (spec-strict Symbian) so a new file with
            // an emoji/accented name is created with its real decoded name on the
            // device. Throwable, not IOException: a raw-emoji URL throws
            // IllegalArgumentException on Symbian.
            Throwable last = null;
            try {
                fc = (FileConnection) Connector.open(url(rel), Connector.READ_WRITE);
            } catch (Throwable t) {
                last = t;
            }
            if (fc == null) {
                String enc = urlEnc(rel);
                if (!enc.equals(url(rel))) {
                    try {
                        fc = (FileConnection) Connector.open(enc, Connector.READ_WRITE);
                    } catch (Throwable t) {
                        last = t;
                    }
                }
            }
            if (fc == null) {
                throw new IOException("open " + rel + ": "
                        + (last == null ? "unopenable" : last.toString()));
            }
            // create() then truncate(0): JSR-75 has no "open for overwrite"
            // mode, so writing shorter data over a longer existing file would
            // otherwise leave the old tail behind and corrupt the note.
            if (!fc.exists()) {
                fc.create();
            }
            fc.truncate(0);
            out = fc.openOutputStream();
            if (data != null && data.length > 0) {
                out.write(data);
            }
            out.flush();
        } finally {
            closeQuiet(out);
            closeQuiet(fc);
        }
    }

    /**
     * True when anything - file OR directory - resolves at rel. False also
     * means "could not tell": a denied JSR-75 permission prompt or an ejected
     * card is indistinguishable from absence here. That is the behaviour the
     * callers want; they treat it as "not there" and carry on.
     */
    public boolean exists(String rel) {
        FileConnection fc = null;
        try {
            fc = openExisting(rel, Connector.READ);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuiet(fc);
        }
    }

    /** True only for an existing directory; every failure reads as false. */
    public boolean isDir(String rel) {
        FileConnection fc = null;
        try {
            fc = openExisting(rel, Connector.READ);
            return fc.isDirectory();
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuiet(fc);
        }
    }

    /** Deletes a file or an empty directory. */
    public void delete(String rel) throws IOException {
        FileConnection fc = null;
        try {
            fc = openExisting(rel, Connector.READ_WRITE);
            fc.delete();
        } finally {
            closeQuiet(fc);
        }
    }

    /**
     * Creates each path prefix of rel left to right, rel itself included, and
     * skips the prefixes that already exist. JSR-75 offers only
     * a single-level mkdir() that fails when the parent is absent, so this walk
     * is mandatory - there is no mkdirs() in the API to delegate to.
     */
    public void mkdirs(String rel) throws IOException {
        if (rel == null || rel.length() == 0) {
            return;
        }
        int pos = 0;
        int len = rel.length();
        while (pos < len) {
            int slash = rel.indexOf('/', pos);
            String prefix;
            if (slash < 0) {
                prefix = rel;
                pos = len;
            } else {
                prefix = rel.substring(0, slash);
                pos = slash + 1;
            }
            if (prefix.length() == 0) {
                continue;
            }
            FileConnection fc = null;
            try {
                // Raw first, then UTF-8 percent-encoded fallback (see write()),
                // so "New folder" with an emoji name works on the device.
                Throwable last = null;
                try {
                    fc = (FileConnection) Connector.open(url(prefix) + "/", Connector.READ_WRITE);
                } catch (Throwable t) {
                    last = t;
                }
                if (fc == null) {
                    String enc = urlEnc(prefix) + "/";
                    if (!enc.equals(url(prefix) + "/")) {
                        try {
                            fc = (FileConnection) Connector.open(enc, Connector.READ_WRITE);
                        } catch (Throwable t) {
                            last = t;
                        }
                    }
                }
                if (fc == null) {
                    throw new IOException("mkdir " + prefix + ": "
                            + (last == null ? "unopenable" : last.toString()));
                }
                // Re-checking exists() inside the catch is not paranoia: some
                // stacks throw IOException when the directory already exists,
                // and mkdirs runs on several threads (Sync worker, Library's
                // folder-create worker), so the directory can also appear
                // between our exists() and our mkdir(). Only a still-missing
                // directory is fatal.
                if (!fc.exists()) {
                    try {
                        fc.mkdir();
                    } catch (IOException e) {
                        if (!fc.exists()) {
                            throw new IOException("mkdir " + prefix + ": " + e.toString());
                        }
                    }
                }
            } finally {
                closeQuiet(fc);
            }
        }
    }

    /**
     * Child names of a directory ("" = vault root). Directory names end
     * with '/'. Sorted dirs-first, then case-insensitive by name.
     *
     * The stack hands back real file names, never percent-encoded ones, so an
     * entry can be appended to rel and fed straight back into any method here.
     * Sorting happens at this layer rather than in each caller so every user of
     * it - the MIDlet's cached listDir (which Library paints), CryptoSetup's
     * re-encrypt walk, ImageNav - agrees on the on-screen order.
     */
    public Vector list(String rel) throws IOException {
        Vector v = new Vector();
        FileConnection fc = null;
        try {
            fc = openDir(rel);
            Enumeration e = fc.list();
            while (e.hasMoreElements()) {
                v.addElement((String) e.nextElement());
            }
        } finally {
            closeQuiet(fc);
        }
        sortEntries(v);
        return v;
    }

    /** Last-modified millis, or 0 on any error. */
    public long modified(String rel) {
        FileConnection fc = null;
        try {
            fc = openExisting(rel, Connector.READ);
            return fc.lastModified();
        } catch (Throwable t) {
            return 0L;
        } finally {
            closeQuiet(fc);
        }
    }

    /** File size in bytes, or -1 on any error. */
    public long size(String rel) {
        FileConnection fc = null;
        try {
            fc = openExisting(rel, Connector.READ);
            return fc.fileSize();
        } catch (Throwable t) {
            return -1L;
        } finally {
            closeQuiet(fc);
        }
    }

    /** Renames within the same directory; newName is a plain name (no path). */
    public void rename(String rel, String newName) throws IOException {
        FileConnection fc = null;
        try {
            fc = openExisting(rel, Connector.READ_WRITE);
            fc.rename(newName);
        } finally {
            closeQuiet(fc);
        }
    }

    /**
     * Recursively collects vault-relative paths of FILES only. Skips
     * ".noksidian/" and any name starting with '.'. Never throws; IO
     * errors simply end that branch of the scan.
     *
     * The app's own directory is matched as "noksidian/" with NO leading dot:
     * the real E71 JSR-75 stack rejects any path segment starting with '.'
     * (IllegalArgumentException "url is not valid"), so a dot-dir is simply not
     * available on device. The leading-dot skip above still earns its keep for
     * vaults synced from desktop Obsidian, which do carry ".obsidian/".
     */
    public void scanAll(String rel, Vector out) {
        Vector kids;
        try {
            kids = list(rel);
        } catch (Throwable t) {
            return;
        }
        boolean root = (rel == null || rel.length() == 0);
        for (int i = 0; i < kids.size(); i++) {
            String name = (String) kids.elementAt(i);
            if (name.length() == 0 || name.charAt(0) == '.') {
                continue;
            }
            // Matched at the root only, so a user's own note folder that
            // happens to be called "noksidian" deeper in the vault is scanned.
            if (root && name.equals("noksidian/")) {
                continue; // app's private sync-state dir (no leading dot)
            }
            if (name.endsWith("/")) {
                String dir = name.substring(0, name.length() - 1);
                if (dir.length() == 0) {
                    continue;
                }
                scanAll(root ? dir : rel + "/" + dir, out);
            } else {
                out.addElement(root ? name : rel + "/" + name);
            }
        }
    }

    /** Vector of root strings like "E:/" from FileSystemRegistry. Never throws. */
    public static Vector listRoots() {
        Vector v = new Vector();
        try {
            Enumeration e = FileSystemRegistry.listRoots();
            while (e.hasMoreElements()) {
                v.addElement((String) e.nextElement());
            }
        } catch (Throwable t) {
            // return whatever we collected
        }
        return v;
    }

    // ------------------------------------------------------------------
    // internals

    /** Raw URL (no encoding) - the primary form for Nokia S60 / MicroEmulator. */
    private String url(String rel) {
        return vaultUrl + (rel == null ? "" : rel);
    }

    /**
     * Full UTF-8 percent-encoded URL - fallback for spec-strict JSR-75 stacks
     * (the real Symbian E71). Encodes space, '%', emoji, accented chars, etc.
     * per segment (keeps '/'); vaultUrl is already encoded as VaultPicker stored
     * it, so it is not re-encoded here (that would double-encode its '%').
     */
    private String urlEnc(String rel) {
        return vaultUrl + Path.urlEncodePath(rel);
    }

    /**
     * Opens rel and verifies it exists. Tries the raw URL (and its directory
     * form), then the percent-encoded URL (and its directory form), so files
     * whose names contain spaces resolve on both non-decoding stacks
     * (Nokia S60 / MicroEmulator) and spec-strict stacks that decode %20.
     * Throws IOException with the path when nothing exists there.
     */
    private FileConnection openExisting(String rel, int mode) throws IOException {
        String raw = url(rel);
        FileConnection fc = tryOpen(raw, mode);
        if (fc == null && !raw.endsWith("/")) {
            fc = tryOpen(raw + "/", mode);
        }
        if (fc == null) {
            String enc = urlEnc(rel);
            if (!enc.equals(raw)) {
                fc = tryOpen(enc, mode);
                if (fc == null && !enc.endsWith("/")) {
                    fc = tryOpen(enc + "/", mode);
                }
            }
        }
        if (fc != null) {
            return fc;
        }
        throw new IOException("not found: " + rel);
    }

    /** Opens url; returns it only if it exists, else closes it and returns null. */
    private static FileConnection tryOpen(String url, int mode) {
        FileConnection fc = null;
        boolean keep = false;
        try {
            fc = (FileConnection) Connector.open(url, mode);
            keep = fc.exists();
            if (keep) {
                return fc;
            }
        } catch (Throwable t) {
            // Not openable at this URL; caller tries the next candidate.
            // Throwable (not just IOException) is REQUIRED: Symbian JSR-75
            // throws IllegalArgumentException("url is not valid") for a URL with
            // raw emoji/surrogate chars (like the old ".noksidian" dot-dir), and
            // SecurityException when the user denies the permission prompt - both
            // RuntimeExceptions that would otherwise escape and kill the MIDlet.
        } finally {
            if (!keep) {
                closeQuiet(fc);
            }
        }
        return null;
    }

    /**
     * Opens a directory for listing. "" (or null) = vault root. Tries the raw
     * directory URL first, then the percent-encoded form for stacks that
     * decode %20. Throws IOException when the path is not an existing directory.
     */
    private FileConnection openDir(String rel) throws IOException {
        if (rel == null || rel.length() == 0) {
            FileConnection fc = null;
            try {
                fc = (FileConnection) Connector.open(vaultUrl, Connector.READ);
                if (fc.exists() && fc.isDirectory()) {
                    return fc;
                }
            } catch (Throwable t) {
                // fall through to the IOException below
            }
            closeQuiet(fc);
            throw new IOException("not a directory: " + rel);
        }
        String raw = url(rel);
        if (!raw.endsWith("/")) {
            raw = raw + "/";
        }
        FileConnection fc = tryOpenDir(raw);
        if (fc == null) {
            String enc = urlEnc(rel);
            if (!enc.endsWith("/")) {
                enc = enc + "/";
            }
            if (!enc.equals(raw)) {
                fc = tryOpenDir(enc);
            }
        }
        if (fc != null) {
            return fc;
        }
        throw new IOException("not found: " + rel);
    }

    /** Opens url; returns it only if it exists AND is a directory, else null. */
    private static FileConnection tryOpenDir(String url) {
        FileConnection fc = null;
        boolean keep = false;
        try {
            fc = (FileConnection) Connector.open(url, Connector.READ);
            keep = fc.exists() && fc.isDirectory();
            if (keep) {
                return fc;
            }
        } catch (Throwable t) {
            // Throwable (not just IOException): a raw-emoji URL throws
            // IllegalArgumentException on Symbian; caller falls back to the
            // percent-encoded candidate instead of crashing the app.
        } finally {
            if (!keep) {
                closeQuiet(fc);
            }
        }
        return null;
    }

    /**
     * Drains a stream fully into a byte[]. 'expected' is only a starting
     * capacity hint (fileSize may be unknown or wrong), so the read loop, not
     * the hint, decides the final length. The loop is not optional: a stream
     * read() may return fewer bytes than the 4096 requested, so a single read
     * would silently truncate the file.
     */
    private static byte[] readAll(InputStream in, int expected) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(expected > 0 ? expected : 1024);
        byte[] buf = new byte[4096];
        while (true) {
            int r = in.read(buf, 0, buf.length);
            if (r < 0) {
                break;
            }
            if (r > 0) {
                bo.write(buf, 0, r);
            }
        }
        return bo.toByteArray();
    }

    /** Dirs-first, then case-insensitive name order. Insertion sort. */
    private static void sortEntries(Vector v) {
        // CLDC 1.1 has no Collections.sort and no Arrays.sort for objects, so
        // the sort is hand-rolled. Insertion sort because it sorts the Vector
        // in place with no temporary array and is stable, which is what makes
        // the exact-case tiebreak in compareEntries deterministic.
        for (int i = 1; i < v.size(); i++) {
            String cur = (String) v.elementAt(i);
            int j = i - 1;
            while (j >= 0 && compareEntries((String) v.elementAt(j), cur) > 0) {
                v.setElementAt(v.elementAt(j), j + 1);
                j--;
            }
            v.setElementAt(cur, j + 1);
        }
    }

    /**
     * Directories before files, then lowercased name order. The trailing exact
     * compareTo is a tiebreak: without it two names differing only in case
     * ("Notes" vs "notes") compare equal and their on-screen order would depend
     * on whatever sequence the stack happened to enumerate them in.
     */
    private static int compareEntries(String a, String b) {
        boolean ad = a.endsWith("/");
        boolean bd = b.endsWith("/");
        if (ad != bd) {
            return ad ? -1 : 1;
        }
        int c = a.toLowerCase().compareTo(b.toLowerCase());
        if (c != 0) {
            return c;
        }
        return a.compareTo(b);
    }

    // Close helpers. Three overloads rather than one because CLDC 1.1 has no
    // Closeable interface and FileConnection is not an InputStream/OutputStream
    // relative. Failures are swallowed on purpose: close() on an already-dead
    // handle (card pulled, prompt denied, stream aborted) throws, it happens in
    // a finally block that is often unwinding a more interesting exception, and
    // there is nothing left to salvage at that point.

    private static void closeQuiet(FileConnection fc) {
        if (fc != null) {
            try {
                fc.close();
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    private static void closeQuiet(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    private static void closeQuiet(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (Throwable t) {
                // ignore
            }
        }
    }
}
