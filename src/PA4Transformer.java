import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.CallGraph;

public class PA4Transformer extends SceneTransformer {
    
    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        CallGraph cg = Scene.v().getCallGraph();
        System.out.println("[Main] Call graph edges (total): " + cg.size());

        VTAResolver vtaResolver = new VTAResolver(cg);
        vtaResolver.printCallGraph();

        MonomorphizationTransformer transformer = new MonomorphizationTransformer(vtaResolver);
        transformer.internalTransform(phaseName, options);
    }
}
