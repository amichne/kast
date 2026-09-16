package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticReadRejection
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Bounded planning evidence, without source text, paths, or a fabricated planning continuation. */
@Serializable
internal sealed interface HostedPlanningEvidenceFailure {
    @Serializable
    @SerialName("RELATION_REJECTED")
    data class RelationRejected(
        @Serializable(with = PlanningRelationRejectionSerializer::class) val cause: RelationReadRejection
    ) : HostedPlanningEvidenceFailure

    @Serializable
    @SerialName("RELATION_INCOMPLETE")
    data class RelationIncomplete(
        val limitations: Set<@Serializable(with = PlanningRelationLimitationSerializer::class) RelationLimitation>,
        val continuation: HostedPlanningContinuation,
    ) : HostedPlanningEvidenceFailure

    @Serializable
    @SerialName("TRAVERSAL_REJECTED")
    data class TraversalRejected(val cause: HostedPlanningTraversalRejection) : HostedPlanningEvidenceFailure

    @Serializable
    @SerialName("TRAVERSAL_ONE_HOP_REJECTED")
    data class TraversalOneHopRejected(
        @Serializable(with = PlanningRelationRejectionSerializer::class) val cause: RelationReadRejection
    ) : HostedPlanningEvidenceFailure

    @Serializable
    @SerialName("TRAVERSAL_INCOMPLETE")
    data class TraversalIncomplete(
        val limitations: Set<@Serializable(with = PlanningTraversalLimitationSerializer::class) TraversalLimitation>,
        val relationLimitations:
            Set<@Serializable(with = PlanningRelationLimitationSerializer::class) RelationLimitation>,
        val continuation: HostedPlanningContinuation,
    ) : HostedPlanningEvidenceFailure

    @Serializable
    @SerialName("DIAGNOSTIC_REJECTED")
    data class DiagnosticRejected(
        @Serializable(with = PlanningDiagnosticRejectionSerializer::class) val cause: DiagnosticReadRejection
    ) : HostedPlanningEvidenceFailure

    @Serializable
    @SerialName("DIAGNOSTIC_INCOMPLETE")
    data class DiagnosticIncomplete(
        val limitations:
            Set<@Serializable(with = PlanningDiagnosticLimitationSerializer::class) DiagnosticLimitationReason>
    ) : HostedPlanningEvidenceFailure
}

/** Read page checkpoints cannot stand in for a complete accumulated planning proof. */
@Serializable
internal enum class HostedPlanningContinuation {
    COMPLETE_EVIDENCE_ACCUMULATION_UNAVAILABLE,
    TERMINAL_INCOMPLETE,
}

@Serializable
internal enum class HostedPlanningTraversalRejection {
    REQUIRED_EVIDENCE_UNAVAILABLE,
    REQUIRED_EVIDENCE_STALE,
    READER_CONTRACT_VIOLATION,
    TRAVERSAL_CONTRACT_VIOLATION,
}

internal fun RelationReadResult.planningEvidence():
    Refinement<RelationReadResult.Complete, HostedPlanningEvidenceFailure> =
    when (this) {
        is RelationReadResult.Complete -> Refinement.Refined(this)
        is RelationReadResult.Rejected -> Refinement.Rejected(HostedPlanningEvidenceFailure.RelationRejected(reason))
        is RelationReadResult.Qualified ->
            Refinement.Rejected(
                HostedPlanningEvidenceFailure.RelationIncomplete(
                    coverage.limitations,
                    when (coverage) {
                        is RelationIncompleteCoverage.Resumable ->
                            HostedPlanningContinuation.COMPLETE_EVIDENCE_ACCUMULATION_UNAVAILABLE
                        is RelationIncompleteCoverage.TerminalIncomplete ->
                            HostedPlanningContinuation.TERMINAL_INCOMPLETE
                    },
                )
            )
    }

