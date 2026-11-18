package fixture.controls;

final class JavaFlow {
    static int relay(int other, int value) {
        final int alias = value;
        return alias;
    }

    static int direct(int value) {
        return value;
    }

    static int constant(int value) {
        return 17;
    }

    static int bucket(int raw) {
        return raw / 4;
    }

    static int zero(int raw) {
        return raw / 0;
    }

    static long promoted(int raw) {
        return raw / 4L;
    }

    static int boxed(Integer raw) {
        return raw / 4;
    }

    static int branch(int value) {
        if (value > 0) return value;
        return 0;
    }
}
