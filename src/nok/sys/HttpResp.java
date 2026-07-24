package nok.sys;

import java.util.Hashtable;

import nok.core.Utf8;

/**
 * Value object for an HTTP response: status code, raw body bytes and a
 * lowercased subset of response headers.
 *
 * <p>Public mutable fields instead of accessors, matching the other struct
 * types in this codebase: Http.once() fills the instance in field by field as
 * the exchange progresses. Nothing here validates or copies - the object is
 * built by Http and returned to the caller on the thread that ran the request,
 * so there is no second writer to defend against.
 *
 * <p>Deliberately dumb: it knows nothing about status classes, redirects or
 * content types. Non-2xx is not an error at this layer (see Http.request), so
 * callers such as GitHub and Settings read the code and decide for themselves
 * what it means.
 */
public final class HttpResp {
    // Always a real HTTP status once the instance escapes Http: a response
    // code that could not be read is turned into an IOException in once()
    // instead, so a caller never has to treat 0 as "unknown".
    public int code;

    // Empty rather than null for 204s, for error codes whose body the stack
    // refuses to hand over, and after an early EOF - callers can read .length
    // unconditionally. Http sizes this array to the bytes actually received
    // and never pads it out to a buffer capacity, because bodyText() decodes
    // the whole array and trailing NULs would look like a server bug to the
    // JSON parser.
    public byte[] body = new byte[0];          // never null (empty array ok)

    // Only the four headers Http.wanted() whitelists ever land here (etag,
    // location, content-type, x-ratelimit-remaining); capturing every header
    // would strand a Hashtable of dead strings in the ~2MB heap once per
    // response. Keys are lowercased at capture time because MIDP stacks
    // disagree on the casing they report, so lookups must spell them lower
    // case - headers.get("Location") finds nothing.
    public Hashtable headers = new Hashtable(); // lowercase header name -> String value

    public HttpResp() {
    }

    /** Body decoded as UTF-8; "" when the body is empty. */
    public String bodyText() {
        // Null-tolerant even though Http never leaves body null: the field is
        // public and mutable, so a caller that clears it to drop the payload
        // still gets "" here rather than a NullPointerException.
        if (body == null || body.length == 0) {
            return "";
        }
        // This is a second full copy of the payload - the char[] behind the
        // String costs 2 bytes per char while body is still reachable, so a
        // 500KB response peaks around 1.5MB of the ~2MB heap. Every caller goes
        // through here (the GitHub API is JSON, so even getBlob's binary content
        // arrives base64 inside a JSON body), which is why the size ceiling
        // lives upstream in Sync.MAX_BLOB (~1.5MB) rather than in this method.
        //
        // nok.core.Utf8, not the platform codec: response JSON carries
        // tree-listing paths - i.e. emoji FILENAMES, which are identity here -
        // and getBlob's encoding=="utf-8" branch reads note text out of this
        // same string, so both must decode on the CESU-8-tolerant codec.
        return Utf8.decode(body);
    }
}
