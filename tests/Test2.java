// Test2: Genuinely polymorphic call site — negative test.
// Both Dog2 and Cat2 flow into makeNoise(), so VTA sees two targets
// for a.speak() and correctly leaves the call as a virtualinvoke.
//
// Expected call graph output:
//   [CG] <Test2: void makeNoise(Animal2)>
//         stmt : virtualinvoke $r0.<Animal2: void speak()>()
//         kind : VIRTUAL → POLYMORPHIC (2 targets)
//         tgt  : <Dog2: void speak()>
//         tgt  : <Cat2: void speak()>
//
// Expected transformation: NONE (site remains virtual)
//
// Expected output: alternating Woof!/Meow!, then timing line

abstract class Animal2 { abstract void speak(); }

class Dog2 extends Animal2 {
    public void speak() { System.out.println("Woof!"); }
}

class Cat2 extends Animal2 {
    public void speak() { System.out.println("Meow!"); }
}

public class Test2 {
    static void makeNoise(Animal2 a) {
        a.speak(); // polymorphic: both Dog2 and Cat2 flow here
    }

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 500000; i++) {
            makeNoise(i % 2 == 0 ? new Dog2() : new Cat2());
        }
        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}