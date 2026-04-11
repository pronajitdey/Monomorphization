import soot.*;
import soot.jimple.*;
import soot.util.Chain;

import java.util.*;

/**
 * MonomorphizationTransformer
 *
 * A Soot SceneTransformer that replaces monomorphic virtual call sites
 * with calls to a newly created static copy of the target method.
 *
 * For each call site that the resolver declares monomorphic:
 *
 * The static clone:
 *   - Has the same body as the original method
 *   - Has @this replaced by @parameter0 in its identity statements
 *   - Is declared static, so no vtable lookup is needed at the call site
 *
 * Why this is faster under -Xint (interpreter mode):
 *   A virtualinvoke requires: null check on receiver, vtable index lookup,
 *   method pointer dereference, then the call.
 *   A staticinvoke requires: null check on receiver (optional), direct call.
 *   Under -Xint there is no JIT to inline the vtable lookup, so the
 *   savings are directly observable in wall-clock time.
 */
public class MonomorphizationTransformer extends SceneTransformer {

    private final CallTargetResolver resolver;

    // counters for the statistics summary
    private int totalVirtual = 0;
    private int monomorphic  = 0;
    private int transformed  = 0;
    private int skipped      = 0;

    // cache: original target method -> its static clone
    // ensures we create at most one clone per target, even if the same
    // target is called from multiple monomorphic call sites
    // Cache key: "methodSignature##receiverType"
    // A target method may be called from sites with different declared
    // receiver types (e.g. Animal1 vs Dog1), so we cache per (target, receiverType).
    private final Map<String, SootMethod> cloneCache = new HashMap<>();

