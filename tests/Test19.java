// SCENARIO: The SAME method execute() is called from TWO different
// call sites — one passes FastJob, one passes SlowJob.
// VTA merges both → POLYMORPHIC.
// IntraPTA: allocation is in different method → pt(job) = {} → fails.
// InterPTA k=5: separate contexts for each call site → each is MONOMORPHIC.
//
// WHY VTA FAILS:
//   execute() is called from main() with BOTH FastJob and SlowJob.
//   edgesOutOf(job.run() in execute) = {FastJob.run, SlowJob.run}
//   → POLYMORPHIC — VTA cannot separate the two call sites.
//
// WHY INTRA PTA FAILS:
//   Inside execute(Job job), parameter 'job' has no local allocation.
//   IntraPTA: pt(job) = {} → cannot resolve → fails.
//
// WHY INTER PTA k=5 DETECTS:
//   Context for callsite1: main→execute[callsite1] → pt(job) = {FastJob}
//     → job.run() MONOMORPHIC → FastJob.run() ✓
//   Context for callsite2: main→execute[callsite2] → pt(job) = {SlowJob}
//     → job.run() MONOMORPHIC → SlowJob.run() ✓
//
//   k=5 is more than enough — depth is only 1 call deep.
//   Each context has exactly 1 type → both devirtualized.
//
// EXPECTED: job.run() resolved TWICE via L3:
//   context[callsite1] → FastJob.run()
//   context[callsite2] → SlowJob.run()

abstract class Job { abstract int run(int x); }

class FastJob extends Job {
    public int run(int x) { return x * 2 + 1; }
}

class SlowJob extends Job {
    public int run(int x) { return x * 3 + 1; }
}

public class Test19 {

    static int execute(Job job, int x) {
        return job.run(x);   // L1: {FastJob,SlowJob} → POLY
                              // L2: pt(job)={} intraproc → POLY
                              // L3: context separates → MONO ✓
    }

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        long sum = 0;

        FastJob fast = new FastJob();
        SlowJob slow = new SlowJob();

        for (int i = 0; i < 2_000_000; i++) {
            sum += execute(fast, i);   // callsite1: always FastJob
            sum += execute(slow, i);   // callsite2: always SlowJob
        }

        System.out.println("Sum : " + sum);
        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}