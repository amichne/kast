package io.github.amichne.kast.indexstore.api.index

@JvmInline
value class GradleSourceSetName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): GradleSourceSetName {
            require(raw.isNotBlank()) { "Gradle source-set name must not be blank" }
            require(raw == raw.trim()) { "Gradle source-set name must be canonical" }
            require(raw.none(Char::isISOControl)) { "Gradle source-set name must not contain control characters" }
            require('/' !in raw && '\\' !in raw && ':' !in raw) {
                "Gradle source-set name must be one model-owned name"
            }
            return GradleSourceSetName(raw)
        }
    }
}
