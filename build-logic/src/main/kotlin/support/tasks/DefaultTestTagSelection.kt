internal data class TestTagSelection(
    val included: Set<String>,
    val excluded: Set<String>,
)

internal object DefaultTestTagSelection {
    private val defaultExcludedTags = linkedSetOf("concurrency", "performance", "parity")

    fun from(includeTags: String?, excludeTags: String?): TestTagSelection {
        val included = parseTags(includeTags)
        val excluded = linkedSetOf<String>().apply {
            if (included.isEmpty()) {
                addAll(defaultExcludedTags)
            }
            addAll(parseTags(excludeTags))
        }
        return TestTagSelection(
            included = included,
            excluded = excluded,
        )
    }

    private fun parseTags(rawTags: String?): LinkedHashSet<String> =
        rawTags
            ?.split(",")
            ?.asSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toCollection(linkedSetOf())
            ?: linkedSetOf()
}
