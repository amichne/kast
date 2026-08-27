package support.delivery

import org.gradle.util.GradleVersion

internal data class Kvp035ProofCases(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<String>,
)

internal data class Kvp035ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp035Dependencies,
    val relevantInputDigest: RelevantInputDigest,
    val scope: AdmittedKvp035ImplementationScope,
    val cases: Kvp035ProofCases,
    val report: Kvp035ReleaseReportDocument,
    val reportRaw: String,
)

internal sealed interface Kvp035CaseAdmission {
    data class Complete(val cases: Kvp035ProofCases) : Kvp035CaseAdmission
    data object Rejected : Kvp035CaseAdmission
}

/** Canonical task packet -> graph-named misuse/legal cases or closed rejection. */
internal fun admitKvp035Cases(packet: TaskPacket): Kvp035CaseAdmission {
    if (
        packet.task.id.value != "KVP-035" ||
        packet.proofCommand.command != "./gradlew proveKVP035" ||
        packet.proofCommand.misuse.command != "./gradlew verifyIdeHostedReleaseNegative" ||
        packet.proofCommand.legalPath.command !=
        "./gradlew assembleIdeHostedRelease verifyIdeHostedRelease"
    ) return Kvp035CaseAdmission.Rejected
    return Kvp035CaseAdmission.Complete(Kvp035ProofCases(
        packet.proofCommand.misuse.namedCase,
        packet.proofCommand.legalPath.namedCase,
        packet.task.forbiddenWork,
    ))
}

/** Fully admitted KVP-035 context -> complete content-scoped receipt expectation. */
internal fun Kvp035ProofContext.receiptExpectation(): TaskProofReceiptExpectation {
    val output = packet.packet.task.outputs.single().path
    return when (val refined = TaskProofReceiptExpectation.refine(
        programVersion.value,
        packet.packet.receipt.receiptId.value,
        packet.packet.task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        relevantInputDigest.value,
        packet.packet.kvp035CommandDigest().value,
        currentKvp035ToolchainDigest().value,
        linkedMapOf(
            "misuseOutcome" to "REJECTED",
            "legalPathOutcome" to "COMPLETE",
            "releaseReportOutcome" to report.outcome.name,
            "writeScopeOutcome" to "COMPLETE",
            "implementationCommitCount" to scope.commitCount.toString(),
            "negativeFixtureCount" to "5",
            "assetCount" to report.assets.size.toString(),
            "combinedBytes" to report.combinedBytes.toString(),
            "maximumCombinedBytes" to report.maximumCombinedBytes.toString(),
            "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
            "predecessorReceiptCount" to dependencies.digests.size.toString(),
        ),
        mapOf(output to sha256(reportRaw).value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-035 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun TaskPacket.kvp035CommandDigest() = TaskProofCommandDigest(sha256(canonicalJson(
    listOf(proofCommand.command, proofCommand.misuse.command, proofCommand.legalPath.command),
)).value)

private fun currentKvp035ToolchainDigest() = ToolchainDigest(sha256(canonicalJson(mapOf(
    "gradle" to GradleVersion.current().version,
    "javaRuntime" to System.getProperty("java.runtime.version"),
    "javaVendor" to System.getProperty("java.vendor"),
    "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
))).value)
