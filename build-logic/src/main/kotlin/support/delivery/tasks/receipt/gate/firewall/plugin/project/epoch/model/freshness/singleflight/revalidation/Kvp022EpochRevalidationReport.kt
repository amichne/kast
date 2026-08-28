package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp022EpochRevalidationDocument(
    val schemaVersion: Int,
    val taskId: String,
    val authorities: List<Kvp022ReportAuthority>,
    val publicInterface: Kvp022PublicInterface,
    val epochObservations: List<Kvp022EpochObservationDocument>,
    val relationDecisions: List<Kvp022RelationDecisionDocument>,
    val phaseFailures: List<Kvp022PhaseFailure>,
    val epochObservationCountPerCompletedRead: Int,
    val semanticExecutionLimitPerAttempt: Int,
    val retryCount: Int,
    val priorEpochReuseCount: Int,
    val forbiddenWork: List<Kvp022ForbiddenWorkDocument>,
    val predecessorReceipt: Kvp022PredecessorDocument,
)

@Serializable
private data class Kvp022EpochObservationDocument(
    val stage: Kvp022EpochObservationStage,
    val observedCount: Int,
)

@Serializable
private data class Kvp022RelationDecisionDocument(
    val relation: Kvp022EpochRelation,
    val decision: Kvp022RelationDecision,
)

@Serializable
private data class Kvp022ForbiddenWorkDocument(
    val kind: Kvp022ForbiddenWork,
    val observedCount: Int,
)

@Serializable private enum class Kvp022ReportAuthority { READ_EPOCH, READ_RUNTIME }
@Serializable private enum class Kvp022PublicInterface { RevalidatedIdeReadResult }
@Serializable private enum class Kvp022EpochObservationStage { BEFORE, AFTER }
@Serializable private enum class Kvp022EpochRelation { SAME, MOVED, INCOMPARABLE }
@Serializable private enum class Kvp022RelationDecision {
    COMPLETE,
    WORKSPACE_MOVED,
    EPOCH_INCOMPARABLE,
}

@Serializable private enum class Kvp022PhaseFailure {
    BEFORE_EPOCH_OBSERVATION_REJECTED,
    SEMANTIC_EXECUTION_REJECTED,
    AFTER_EPOCH_OBSERVATION_REJECTED,
}

@Serializable private enum class Kvp022ForbiddenWork {
    ACCEPTING_STALE_OUTPUT_WITH_WARNING,
    RETRY_LOOP_WITHOUT_BUDGET,
    REUSING_PRIOR_EPOCH_AFTER_CANCELLATION,
}

internal enum class Kvp022EpochRevalidationReportFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    SCHEMA_MISMATCH,
    IDENTITY_MISMATCH,
    AUTHORITY_SET_MISMATCH,
    EPOCH_OBSERVATION_MISMATCH,
    RELATION_DECISION_SET_MISMATCH,
    PHASE_FAILURE_SET_MISMATCH,
    EPOCH_OBSERVATION_COUNT_MISMATCH,
    SEMANTIC_EXECUTION_LIMIT_MISMATCH,
    RETRY_COUNT_MISMATCH,
    PRIOR_EPOCH_REUSE_COUNT_MISMATCH,
    FORBIDDEN_WORK_MISMATCH,
    PREDECESSOR_MISMATCH,
    PREDECESSOR_RECEIPT_REJECTED,
}

