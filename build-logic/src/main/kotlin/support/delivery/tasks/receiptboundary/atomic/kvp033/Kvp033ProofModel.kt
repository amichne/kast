package support.delivery

import kotlinx.serialization.SerializationException
import org.gradle.util.GradleVersion
import support.tasks.vfspassive.DynamicNegativeEvidenceDocument
import support.tasks.vfspassive.DynamicProofAdmission
import support.tasks.vfspassive.DynamicProofOutcome
import support.tasks.vfspassive.KVP033_DYNAMIC_JSON
import support.tasks.vfspassive.VfsPassiveDynamicProofDocument
import support.tasks.vfspassive.admitVfsPassiveDynamicProof

internal data class Kvp033ProofCases(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<String>,
    val negativeFixtureCount: Int,
)

internal data class Kvp033ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp033Dependencies,
    val relevantInputDigest: RelevantInputDigest,
    val scope: AdmittedKvp033ImplementationScope,
    val cases: Kvp033ProofCases,
    val report: VfsPassiveDynamicProofDocument,
    val reportRaw: String,
    val observedHead: DeliveryGeneration,
)

internal sealed interface Kvp033CaseAdmission {
    data class Complete(val cases: Kvp033ProofCases) : Kvp033CaseAdmission
    data object Rejected : Kvp033CaseAdmission
}

internal sealed interface Kvp033NegativeEvidenceAdmission {
    data class Complete(val document: DynamicNegativeEvidenceDocument) :
        Kvp033NegativeEvidenceAdmission
    data object Rejected : Kvp033NegativeEvidenceAdmission
}

internal sealed interface Kvp033ReportAdmission {
    data class Complete(val document: VfsPassiveDynamicProofDocument) : Kvp033ReportAdmission
    data object Qualified : Kvp033ReportAdmission
    data object Rejected : Kvp033ReportAdmission
}

/**
 * Proof transition: `Kvp033ProofContext -> TaskProofReceiptExpectation`.
 *
 * Establishes program/task identity, four predecessor digests, relevant inputs, command/toolchain,
 * complete dynamic observations, report digest, and content head policy. The context is already
 * admitted, so refinement rejection is an internal defect; raw receipt fields exit only at the
 * shared receipt-issuance boundary.
 */
