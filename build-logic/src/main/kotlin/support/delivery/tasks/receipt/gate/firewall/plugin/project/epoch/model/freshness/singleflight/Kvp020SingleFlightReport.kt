package support.delivery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp020SingleFlightDocument(
    val schemaVersion: Int,
    val taskId: String,
    val authority: Kvp020ReportAuthority,
    val publicInterface: Kvp020PublicInterface,
    val effect: Kvp020ReportEffect,
    val scopeEvidence: List<Kvp020ScopeEvidence>,
    val states: List<Kvp020SingleFlightState>,
    val transitions: List<Kvp020SingleFlightTransition>,
    val activePermitLimit: Int,
    val queuedRequestLimit: Int,
    val admissionCases: List<Kvp020AdmissionCase>,
    val retirementCauses: List<Kvp020RetirementCause>,
    val cancellationCauses: List<Kvp020CancellationCause>,
    val terminalizationLimitPerAuthority: Int,
    val promotionLimitPerActiveTerminalization: Int,
    val freshnessObservationCount: Int,
    val semanticExecutionCount: Int,
    val retainedCapabilityEvidence: List<Kvp020RetainedEvidence>,
    val forbiddenWork: List<Kvp020ForbiddenWorkDocument>,
    val forbiddenRetention: List<Kvp020ForbiddenRetentionDocument>,
    val predecessorReceipts: List<Kvp020PredecessorDocument>,
)

@Serializable private data class Kvp020ForbiddenWorkDocument(
    val kind: Kvp020ForbiddenWork, val observedCount: Int,
)

@Serializable private data class Kvp020ForbiddenRetentionDocument(
    val kind: Kvp020ForbiddenRetention, val observedCount: Int,
)

@Serializable internal data class Kvp020PredecessorDocument(
    val receiptId: Kvp020PredecessorReceiptId, val sha256: String,
)
@Serializable private enum class Kvp020ReportAuthority { READ_RUNTIME }
@Serializable private enum class Kvp020PublicInterface { ProjectReadPermit }
@Serializable private enum class Kvp020ReportEffect { PURE_STATE_TRANSITIONS }

@Serializable private enum class Kvp020ScopeEvidence {
    CANONICAL_ROOT, PROJECT_READ_EPOCH_COMPARISON_DOMAIN,
}

@Serializable private enum class Kvp020SingleFlightState { IDLE, ACTIVE, ACTIVE_AND_QUEUED, RETIRED }

@Serializable private enum class Kvp020AdmissionCase {
    ACTIVE_PERMIT_ISSUED, REQUEST_QUEUED, REJECTED_BUSY, REJECTED_PROJECT_SCOPE,
    REJECTED_RETIRED,
}

@Serializable private enum class Kvp020RetirementCause {
    PROJECT_DISPOSED, PLUGIN_UNLOADED, ENDPOINT_PUBLICATION_FAILED, SOCKET_FAILED,
}

@Serializable private enum class Kvp020CancellationCause {
    REQUEST_CANCELLED, CLIENT_DISCONNECTED,
}

@Serializable private enum class Kvp020RetainedEvidence {
    VFS_PASSIVE_READ_CAPABILITY, CANONICAL_ROOT, ADMITTED_PROJECT_READ_EPOCH,
}

@Serializable private enum class Kvp020ForbiddenWork {
    UNBOUNDED_CHANNEL, GLOBAL_LOCK_ACROSS_PROJECTS,
    HOLDING_PERMIT_AFTER_DISCONNECT_OR_DISPOSAL, PARALLEL_SEMANTIC_READS_BY_DEFAULT,
}

@Serializable private enum class Kvp020ForbiddenRetention {
    INTELLIJ_PROJECT, CALLBACK, EXECUTION_EFFECT, GLOBAL_REGISTRY,
    CROSS_PROJECT_LOCK, CHANNEL, UNBOUNDED_COLLECTION,
}

