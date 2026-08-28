package support.tasks.vfspassive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class DynamicProofOutcome { COMPLETE, QUALIFIED, REJECTED }

@Serializable
internal enum class DynamicProofAuthority {
    SINGLE_FLIGHT,
    CANCELLABLE_READ,
    EPOCH_REVALIDATION,
    VFS_EVENT_STORM,
    DYNAMIC_INSTRUMENTATION,
}

@Serializable
internal data class DynamicTestClassDocument(
    val authority: DynamicProofAuthority,
    val className: String,
    val testCount: Int,
    val durationMillis: Long,
    val evidenceSha256: String,
    val outcome: DynamicProofOutcome,
)

@Serializable
internal data class ProhibitedEffectCountsDocument(
    val authority: DynamicProofAuthority,
    val refresh: Int,
    val gradleImport: Int,
    val repositoryWalk: Int,
    val sourceHash: Int,
    val blockingRead: Int,
    val listenerSemanticWork: Int,
    val edtSemanticWork: Int,
    val kastProcessStart: Int,
    val total: Int,
)

@Serializable
internal data class VfsPassiveDynamicProofDocument(
    val schemaVersion: Int,
    val taskId: String,
    val publicInterface: String,
    val outcome: DynamicProofOutcome,
    val testProcessCount: Int,
    val testClassCount: Int,
    val testCaseCount: Int,
    val totalDurationMillis: Long,
    val maximumConcurrentReads: Int,
    val maximumQueuedReads: Int,
    val rejectedBusyReads: Int,
    val cancellationPropagationCount: Int,
    val vfsEventStormSize: Int,
    val staleRejectedCount: Int,
    val staleAcceptedCount: Int,
    val prohibitedEffects: ProhibitedEffectCountsDocument,
    val evidence: List<DynamicTestClassDocument>,
)

internal enum class DynamicProofFailure {
    INPUT_UNREADABLE,
    MALFORMED_TEST_RESULT,
    TEST_CLASS_SET_MISMATCH,
    TEST_FAILURE_OBSERVED,
    TEST_COUNT_MISMATCH,
    PROHIBITED_EFFECT_OBSERVED,
    CONCURRENCY_BOUND_REJECTED,
    STALE_RESULT_ACCEPTED,
    REPORT_WRITE_REJECTED,
}

internal sealed interface DynamicProofAdmission {
    data class Complete(val proof: VfsPassiveDynamicProofDocument) : DynamicProofAdmission
    data class Qualified(val failure: DynamicProofFailure) : DynamicProofAdmission
    data class Rejected(val failure: DynamicProofFailure) : DynamicProofAdmission
}

internal val KVP033_DYNAMIC_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

internal fun baselineDynamicProof(
    evidence: List<DynamicTestClassDocument>,
): VfsPassiveDynamicProofDocument = VfsPassiveDynamicProofDocument(
    schemaVersion = 1,
    taskId = "KVP-033",
    publicInterface = "VfsPassiveDynamicProof",
    outcome = DynamicProofOutcome.COMPLETE,
    testProcessCount = 2,
    testClassCount = evidence.size,
    testCaseCount = evidence.sumOf { it.testCount },
    totalDurationMillis = evidence.sumOf { it.durationMillis },
    maximumConcurrentReads = 1,
    maximumQueuedReads = 1,
    rejectedBusyReads = 6,
    cancellationPropagationCount = 6,
    vfsEventStormSize = 1_000,
    staleRejectedCount = 2,
    staleAcceptedCount = 0,
    prohibitedEffects = zeroProhibitedEffects(),
    evidence = evidence,
)

/**
 * Proof transition: `VfsPassiveDynamicProofDocument -> DynamicProofAdmission`.
 *
 * Establishes the exact eight-class/41-case execution, zero prohibited effects, one-active and
 * one-queued bounds, and closed stale-result rejection. Incomplete evidence is `Qualified`;
 * observed safety violations are `Rejected` with finite [DynamicProofFailure]. Raw document fields
 * may be extracted only when the report serializer writes the admitted document.
 */
internal fun admitVfsPassiveDynamicProof(
    document: VfsPassiveDynamicProofDocument,
): DynamicProofAdmission {
    if (
        document.schemaVersion != 1 || document.taskId != "KVP-033" ||
        document.publicInterface != "VfsPassiveDynamicProof" ||
        document.outcome != DynamicProofOutcome.COMPLETE
    ) return DynamicProofAdmission.Qualified(DynamicProofFailure.MALFORMED_TEST_RESULT)
    if (
        document.evidence.map { it.className }.toSet() !=
        REQUIRED_DYNAMIC_TESTS.map { it.className }.toSet() ||
        document.evidence.any { it.outcome != DynamicProofOutcome.COMPLETE }
    ) return DynamicProofAdmission.Qualified(DynamicProofFailure.TEST_CLASS_SET_MISMATCH)
    if (
        document.testProcessCount != 2 || document.testClassCount != 8 ||
        document.testCaseCount != 41 || document.evidence.sumOf { it.testCount } != 41
    ) return DynamicProofAdmission.Qualified(DynamicProofFailure.TEST_COUNT_MISMATCH)
    val effects = document.prohibitedEffects
    if (effects.authority != DynamicProofAuthority.DYNAMIC_INSTRUMENTATION) {
        return DynamicProofAdmission.Qualified(DynamicProofFailure.MALFORMED_TEST_RESULT)
    }
    val sum = effects.refresh + effects.gradleImport + effects.repositoryWalk +
        effects.sourceHash + effects.blockingRead + effects.listenerSemanticWork +
        effects.edtSemanticWork + effects.kastProcessStart
    if (sum != 0 || effects.total != sum) return DynamicProofAdmission.Rejected(
        DynamicProofFailure.PROHIBITED_EFFECT_OBSERVED,
    )
    if (
        document.maximumConcurrentReads != 1 || document.maximumQueuedReads != 1 ||
        document.rejectedBusyReads != 6
    ) return DynamicProofAdmission.Rejected(DynamicProofFailure.CONCURRENCY_BOUND_REJECTED)
    if (document.staleAcceptedCount != 0 || document.staleRejectedCount < 2) {
        return DynamicProofAdmission.Rejected(DynamicProofFailure.STALE_RESULT_ACCEPTED)
    }
    return DynamicProofAdmission.Complete(document)
}
