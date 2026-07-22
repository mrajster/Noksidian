package nok.sys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Enumeration;
import java.util.Hashtable;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

/**
 * RMS-backed key-value store. Single record in RecordStore "nokcfg":
 * DataOutputStream layout: writeInt(count) then writeUTF(key)/writeUTF(value) pairs.
 *
 * load() never throws: a missing or corrupt store falls back to defaults
 * (i.e. an empty table, so get() returns the caller-supplied default).
 *
 * RMS rather than a file in the vault because of a bootstrap problem: the "vault"
 * key IS the vault location, so there is nowhere to put a config file before it is
 * read. RMS also needs no JSR-75 file permission, so reading config at startup
 * cannot trigger a security prompt.
 *
 * All state is static and every public entry point is synchronized: the settings UI
 * thread and Sync's background worker thread both read keys freely (Sync.strategy()
 * re-reads sync.strategy on the worker at every merge), and the whole table is a few
 * dozen short strings so a global lock costs nothing.
 *
 * Persistence is whole-table rewrite, not delta records. A settings screen changing
 * ten keys would otherwise mean ten flash writes; setQuiet()+flush() collapses that
 * into one. Known keys and their defaults live in CONTRACTS.md and
 * CONTRACTS-FEATURES.md; this class deliberately knows none of them, so callers
 * always pass their own default.
 */
public final class Config {

    private static final String STORE = "nokcfg";

    // Null doubles as the "not loaded yet" flag; after load() it is non-null even
    // when the store was missing or corrupt (an empty table means "all defaults").
    private static Hashtable data = null;

    private Config() {
    }

    /** Loads the store once; safe to call repeatedly. Never throws. */
    public static synchronized void load() {
        if (data != null) {
            return;
        }
        Hashtable h = new Hashtable();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            if (re.hasNextElement()) {
                byte[] b = re.nextRecord();
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
                int n = in.readInt();
                // Sanity-gate the count before trusting it as a loop bound. A
                // corrupt first word (say 0x7fffffff) would otherwise be used to
                // size the parse loop; the explicit throw lands in the same catch
                // as a later EOF, but fails immediately and unambiguously rather
                // than after an arbitrary number of readUTF calls have grown the
                // table on a ~2MB heap.
                if (n < 0 || n > 10000) {
                    throw new RuntimeException("bad count");
                }
                for (int i = 0; i < n; i++) {
                    String k = in.readUTF();
                    String v = in.readUTF();
                    h.put(k, v);
                }
            }
            re.destroy();
        } catch (Throwable t) {
            // Missing or corrupt store: fall back to defaults.
            // Throwable, not Exception: this runs first thing in startApp(), so
            // anything escaping here kills the app before a screen exists to
            // report it. The partly-filled table is discarded so a half-parsed
            // record can never masquerade as a complete one.
            h = new Hashtable();
        } finally {
            if (rs != null) {
                try {
                    rs.closeRecordStore();
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
        data = h;
    }

    public static synchronized String get(String key, String def) {
        load();
        if (key == null) {
            return def;
        }
        Object v = data.get(key);
        if (v == null) {
            return def;
        }
        return (String) v;
    }

    /**
     * Integer view of a key. Every value in the table is text typed into a Settings
     * text row, so a stray space or a non-numeric edit is ordinary input rather than
     * corruption: trim it, and on any parse failure hand back the caller's default
     * instead of propagating a NumberFormatException into the UI.
     * Passing null as get()'s default distinguishes "absent" from a stored "0".
     */
    public static synchronized int getInt(String key, int def) {
        String s = get(key, null);
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** Sets a key and persists the whole table immediately. Never throws. */
    public static synchronized void set(String key, String value) {
        load();
        if (key == null) {
            return;
        }
        if (value == null) {
            // A null value deletes rather than storing "null": that is how callers
            // turn a setting back off (Settings' "Forget password" clears crypt.dk
            // this way), and it keeps save()'s writeUTF from ever seeing a null.
            data.remove(key);
        } else {
            data.put(key, value);
        }
        save();
    }

    /**
     * Like {@link #set} but does NOT write to RMS; the value lives only in the
     * in-memory table until {@link #flush} commits it. Lets a screen batch many
     * edits into one flash commit. Never throws.
     *
     * Beware the shared table: an unflushed setQuiet value is visible to every
     * get() in the process and will be written by anyone else's set(). Settings
     * relies on that in one direction (live theme preview) and works around it in
     * the other (reverting an unsaved theme uses set(), not setQuiet, so the
     * revert is guaranteed to reach flash).
     */
    public static synchronized void setQuiet(String key, String value) {
        load();
        if (key == null) {
            return;
        }
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }

    /** Commits the current in-memory table to RMS in a single flash write. */
    public static synchronized void flush() {
        load();
        save();
    }

    /**
     * Serializes and stores the whole table. Callers must already hold the class
     * lock (every caller is a synchronized method), because the count written up
     * front and the following key/value pairs must come from the same snapshot of
     * the table - a concurrent put between the two would produce a record whose
     * declared count disagrees with its contents, which load() can only recover
     * from by throwing away every setting and falling back to defaults.
     */
    private static void save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(data.size());
            Enumeration keys = data.keys();
            while (keys.hasMoreElements()) {
                String k = (String) keys.nextElement();
                out.writeUTF(k);
                out.writeUTF((String) data.get(k));
            }
            out.flush();
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            if (re.hasNextElement()) {
                // Overwrite the existing record instead of delete+add: record ids
                // are never reused, so add-per-save would grow the store's id space
                // and churn flash on every settings change.
                int id = re.nextRecordId();
                rs.setRecord(id, b, 0, b.length);
                // Enforce the single-record invariant if extras ever appear.
                while (re.hasNextElement()) {
                    try {
                        rs.deleteRecord(re.nextRecordId());
                    } catch (Throwable t) {
                        break;
                    }
                }
            } else {
                rs.addRecord(b, 0, b.length);
            }
            re.destroy();
        } catch (Throwable t) {
            // Nothing sensible to do on device; keep in-memory value.
            // Throwing would take down whichever screen happened to change a
            // setting; the session continues correctly, only persistence is lost.
        } finally {
            if (rs != null) {
                try {
                    rs.closeRecordStore();
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
    }
}
