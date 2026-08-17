import soot.Local;
import soot.SootField;
import soot.Type;

import java.util.*;

/**
 * Flow abstraction for the field-sensitive intraprocedural PTA.
 *
 * Tracks two kinds of points-to information at each program point:
 *
 * 1. localMap:  Local → Set<Type>
 *    What concrete types may a local variable point to.
 *    e.g.  $r0 → {Dog}
 *
 * 2. fieldMap:  (Local, SootField) → Set<Type>
 *    What concrete types may be stored in a specific field of a
 *    specific receiver local.
 *    e.g.  ($r0, Engine.animal) → {Dog}
 *
 * Field sensitivity:
 * ──────────────────────────────────────────────────────────────────
 * A field-insensitive analysis treats ALL writes to any field of
 * type Animal as one merged set — so if anywhere in the method you
 * write `x.animal = new Dog()` AND `y.animal = new Cat()`, then a
 * later read `z = w.animal` sees {Dog, Cat} regardless of which
 * object w is.
 *
 * A field-sensitive analysis distinguishes (x, animal) from
 * (y, animal). If x and y are different locals, their animal fields
 * are tracked separately. A read `z = w.animal` only picks up types
 * from (w, animal), not from every animal field write in the method.
 *
 * This is "local field sensitivity" — we are sensitive to which
 * receiver LOCAL the field belongs to, not to which heap object
 * (that would require heap sensitivity, which is more expensive).
 *
 * Limitation:
 *   If two locals alias (both point to the same object), writes
 *   through one are NOT automatically reflected in the other.
 *   We handle only the most common non-aliasing case. At join
 *   points we take the union, which is conservative (sound).
 *
 * equals() and hashCode() are implemented correctly so Soot's
 * fixed-point engine can detect convergence.
 */
public class PointsToFlowSet {

    // ── Local map: local variable → set of concrete types ────────────────────
    // package-private so PTADebugPrinter can iterate them for display
    final Map<Local, Set<Type>> localMap;
 
    // ── Field map: (receiver local, field) → set of concrete types ───────────
    // Key is a FieldKey record: (Local, SootField) pair.
    // package-private for the same reason
    final Map<FieldKey, Set<Type>> fieldMap;

    // ─────────────────────────────────────────────────────────────────────────
    // FieldKey: identifies a specific field on a specific receiver local
    // ─────────────────────────────────────────────────────────────────────────

    static class FieldKey {
        final Local     receiver;
        final SootField field;