internal fun TraversalResult.planningEvidence(): Refinement<TraversalResult.Complete, HostedPlanningEvidenceFailure> =
    when (this) {
        is TraversalResult.Complete -> Refinement.Refined(this)
        is TraversalResult.Qualified ->
            Refinement.Rejected(
                HostedPlanningEvidenceFailure.TraversalIncomplete(
                    qualification.limitations,
                    qualification.relationLimitations,
                    when (qualification) {
                        is TraversalQualification.Resumable ->
                            HostedPlanningContinuation.COMPLETE_EVIDENCE_ACCUMULATION_UNAVAILABLE
                        is TraversalQualification.TerminalIncomplete -> HostedPlanningContinuation.TERMINAL_INCOMPLETE
                    },
                )
            )
        is TraversalResult.Rejected ->
            Refinement.Rejected(
                when (val failure = reason) {
                    is TraversalRejection.OneHopRejected ->
                        HostedPlanningEvidenceFailure.TraversalOneHopRejected(failure.reason)
                    TraversalRejection.RequiredEvidenceUnavailable ->
                        HostedPlanningEvidenceFailure.TraversalRejected(
                            HostedPlanningTraversalRejection.REQUIRED_EVIDENCE_UNAVAILABLE
                        )
                    TraversalRejection.RequiredEvidenceStale ->
                        HostedPlanningEvidenceFailure.TraversalRejected(
                            HostedPlanningTraversalRejection.REQUIRED_EVIDENCE_STALE
                        )
                    TraversalRejection.ReaderContractViolation ->
                        HostedPlanningEvidenceFailure.TraversalRejected(
                            HostedPlanningTraversalRejection.READER_CONTRACT_VIOLATION
                        )
                    TraversalRejection.TraversalContractViolation ->
                        HostedPlanningEvidenceFailure.TraversalRejected(
                            HostedPlanningTraversalRejection.TRAVERSAL_CONTRACT_VIOLATION
                        )
                }
            )
    }

internal fun DiagnosticCheckResult.planningEvidence():
    Refinement<DiagnosticCheckResult.Complete, HostedPlanningEvidenceFailure> =
    when (this) {
        is DiagnosticCheckResult.Complete -> Refinement.Refined(this)
        is DiagnosticCheckResult.Rejected ->
            Refinement.Rejected(HostedPlanningEvidenceFailure.DiagnosticRejected(reason))
        is DiagnosticCheckResult.Qualified ->
            Refinement.Rejected(
                HostedPlanningEvidenceFailure.DiagnosticIncomplete(coverage.limitations.map { it.reason }.toSet())
            )
    }

/** Serializes existing closed domain causes without introducing a second failure vocabulary. */
internal abstract class PlanningEnumSerializer<Value : Enum<Value>>(
    name: String,
    private val entries: List<Value>,
) : KSerializer<Value> {
    override val descriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Value) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): Value {
        val name = decoder.decodeString()
        return entries.firstOrNull { it.name == name }
            ?: throw SerializationException("Unknown planning evidence cause")
    }
}

internal object PlanningRelationRejectionSerializer :
    PlanningEnumSerializer<RelationReadRejection>("PlanningRelationRejection", RelationReadRejection.entries)

internal object PlanningRelationLimitationSerializer :
    PlanningEnumSerializer<RelationLimitation>("PlanningRelationLimitation", RelationLimitation.entries)

internal object PlanningTraversalLimitationSerializer :
    PlanningEnumSerializer<TraversalLimitation>("PlanningTraversalLimitation", TraversalLimitation.entries)

internal object PlanningDiagnosticRejectionSerializer :
    PlanningEnumSerializer<DiagnosticReadRejection>("PlanningDiagnosticRejection", DiagnosticReadRejection.entries)

internal object PlanningDiagnosticLimitationSerializer :
    PlanningEnumSerializer<DiagnosticLimitationReason>(
        "PlanningDiagnosticLimitation",
        DiagnosticLimitationReason.entries,
    )