    public MonomorphizationTransformer(CallTargetResolver resolver) {
        this.resolver = resolver;
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Override
    public void internalTransform(String phaseName, Map<String, String> options) {
        System.out.println("[Mono] Resolver      : " + resolver.name());
        System.out.println("[Mono] Starting transformation pass...");

        // Pass 1: scan all methods, collect sites to transform
        // We collect first and transform second to avoid ConcurrentModificationException
        // while iterating over the Units chain
        List<TransformSite> sites = collectMonomorphicSites();

        System.out.println("[Mono] Virtual sites : " + totalVirtual);
        System.out.println("[Mono] Monomorphic   : " + monomorphic);

        // Pass 2: apply the transformation at each collected site
        for (TransformSite site : sites) {
            try {
                applyTransformation(site);
                transformed++;
            } catch (Exception e) {
                skipped++;
                System.err.println("[Mono] SKIP "
                    + site.caller.getSignature()
                    + " -> " + site.target.getSignature()
                    + " reason: " + e.getMessage());
            }
        }

        System.out.println("[Mono] Transformed   : " + transformed);
        System.out.println("[Mono] Skipped       : " + skipped);
    }

    // -------------------------------------------------------------------------
    // Pass 1: collect monomorphic sites
    // -------------------------------------------------------------------------

    private List<TransformSite> collectMonomorphicSites() {
        List<TransformSite> sites = new ArrayList<>();

        for (SootClass sc : Scene.v().getApplicationClasses()) {
            for (SootMethod method : new ArrayList<>(sc.getMethods())) {
                if (!method.isConcrete()) continue;

                Body body = method.retrieveActiveBody();

                for (Unit u : new ArrayList<>(body.getUnits())) {
                    Stmt stmt = (Stmt) u;
                    if (!stmt.containsInvokeExpr()) continue;

                    InvokeExpr ie = stmt.getInvokeExpr();

                    // only consider virtual and interface dispatches
                    if (!(ie instanceof VirtualInvokeExpr)
                     && !(ie instanceof InterfaceInvokeExpr)) continue;

                    totalVirtual++;

                    // whether site is monomorphic
                    SootMethod target = resolver.resolve(stmt, method);
                    if (target == null) continue; // polymorphic or unknown

                    monomorphic++;
                    sites.add(new TransformSite(method, body, stmt, ie, target));
                }
            }
        }
        return sites;
    }

    // -------------------------------------------------------------------------
    // Pass 2: apply transformation at a site
    // -------------------------------------------------------------------------

    private void applyTransformation(TransformSite site) {
        InstanceInvokeExpr iie = (InstanceInvokeExpr) site.invokeExpr;
 
        // THE KEY FIX:
        // Use the declared type of the receiver local at this call site,
        // not the declaring class of the target method.
        //
        // iie.getBase() is the receiver local (e.g. $r0 of type Animal1).
        // iie.getBase().getType() gives Animal1 — the statically declared type.
        //
        // The static clone must accept Animal1 as its first parameter so that
        // the bytecode verifier is satisfied when it sees:
        //   invokestatic Dog1.speak$mono(Animal1)  with stack: [Animal1]
        Type receiverType = iie.getBase().getType();

        // Get or create the static clone of the target method
        SootMethod staticClone = getOrCreateClone(site.target, receiverType);

        // receiver becomes arg[0], original args follow
        List<Value> newArgs = new ArrayList<>();
        newArgs.add(iie.getBase());       // receiver object
        newArgs.addAll(iie.getArgs());    // original arguments

        // staticinvoke <Clone: retType name$mono(RecvType, ...)>(args)
        StaticInvokeExpr staticCall = Jimple.v()
            .newStaticInvokeExpr(staticClone.makeRef(), newArgs);

        // Patch the call site in-place — the InvokeExprBox wraps the expression
        // inside the statement; replacing its value rewrites the Jimple IR
        site.stmt.getInvokeExprBox().setValue(staticCall);
    }

    // -------------------------------------------------------------------------
    // Static clone creation
    // -------------------------------------------------------------------------

    private SootMethod getOrCreateClone(SootMethod target, Type receiverType) {
        String key = target.getSignature() + "##" + receiverType;
        if (cloneCache.containsKey(key)) return cloneCache.get(key);
        SootMethod clone = buildStaticClone(target, receiverType);
        cloneCache.put(key, clone);
        return clone;
    }

    /**
     * Creates a new static method on target's declaring class.
     *
     * New signature: static <retType> <name>$mono(<receiverType> this$0, <originalParams...>)
     *
     * The body is a clone of the original, with identity statements rewritten:
     *   @this: T       ->  @parameter0: T     (receiver is now explicit param)
     *   @parameter0: X ->  @parameter1: X     (all other params shift by 1)
     *   @parameter1: X ->  @parameter2: X
     *   ...
     */
    private SootMethod buildStaticClone(SootMethod target, Type receiverType) {
        SootClass declaringClass = target.getDeclaringClass();

        // New param list
        List<Type> newParamTypes = new ArrayList<>();
        // newParamTypes.add(declaringClass.getType());   
        // Above is incorrect (should be receivertype not target class type)
        newParamTypes.add(receiverType);   // this$0
        newParamTypes.addAll(target.getParameterTypes());

        // Pick a name that does not clash with any existing method on the class
        String cloneName = uniqueName(declaringClass, target.getName() + "$mono");

        SootMethod clone = new SootMethod(
            cloneName,
            newParamTypes,
            target.getReturnType(),
            Modifier.PUBLIC | Modifier.STATIC
        );

        // Copy checked exception declarations
        for (SootClass ex : target.getExceptions()) {
            clone.addException(ex);
        }

        // Register the new method on the class
        declaringClass.addMethod(clone);

        // Clone the body and re-attach it to the new method
        Body originalBody = target.retrieveActiveBody();
        Body clonedBody   = (Body) originalBody.clone();
        clonedBody.setMethod(clone);
        clone.setActiveBody(clonedBody);

        // Rewrite identity statements inside the cloned body
        rewriteIdentityStmts(clonedBody, clone);

        System.out.println("[Mono]   clone: " + clone.getSignature());

        return clone;
    }

    /**
     * Rewrites the identity statements at the top of a Jimple method body.
     *
     * Every concrete Jimple method starts with a block of IdentityStmts that
     * bind the implicit receiver and formal parameters to named locals:
     *
     *   $r0 := @this: Animal1        <- implicit receiver
     *   $i0 := @parameter0: int      <- first formal param
     *   $i1 := @parameter1: int      <- second formal param
     *
     * Because the static clone makes the receiver explicit as parameter 0,
     * we must rewrite these to:
     *
     *   $r0 := @parameter0: Animal1  <- receiver is now param 0
     *   $i0 := @parameter1: int      <- former param 0 is now param 1
     *   $i1 := @parameter2: int      <- former param 1 is now param 2
     *
     * We collect replacements first, then apply them, to avoid mutating
     * the chain while iterating over it.
     */
    private void rewriteIdentityStmts(Body body, SootMethod clone) {
        Chain<Unit> units = body.getUnits();

        // Collect all leading IdentityStmts (they are always contiguous at the top)
        List<Unit> identityStmts = new ArrayList<>();
        for (Unit u : units) {
            if (u instanceof IdentityStmt) identityStmts.add(u);
            else break;
        }

        List<Unit> toRemove = new ArrayList<>();
        List<Unit[]> toInsert = new ArrayList<>(); // each entry: {insertBefore, newStmt}

        for (Unit u : identityStmts) {
            IdentityStmt id = (IdentityStmt) u;
            Value rhs = id.getRightOp();
            Local lhs = (Local) id.getLeftOp();

            if (rhs instanceof ThisRef) {
                // @this -> @parameter0
                IdentityStmt rewritten = Jimple.v().newIdentityStmt(
                    lhs,
                    Jimple.v().newParameterRef(clone.getParameterType(0), 0)
                );
                toInsert.add(new Unit[]{u, rewritten});
                toRemove.add(u);

            } else if (rhs instanceof ParameterRef) {
                // @parameter(i) -> @parameter(i+1)
                int oldIdx = ((ParameterRef) rhs).getIndex();
                int newIdx = oldIdx + 1;
                IdentityStmt rewritten = Jimple.v().newIdentityStmt(
                    lhs,
                    Jimple.v().newParameterRef(clone.getParameterType(newIdx), newIdx)
                );
                toInsert.add(new Unit[]{u, rewritten});
                toRemove.add(u);
            }
        }

        // Insert new stmt before the old one, then remove the old one
        for (Unit[] pair : toInsert) units.insertBefore(pair[1], pair[0]);
        for (Unit u : toRemove) units.remove(u);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** Returns a method name that is not already declared on sc. */
    private String uniqueName(SootClass sc, String base) {
        String name = base;
        int suffix = 0;
        while (sc.declaresMethodByName(name)) name = base + "$" + (++suffix);
        return name;
    }

    // -------------------------------------------------------------------------
    // Internal data class
    // -------------------------------------------------------------------------

    private static class TransformSite {
        final SootMethod caller;
        final Body       body;
        final Stmt       stmt;
        final InvokeExpr invokeExpr;
        final SootMethod target;

        TransformSite(SootMethod caller, Body body, Stmt stmt,
                      InvokeExpr invokeExpr, SootMethod target) {
            this.caller     = caller;
            this.body       = body;
            this.stmt       = stmt;
            this.invokeExpr = invokeExpr;
            this.target     = target;
        }
    }
}