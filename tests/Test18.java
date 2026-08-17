// SCENARIO: Field written once with concrete type and read back
// in the SAME method. Both VTA and InterPTA are field-insensitive.
// IntraPTA's field-sensitivity tracks the write-then-read.
//
// WHY VTA FAILS:
//   Field-insensitive: VTA cannot track what concrete type is stored
//   in Holder.worker. It sees both Worker and Manager stored in fields
//   of type Task anywhere in the program → pt(task) = {Worker, Manager}
//   → POLYMORPHIC.
//
// WHY INTRA PTA DETECTS:
//   Inside doWork(), field-sensitive analysis tracks:
//     fieldWriteMap[(h, task)] = {Worker}  ← only Worker written here
//     h.task read → pt(t) = {Worker}
//     t.execute() → MONOMORPHIC → Worker.execute()
//
// WHY INTER PTA FAILS:
//   InterPTA propagates across method boundaries but is still
//   field-insensitive — it cannot distinguish h.task from
//   other.task written in other methods.
//
// EXPECTED: t.execute() → MONOMORPHIC via L2 field-sensitivity

abstract class Task { abstract void execute(); }

class Worker extends Task {
    public void execute() { System.out.println("Worker"); }
}

class Manager extends Task {
    public void execute() { System.out.println("Manager"); }
}

class Holder {
    Task task;
}

public class Test18 {

    static void confuseVTA(Holder h) {
        h.task = new Manager();  // Manager stored in some Holder.task
    }                             // VTA sees Manager → field of type Task

    static void doWork() {
        Holder h = new Holder();
        h.task = new Worker();    // fieldWriteMap[(h,task)] = {Worker}

        // VTA: sees Manager also stored in Holder.task (from confuseVTA)
        //      → pt(task field) = {Worker, Manager} → POLYMORPHIC
        // L2:  h.task only written with Worker in THIS method
        //      → pt(t) = {Worker} → MONOMORPHIC ✓

        Task t = h.task;          // field read in same method
        t.execute();              // L2 DETECTS: MONOMORPHIC → Worker.execute()

        // confuseVTA is called on a DIFFERENT holder
        Holder other = new Holder();
        confuseVTA(other);        // this is what tricks VTA
    }

    public static void main(String[] args) {
        long start = System.currentTimeMillis();

        for (int i = 0; i < 2_000_000; i++) {
            doWork();
        }

        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}