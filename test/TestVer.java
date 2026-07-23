import nok.core.Ver;

/**
 * Desktop tests for nok.core.Ver - the version comparison behind the in-app
 * update check. Java 1.3 syntax, CLDC-safe APIs only.
 *
 * <p>The stakes: compare() deciding wrong either nags the user to "update" to
 * the build already installed, or - worse - stays silent about a real update
 * forever. Inputs come from a GitHub tag_name over the network, so the junk
 * cases are as load-bearing as the happy ones.
 *
 * <p>Failure is a thrown RuntimeException, so the first bad assertion aborts
 * the run and test.sh (set -e) stops there.
 */
public class TestVer {

    static int n = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new RuntimeException("FAIL: " + name);
        n++;
    }

    public static void main(String[] args) {
        testEqual();
        testOrder();
        testPrefixAndLength();
        testJunk();
        System.out.println("ALL PASS " + n);
    }

    static void testEqual() {
        check(Ver.compare("1.2.2", "1.2.2") == 0, "equal");
        check(Ver.compare("v1.2.2", "1.2.2") == 0, "v prefix equal");
        check(Ver.compare("1.2", "1.2.0") == 0, "missing segment is zero");
        check(Ver.compare("1.2.0.0", "1.2") == 0, "trailing zeros equal");
        check(!Ver.newer("1.2.2", "1.2.2"), "equal is not newer");
    }

    static void testOrder() {
        check(Ver.newer("1.2.3", "1.2.2"), "patch newer");
        check(Ver.newer("1.3.0", "1.2.9"), "minor beats patch");
        check(Ver.newer("2.0.0", "1.9.9"), "major beats all");
        // The case plain string comparison gets wrong:
        check(Ver.newer("1.2.10", "1.2.9"), "10 beats 9 numerically");
        check(!Ver.newer("1.2.2", "1.2.10"), "9-ish not newer than 10");
        check(Ver.compare("1.2.2", "1.2.3") < 0, "older is negative");
    }

    static void testPrefixAndLength() {
        check(Ver.newer("v1.3", "1.2.9"), "v prefix still compares");
        check(Ver.newer("V2", "1.9"), "capital V");
        check(Ver.newer("1.2.2.1", "1.2.2"), "extra segment breaks tie");
    }

    static void testJunk() {
        check(Ver.compare(null, null) == 0, "null null");
        check(Ver.newer("1.0", null), "null is oldest");
        check(Ver.compare("abc", "abc") == 0, "letters read as zero");
        check(Ver.newer("1.0", "abc"), "number beats letters");
        // A non-digit tail ends the number instead of poisoning it.
        check(Ver.compare("1.2.3-beta", "1.2.3") == 0, "suffix ignored");
        check(Ver.newer("1.2.4-rc1", "1.2.3"), "suffixed but newer");
        check(Ver.compare("", "") == 0, "empty empty");
    }
}