internal fun Kvp033ProofContext.receiptExpectation(): TaskProofReceiptExpectation {
    val outputPath = packet.packet.task.outputs.single().path
    val observations = linkedMapOf(
        "misuseOutcome" to "REJECTED",
        "legalPathOutcome" to "COMPLETE",
        "dynamicReportOutcome" to report.outcome.name,
        "writeScopeOutcome" to "COMPLETE",
        "implementationCommitCount" to scope.commitCount.toString(),
        "negativeFixtureCount" to cases.negativeFixtureCount.toString(),
        "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
        "predecessorReceiptCount" to dependencies.digests.size.toString(),
        "dynamicTestProcessCount" to report.testProcessCount.toString(),
        "dynamicTestCaseCount" to report.testCaseCount.toString(),
        "prohibitedEffectCount" to report.prohibitedEffects.total.toString(),
        "maximumConcurrentReads" to report.maximumConcurrentReads.toString(),
        "maximumQueuedReads" to report.maximumQueuedReads.toString(),
        "staleAcceptedCount" to report.staleAcceptedCount.toString(),
    )
    return when (val refined = TaskProofReceiptExpectation.refine(
        programVersion.value,
        packet.packet.receipt.receiptId.value,
        packet.packet.task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        relevantInputDigest.value,
        packet.packet.kvp033CommandDigest().value,
        currentKvp033ToolchainDigest().value,
        observations,
        mapOf(outputPath to sha256(reportRaw).value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-033 receipt expectation rejected: ${refined.failure}",
        )
    }
}

/**
 * Proof transition: canonical `TaskPacket -> Kvp033CaseAdmission`.
 *
 * Establishes the exact graph-owned task identity, named misuse/legal commands, forbidden-work
 * closure, and eight fixed mutations. Any noncanonical packet shape is closed rejection; command
 * primitives are extracted only at the Gradle gate boundary.
 */
internal fun admitKvp033Cases(packet: TaskPacket): Kvp033CaseAdmission {
    if (
        packet.task.id.value != "KVP-033" ||
        packet.proofCommand.misuse.command != "./gradlew ideHostedVfsSafetyNegativeProof" ||
        packet.proofCommand.legalPath.command != "./gradlew ideHostedVfsSafetyAcceptance"
    ) return Kvp033CaseAdmission.Rejected
    return Kvp033CaseAdmission.Complete(Kvp033ProofCases(
        packet.proofCommand.misuse.namedCase,
        packet.proofCommand.legalPath.namedCase,
        packet.task.forbiddenWork,
        8,
    ))
}

/**
 * Proof transition: raw negative-evidence JSON plus expected count ->
 * `Kvp033NegativeEvidenceAdmission`.
 *
 * Establishes canonical KVP-033 identity, complete outcome, and all eight misuse rejections.
 * Malformed, noncanonical, incomplete, or mismatched evidence is closed rejection; raw JSON is
 * extracted only at this serializer boundary.
 */
internal fun admitKvp033NegativeEvidence(
    raw: String,
    expectedCount: Int,
): Kvp033NegativeEvidenceAdmission {
    val document = try {
        KVP033_DYNAMIC_JSON.decodeFromString(DynamicNegativeEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp033NegativeEvidenceAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp033NegativeEvidenceAdmission.Rejected
    }
    return if (document.schemaVersion == 1 && document.taskId == "KVP-033" &&
        document.outcome == DynamicProofOutcome.COMPLETE &&
        document.rejectedFixtureCount == expectedCount &&
        raw == KVP033_DYNAMIC_JSON.encodeToString(
            DynamicNegativeEvidenceDocument.serializer(), document,
        ) + "\n") Kvp033NegativeEvidenceAdmission.Complete(document)
    else Kvp033NegativeEvidenceAdmission.Rejected
}

/**
 * Proof transition: raw dynamic-report JSON -> `Kvp033ReportAdmission`.
 *
 * Establishes canonical generated bytes and the complete typed dynamic-safety proof. Incomplete
 * evidence remains `Qualified`; malformed, noncanonical, or unsafe evidence is `Rejected`. Raw
 * document fields may be extracted only at this serializer boundary.
 */
internal fun admitKvp033Report(raw: String): Kvp033ReportAdmission {
    val document = try {
        KVP033_DYNAMIC_JSON.decodeFromString(VfsPassiveDynamicProofDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp033ReportAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp033ReportAdmission.Rejected
    }
    val admitted = admitVfsPassiveDynamicProof(document)
    val canonical = raw == KVP033_DYNAMIC_JSON.encodeToString(
        VfsPassiveDynamicProofDocument.serializer(), document,
    ) + "\n"
    return when (admitted) {
        is DynamicProofAdmission.Complete -> if (canonical) {
            Kvp033ReportAdmission.Complete(document)
        } else Kvp033ReportAdmission.Rejected
        is DynamicProofAdmission.Qualified -> Kvp033ReportAdmission.Qualified
        is DynamicProofAdmission.Rejected -> Kvp033ReportAdmission.Rejected
    }
}

internal fun TaskPacket.kvp033CommandDigest() = TaskProofCommandDigest(sha256(canonicalJson(
    listOf(proofCommand.command, proofCommand.misuse.command, proofCommand.legalPath.command),
)).value)

private fun currentKvp033ToolchainDigest() = ToolchainDigest(sha256(canonicalJson(mapOf(
    "gradle" to GradleVersion.current().version,
    "javaRuntime" to System.getProperty("java.runtime.version"),
    "javaVendor" to System.getProperty("java.vendor"),
    "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
))).value)
