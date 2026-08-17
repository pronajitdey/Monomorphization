import soot.Local;
import soot.Type;

import java.util.*;

/**
 * The seed facts passed into a callee's intraprocedural analysis.
 *
 * Keys are Local objects from the CALLEE's body, resolved by SeedBuilder
 * using argument positions — never borrowed from the caller.
 */
public class MethodSeed {

    public static final MethodSeed EMPTY = new MethodSeed(Collections.emptyMap());

    // package-private so SeedBuilder and InterProceduralPTA can access it
    final Map<Local, Set<Type>> localToTypes;

    public MethodSeed(Map<Local, Set<Type>> localToTypes) {
        // Deep copy for immutability
        Map<Local, Set<Type>> copy = new HashMap<>();
        for (Map.Entry<Local, Set<Type>> e : localToTypes.entrySet())
            copy.put(e.getKey(), new HashSet<>(e.getValue()));
        this.localToTypes = Collections.unmodifiableMap(copy);
    }

    /** Returns the seeded types for a callee-local, or empty set if none. */
    public Set<Type> getTypes(Local calleeLocal) {
        Set<Type> s = localToTypes.get(calleeLocal);
        return s != null ? s : Collections.emptySet();
    }

    public boolean isEmpty() { return localToTypes.isEmpty(); }

    /**
     * Merges two seeds by taking the pointwise union of type sets.
     * Used when the same callee is called from multiple call sites that
     * share the same call-string context.
     */
    public static MethodSeed merge(MethodSeed a, MethodSeed b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;

        Map<Local, Set<Type>> merged = new HashMap<>(a.localToTypes);
        for (Map.Entry<Local, Set<Type>> e : b.localToTypes.entrySet()) {
            merged.merge(e.getKey(), e.getValue(), (existing, incoming) -> {
                Set<Type> union = new HashSet<>(existing);
                union.addAll(incoming);
                return union;
            });
        }
        return new MethodSeed(merged);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MethodSeed)) return false;
        return localToTypes.equals(((MethodSeed) o).localToTypes);
    }

    @Override public int hashCode()  { return localToTypes.hashCode(); }
    @Override public String toString(){ return localToTypes.toString(); }
}