package support.delivery

import org.gradle.util.GradleVersion

internal data class Kvp032ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp032Dependencies,
    val cases: Kvp032ProofCaseExpectation,
    val implementationScope: AdmittedKvp032ImplementationScope,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val observedHead: DeliveryGeneration,
)

internal sealed interface Kvp032ExistingProofAdmission {
    data class Complete(val receipt: AdmittedTaskProofReceipt) : Kvp032ExistingProofAdmission
    data object Rejected : Kvp032ExistingProofAdmission
}

/**
 * Proof transition: physical KVP-032 static-safety report plus complete admitted context ->
 * `TaskProofReceiptExpectation`.
 *
 * Establishes program, task, dependency, relevant-input, command, toolchain, observed-case, output,
 * and content-scoped head policy closure. Raw report content is reduced only to its exact digest.
 */
internal fun Kvp032ProofContext.receiptExpectation(
    proofReport: String,
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
        mapOf(outputPath to sha256(proofReport).value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-032 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun Kvp032ProofContext.completeObservations() = linkedMapOf(
    "misuseOutcome" to Kvp032SemanticOutcome.REJECTED.name,
    "legalPathOutcome" to Kvp032SemanticOutcome.COMPLETE.name,
    "staticReportOutcome" to Kvp032SemanticOutcome.COMPLETE.name,
    "writeScopeOutcome" to Kvp032SemanticOutcome.COMPLETE.name,
    "negativeFixtureCount" to cases.negativeFixtureCount.toString(),
    "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    "predecessorReceiptCount" to dependencies.digests.size.toString(),
)

/**
 * Proof transition: physical static-safety/receipt JSON plus context/current head ->
 * `Kvp032ExistingProofAdmission`.
 *
 * Establishes exact v2 receipt self-integrity and the complete content-scoped expectation. Any
 * schema, digest, dependency, observation, output, or policy mismatch is closed rejection; raw
 * receipt JSON is extracted only at this receipt boundary.
 */
internal fun admitKvp032ExistingProof(
    proofReport: String,
    receiptRaw: String,
    context: Kvp032ProofContext,
    currentHead: DeliveryGeneration,
): Kvp032ExistingProofAdmission = when (val admitted = admitTaskProofReceipt(
    receiptRaw,
    context.receiptExpectation(proofReport),
    currentHead,
)) {
    is TaskProofReceiptAdmission.Complete ->
        Kvp032ExistingProofAdmission.Complete(admitted.receipt)
    is TaskProofReceiptAdmission.Rejected -> Kvp032ExistingProofAdmission.Rejected
}

internal fun TaskPacket.kvp032CommandDigest() = TaskProofCommandDigest(
    sha256(canonicalJson(listOf(
        proofCommand.command,
        proofCommand.misuse.command,
        proofCommand.legalPath.command,
    ))).value,
)

internal fun currentKvp032ToolchainDigest() = ToolchainDigest(
    sha256(canonicalJson(mapOf(
        "gradle" to GradleVersion.current().version,
        "javaRuntime" to System.getProperty("java.runtime.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
    ))).value,
)
