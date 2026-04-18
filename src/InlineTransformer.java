import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.invoke.SiteInliner;
import soot.util.Chain;
import java.util.*;

/**
 * InlineTransformer: Inlines static $mono calls produced by MonomorphizationTransformer.
 *
 * THE BUG IN THE OLD VERSION:
 *   Old code collected ALL $mono stmts into a list FIRST, then iterated
 *   and called SiteInliner on each. But SiteInliner.inlineSite() REMOVES
 *   the call stmt from the unit chain and inserts the callee body in its place.
 *   After the first inline, the unit chain is modified. When we then try to
 *   inline the second stmt (which was collected before the first inline ran),
 *   it may be stale, detached, or the body validation may fail on the
 *   partially-modified chain.
 *
 *   This is exactly what happens with Test4:
 *     - 2 $mono sites exist: Dog2.speak$mono and Cat2.speak$mono
 *     - Old code collects both into toInline list
 *     - Inlines Dog2.speak$mono → unit chain modified
 *     - Tries to inline Cat2.speak$mono from stale reference → FAILS silently
 *     - Only 1 of 2 sites gets inlined
 *
 * THE FIX:
 *   Re-scan the body from scratch after EACH successful inline.
 *   This guarantees we always work on a fresh, valid unit chain.
 *   We stop re-scanning when a full pass finds no more $mono sites.
 *   This is a simple fixpoint loop: keep inlining until nothing changes.
 */
public class InlineTransformer extends SceneTransformer {

    private static final int MAX_INLINE_STMTS = 50;

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        System.out.println("[Inline] Starting method inlining pass...");

        int totalInlined = 0;
        int totalSkipped = 0;

        for (SootClass sc : Scene.v().getApplicationClasses()) {
            for (SootMethod method : new ArrayList<>(sc.getMethods())) {
                if (!method.isConcrete()) continue;

                int[] result = inlineAllSitesInMethod(method);
                totalInlined += result[0];
                totalSkipped += result[1];
            }
        }

        System.out.println("[Inline] Total inlined : " + totalInlined);
        System.out.println("[Inline] Total skipped : " + totalSkipped);
    }

    /**
     * Inlines all $mono call sites in a single method.
     *
     * KEY: Uses a FIXPOINT LOOP — re-scans the body after every successful
     * inline instead of pre-collecting stmts. This avoids stale references.
     *
     * Returns [inlinedCount, skippedCount]
     */
    private int[] inlineAllSitesInMethod(SootMethod method) {
        int inlined = 0;
        int skipped = 0;

        boolean changed = true;

        // Keep looping until a full pass over the body finds no $mono sites
        while (changed) {
            changed = false;

            Body body = method.retrieveActiveBody();

            // Find the FIRST $mono site in this fresh scan
            Stmt toInline = null;
            SootMethod target = null;

            for (Unit u : body.getUnits()) {
                if (!(u instanceof Stmt)) continue;
                Stmt stmt = (Stmt) u;
                if (!stmt.containsInvokeExpr()) continue;

                InvokeExpr ie = stmt.getInvokeExpr();
                if (!(ie instanceof StaticInvokeExpr)) continue;

                SootMethod tgt = ie.getMethod();
                if (!tgt.getName().contains("$mono")) continue;

                // Found one — check guards before accepting
                if (!isSafeToInline(tgt)) {
                    skipped++;
                    System.out.println("[Inline] SKIP (guard): "
                            + tgt.getSignature());
                    continue;
                }

                toInline = stmt;
                target   = tgt;
                break; // inline one at a time, then re-scan
            }

            if (toInline == null) break; // no more $mono sites found → done

            // Inline this one site
            try {
                System.out.println("[Inline] Inlining: " + target.getSignature()
                        + " into " + method.getSignature());
                SiteInliner.inlineSite(target, toInline, method);
                inlined++;
                changed = true; // body was modified → re-scan
            } catch (Exception e) {
                skipped++;
                System.err.println("[Inline] FAILED: " + target.getSignature()
                        + " reason: " + e.getMessage());
                // Mark this site so we don't loop forever on the same failure
                // Rename the method so it no longer matches "$mono"
                // Actually: just break out — if SiteInliner threw, stmt is likely
                // still in the chain unchanged, and we'd loop forever.
                // Safe approach: stop trying to inline in this method.
                break;
            }
        }

        return new int[]{inlined, skipped};
    }

    /**
     * Guards — same as before, but now applied per-site before inlining.
     */
    private boolean isSafeToInline(SootMethod callee) {
        if (!callee.isConcrete()) return false;

        Body body;
        try {
            body = callee.retrieveActiveBody();
        } catch (Exception e) {
            return false;
        }

        if (body.getUnits().size() > MAX_INLINE_STMTS) return false;

        // Recursion check
        for (Unit u : body.getUnits()) {
            Stmt s = (Stmt) u;
            if (s.containsInvokeExpr()
                    && s.getInvokeExpr().getMethod().equals(callee)) {
                return false;
            }
        }

        return true;
    }
}