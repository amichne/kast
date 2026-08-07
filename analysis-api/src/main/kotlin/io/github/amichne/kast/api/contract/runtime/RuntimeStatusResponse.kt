@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.EncodeDefault

@Serializable
data class RuntimeStatusResponse(
    @DocField(description = "Current runtime state: STARTING, INDEXING, READY, or DEGRADED.")
    val state: RuntimeState,
    @DocField(description = "True when the daemon is responsive and not in an error state.")
    val healthy: Boolean,
    @DocField(description = "True when the daemon has an active workspace session.")
    val active: Boolean,
    @DocField(description = "True when the daemon is currently indexing the workspace.")
    val indexing: Boolean,
    @DocField(description = "Identifier of the analysis backend.")
    val backendName: String,
    @DocField(description = "Version string of the analysis backend.")
    val backendVersion: String,
    @DocField(description = "Absolute path of the workspace root directory.")
    val workspaceRoot: String,
    @DocField(description = "Human-readable status message with additional context.")
    val message: String? = null,
    @DocField(description = "Active warning messages about the runtime environment.", defaultValue = "emptyList()")
    val warnings: List<String> = emptyList(),
    @DocField(description = "Names of source modules discovered in the workspace.", defaultValue = "emptyList()")
    val sourceModuleNames: List<String> = emptyList(),
    @DocField(description = "Map from source module name to its dependency module names.", defaultValue = "emptyMap()")
    val dependentModuleNamesBySourceModuleName: Map<String, List<String>> = emptyMap(),
    @DocField(
        description = "True when committed symbol-reference evidence is queryable, including qualified evidence.",
        defaultValue = "false",
    )
    val referenceIndexReady: Boolean = false,
    @DocField(
        description = "Global persisted reference evidence state. This state is independent of runtime readiness.",
        defaultValue = "COMPLETE when referenceIndexReady is true; otherwise UNAVAILABLE",
    )
    val referenceCoverageState: ReferenceCoverageState = if (referenceIndexReady) {
        ReferenceCoverageState.COMPLETE
    } else {
        ReferenceCoverageState.UNAVAILABLE
    },
    @DocField(
        description = "Typed limitations that qualify or prevent persisted reference evidence.",
        defaultValue = "emptyList()",
    )
    val referenceCoverageLimitations: List<ReferenceCoverageLimitation> = emptyList(),
    @DocField(
        description = "Exact immutable workspace generation admitted for semantic reads, or null outside generation-backed READY.",
        defaultValue = "null",
    )
    val publishedWorkspaceGeneration: PublishedWorkspaceGenerationStatus? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @DocField(
        description = "Independent readiness evidence for the runtime, Gradle model, references, semantic graph, and mutation lanes.",
        defaultValue = "derived from legacy runtime and reference fields",
    )
    val readiness: RuntimeReadiness = RuntimeReadiness.fromLegacy(
        state = state,
        healthy = healthy,
        active = active,
        indexing = indexing,
        referenceCoverage = ReferenceCoverage.parse(
            indexReady = referenceIndexReady,
            state = referenceCoverageState,
            limitations = referenceCoverageLimitations,
        ),
    ),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @DocField(description = "Compatibility summary. True only when every readiness lane is ready.")
    val ready: Boolean = readiness.readySummary,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    @Transient
    val referenceCoverage: ReferenceCoverage = ReferenceCoverage.parse(
        indexReady = referenceIndexReady,
        state = referenceCoverageState,
        limitations = referenceCoverageLimitations,
    )

    init {
        require(ready == readiness.readySummary) {
            "Compatibility readiness must equal the layered readiness summary"
        }
        require(RuntimeReadinessLane.matchesReferenceCoverage(readiness.references, referenceCoverage)) {
            "Reference readiness must match reference coverage evidence"
        }
    }

    fun withReferenceCoverage(coverage: ReferenceCoverage): RuntimeStatusResponse {
        val updatedReadiness = readiness.copy(
            references = RuntimeReadinessLane.fromReferenceCoverage(coverage),
        )
        return copy(
            referenceIndexReady = coverage.indexReady,
            referenceCoverageState = coverage.state,
            referenceCoverageLimitations = coverage.limitations,
            readiness = updatedReadiness,
            ready = updatedReadiness.readySummary,
        )
    }
}

/**
 * Wire-boundary transition from serialized primitives to one constrained
 * published-workspace status. This DTO is the outer protocol boundary where
 * raw extraction is permitted; core publication code consumes
 * `PublishedWorkspaceGenerationManifest` instead.
 */
@Serializable
data class PublishedWorkspaceGenerationStatus(
    @DocField(description = "Positive revision committed in the workspace source-index database.")
    val generation: Long,
    @DocField(description = "Verified workspace-state identity bound to this generation.")
    val identity: String,
    @DocField(description = "Source-index generation stored in the published database.")
    val sourceIndexGeneration: Long,
    @DocField(description = "Source-index schema version stored in the published database.")
    val sourceIndexSchemaVersion: Int,
    @DocField(description = "Canonical workspace source-index database filename.")
    val databaseFile: String,
    @DocField(description = "Publication time in Unix epoch milliseconds.")
    val publishedAtEpochMillis: Long,
    @DocField(description = "Contained repository overlay descriptor filename, when repository evidence is attached.")
    val repositoryOverlayFile: String? = null,
) {
    init {
        require(generation > 0) { "Workspace semantic generation must be positive" }
        require(identity.isNotBlank()) { "Published workspace identity must not be blank" }
        require(sourceIndexGeneration >= 0) { "Source-index generation must not be negative" }
        require(sourceIndexSchemaVersion > 0) { "Source-index schema version must be positive" }
        require(databaseFile == "source-index.db") {
            "Published database file must be the single workspace source-index.db"
        }
        require(repositoryOverlayFile == null || repositoryOverlayFile == "repository-overlay.json") {
            "Published repository overlay must be repository-overlay.json"
        }
        require(publishedAtEpochMillis >= 0) { "Publication time must not be negative" }
    }
}

