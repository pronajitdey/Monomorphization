// SCENARIO: Factory method that always returns one concrete type.
// The allocation is ONE method call away from the call site.
// VTA: both types are visible globally → fails.
// IntraPTA: return value from method call → pt = {} → fails.
// InterPTA k=5: propagates return value of factory → detects.
//
// WHY VTA FAILS:
//   Both GpuRenderer and CpuRenderer are allocated in the program.
//   GpuRenderer flows into renderAll via createRenderer().
//   CpuRenderer also allocated (in createFallback()) — even if never
//   passed to renderAll, VTA's global analysis may conflate them.
//   More precisely: createRenderer returns GpuRenderer, but
//   createFallback returns CpuRenderer. Both exist globally.
//   VTA: pt(renderer param of renderAll) = {GpuRenderer, CpuRenderer}
//   → POLYMORPHIC.
//
// WHY INTRA PTA FAILS:
//   Inside renderAll(Renderer r), 'r' is a parameter.
//   IntraPTA: pt(r) = {} — no local allocation → cannot resolve.
//
// WHY INTER PTA k=5 DETECTS:
//   InterPTA propagates cross-method:
//     createRenderer() returns new GpuRenderer()
//       → pt(return value) = {GpuRenderer}
//     main() calls: Renderer r = createRenderer()
//       → pt(r in main) = {GpuRenderer}
//     main() calls: renderAll(r)
//       → pt(r in renderAll) = {GpuRenderer}
//     renderAll(): r.draw() → pt(r) = {GpuRenderer} → MONOMORPHIC ✓
//
//   Call chain depth = 2 (main→renderAll), well within k=5.
//
// EXPECTED: r.draw() inside renderAll → MONOMORPHIC via L3

abstract class Renderer { abstract void draw(int frame); }

class GpuRenderer extends Renderer {
    public void draw(int frame) {
        int result = frame * frame + frame; // simulate GPU work
    }
}

class CpuRenderer extends Renderer {
    public void draw(int frame) {
        int result = frame + frame;          // simulate CPU work
    }
}

public class Test20 {

    static Renderer createRenderer() {
        return new GpuRenderer();   // always GpuRenderer
    }

    static Renderer createFallback() {
        return new CpuRenderer();   // confuses VTA — CpuRenderer exists
    }

    static void renderAll(Renderer r, int frames) {
        for (int i = 0; i < frames; i++) {
            r.draw(i);   // L1: {GpuRenderer,CpuRenderer} → POLY
                          // L2: pt(r)={} parameter → POLY
                          // L3: propagates createRenderer→main→renderAll
                          //     pt(r)={GpuRenderer} → MONO ✓
        }
    }

    public static void main(String[] args) {
        long start = System.currentTimeMillis();

        Renderer r = createRenderer();   // always GpuRenderer
        renderAll(r, 1_000_000);

        // createFallback exists but its result never flows into renderAll
        // It is only called here directly to confuse VTA
        Renderer fallback = createFallback();
        fallback.draw(0);  // direct call on CpuRenderer — separate call site

        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}