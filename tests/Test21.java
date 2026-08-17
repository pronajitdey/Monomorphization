abstract class Animal2 { abstract void speak(); }

class Dog2 extends Animal2 {
    public void speak() { System.out.println("Woof!"); }
}

class Cat2 extends Animal2 {
    public void speak() { System.out.println("Meow!"); }
}

public class Test21 {
    static Animal2 makeNoise(Animal2 a) {
        // a.speak(); // polymorphic: both Dog2 and Cat2 flow here
        return a;
    }

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 20000000; i++) {
            Animal2 x = makeNoise(new Dog2());
            Animal2 y = x;
            // Animal2 y = makeNoise(new Cat2());
            x.speak();
            y.speak();
        }
        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}