package nok.sync;

/**
 * One blob entry from the GitHub git tree listing.
 *
 * A plain struct with public fields rather than a class with accessors, matching
 * how the rest of this codebase carries small records (see HttpResp).
 *
 * Only "blob" rows ever become a GhEntry. GitHub.listTree drops the "tree"
 * (directory) and "commit" (submodule) rows of a recursive listing: neither has
 * fetchable content, and a directory has no local meaning because the vault's
 * folders are implied by its file paths.
 *
 * Treated as immutable in practice - nothing writes the fields after the
 * constructor runs. Instances live as Hashtable VALUES keyed by normalized path
 * (see Sync's remoteMap), never as keys, which is why no equals/hashCode is
 * needed here.
 */
public final class GhEntry {

    /**
     * Repo-relative POSIX path exactly as GitHub reported it, NOT normalized.
     * Sync passes it through Path.normalize before using it as a map key or
     * touching the filesystem, so this field must not be compared against a
     * local vault path directly.
     */
    public String path;

    /**
     * Git blob sha1 in hex, which doubles as this file's content identity.
     * Sync records it in state.json after a successful transfer and compares the
     * next pass's value against the stored one to decide whether the remote side
     * changed; it is also the fetch key for GitHub.getBlob and the
     * optimistic-concurrency token handed to putFile/deleteFile. Note it is a
     * git blob hash - sha1 over "blob <len>\0" + content - so it will never match
     * a bare hash of the file bytes.
     */
    public String sha;

    /**
     * Blob size in bytes; 0 when the tree entry carried no "size".
     * Its only consumer is Sync's MAX_BLOB gate (~1.5MB), applied BEFORE any
     * fetch because getBlob transiently holds the json body, its String form, the
     * base64 String pulled out of it and the decoded array - several times the
     * file size live at once in a ~2MB heap. Defaulting to 0 is the safe
     * direction: it reads as "not oversize", so an entry whose json carried no
     * "size" is still attempted rather than skipped on a value nobody supplied.
     */
    public long size;

    public GhEntry(String path, String sha, long size) {
        this.path = path;
        this.sha = sha;
        this.size = size;
    }
}
