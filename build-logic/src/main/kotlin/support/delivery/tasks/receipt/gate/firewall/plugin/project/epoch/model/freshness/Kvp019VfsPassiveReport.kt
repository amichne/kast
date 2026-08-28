package support.delivery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp019VfsPassiveDocument(
    val schemaVersion: Int,
    val taskId: String,
    val authority: Kvp019ReportAuthority,
    val publicInterface: Kvp019PublicInterface,
    val admissionMode: Kvp019AdmissionMode,
    val admissionCases: List<Kvp019AdmissionCase>,
    val freshnessObservationCountPerAdmission: Int,
    val retainedCapabilityEvidence: List<Kvp019RetainedEvidence>,
    val unavailableObservationFailures: List<Kvp019UnavailableObservationFailure>,
    val unavailableObservationFailureCount: Int,
    val observationFailureStages: List<Kvp019ObservationFailureStage>,
    val forbiddenWork: List<Kvp019ForbiddenWorkDocument>,
    val predecessorReceipts: List<Kvp019PredecessorDocument>,
)

@Serializable
private data class Kvp019ForbiddenWorkDocument(
    val kind: Kvp019ForbiddenWork,
    val observedCount: Int,
)

@Serializable
internal data class Kvp019PredecessorDocument(
    val receiptId: Kvp019PredecessorReceiptId,
    val sha256: String,
)

@Serializable private enum class Kvp019ReportAuthority { READ_EPOCH }
@Serializable private enum class Kvp019PublicInterface { VfsPassiveReadCapability }
@Serializable private enum class Kvp019AdmissionMode { IDE_SNAPSHOT_ONLY }

@Serializable
private enum class Kvp019AdmissionCase {
    ADMITTED_SAME_SOURCE_EQUAL_STATE,
    REJECTED_MOVED_STATE,
    REJECTED_INCOMPARABLE_SOURCE,
    REJECTED_PROJECT_DISPOSED,
    REJECTED_DUMB_MODE,
    REJECTED_UNAVAILABLE_OBSERVATION,
    PROPAGATED_PLATFORM_CANCELLATION,
}

@Serializable
private enum class Kvp019RetainedEvidence { CANONICAL_ROOT, ADMITTED_EPOCH }

@Serializable
private enum class Kvp019UnavailableObservationFailure {
    WRONG_THREAD,
    PROJECT_NOT_OPEN,
    PROJECT_NOT_INITIALIZED,
    PROJECT_ROOT_UNAVAILABLE,
    PROJECT_ROOT_MALFORMED,
    GRADLE_MODEL_UNAVAILABLE,
    GRADLE_MODEL_INCOMPLETE,
    GRADLE_MODEL_AMBIGUOUS,
    GRADLE_ROOT_UNAVAILABLE,
    GRADLE_ROOT_MALFORMED,
    IMPORT_TIMESTAMPS_INCOHERENT,
    VFS_BATCH_LIMIT_EXCEEDED,
    VFS_PATH_MALFORMED,
    SIGNAL_EXHAUSTED,
    READ_PREEMPTED,
    OBSERVATION_FAILED,
}

@Serializable
private enum class Kvp019ObservationFailureStage {
    THREAD,
    DISPOSAL,
    OPEN,
    INITIALIZATION,
    PROJECT_ROOT,
    PROJECT_MODEL,
    PSI,
    VFS,
    ROOT_MODEL,
    DUMB_MODE,
}

@Serializable
private enum class Kvp019ForbiddenWork {
    VFS_REFRESH,
    GRADLE_IMPORT,
    BACKGROUND_REPAIR,
    PER_EVENT_SEMANTIC_JOB,
    EVENT_TRIGGERED_SEMANTIC_WORK,
}

@Serializable
internal enum class Kvp019PredecessorReceiptId {
    @SerialName("KVP-017-COMPLETE") KVP_017_COMPLETE,
    @SerialName("KVP-018-COMPLETE") KVP_018_COMPLETE,
}

