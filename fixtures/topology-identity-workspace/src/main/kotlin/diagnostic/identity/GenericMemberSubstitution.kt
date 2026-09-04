package diagnostic.identity

class GenericMemberTarget<Value> {
    fun accept(value: Value): Value = value
}

fun genericMemberSubstitutionProbe(): String =
    GenericMemberTarget<String>().accept("probe")
