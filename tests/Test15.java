// SCENARIO: Single concrete type flows into a method through
// a deep call chain. Only ONE type (Diesel) ever enters
// process() anywhere in the whole program.
//
// WHY VTA DETECTS:
//   SPARK traces: main → wrap → process(Diesel)
//   No other call to process() exists anywhere.
//   edgesOutOf(engine.start()) = {Diesel.start()} → size 1 → MONOMORPHIC
//
// WHY INTRA PTA WOULD MISS (if VTA didn't catch it first):
//   Inside process(Engine e), parameter 'e' has no local allocation.
//   IntraPTA sees pt(e) = {} → cannot resolve.
//
// WHY INTER PTA WOULD ALSO CATCH IT (but VTA is faster/cheaper):
//   InterPTA propagates: main allocs Diesel → wrap(d) → process(e)
//   pt(e in process) = {Diesel} → MONOMORPHIC
//   But VTA already handles this — InterPTA not needed.
//
// EXPECTED: engine.start() → MONOMORPHIC → Diesel.start()
// OUTPUT:   "Diesel starting" x 1000000, then timing

abstract class Engine  { abstract void start(); }
class Diesel extends Engine {
    public void start() { System.out.println("Diesel starting"); }
}
class Electric extends Engine {
    public void start() { System.out.println("Electric starting"); }
}

public class Test15 {

    static void process(Engine engine) {
        engine.start();   // VTA: only Diesel ever reaches here → MONOMORPHIC
    }

    static void wrap(Engine e) {
        process(e);       // passes through unchanged
    }

    public static void main(String[] args) {
        long start = System.currentTimeMillis();

        Engine d = new Diesel();  // only Diesel created and passed to wrap
        for (int i = 0; i < 1_000_000; i++) {
            wrap(d);
        }

        // Electric exists but NEVER flows into wrap() or process()
        // It is only used directly — separate call site
        Electric e = new Electric();
        e.start();  // direct call, separate stmt — does not affect process()

        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}