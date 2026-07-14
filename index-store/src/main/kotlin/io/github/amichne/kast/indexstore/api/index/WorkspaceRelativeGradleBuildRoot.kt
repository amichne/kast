package io.github.amichne.kast.indexstore.api.index

@JvmInline
value class WorkspaceRelativeGradleBuildRoot private constructor(val value: String) {
    companion object {
        private val windowsDrivePrefix = Regex("^[A-Za-z]:")

        fun parse(raw: String): WorkspaceRelativeGradleBuildRoot {
            require(raw.isNotBlank()) { "Gradle build root must not be blank" }
            require(raw.none(Char::isISOControl)) { "Gradle build root must not contain control characters" }
            val normalized = raw.replace('\\', '/')
            require(!normalized.startsWith('/')) { "Gradle build root must be workspace-relative" }
            require(!windowsDrivePrefix.containsMatchIn(normalized)) {
                "Gradle build root must not be a Windows drive root"
            }
            if (normalized == ".") return WorkspaceRelativeGradleBuildRoot(normalized)
            val segments = normalized.split('/')
            require(segments.all { segment -> segment.isNotEmpty() && segment != "." && segment != ".." }) {
                "Gradle build root must be a normalized contained path"
            }
            return WorkspaceRelativeGradleBuildRoot(segments.joinToString("/"))
        }
    }
}
