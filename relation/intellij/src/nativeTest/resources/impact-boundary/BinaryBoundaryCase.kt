package fixture.impactboundary

fun producer(): String = "tracked"

fun consume(value: Unit) = Unit

fun binaryArgument() {
    /*<binding>*/ val value = producer() /*</binding>*/
    val result = /*<external-result>*/ effects.exactlyOnce(/*<use>*/ value /*</use>*/) {} /*</external-result>*/
    consume(result)
}
