// WHY L1 (SPARK/VTA) FAILS:
//   execute() is called from TWO different call sites in main():
//     execute(new FastRunner())   → one call site
//     execute(new SlowRunner())   → another call site
//   VTA merges both call sites — edgesOutOf(r.run()) = {FastRunner, SlowRunner}
//   VTA is CALL-SITE INSENSITIVE — it cannot distinguish the two calls.
//
// WHY L2 (Field-sens Intra) FAILS:
//   Inside execute(), parameter 'r' has no local allocation.
//   Intraprocedural PTA sees pt(r) = {} — cannot look into callers.
//
// WHY L3 (Interprocedural k=5) DETECTS:
//   With callstring context, the two call sites get DIFFERENT contexts:
//     Context [main:callsite1] → execute() → r = {FastRunner} → MONOMORPHIC
//     Context [main:callsite2] → execute() → r = {SlowRunner} → MONOMORPHIC
//   Each context has exactly 1 target → both are devirtualized separately.
//
// Runtime output: "Fast!" then "Slow!"

abstract class Runner { abstract void run(); }

class FastRunner extends Runner {
    public void run() { System.out.println("Fast!"); }
}
class SlowRunner extends Runner {
    public void run() { System.out.println("Slow!"); }
}

public class Test13 {

    static void execute(Runner r) {
        r.run();  // L1: {FastRunner, SlowRunner} — merged across both call sites
                  // L2: pt(r) = {} — no local allocation
                  // L3: context [callsite1] → {FastRunner}, [callsite2] → {SlowRunner}
    }

    public static void main(String[] args) {
        execute(new FastRunner());  // call site 1
        execute(new SlowRunner());  // call site 2 — this is what kills L1
    }
}