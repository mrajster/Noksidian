package nok.sys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Vector;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

/**
 * RMS-backed note-index cache. Unlike the FileConnection sidecar it replaces,
 * RMS needs no JSR-75 permission, so loading/saving the index is PROMPT-FREE
 * even for an untrusted MIDlet - later-session browsing costs zero "allow read
 * user data?" prompts.
 *
 * <p>Single record in RecordStore "noksidian_idx"; DataOutputStream layout:</p>
 * <pre>
 *   byte  schema version (SCHEMA)
 *   UTF   vault URL the paths belong to
 *   int   byte-length of the joined-paths blob
 *   bytes UTF-8 of the newline-joined vault-relative paths (today's cache format)
 * </pre>
 *
 * <p>RMS is GLOBAL (per MIDlet suite) but the index is PER-VAULT, so the vault
 * URL is stored in a header and {@link #load} rejects a cache whose vault does
 * not match the current one (a vault switch then falls back to a full rescan
 * that overwrites the cache). Every method degrades on failure - a missing or
 * corrupt store, a schema mismatch, or an oversized record all behave as "no
 * cache" (rebuild/rescan); nothing ever throws.</p>
 *
 * <p>RMS does NOT survive an app reinstall, which is why NoksidianMIDlet keeps
 * a second-chance fallback: the "noksidian/index" file on the card written by
 * tools/gen-index.sh. That file costs one read prompt, and its caller feeds the
 * result straight back through {@link #save}, so only the first load after a
 * reinstall pays for it.</p>
 *
 * <p>All three entry points are static synchronized because they are reached
 * from more than one thread - NoksidianMIDlet.presentNote loads on the UI thread
 * while ensureIndex() runs the scan-and-save on a background thread - and an RMS
 * store is a process-wide resource, so the class monitor serialises store opens.
 * The class monitor is the only lock taken here.</p>
 *
 * <p>Policy lives in the caller, not here: an encrypted vault must never have a
 * plaintext path list persisted, so NoksidianMIDlet skips {@link #save} (and
 * drops any stale entry) for such a vault, and CryptoSetup calls {@link #clear}
 * when it resets sync state after an encryption change.</p>
 */
public final class IndexStore {

    private static final String STORE = "noksidian_idx";
    private static final byte SCHEMA = 1;
    /** Upper bound on the joined-paths blob (guards a corrupt length field). */
    private static final int MAX_BLOB = 4 * 1024 * 1024;

    private IndexStore() {
    }

    /**
     * Returns the cached path list for {@code vaultUrl}, or null when there is
     * no usable cache (missing/corrupt store, schema mismatch, or a cache that
     * belongs to a different vault). Never throws.
     */
    public static synchronized Vector load(String vaultUrl) {
        if (vaultUrl == null) {
            return null;
        }
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            byte[] b = re.hasNextElement() ? re.nextRecord() : null;
            re.destroy();
            if (b == null || b.length == 0) {
                return null;
            }
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(b));
            if (in.readByte() != SCHEMA) {
                return null;
            }
            String storedVault = in.readUTF();
            if (!vaultUrl.equals(storedVault)) {
                return null; // cache belongs to a different vault
            }
            int len = in.readInt();
            if (len < 0 || len > MAX_BLOB) {
                return null;
            }
            byte[] joined = new byte[len];
            in.readFully(joined);
            return splitLines(joined);
        } catch (Throwable t) {
            return null; // missing or corrupt: behave as "no cache"
        } finally {
            closeQuiet(rs);
        }
    }

    /**
     * Persists {@code paths} tagged with {@code vaultUrl}, replacing any prior
     * cache. Never throws; an oversized record or any RMS error just leaves the
     * next session to rescan.
     */
    public static synchronized void save(String vaultUrl, Vector paths) {
        if (vaultUrl == null || paths == null) {
            return;
        }
        RecordStore rs = null;
        try {
            StringBuffer sb = new StringBuffer(paths.size() * 24);
            for (int i = 0; i < paths.size(); i++) {
                sb.append((String) paths.elementAt(i));
                sb.append('\n');
            }
            byte[] joined = utf8(sb.toString());
            ByteArrayOutputStream bo =
                    new ByteArrayOutputStream(joined.length + 64);
            DataOutputStream out = new DataOutputStream(bo);
            out.writeByte(SCHEMA);
            out.writeUTF(vaultUrl);
            out.writeInt(joined.length);
            out.write(joined);
            out.flush();
            byte[] rec = bo.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            if (re.hasNextElement()) {
                int id = re.nextRecordId();
                rs.setRecord(id, rec, 0, rec.length);
                // Enforce the single-record invariant if extras ever appear.
                while (re.hasNextElement()) {
                    try {
                        rs.deleteRecord(re.nextRecordId());
                    } catch (Throwable t) {
                        break;
                    }
                }
            } else {
                rs.addRecord(rec, 0, rec.length);
            }
            re.destroy();
        } catch (Throwable t) {
            // best-effort: a failed write just means a rescan next session
        } finally {
            closeQuiet(rs);
        }
    }

    /** Drops the cache so the next {@link #load} rescans. Never throws. */
    public static synchronized void clear() {
        try {
            RecordStore.deleteRecordStore(STORE);
        } catch (Throwable t) {
            // not present or busy: nothing to do
        }
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Throwable t) {
            return s.getBytes();
        }
    }

    /** Splits UTF-8 cache bytes into non-empty vault paths (one per line). */
    private static Vector splitLines(byte[] data) {
        Vector v = new Vector();
        String all;
        try {
            all = new String(data, "UTF-8");
        } catch (Throwable t) {
            all = new String(data);
        }
        int start = 0;
        int n = all.length();
        for (int i = 0; i <= n; i++) {
            if (i == n || all.charAt(i) == '\n' || all.charAt(i) == '\r') {
                if (i > start) {
                    String line = all.substring(start, i);
                    if (line.length() > 0) {
                        v.addElement(line);
                    }
                }
                start = i + 1;
            }
        }
        return v;
    }

    private static void closeQuiet(RecordStore rs) {
        if (rs != null) {
            try {
                rs.closeRecordStore();
            } catch (Throwable t) {
                // ignore
            }
        }
    }
}
