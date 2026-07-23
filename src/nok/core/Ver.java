package nok.core;

/**
 * Dotted-version comparison for the in-app update check. "v1.2.10" against
 * "1.2.9" has to come out newer, which rules out plain string comparison, and
 * CLDC 1.1 has no String.split - so the segments are walked by hand.
 *
 * <p>Rules: an optional leading 'v'/'V' is ignored, each dot-separated
 * segment is compared numerically, a missing segment counts as 0 (1.2 equals
 * 1.2.0), and any non-digit tail inside a segment ends its number ("3-beta"
 * reads as 3). Anything unparseable degrades to 0 rather than throwing -
 * the inputs come off the network and a bad tag must not kill the check.
 *
 * <p>Pure strings, no javax (CONTRACTS.md keeps nok.core javax-free);
 * exercised by TestVer on a desktop JVM.
 */
public final class Ver {

    private Ver() {
    }

    /** &lt;0 when a is older than b, 0 when equal, &gt;0 when a is newer. */
    public static int compare(String a, String b) {
        a = strip(a);
        b = strip(b);
        int ia = 0;
        int ib = 0;
        while (ia < a.length() || ib < b.length()) {
            int ea = seg(a, ia);
            int eb = seg(b, ib);
            int na = num(a, ia, ea);
            int nb = num(b, ib, eb);
            if (na != nb) {
                return (na < nb) ? -1 : 1;
            }
            ia = (ea < a.length()) ? ea + 1 : a.length();
            ib = (eb < b.length()) ? eb + 1 : b.length();
        }
        return 0;
    }

    /** True when candidate is strictly newer than current. */
    public static boolean newer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        if (s.length() > 0 && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) {
            s = s.substring(1);
        }
        return s;
    }

    /** Index of the '.' ending the segment that starts at i (or length). */
    private static int seg(String s, int i) {
        while (i < s.length() && s.charAt(i) != '.') {
            i++;
        }
        return i;
    }

    /** Leading digits of s[i,e) as an int; 0 when there are none. */
    private static int num(String s, int i, int e) {
        int v = 0;
        boolean any = false;
        while (i < e) {
            char c = s.charAt(i);
            if (c < '0' || c > '9' || v > 99999999) {
                break;
            }
            v = v * 10 + (c - '0');
            any = true;
            i++;
        }
        return any ? v : 0;
    }
}
