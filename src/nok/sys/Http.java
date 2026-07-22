package nok.sys;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Hashtable;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

/**
 * Blocking HTTP engine on top of javax.microedition.io.HttpConnection.
 * Always sends "User-Agent: Noksidian/1.0" and "Connection: close".
 * Follows up to 3 redirects (301/302/303/307) for GET requests.
 * Captures etag / location / content-type / x-ratelimit-remaining
 * response headers under lowercase names. Everything closed in finally.
 *
 * MIDP 2.0 gives us exactly one networking API: Connector.open() returning
 * an HttpConnection. There is no java.net, no URL class, no cookie store, no
 * connection pool and no chunked/gzip handling to opt into - whatever the
 * Symbian stack does is what happens. So this class is deliberately thin: it
 * sets request properties, triggers the exchange, slurps the body and closes
 * everything. Anything smarter (auth headers, JSON, error mapping) lives a
 * layer up in nok.sync.GitHub, and retry/backoff above that in nok.sync.Sync.
 *
 * The same Connector.open() call serves http:// and https://, but on the E71
 * the TLS stack tops out at TLS 1.0, so https://api.github.com cannot be
 * reached at all - the handshake dies before any HTTP is exchanged. Real
 * deployments therefore point gh.api at the LAN-side TLS bridge
 * (tools/ghproxy.py) over plain http. See docs/troubleshooting.md, "Nothing
 * syncs at all (it's TLS)".
 *
 * Threading: every method here BLOCKS - on DNS, on the handshake, and
 * potentially on a MIDP "allow network access?" permission prompt that only
 * resolves when the user answers. Calling this from the MIDP event thread
 * freezes the UI (and can deadlock against the prompt itself). Sync runs it
 * on its worker thread; Settings' connection test spawns its own.
 *
 * Redirects are followed for GET only. A POST/PUT/DELETE body has already
 * been written to a now-dead connection and there is no general-purpose way
 * to know whether replaying it is safe, so non-GET redirects are handed back
 * to the caller as-is.
 */
public final class Http {

    /**
     * Redirect hop budget. Small on purpose: the only redirects expected here
     * are GitHub's own (host canonicalisation, renamed repos), and every extra
     * hop is another full connection setup over a phone radio.
     */
    private static final int MAX_REDIRECTS = 3;

    private Http() {
    }

    /**
     * Performs one HTTP exchange, following GET redirects, and returns the
     * status code, body bytes and captured headers. A null or empty method
     * means GET; headers may be null; body is written only for non-null.
     * Non-2xx is NOT an error here - it comes back as an HttpResp with that
     * code, and callers decide what it means.
     */
    public static HttpResp request(String method, String url, Hashtable headers, byte[] body)
            throws IOException {
        if (url == null || url.length() == 0) {
            throw new IOException("empty url");
        }
        String m = (method == null || method.length() == 0) ? "GET" : method.toUpperCase();
        String cur = url;
        int hops = 0;
        boolean isGet = m.equals("GET");
        while (true) {
            HttpResp r = once(m, cur, headers, body);
            if (isGet && hops < MAX_REDIRECTS && isRedirect(r.code)) {
                String loc = (String) r.headers.get("location");
                if (loc != null && loc.length() > 0) {
                    String next = resolveLocation(cur, loc);
                    // Reassigning the local only: stripAuthorization returns a
                    // copy, so the Hashtable the caller passed in is never
                    // mutated behind its back.
                    if (headers != null && !authority(cur).equals(authority(next))) {
                        // Cross-host redirect: never forward credentials to a
                        // foreign host (mirrors curl CVE-2018-1000007 / Go /
                        // python-requests). Same-host redirects keep auth so
                        // e.g. GitHub 301s for renamed private repos still work.
                        headers = stripAuthorization(headers);
                    }
                    cur = next;
                    hops++;
                    continue;
                }
            }
            return r;
        }
    }

    // ------------------------------------------------------------------
    // internals

