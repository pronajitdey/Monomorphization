import soot.SootMethod;

/**
 * A (method, call-string context) pair.
 *
 * This is the unit of work in the interprocedural analysis.
 * The same method may be analyzed multiple times, once per distinct
 * calling context (call string). That is exactly what makes the
 * analysis context-sensitive.
 *
 * Example (K=1):
 *   main calls helper at site s1  →  MethodContext(helper, [s1])
 *   main calls helper at site s2  →  MethodContext(helper, [s2])
 *   These are two distinct work items, analyzed independently.
 *   Their local type maps don't interfere with each other.
 */
public final class MethodContext {

    public final SootMethod method;
    public final CallString ctx;

    public MethodContext(SootMethod method, CallString ctx) {
        this.method = method;
        this.ctx    = ctx;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MethodContext)) return false;
        MethodContext other = (MethodContext) o;
        return method.equals(other.method) && ctx.equals(other.ctx);
    }

    @Override
    public int hashCode() {
        return 31 * method.hashCode() + ctx.hashCode();
    }

    @Override
    public String toString() {
        return method.getName() + ctx.toString();
    }
}