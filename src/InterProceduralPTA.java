import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

import java.util.*;

/**
 * k-Callstring Context-Sensitive Interprocedural Points-To Analysis.
 *
 * Algorithm: top-down, worklist-driven, summary-based.
 *
 * State:
 * ──────────────────────────────────────────────────────────────────────
 *   entryFacts : Map<MethodContext, PointsToFlowSet>
 *     The pts facts at the entry of each (method, context) pair.
 *     These are the parameter bindings passed in from the caller.
 *
 *   stmtFacts : Map<MethodContext, Map<Unit, PointsToFlowSet>>
 *     Per-statement flow facts for each (method, context), computed
 *     by running IntraProceduralPTA seeded with entryFacts.
 *
 *   exitFacts : Map<MethodContext, PointsToFlowSet>
 *     The pts facts at return statements — used to propagate return
 *     types back to the caller's LHS local.
 *
 *   callerDeps : Map<MethodContext, Set<MethodContext>>
 *     If callee's exit facts change, all callers in callerDeps[callee]
 *     are re-enqueued for reanalysis.
 *
 * Algorithm:
 * ──────────────────────────────────────────────────────────────────────
 *   1. Enqueue (main, EMPTY).
 *   2. Pop (method, ctx) from worklist.
 *   3. Run intra-PTA on method's body, seeded with entryFacts[(method,ctx)].
 *      At each call site stmt:
 *        a. Determine callee (from Soot's call graph as a fallback oracle).
 *        b. new_ctx = ctx.push(stmt)  [bounded to K]
 *        c. Map actual arguments → callee's formal params in new entry facts.
 *        d. If entryFacts[(callee, new_ctx)] changed → enqueue (callee, new_ctx).
 *        e. If exitFacts[(callee, new_ctx)] is available → use return types
 *           to refine the LHS local's type set in the current method.
 *   4. Compute exitFacts[(method,ctx)] from return stmts in method's body.
 *   5. If exitFacts changed → re-enqueue all callers in callerDeps[(method,ctx)].
 *   6. Repeat until worklist empty (fixed point).
 *
 * Context sensitivity gain:
 * ──────────────────────────────────────────────────────────────────────
 *   helper(new Dog()) called at s1 → analyzes helper under context [s1]
 *                                     with param0 = {Dog}
 *   helper(new Cat()) called at s2 → analyzes helper under context [s2]
 *                                     with param0 = {Cat}
 *
 *   Under [s1], a.speak() in helper has receiver type {Dog} → monomorphic.
 *   Under [s2], a.speak() in helper has receiver type {Cat} → also monomorphic.
 *   Context-insensitive: receiver = {Dog, Cat} → polymorphic (VTA result).
 *
 * Scalability:
 * ──────────────────────────────────────────────────────────────────────
 *   - Only application classes are analyzed (library methods skipped).
 *   - K is bounded (typically 1 or 2) to limit context explosion.
 *   - Results are cached: a (method, ctx) pair is only re-analyzed when
 *     its entry facts change.
 */

/**
 * k-Callstring Context-Sensitive Interprocedural Points-To Analysis.
 *
 * CORRECTNESS FIX applied here:
 * ─────────────────────────────────────────────────────────────────────────
 * When passing type information from caller to callee we use SeedBuilder,
 * which maps argument positions → callee-parameter-locals by scanning the
 * callee's own IdentityStmts. This is the only correct way to bridge
 * the method boundary because:
 *
 *   - Locals are method-scoped in Soot. $r1 in main() and $r1 in helper()
 *     are different Java objects. Copying a caller's localMap into a callee
 *     and looking up by Local identity would match nothing.
 *
 *   - The mapping is by ARGUMENT POSITION:
 *       call site: helper(actualArg0, actualArg1)
 *       callee:    $p1 := @parameter0: T   (index 0)
 *                  $p2 := @parameter1: T   (index 1)
 *       seed:      {$p1 → types(actualArg0), $p2 → types(actualArg1)}
 *
 * The MethodSeed object carries this already-resolved mapping and is
 * consumed by IntraProceduralPTA's flowThrough at identity statements.
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Algorithm: top-down worklist, summary-based fixed-point.
 *
 * State:
 *   seeds:     MethodContext → MethodSeed   (entry types for each context)
 *   stmtFacts: MethodContext → (Unit → PointsToFlowSet)
 *   exitFacts: MethodContext → PointsToFlowSet
 *   callerDeps: MethodContext → Set<MethodContext>
 */
public class InterProceduralPTA {

    // ── Analysis state ────────────────────────────────────────────────────────
    private final CallGraph cg;

    private final Map<MethodContext, MethodSeed> seeds = new HashMap<>();

