package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp026ProofReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp026ReportOutcome,
    val misuse: Kvp026ReportCaseDocument,
    val legalPath: Kvp026ReportCaseDocument,
    val implementationCommits: List<Kvp026ImplementationCommitDocument>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp026ForbiddenWorkDocument>,
    val executedTestCount: Int,
    val observedRepositoryHead: String,
)

@Serializable private enum class Kvp026ReportOutcome { COMPLETE }

@Serializable
private data class Kvp026ReportCaseDocument(
    val name: String,
    val outcome: Kvp026SemanticOutcome,
)

@Serializable
private data class Kvp026ImplementationCommitDocument(
    val revision: String,
    val changedPaths: List<String>,
)

@Serializable
private data class Kvp026ForbiddenWorkDocument(
    val description: String,
    val enforcementCaseName: String,
    val observedTestResult: Kvp026ObservedTestResult,
)

internal data class Kvp026ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp026Dependencies,
    val cases: Kvp026ProofCaseExpectation,
    val implementationScope: AdmittedKvp026ImplementationScope,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val observedHead: DeliveryGeneration,
)

internal enum class Kvp026ProofReportFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    REPORT_MISMATCH,
}

internal class AdmittedKvp026ProofReport internal constructor(
    val outputDigest: TaskProofOutputDigest,
    val observations: Map<String, String>,
)

internal sealed interface Kvp026ProofReportAdmission {
    data class Complete(val report: AdmittedKvp026ProofReport) : Kvp026ProofReportAdmission
    data class Rejected(val failure: Kvp026ProofReportFailure) : Kvp026ProofReportAdmission
}

private val kvp026ProofReportJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: complete KVP-026 packet, dependency, scope, input, test, command, and
 * toolchain evidence -> canonical COMPLETE report JSON.
 *
 * Preserves every admitted authority without writable status. Raw JSON exits only at the Gradle
 * report boundary.
 */
internal fun canonicalKvp026ProofReport(context: Kvp026ProofContext): String =
    encode(context.document())

/**
 * Proof transition: KVP-026 report JSON plus complete context ->
 * `Kvp026ProofReportAdmission`.
 *
 * Generated decoding and canonical equality establish the exact proof closure. Expected malformed
 * or mismatched evidence remains finite rejection.
 */
internal fun admitKvp026ProofReport(
    raw: String,
    context: Kvp026ProofContext,
): Kvp026ProofReportAdmission {
    val document = try {
        kvp026ProofReportJson.decodeFromString(Kvp026ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return reportRejected(Kvp026ProofReportFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return reportRejected(Kvp026ProofReportFailure.MALFORMED_DOCUMENT)
    }
    val expected = context.document()
    if (document != expected) return reportRejected(Kvp026ProofReportFailure.REPORT_MISMATCH)
    if (raw != encode(expected)) {
        return reportRejected(Kvp026ProofReportFailure.NON_CANONICAL_DOCUMENT)
    }
    return Kvp026ProofReportAdmission.Complete(
        AdmittedKvp026ProofReport(
            TaskProofOutputDigest(sha256(raw).value),
            context.completeObservations(),
        ),
    )
}

internal fun Kvp026ProofContext.receiptExpectation(): TaskProofReceiptExpectation {
    val outputPath = packet.packet.task.outputs.single().path
    val outputDigest = when (val admitted = admitKvp026ProofReport(
        canonicalKvp026ProofReport(this),
        this,
    )) {
        is Kvp026ProofReportAdmission.Complete -> admitted.report.outputDigest.value
        is Kvp026ProofReportAdmission.Rejected -> error(
            "canonical KVP-026 report rejected: ${admitted.failure}",
        )
    }
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
        mapOf(outputPath to outputDigest),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-026 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun Kvp026ProofContext.completeObservations() = linkedMapOf(
    "misuseOutcome" to Kvp026SemanticOutcome.REJECTED.name,
    "legalPathOutcome" to Kvp026SemanticOutcome.COMPLETE.name,
    "implementationCommitCount" to implementationScope.commits.size.toString(),
    "executedTestCount" to cases.executedTestCount.toString(),
    "suiteFailureCount" to cases.suiteFailureCount.toString(),
    "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    "predecessorReceiptCount" to dependencies.digests.size.toString(),
)

private fun Kvp026ProofContext.document(): Kvp026ProofReportDocument {
    val task = packet.packet.task
    return Kvp026ProofReportDocument(
        1,
        programVersion.value,
        task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        packet.documentDigest.value,
        relevantInputDigest.value,
        commandDigest.value,
        toolchainDigest.value,
        Kvp026ReportOutcome.COMPLETE,
        Kvp026ReportCaseDocument(cases.misuseName, Kvp026SemanticOutcome.REJECTED),
        Kvp026ReportCaseDocument(cases.legalPathName, Kvp026SemanticOutcome.COMPLETE),
        implementationScope.commits.map {
            Kvp026ImplementationCommitDocument(it.revision.value, it.changedPaths)
        },
        task.allowedWrites,
        cases.forbiddenWork.map {
            Kvp026ForbiddenWorkDocument(it.description, it.enforcementCaseName, it.testResult)
        },
        cases.executedTestCount,
        observedHead.value,
    )
}

private fun encode(document: Kvp026ProofReportDocument) =
    kvp026ProofReportJson.encodeToString(Kvp026ProofReportDocument.serializer(), document) + "\n"

private fun reportRejected(failure: Kvp026ProofReportFailure) =
    Kvp026ProofReportAdmission.Rejected(failure)
