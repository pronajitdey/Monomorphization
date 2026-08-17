import soot.*;
import soot.jimple.*;

import java.util.*;

/**
 * Debug printer for the PTA analyses.
 *
 * Prints three levels of detail, controlled by the DEBUG_LEVEL flag:
 *
 *   Level 0 — off (production mode)
 *
 *   Level 1 — summary only
 *     Per method/context: seed, how many stmts had facts, exit localMap
 *
 *   Level 2 — per-statement localMap + fieldMap
 *     For every statement in every (method, context), prints:
 *       STMT:  the Jimple statement text
 *       LOCAL: localMap entries  (variable → concrete types)
 *       FIELD: fieldMap entries  ((receiver.field) → concrete types)
 *
 *   Level 3 — level 2 + seed detail
 *     Also prints the full MethodSeed before running each method,
 *     showing exactly what the caller passed in for each parameter.
 *
 * Usage:
 *   Set DEBUG_LEVEL = 2 (or 3) before running the analysis.
 *   Output goes to System.out, prefixed with "[PTA]" for grep-ability.
 *
 *   To filter just one method:
 *     bash script.sh 2>&1 | grep -A 40 "METHOD: helper"
 *
 *   To see only field map entries:
 *     bash script.sh 2>&1 | grep "FIELD:"
 *
 *   To see where monomorphic sites are found:
 *     bash script.sh 2>&1 | grep "MONO\|POLY"
 */
public class PTADebugPrinter {

    // ── Change this to 0, 1, 2, or 3 ─────────────────────────────────────────
    public static int DEBUG_LEVEL = 2;
    // ─────────────────────────────────────────────────────────────────────────

    private static final String PREFIX = "[PTA] ";

    // ── Intraprocedural printer ───────────────────────────────────────────────

    /**
     * Prints the full per-statement state of a completed intraprocedural PTA.
     *
     * Called once per (method, context) after doAnalysis() finishes.
     * Shows the localMap and fieldMap before each Jimple statement.
     */
    public static void printIntraState(IntraProceduralPTA pta,
                                        Body body,
                                        String contextLabel,
                                        MethodSeed seed) {
        if (DEBUG_LEVEL < 2) return;

        System.out.println(PREFIX + "══════════════════════════════════════════");
        System.out.println(PREFIX + "METHOD: " + body.getMethod().getSignature());
        System.out.println(PREFIX + "CONTEXT: " + contextLabel);

        if (DEBUG_LEVEL >= 3) {
            printSeed(seed);
        }

        System.out.println(PREFIX + "── Per-statement facts ────────────────────");

        for (Unit u : body.getUnits()) {
            PointsToFlowSet fs = pta.getFlowBefore(u);

            System.out.println(PREFIX + "  STMT: " + u);
            printFlowSet(fs, "    ");
        }

        System.out.println(PREFIX + "══════════════════════════════════════════");
    }

    /**
     * Prints only the summary line for a method context.
     * Used at DEBUG_LEVEL = 1.
     */
    public static void printIntraSummary(Body body,
                                          String contextLabel,
                                          MethodSeed seed,
                                          PointsToFlowSet exitFacts) {
        if (DEBUG_LEVEL < 1) return;

        System.out.println(PREFIX + "── " + body.getMethod().getName()
            + " " + contextLabel);

        if (DEBUG_LEVEL >= 3) printSeed(seed);

        System.out.print(PREFIX + "   EXIT localMap: ");
        printLocalMapInline(exitFacts);
        System.out.println();

        if (!exitFacts.fieldMap.isEmpty()) {
            System.out.print(PREFIX + "   EXIT fieldMap: ");
            printFieldMapInline(exitFacts);
            System.out.println();
        }
    }

    // ── Interprocedural printer ───────────────────────────────────────────────

