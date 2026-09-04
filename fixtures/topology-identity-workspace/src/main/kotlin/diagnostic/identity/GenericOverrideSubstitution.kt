package diagnostic.identity

interface GenericOverrideParent<Value> {
    fun accept(value: Value): Value
}

class GenericOverrideChild : GenericOverrideParent<String> {
    override fun accept(value: String): String = value
}

fun genericOverrideSubstitutionProbe(): String = GenericOverrideChild().accept("probe")
