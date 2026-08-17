// SCENARIO: Two call sites to process() in the SAME method,
// each passing a DIFFERENT concrete type. VTA merges both
// types at process()'s parameter. IntraPTA tracks each
// call site separately via flow-sensitivity.
//
// WHY VTA FAILS:
//   process() is called from main() with BOTH FastCalc and SlowCalc.
//   SPARK: edgesOutOf(calc.compute()) = {FastCalc, SlowCalc} → size 2
//   VTA says POLYMORPHIC — cannot devirtualize.
//
// WHY INTRA PTA DETECTS:
//   The virtual call calc.compute() is INSIDE main(), not inside process().
//   After inlining process() conceptually:
//
//   In main(), the if/else branches:
//     if (i%2==0): a = new FastCalc() → a.compute()
//                  pt(a) at call = {FastCalc} → MONOMORPHIC ✓
//     else:        a = new SlowCalc() → a.compute()
//                  pt(a) at call = {SlowCalc} → MONOMORPHIC ✓
//
//   WAIT — but the call is inside process(), not main().
//   So IntraPTA runs on process(). Inside process(calc):
//     pt(calc) = {} — parameter, no local alloc → IntraPTA fails too!
//
//   KEY INSIGHT: We need the call site to be DIRECTLY in main(),
//   not hidden inside process(). So we restructure:
//   The virtual call a.compute() is IN main() directly.
//   Two separate call sites, each with one type.
//
// WHY INTRA PTA DETECTS (correctly structured):
//   Flow-sensitive analysis on main():
//   if-branch:   pt(a) = {FastCalc} → a.compute() → MONOMORPHIC ✓
//   else-branch: pt(a) = {SlowCalc} → a.compute() → MONOMORPHIC ✓
//
// WHY VTA FAILS (correctly structured):
//   VTA: pt(a globally in main) = {FastCalc, SlowCalc}
//   Flow-insensitive → merges both → POLYMORPHIC at both sites
//
// EXPECTED: Both a.compute() calls → MONOMORPHIC via L2

abstract class Calc { abstract int compute(int x); }

class FastCalc extends Calc {
    public int compute(int x) { return x * 2; }
}

class SlowCalc extends Calc {
    public int compute(int x) { return x * 3; }
}

public class Test17 {

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        long sum = 0;

        for (int i = 0; i < 20000000; i++) {
            if (i % 2 == 0) {
                Calc a = new FastCalc();     // pt(a) = {FastCalc} here
                sum += a.compute(i);         // L2: MONOMORPHIC → FastCalc
                                             // L1: sees {FastCalc,SlowCalc} → POLY
            } else {
                Calc a = new SlowCalc();     // pt(a) = {SlowCalc} here
                sum += a.compute(i);         // L2: MONOMORPHIC → SlowCalc
                                             // L1: sees {FastCalc,SlowCalc} → POLY
            }
        }

        System.out.println("Sum : " + sum);
        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}