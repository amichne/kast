package fixture.controls

data class ComponentPacket(val first: String, val second: String)

class AuthoredComponents(val first: String, val second: String) {
    operator fun component1(): String = second

    operator fun component2(): String = "constant"
}

class SkippedComponent(val value: String) {
    operator fun component1(): String = error("Skipped component must not execute")

    operator fun component2(): String = value
}

class OrderedComponents(val value: String) {
    operator fun component1(): String = error("Earlier component always fails")

    operator fun component2(): String = /*<ordered-return>*/value/*</ordered-return>*/
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
