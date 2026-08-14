package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Freshness admitted by compiler, workspace, runtime, model, and mutation lanes. */
@Serializable
enum class CurrentCapabilityLaneFreshness {
    CURRENT,
}

/** Freshness admitted by independently persisted evidence lanes. */
@Serializable
enum class RetainedCapabilityLaneFreshness {
    CURRENT,
    PREVIOUS,
}

/** Freshness carried by a persisted fallback while its replacement is building. */
@Serializable
enum class PreviousCapabilityLaneFreshness {
    PREVIOUS,
}

/**
 * Current-only evidence for an ephemeral or mutation-capable lane.
 *
 * The freshness type has no `PREVIOUS` inhabitant, so compiler and mutation
 * availability cannot claim a stale generation. The raw revision is exposed
 * only by protocol serialization and adapter boundaries.
 */
@Serializable
@ConsistentCopyVisibility
data class CurrentCapabilityLaneEvidence private constructor(
    @DocField(description = "Positive revision proven for this capability lane.")
    val revision: EvidenceRevision,
    @DocField(description = "Current-only freshness proof for this capability lane.")
    val freshness: CurrentCapabilityLaneFreshness,
) {
    companion object {
        /** Proof composition: `EvidenceRevision -> CurrentCapabilityLaneEvidence`. */
        fun current(revision: EvidenceRevision): CurrentCapabilityLaneEvidence =
            CurrentCapabilityLaneEvidence(revision, CurrentCapabilityLaneFreshness.CURRENT)
    }
}

/**
 * Current or explicitly previous evidence for an independently persisted lane.
 *
 * The revision remains typed until the protocol or storage adapter boundary.
 */
@Serializable
@ConsistentCopyVisibility
data class RetainedCapabilityLaneEvidence private constructor(
    @DocField(description = "Positive revision proven for this persisted capability lane.")
    val revision: EvidenceRevision,
    @DocField(description = "Whether the revision describes the current or previous workspace generation.")
    val freshness: RetainedCapabilityLaneFreshness,
) {
    companion object {
        /** Proof composition: `EvidenceRevision -> RetainedCapabilityLaneEvidence(CURRENT)`. */
        fun current(revision: EvidenceRevision): RetainedCapabilityLaneEvidence =
            RetainedCapabilityLaneEvidence(revision, RetainedCapabilityLaneFreshness.CURRENT)

        /** Proof composition: `EvidenceRevision -> RetainedCapabilityLaneEvidence(PREVIOUS)`. */
        fun previous(revision: EvidenceRevision): RetainedCapabilityLaneEvidence =
            RetainedCapabilityLaneEvidence(revision, RetainedCapabilityLaneFreshness.PREVIOUS)
    }
}

/**
 * Previous-generation evidence retained as a fallback during a rebuild.
 *
 * The single-value freshness type prevents a building lane from labeling its
 * fallback as current.
 */
@Serializable
@ConsistentCopyVisibility
data class PreviousCapabilityLaneEvidence private constructor(
    @DocField(description = "Positive revision of the retained persisted capability lane.")
    val revision: EvidenceRevision,
    @DocField(description = "Previous-generation freshness proof for the retained fallback.")
    val freshness: PreviousCapabilityLaneFreshness,
) {
    companion object {
        /** Proof composition: `EvidenceRevision -> PreviousCapabilityLaneEvidence`. */
        fun previous(revision: EvidenceRevision): PreviousCapabilityLaneEvidence =
            PreviousCapabilityLaneEvidence(revision, PreviousCapabilityLaneFreshness.PREVIOUS)
    }
}

/** Closed reason that a capability lane cannot currently build or serve evidence. */
@Serializable
enum class CapabilityLaneBlocker {
    CAPABILITY_UNAVAILABLE,
    DEPENDENCY_UNAVAILABLE,
    INITIALIZATION_FAILED,
    INVALIDATED,
    UNSUPPORTED,
}

