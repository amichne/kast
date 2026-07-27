package io.github.amichne.kast.indexstore.api.index

@JvmInline
value class GradleProjectPath private constructor(val value: String) {
    companion object {
        fun parse(raw: String): GradleProjectPath {
            require(raw.startsWith(':')) { "Gradle project path must be absolute" }
            require(raw.none(Char::isISOControl)) { "Gradle project path must not contain control characters" }
            require('/' !in raw && '\\' !in raw) { "Gradle project path must use Gradle colon segments" }
            if (raw == ":") return GradleProjectPath(raw)
            require(!raw.endsWith(':')) { "Gradle project path must not have an empty final segment" }
            require(raw.drop(1).split(':').all(String::isNotBlank)) {
                "Gradle project path must not contain empty segments"
            }
            return GradleProjectPath(raw)
        }
    }
}
