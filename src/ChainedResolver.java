import soot.SootMethod;
import soot.jimple.Stmt;

/**
 * Chains VTA → IntraPTA → InterPTA in order of increasing precision and cost.
 *
 * A site is resolved the moment any layer returns a non-null answer.
 * Statistics show how many sites each layer contributed — the key table
 * for the report's "result" section.
 */
public class ChainedResolver implements CallTargetResolver {

    private final CallTargetResolver vta;
    private final CallTargetResolver intra;
    private final CallTargetResolver inter;

    private int byVTA   = 0;
    private int byIntra = 0;
    private int byInter = 0;
    private int none    = 0;

    public ChainedResolver(CallTargetResolver vta,
                           CallTargetResolver intra,
                           CallTargetResolver inter) {
        this.vta   = vta;
        this.intra = intra;
        this.inter = inter;
    }

    @Override
    public SootMethod resolve(Stmt stmt, SootMethod caller) {
        SootMethod t;

        t = vta.resolve(stmt, caller);
        if (t != null) { byVTA++;   return t; }

        t = intra.resolve(stmt, caller);
        if (t != null) { byIntra++; return t; }

        t = inter.resolve(stmt, caller);
        if (t != null) { byInter++; return t; }

        none++;
        return null;
    }

    @Override
    public String name() {
        return "VTA + IntraPTA + InterPTA(K=" + CallString.K + ")";
    }

    public void printStats() {
        int total = byVTA + byIntra + byInter;
        System.out.println("\n[Chain] ── Resolution breakdown ─────────────────────────────");
        System.out.println("[Chain] Resolved by VTA           (Layer 1): " + byVTA);
        System.out.println("[Chain] Resolved by IntraPTA      (Layer 2): " + byIntra
            + "  (additional over VTA)");
        System.out.println("[Chain] Resolved by InterPTA K=" + CallString.K
            + " (Layer 3): " + byInter
            + "  (additional over VTA+Intra)");
        System.out.println("[Chain] Total monomorphic sites resolved    : " + total);
        System.out.println("[Chain] Unresolved (remain virtual)         : " + none);
        System.out.println("[Chain] ────────────────────────────────────────────────────\n");
    }
}