/** Closed retention state for the last independently published workspace generation. */
@Serializable
sealed interface RetainedWorkspaceGenerationStatus {
    @Serializable
    @SerialName("NONE")
    data object None : RetainedWorkspaceGenerationStatus

    @Serializable
    @SerialName("PREVIOUS")
    data class Previous(
        @DocField(description = "Previous generation retained for explicitly stale persisted reads.")
        val publication: PublishedWorkspaceGenerationStatus,
    ) : RetainedWorkspaceGenerationStatus
}

/**
 * Readiness of a capability that may serve only current-generation evidence.
 *
 * `PREVIOUS` is absent from [CurrentCapabilityLaneEvidence], making stale
 * compiler and mutation availability unrepresentable in this family.
 */
@Serializable
sealed interface CurrentCapabilityLaneReadiness {
    @Serializable
    @SerialName("AVAILABLE")
    data class Available(
        @DocField(description = "Current revision available to callers.")
        val evidence: CurrentCapabilityLaneEvidence,
    ) : CurrentCapabilityLaneReadiness

    @Serializable
    @SerialName("BUILDING")
    data class Building(
        @DocField(description = "Progress toward a current revision; no previous fallback is admissible.")
        val progress: RuntimeReadinessProgress,
    ) : CurrentCapabilityLaneReadiness

    @Serializable
    @SerialName("BLOCKED")
    data class Blocked(
        @DocField(description = "Closed reason this current-only lane is unavailable.")
        val blocker: CapabilityLaneBlocker,
    ) : CurrentCapabilityLaneReadiness
}

/** Explicit absence or previous-generation fallback for a building persisted lane. */
@Serializable
sealed interface RetainedCapabilityLaneFallback {
    @Serializable
    @SerialName("NONE")
    data object None : RetainedCapabilityLaneFallback

    @Serializable
    @SerialName("PREVIOUS")
    data class Previous(
        @DocField(description = "Explicit previous revision available while the replacement builds.")
        val evidence: PreviousCapabilityLaneEvidence,
    ) : RetainedCapabilityLaneFallback
}

/** Readiness of an independently published and retained evidence lane. */
@Serializable
sealed interface RetainedCapabilityLaneReadiness {
    @Serializable
    @SerialName("AVAILABLE")
    data class Available(
        @DocField(description = "Current or explicitly previous persisted revision available to callers.")
        val evidence: RetainedCapabilityLaneEvidence,
    ) : RetainedCapabilityLaneReadiness

    @Serializable
    @SerialName("BUILDING")
    data class Building(
        @DocField(description = "Progress toward the next atomically published revision.")
        val progress: RuntimeReadinessProgress,
        @DocField(description = "Closed fallback state for the last independently published revision.")
        val fallback: RetainedCapabilityLaneFallback,
    ) : RetainedCapabilityLaneReadiness

    @Serializable
    @SerialName("BLOCKED")
    data class Blocked(
        @DocField(description = "Closed reason this persisted lane is unavailable.")
        val blocker: CapabilityLaneBlocker,
    ) : RetainedCapabilityLaneReadiness
}

internal fun CurrentCapabilityLaneReadiness.toLegacyReadinessLane(): RuntimeReadinessLane = when (this) {
    is CurrentCapabilityLaneReadiness.Available -> RuntimeReadinessLane.Ready
    is CurrentCapabilityLaneReadiness.Building -> RuntimeReadinessLane.InProgress(progress)
    is CurrentCapabilityLaneReadiness.Blocked -> RuntimeReadinessLane.Blocked
}

internal fun RetainedCapabilityLaneReadiness.toLegacyReadinessLane(): RuntimeReadinessLane = when (this) {
    is RetainedCapabilityLaneReadiness.Available -> RuntimeReadinessLane.Ready
    is RetainedCapabilityLaneReadiness.Building -> RuntimeReadinessLane.InProgress(progress)
    is RetainedCapabilityLaneReadiness.Blocked -> RuntimeReadinessLane.Blocked
}
