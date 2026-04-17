// Test6: Polymorphic via array element access.
// VTA sees that array elements can be multiple types, so a.speak() has 2 targets.

abstract class Animal6 { abstract void speak(); }

class Dog6 extends Animal6 {
    public void speak() { System.out.println("Woof!"); }
}

class Cat6 extends Animal6 {
    public void speak() { System.out.println("Meow!"); }
}

public class Test6 {
    static void makeNoise(Animal6[] arr, int i) {
        Animal6 a = arr[i];  // a can be Dog6 or Cat6
        a.speak();            // polymorphic
    }

    public static void main(String[] args) {
        Animal6[] arr = {new Dog6(), new Cat6()};
        long start = System.currentTimeMillis();
        for (int i = 0; i < 600000; i++) {
            makeNoise(arr, i % 2);
        }
        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}