    /**
     * Prints the full state of the interprocedural analysis after fixed-point.
     *
     * Organises output by (method, context) pair, showing:
     *   - The seed that was used (what the caller passed in)
     *   - The per-statement facts (at DEBUG_LEVEL >= 2)
     *   - The exit facts
     */
    public static void printInterState(
            Map<MethodContext, MethodSeed>                 seeds,
            Map<MethodContext, Map<Unit, PointsToFlowSet>> stmtFacts,
            Map<MethodContext, PointsToFlowSet>            exitFacts) {

        if (DEBUG_LEVEL < 1) return;

        System.out.println("\n" + PREFIX
            + "╔══════════════════════════════════════════════════════╗");
        System.out.println(PREFIX
            + "║  INTERPROCEDURAL PTA STATE  (after fixed point)      ║");
        System.out.println(PREFIX
            + "╚══════════════════════════════════════════════════════╝");

        // Sort contexts for deterministic output: by method name then context
        List<MethodContext> sorted = new ArrayList<>(stmtFacts.keySet());
        sorted.sort(Comparator
            .comparing((MethodContext mc) -> mc.method.getDeclaringClass().getName())
            .thenComparing(mc -> mc.method.getName())
            .thenComparing(mc -> mc.ctx.toString()));

        for (MethodContext mc : sorted) {
            System.out.println(PREFIX
                + "┌─────────────────────────────────────────────────────");
            System.out.println(PREFIX
                + "│ METHOD : " + mc.method.getSignature());
            System.out.println(PREFIX
                + "│ CONTEXT: " + mc.ctx);

            // Print seed
            MethodSeed seed = seeds.getOrDefault(mc, MethodSeed.EMPTY);
            if (DEBUG_LEVEL >= 1) {
                System.out.println(PREFIX + "│ SEED   :");
                printSeedIndented(seed, "│   ");
            }

            // Print per-stmt facts
            if (DEBUG_LEVEL >= 2) {
                Map<Unit, PointsToFlowSet> facts = stmtFacts.get(mc);
                System.out.println(PREFIX + "│ FACTS  :");
                if (facts != null) {
                    for (Map.Entry<Unit, PointsToFlowSet> e : facts.entrySet()) {
                        System.out.println(PREFIX + "│   STMT : " + e.getKey());
                        printFlowSet(e.getValue(), "│     ");
                    }
                }
            }

            // Print exit facts
            PointsToFlowSet exit = exitFacts.get(mc);
            System.out.println(PREFIX + "│ EXIT   :");
            if (exit != null) {
                printFlowSet(exit, "│   ");
            } else {
                System.out.println(PREFIX + "│   (not yet computed)");
            }

            System.out.println(PREFIX + "└─────────────────────────────────────────────────────");
        }
    }

    /**
     * Prints a single call-site resolution result.
     * Call this from IntraPTAResolver and InterPTAResolver when they
     * resolve (or fail to resolve) a virtual call site.
     */
    public static void printResolution(Stmt stmt,
                                        SootMethod caller,
                                        SootMethod resolvedTarget,
                                        String resolverName) {
        if (DEBUG_LEVEL < 1) return;

        InvokeExpr ie = stmt.getInvokeExpr();
        String receiverType = "?";
        if (ie instanceof InstanceInvokeExpr) {
            receiverType = ((InstanceInvokeExpr) ie).getBase().getType().toString();
        }

        if (resolvedTarget != null) {
            System.out.println(PREFIX + "MONO [" + resolverName + "]"
                + "  caller=" + caller.getName()
                + "  site=" + ie.getMethodRef().getName()
                + "  receiver=" + receiverType
                + "  → " + resolvedTarget.getDeclaringClass().getShortName()
                + "." + resolvedTarget.getName());
        } else {
            if (DEBUG_LEVEL >= 2) {
                System.out.println(PREFIX + "POLY [" + resolverName + "]"
                    + "  caller=" + caller.getName()
                    + "  site=" + ie.getMethodRef().getName()
                    + "  receiver=" + receiverType);
            }
        }
    }

    // ── Low-level printers ────────────────────────────────────────────────────

