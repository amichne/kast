package fixture.attributes

inline fun <reified T : Any> getWithAttributes(manager: Attributes, key: AttributeKey<T>, noinline load: () -> T): T =
    manager.computeIfAbsent(key, load)
fun attributesFactory(): Attributes = MapAttributes()
fun ordinaryAttributes(manager: Attributes, key: AttributeKey<String>): String =
    getWithAttributes(manager, key, load = { "ordinary" })
fun inferredAttributes(key: AttributeKey<String>): String = attributesFactory().take(key)
inline fun <T> insideAlso(value: T, block: (T) -> Unit): T {
    block(value)
    return value
}
fun helperRemoval(manager: Attributes, key: AttributeKey<String>): String =
    insideAlso(manager.get(key)) { manager.remove(key) }
