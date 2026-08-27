package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp027ProofReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp027ReportOutcome,
    val misuse: Kvp027ReportCaseDocument,
    val legalPath: Kvp027ReportCaseDocument,
    val implementationCommits: List<Kvp027ImplementationCommitDocument>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp027ForbiddenWorkDocument>,
    val observedRepositoryHead: String,
)

@Serializable private enum class Kvp027ReportOutcome { COMPLETE }

@Serializable
private data class Kvp027ReportCaseDocument(
    val name: String,
    val outcome: Kvp027SemanticOutcome,
)

@Serializable
private data class Kvp027ImplementationCommitDocument(
    val revision: String,
    val changedPaths: List<String>,
)

@Serializable
private data class Kvp027ForbiddenWorkDocument(
    val description: String,
    val enforcementCaseName: String,
)

internal data class Kvp027ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp027Dependencies,
    val cases: Kvp027ProofCaseExpectation,
    val implementationScope: AdmittedKvp027ImplementationScope,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val observedHead: DeliveryGeneration,
)

internal enum class Kvp027ProofReportFailure {
    MALFORMED_DOCUMENT,
    MALFORMED_OBSERVED_HEAD,
    NON_CANONICAL_DOCUMENT,
    REPORT_MISMATCH,
}

internal class AdmittedKvp027ProofReport internal constructor(
    val outputDigest: TaskProofOutputDigest,
    val observations: Map<String, String>,
    val observedRepositoryHead: DeliveryGeneration,
)

internal sealed interface Kvp027ProofReportAdmission {
    data class Complete(val report: AdmittedKvp027ProofReport) : Kvp027ProofReportAdmission
    data class Rejected(val failure: Kvp027ProofReportFailure) : Kvp027ProofReportAdmission
}

private val kvp027ProofReportJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: complete KVP-027 packet, dependency, scope, input, test, command, and
 * toolchain evidence -> canonical COMPLETE report JSON.
 *
 * Preserves every admitted authority without writable status. Raw JSON exits only at the Gradle
 * report boundary.
 */
internal fun canonicalKvp027ProofReport(context: Kvp027ProofContext): String =
    encode(context.document(context.observedHead))

/**
 * Proof transition: KVP-027 report JSON plus complete context ->
 * `Kvp027ProofReportAdmission`.
 *
 * Generated decoding and canonical equality establish the exact content closure while preserving
 * the report's original observed head across unrelated later heads. Expected malformed or
 * mismatched evidence remains finite rejection.
 */
internal fun admitKvp027ProofReport(
    raw: String,
    context: Kvp027ProofContext,
): Kvp027ProofReportAdmission {
    val document = try {
        kvp027ProofReportJson.decodeFromString(Kvp027ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return reportRejected(Kvp027ProofReportFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return reportRejected(Kvp027ProofReportFailure.MALFORMED_DOCUMENT)
    }
    val reportHead = when (val refined = refineDeliveryGeneration(
        document.observedRepositoryHead,
    )) {
        is DeliveryRefinement.Complete -> refined.value
        is DeliveryRefinement.Rejected -> return reportRejected(
            Kvp027ProofReportFailure.MALFORMED_OBSERVED_HEAD,
        )
    }
    val expected = context.document(reportHead)
    if (document != expected) return reportRejected(Kvp027ProofReportFailure.REPORT_MISMATCH)
    if (raw != encode(expected)) {
        return reportRejected(Kvp027ProofReportFailure.NON_CANONICAL_DOCUMENT)
    }
    return Kvp027ProofReportAdmission.Complete(
        AdmittedKvp027ProofReport(
            TaskProofOutputDigest(sha256(raw).value),
            context.completeObservations(),
            reportHead,
        ),
    )
}

internal fun Kvp027ProofContext.receiptExpectation(
    report: AdmittedKvp027ProofReport,
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
        mapOf(outputPath to report.outputDigest.value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-027 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun Kvp027ProofContext.completeObservations() = linkedMapOf(
    "misuseOutcome" to Kvp027SemanticOutcome.REJECTED.name,
    "legalPathOutcome" to Kvp027SemanticOutcome.COMPLETE.name,
    "implementationCommitCount" to implementationScope.commits.size.toString(),
    "testSuiteOutcome" to Kvp027SemanticOutcome.COMPLETE.name,
    "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    "predecessorReceiptCount" to dependencies.digests.size.toString(),
)

private fun Kvp027ProofContext.document(
    reportHead: DeliveryGeneration,
): Kvp027ProofReportDocument {
    val task = packet.packet.task
    return Kvp027ProofReportDocument(
        1,
        programVersion.value,
        task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        packet.documentDigest.value,
        relevantInputDigest.value,
        commandDigest.value,
        toolchainDigest.value,
        Kvp027ReportOutcome.COMPLETE,
        Kvp027ReportCaseDocument(cases.misuseName, Kvp027SemanticOutcome.REJECTED),
        Kvp027ReportCaseDocument(cases.legalPathName, Kvp027SemanticOutcome.COMPLETE),
        implementationScope.commits.map {
            Kvp027ImplementationCommitDocument(it.revision.value, it.changedPaths)
        },
        task.allowedWrites,
        cases.forbiddenWork.map {
            Kvp027ForbiddenWorkDocument(it.description, it.enforcementCaseName)
        },
        reportHead.value,
    )
}

private fun encode(document: Kvp027ProofReportDocument) =
    kvp027ProofReportJson.encodeToString(Kvp027ProofReportDocument.serializer(), document) + "\n"

private fun reportRejected(failure: Kvp027ProofReportFailure) =
    Kvp027ProofReportAdmission.Rejected(failure)
