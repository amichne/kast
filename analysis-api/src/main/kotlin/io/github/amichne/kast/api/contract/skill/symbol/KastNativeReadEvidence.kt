package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class KastNativeReadCompleteness {
    EXACT,
    QUALIFIED,
}

@Serializable
enum class KastNativeReadQualification {
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DUMB_MODE_TRANSITION,
    PROVIDER_FAILURE,
    UNSCOPED_PROVIDER,
    UNSUPPORTED_ITEM,
    EXACT_DEFINITION_UNAVAILABLE,
}

@Serializable
enum class KastNativeReadStage {
    ADMISSION_QUEUE,
    SMART_MODE_OR_TRANSITION_WAIT,
    SEARCH_SCOPE_COMPILATION,
    NATIVE_QUERY,
    SEMANTIC_RESOLUTION,
    PERSISTENCE_OR_PUBLICATION,
    PROJECTION_SERIALIZATION,
    IPC,
}

@Serializable
sealed interface KastReadStageObservation {
    @Serializable
    @SerialName("MEASURED")
    data class Measured(
        val nanoseconds: Long,
    ) : KastReadStageObservation {
        init {
            require(nanoseconds >= 0L) { "native read stage duration must be non-negative" }
        }
    }

    @Serializable
    @SerialName("NOT_APPLICABLE")
    data object NotApplicable : KastReadStageObservation

    @Serializable
    @SerialName("OUTSIDE_RESPONSE_BOUNDARY")
    data object OutsideResponseBoundary : KastReadStageObservation
}

@Serializable
data class KastNativeReadStages(
    val values: Map<KastNativeReadStage, KastReadStageObservation>,
) {
    init {
        require(values.keys == KastNativeReadStage.entries.toSet()) {
            "native read evidence must observe every stage exactly once"
        }
    }
}

@Serializable
data class KastNativeReadWork(
    val vfsRefreshCount: Long,
    val gradleImportCount: Long,
    val graphBuildCount: Long,
    val sqliteWriteCount: Long,
    val readActionCount: Long,
) {
    init {
        require(
            listOf(
                vfsRefreshCount,
                gradleImportCount,
                graphBuildCount,
                sqliteWriteCount,
                readActionCount,
            ).all { it >= 0L },
        ) { "native read work counters must be non-negative" }
    }
}

@Serializable
sealed interface KastReadEvidence {
    @Serializable
    @SerialName("LEGACY_COMPATIBILITY")
    data object LegacyCompatibility : KastReadEvidence

    @Serializable
    @SerialName("NATIVE_INTELLIJ")
    data class NativeIntellij(
        val generation: Long,
        val completeness: KastNativeReadCompleteness,
        val qualifications: Set<KastNativeReadQualification>,
        val stages: KastNativeReadStages,
        val work: KastNativeReadWork,
        val projectionBytes: Long,
    ) : KastReadEvidence {
        init {
            require(generation >= 0L) { "native read generation must be non-negative" }
            require(projectionBytes >= 0L) { "native read projection bytes must be non-negative" }
            require(
                when (completeness) {
                    KastNativeReadCompleteness.EXACT -> qualifications.isEmpty()
                    KastNativeReadCompleteness.QUALIFIED -> qualifications.isNotEmpty()
                },
            ) { "native read completeness and qualifications disagree" }
        }
    }
}
