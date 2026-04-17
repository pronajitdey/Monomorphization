// L1 FAILS  : FastRunner and SlowRunner both visible globally
// L2 FAILS  : allocation is 3 levels deep from the call site
// L3 DETECTS: k=5 callstring covers main→level1→level2→level3→execute
//             in that context, always FastRunner → MONOMORPHIC
//
// Expected: r.run() inside execute() is MONOMORPHIC → FastRunner.run()

abstract class Runner {
    abstract void run();
}

class FastRunner extends Runner {
    public void run() { System.out.println("Fast!"); }
}

class SlowRunner extends Runner {
    public void run() { System.out.println("Slow!"); }
}

public class Test11 {

    static void execute(Runner r) {
        r.run();   // L3: in context main→lv1→lv2→lv3→execute, r = {FastRunner}
    }

    static void level3(Runner r) { execute(r); }
    static void level2(Runner r) { level3(r);  }
    static void level1(Runner r) { level2(r);  }

    public static void main(String[] args) {
        Runner fast = new FastRunner();  // allocation here
        level1(fast);                   // chain: 4 levels deep to execute()

        // SlowRunner exists to confuse L1 and L2
        Runner slow = new SlowRunner();
        slow.run();  // direct call, not through chain
    }
}

// Detected by L1