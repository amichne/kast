package fixture.controls;
public class JavaOrigin {
    static int source() { return 1; }
    static int source(int fallback) { return fallback; }
    static native int missing(int value);
    static int other() { return 2; }
    static int relay(int value) { return value; }
    static String relay(String value) { return value; }
    static int sink(int value) { return 0; }
    static void host() {
        sink(relay(other()));
        sink(missing(source(10)));
        sink(relay(source()));
    }
}