        FieldKey(Local receiver, SootField field) {
            this.receiver = receiver;
            this.field    = field;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FieldKey)) return false;
            FieldKey k = (FieldKey) o;
            return receiver.equals(k.receiver) && field.equals(k.field);
        }

        @Override
        public int hashCode() {
            return 31 * receiver.hashCode() + field.hashCode();
        }

        @Override
        public String toString() {
            return receiver.getName() + "." + field.getName();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────────

    public PointsToFlowSet() {
        this.localMap = new HashMap<>();
        this.fieldMap = new HashMap<>();
    }

    /** Deep-copy constructor — required so nodes don't alias each other. */
    public PointsToFlowSet(PointsToFlowSet other) {
        this.localMap = new HashMap<>();
        for (Map.Entry<Local, Set<Type>> e : other.localMap.entrySet())
            this.localMap.put(e.getKey(), new HashSet<>(e.getValue()));

        this.fieldMap = new HashMap<>();
        for (Map.Entry<FieldKey, Set<Type>> e : other.fieldMap.entrySet())
            this.fieldMap.put(e.getKey(), new HashSet<>(e.getValue()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local map operations
    // ─────────────────────────────────────────────────────────────────────────

    /** x = new T()  →  localMap[x] = {T}  (strong update, kills old info) */
    public void assignLocal(Local x, Type t) {
        Set<Type> s = new HashSet<>();
        s.add(t);
        localMap.put(x, s);
    }

    /** x = {T1, T2, ...}  →  localMap[x] = that full set (strong update). */
    public void assignLocalTypes(Local x, Set<Type> types) {
        if (types == null || types.isEmpty()) {
            localMap.remove(x);
            return;
        }
        localMap.put(x, new HashSet<>(types));
    }

    /** x = y  →  localMap[x] = copy of localMap[y]  (strong update) */
    public void copyLocal(Local x, Local y) {
        Set<Type> src = localMap.get(y);
        if (src != null && !src.isEmpty())
            localMap.put(x, new HashSet<>(src));
        else
            localMap.remove(x);
    }

    /** x = @this / @paramN  →  localMap[x] = {T} */
    public void assignIdentity(Local x, Type t) {
        assignLocal(x, t);
    }

    /** Returns the unique type for x if singleton, else null. */
    public Type getUniqueLocalType(Local x) {
        Set<Type> s = localMap.get(x);
        return (s != null && s.size() == 1) ? s.iterator().next() : null;
    }

    public Set<Type> getLocalTypes(Local x) {
        Set<Type> s = localMap.get(x);
        return s != null ? Collections.unmodifiableSet(s) : Collections.emptySet();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Field map operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * a.f = b  (field write, strong update on the (a,f) slot)
     *
     * We do a STRONG update: we replace the previous types for (a,f) with
     * whatever types b currently has. This is precise when a is the only
     * local that points to this object — the common case inside a method.
     *
     * At join points (merge) we take the union, which is conservative.
     */
    public void writeField(Local a, SootField f, Local b) {
        Set<Type> srcTypes = localMap.get(b);
        if (srcTypes == null || srcTypes.isEmpty()) return;
        fieldMap.put(new FieldKey(a, f), new HashSet<>(srcTypes));
    }

    /**
     * a.f = new T()  (field write with a new expression directly)
     * Convenience variant used when the RHS is a NewExpr inline.
     */
    public void writeFieldDirect(Local a, SootField f, Type t) {
        Set<Type> s = new HashSet<>();
        s.add(t);
        fieldMap.put(new FieldKey(a, f), s);
    }

    /**
     * x = a.f  (field read)
     *
     * x gets the types stored in (a, f).
     * If we have no information about (a, f), x's type set is cleared
     * (unknown) — conservative.
     */
    public void readField(Local x, Local a, SootField f) {
        Set<Type> fieldTypes = fieldMap.get(new FieldKey(a, f));
        if (fieldTypes != null && !fieldTypes.isEmpty())
            localMap.put(x, new HashSet<>(fieldTypes));
        else
            localMap.remove(x); // unknown after this read
    }

    /** Returns the unique type stored in field (a,f), or null if unknown/multiple. */
    public Type getUniqueFieldType(Local a, SootField f) {
        Set<Type> s = fieldMap.get(new FieldKey(a, f));
        return (s != null && s.size() == 1) ? s.iterator().next() : null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lattice operations  (used by ForwardFlowAnalysis infrastructure)
    // ─────────────────────────────────────────────────────────────────────────

    /** Clears all information (bottom of lattice). */
    public void clear() {
        localMap.clear();
        fieldMap.clear();
    }

    /**
     * Union this with other (in-place).
     * Used at CFG join points and in the copy() helper.
     * May-analysis: a type is possible if it appears on ANY incoming path.
     */
    public void mergeWith(PointsToFlowSet other) {
        // Union local maps
        for (Map.Entry<Local, Set<Type>> e : other.localMap.entrySet())
            localMap.computeIfAbsent(e.getKey(), k -> new HashSet<>())
                    .addAll(e.getValue());

        // Union field maps
        for (Map.Entry<FieldKey, Set<Type>> e : other.fieldMap.entrySet())
            fieldMap.computeIfAbsent(e.getKey(), k -> new HashSet<>())
                    .addAll(e.getValue());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // equals / hashCode — MUST be correct for Soot fixed-point detection
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PointsToFlowSet)) return false;
        PointsToFlowSet other = (PointsToFlowSet) o;
        return localMap.equals(other.localMap)
            && fieldMap.equals(other.fieldMap);
    }

    @Override
    public int hashCode() {
        return 31 * localMap.hashCode() + fieldMap.hashCode();
    }

    @Override
    public String toString() {
        return "locals=" + localMap + " fields=" + fieldMap;
    }
}
