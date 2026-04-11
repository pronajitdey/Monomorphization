// Test1: Basic monomorphic virtual call site.
// makeNoise() is always called with a Dog1 — VTA sees that only Dog1
// is ever assigned to variables of type Animal1 in this program,
// so the call site a.speak() has exactly one target: Dog1.speak().
//
// Expected call graph output:
//   [CG] <Test1: void makeNoise(Animal1)>
//         stmt : virtualinvoke $r0.<Animal1: void speak()>()
//         kind : VIRTUAL → MONOMORPHIC
//         tgt  : <Dog1: void speak()>
//
// Expected transformation:
//   virtualinvoke $r0.<Animal1: void speak()>()
//   →  staticinvoke <Dog1: void speak$mono(Animal1)>($r0)
//
// Expected output: Woof! (repeated), then timing line

abstract class Animal1 { abstract void speak(); }

class Dog1 extends Animal1 {
    public void speak() { System.out.println("Woof!"); }
}

class Cat1 extends Animal1 {
    public void speak() { System.out.println("Meow!"); }
}

public class Test1 {
    static void makeNoise(Animal1 a) {
        a.speak(); // <-- the call site under analysis
    }

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000000; i++) {
            makeNoise(new Dog1()); // only Dog1 ever passed here
        }
        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}