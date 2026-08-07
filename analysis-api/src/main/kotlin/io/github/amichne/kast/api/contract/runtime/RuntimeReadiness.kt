package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeReadiness(
    @DocField(description = "Readiness of the runtime process and workspace session.")
    val runtime: RuntimeReadinessLane,
    @DocField(description = "Readiness of the imported Gradle project model.")
    val model: RuntimeReadinessLane,
    @DocField(description = "Readiness of committed symbol-reference evidence.")
    val references: RuntimeReadinessLane,
    @DocField(description = "Readiness of committed semantic graph evidence.")
    val semanticGraph: RuntimeReadinessLane,
    @DocField(description = "Readiness of verified mutation operations.")
    val mutation: RuntimeReadinessLane,
) {
    val readySummary: Boolean
        get() = runtime is RuntimeReadinessLane.Ready &&
            model is RuntimeReadinessLane.Ready &&
            references is RuntimeReadinessLane.Ready &&
            semanticGraph is RuntimeReadinessLane.Ready &&
            mutation is RuntimeReadinessLane.Ready

    internal companion object {
        fun fromLegacy(
            state: RuntimeState,
            healthy: Boolean,
            active: Boolean,
            indexing: Boolean,
            referenceCoverage: ReferenceCoverage,
        ): RuntimeReadiness {
            val runtimeLane = when {
                !healthy || state == RuntimeState.DEGRADED -> RuntimeReadinessLane.Blocked
                active -> RuntimeReadinessLane.Ready
                else -> RuntimeReadinessLane.inProgress(RuntimeProgressStage.STARTING)
            }
            val modelLane = when {
                !healthy || state == RuntimeState.DEGRADED -> RuntimeReadinessLane.Blocked
                state == RuntimeState.READY && !indexing -> RuntimeReadinessLane.Ready
                else -> RuntimeReadinessLane.inProgress(RuntimeProgressStage.GRADLE_IMPORT)
            }
            val graphLane = when {
                !healthy || state == RuntimeState.DEGRADED -> RuntimeReadinessLane.Blocked
                state == RuntimeState.READY && !indexing -> RuntimeReadinessLane.Ready
                else -> RuntimeReadinessLane.inProgress(RuntimeProgressStage.SEMANTIC_GRAPH)
            }
            return RuntimeReadiness(
                runtime = runtimeLane,
                model = modelLane,
                references = RuntimeReadinessLane.fromReferenceCoverage(referenceCoverage),
                semanticGraph = graphLane,
                mutation = graphLane,
            )
        }
    }
}

@Serializable
sealed interface RuntimeReadinessLane {
    @Serializable
    @SerialName("READY")
    data object Ready : RuntimeReadinessLane

    @Serializable
    @SerialName("IN_PROGRESS")
    data class InProgress(
        @DocField(description = "Typed progress evidence for the active runtime stage.")
        val progress: RuntimeReadinessProgress,
    ) : RuntimeReadinessLane

    @Serializable
    @SerialName("BLOCKED")
    data object Blocked : RuntimeReadinessLane

    companion object {
        fun inProgress(stage: RuntimeProgressStage): InProgress =
            InProgress(RuntimeReadinessProgress.uncounted(stage))

        internal fun matchesReferenceCoverage(
            lane: RuntimeReadinessLane,
            coverage: ReferenceCoverage,
        ): Boolean = when (fromReferenceCoverage(coverage)) {
            Ready -> lane is Ready
            is InProgress -> lane is InProgress
            Blocked -> lane is Blocked
        }

        internal fun fromReferenceCoverage(coverage: ReferenceCoverage): RuntimeReadinessLane = when {
            coverage.indexReady -> Ready
            coverage.state == ReferenceCoverageState.QUALIFIED -> inProgress(RuntimeProgressStage.REFERENCE_INDEX)
            else -> Blocked
        }
    }
}

@Serializable
data class RuntimeReadinessProgress(
    @DocField(description = "Current progress stage.")
    val stage: RuntimeProgressStage,
    @DocField(description = "Completed work units. Zero with zero total means uncounted work.")
    val completedUnits: Long,
    @DocField(description = "Total known work units. Zero means the stage is not countable.")
    val totalUnits: Long,
    @DocField(description = "Milliseconds elapsed in the current stage.")
    val elapsedMillis: Long,
    @DocField(description = "Milliseconds since the most recent observed progress.")
    val noProgressMillis: Long,
) {
    init {
        require(completedUnits >= 0) { "Completed readiness work units must not be negative" }
        require(totalUnits >= 0) { "Total readiness work units must not be negative" }
        require(completedUnits <= totalUnits) { "Completed readiness work units must not exceed the total" }
        require(elapsedMillis >= 0) { "Readiness elapsed time must not be negative" }
        require(noProgressMillis in 0..elapsedMillis) { "No-progress time must be within elapsed time" }
    }

    companion object {
        fun uncounted(stage: RuntimeProgressStage): RuntimeReadinessProgress = RuntimeReadinessProgress(
            stage = stage,
            completedUnits = 0,
            totalUnits = 0,
            elapsedMillis = 0,
            noProgressMillis = 0,
        )
    }
}

@Serializable
enum class RuntimeProgressStage {
    STARTING,
    GRADLE_IMPORT,
    MODEL_SETTLEMENT,
    IDE_INDEXING,
    SOURCE_INDEX,
    REFERENCE_INDEX,
    SEMANTIC_GRAPH,
}
