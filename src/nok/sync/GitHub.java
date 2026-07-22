package nok.sync;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

import nok.core.Base64;
import nok.core.Json;
import nok.core.Path;
import nok.sys.Config;
import nok.sys.Http;
import nok.sys.HttpResp;

/**
 * Minimal GitHub REST v3 client. Reads Config at call time (no cached
 * fields), so settings changes take effect immediately.
 *
 * Endpoints used:
 *   GET    {base}/repos/{o}/{r}/git/trees/{branch}?recursive=1
 *   GET    {base}/repos/{o}/{r}/git/blobs/{sha}
 *   PUT    {base}/repos/{o}/{r}/contents/{path}
 *   DELETE {base}/repos/{o}/{r}/contents/{path}
 *
 * Every call sends Authorization: "token <gh.token>" and
 * Accept: "application/vnd.github+json". Non-2xx responses become
 * IOException("HTTP <code> <message>") with the server's json
 * "message" field when parseable.
 *
 * CLDC 1.1 ships no HTTP client library and no JSON parser, so this client
 * is three hand-rolled layers from this repo stacked together: nok.sys.Http
 * (one blocking exchange over javax.microedition.io), nok.core.Json
 * (parse/write) and nok.core.Base64 (blob payloads). Everything a desktop
 * client would inherit from a library is simply absent: no retries, no
 * pagination, no ETag caching, no typed exception hierarchy. Redirect
 * following is the one such nicety, and it lives a layer down in Http.
 *
 * Only two of GitHub's several file APIs are used, and the split is
 * deliberate. The git trees API with recursive=1 returns the whole branch in
 * ONE request; walking the contents API directory by directory would cost a
 * request per folder, which over a phone radio turns a sync pass into
 * minutes. Writes go through the contents API because a single PUT creates
 * the blob, the tree and the commit at once - the same thing through the git
 * data API is four round trips (blob, tree, commit, ref update) per file.
 *
 * Statelessness is the whole design. Every accessor below re-reads Config,
 * so a token or branch edited in Settings applies to the very next request
 * with no restart and no cache to invalidate; a Config lookup is a Hashtable
 * hit, free next to a network round trip. Instances are throwaway - Sync
 * holds one purely for convenience.
 *
 * Threading: EVERY method here blocks, because Http.request blocks on DNS,
 * the handshake, the transfer, and possibly on a MIDP "allow network
 * access?" permission prompt that only clears when the user answers. None of
 * them may be called from the MIDP event thread. Sync's worker thread is the
 * only caller in the app.
 *
 * On a real E71 gh.api almost never points at api.github.com: the phone's
 * TLS stack tops out at TLS 1.0, so the handshake dies before any HTTP is
 * exchanged. Real deployments point gh.api at the LAN-side TLS bridge in
 * tools/ghproxy.py over plain http, which is why apiBase() keeps any path
 * component of the configured URL - the bridge's optional "--secret WORD"
 * mode expects every request to arrive under /WORD/.
 *
 * Error strings are user-facing. Sync does not match on them, it prints them
 * verbatim in the status line, so they are worded for a phone screen ("repo
 * too large", "conflict: <path>") rather than for a log file.
 */
public final class GitHub {

    public GitHub() {
    }

    /** True when gh.owner, gh.repo and gh.token are all non-empty. */
    public boolean configured() {
        // gh.branch is deliberately NOT part of the check: branch() falls back
        // to "main", so a settings form with the branch left blank still syncs.
        return owner().length() > 0 && repo().length() > 0 && token().length() > 0;
    }

