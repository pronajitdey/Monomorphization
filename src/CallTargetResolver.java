import soot.SootMethod;
import soot.jimple.Stmt;

public interface CallTargetResolver {
    /**
     * 
     * @param stmt      the Jimple statement containing the invoke expression
     * @param caller    the method that contains stmt
     * @return          the unique concrete target, or null
     */
    SootMethod resolve(Stmt stmt, SootMethod caller);
    
    String name();
}