// L1 FAILS : both Dog and Cat stored in Animal-typed structures globally
// L2 FAILS : allocation is in a separate factory, not local to caller
// L3 FAILS : objects flow through ArrayList — callstring cannot distinguish
//            which element is Dog vs Cat inside the list at position 0
//
// Expected: a.speak() is POLYMORPHIC — no layer can prove monomorphism
// Runtime output: "Woof" (but no layer can statically verify this)

import java.util.ArrayList;

abstract class Animal {
    abstract void speak();
}

class Dog extends Animal {
    public void speak() { System.out.println("Woof"); }
}

class Cat extends Animal {
    public void speak() { System.out.println("Meow"); }
}

public class Test9 {

    static ArrayList<Animal> buildList(boolean useDog) {
        ArrayList<Animal> list = new ArrayList<>();
        if (useDog)
            list.add(new Dog());   // sometimes Dog
        else
            list.add(new Cat());   // sometimes Cat
        return list;
    }

    static void runFirst(ArrayList<Animal> list) {
        Animal a = list.get(0);  // L3: loses type through ArrayList.get()
        a.speak();               // ALL LAYERS FAIL: {Dog, Cat} or unknown
    }

    public static void main(String[] args) {
        // L3 callstring: main → buildList → but ArrayList.get() destroys type info
        ArrayList<Animal> list = buildList(true);
        runFirst(list);   // runtime: always Dog, but no layer can prove it
    }
}