    /** Config gh.api (default https://api.github.com) with trailing '/' trimmed. */
    public String apiBase() {
        String s = Config.get("gh.api", "https://api.github.com").trim();
        // repoUrl() appends "/repos/...", so one trailing slash typed into the
        // settings form would produce "//repos/" - a 404 from api.github.com,
        // and a 403 through ghproxy.py --secret WORD, whose prefix test wants
        // the path to start with "/WORD/" exactly. Loop rather than strip once
        // so a pasted "https://host/api/v3//" is fully cleaned; Settings'
        // testConnection() does the same by hand for unsaved fields.
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() == 0) {
            s = "https://api.github.com";
        }
        // Note what is NOT stripped: the path. "http://host:8180/WORD" survives
        // intact because tools/ghproxy.py --secret WORD demands that prefix.
        return s;
    }

    /**
     * Lists all blobs of the configured branch (recursive tree).
     * Throws IOException("repo too large") when the tree is truncated
     * and IOException("repo or branch not found") on 404.
     *
     * This one request is the entire remote side of a sync pass, which makes
     * its completeness safety-critical: Sync treats "in state but absent from
     * the tree" as a remote deletion and moves the local file to trash. A
     * silently truncated tree would therefore read as a mass delete and bin
     * the user's vault, so truncation is a hard failure rather than a
     * best-effort listing. GitHub truncates around 100k entries or 7MB of
     * response, far above any plausible note vault.
     *
     * The tree is passed by branch NAME, which the API accepts as a tree-ish
     * alongside a commit sha. That means every pass sees the branch tip with
     * no extra ref lookup, at the cost of not being able to pin a snapshot -
     * fine here, since Sync re-reconciles from scratch each pass anyway.
     */
    public Vector listTree() throws IOException {
        String url = repoUrl() + "/git/trees/" + Path.urlEncodePath(branch())
                + "?recursive=1";
        HttpResp r = Http.request("GET", url, baseHeaders(), null);
        // 404 is intercepted before the generic path because GitHub answers it
        // for several different mistakes - wrong owner/repo, wrong branch, and
        // a private repo whose token lacks the scope to see it - and its own
        // "Not Found" message helps nobody. The wording names the two the user
        // can most easily check.
        if (r.code == 404) {
            throw new IOException("repo or branch not found");
        }
        if (!ok2xx(r.code)) {
            throw httpError(r);
        }
        Hashtable o = parseObj(r);
        if (Json.bool(o, "truncated", false)) {
            throw new IOException("repo too large");
        }
        Vector out = new Vector();
        Vector tree = Json.arr(o.get("tree"));
        if (tree == null) {
            // Json.arr yields null when "tree" is missing or is not an array.
            // Reported as an empty listing rather than an error, which is the
            // right answer on a first sync against a bare branch: Sync then
            // pushes every local file.
            return out;
        }
        int n = tree.size();
        for (int i = 0; i < n; i++) {
            Hashtable e = Json.obj(tree.elementAt(i));
            if (e == null) {
                continue;
            }
            // A recursive tree also lists "tree" entries (directories) and
            // "commit" entries (submodule pointers). Neither has fetchable
            // content, and a directory has no meaning locally - the vault's
            // folders are implied by its file paths.
            if (!"blob".equals(Json.str(e, "type"))) {
                continue;
            }
            String p = Json.str(e, "path");
            String sha = Json.str(e, "sha");
            // Skip rather than throw on a malformed entry: one weird blob must
            // not abort a pass that would otherwise sync hundreds of notes.
            // A missing "size" defaults to 0, which is safe because Sync uses
            // it only as an oversize gate (0 just means "not skipped").
            if (p == null || sha == null) {
                continue;
            }
            out.addElement(new GhEntry(p, sha, Json.num(e, "size", 0)));
        }
        return out;
    }

    /** Fetches a blob by sha and decodes its base64 content. */
    public byte[] getBlob(String sha) throws IOException {
        // No size guard here on purpose - Sync gates on the tree entry's size
        // (MAX_BLOB, ~1.5MB) before calling. It has to: at peak this method
        // holds the raw json body bytes, its String form, the base64 String
        // pulled out of that AND the decoded array, several times the file
        // size live at once in a ~2MB heap.
        // The sha is a hex string straight from listTree, so it needs no
        // encoding; anything else would be a caller bug, not user input.
        String url = repoUrl() + "/git/blobs/" + sha;
        HttpResp r = Http.request("GET", url, baseHeaders(), null);
        if (!ok2xx(r.code)) {
            throw httpError(r);
        }
        Hashtable o = parseObj(r);
        String content = Json.str(o, "content");
        if (content == null) {
            throw new IOException("blob " + sha + ": no content");
        }
        String enc = Json.str(o, "encoding");
        // The blobs API may answer with either "base64" or "utf-8" encoding.
        // Under "utf-8" the "content" field is already the literal file text,
        // and base64-decoding it would silently corrupt the note. Anything
        // that is not utf-8 is assumed to be base64.
        if (enc != null && enc.toLowerCase().equals("utf-8")) {
            return utf8(content);
        }
        try {
            // GitHub wraps blob base64 at 60 chars; Base64.decode skips CR/LF
            // itself, so the payload is handed over unclean rather than paying
            // for another full-size String just to strip newlines.
            return Base64.decode(content);
        } catch (IllegalArgumentException e) {
            // Base64 signals corruption with an unchecked exception. Convert
            // it so every failure out of this client is an IOException and no
            // caller has to guard the unchecked path separately.
            throw new IOException("blob " + sha + ": bad base64: " + e.toString());
        }
    }

    /**
     * Creates or updates a file via the contents API. knownSha == null
     * creates; otherwise updates that blob. Returns the NEW blob sha.
     * 409/422 -> IOException("conflict: <path>").
     *
     * knownSha doubles as the optimistic-concurrency token: GitHub refuses the
     * write unless the file on the branch still has exactly that blob sha, so
     * a change pushed from elsewhere between this pass's listTree and this PUT
     * is rejected instead of silently overwritten. Sync does its own three-way
     * merge before ever getting here, so this is a last-resort guard against a
     * race inside the pass rather than the normal conflict path.
     *
     * Returning the NEW sha matters just as much: Sync stores it in
     * state.json, so the next pass compares it against the tree listing and
     * recognises its own write as "unchanged" without downloading the blob
     * back. Drop the return value and every push costs a pull next pass.
     */
    public String putFile(String path, byte[] content, String knownSha, String message)
            throws IOException {
        Hashtable body = new Hashtable();
        // Hashtable rejects null values, so a null message or content is
        // normalised to empty rather than conditionally omitted. Empty content
        // is a legitimate request: it creates a zero-byte file.
        body.put("message", message == null ? "" : message);
        body.put("content", Base64.encode(content == null ? new byte[0] : content));
        body.put("branch", branch());
        // Presence of "sha" is the only thing that makes this an update rather
        // than a create; getting it wrong in either direction is a 4xx, which
        // is why the split is expressed purely by whether the caller had a sha
        // to give instead of by a separate flag.
        if (knownSha != null) {
            body.put("sha", knownSha);
        }
        Hashtable h = baseHeaders();
        h.put("Content-Type", "application/json");
        HttpResp r = Http.request("PUT", contentsUrl(path), h, utf8(Json.write(body)));
        // Both codes are collapsed into "the branch moved under our feet",
        // since a stale or missing sha can surface as either. Throwing leaves
        // the local file untouched and aborts the pass, so the next pass
        // re-lists the tree and reconciles against the new remote state. The
        // cost of collapsing them is that a genuine 422 validation error (bad
        // path, oversize content) also reads as "conflict" and loses the
        // server's own message.
        if (r.code == 409 || r.code == 422) {
            throw new IOException("conflict: " + path);
        }
        if (!ok2xx(r.code)) {
            throw httpError(r);
        }
        Hashtable o = parseObj(r);
        Hashtable c = Json.obj(o.get("content"));
        String sha = Json.str(c, "sha");
        if (sha == null) {
            throw new IOException("put " + path + ": no sha in response");
        }
        return sha;
    }

    /** Deletes a file via the contents API (needs its current blob sha). */
    public void deleteFile(String path, String sha, String message) throws IOException {
        Hashtable body = new Hashtable();
        body.put("message", message == null ? "" : message);
        body.put("sha", sha == null ? "" : sha);
        body.put("branch", branch());
        Hashtable h = baseHeaders();
        h.put("Content-Type", "application/json");
        HttpResp r = Http.request("DELETE", contentsUrl(path), h, utf8(Json.write(body)));
        if (!ok2xx(r.code)) {
            throw httpError(r);
        }
    }

    /**
     * Connectivity/credentials check. Returns null when everything is
     * fine, else a short human-readable error message. Never throws.
     */
    public String test() {
        if (!configured()) {
            return "not configured (owner, repo and token required)";
        }
        try {
            String url = repoUrl() + "/branches/" + Path.urlEncodePath(branch());
            HttpResp r = Http.request("GET", url, baseHeaders(), null);
            if (ok2xx(r.code)) {
                return null;
            }
            if (r.code == 401) {
                return "bad token (HTTP 401)";
            }
            if (r.code == 403) {
                return "access denied or rate limited (HTTP 403)";
            }
            if (r.code == 404) {
                return "repo or branch not found (HTTP 404)";
            }
            return httpError(r).getMessage();
        } catch (Throwable t) {
            return "connection failed: " + t.toString();
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private String owner() {
        return Config.get("gh.owner", "").trim();
    }

    private String repo() {
        return Config.get("gh.repo", "").trim();
    }

    private String branch() {
        String b = Config.get("gh.branch", "main").trim();
        if (b.length() == 0) {
            b = "main";
        }
        return b;
    }

    private String token() {
        return Config.get("gh.token", "").trim();
    }

    private String repoUrl() {
        return apiBase() + "/repos/" + owner() + "/" + repo();
    }

    private String contentsUrl(String path) {
        return repoUrl() + "/contents/" + Path.urlEncodePath(Path.normalize(path));
    }

    private Hashtable baseHeaders() {
        Hashtable h = new Hashtable();
        h.put("Authorization", "token " + token());
        h.put("Accept", "application/vnd.github+json");
        return h;
    }

    private static boolean ok2xx(int code) {
        return code >= 200 && code < 300;
    }

    /** Parses the response body as a JSON object; IOException otherwise. */
    private static Hashtable parseObj(HttpResp r) throws IOException {
        Object v;
        try {
            v = Json.parse(r.bodyText());
        } catch (IllegalArgumentException e) {
            throw new IOException("bad json from server: " + e.toString());
        }
        Hashtable o = Json.obj(v);
        if (o == null) {
            throw new IOException("unexpected json from server");
        }
        return o;
    }

    /** Builds IOException("HTTP <code> <message>") from an error response. */
    private static IOException httpError(HttpResp r) {
        String msg = null;
        try {
            Hashtable o = Json.obj(Json.parse(r.bodyText()));
            msg = Json.str(o, "message");
        } catch (Throwable t) {
            // body not json; fall through
        }
        if (msg == null || msg.length() == 0) {
            return new IOException("HTTP " + r.code);
        }
        return new IOException("HTTP " + r.code + " " + msg);
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }
}
