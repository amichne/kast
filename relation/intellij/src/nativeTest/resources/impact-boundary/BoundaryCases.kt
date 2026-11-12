package fixture.impactboundary

fun producer(): String = "tracked"

fun consume(value: String) = Unit

fun relay(value: String): String {
    val returned = value
    return returned
}

fun constant(ignored: String): String = "other"

class Box(val tracked: String, val other: String)

fun bindingOnly() {
    /*<binding>*/ val value = producer() /*</binding>*/
    /*<use>*/ value /*</use>*/
}

fun directReturn(): String = /*<returned>*/ producer() /*</returned>*/

fun localReturn(): String {
    /*<binding>*/ val value = producer() /*</binding>*/
    return /*<use>*/ value /*</use>*/
}

fun localArgument() {
    /*<binding>*/ val value = producer() /*</binding>*/
    consume(/*<use>*/ value /*</use>*/)
}

fun directNestedArgument() {
    consume(/*<use>*/ producer() /*</use>*/)
}

fun nestedArgument() {
    consume(/*<relay-result>*/ relay(producer()) /*</relay-result>*/)
}

fun sourceWrapper() {
    val box = Box(producer(), "other")
    consume(/*<payload>*/ box.tracked /*</payload>*/)
}

fun discardedInput() {
    consume(/*<constant-result>*/ constant(producer()) /*</constant-result>*/)
}

fun otherField() {
    val box = Box(producer(), "other")
    consume(/*<unrelated>*/ box.other /*</unrelated>*/)
}

fun jdkArgument() {
    /*<binding>*/ val value = producer() /*</binding>*/
    val result = /*<external-result>*/
        java.util.Objects.requireNonNull(/*<use>*/ value /*</use>*/) /*</external-result>*/
    consume(result)
}
