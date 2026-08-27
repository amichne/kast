package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp028ProofReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp028ReportOutcome,
    val misuse: Kvp028ReportCaseDocument,
    val legalPath: Kvp028ReportCaseDocument,
    val implementationCommits: List<Kvp028ImplementationCommitDocument>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp028ForbiddenWorkDocument>,
    val observedRepositoryHead: String,
)

@Serializable private enum class Kvp028ReportOutcome { COMPLETE }

@Serializable
private data class Kvp028ReportCaseDocument(
    val name: String,
    val outcome: Kvp028SemanticOutcome,
)

@Serializable
private data class Kvp028ImplementationCommitDocument(
    val revision: String,
    val changedPaths: List<String>,
)

@Serializable
private data class Kvp028ForbiddenWorkDocument(
    val description: String,
    val enforcementCaseName: String,
)

internal data class Kvp028ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp028Dependencies,
    val cases: Kvp028ProofCaseExpectation,
    val implementationScope: AdmittedKvp028ImplementationScope,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val observedHead: DeliveryGeneration,
)

internal enum class Kvp028ProofReportFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    REPORT_MISMATCH,
}

internal class AdmittedKvp028ProofReport internal constructor(
    val outputDigest: TaskProofOutputDigest,
    val observations: Map<String, String>,
)

internal sealed interface Kvp028ProofReportAdmission {
    data class Complete(val report: AdmittedKvp028ProofReport) : Kvp028ProofReportAdmission
    data class Rejected(val failure: Kvp028ProofReportFailure) : Kvp028ProofReportAdmission
}

private val kvp028ProofReportJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: complete KVP-028 packet, dependency, scope, input, test, command, and
 * toolchain evidence -> canonical COMPLETE report JSON.
 *
 * Preserves every admitted authority without writable status. Raw JSON exits only at the Gradle
 * report boundary.
 */
internal fun canonicalKvp028ProofReport(context: Kvp028ProofContext): String =
    encode(context.document())

/**
 * Proof transition: KVP-028 report JSON plus complete context ->
 * `Kvp028ProofReportAdmission`.
 *
 * Generated decoding and canonical equality establish the exact proof closure. Expected malformed
 * or mismatched evidence remains finite rejection.
 */
internal fun admitKvp028ProofReport(
    raw: String,
    context: Kvp028ProofContext,
): Kvp028ProofReportAdmission {
    val document = try {
        kvp028ProofReportJson.decodeFromString(Kvp028ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return reportRejected(Kvp028ProofReportFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return reportRejected(Kvp028ProofReportFailure.MALFORMED_DOCUMENT)
    }
    val expected = context.document()
    if (document != expected) return reportRejected(Kvp028ProofReportFailure.REPORT_MISMATCH)
    if (raw != encode(expected)) {
        return reportRejected(Kvp028ProofReportFailure.NON_CANONICAL_DOCUMENT)
    }
    return Kvp028ProofReportAdmission.Complete(
        AdmittedKvp028ProofReport(
            TaskProofOutputDigest(sha256(raw).value),
            context.completeObservations(),
        ),
    )
}

internal fun Kvp028ProofContext.receiptExpectation(): TaskProofReceiptExpectation {
    val outputPath = packet.packet.task.outputs.single().path
    val outputDigest = when (val admitted = admitKvp028ProofReport(
        canonicalKvp028ProofReport(this),
        this,
    )) {
        is Kvp028ProofReportAdmission.Complete -> admitted.report.outputDigest.value
        is Kvp028ProofReportAdmission.Rejected -> error(
            "canonical KVP-028 report rejected: ${admitted.failure}",
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
            "KVP-028 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun Kvp028ProofContext.completeObservations() = linkedMapOf(
    "misuseOutcome" to Kvp028SemanticOutcome.REJECTED.name,
    "legalPathOutcome" to Kvp028SemanticOutcome.COMPLETE.name,
    "implementationCommitCount" to implementationScope.commits.size.toString(),
    "testSuiteOutcome" to Kvp028SemanticOutcome.COMPLETE.name,
    "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    "predecessorReceiptCount" to dependencies.digests.size.toString(),
)

private fun Kvp028ProofContext.document(): Kvp028ProofReportDocument {
    val task = packet.packet.task
    return Kvp028ProofReportDocument(
        1,
        programVersion.value,
        task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        packet.documentDigest.value,
        relevantInputDigest.value,
        commandDigest.value,
        toolchainDigest.value,
        Kvp028ReportOutcome.COMPLETE,
        Kvp028ReportCaseDocument(cases.misuseName, Kvp028SemanticOutcome.REJECTED),
        Kvp028ReportCaseDocument(cases.legalPathName, Kvp028SemanticOutcome.COMPLETE),
        implementationScope.commits.map {
            Kvp028ImplementationCommitDocument(it.revision.value, it.changedPaths)
        },
        task.allowedWrites,
        cases.forbiddenWork.map {
            Kvp028ForbiddenWorkDocument(it.description, it.enforcementCaseName)
        },
        observedHead.value,
    )
}

private fun encode(document: Kvp028ProofReportDocument) =
    kvp028ProofReportJson.encodeToString(Kvp028ProofReportDocument.serializer(), document) + "\n"

private fun reportRejected(failure: Kvp028ProofReportFailure) =
    Kvp028ProofReportAdmission.Rejected(failure)
