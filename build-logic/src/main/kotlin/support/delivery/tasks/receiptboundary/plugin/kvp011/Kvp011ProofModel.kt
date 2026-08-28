package support.delivery

import org.gradle.util.GradleVersion

internal data class Kvp011ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp011Dependencies,
    val cases: Kvp011ProofCaseExpectation,
    val implementationScope: AdmittedKvp011ImplementationScope,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val observedHead: DeliveryGeneration,
)

internal sealed interface Kvp011ExistingProofAdmission {
    data class Complete(val receipt: AdmittedTaskProofReceipt) : Kvp011ExistingProofAdmission
    data object Rejected : Kvp011ExistingProofAdmission
}

/**
 * Proof transition: physical KVP-011 layout report plus complete admitted context ->
 * `TaskProofReceiptExpectation`.
 *
 * Establishes program, task, dependency, relevant-input, command, toolchain, observed-case, output,
 * and content-scoped head policy closure. Raw report content is reduced only to its exact digest.
 */
internal fun Kvp011ProofContext.receiptExpectation(
    layoutReport: String,
): TaskProofReceiptExpectation {
    val outputPath = packet.packet.task.outputs.single().path
    return when (val refined = TaskProofReceiptExpectation.refine(
        programVersion.value,
        packet.packet.receipt.receiptId.value,
        packet.packet.task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        relevantInputDigest.value,
        commandDigest.value,
        toolchainDigest.value,
        completeObservations(),
        mapOf(outputPath to sha256(layoutReport).value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-011 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun Kvp011ProofContext.completeObservations() = linkedMapOf(
    "misuseOutcome" to Kvp011SemanticOutcome.REJECTED.name,
    "legalPathOutcome" to Kvp011SemanticOutcome.COMPLETE.name,
    "layoutReportOutcome" to Kvp011SemanticOutcome.COMPLETE.name,
    "writeScopeOutcome" to Kvp011SemanticOutcome.COMPLETE.name,
    "negativeFixtureCount" to cases.negativeFixtureCount.toString(),
    "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    "predecessorReceiptCount" to dependencies.digests.size.toString(),
)

/**
 * Proof transition: physical layout/receipt JSON plus context/current head ->
 * `Kvp011ExistingProofAdmission`.
 *
 * Establishes exact v2 receipt self-integrity and the complete content-scoped expectation. Any
 * schema, digest, dependency, observation, output, or policy mismatch is closed rejection; raw
 * receipt JSON is extracted only at this receipt boundary.
 */
internal fun admitKvp011ExistingProof(
    layoutReport: String,
    receiptRaw: String,
    context: Kvp011ProofContext,
    currentHead: DeliveryGeneration,
): Kvp011ExistingProofAdmission = when (val admitted = admitTaskProofReceipt(
    receiptRaw,
    context.receiptExpectation(layoutReport),
    currentHead,
)) {
    is TaskProofReceiptAdmission.Complete ->
        Kvp011ExistingProofAdmission.Complete(admitted.receipt)
    is TaskProofReceiptAdmission.Rejected -> Kvp011ExistingProofAdmission.Rejected
}

internal fun TaskPacket.kvp011CommandDigest() = TaskProofCommandDigest(
    sha256(canonicalJson(listOf(
        proofCommand.command,
        proofCommand.misuse.command,
        proofCommand.legalPath.command,
    ))).value,
)

internal fun currentKvp011ToolchainDigest() = ToolchainDigest(
    sha256(canonicalJson(mapOf(
        "gradle" to GradleVersion.current().version,
        "javaRuntime" to System.getProperty("java.runtime.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
    ))).value,
)
