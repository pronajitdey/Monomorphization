import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;

import java.util.Set;

/**
 * Field-Sensitive Intraprocedural Points-To Analysis.
 *
 * Extended to accept optional seed entry facts — used by the
 * interprocedural layer to inject parameter type information computed
 * from the caller context before running the fixed-point iteration.
 *
 * When called from InterProceduralPTA:
 *   - entryFacts contains localMap entries for @this and @parameter locals
 *     already bound to the concrete types the caller passed in.
 *   - The identity statements at the top of the body still run, but they
 *     will override @this/@param bindings with declared types — so the
 *     interprocedural layer applies its refinements AFTER intra runs,
 *     by overlaying callee entry facts at the start of flowThrough for
 *     identity stmts. See InterProceduralPTA.buildCalleeEntry().
 *
 * When called standalone (Layer 2 only):
 *   - entryFacts is empty (new PointsToFlowSet()).
 *   - Identity stmts populate types from declared signatures only.
 */

/**
 * Field-Sensitive Intraprocedural Points-To Analysis.
 *
 * Accepts a MethodSeed that was built by the interprocedural engine
 * by mapping CALLER argument types → CALLEE parameter locals by position.
 *
 * How the seed integrates:
 * ─────────────────────────────────────────────────────────────────────────
 * Every Jimple body starts with identity statements that bind formals:
 *
 *   $p0 := @this: Engine           // this-local
 *   $p1 := @parameter0: Animal     // first formal
 *   $p2 := @parameter1: int        // second formal
 *
 * When the seed contains:  {$p1 → {Dog}, $p2 → {int}}
 * the flowThrough for the @parameter0 identity statement uses Dog
 * (the caller-derived type) instead of the declared type Animal.
 *
 * This is what gives context sensitivity:
 *   call site s1: helper(new Dog())  → seed $p1 → {Dog}
 *   call site s2: helper(new Cat())  → seed $p1 → {Cat}
 * Each (method, callSite) pair produces a different analysis result.
 *
 * CORRECTNESS NOTE:
 * The seed keys are Local objects from THIS callee's body — resolved
 * by the interprocedural engine by scanning @parameter IdentityStmts.
 * They are never borrowed from the caller's body.
 * ─────────────────────────────────────────────────────────────────────────
 */
