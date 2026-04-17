// L1 (VTA) FAILS  : sees both B and C ever stored into fields of type B globally
// L2 (Field-sens) : within main(), a.f is only assigned new B() → pt(x) = {B}
// L3 not needed
//
// Expected: x.foo() is MONOMORPHIC → B.foo()

class B {
    void foo() { System.out.println("B.foo"); }
}

class C extends B {
    void foo() { System.out.println("C.foo"); }
}

class Container {
    B f;
}

public class Test7 {
    public static void main(String[] args) {
        Container a = new Container();
        a.f = new B();       // only B assigned to a.f in this method

        // VTA sees C is also allocated somewhere in the program (below)
        // and stored into a field of type B — so L1 says {B, C}
        Container dummy = new Container();
        dummy.f = new C();   // this confuses VTA globally

        B x = a.f;           // L2 tracks a.f = {B} separately from dummy.f = {C}
        x.foo();             // L2: MONOMORPHIC → B.foo()
                             // L1: POLYMORPHIC (sees {B,C} for any field of type B)
    }
}