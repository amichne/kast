package fixture.controls

class InterpolationCustomValue {
    override fun toString(): String = "custom"
}

object StringInterpolationControls {
    fun stringValue(value: String): String = "string=${value}"

    fun booleanValue(value: Boolean): String = "boolean=${value}"

    fun charValue(value: Char): String = "char=${value}"

    fun byteValue(value: Byte): String = "byte=${value}"

    fun shortValue(value: Short): String = "short=${value}"

    fun intValue(value: Int): String = "int=${value}"

    fun longValue(value: Long): String = "long=${value}"

    fun floatValue(value: Float): String = "float=${value}"

    fun doubleValue(value: Double): String = "double=${value}"

    fun nullableValue(value: String?): String = "nullable=${value}"

    fun customValue(value: InterpolationCustomValue): String = "custom=${value}"

    fun <T> genericValue(value: T): String = "generic=${value}"

    fun flexibleValue(): String = "flexible=${System.getProperty("kast.fixture.control")}"

    fun nestedPath(value: String): String = "length=${value.length}; text=${value}"

    fun siblings(value: String, other: String): Pair<String, String> {
        val selected = "selected=${value}"
        val sibling = "sibling=${other}"
        return selected to sibling
    }

    fun constantResult(value: String): String {
        value.hashCode()
        return "constant"
    }
}