internal class Kvp019PredecessorReceipt private constructor(
    val id: Kvp019PredecessorReceiptId,
    val digest: String,
) {
    companion object {
        /**
         * Proof transition: `(Kvp019PredecessorReceiptId, String) ->
         * Kvp019PredecessorRefinement`.
         *
         * Establishes an exact completion identity and self-derived semantic digest. Malformed or
         * mismatched receipt bytes remain closed [Kvp019ReportFailure] data; raw JSON enters only
         * at this Gradle report boundary.
         */
        fun decode(
            expected: Kvp019PredecessorReceiptId,
            raw: String,
        ): Kvp019PredecessorRefinement = when (val decoded = decodeProofReceiptDocument(raw)) {
            is ProofReceiptDocumentResult.Rejected -> rejected(
                Kvp019ReportFailure.PREDECESSOR_RECEIPT_REJECTED,
            )
            is ProofReceiptDocumentResult.Complete -> {
                val document = decoded.document
                if (document.receiptId.value != expected.receiptId ||
                    document.taskId.value != expected.taskId ||
                    document.gateId.value != expected.gateId ||
                    document.receiptDigest != document.derivedDigest()
                ) rejected(Kvp019ReportFailure.PREDECESSOR_RECEIPT_REJECTED)
                else Kvp019PredecessorRefinement.Admitted(
                    Kvp019PredecessorReceipt(expected, document.receiptDigest.value),
                )
            }
        }

        /**
         * Proof transition: `(Kvp019PredecessorReceiptId, AdmittedProofReceipt) ->
         * Kvp019PredecessorRefinement`.
         *
         * Preserves the semantic digest only for the exact required completion task/gate
         * identity. Identity mismatch remains closed [Kvp019ReportFailure] data. Raw receipt
         * fields are extracted only at this delivery-to-report boundary.
         */
        fun fromAdmitted(
            expected: Kvp019PredecessorReceiptId,
            receipt: AdmittedProofReceipt,
        ): Kvp019PredecessorRefinement = if (
            receipt.receiptId.value == expected.receiptId &&
            receipt.taskId.value == expected.taskId &&
            receipt.gateId.value == expected.gateId
        ) Kvp019PredecessorRefinement.Admitted(
            Kvp019PredecessorReceipt(expected, receipt.digest.value),
        ) else rejected(Kvp019ReportFailure.PREDECESSOR_RECEIPT_REJECTED)
    }
}

internal sealed interface Kvp019PredecessorRefinement {
    data class Admitted(val receipt: Kvp019PredecessorReceipt) : Kvp019PredecessorRefinement
    data class Rejected(val failure: Kvp019ReportFailure) : Kvp019PredecessorRefinement
}

internal class Kvp019ReportPredecessors private constructor(
    private val receipts: List<Kvp019PredecessorReceipt>,
) {
    internal fun documents() = receipts.map { Kvp019PredecessorDocument(it.id, it.digest) }
    internal fun digestMap() = receipts.associate { it.id.receiptId to it.digest }

    companion object {
        /**
         * Proof transition: `List<Kvp019PredecessorReceipt> -> Kvp019PredecessorSetRefinement`.
         * Establishes exactly KVP-017 and KVP-018 completion evidence in canonical order.
         */
        fun refine(receipts: List<Kvp019PredecessorReceipt>): Kvp019PredecessorSetRefinement {
            val ordered = receipts.sortedBy { it.id.ordinal }
            return if (ordered.map { it.id } == Kvp019PredecessorReceiptId.entries &&
                receipts.map { it.id }.toSet().size == Kvp019PredecessorReceiptId.entries.size
            ) Kvp019PredecessorSetRefinement.Admitted(Kvp019ReportPredecessors(ordered))
            else Kvp019PredecessorSetRefinement.Rejected(
                Kvp019ReportFailure.PREDECESSOR_SET_MISMATCH,
            )
        }
    }
}

internal sealed interface Kvp019PredecessorSetRefinement {
    data class Admitted(val predecessors: Kvp019ReportPredecessors) :
        Kvp019PredecessorSetRefinement
    data class Rejected(val failure: Kvp019ReportFailure) : Kvp019PredecessorSetRefinement
}

internal enum class Kvp019ReportFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    SCHEMA_MISMATCH,
    IDENTITY_MISMATCH,
    ADMISSION_CASE_SET_MISMATCH,
    RETAINED_EVIDENCE_SET_MISMATCH,
    OBSERVATION_COUNT_MISMATCH,
    UNAVAILABLE_FAILURE_SET_MISMATCH,
    UNAVAILABLE_FAILURE_COUNT_MISMATCH,
    OBSERVATION_STAGE_SET_MISMATCH,
    FORBIDDEN_WORK_MISMATCH,
    PREDECESSOR_SET_MISMATCH,
    PREDECESSOR_RECEIPT_REJECTED,
}

