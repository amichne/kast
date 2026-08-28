package support.delivery

import org.gradle.util.GradleVersion

internal data class Kvp034ProofCases(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<String>,
    val metricCount: Int,
)

internal data class Kvp034ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp034Dependencies,
    val relevantInputDigest: RelevantInputDigest,
    val scope: AdmittedKvp034ImplementationScope,
    val cases: Kvp034ProofCases,
    val report: Kvp034InstalledReportDocument,
    val reportRaw: String,
    val observedHead: DeliveryGeneration,
)

internal sealed interface Kvp034CaseAdmission {
    data class Complete(val cases: Kvp034ProofCases) : Kvp034CaseAdmission
    data object Rejected : Kvp034CaseAdmission
}

/**
 * Proof transition: `TaskPacket -> Kvp034CaseAdmission`.
 *
 * Establishes graph-owned task identity, named misuse/legal commands, forbidden work, and installed
 * metric cardinality. A noncanonical packet is closed [Kvp034CaseAdmission.Rejected] data; raw
 * command text may leave only at task registration.
 */
internal fun admitKvp034Cases(packet: TaskPacket): Kvp034CaseAdmission {
    val metrics = KastVfsPassiveReusedIndexProgram.validated.program.installedMetrics
    if (
        packet.task.id.value != "KVP-034" ||
        packet.proofCommand.misuse.command != "./gradlew ideHostedInstalledNegativeProof" ||
        packet.proofCommand.legalPath.command !=
        "./gradlew ideHostedInstalledExactReadAcceptance" || metrics.isEmpty()
    ) return Kvp034CaseAdmission.Rejected
    return Kvp034CaseAdmission.Complete(Kvp034ProofCases(
        packet.proofCommand.misuse.namedCase,
        packet.proofCommand.legalPath.namedCase,
        packet.task.forbiddenWork,
        metrics.size,
    ))
}

/**
 * Proof transition: `Kvp034ProofContext -> TaskProofReceiptExpectation`.
 *
 * Establishes exact-head program/task/dependency/input/command/toolchain/observation/output closure.
 * The input context is already admitted; raw receipt fields leave only at the shared issuer.
 */
internal fun Kvp034ProofContext.receiptExpectation(): TaskProofReceiptExpectation {
    val output = packet.packet.task.outputs.single().path
    val observations = linkedMapOf(
        "misuseOutcome" to "REJECTED",
        "legalPathOutcome" to "COMPLETE",
        "installedReportOutcome" to report.outcome.name,
        "writeScopeOutcome" to "COMPLETE",
        "implementationCommitCount" to scope.commitCount.toString(),
        "negativeFixtureCount" to cases.metricCount.toString(),
        "installedMetricCount" to report.metrics.size.toString(),
        "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
        "predecessorReceiptCount" to dependencies.digests.size.toString(),
        "exactHead" to observedHead.value,
    )
    return when (val refined = TaskProofReceiptExpectation.refine(
        programVersion.value,
        packet.packet.receipt.receiptId.value,
        packet.packet.task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        relevantInputDigest.value,
        packet.packet.kvp034CommandDigest().value,
        currentKvp034ToolchainDigest().value,
        observations,
        mapOf(output to sha256(reportRaw).value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-034 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun TaskPacket.kvp034CommandDigest() = TaskProofCommandDigest(sha256(canonicalJson(
    listOf(proofCommand.command, proofCommand.misuse.command, proofCommand.legalPath.command),
)).value)

private fun currentKvp034ToolchainDigest() = ToolchainDigest(sha256(canonicalJson(mapOf(
    "gradle" to GradleVersion.current().version,
    "javaRuntime" to System.getProperty("java.runtime.version"),
    "javaVendor" to System.getProperty("java.vendor"),
    "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
))).value)
