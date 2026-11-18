package fixture.controls

fun producer(): Int = 42

fun consume(value: Int) = Unit

fun aliased() {
    consume(/*<tracked>*/ JavaFlow.relay(0, producer()) /*</tracked>*/)
    consume(/*<other>*/ JavaFlow.relay(0, 17) /*</other>*/)
}

fun direct() {
    consume(/*<tracked>*/ JavaFlow.direct(producer()) /*</tracked>*/)
}

fun constant() {
    consume(/*<tracked>*/ JavaFlow.constant(producer()) /*</tracked>*/)
}

fun sibling() {
    val value = producer()
    consume(/*<tracked>*/ JavaFlow.relay(0, value) /*</tracked>*/)
    consume(/*<other>*/ JavaFlow.branch(value) /*</other>*/)
}