class ReferenceCoverage private constructor(
    val indexReady: Boolean,
    val state: ReferenceCoverageState,
    val limitations: List<ReferenceCoverageLimitation>,
) {
    companion object {
        fun complete(
            limitations: List<ReferenceCoverageLimitation> = emptyList(),
        ): ReferenceCoverage = parse(
            indexReady = true,
            state = ReferenceCoverageState.COMPLETE,
            limitations = limitations,
        )

        fun qualified(
            limitations: List<ReferenceCoverageLimitation>,
            indexReady: Boolean,
        ): ReferenceCoverage = parse(
            indexReady = indexReady,
            state = ReferenceCoverageState.QUALIFIED,
            limitations = limitations,
        )

        fun incomplete(limitations: List<ReferenceCoverageLimitation>): ReferenceCoverage = parse(
            indexReady = false,
            state = ReferenceCoverageState.INCOMPLETE,
            limitations = limitations,
        )

        fun unavailable(
            limitations: List<ReferenceCoverageLimitation> = emptyList(),
        ): ReferenceCoverage = parse(
            indexReady = false,
            state = ReferenceCoverageState.UNAVAILABLE,
            limitations = limitations,
        )

        /**
         * Construction transition:
         * `(Boolean, ReferenceCoverageState, List<ReferenceCoverageLimitation>) -> ReferenceCoverage`.
         *
         * Establishes a unique, state-compatible limitation set and readiness
         * relationship. Inputs come from the runtime-status protocol boundary;
         * invalid combinations are protocol-construction defects and never
         * flow into the returned core value.
         */
        fun parse(
            indexReady: Boolean,
            state: ReferenceCoverageState,
            limitations: List<ReferenceCoverageLimitation>,
        ): ReferenceCoverage {
            require(limitations.distinct().size == limitations.size) {
                "Reference coverage limitations must be unique"
            }
            val allowedLimitations = when (state) {
                ReferenceCoverageState.COMPLETE -> emptySet()
                ReferenceCoverageState.QUALIFIED -> QUALIFIED_LIMITATIONS
                ReferenceCoverageState.INCOMPLETE -> INCOMPLETE_LIMITATIONS
                ReferenceCoverageState.UNAVAILABLE -> UNAVAILABLE_LIMITATIONS
            }
            require(limitations.all(allowedLimitations::contains)) {
                "$state reference coverage has an incompatible limitation"
            }
            when (state) {
                ReferenceCoverageState.COMPLETE -> {
                    require(indexReady) { "Complete reference coverage must be ready" }
                    require(limitations.isEmpty()) { "Complete reference coverage cannot have limitations" }
                }
                ReferenceCoverageState.QUALIFIED -> {
                    require(limitations.isNotEmpty()) { "Qualified reference coverage requires a limitation" }
                    require(indexReady != limitations.contains(ReferenceCoverageLimitation.INDEXING_IN_PROGRESS)) {
                        "Qualified reference coverage is ready only after indexing completes"
                    }
                }
                ReferenceCoverageState.INCOMPLETE -> {
                    require(!indexReady) { "Incomplete reference coverage cannot be ready" }
                    require(limitations.isNotEmpty()) { "Incomplete reference coverage requires a limitation" }
                }
                ReferenceCoverageState.UNAVAILABLE ->
                    require(!indexReady) { "Unavailable reference coverage cannot be ready" }
            }
            return ReferenceCoverage(
                indexReady = indexReady,
                state = state,
                limitations = limitations.toList(),
            )
        }

        private val QUALIFIED_LIMITATIONS = setOf(
            ReferenceCoverageLimitation.INDEXING_IN_PROGRESS,
            ReferenceCoverageLimitation.NONCRITICAL_STAGE_GAP,
        )
        private val INCOMPLETE_LIMITATIONS = setOf(
            ReferenceCoverageLimitation.CRITICAL_STAGE_GAP,
            ReferenceCoverageLimitation.UNMATCHED_CRITICAL_PATH,
        )
        private val UNAVAILABLE_LIMITATIONS = setOf(
            ReferenceCoverageLimitation.INDEX_NOT_COMMITTED,
            ReferenceCoverageLimitation.PROJECT_MODEL_UNAVAILABLE,
            ReferenceCoverageLimitation.CANCELLED,
        )
    }
}

@Serializable
enum class ReferenceCoverageState {
    COMPLETE,
    QUALIFIED,
    INCOMPLETE,
    UNAVAILABLE,
}

@Serializable
enum class ReferenceCoverageLimitation {
    INDEX_NOT_COMMITTED,
    PROJECT_MODEL_UNAVAILABLE,
    INDEXING_IN_PROGRESS,
    NONCRITICAL_STAGE_GAP,
    CRITICAL_STAGE_GAP,
    UNMATCHED_CRITICAL_PATH,
    CANCELLED,
}
