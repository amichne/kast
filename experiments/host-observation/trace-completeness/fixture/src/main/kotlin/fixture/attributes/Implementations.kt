package fixture.attributes

class MapAttributes : Attributes {
    private val values = mutableMapOf<AttributeKey<*>, Any>()
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getOrNull(key: AttributeKey<T>): T? = values[key] as T?
    override fun contains(key: AttributeKey<*>): Boolean = values.containsKey(key)
    override fun <T : Any> put(key: AttributeKey<T>, value: T) { values[key] = value }
    override fun <T : Any> remove(key: AttributeKey<T>) { values.remove(key) }
    override fun <T : Any> computeIfAbsent(key: AttributeKey<T>, block: () -> T): T {
        val previous = getOrNull(key)
        if (previous != null) return previous
        val value = block()
        put(key, value)
        return value
    }
    override val allKeys: List<AttributeKey<*>> get() = values.keys.toList()
}

object EmptyAttributes : Attributes {
    override fun <T : Any> getOrNull(key: AttributeKey<T>): T? = null
    override fun contains(key: AttributeKey<*>): Boolean = false
    override fun <T : Any> put(key: AttributeKey<T>, value: T) = Unit
    override fun <T : Any> remove(key: AttributeKey<T>) = Unit
    override fun <T : Any> computeIfAbsent(key: AttributeKey<T>, block: () -> T): T = block()
    override val allKeys: List<AttributeKey<*>> get() = emptyList()
}