internal class AdmittedKvp019VfsPassiveReport private constructor(
    val canonicalDocument: String,
    val authority: String,
    val publicInterface: String,
    val admissionMode: String,
    val admissionCaseCount: Int,
    val freshnessObservationCountPerAdmission: Int,
    val retainedCapabilityEvidenceCount: Int,
    val unavailableObservationFailureCount: Int,
    val observationFailureStageCount: Int,
    val forbiddenWorkKindCount: Int,
    val observedForbiddenWorkCount: Int,
) {
    companion object {
        /**
         * Proof transition: `(String, Kvp019ReportPredecessors) -> Kvp019ReportAdmission`.
         *
         * Establishes canonical generated bytes for one VFS-passive snapshot admission, its exact
         * closed cases, retained root/epoch evidence, zero forbidden work, and both semantic
         * predecessor digests. Expected failures remain closed [Kvp019ReportFailure] data; raw
         * JSON may leave only at this outer Gradle receipt boundary.
         */
        fun admit(
            raw: String,
            predecessors: Kvp019ReportPredecessors,
        ): Kvp019ReportAdmission {
            val document = try {
                KVP019_JSON.decodeFromString(Kvp019VfsPassiveDocument.serializer(), raw)
            } catch (_: SerializationException) {
                return reportRejected(Kvp019ReportFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return reportRejected(Kvp019ReportFailure.MALFORMED_DOCUMENT)
            }
            when {
                document.schemaVersion != KVP019_SCHEMA_VERSION ->
                    return reportRejected(Kvp019ReportFailure.SCHEMA_MISMATCH)
                document.taskId != "KVP-019" ||
                    document.authority != Kvp019ReportAuthority.READ_EPOCH ||
                    document.publicInterface != Kvp019PublicInterface.VfsPassiveReadCapability ||
                    document.admissionMode != Kvp019AdmissionMode.IDE_SNAPSHOT_ONLY ->
                    return reportRejected(Kvp019ReportFailure.IDENTITY_MISMATCH)
                document.admissionCases != Kvp019AdmissionCase.entries ->
                    return reportRejected(Kvp019ReportFailure.ADMISSION_CASE_SET_MISMATCH)
                document.retainedCapabilityEvidence != Kvp019RetainedEvidence.entries ->
                    return reportRejected(Kvp019ReportFailure.RETAINED_EVIDENCE_SET_MISMATCH)
                document.freshnessObservationCountPerAdmission != 1 ->
                    return reportRejected(Kvp019ReportFailure.OBSERVATION_COUNT_MISMATCH)
                document.unavailableObservationFailures !=
                    Kvp019UnavailableObservationFailure.entries ->
                    return reportRejected(Kvp019ReportFailure.UNAVAILABLE_FAILURE_SET_MISMATCH)
                document.unavailableObservationFailureCount !=
                    document.unavailableObservationFailures.size ->
                    return reportRejected(Kvp019ReportFailure.UNAVAILABLE_FAILURE_COUNT_MISMATCH)
                document.observationFailureStages != Kvp019ObservationFailureStage.entries ->
                    return reportRejected(Kvp019ReportFailure.OBSERVATION_STAGE_SET_MISMATCH)
                document.forbiddenWork != canonicalForbiddenWork() ->
                    return reportRejected(Kvp019ReportFailure.FORBIDDEN_WORK_MISMATCH)
                document.predecessorReceipts != predecessors.documents() ->
                    return reportRejected(Kvp019ReportFailure.PREDECESSOR_SET_MISMATCH)
                else -> Unit
            }
            val canonical = encode(document)
            if (raw != canonical) return reportRejected(
                Kvp019ReportFailure.NON_CANONICAL_DOCUMENT,
            )
            return Kvp019ReportAdmission.Admitted(AdmittedKvp019VfsPassiveReport(
                canonical,
                document.authority.name,
                document.publicInterface.name,
                document.admissionMode.name,
                document.admissionCases.size,
                document.freshnessObservationCountPerAdmission,
                document.retainedCapabilityEvidence.size,
                document.unavailableObservationFailureCount,
                document.observationFailureStages.size,
                document.forbiddenWork.size,
                document.forbiddenWork.sumOf(Kvp019ForbiddenWorkDocument::observedCount),
            ))
        }
    }
}

internal sealed interface Kvp019ReportAdmission {
    data class Admitted(val report: AdmittedKvp019VfsPassiveReport) : Kvp019ReportAdmission
    data class Rejected(val failure: Kvp019ReportFailure) : Kvp019ReportAdmission
}

internal fun canonicalKvp019VfsPassiveReport(
    predecessors: Kvp019ReportPredecessors,
): String = encode(Kvp019VfsPassiveDocument(
    schemaVersion = KVP019_SCHEMA_VERSION,
    taskId = "KVP-019",
    authority = Kvp019ReportAuthority.READ_EPOCH,
    publicInterface = Kvp019PublicInterface.VfsPassiveReadCapability,
    admissionMode = Kvp019AdmissionMode.IDE_SNAPSHOT_ONLY,
    admissionCases = Kvp019AdmissionCase.entries,
    freshnessObservationCountPerAdmission = 1,
    retainedCapabilityEvidence = Kvp019RetainedEvidence.entries,
    unavailableObservationFailures = Kvp019UnavailableObservationFailure.entries,
    unavailableObservationFailureCount = Kvp019UnavailableObservationFailure.entries.size,
    observationFailureStages = Kvp019ObservationFailureStage.entries,
    forbiddenWork = canonicalForbiddenWork(),
    predecessorReceipts = predecessors.documents(),
))

/**
 * Proof transition: two raw completion receipt documents ->
 * `Kvp019PredecessorSetRefinement`.
 *
 * Establishes the exact semantic KVP-017/KVP-018 completion pair. Malformed, forged, missing, or
 * mismatched members remain closed [Kvp019ReportFailure] data; raw JSON extraction is permitted
 * only when Gradle report tasks call this boundary.
 */
internal fun refineKvp019ReportPredecessors(
    kvp017: String,
    kvp018: String,
): Kvp019PredecessorSetRefinement {
    val readEpoch = when (val result = Kvp019PredecessorReceipt.decode(
        Kvp019PredecessorReceiptId.KVP_017_COMPLETE,
        kvp017,
    )) {
        is Kvp019PredecessorRefinement.Admitted -> result.receipt
        is Kvp019PredecessorRefinement.Rejected -> return Kvp019PredecessorSetRefinement.Rejected(
            result.failure,
        )
    }
    val hosted = when (val result = Kvp019PredecessorReceipt.decode(
        Kvp019PredecessorReceiptId.KVP_018_COMPLETE,
        kvp018,
    )) {
        is Kvp019PredecessorRefinement.Admitted -> result.receipt
        is Kvp019PredecessorRefinement.Rejected -> return Kvp019PredecessorSetRefinement.Rejected(
            result.failure,
        )
    }
    return Kvp019ReportPredecessors.refine(listOf(readEpoch, hosted))
}

private fun canonicalForbiddenWork() = Kvp019ForbiddenWork.entries.map {
    Kvp019ForbiddenWorkDocument(it, 0)
}

private fun encode(document: Kvp019VfsPassiveDocument) =
    KVP019_JSON.encodeToString(Kvp019VfsPassiveDocument.serializer(), document) + "\n"

private fun rejected(failure: Kvp019ReportFailure) =
    Kvp019PredecessorRefinement.Rejected(failure)

private fun reportRejected(failure: Kvp019ReportFailure) =
    Kvp019ReportAdmission.Rejected(failure)

private val Kvp019PredecessorReceiptId.receiptId: String
    get() = when (this) {
        Kvp019PredecessorReceiptId.KVP_017_COMPLETE -> "KVP-017-COMPLETE"
        Kvp019PredecessorReceiptId.KVP_018_COMPLETE -> "KVP-018-COMPLETE"
    }
private val Kvp019PredecessorReceiptId.taskId: String get() = receiptId.removeSuffix("-COMPLETE")
private val Kvp019PredecessorReceiptId.gateId: String get() = "$taskId-COMPLETE-GATE"

private const val KVP019_SCHEMA_VERSION = 1
private val KVP019_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = true
    prettyPrintIndent = "    "
}
