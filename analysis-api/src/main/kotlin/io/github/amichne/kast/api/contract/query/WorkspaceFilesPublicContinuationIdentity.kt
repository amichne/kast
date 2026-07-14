package io.github.amichne.kast.api.contract.query

import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesPublicContinuationIdentity(
    val workspaceRoot: WorkspaceRoot,
    val backendName: BackendName,
    val normalizedQuery: NormalizedQuery,
    val projection: Projection,
    val limit: Limit,
) {
    @Serializable
    @JvmInline
    value class WorkspaceRoot private constructor(val value: String) {
        init {
            require(value.isNotBlank()) { "Workspace root must not be blank" }
            require(value.none(Char::isISOControl)) { "Workspace root must not contain control characters" }
            val path = Path.of(value)
            require(path.isAbsolute) { "Workspace root must be absolute" }
            require(path.normalize().toString() == value) { "Workspace root must be normalized" }
        }

        companion object {
            fun parse(raw: String): WorkspaceRoot = WorkspaceRoot(raw)
        }
    }

    @Serializable
    @JvmInline
    value class BackendName private constructor(val value: String) {
        init {
            require(value.isNotBlank()) { "Backend name must not be blank" }
            require(value == value.trim()) { "Backend name must be normalized" }
            require(value.none(Char::isISOControl)) { "Backend name must not contain control characters" }
        }

        companion object {
            fun parse(raw: String): BackendName = BackendName(raw)
        }
    }

    @Serializable
    @JvmInline
    value class NormalizedQuery private constructor(val value: String) {
        init {
            require(value.isNotBlank()) { "Normalized workspace-file query must not be blank" }
            require(value.none(Char::isISOControl)) {
                "Normalized workspace-file query must not contain control characters"
            }
        }

        companion object {
            fun parse(raw: String): NormalizedQuery = NormalizedQuery(raw)
        }
    }

    @Serializable
    @JvmInline
    value class Projection private constructor(val value: String) {
        init {
            require(value.isNotBlank()) { "Workspace-file projection must not be blank" }
            require(value.none(Char::isISOControl)) {
                "Workspace-file projection must not contain control characters"
            }
        }

        companion object {
            fun parse(raw: String): Projection = Projection(raw)
        }
    }

    @Serializable
    @JvmInline
    value class Limit private constructor(val value: Int) {
        init {
            require(value in 1..200) { "Workspace-file continuation limit must be between 1 and 200" }
        }

        companion object {
            fun of(value: Int): Limit = Limit(value)
        }
    }
}