internal class AdmittedKvp022EpochRevalidationReport private constructor(
    val canonicalDocument: String,
    val authorities: String,
    val publicInterface: String,
    val beforeObservationCount: Int,
    val afterObservationCount: Int,
    val epochObservationCountPerCompletedRead: Int,
    val relationDecisions: String,
    val relationDecisionCount: Int,
    val phaseFailures: String,
    val phaseFailureCount: Int,
    val semanticExecutionLimitPerAttempt: Int,
    val retryCount: Int,
    val priorEpochReuseCount: Int,
    val observedForbiddenWorkCount: Int,
) {
    companion object {
        /**
         * Proof transition: `(String, Kvp022ReportPredecessor) ->
         * Kvp022EpochRevalidationReportAdmission`.
         *
         * Establishes canonical KVP-022 BEFORE/AFTER observation, relation decision, phase
         * failure, bounded execution, and zero-forbidden-work claims bound to exact KVP-021
         * completion. Expected failures remain closed [Kvp022EpochRevalidationReportFailure]
         * data. Raw JSON is extracted only at Gradle report and receipt boundaries.
         */
        fun admit(
            raw: String,
            predecessor: Kvp022ReportPredecessor,
        ): Kvp022EpochRevalidationReportAdmission {
            val document = try {
                KVP022_REPORT_JSON.decodeFromString(
                    Kvp022EpochRevalidationDocument.serializer(),
                    raw,
                )
            } catch (_: SerializationException) {
                return rejected(Kvp022EpochRevalidationReportFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return rejected(Kvp022EpochRevalidationReportFailure.MALFORMED_DOCUMENT)
            }
            when {
                document.schemaVersion != KVP022_REPORT_SCHEMA_VERSION -> return rejected(
                    Kvp022EpochRevalidationReportFailure.SCHEMA_MISMATCH,
                )
                document.taskId != "KVP-022" ||
                    document.publicInterface != Kvp022PublicInterface.RevalidatedIdeReadResult ->
                    return rejected(Kvp022EpochRevalidationReportFailure.IDENTITY_MISMATCH)
                document.authorities != Kvp022ReportAuthority.entries -> return rejected(
                    Kvp022EpochRevalidationReportFailure.AUTHORITY_SET_MISMATCH,
                )
                document.epochObservations != canonicalKvp022EpochObservations() ->
                    return rejected(
                        Kvp022EpochRevalidationReportFailure.EPOCH_OBSERVATION_MISMATCH,
                    )
                document.relationDecisions != canonicalKvp022RelationDecisions() ->
                    return rejected(
                        Kvp022EpochRevalidationReportFailure.RELATION_DECISION_SET_MISMATCH,
                    )
                document.phaseFailures != Kvp022PhaseFailure.entries -> return rejected(
                    Kvp022EpochRevalidationReportFailure.PHASE_FAILURE_SET_MISMATCH,
                )
                document.epochObservationCountPerCompletedRead != 2 -> return rejected(
                    Kvp022EpochRevalidationReportFailure.EPOCH_OBSERVATION_COUNT_MISMATCH,
                )
                document.semanticExecutionLimitPerAttempt != 1 -> return rejected(
                    Kvp022EpochRevalidationReportFailure.SEMANTIC_EXECUTION_LIMIT_MISMATCH,
                )
                document.retryCount != 0 -> return rejected(
                    Kvp022EpochRevalidationReportFailure.RETRY_COUNT_MISMATCH,
                )
                document.priorEpochReuseCount != 0 -> return rejected(
                    Kvp022EpochRevalidationReportFailure.PRIOR_EPOCH_REUSE_COUNT_MISMATCH,
                )
                document.forbiddenWork != canonicalKvp022ForbiddenWork() -> return rejected(
                    Kvp022EpochRevalidationReportFailure.FORBIDDEN_WORK_MISMATCH,
                )
                document.predecessorReceipt != predecessor.document() -> return rejected(
                    Kvp022EpochRevalidationReportFailure.PREDECESSOR_MISMATCH,
                )
            }
            val canonical = encodeKvp022Report(document)
            if (raw != canonical) return rejected(
                Kvp022EpochRevalidationReportFailure.NON_CANONICAL_DOCUMENT,
            )
            val observations = document.epochObservations.associateBy { it.stage }
            return Kvp022EpochRevalidationReportAdmission.Admitted(
                AdmittedKvp022EpochRevalidationReport(
                    canonical,
                    document.authorities.joinToString(",") { it.name },
                    document.publicInterface.name,
                    observations.getValue(Kvp022EpochObservationStage.BEFORE).observedCount,
                    observations.getValue(Kvp022EpochObservationStage.AFTER).observedCount,
                    document.epochObservationCountPerCompletedRead,
                    document.relationDecisions.joinToString(",") {
                        "${it.relation.name}->${it.decision.name}"
                    },
                    document.relationDecisions.size,
                    document.phaseFailures.joinToString(",") { it.name },
                    document.phaseFailures.size,
                    document.semanticExecutionLimitPerAttempt,
                    document.retryCount,
                    document.priorEpochReuseCount,
                    document.forbiddenWork.sumOf(Kvp022ForbiddenWorkDocument::observedCount),
                ),
            )
        }
    }
}

internal sealed interface Kvp022EpochRevalidationReportAdmission {
    data class Admitted(val report: AdmittedKvp022EpochRevalidationReport) :
        Kvp022EpochRevalidationReportAdmission

    data class Rejected(val failure: Kvp022EpochRevalidationReportFailure) :
        Kvp022EpochRevalidationReportAdmission
}

internal fun canonicalKvp022EpochRevalidationReport(
    predecessor: Kvp022ReportPredecessor,
): String = encodeKvp022Report(
    Kvp022EpochRevalidationDocument(
        schemaVersion = KVP022_REPORT_SCHEMA_VERSION,
        taskId = "KVP-022",
        authorities = Kvp022ReportAuthority.entries,
        publicInterface = Kvp022PublicInterface.RevalidatedIdeReadResult,
        epochObservations = canonicalKvp022EpochObservations(),
        relationDecisions = canonicalKvp022RelationDecisions(),
        phaseFailures = Kvp022PhaseFailure.entries,
        epochObservationCountPerCompletedRead = 2,
        semanticExecutionLimitPerAttempt = 1,
        retryCount = 0,
        priorEpochReuseCount = 0,
        forbiddenWork = canonicalKvp022ForbiddenWork(),
        predecessorReceipt = predecessor.document(),
    ),
)

private fun canonicalKvp022EpochObservations() = Kvp022EpochObservationStage.entries.map {
    Kvp022EpochObservationDocument(it, 1)
}

private fun canonicalKvp022RelationDecisions() = listOf(
    Kvp022RelationDecisionDocument(Kvp022EpochRelation.SAME, Kvp022RelationDecision.COMPLETE),
    Kvp022RelationDecisionDocument(
        Kvp022EpochRelation.MOVED,
        Kvp022RelationDecision.WORKSPACE_MOVED,
    ),
    Kvp022RelationDecisionDocument(
        Kvp022EpochRelation.INCOMPARABLE,
        Kvp022RelationDecision.EPOCH_INCOMPARABLE,
    ),
)

private fun canonicalKvp022ForbiddenWork() = Kvp022ForbiddenWork.entries.map {
    Kvp022ForbiddenWorkDocument(it, 0)
}

private fun encodeKvp022Report(document: Kvp022EpochRevalidationDocument) =
    KVP022_REPORT_JSON.encodeToString(Kvp022EpochRevalidationDocument.serializer(), document) +
        "\n"

private fun rejected(failure: Kvp022EpochRevalidationReportFailure) =
    Kvp022EpochRevalidationReportAdmission.Rejected(failure)

private const val KVP022_REPORT_SCHEMA_VERSION = 1
private val KVP022_REPORT_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