    /**
     * The redirect codes worth following here. 308 is absent because nothing
     * in this deployment (GitHub or the bridge) has been seen to emit it; a
     * 308 therefore comes back to the caller unfollowed.
     */
    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307;
    }

    // Used only to compare two URLs for same-host-ness, so what matters is
    // that both sides are normalised identically, not that this is a correct
    // URL parser. Hand-rolled because CLDC has no URL class and no regex.
    /** Lowercased authority (host[:port]) of an absolute http(s) URL, or "". */
    private static String authority(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        int start = scheme + 3;
        int end = url.indexOf('/', start);
        int q = url.indexOf('?', start);
        // "http://host?x=1" is legal and has no path slash, so the authority
        // ends at whichever of '/' or '?' comes first.
        if (q >= 0 && (end < 0 || q < end)) {
            end = q;
        }
        String a = (end < 0) ? url.substring(start) : url.substring(start, end);
        return a.toLowerCase();
    }

    /** Shallow copy of headers with any Authorization key removed (case-insensitive). */
    private static Hashtable stripAuthorization(Hashtable headers) {
        Hashtable copy = new Hashtable();
        Enumeration k = headers.keys();
        while (k.hasMoreElements()) {
            Object key = k.nextElement();
            // instanceof guard because Hashtable is untyped on CLDC 1.1 (no
            // generics) and the cast would otherwise be a crash waiting for a
            // caller that keys by something else.
            if (key instanceof String && "Authorization".equalsIgnoreCase((String) key)) {
                continue;
            }
            copy.put(key, headers.get(key));
        }
        return copy;
    }

    /**
     * One exchange, no redirect handling. Each stage rewraps its IOException
     * with the URL, and most of them with a stage word too, because the
     * Symbian stack's own messages are frequently just a numeric code, which
     * makes a bug report read off the phone screen impossible to act on.
     * The URL is safe to embed - the token lives in a header, not the URL.
     */
    private static HttpResp once(String method, String url, Hashtable headers, byte[] body)
            throws IOException {
        HttpConnection hc = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            try {
                // The trailing 'true' declares that the caller wants timeout
                // exceptions instead of an indefinite block. Honouring it is
                // optional for the stack, but on a phone that has wandered out
                // of WiFi range the alternative is a worker thread hung for
                // good, so it is worth asking for.
                hc = (HttpConnection) Connector.open(url, Connector.READ_WRITE, true);
            } catch (IOException e) {
                throw new IOException("open " + url + ": " + e.toString());
            }
            hc.setRequestMethod(method);
            // GitHub rejects requests with no User-Agent outright. "close"
            // because nothing here pools connections, and a stack that thinks
            // it is keeping one alive can stall the following read.
            hc.setRequestProperty("User-Agent", "Noksidian/1.0");
            hc.setRequestProperty("Connection", "close");
            if (headers != null) {
                Enumeration k = headers.keys();
                while (k.hasMoreElements()) {
                    String name = (String) k.nextElement();
                    Object val = headers.get(name);
                    if (val != null) {
                        hc.setRequestProperty(name, val.toString());
                    }
                }
            }
            if (body != null) {
                // Set explicitly rather than left to the stack: the body is
                // fully in hand, so declaring its length avoids depending on
                // whatever framing (chunked or none) the stack would pick.
                hc.setRequestProperty("Content-Length", Integer.toString(body.length));
                try {
                    out = hc.openOutputStream();
                    out.write(body);
                    out.close();
                    // Nulled so the finally block skips it: a second close is
                    // not reliably a no-op on MIDP stacks, and anything it
                    // threw would be swallowed there anyway.
                    out = null;
                } catch (IOException e) {
                    throw new IOException("send " + method + " " + url + ": " + e.toString());
                }
            }
            HttpResp r = new HttpResp();
            try {
                // Triggers the actual exchange; can itself throw on CLDC.
                r.code = hc.getResponseCode();
            } catch (IOException e) {
                throw new IOException(method + " " + url + ": " + e.toString());
            }
            captureHeaders(hc, r);
            // Open the input stream only after the response code was read.
            try {
                in = hc.openInputStream();
            } catch (IOException e) {
                // Swallowed rather than thrown: the status code is already in
                // hand and is the useful part. Some stacks refuse to hand over
                // a stream for 4xx/5xx at all, and turning that into an
                // exception would lose the code the caller needs to react to.
                // Cost: GitHub's JSON error message is then unavailable, so
                // GitHub.httpError() degrades to a bare "HTTP <code>".
                in = null; // no body (e.g. 204 / some error codes) -> empty
            }
            if (in != null) {
                try {
                    long len = hc.getLength();
                    // A known length lets us allocate the array once at the
                    // exact size instead of growing a buffer. The 16MB ceiling
                    // only guards that single up-front allocation against a
                    // bogus or hostile header - it is NOT a heap-safety limit,
                    // since 16MB is already far past the ~2MB heap; what keeps
                    // this safe in practice is that notes and blobs are small.
                    // getLength() returns -1 whenever the length is unknown
                    // (chunked responses), so readAll() is a normal path here,
                    // not an exotic one.
                    if (len >= 0 && len <= 16777216L) {
                        r.body = readN(in, (int) len);
                    } else {
                        r.body = readAll(in);
                    }
                } catch (IOException e) {
                    throw new IOException("read body " + url + ": " + e.toString());
                }
            }
            return r;
        } finally {
            // Streams before the connection, and every close swallows
            // Throwable (not just IOException) so that a stack throwing a
            // RuntimeException on close cannot escape and replace the real
            // failure. Native connection handles are scarce on the E71 -
            // leaking one here eventually kills all further networking.
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable t) {
                    // ignore
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable t) {
                    // ignore
                }
            }
            if (hc != null) {
                try {
                    hc.close();
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
    }

    /**
     * Captures etag / location / content-type / x-ratelimit-remaining under
     * lowercase names. Scans indexed headers first (case-safe on every stack),
     * then falls back to lookups by name for anything still missing.
     */
    private static void captureHeaders(HttpConnection hc, HttpResp r) {
        try {
            // Bounded at 64 rather than looping to the first null pair alone:
            // a stack that never terminates the enumeration would spin here
            // forever, and no response we care about carries 64 headers.
            for (int i = 0; i < 64; i++) {
                String k = hc.getHeaderFieldKey(i);
                String v = hc.getHeaderField(i);
                if (k == null && v == null) {
                    break;
                }
                if (k == null || v == null) {
                    continue; // status-line slot on some stacks
                }
                String lk = k.toLowerCase();
                if (wanted(lk)) {
                    r.headers.put(lk, v);
                }
            }
        } catch (Throwable t) {
            // indexed enumeration unsupported; rely on named lookups below
        }
        grab(hc, r, "etag", "ETag");
        grab(hc, r, "location", "Location");
        grab(hc, r, "content-type", "Content-Type");
        grab(hc, r, "x-ratelimit-remaining", "X-RateLimit-Remaining");
    }

    /**
     * Whitelist of the headers worth keeping. Only "location" is consumed in
     * this file (by the redirect loop); the other three are captured for
     * callers and diagnostics. Capturing wholesale would park a Hashtable of
     * dead strings in a ~2MB heap for every response.
     */
    private static boolean wanted(String lk) {
        return lk.equals("etag") || lk.equals("location")
                || lk.equals("content-type") || lk.equals("x-ratelimit-remaining");
    }

    /**
     * Named-lookup fallback for one header, no-op if the indexed scan already
     * found it. Tries the conventional capitalisation first, then the all
     * lowercase spelling, because MIDP does not promise getHeaderField(name)
     * is case-insensitive, so a stack that compares exactly would miss the
     * other spelling. Wrapped in Throwable because this is the fallback path
     * already - a stack that cannot answer must not take the response down.
     */
    private static void grab(HttpConnection hc, HttpResp r, String lowerName, String typical) {
        if (r.headers.get(lowerName) != null) {
            return;
        }
        try {
            String v = hc.getHeaderField(typical);
            if (v == null) {
                v = hc.getHeaderField(lowerName);
            }
            if (v != null) {
                r.headers.put(lowerName, v);
            }
        } catch (Throwable t) {
            // header not available; skip
        }
    }

    // The loop is required because InputStream.read is free to return a short
    // count on every call. A truncated response must come back as a short
    // array rather than one padded with zero bytes: HttpResp.bodyText()
    // decodes the whole array as UTF-8 and hands it to the JSON parser, which
    // trailing NULs would break in a way that looks like a server bug.
    /** Reads exactly n bytes (or fewer on early EOF). */
    private static byte[] readN(InputStream in, int n) throws IOException {
        if (n <= 0) {
            return new byte[0];
        }
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) {
                break;
            }
            got += r;
        }
        if (got == n) {
            return b;
        }
        byte[] t = new byte[got];
        System.arraycopy(b, 0, t, 0, got);
        return t;
    }

    // Used when Content-Length is absent (chunked responses) or implausible.
    // The chunk size is a compromise: big enough to keep the syscall count
    // down, small enough that the transient buffer plus the growing
    // ByteArrayOutputStream stay affordable in a ~2MB heap.
    /** Reads to EOF in 4KB chunks. */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(4096);
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

    // Servers may send absolute URLs, absolute paths or relative references,
    // and CLDC has no URL class to do this for us, so all three cases are
    // handled by hand below. This covers the shapes GitHub and the bridge
    // emit; it is not a full RFC 3986 resolver (no "." / ".." collapsing, and
    // no protocol-relative "//host/path" form).
    /** Resolves a Location header value against the current URL. */
    private static String resolveLocation(String cur, String loc) {
        if (loc.startsWith("http://") || loc.startsWith("https://")) {
            return loc;
        }
        int scheme = cur.indexOf("://");
        if (scheme < 0) {
            return loc;
        }
        // Absolute path: keep scheme+authority from cur, replace everything
        // after it. No '/' after the authority means cur had no path at all.
        if (loc.startsWith("/")) {
            int hostEnd = cur.indexOf('/', scheme + 3);
            if (hostEnd < 0) {
                return cur + loc;
            }
            return cur.substring(0, hostEnd) + loc;
        }
        // Relative reference: resolve against cur's directory. The query
        // string is dropped first because it is not part of the path and
        // must not leak into the resolved target.
        String base = cur;
        int q = base.indexOf('?');
        if (q >= 0) {
            base = base.substring(0, q);
        }
        int lastSlash = base.lastIndexOf('/');
        // A lastSlash at or before scheme+2 is the second slash of "://",
        // i.e. cur is a bare "http://host" with no path, so there is no
        // directory to strip - just append.
        if (lastSlash <= scheme + 2) {
            return base + "/" + loc;
        }
        return base.substring(0, lastSlash + 1) + loc;
    }
}
