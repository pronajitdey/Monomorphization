// SCENARIO: Interface with single implementor used throughout
// the program. Only Wifi ever implements Connection.
// VTA's whole-program view immediately sees 1 edge everywhere.
//
// WHY VTA DETECTS:
//   edgesOutOf(conn.send()) = {Wifi.send()} everywhere → MONOMORPHIC
//   Because Wifi is the only class implementing Connection.
//
// WHY INTRA PTA WOULD MISS:
//   conn is a parameter in transfer() → pt(conn) = {} intraproc.
//
// WHY INTER PTA WOULD ALSO CATCH (but VTA is cheaper):
//   InterPTA propagates main's 'new Wifi()' → transfer(conn)
//   pt(conn) = {Wifi} → MONOMORPHIC
//
// EXPECTED: conn.send() → MONOMORPHIC → Wifi.send()

interface Connection { void send(String data); }

class Wifi implements Connection {
    public void send(String data) {
        System.out.println("Wifi: " + data);
    }
}

// Bluetooth exists in source but is NEVER instantiated
class Bluetooth implements Connection {
    public void send(String data) {
        System.out.println("BT: " + data);
    }
}

public class Test16 {

    static void transfer(Connection conn, String payload) {
        conn.send(payload);  // VTA: only Wifi ever flows here → MONOMORPHIC
    }

    public static void main(String[] args) {
        long start = System.currentTimeMillis();

        Connection c = new Wifi();  // only Wifi instantiated anywhere
        for (int i = 0; i < 1_000_000; i++) {
            transfer(c, "data");
        }

        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}