import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
 
import java.util.*;

public class VTAResolver implements CallTargetResolver {
    
    private final CallGraph cg;

    public VTAResolver(CallGraph cg) {
        this.cg = cg;
    }

    @Override
    public SootMethod resolve(Stmt stmt, SootMethod caller) {
        if (!stmt.containsInvokeExpr()) return null;
        InvokeExpr ie = stmt.getInvokeExpr();
        if (!(ie instanceof VirtualInvokeExpr) && !(ie instanceof InterfaceInvokeExpr)) return null;

        // Count call graph edges out of this specific callsite
        Iterator<Edge> edges = cg.edgesOutOf(stmt);
        SootMethod singleTarget = null;
        int count = 0;

        while (edges.hasNext()) {
            SootMethod tgt = edges.next().tgt();
            if (tgt.getName().equals("<clinit>")) continue;
            count++;
            singleTarget = tgt;
            if (count > 1) return null; // more than one target = polymorphic
        }

        // Exactly one target: check it is safe to transform
        if (count == 1 && isSafeTarget(singleTarget)) {
            return singleTarget;
        }
        return null;
    }

    @Override
    public String name() {
        return "VTA (SPARK, whole-program)";
    }

    /**
     * A target is safe to devirtualize if:
     *   - It has a concrete body we can clone
     *   - It is not native (no body available)
     *   - It is not abstract (no body)
     *   - It is not a constructor (constructors use specialinvoke, not virtualinvoke,
     *     but guard anyway)
     *   - It is not already static (would mean VTA gave us a wrong edge)
     */
    public static boolean isSafeTarget(SootMethod m) {
        return m != null
            && m.isConcrete()
            && !m.isNative()
            && !m.isAbstract()
            && !m.isConstructor()
            && !m.getName().equals("<clinit>")
            && !m.isStatic();
    }

    /**
     * Prints the call graph restricted to application classes.
     *
     * For each method in the application, lists every outgoing call edge
     * and marks each call site as MONOMORPHIC (1 target) or
     * POLYMORPHIC (N targets).
     *
     * This output lets you verify that VTA correctly identified which
     * call sites are monomorphic before looking at the transformed bytecode.
     *
     * Call this from Main after the call graph is built (i.e. inside
     * internalTransform, which runs after PackManager.runPacks() has
     * finished the cg phase).
     */
    public void printCallGraph() {
        System.out.println("\n============================================");
        System.out.println("  VTA Call Graph (application classes only)");
        System.out.println("============================================");
 
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            for (SootMethod method : sc.getMethods()) {
                if (!method.isConcrete()) continue;
 
                // Collect all outgoing edges from this method, grouped by call site
                // We need to group by statement so we can count targets per site
                Body body;
                try {
                    body = method.retrieveActiveBody();
                } catch (Exception e) {
                    continue;
                }
 
                boolean methodPrinted = false;
 
                for (Unit u : body.getUnits()) {
                    Stmt stmt = (Stmt) u;
                    if (!stmt.containsInvokeExpr()) continue;
 
                    InvokeExpr ie = stmt.getInvokeExpr();
 
                    // Collect targets for this call site
                    Iterator<Edge> edgeIt = cg.edgesOutOf(stmt);
                    List<SootMethod> targets = new ArrayList<>();
                    while (edgeIt.hasNext()) {
                        SootMethod tgt = edgeIt.next().tgt();
                        if (!tgt.getName().equals("<clinit>")) targets.add(tgt);
                    }
 
                    if (targets.isEmpty()) continue;
 
                    // Print method header once
                    if (!methodPrinted) {
                        System.out.println("\n[CG] " + method.getSignature());
                        methodPrinted = true;
                    }
 
                    // Determine call kind label
                    String kind;
                    if (ie instanceof VirtualInvokeExpr || ie instanceof InterfaceInvokeExpr) {
                        kind = targets.size() == 1 ? "VIRTUAL → MONOMORPHIC" : "VIRTUAL → POLYMORPHIC (" + targets.size() + " targets)";
                    } else if (ie instanceof StaticInvokeExpr) {
                        kind = "STATIC";
                    } else if (ie instanceof SpecialInvokeExpr) {
                        kind = "SPECIAL (<init> or super)";
                    } else {
                        kind = "OTHER";
                    }
 
                    // Print call site
                    System.out.println("      stmt : " + stmt);
                    System.out.println("      kind : " + kind);
                    for (SootMethod tgt : targets) {
                        System.out.println("      tgt  : " + tgt.getSignature());
                    }
                }
            }
        }
 
        System.out.println("\n===========================================\n");
    }
}