@Serializable internal enum class Kvp020PredecessorReceiptId {
    @SerialName("KVP-014-COMPLETE") KVP_014_COMPLETE,
    @SerialName("KVP-019-COMPLETE") KVP_019_COMPLETE,
}

internal class Kvp020PredecessorReceipt private constructor(
    val id: Kvp020PredecessorReceiptId,
    val digest: String,
) {
    companion object {
        /**
         * Proof transition: `(Kvp020PredecessorReceiptId, String) ->
         * Kvp020PredecessorRefinement`.
         *
         * Establishes the exact completion identity and self-derived digest. Malformed or
         * mismatched receipt bytes remain closed [Kvp020SingleFlightReportFailure] data. Raw JSON
         * enters only at this Gradle report boundary.
         */
        fun decode(
            expected: Kvp020PredecessorReceiptId,
            raw: String,
        ): Kvp020PredecessorRefinement = when (val decoded = decodeProofReceiptDocument(raw)) {
            is ProofReceiptDocumentResult.Rejected -> predecessorRejected()
            is ProofReceiptDocumentResult.Complete -> {
                val document = decoded.document
                if (document.receiptId.value != expected.receiptId ||
                    document.taskId.value != expected.taskId ||
                    document.gateId.value != expected.gateId ||
                    document.receiptDigest != document.derivedDigest()
                ) predecessorRejected()
                else Kvp020PredecessorRefinement.Admitted(
                    Kvp020PredecessorReceipt(expected, document.receiptDigest.value),
                )
            }
        }

        /**
         * Proof transition: `(Kvp020PredecessorReceiptId, AdmittedProofReceipt) ->
         * Kvp020PredecessorRefinement`.
         *
         * Preserves the digest only for the exact completion identity. Identity mismatch remains
         * closed [Kvp020SingleFlightReportFailure] data. Raw receipt fields leave only at this
         * delivery-to-report boundary.
         */
        fun fromAdmitted(
            expected: Kvp020PredecessorReceiptId,
            receipt: AdmittedProofReceipt,
        ): Kvp020PredecessorRefinement = if (
            receipt.receiptId.value == expected.receiptId &&
            receipt.taskId.value == expected.taskId &&
            receipt.gateId.value == expected.gateId
        ) Kvp020PredecessorRefinement.Admitted(
            Kvp020PredecessorReceipt(expected, receipt.digest.value),
        ) else predecessorRejected()
    }
}

internal sealed interface Kvp020PredecessorRefinement {
    data class Admitted(val receipt: Kvp020PredecessorReceipt) : Kvp020PredecessorRefinement
    data class Rejected(val failure: Kvp020SingleFlightReportFailure) :
        Kvp020PredecessorRefinement
}

internal class Kvp020ReportPredecessors private constructor(
    private val receipts: List<Kvp020PredecessorReceipt>,
) {
    internal fun documents() = receipts.map { Kvp020PredecessorDocument(it.id, it.digest) }
    internal fun digestMap() = receipts.associate { it.id.receiptId to it.digest }

    companion object {
        /**
         * Proof transition: `List<Kvp020PredecessorReceipt> ->
         * Kvp020PredecessorSetRefinement`.
         *
         * Establishes exactly KVP-014 then KVP-019 completion evidence.
         */
        fun refine(receipts: List<Kvp020PredecessorReceipt>): Kvp020PredecessorSetRefinement {
            val ordered = receipts.sortedBy { it.id.ordinal }
            return if (ordered.map { it.id } == Kvp020PredecessorReceiptId.entries &&
                receipts.map { it.id }.toSet().size == Kvp020PredecessorReceiptId.entries.size
            ) Kvp020PredecessorSetRefinement.Admitted(Kvp020ReportPredecessors(ordered))
            else Kvp020PredecessorSetRefinement.Rejected(
                Kvp020SingleFlightReportFailure.PREDECESSOR_SET_MISMATCH,
            )
        }
    }
}

