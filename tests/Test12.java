// WHY L1 (SPARK/VTA) FAILS:
//   Objects flow through a Map<String, Animal> — a collection with
//   type-erased keys. SPARK cannot model which String key maps to which
//   concrete type. edgesOutOf(a.speak()) sees {Dog, Cat}.
//
// WHY L2 (Field-sens Intra) FAILS:
//   The map.get() result is loaded from a heap object (the HashMap).
//   Intraprocedural PTA has no model for HashMap internals.
//   pt(a) = {} after map.get() — unresolvable.
//
// WHY L3 (Interprocedural k=5) FAILS:
//   Even with cross-method tracking, the concrete type is lost inside
//   HashMap.put()/get() which are JDK methods with no analyzable body.
//   The callstring context cannot recover the type through the JDK wall.
//
// Runtime output: "Woof" — but NO layer can prove it statically.

import java.util.HashMap;

abstract class Animal {
    abstract void speak();
}
class Dog extends Animal {
    public void speak() { System.out.println("Woof"); }
}
class Cat extends Animal {
    public void speak() { System.out.println("Meow"); }
}

public class Test12 {
    public static void main(String[] args) {
        HashMap<String, Animal> zoo = new HashMap<>();
        zoo.put("dog", new Dog());
        zoo.put("cat", new Cat());

        // Both Dog and Cat are stored — no layer can distinguish by key
        Animal a = zoo.get("dog");  // always Dog at runtime
        a.speak();                  // ALL LAYERS FAIL: cannot track through HashMap
    }
}