public class IntraProceduralPTA
        extends ForwardFlowAnalysis<Unit, PointsToFlowSet> {

    private final MethodSeed seed;
 
    /** Standalone (no interprocedural context). */
    public IntraProceduralPTA(Body body) {
        this(body, MethodSeed.EMPTY);
    }
 
    /**
     * Seeded constructor — called by InterProceduralPTA.
     * seed contains callee-local → types mappings already resolved
     * by the interprocedural engine using argument positions.
     */
    public IntraProceduralPTA(Body body, MethodSeed seed) {
        super(new ExceptionalUnitGraph(body));
        this.seed = seed;
        doAnalysis();
    }

    /**
     * Core constructor. contextLabel is only used for debug output.
     * seed contains callee-local → types mappings already resolved
     * by SeedBuilder using argument positions — never borrowed from caller.
     */
    public IntraProceduralPTA(Body body, MethodSeed seed, String contextLabel) {
        super(new ExceptionalUnitGraph(body));
        this.seed = seed;
        doAnalysis();
 
        // Print per-statement localMap + fieldMap after analysis completes
        if (PTADebugPrinter.DEBUG_LEVEL >= 2) {
            PTADebugPrinter.printIntraState(this, body, contextLabel, seed);
        } else if (PTADebugPrinter.DEBUG_LEVEL == 1) {
            // Compute exit facts inline for the summary line
            PointsToFlowSet exit = new PointsToFlowSet();
            for (Unit u : body.getUnits()) {
                if (u instanceof soot.jimple.ReturnStmt
                 || u instanceof soot.jimple.ReturnVoidStmt) {
                    exit.mergeWith(getFlowBefore(u));
                }
            }
            PTADebugPrinter.printIntraSummary(body, contextLabel, seed, exit);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Transfer function
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void flowThrough(PointsToFlowSet in, Unit unit,
                                PointsToFlowSet out) {
        copy(in, out);

        if (!(unit instanceof Stmt)) return;
        Stmt stmt = (Stmt) unit;

        // ── Identity statements (@this, @parameter) ───────────────────────────
        if (stmt instanceof IdentityStmt) {
            IdentityStmt id = (IdentityStmt) stmt;
            if (!(id.getLeftOp() instanceof Local)) return;
            Local lhs = (Local) id.getLeftOp();
            Value rhs = id.getRightOp();
 
            if (rhs instanceof ThisRef || rhs instanceof ParameterRef) {
                // Does the interprocedural seed have caller-derived types
                // for this exact local (which exists in THIS callee's body)?
                Set<Type> seededTypes = seed.getTypes(lhs);
                if (!seededTypes.isEmpty()) {
                    // Use caller-derived types as one unioned strong update.
                    out.assignLocalTypes(lhs, seededTypes);
                } else {
                    // No seed info — fall back to the declared type from the IR
                    out.assignIdentity(lhs, rhs.getType());
                }
            }
            return;
        }

        // ── Assignment statements ─────────────────────────────────────────────
        if (!(stmt instanceof AssignStmt)) return;
        AssignStmt as  = (AssignStmt) stmt;
        Value lhsVal   = as.getLeftOp();
        Value rhsVal   = as.getRightOp();
 
        // ── Field write: a.f = b ──────────────────────────────────────────────
        if (lhsVal instanceof InstanceFieldRef) {
            InstanceFieldRef lhsRef = (InstanceFieldRef) lhsVal;
            if (!(lhsRef.getBase() instanceof Local)) return;
            Local base    = (Local) lhsRef.getBase();
            SootField fld = lhsRef.getField();
            if (rhsVal instanceof Local)
                out.writeField(base, fld, (Local) rhsVal);
            else if (rhsVal instanceof NewExpr)
                out.writeFieldDirect(base, fld, ((NewExpr) rhsVal).getType());
            return;
        }
 
        if (!(lhsVal instanceof Local)) return;
        Local lhs = (Local) lhsVal;

        // x = new T()
        if (rhsVal instanceof NewExpr)
            out.assignLocal(lhs, ((NewExpr) rhsVal).getType());
        // x = y
        else if (rhsVal instanceof Local)
            out.copyLocal(lhs, (Local) rhsVal);
        // x = (T) y
        else if (rhsVal instanceof CastExpr) {
            Value op = ((CastExpr) rhsVal).getOp();
            if (op instanceof Local) out.copyLocal(lhs, (Local) op);
        }
        // x = a.f  (field read)
        else if (rhsVal instanceof InstanceFieldRef) {
            InstanceFieldRef rhsRef = (InstanceFieldRef) rhsVal;
            if (rhsRef.getBase() instanceof Local)
                out.readField(lhs, (Local) rhsRef.getBase(), rhsRef.getField());
        }
        // x = new T[] / new T[][]
        else if (rhsVal instanceof NewArrayExpr
              || rhsVal instanceof NewMultiArrayExpr)
            out.assignLocal(lhs, rhsVal.getType());
        // call result, static field, array read → conservative (no update)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lattice
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void merge(PointsToFlowSet in1, PointsToFlowSet in2,
                         PointsToFlowSet out) {
        copy(in1, out);
        out.mergeWith(in2);
    }

    @Override
    protected void copy(PointsToFlowSet src, PointsToFlowSet dst) {
        dst.clear();
        dst.mergeWith(src);
    }

    @Override
    protected PointsToFlowSet newInitialFlow() { return new PointsToFlowSet(); }

    @Override
    protected PointsToFlowSet entryInitialFlow() {
        return new PointsToFlowSet();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query
    // ─────────────────────────────────────────────────────────────────────────

    public Type getUniqueLocalTypeBefore(Unit stmt, Local local) {
        return getFlowBefore(stmt).getUniqueLocalType(local);
    }

    public Set<Type> getLocalTypesBefore(Unit stmt, Local local) {
        return getFlowBefore(stmt).getLocalTypes(local);
    }
}
