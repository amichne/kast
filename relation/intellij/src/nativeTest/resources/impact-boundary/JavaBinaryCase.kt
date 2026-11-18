package fixture.controls

fun producer(): Int = 42

fun consume(value: Int) = Unit

fun binary() {
    consume(/*<tracked>*/ JavaBinary.relay(producer()) /*</tracked>*/)
}