internal sealed interface Kvp020PredecessorSetRefinement {
    data class Admitted(val predecessors: Kvp020ReportPredecessors) :
        Kvp020PredecessorSetRefinement
    data class Rejected(val failure: Kvp020SingleFlightReportFailure) :
        Kvp020PredecessorSetRefinement
}

internal enum class Kvp020SingleFlightReportFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    SCHEMA_MISMATCH,
    IDENTITY_MISMATCH,
    SCOPE_EVIDENCE_SET_MISMATCH,
    STATE_SET_MISMATCH,
    TRANSITION_SET_MISMATCH,
    ACTIVE_LIMIT_MISMATCH,
    QUEUE_LIMIT_MISMATCH,
    ADMISSION_CASE_SET_MISMATCH,
    RETIREMENT_CAUSE_SET_MISMATCH,
    CANCELLATION_CAUSE_SET_MISMATCH,
    TERMINALIZATION_LIMIT_MISMATCH,
    PROMOTION_LIMIT_MISMATCH,
    FRESHNESS_OBSERVATION_MISMATCH,
    SEMANTIC_EXECUTION_MISMATCH,
    RETAINED_EVIDENCE_SET_MISMATCH,
    FORBIDDEN_WORK_MISMATCH,
    FORBIDDEN_RETENTION_MISMATCH,
    PREDECESSOR_SET_MISMATCH,
    PREDECESSOR_RECEIPT_REJECTED,
}

