import soot.*;
import soot.jimple.*;

import java.util.*;

/**
 * Layer 3 — Interprocedural PTA Resolver.
 *
 * Uses InterProceduralPTA (k-callstring context-sensitive) to resolve
 * virtual call sites that neither VTA nor intraprocedural PTA could resolve.
 *
 * The key scenario this handles:
 *
 *   static void helper(Animal a) {
 *       a.speak();   // VTA: polymorphic. IntraPTA: unknown param type.
 *   }
 *   main() {
 *       helper(new Dog()); // s1
 *       helper(new Cat()); // s2
 *   }
 *
 *   Under context [s1]: param a = {Dog} → a.speak() → Dog.speak() MONOMORPHIC
 *   Under context [s2]: param a = {Cat} → a.speak() → Cat.speak() MONOMORPHIC
 *
 * Resolution strategy:
 *   For each virtual call site, look up ALL contexts in which the enclosing
 *   method was analyzed. If EVERY context gives the same unique target,
 *   the site is monomorphic under all calling contexts → safe to transform.
 *   If different contexts give different targets, the site is polymorphic
 *   and must remain virtual.
 */
public class InterPTAResolver implements CallTargetResolver {

    private final InterProceduralPTA pta;

    public InterPTAResolver(InterProceduralPTA pta) {
        this.pta = pta;
    }

    @Override
    public SootMethod resolve(Stmt stmt, SootMethod caller) {
        if (!stmt.containsInvokeExpr()) return null;
        InvokeExpr ie = stmt.getInvokeExpr();

        if (!(ie instanceof VirtualInvokeExpr)
         && !(ie instanceof InterfaceInvokeExpr)) return null;

        InstanceInvokeExpr iie = (InstanceInvokeExpr) ie;
        if (!(iie.getBase() instanceof Local)) return null;
        Local receiver = (Local) iie.getBase();

        // Get all contexts under which this caller was analyzed
        Set<MethodContext> contexts = pta.getContextsFor(caller);
        if (contexts.isEmpty()) return null;

        // Check all contexts: the site is monomorphic only if EVERY
        // context gives the same unique target
        SootMethod agreedTarget = null;

        for (MethodContext mc : contexts) {
            PointsToFlowSet facts = pta.getFactsBefore(mc, stmt);
            if (facts == null) return null; // not analyzed under this ctx

            Type receiverType = facts.getUniqueLocalType(receiver);
            if (receiverType == null) return null; // unknown or multiple

            if (!(receiverType instanceof RefType)) return null;
            SootClass receiverClass = ((RefType) receiverType).getSootClass();

            SootMethod target = resolveDispatch(
                receiverClass,
                ie.getMethodRef().getName(),
                ie.getMethodRef().getParameterTypes(),
                ie.getMethodRef().getReturnType()
            );

            if (target == null || !VTAResolver.isSafeTarget(target)) return null;

            if (agreedTarget == null) {
                agreedTarget = target;
            } else if (!agreedTarget.equals(target)) {
                // Different contexts give different targets — polymorphic
                return null;
            }
        }

        return agreedTarget;
    }

    @Override
    public String name() {
        return "InterProceduralPTA (k-callstring, K=" + CallString.K + ")";
    }

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