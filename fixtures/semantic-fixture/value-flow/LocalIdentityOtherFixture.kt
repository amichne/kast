package representation.other

fun otherFileBindings(input: String) {
    val binding = input
    fun named(value: String): String = value
    named(binding)
}
