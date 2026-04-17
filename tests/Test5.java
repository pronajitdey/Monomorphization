// Test5: Field-sensitive monomorphic call site.
//
// The virtual call is through a FIELD, not a local directly:
//   $r1 = $r0.<Engine: Animal animal>   // field read
//   virtualinvoke $r1.<Animal: speak()>()
//
// VTA is field-INSENSITIVE: it sees that the field 'animal' has
// declared type Animal, and there exist writes of both Dog and Cat
// to Animal-typed fields somewhere in the program — so it reports
// 2 targets for the call site and does NOT devirtualize it.
//
// Our IntraPTA is field-SENSITIVE: inside run(), it tracks that
// $r0.animal was written with {Dog} in the constructor, so the
// field read gives $r1 = {Dog}, and the call is monomorphic.
//
// Expected Layer 1 (VTA) behaviour:     POLYMORPHIC (misses this)
// Expected Layer 2 (IntraPTA) behaviour: MONOMORPHIC (catches this)
//
// Expected output: Woof! (repeated), then timing

abstract class Animal5 { abstract void speak(); }
class Dog5 extends Animal5 { public void speak() { System.out.println("Woof!"); } }
class Cat5 extends Animal5 { public void speak() { System.out.println("Meow!"); } }

class Engine5 {
    Animal5 animal;   // field of declared type Animal5

    Engine5(Animal5 a) {
        this.animal = a;   // only Dog5 is ever written here
    }

    void run() {
        // In Jimple this becomes:
        //   $r1 = this.<Engine5: Animal5 animal>   ← field read
        //   virtualinvoke $r1.<Animal5: void speak()>()
        //
        // Field-sensitive IntraPTA tracks: fieldMap[(this, Engine5.animal)] = {Dog5}
        // So after the read: localMap[$r1] = {Dog5}
        // Call site: exactly 1 target → Dog5.speak() → monomorphic
        animal.speak();
    }
}

public class Test5 {
    public static void main(String[] args) {
        Engine5 e = new Engine5(new Dog5());
        long start = System.currentTimeMillis();
        for (int i = 0; i < 500000; i++) {
            e.run();
        }
        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}