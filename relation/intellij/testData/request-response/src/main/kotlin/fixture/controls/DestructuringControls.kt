package fixture.controls

data class ComponentPacket(val first: String, val second: String)

class AuthoredComponents(val first: String, val second: String) {
    operator fun component1(): String = second

    operator fun component2(): String { return "constant" }
}

class SkippedComponent(val value: String) {
    operator fun component1(): String = error("Skipped component must not execute")

    operator fun component2(): String = value
}

class OrderedComponents(val value: String) {
    operator fun component1(): String { return error("Earlier component always fails") }

    operator fun component2(): String = /*<ordered-return>*/value/*</ordered-return>*/
}

class ReorderedComponents(val first: String, val second: String) {
    operator fun component1(): String { return second }
    operator fun component2(): String { return first }
}

class LaterThrowingComponents(val value: String) {
    operator fun component1(): String = /*<later-return>*/value/*</later-return>*/
    operator fun component2(): String { return error("Later component prevents both uses") }
}

class UnclassifiedComponents(val value: String) {
    val other: String
        get() = value
    operator fun component1(): String = value
    operator fun component2(): String { return other }
}

class SideEffectComponents(val value: String) {
    operator fun component1(): String { return value }
    operator fun component2(): String {
        println("Side effect before the return")
        return value
    }
}

fun destructuringOrigin(): String = "origin"

fun reorderedComponents() {
    val (/*<left-binding>*/left/*</left-binding>*/, /*<right-binding>*/right/*</right-binding>*/) =
        ReorderedComponents("unrelated", destructuringOrigin())
    /*<left-use>*/left/*</left-use>*/.length
    /*<right-use>*/right/*</right-use>*/.length
}

fun normalConstantSibling() {
    val (/*<binding>*/payload/*</binding>*/, constant) = AuthoredComponents("unrelated", "origin")
    /*<use>*/payload/*</use>*/.length
    constant.length
}

fun laterThrowingComponents() {
    val (/*<binding>*/payload/*</binding>*/, later) = LaterThrowingComponents("origin")
    /*<use>*/payload/*</use>*/.length
    later.length
}

fun unusedThrowingComponent() {
    val (/*<binding>*/payload/*</binding>*/, unused) = LaterThrowingComponents("origin")
    /*<use>*/payload/*</use>*/.length
}

fun skippedLaterThrowingComponent() {
    val (/*<binding>*/payload/*</binding>*/, _) = LaterThrowingComponents("origin")
    /*<use>*/payload/*</use>*/.length
}

fun unclassifiedSibling() {
    val (payload, unused) = UnclassifiedComponents("origin")
    payload.length
}

fun sideEffectSibling() {
    val (payload, unused) = SideEffectComponents("origin")
    payload.length
}

data class MutableComponent(var value: String)

fun generatedComponents() {
    val (/*<first-binding>*/first/*</first-binding>*/, /*<second-binding>*/second/*</second-binding>*/) =
        ComponentPacket("first-value", "second-value")
    first.length
    second.length
}

fun explicitGeneratedComponent() {
    val first = ComponentPacket("first-value", "second-value")./*<selector>*/component1()/*</selector>*/
    first.length
}

fun authoredComponents() {
    val (first, second) = AuthoredComponents("first-value", "second-value")
    first.length
    second.length
}

fun oneAuthoredComponent() {
    val (/*<binding>*/first/*</binding>*/) = AuthoredComponents("first-value", "second-value")
    first.length
}

fun constantAuthoredComponent() {
    val (_, second) = AuthoredComponents("first-value", "second-value")
    second.length
}

fun explicitAuthoredComponent() {
    val first = AuthoredComponents("first-value", "second-value").component1()
    first.length
}

fun twoAuthoredComponents() {
    val (/*<first-binding>*/first/*</first-binding>*/) = AuthoredComponents("ignored-a", "value-a")
    val (/*<second-binding>*/second/*</second-binding>*/) = AuthoredComponents("ignored-b", "value-b")
    first.length
    second.length
}

fun skippedComponent() {
    val (_, kept) = SkippedComponent("kept-value")
    kept.length
}

fun mutableDestructuring() {
    var (first) = ComponentPacket("first-value", "second-value")
    first = "changed"
    first.length
}

fun mutableComponent(packet: MutableComponent) {
    val (first) = /*<mutable-source>*/packet/*</mutable-source>*/
    first.length
}

fun orderedComponents() {
    val (first, second) = OrderedComponents("value")
    first.length
    second.length
}

fun loopDestructuring(values: List<ComponentPacket>) {
    for ((first) in values) first.length
}

fun lambdaDestructuring(values: List<ComponentPacket>) {
    values.forEach { (first) -> first.length }
}

fun mutableEntryUnchanged() {
    var (/*<binding>*/first/*</binding>*/, /*<other-binding>*/second/*</other-binding>*/) =
        ComponentPacket("first-value", "second-value")
    /*<use>*/first/*</use>*/.length
    /*<other-use>*/second/*</other-use>*/.length
}

fun mutableEntryReassigned() {
    var (/*<binding>*/first/*</binding>*/, /*<other-binding>*/second/*</other-binding>*/) =
        ComponentPacket("first-value", "second-value")
    /*<before>*/first/*</before>*/.length
    /*<other-before>*/second/*</other-before>*/.length
    /*<replacement-binding>*/first/*</replacement-binding>*/ = /*<replacement>*/"replacement-value"/*</replacement>*/
    /*<after>*/first/*</after>*/.length
    /*<other-after>*/second/*</other-after>*/.length
}

fun mutableEntrySelfAssigned() {
    var (first) = ComponentPacket("first-value", "second-value")
    /*<before>*/first/*</before>*/.length
    /*<write>*/first/*</write>*/ = /*<rhs>*/first/*</rhs>*/
    /*<after>*/first/*</after>*/.length
}