internal class AdmittedKvp020SingleFlightReport private constructor(
    val canonicalDocument: String,
    val authority: String,
    val publicInterface: String,
    val effect: String,
    val stateCount: Int,
    val activePermitLimit: Int,
    val queuedRequestLimit: Int,
    val terminalizationLimitPerAuthority: Int,
    val promotionLimitPerActiveTerminalization: Int,
    val freshnessObservationCount: Int,
    val semanticExecutionCount: Int,
    val observedForbiddenWorkCount: Int,
    val observedForbiddenRetentionCount: Int,
) {
    companion object {
        /**
         * Proof transition: `(String, Kvp020ReportPredecessors) ->
         * Kvp020SingleFlightReportAdmission`.
         *
         * Establishes canonical bytes for bounded project-local admission, exact lifecycle causes,
         * retained freshness proof, zero hidden effects, and both predecessor digests. Expected
         * failures remain closed [Kvp020SingleFlightReportFailure] data. Raw JSON leaves only at
         * this outer Gradle receipt boundary.
         */
        fun admit(
            raw: String,
            predecessors: Kvp020ReportPredecessors,
        ): Kvp020SingleFlightReportAdmission {
            val document = try {
                KVP020_JSON.decodeFromString(Kvp020SingleFlightDocument.serializer(), raw)
            } catch (_: SerializationException) {
                return reportRejected(Kvp020SingleFlightReportFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return reportRejected(Kvp020SingleFlightReportFailure.MALFORMED_DOCUMENT)
            }
            when {
                document.schemaVersion != KVP020_SCHEMA_VERSION -> return reportRejected(
                    Kvp020SingleFlightReportFailure.SCHEMA_MISMATCH,
                )
                document.taskId != "KVP-020" ||
                    document.authority != Kvp020ReportAuthority.READ_RUNTIME ||
                    document.publicInterface != Kvp020PublicInterface.ProjectReadPermit ||
                    document.effect != Kvp020ReportEffect.PURE_STATE_TRANSITIONS ->
                    return reportRejected(Kvp020SingleFlightReportFailure.IDENTITY_MISMATCH)
                document.scopeEvidence != Kvp020ScopeEvidence.entries ->
                    return reportRejected(Kvp020SingleFlightReportFailure.SCOPE_EVIDENCE_SET_MISMATCH)
                document.states != Kvp020SingleFlightState.entries ->
                    return reportRejected(Kvp020SingleFlightReportFailure.STATE_SET_MISMATCH)
                document.transitions != canonicalKvp020SingleFlightTransitions() ->
                    return reportRejected(Kvp020SingleFlightReportFailure.TRANSITION_SET_MISMATCH)
                document.activePermitLimit != 1 ->
                    return reportRejected(Kvp020SingleFlightReportFailure.ACTIVE_LIMIT_MISMATCH)
                document.queuedRequestLimit != 1 ->
                    return reportRejected(Kvp020SingleFlightReportFailure.QUEUE_LIMIT_MISMATCH)
                document.admissionCases != Kvp020AdmissionCase.entries ->
                    return reportRejected(Kvp020SingleFlightReportFailure.ADMISSION_CASE_SET_MISMATCH)
                document.retirementCauses != Kvp020RetirementCause.entries ->
                    return reportRejected(Kvp020SingleFlightReportFailure.RETIREMENT_CAUSE_SET_MISMATCH)
                document.cancellationCauses != Kvp020CancellationCause.entries ->
                    return reportRejected(Kvp020SingleFlightReportFailure.CANCELLATION_CAUSE_SET_MISMATCH)
                document.terminalizationLimitPerAuthority != 1 ->
                    return reportRejected(Kvp020SingleFlightReportFailure.TERMINALIZATION_LIMIT_MISMATCH)
                document.promotionLimitPerActiveTerminalization != 1 ->
                    return reportRejected(Kvp020SingleFlightReportFailure.PROMOTION_LIMIT_MISMATCH)
                document.freshnessObservationCount != 0 ->
                    return reportRejected(Kvp020SingleFlightReportFailure.FRESHNESS_OBSERVATION_MISMATCH)
                document.semanticExecutionCount != 0 ->
                    return reportRejected(Kvp020SingleFlightReportFailure.SEMANTIC_EXECUTION_MISMATCH)
                document.retainedCapabilityEvidence != Kvp020RetainedEvidence.entries ->
                    return reportRejected(Kvp020SingleFlightReportFailure.RETAINED_EVIDENCE_SET_MISMATCH)
                document.forbiddenWork != canonicalForbiddenWork() ->
                    return reportRejected(Kvp020SingleFlightReportFailure.FORBIDDEN_WORK_MISMATCH)
                document.forbiddenRetention != canonicalForbiddenRetention() ->
                    return reportRejected(Kvp020SingleFlightReportFailure.FORBIDDEN_RETENTION_MISMATCH)
                document.predecessorReceipts != predecessors.documents() ->
                    return reportRejected(Kvp020SingleFlightReportFailure.PREDECESSOR_SET_MISMATCH)
            }
            val canonical = encodeKvp020(document)
            if (raw != canonical) return reportRejected(
                Kvp020SingleFlightReportFailure.NON_CANONICAL_DOCUMENT,
            )
            return Kvp020SingleFlightReportAdmission.Admitted(
                AdmittedKvp020SingleFlightReport(
                    canonical,
                    document.authority.name,
                    document.publicInterface.name,
                    document.effect.name,
                    document.states.size,
                    document.activePermitLimit,
                    document.queuedRequestLimit,
                    document.terminalizationLimitPerAuthority,
                    document.promotionLimitPerActiveTerminalization,
                    document.freshnessObservationCount,
                    document.semanticExecutionCount,
                    document.forbiddenWork.sumOf(Kvp020ForbiddenWorkDocument::observedCount),
                    document.forbiddenRetention.sumOf(
                        Kvp020ForbiddenRetentionDocument::observedCount,
                    ),
                ),
            )
        }
    }
}

internal sealed interface Kvp020SingleFlightReportAdmission {
    data class Admitted(val report: AdmittedKvp020SingleFlightReport) :
        Kvp020SingleFlightReportAdmission
    data class Rejected(val failure: Kvp020SingleFlightReportFailure) :
        Kvp020SingleFlightReportAdmission
}

internal fun canonicalKvp020SingleFlightReport(
    predecessors: Kvp020ReportPredecessors,
): String = encodeKvp020(Kvp020SingleFlightDocument(
    schemaVersion = KVP020_SCHEMA_VERSION,
    taskId = "KVP-020",
    authority = Kvp020ReportAuthority.READ_RUNTIME,
    publicInterface = Kvp020PublicInterface.ProjectReadPermit,
    effect = Kvp020ReportEffect.PURE_STATE_TRANSITIONS,
    scopeEvidence = Kvp020ScopeEvidence.entries,
    states = Kvp020SingleFlightState.entries,
    transitions = canonicalKvp020SingleFlightTransitions(),
    activePermitLimit = 1,
    queuedRequestLimit = 1,
    admissionCases = Kvp020AdmissionCase.entries,
    retirementCauses = Kvp020RetirementCause.entries,
    cancellationCauses = Kvp020CancellationCause.entries,
    terminalizationLimitPerAuthority = 1,
    promotionLimitPerActiveTerminalization = 1,
    freshnessObservationCount = 0,
    semanticExecutionCount = 0,
    retainedCapabilityEvidence = Kvp020RetainedEvidence.entries,
    forbiddenWork = canonicalForbiddenWork(),
    forbiddenRetention = canonicalForbiddenRetention(),
    predecessorReceipts = predecessors.documents(),
))

/**
 * Proof transition: two raw completion receipt documents ->
 * `Kvp020PredecessorSetRefinement`.
 *
 * Establishes the exact KVP-014/KVP-019 completion pair. Malformed, forged, missing, or mismatched
 * members remain closed [Kvp020SingleFlightReportFailure] data. Raw JSON extraction is permitted
 * only when Gradle report tasks call this boundary.
 */
internal fun refineKvp020ReportPredecessors(
    kvp014: String,
    kvp019: String,
): Kvp020PredecessorSetRefinement {
    val project = when (val result = Kvp020PredecessorReceipt.decode(
        Kvp020PredecessorReceiptId.KVP_014_COMPLETE, kvp014,
    )) {
        is Kvp020PredecessorRefinement.Admitted -> result.receipt
        is Kvp020PredecessorRefinement.Rejected ->
            return Kvp020PredecessorSetRefinement.Rejected(result.failure)
    }
    val freshness = when (val result = Kvp020PredecessorReceipt.decode(
        Kvp020PredecessorReceiptId.KVP_019_COMPLETE, kvp019,
    )) {
        is Kvp020PredecessorRefinement.Admitted -> result.receipt
        is Kvp020PredecessorRefinement.Rejected ->
            return Kvp020PredecessorSetRefinement.Rejected(result.failure)
    }
    return Kvp020ReportPredecessors.refine(listOf(project, freshness))
}

private fun canonicalForbiddenWork() = Kvp020ForbiddenWork.entries.map {
    Kvp020ForbiddenWorkDocument(it, 0)
}
private fun canonicalForbiddenRetention() = Kvp020ForbiddenRetention.entries.map {
    Kvp020ForbiddenRetentionDocument(it, 0)
}
private fun encodeKvp020(document: Kvp020SingleFlightDocument) =
    KVP020_JSON.encodeToString(Kvp020SingleFlightDocument.serializer(), document) + "\n"

private fun predecessorRejected() = Kvp020PredecessorRefinement.Rejected(
    Kvp020SingleFlightReportFailure.PREDECESSOR_RECEIPT_REJECTED,
)

private fun reportRejected(failure: Kvp020SingleFlightReportFailure) =
    Kvp020SingleFlightReportAdmission.Rejected(failure)

private val Kvp020PredecessorReceiptId.receiptId: String
    get() = when (this) {
        Kvp020PredecessorReceiptId.KVP_014_COMPLETE -> "KVP-014-COMPLETE"
        Kvp020PredecessorReceiptId.KVP_019_COMPLETE -> "KVP-019-COMPLETE"
    }
private val Kvp020PredecessorReceiptId.taskId: String get() = receiptId.removeSuffix("-COMPLETE")
private val Kvp020PredecessorReceiptId.gateId: String get() = "$taskId-COMPLETE-GATE"

private const val KVP020_SCHEMA_VERSION = 1
private val KVP020_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = true
    prettyPrintIndent = "    "
}
