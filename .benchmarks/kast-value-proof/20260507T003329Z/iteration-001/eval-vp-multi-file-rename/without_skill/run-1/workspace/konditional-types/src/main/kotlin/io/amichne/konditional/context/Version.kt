package io.amichne.konditional.context

// ---------- Semantic Version ----------
data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<Version> {
    init {
        listOf(major, minor, patch).forEachIndexed { index, i ->
            require(i >= 0) {
                "Negative numbers are not supported for version identifiers, got $i for index $index"
            }
        }
    }

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    companion object {
        fun of(
            major: Int,
            minor: Int,
            patch: Int,
        ): Version = Version(major, minor, patch)

        fun parse(raw: String): Result<Version> = runCatching { parseUnsafe(raw) }

        @PublishedApi
        internal fun parseUnsafe(raw: String): Version = with(raw.split('.')) {
            require(isNotEmpty() && size <= 3) { "Bad versions: $raw" }
            forEachIndexed { index, string ->
                require(string.isNotBlank()) { "Got a blank value at index $index" }
                requireNotNull(string.toIntOrNull()) { "Unable to convert index $index with value $string to integer" }
            }

            val major = this[0].toInt()
            val minor = this.getOrElse(1, { "0" }).toInt()
            val patch = this.getOrElse(2, { "0" }).toInt()
            Version(major, minor, patch)
        }

        val default = Version(0, 0, 0)
    }
}
