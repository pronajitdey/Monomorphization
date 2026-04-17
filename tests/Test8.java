// L1 (VTA)         FAILS : Dog and Cat both flow into Animal variables globally
// L2 (Field-sens)  FAILS : allocation of Dog is in createAnimal(), not in process()
//                           intraprocedural cannot see across method boundary
// L3 (Inter k=5)   DETECTS: tracks createAnimal() → always returns Dog
//                            process() always called with Dog → MONOMORPHIC
//
// Expected: a.speak() inside process() is MONOMORPHIC → Dog.speak()

abstract class Animal {
    abstract void speak();
}

class Dog extends Animal {
    public void speak() { System.out.println("Woof"); }
}

class Cat extends Animal {
    public void speak() { System.out.println("Meow"); }
}

public class Test8 {

    // Always returns Dog — but L1 and L2 cannot see this
    static Animal createAnimal() {
        return new Dog();
    }

    // L2 sees parameter 'a' with no local allocation → pt(a) = {} → fails
    static void process(Animal a) {
        a.speak();   // L3 context: called only from main with Dog → MONOMORPHIC
    }

    public static void main(String[] args) {
        Animal a = createAnimal();  // L3 knows this is always Dog
        process(a);                 // L3 tracks context: process() called with {Dog}

        // This line causes L1 to see Cat in the program — confusing VTA
        Cat dummy = new Cat();
        dummy.speak();  // direct call, not through Animal variable
    }
}

// detected by L1