import soot.*;
import soot.jimple.*;

import java.util.*;

/**
 * Builds a MethodSeed for a callee from a call site in the caller.
 *
 * This is the component that correctly bridges caller and callee locals.
 *
 * Algorithm:
 * ─────────────────────────────────────────────────────────────────────────
 * 1. Scan the callee body's leading IdentityStmts to find:
 *      thisLocal:     the Local bound to @this
 *      paramLocals:   index → Local for @parameter(i) bindings
 *
 * 2. For each argument at the call site in the caller:
 *      actualArg = ie.getArg(i)            (a Value in CALLER's body)
 *      calleeParamLocal = paramLocals[i]   (a Local in CALLEE's body)
 *
 *    If actualArg is a Local in the caller:
 *      types = callerFacts.getTypes(actualArg)  (from caller's PTA result)
 *      seed[calleeParamLocal] = types
 *    Else (constant, new expr, etc.):
 *      seed[calleeParamLocal] = {actualArg.getType()}
 *
 * 3. For instance calls, also map the receiver:
 *      receiver = ((InstanceInvokeExpr) ie).getBase()   (Local in CALLER)
 *      thisLocal = callee's @this local
 *      seed[thisLocal] = callerFacts.getTypes(receiver)
 *
 * The resulting MethodSeed has keys that are CALLEE-LOCAL objects,
 * never borrowed from the caller. This makes it safe to pass directly
 * into IntraProceduralPTA without any name-matching logic.
 * ─────────────────────────────────────────────────────────────────────────
 */
public class SeedBuilder {

    /**
     * Builds the seed for a callee invoked at callSite in the caller,
     * using callerFacts (the flow-before facts at callSite in the caller).
     *
     * @param callSite    the invoke statement in the caller's body
     * @param callee      the method being called
     * @param callerFacts the PTA flow-before facts at callSite in the caller
     * @return a MethodSeed mapping callee-locals to caller-derived types
     */
    public static MethodSeed build(Stmt callSite,
                                    SootMethod callee,
                                    PointsToFlowSet callerFacts) {
        if (!callSite.containsInvokeExpr()) return MethodSeed.EMPTY;

        InvokeExpr ie = callSite.getInvokeExpr();

        // Get callee body — needed to find the parameter local names
        Body calleeBody;
        try {
            calleeBody = callee.retrieveActiveBody();
        } catch (Exception e) {
            return MethodSeed.EMPTY;
        }

        // Step 1: scan callee body's identity stmts to resolve callee-locals
        // These are the Local objects that actually live in the callee's body.
        Local calleeThisLocal = null;
        Map<Integer, Local> calleeParamLocals = new HashMap<>();

        for (Unit u : calleeBody.getUnits()) {
            if (!(u instanceof IdentityStmt)) break; // identity stmts are always first
            IdentityStmt id = (IdentityStmt) u;
            Value rhs       = id.getRightOp();
            Local lhs       = (Local) id.getLeftOp(); // a Local IN THE CALLEE

            if (rhs instanceof ThisRef) {
                calleeThisLocal = lhs;
            } else if (rhs instanceof ParameterRef) {
                int idx = ((ParameterRef) rhs).getIndex();
                calleeParamLocals.put(idx, lhs);
            }
        }

        // Step 2: build the seed map (callee-local → types)
        Map<Local, Set<Type>> seedMap = new HashMap<>();

        // Map actual arguments → formal parameter locals by index
        for (int i = 0; i < ie.getArgCount(); i++) {
            Local calleeParam = calleeParamLocals.get(i);
            if (calleeParam == null) continue;

            Value actualArg = ie.getArg(i); // a Value in CALLER's body

            Set<Type> types = resolveTypes(actualArg, callerFacts);
            if (!types.isEmpty()) {
                seedMap.put(calleeParam, types);
            }
        }

        // Map receiver (this) for instance calls
        if (ie instanceof InstanceInvokeExpr && calleeThisLocal != null) {
            Value receiver = ((InstanceInvokeExpr) ie).getBase(); // Local in CALLER

            Set<Type> types = resolveTypes(receiver, callerFacts);
            if (!types.isEmpty()) {
                seedMap.put(calleeThisLocal, types);
            }
        }

        return seedMap.isEmpty() ? MethodSeed.EMPTY : new MethodSeed(seedMap);
    }

    /**
     * Resolves the concrete types of a value using the caller's PTA facts.
     *
     * If the value is a Local in the caller, we look up its type set.
     * Otherwise (constant, NewExpr etc.) we use the declared static type.
     */
    private static Set<Type> resolveTypes(Value v, PointsToFlowSet callerFacts) {
        if (v instanceof Local) {
            // Look up the caller-local's types in caller's flow facts
            Set<Type> types = callerFacts.getLocalTypes((Local) v);
            if (!types.isEmpty()) return types;
            // If no info in PTA (e.g. parameter itself was not seeded), use declared type
            Set<Type> fallback = new HashSet<>();
            fallback.add(v.getType());
            return fallback;
        } else {
            // Constant, NewExpr, etc. — use the static type
            Set<Type> s = new HashSet<>();
            s.add(v.getType());
            return s;
        }
    }
}