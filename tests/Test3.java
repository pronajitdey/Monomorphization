class A {
    B f;
}

class B { void foo() {} }
class C extends B { void foo() {} }

public class Test3 {
    public static void main(String[] args) {
        A a1 = new A();   // S1
        A a2 = new A();   // S2
        
        a1.f = new B();   // S3
        a2.f = new C();   // S4
        
        B x = a1.f;
        x.foo();          // POLYMORPHIC: f -> {B, C}
    }
}