    /**
     * Prints a PointsToFlowSet: localMap entries then fieldMap entries.
     * Only prints non-empty maps to reduce noise.
     */
    public static void printFlowSet(PointsToFlowSet fs, String indent) {
        if (fs == null) {
            System.out.println(PREFIX + indent + "(null)");
            return;
        }

        // ── localMap ─────────────────────────────────────────────────────────
        if (!fs.localMap.isEmpty()) {
            // Sort by local name for deterministic output
            List<Local> locals = new ArrayList<>(fs.localMap.keySet());
            locals.sort(Comparator.comparing(Local::getName));

            for (Local l : locals) {
                Set<Type> types = fs.localMap.get(l);
                System.out.println(PREFIX + indent
                    + "LOCAL  " + l.getName()
                    + " : " + l.getType()
                    + "  →  " + formatTypes(types));
            }
        }

        // ── fieldMap ─────────────────────────────────────────────────────────
        if (!fs.fieldMap.isEmpty()) {
            // Sort by "receiver.field" string
            List<PointsToFlowSet.FieldKey> keys = new ArrayList<>(fs.fieldMap.keySet());
            keys.sort(Comparator.comparing(PointsToFlowSet.FieldKey::toString));

            for (PointsToFlowSet.FieldKey key : keys) {
                Set<Type> types = fs.fieldMap.get(key);
                System.out.println(PREFIX + indent
                    + "FIELD  " + key.receiver.getName()
                    + "." + key.field.getName()
                    + " : " + key.field.getType()
                    + "  →  " + formatTypes(types));
            }
        }

        if (fs.localMap.isEmpty() && fs.fieldMap.isEmpty()) {
            System.out.println(PREFIX + indent + "(empty)");
        }
    }

    private static void printLocalMapInline(PointsToFlowSet fs) {
        if (fs == null || fs.localMap.isEmpty()) { System.out.print("{}"); return; }
        StringJoiner sj = new StringJoiner(", ", "{", "}");
        List<Local> locals = new ArrayList<>(fs.localMap.keySet());
        locals.sort(Comparator.comparing(Local::getName));
        for (Local l : locals)
            sj.add(l.getName() + "→" + formatTypes(fs.localMap.get(l)));
        System.out.print(sj);
    }

    private static void printFieldMapInline(PointsToFlowSet fs) {
        if (fs == null || fs.fieldMap.isEmpty()) { System.out.print("{}"); return; }
        StringJoiner sj = new StringJoiner(", ", "{", "}");
        for (Map.Entry<PointsToFlowSet.FieldKey, Set<Type>> e : fs.fieldMap.entrySet())
            sj.add(e.getKey() + "→" + formatTypes(e.getValue()));
        System.out.print(sj);
    }

    private static void printSeed(MethodSeed seed) {
        if (seed == null || seed.isEmpty()) {
            System.out.println(PREFIX + "  SEED: (empty — no caller info)");
            return;
        }
        System.out.println(PREFIX + "  SEED:");
        List<Local> locals = new ArrayList<>(seed.localToTypes.keySet());
        locals.sort(Comparator.comparing(Local::getName));
        for (Local l : locals) {
            System.out.println(PREFIX + "    "
                + l.getName() + " : " + l.getType()
                + "  →  " + formatTypes(seed.localToTypes.get(l)));
        }
    }

    private static void printSeedIndented(MethodSeed seed, String indent) {
        if (seed == null || seed.isEmpty()) {
            System.out.println(PREFIX + indent + "(empty)");
            return;
        }
        List<Local> locals = new ArrayList<>(seed.localToTypes.keySet());
        locals.sort(Comparator.comparing(Local::getName));
        for (Local l : locals) {
            System.out.println(PREFIX + indent
                + l.getName() + " : " + l.getType()
                + "  →  " + formatTypes(seed.localToTypes.get(l)));
        }
    }

    /**
     * Formats a set of types as a compact string.
     * e.g.  {Dog, Cat}  or  {Dog}  or  {}
     */
    private static String formatTypes(Set<Type> types) {
        if (types == null || types.isEmpty()) return "{}";
        List<String> names = new ArrayList<>();
        for (Type t : types) names.add(shortTypeName(t));
        Collections.sort(names);
        return "{" + String.join(", ", names) + "}";
    }

    /** Strips the package prefix for readability: "com.example.Dog" → "Dog" */
    private static String shortTypeName(Type t) {
        String s = t.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}