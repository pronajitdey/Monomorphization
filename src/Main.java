import soot.*;
import soot.options.Options;
import java.util.*;

public class Main {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: Main <output_dir> <classpath> <main_class>");
            System.exit(1);
        }
 
        String outputDir = args[0];
        String classpath = args[1];
        String mainClass = args[2];
 
        configureSoot(outputDir, classpath, mainClass);

        SceneTransformer sceneTransformer = new PA4Transformer();
        // Register in the wjtp pack — fires AFTER the call graph is fully built.
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.mono", sceneTransformer));
 
        PackManager.v().runPacks();
        PackManager.v().writeOutput();
    }

    private static void configureSoot(String outputDir, String classpath,
                                       String mainClass) {
        Options.v().set_whole_program(true);
        Options.v().set_app(true);
        Options.v().set_output_format(Options.output_format_class);
        Options.v().set_output_dir(outputDir);
        Options.v().set_soot_classpath(classpath);
        Options.v().set_prepend_classpath(true);
        Options.v().set_process_dir(Collections.singletonList(classpath));

        Options.v().set_no_bodies_for_excluded(true);
        Options.v().set_exclude(List.of("java.*", "javax.*", "sun.*", "com.sun.*", "jdk.*"));
 
        // SPARK/VTA for the call graph
        Options.v().setPhaseOption("cg.spark", "on");
        Options.v().setPhaseOption("cg.spark", "vta:true");
 
        Options.v().set_keep_line_number(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_no_bodies_for_excluded(true);
 
        Scene.v().loadClassAndSupport(mainClass);
        Scene.v().setMainClass(Scene.v().getSootClass(mainClass));
        Scene.v().loadNecessaryClasses();
    }
}
