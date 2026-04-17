import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.CallGraph;

public class PA4Transformer extends SceneTransformer {
    
    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        CallGraph cg = Scene.v().getCallGraph();
        System.out.println("[Main] Call graph edges (total): " + cg.size());

        // ── Layer 1: VTA call graph (already built by SPARK) ──────
        VTAResolver vta = new VTAResolver(cg);
        vta.printCallGraph();

        // ── Layer 2: IntraPTA (field-sensitive, per-method) ───────
        IntraPTAResolver intra = new IntraPTAResolver();

        // ── Layer 3: InterPTA (k-callstring, K=" + CallString.K) ──
        System.out.println("[Main] Running InterProceduralPTA (K="
            + CallString.K + ")...");
        InterProceduralPTA interPTA = new InterProceduralPTA();
        InterPTAResolver   inter    = new InterPTAResolver(interPTA);

        // ── Chain: VTA → IntraPTA → InterPTA ─────────────────────
        ChainedResolver chain = new ChainedResolver(vta, intra, inter);

        // ── Transform ─────────────────────────────────────────────
        MonomorphizationTransformer transformer =
            new MonomorphizationTransformer(chain);
        transformer.internalTransform(phaseName, options);

        // Per-layer breakdown for the report
        chain.printStats();
    }
}
