package support.delivery

import org.gradle.util.GradleVersion

internal data class Kvp036ProofCases(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<String>,
)

internal data class Kvp036ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp036Dependencies,
    val relevantInputDigest: RelevantInputDigest,
    val scope: AdmittedKvp036ImplementationScope,
    val cases: Kvp036ProofCases,
    val report: Kvp036RetirementReportDocument,
    val reportRaw: String,
)

internal sealed interface Kvp036CaseAdmission {
    data class Complete(val cases: Kvp036ProofCases) : Kvp036CaseAdmission
    data object Rejected : Kvp036CaseAdmission
}

/** Canonical task packet -> graph-named misuse/legal cases or closed rejection. */
internal fun admitKvp036Cases(packet: TaskPacket): Kvp036CaseAdmission {
    if (
        packet.task.id.value != "KVP-036" ||
        packet.proofCommand.command != "./gradlew proveKVP036" ||
        packet.proofCommand.misuse.command != "./gradlew verifyNoDefaultIsolatedRuntimeNegative" ||
        packet.proofCommand.legalPath.command !=
        "./gradlew verifyNoDefaultIsolatedRuntime verifyIdeHostedRelease"
    ) return Kvp036CaseAdmission.Rejected
    return Kvp036CaseAdmission.Complete(Kvp036ProofCases(
        packet.proofCommand.misuse.namedCase,
        packet.proofCommand.legalPath.namedCase,
        packet.task.forbiddenWork,
    ))
}

/** Fully admitted KVP-036 context -> complete exact-head receipt expectation. */
internal fun Kvp036ProofContext.receiptExpectation(): TaskProofReceiptExpectation {
    val output = packet.packet.task.outputs.single().path
    return when (val refined = TaskProofReceiptExpectation.refine(
        programVersion.value,
        packet.packet.receipt.receiptId.value,
        packet.packet.task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        relevantInputDigest.value,
        packet.packet.kvp036CommandDigest().value,
        currentKvp036ToolchainDigest().value,
        linkedMapOf(
            "misuseOutcome" to "REJECTED",
            "legalPathOutcome" to "COMPLETE",
            "retirementReportOutcome" to report.outcome.name,
            "writeScopeOutcome" to "COMPLETE",
            "implementationCommitCount" to scope.commitCount.toString(),
            "negativeFixtureCount" to "5",
            "assetCount" to report.assetCount.toString(),
            "retiredAuthorityCount" to report.retiredAuthorityCount.toString(),
            "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
            "predecessorReceiptCount" to dependencies.digests.size.toString(),
        ),
        mapOf(output to sha256(reportRaw).value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-036 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun TaskPacket.kvp036CommandDigest() = TaskProofCommandDigest(sha256(canonicalJson(
    listOf(proofCommand.command, proofCommand.misuse.command, proofCommand.legalPath.command),
)).value)

private fun currentKvp036ToolchainDigest() = ToolchainDigest(sha256(canonicalJson(mapOf(
    "gradle" to GradleVersion.current().version,
    "javaRuntime" to System.getProperty("java.runtime.version"),
    "javaVendor" to System.getProperty("java.vendor"),
    "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
))).value)
