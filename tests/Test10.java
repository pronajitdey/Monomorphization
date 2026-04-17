// L1 FAILS  : VTA sees both Worker and Manager stored into Employee.worker globally
// L2 DETECTS: within main(), emp.worker = new Worker() only → pt = {Worker}
//
// Expected: emp.worker.doWork() is MONOMORPHIC → Worker.doWork()

class Worker {
    void doWork() { System.out.println("Worker working"); }
}

class Manager extends Worker {
    void doWork() { System.out.println("Manager managing"); }
}

class Employee {
    Worker worker;
}

public class Test10 {

    static void confuseVTA(Employee e) {
        e.worker = new Manager();  // makes VTA think Manager flows into worker field
    }

    public static void main(String[] args) {
        Employee emp = new Employee();
        emp.worker = new Worker();   // L2: emp.worker = {Worker} locally

        // VTA sees confuseVTA stores Manager into worker field → L1 says {Worker, Manager}
        Employee dummy = new Employee();
        confuseVTA(dummy);

        emp.worker.doWork();  // L2: pt(emp.worker) = {Worker} → MONOMORPHIC
                              // L1: pt = {Worker, Manager} → POLYMORPHIC
    }
}