    /** Per-statement facts for each (method, context).
     *  Stored as: unit → flow-set BEFORE that unit. */
    private final Map<MethodContext, Map<Unit, PointsToFlowSet>> stmtFacts = new HashMap<>();

    /** Exit facts (merged flow set at all return stmts) per (method, context). */
    private final Map<MethodContext, PointsToFlowSet> exitFacts = new HashMap<>();

    /** Dependency: callee → set of callers that need reanalysis if callee changes. */
    private final Map<MethodContext, Set<MethodContext>> callerDeps = new HashMap<>();

    /** Statistics. */
    private int iterations = 0;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    public InterProceduralPTA() {
        this.cg = Scene.v().getCallGraph();

        SootMethod main = Scene.v().getMainMethod();
        MethodContext entryMC = new MethodContext(main, CallString.EMPTY);
        seeds.put(entryMC, MethodSeed.EMPTY);

        // Seed main's entry facts with empty set
        // (main has no callers — its params come from the JVM)
        Deque<MethodContext> worklist = new ArrayDeque<>();
        worklist.add(entryMC);
        runWorklist(worklist);
 
        System.out.println("[InterPTA] Fixed point after " + iterations
            + " iterations, " + seeds.size() + " contexts (K=" + CallString.K + ")");

        // Print full interprocedural state after fixed point
        PTADebugPrinter.printInterState(seeds, stmtFacts, exitFacts);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Worklist loop
    // ─────────────────────────────────────────────────────────────────────────

    private void runWorklist(Deque<MethodContext> worklist) {
        while (!worklist.isEmpty()) {
            MethodContext mc = worklist.poll();
            iterations++;

            SootMethod method = mc.method;
            if (!method.isConcrete() || method.isNative()) continue;
            if (!isAppMethod(method)) continue;

            Body body = method.retrieveActiveBody();
            MethodSeed seed = seeds.getOrDefault(mc, MethodSeed.EMPTY);

            // Run intraprocedural PTA seeded with this context's entry types
            IntraProceduralPTA intra = new IntraProceduralPTA(body, seed);

            // Build per-stmt facts, refining call sites interprocedurally
            Map<Unit, PointsToFlowSet> newFacts = new LinkedHashMap<>();
            for (Unit u : body.getUnits()) {
                PointsToFlowSet before = intra.getFlowBefore(u);
                PointsToFlowSet refined = new PointsToFlowSet(before);
 
                if (u instanceof Stmt && ((Stmt) u).containsInvokeExpr()) {
                    processCallSite((Stmt) u, mc, refined, worklist);
                }
                newFacts.put(u, refined);
            }
 
            stmtFacts.put(mc, newFacts);
 
            // Compute exit facts and notify callers if they changed
            PointsToFlowSet newExit = computeExitFacts(body, newFacts);
            PointsToFlowSet oldExit = exitFacts.get(mc);
            if (!newExit.equals(oldExit)) {
                exitFacts.put(mc, newExit);
                for (MethodContext dep : callerDeps.getOrDefault(mc, Collections.emptySet()))
                    worklist.add(dep);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Call site processing
    // ─────────────────────────────────────────────────────────────────────────
 
    private void processCallSite(Stmt callStmt, MethodContext callerMC,
                                  PointsToFlowSet callerFacts,
                                  Deque<MethodContext> worklist) {
        Set<SootMethod> callees = resolveCallees(callStmt);
        if (callees.isEmpty()) return;

        Local lhsLocal = null;
        Set<Type> returnTypes = new LinkedHashSet<>();
        if (callStmt instanceof AssignStmt) {
            Value lhs = ((AssignStmt) callStmt).getLeftOp();
            if (lhs instanceof Local) {
                lhsLocal = (Local) lhs;
            }
        }

        for (SootMethod callee : callees) {
            if (!callee.isConcrete() || !isAppMethod(callee)) continue;

            // Derive callee context: push this call site onto the caller's string
            CallString calleeCtx = callerMC.ctx.push(callStmt);
            MethodContext calleeMC = new MethodContext(callee, calleeCtx);

            // Register dependency
            callerDeps.computeIfAbsent(calleeMC, k -> new HashSet<>()).add(callerMC);

            // BUILD THE SEED CORRECTLY:
            // SeedBuilder maps actualArg[i] in caller → @parameter(i) local in callee
            // using the callee's IdentityStmts. No cross-method local aliasing.
            MethodSeed newSeed = SeedBuilder.build(callStmt, callee, callerFacts);

            // Merge with existing seed (union — may-analysis)
            MethodSeed existingSeed = seeds.get(calleeMC);
            MethodSeed mergedSeed   = mergeSeed(existingSeed, newSeed);

            if (!mergedSeed.equals(existingSeed)) {
                seeds.put(calleeMC, mergedSeed);
                if (!worklist.contains(calleeMC)) worklist.add(calleeMC);
            }

            // Propagate callee's return types to LHS of this assignment (if any)
            PointsToFlowSet calleeExit = exitFacts.get(calleeMC);
            if (calleeExit != null && lhsLocal != null) {
                returnTypes.addAll(collectReturnTypes(callee, calleeExit));
            }
        }

        if (lhsLocal != null && !returnTypes.isEmpty()) {
            callerFacts.assignLocalTypes(lhsLocal, returnTypes);
        }
    }


    /**
     * Merges two seeds by taking the union of type sets for each callee-local.
     * Union is correct for a may-analysis: a type is possible if it appears
     * in ANY calling context that feeds into this (method, callstring).
     */
    private MethodSeed mergeSeed(MethodSeed existing, MethodSeed incoming) {
        if (existing == null || existing.isEmpty()) return incoming;
        if (incoming.isEmpty()) return existing;
 
        // We need to union them — get all locals from both
        // Since MethodSeed is immutable, we build a new map
        Map<Local, Set<Type>> merged = new HashMap<>();
 
        // Collect all locals from existing seed
        // We can't iterate MethodSeed directly without exposing internals,
        // so we use a helper: build from incoming then merge existing
        // by re-running SeedBuilder results. Instead, expose a merge helper:
        return MethodSeed.merge(existing, incoming);
    }

    /**
     * Propagates the callee's return types into the LHS local of the caller.
     * Collects types from all return statements in the callee's exit facts.
     */
    private Set<Type> collectReturnTypes(SootMethod callee,
                                         PointsToFlowSet calleeExit) {
        Set<Type> result = new LinkedHashSet<>();
        Body calleeBody;
        try { calleeBody = callee.retrieveActiveBody(); }
        catch (Exception e) { return result; }
 
        for (Unit u : calleeBody.getUnits()) {
            if (u instanceof ReturnStmt) {
                Value retVal = ((ReturnStmt) u).getOp();
                if (retVal instanceof Local) {
                    // retVal is a Local IN THE CALLEE — look it up in callee's exit facts
                    result.addAll(calleeExit.getLocalTypes((Local) retVal));
                } else if (retVal != null) {
                    result.add(retVal.getType());
                }
            }
        }
        return result;
    }

    /**
     * Computes the exit fact for (method, ctx) by merging the flow-before
     * facts at all return statements in the method.
     */
    private PointsToFlowSet computeExitFacts(Body body,
                                              Map<Unit, PointsToFlowSet> facts) {
        PointsToFlowSet exit = new PointsToFlowSet();
        for (Unit u : body.getUnits()) {
            if (u instanceof ReturnStmt || u instanceof ReturnVoidStmt) {
                PointsToFlowSet fs = facts.get(u);
                if (fs != null) exit.mergeWith(fs);
            }
        }
        return exit;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the callee of a call site.
     * For virtual/interface calls, uses Soot's call graph to find targets;
     * for static/special calls, resolves directly.
     */
    private Set<SootMethod> resolveCallees(Stmt stmt) {
        Set<SootMethod> targets = new LinkedHashSet<>();
        InvokeExpr ie = stmt.getInvokeExpr();
        try {
            if (ie instanceof StaticInvokeExpr || ie instanceof SpecialInvokeExpr) {
                targets.add(ie.getMethod());
                return targets;
            }
            Iterator<Edge> edges = cg.edgesOutOf(stmt);
            while (edges.hasNext()) {
                SootMethod tgt = edges.next().tgt();
                if (tgt.isConcrete() && !tgt.getName().equals("<clinit>"))
                    targets.add(tgt);
            }
        } catch (Exception ignored) {}
        return targets;
    }


    private boolean isAppMethod(SootMethod m) {
        return m.getDeclaringClass().isApplicationClass();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public query API  (used by InterPTAResolver)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the flow-before facts for stmt inside (method, ctx).
     * Returns null if not available (method not yet analyzed under this ctx).
     */
    public PointsToFlowSet getFactsBefore(MethodContext mc, Unit stmt) {
        Map<Unit, PointsToFlowSet> facts = stmtFacts.get(mc);
        if (facts == null) return null;
        return facts.get(stmt);
    }

    /**
     * Returns all known (method, context) pairs for a given method.
     * Used by the resolver to check all contexts.
     */
    public Set<MethodContext> getContextsFor(SootMethod method) {
        Set<MethodContext> result = new HashSet<>();
        for (MethodContext mc : stmtFacts.keySet()) {
            if (mc.method.equals(method)) result.add(mc);
        }
        return result;
    }
}
