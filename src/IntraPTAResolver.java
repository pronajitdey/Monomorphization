import soot.*;
import soot.jimple.*;

import java.util.*;

/**
 * Intraprocedural PTA Resolver — Layer 2.
 *
 * Resolves virtual call sites using the field-sensitive
 * IntraProceduralPTA. For each call site:
 *
 *   1. Run (or fetch cached) IntraProceduralPTA on the caller's body.
 *   2. Look up the receiver local's type set just before the call stmt.
 *   3. If exactly one type → resolve the dispatch on that type → monomorphic.
 *   4. Otherwise → return null (polymorphic or unknown).
 *
 * This catches two classes of sites that VTA misses:
 *
 *   A. Flow-sensitive cases: a local is assigned the same concrete type
 *      on all paths, but VTA's global view merged it with other types
 *      elsewhere in the program.
 *
 *   B. Field-read cases: the receiver is loaded from a field whose
 *      concrete type the field-sensitive analysis tracked. VTA treats
 *      all field reads of a given declared type as unknown.
 *
 *      Example:
 *        $r1 = $r0.<Engine: Animal animal>  // field read
 *        virtualinvoke $r1.<Animal: speak()>()
 *
 *      If fieldMap[($r0, Engine.animal)] = {Dog}, then $r1 = {Dog}
 *      and the call is monomorphic.
 */
public class IntraPTAResolver implements CallTargetResolver {

    // Cache: SootMethod → its PTA result (lazy, computed on first query)
    private final Map<SootMethod, IntraProceduralPTA> cache = new HashMap<>();

    @Override
    public SootMethod resolve(Stmt stmt, SootMethod caller) {
        if (!stmt.containsInvokeExpr()) return null;
        InvokeExpr ie = stmt.getInvokeExpr();

        if (!(ie instanceof VirtualInvokeExpr)
         && !(ie instanceof InterfaceInvokeExpr)) return null;

        InstanceInvokeExpr iie = (InstanceInvokeExpr) ie;
        if (!(iie.getBase() instanceof Local)) return null;
        Local receiver = (Local) iie.getBase();

        // Get or run the PTA for this method
        IntraProceduralPTA pta = getOrAnalyze(caller);
        if (pta == null) return null;

        // What unique concrete type does the receiver have just before this call?
        Type receiverType = pta.getUniqueLocalTypeBefore(stmt, receiver);
        if (receiverType == null) return null; // unknown or multiple types

        if (!(receiverType instanceof RefType)) return null;
        SootClass receiverClass = ((RefType) receiverType).getSootClass();

        // Resolve virtual dispatch on the concrete type
        SootMethod target = resolveDispatch(
            receiverClass,
            ie.getMethodRef().getName(),
            ie.getMethodRef().getParameterTypes(),
            ie.getMethodRef().getReturnType()
        );

        if (target == null || !VTAResolver.isSafeTarget(target)) return null;
        PTADebugPrinter.printResolution(stmt, caller, target, "IntraPTA");
        return target;
    }

    @Override
    public String name() {
        return "Intraprocedural PTA (flow-sensitive + field-sensitive)";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private IntraProceduralPTA getOrAnalyze(SootMethod method) {
        if (cache.containsKey(method)) return cache.get(method);

        if (!method.isConcrete() || method.isNative()) {
            cache.put(method, null);
            return null;
        }

        try {
            Body body = method.retrieveActiveBody();
            IntraProceduralPTA pta = new IntraProceduralPTA(body);
            cache.put(method, pta);
            return pta;
        } catch (Exception e) {
            cache.put(method, null);
            return null;
        }
    }

    /**
     * Walks up the class hierarchy to find the first concrete method
     * matching the given name/params/return — mirrors JVM virtual dispatch.
     */
    private SootMethod resolveDispatch(SootClass startClass, String name,
                                        List<Type> paramTypes, Type returnType) {
        SootClass current = startClass;
        while (current != null) {
            try {
                SootMethod m = current.getMethod(name, paramTypes, returnType);
                if (!m.isAbstract()) return m;
            } catch (RuntimeException ignored) {}
            if (!current.hasSuperclass()) break;
            current = current.getSuperclass();
        }
        return null;
    }
}