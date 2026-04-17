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
public class IntraProceduralPTA
        extends ForwardFlowAnalysis<Unit, PointsToFlowSet> {

    private final PointsToFlowSet seedEntry;

    /** Standalone constructor — no interprocedural seed. */
    public IntraProceduralPTA(Body body) {
        this(body, new PointsToFlowSet());
    }

    /** Seeded constructor — called by InterProceduralPTA with caller-derived entry facts. */
    public IntraProceduralPTA(Body body, PointsToFlowSet seedEntry) {
        super(new ExceptionalUnitGraph(body));
        this.seedEntry = seedEntry;
        doAnalysis();
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

        // ── Identity statements ───────────────────────────────────────────────
        if (stmt instanceof IdentityStmt) {
            IdentityStmt id = (IdentityStmt) stmt;
            if (!(id.getLeftOp() instanceof Local)) return;
            Local lhs = (Local) id.getLeftOp();
            Value rhs = id.getRightOp();

            if (rhs instanceof ThisRef || rhs instanceof ParameterRef) {
                // If the interprocedural layer seeded a concrete type for this
                // local, prefer it over the declared type.
                // The seed is checked via: does seedEntry have type info for lhs?
                Set<Type> seeded = seedEntry.getLocalTypes(lhs);
                if (!seeded.isEmpty()) {
                    // Use the seeded (caller-derived) type
                    for (Type t : seeded) out.assignLocal(lhs, t);
                } else {
                    // Fall back to the declared type
                    out.assignIdentity(lhs, rhs.getType());
                }
            }
            return;
        }

        // ── Assignment statements ─────────────────────────────────────────────
        if (!(stmt instanceof AssignStmt)) return;
        AssignStmt as = (AssignStmt) stmt;
        Value lhsVal = as.getLeftOp();
        Value rhsVal = as.getRightOp();

        // ── Field write: a.f = b ──────────────────────────────────────────────
        if (lhsVal instanceof InstanceFieldRef) {
            InstanceFieldRef lhsRef = (InstanceFieldRef) lhsVal;
            if (!(lhsRef.getBase() instanceof Local)) return;
            Local base  = (Local) lhsRef.getBase();
            SootField fld = lhsRef.getField();

            if (rhsVal instanceof Local)
                out.writeField(base, fld, (Local) rhsVal);
            else if (rhsVal instanceof NewExpr)
                out.writeFieldDirect(base, fld, ((NewExpr) rhsVal).getType());
            return;
        }

        // ── LHS must be a Local for remaining cases ───────────────────────────
        if (!(lhsVal instanceof Local)) return;
        Local lhs = (Local) lhsVal;

        if (rhsVal instanceof NewExpr)
            out.assignLocal(lhs, ((NewExpr) rhsVal).getType());
        else if (rhsVal instanceof Local)
            out.copyLocal(lhs, (Local) rhsVal);
        else if (rhsVal instanceof CastExpr) {
            Value op = ((CastExpr) rhsVal).getOp();
            if (op instanceof Local) out.copyLocal(lhs, (Local) op);
        } else if (rhsVal instanceof InstanceFieldRef) {
            InstanceFieldRef rhsRef = (InstanceFieldRef) rhsVal;
            if (rhsRef.getBase() instanceof Local)
                out.readField(lhs, (Local) rhsRef.getBase(), rhsRef.getField());
        } else if (rhsVal instanceof NewArrayExpr
                || rhsVal instanceof NewMultiArrayExpr)
            out.assignLocal(lhs, rhsVal.getType());
        // call results, static fields, array reads → conservative (leave as-is)
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
        // The entry node starts with the seed facts from the caller.
        // A deep copy is essential — multiple analyses share the same seedEntry
        // object but must not alias each other's flow sets.
        return new PointsToFlowSet(seedEntry);
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