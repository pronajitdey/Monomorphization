import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.ExceptionalUnitGraph;

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
public class InterProceduralPTA {

    // ── Analysis state ────────────────────────────────────────────────────────

    /** Entry facts for each (method, context). */
    private final Map<MethodContext, PointsToFlowSet> entryFacts = new HashMap<>();

    /** Per-statement facts for each (method, context).
     *  Stored as: unit → flow-set BEFORE that unit. */
    private final Map<MethodContext, Map<Unit, PointsToFlowSet>> stmtFacts = new HashMap<>();

    /** Exit facts (merged flow set at all return stmts) per (method, context). */
    private final Map<MethodContext, PointsToFlowSet> exitFacts = new HashMap<>();

    /** Dependency: callee → set of callers that need reanalysis if callee changes. */
    private final Map<MethodContext, Set<MethodContext>> callerDeps = new HashMap<>();

    /** Statistics. */
    private int iterations = 0;
    private int contextsCreated = 0;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    public InterProceduralPTA() {
        SootMethod main = Scene.v().getMainMethod();
        MethodContext entryCtx = new MethodContext(main, CallString.EMPTY);

        // Seed main's entry facts with empty set
        // (main has no callers — its params come from the JVM)
        entryFacts.put(entryCtx, new PointsToFlowSet());

        Deque<MethodContext> worklist = new ArrayDeque<>();
        worklist.add(entryCtx);
        contextsCreated++;

        runWorklist(worklist);

        System.out.println("[InterPTA] Fixed point after " + iterations
            + " iterations, " + contextsCreated + " contexts (K=" + CallString.K + ")");
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
            PointsToFlowSet entry = entryFacts.getOrDefault(mc, new PointsToFlowSet());

            // Run intraprocedural analysis seeded with entry facts,
            // returning per-stmt facts and the exit fact
            AnalysisResult result = analyzeMethod(mc, body, entry, worklist);

            // Store per-stmt facts for query later
            stmtFacts.put(mc, result.stmtFacts);

            // Compute exit facts (union over all return stmts)
            PointsToFlowSet newExit = computeExitFacts(body, result.stmtFacts);
            PointsToFlowSet oldExit = exitFacts.get(mc);

            if (!newExit.equals(oldExit)) {
                exitFacts.put(mc, newExit);
                // Exit changed → re-enqueue all callers that depend on this method
                Set<MethodContext> deps = callerDeps.getOrDefault(mc, Collections.emptySet());
                worklist.addAll(deps);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-method analysis
    // ─────────────────────────────────────────────────────────────────────────

    private AnalysisResult analyzeMethod(MethodContext mc, Body body,
                                          PointsToFlowSet entry,
                                          Deque<MethodContext> worklist) {
        // Run the field-sensitive intraprocedural PTA as the base analysis.
        // We then OVERLAY interprocedural refinements at call sites:
        // instead of leaving call results unknown, we look up the callee's
        // exit summary and propagate return types into the LHS local.
        IntraProceduralPTA intraPTA = new IntraProceduralPTA(body, entry);
        Map<Unit, PointsToFlowSet> facts = new LinkedHashMap<>();

        for (Unit u : body.getUnits()) {
            // Fetch the "before" facts Soot computed for this unit
            PointsToFlowSet before = intraPTA.getFlowBefore(u);
            // Make a copy so we can modify it with interprocedural info
            PointsToFlowSet refined = new PointsToFlowSet(before);

            if (u instanceof Stmt) {
                Stmt stmt = (Stmt) u;
                if (stmt.containsInvokeExpr()) {
                    processCallSite(stmt, mc, refined, worklist);
                }
            }
            facts.put(u, refined);
        }

        return new AnalysisResult(facts);
    }

    /**
     * At a call site, propagates argument types into the callee's entry facts
     * and propagates the callee's return types back into the caller's LHS.
     */
    private void processCallSite(Stmt callStmt, MethodContext callerMC,
                                  PointsToFlowSet callerFacts,
                                  Deque<MethodContext> worklist) {
        InvokeExpr ie = callStmt.getInvokeExpr();

        // Resolve the callee from Soot's call graph (best effort)
        SootMethod callee = resolveCallee(callStmt, ie);
        if (callee == null || !callee.isConcrete() || !isAppMethod(callee)) return;

        // Derive the callee's context: push this call site onto the caller's string
        CallString calleeCtx = callerMC.ctx.push(callStmt);
        MethodContext calleeMC = new MethodContext(callee, calleeCtx);

        // Register dependency: if callee's exit changes, re-analyze caller
        callerDeps.computeIfAbsent(calleeMC, k -> new HashSet<>()).add(callerMC);

        // Build callee's entry facts from the actual arguments
        PointsToFlowSet calleeEntry = buildCalleeEntry(ie, callerFacts, callee);

        // Check if the callee's entry facts changed
        PointsToFlowSet existingEntry = entryFacts.get(calleeMC);
        if (!calleeEntry.equals(existingEntry)) {
            // Merge (union) with existing entry — sound: take all possibilities
            PointsToFlowSet merged = existingEntry != null
                ? new PointsToFlowSet(existingEntry) : new PointsToFlowSet();
            merged.mergeWith(calleeEntry);
            entryFacts.put(calleeMC, merged);

            // Enqueue callee for (re)analysis
            if (!worklist.contains(calleeMC)) {
                worklist.add(calleeMC);
                if (existingEntry == null) contextsCreated++;
            }
        }

        // If callee has been analyzed and has exit facts, propagate return type
        PointsToFlowSet calleeExit = exitFacts.get(calleeMC);
        if (calleeExit != null && callStmt instanceof AssignStmt) {
            Value lhs = ((AssignStmt) callStmt).getLeftOp();
            if (lhs instanceof Local) {
                // The return type of the callee becomes the type of lhs
                propagateReturnTypes(callee, calleeExit, (Local) lhs, callerFacts);
            }
        }
    }

    /**
     * Maps actual argument types from the call site into formal parameter
     * locals of the callee — this is the key context-sensitivity step.
     *
     * For a call:  result = helper(new Dog())
     *   callerFacts has: $r_arg → {Dog}
     *   calleeEntry gets: param0Local → {Dog}
     *
     * This is what distinguishes (helper, [s1]) called with Dog
     * from (helper, [s2]) called with Cat.
     */
    private PointsToFlowSet buildCalleeEntry(InvokeExpr ie,
                                              PointsToFlowSet callerFacts,
                                              SootMethod callee) {
        PointsToFlowSet entry = new PointsToFlowSet();
        Body calleeBody;
        try { calleeBody = callee.retrieveActiveBody(); }
        catch (Exception e) { return entry; }

        // Find the formal parameter locals in the callee body
        // They appear as IdentityStmts at the top: $p0 := @parameter0: T
        Map<Integer, Local> paramLocals = getParamLocals(calleeBody);

        // Map actual args → formal params
        for (int i = 0; i < ie.getArgCount(); i++) {
            Value arg = ie.getArg(i);
            Local paramLocal = paramLocals.get(i);
            if (paramLocal == null) continue;

            if (arg instanceof Local) {
                // actual arg is a local — copy its type set
                for (soot.Type t : callerFacts.getLocalTypes((Local) arg)) {
                    entry.assignLocal(paramLocal, t);
                }
                // If no info in callerFacts, use the declared type as fallback
                if (callerFacts.getLocalTypes((Local) arg).isEmpty()) {
                    entry.assignLocal(paramLocal, arg.getType());
                }
            } else {
                // constant or other value — use declared type
                entry.assignLocal(paramLocal, arg.getType());
            }
        }

        // For instance calls, also bind the receiver (this)
        if (ie instanceof InstanceInvokeExpr) {
            Value base = ((InstanceInvokeExpr) ie).getBase();
            Local thisLocal = getThisLocal(calleeBody);
            if (thisLocal != null && base instanceof Local) {
                for (soot.Type t : callerFacts.getLocalTypes((Local) base)) {
                    entry.assignLocal(thisLocal, t);
                }
                if (callerFacts.getLocalTypes((Local) base).isEmpty()) {
                    entry.assignLocal(thisLocal, base.getType());
                }
            }
        }

        return entry;
    }

    /**
     * Propagates the callee's return types into the LHS local of the caller.
     * Collects types from all return statements in the callee's exit facts.
     */
    private void propagateReturnTypes(SootMethod callee,
                                       PointsToFlowSet calleeExit,
                                       Local lhs,
                                       PointsToFlowSet callerFacts) {
        Body calleeBody;
        try { calleeBody = callee.retrieveActiveBody(); }
        catch (Exception e) { return; }

        for (Unit u : calleeBody.getUnits()) {
            if (u instanceof ReturnStmt) {
                Value retVal = ((ReturnStmt) u).getOp();
                if (retVal instanceof Local) {
                    for (soot.Type t : calleeExit.getLocalTypes((Local) retVal)) {
                        callerFacts.assignLocal(lhs, t);
                    }
                } else if (retVal != null) {
                    callerFacts.assignLocal(lhs, retVal.getType());
                }
            }
        }
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
    private SootMethod resolveCallee(Stmt stmt, InvokeExpr ie) {
        try {
            if (ie instanceof StaticInvokeExpr || ie instanceof SpecialInvokeExpr) {
                return ie.getMethod();
            }
            // For virtual/interface: use the call graph as oracle
            Iterator<soot.jimple.toolkits.callgraph.Edge> edges =
                Scene.v().getCallGraph().edgesOutOf(stmt);
            // Take first concrete target (approximation — interprocedural analysis
            // will refine this by checking actual argument types)
            while (edges.hasNext()) {
                SootMethod tgt = edges.next().tgt();
                if (tgt.isConcrete() && !tgt.getName().equals("<clinit>"))
                    return tgt;
            }
        } catch (Exception e) {
            // ignore unresolvable calls
        }
        return null;
    }

    /** Extracts the formal parameter locals from the top of a Jimple body. */
    private Map<Integer, Local> getParamLocals(Body body) {
        Map<Integer, Local> params = new HashMap<>();
        for (Unit u : body.getUnits()) {
            if (!(u instanceof IdentityStmt)) break;
            IdentityStmt id = (IdentityStmt) u;
            if (id.getRightOp() instanceof ParameterRef) {
                int idx = ((ParameterRef) id.getRightOp()).getIndex();
                params.put(idx, (Local) id.getLeftOp());
            }
        }
        return params;
    }

    /** Returns the local bound to @this, or null if static. */
    private Local getThisLocal(Body body) {
        for (Unit u : body.getUnits()) {
            if (!(u instanceof IdentityStmt)) break;
            IdentityStmt id = (IdentityStmt) u;
            if (id.getRightOp() instanceof ThisRef)
                return (Local) id.getLeftOp();
        }
        return null;
    }

    private boolean isAppMethod(SootMethod m) {
        return m.getDeclaringClass().isApplicationClass();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public query API  (used by InterPTAResolver)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns per-statement flow facts for (method, ctx), or null if not analyzed.
     * The resolver uses this to look up the receiver type at a call site.
     */
    public Map<Unit, PointsToFlowSet> getStmtFacts(MethodContext mc) {
        return stmtFacts.get(mc);
    }

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
     * Returns the unique receiver type at callStmt under the given context,
     * or null if unknown/multiple.
     */
    public soot.Type getUniqueReceiverType(MethodContext mc, Stmt callStmt) {
        PointsToFlowSet facts = getFactsBefore(mc, callStmt);
        if (facts == null) return null;

        InvokeExpr ie = callStmt.getInvokeExpr();
        if (!(ie instanceof InstanceInvokeExpr)) return null;
        Value base = ((InstanceInvokeExpr) ie).getBase();
        if (!(base instanceof Local)) return null;

        return facts.getUniqueLocalType((Local) base);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Inner class: result of analyzing one (method, context)
    // ─────────────────────────────────────────────────────────────────────────

    private static class AnalysisResult {
        final Map<Unit, PointsToFlowSet> stmtFacts;
        AnalysisResult(Map<Unit, PointsToFlowSet> stmtFacts) {
            this.stmtFacts = stmtFacts;
        }
    }
}