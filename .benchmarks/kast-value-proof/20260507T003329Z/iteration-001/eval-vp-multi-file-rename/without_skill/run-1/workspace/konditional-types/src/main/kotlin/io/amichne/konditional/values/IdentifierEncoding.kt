package io.amichne.konditional.values

internal object IdentifierEncoding {
    fun encode(
        components: List<String>,
        prefix: String,
    ): String {
        require(prefix.isNotBlank()) { "Identifier prefix must not be blank" }
        require(!prefix.contains(SEPARATOR)) { "Identifier prefix must not contain '${SEPARATOR}': '$prefix'" }
        components.forEachIndexed { index, component ->
            require(component.isNotBlank()) { "Identifier component[$index] must not be blank" }
            require(!component.contains(SEPARATOR)) {
                "Identifier component[$index] must not contain '${SEPARATOR}': '$component'"
            }
        }
        return (listOf(prefix) + components).joinToString(SEPARATOR)
    }

    fun split(plainId: String): List<String> = plainId.split(SEPARATOR)

    const val SEPARATOR: String = "::"
}
