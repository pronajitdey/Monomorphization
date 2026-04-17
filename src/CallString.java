import soot.jimple.Stmt;

import java.util.Arrays;

/**
 * An immutable, k-bounded call string for context-sensitive analysis.
 *
 * A call string is a sequence of call site statements representing the
 * call stack leading to a particular method invocation. We bound the
 * length to K to ensure termination.
 *
 * Representation:
 *   [ cs_1, cs_2, ..., cs_k ]
 *   where cs_k is the most recent call site (innermost frame)
 *   and cs_1 is the oldest still-remembered call site.
 *
 * When a new call site cs is pushed onto a full string of length K,
 * the oldest entry cs_1 is dropped:
 *   push(cs) on [cs_1, cs_2, cs_k]  →  [cs_2, ..., cs_k, cs]
 *
 * The empty string [] represents the entry context (e.g. main()).
 *
 * Example with K=2:
 *   main calls A at site s1   → context for A = [s1]
 *   A    calls B at site s2   → context for B = [s1, s2]
 *   B    calls C at site s3   → context for C = [s2, s3]  (s1 dropped)
 *
 * Why call strings work for monomorphization:
 *   helper(new Dog()) and helper(new Cat()) from different call sites
 *   get different call string contexts [s1] vs [s2]. Inside helper(),
 *   the parameter type is Dog under [s1] and Cat under [s2], so the
 *   virtual call a.speak() is resolved independently per context.
 *
 * equals/hashCode are correct — required so contexts can be used as
 * HashMap keys in the analysis state map.
 */
public final class CallString {

    /** Maximum call string length — tune for precision vs cost trade-off.
     *  k=1: distinguishes direct callers
     *  k=2: distinguishes caller's caller as well
     *  k=0: context-insensitive (all calls share one context)
     */
    public static final int K = 5; // change here to try k=2, k=3, ...

    /** The empty call string — used as entry context for main(). */
    public static final CallString EMPTY = new CallString(new Stmt[0]);

    // sites[0] = oldest, sites[length-1] = most recent
    private final Stmt[] sites;

    private CallString(Stmt[] sites) {
        this.sites = sites;
    }

    /**
     * Returns a new CallString with callSite appended, bounded to K.
     * If already at length K, the oldest entry is dropped.
     */
    public CallString push(Stmt callSite) {
        if (K == 0) return EMPTY; // context-insensitive mode

        int oldLen = sites.length;
        int newLen = Math.min(oldLen + 1, K);
        Stmt[] newSites = new Stmt[newLen];

        // copy the most-recent (newLen-1) sites from old, append the new one
        int srcOffset = Math.max(0, oldLen - (K - 1));
        System.arraycopy(sites, srcOffset, newSites, 0, newLen - 1);
        newSites[newLen - 1] = callSite;

        return new CallString(newSites);
    }

    public int length() { return sites.length; }

    public Stmt get(int i) { return sites[i]; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CallString)) return false;
        return Arrays.equals(sites, ((CallString) o).sites);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(sites);
    }

    @Override
    public String toString() {
        if (sites.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sites.length; i++) {
            if (i > 0) sb.append(", ");
            // Print just the statement text (not the full method signature)
            sb.append(sites[i].toString());
        }
        return sb.append("]").toString();
    }
}