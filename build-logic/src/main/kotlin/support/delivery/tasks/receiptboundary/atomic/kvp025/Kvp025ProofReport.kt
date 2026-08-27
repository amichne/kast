package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp025ProofReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp025ReportOutcome,
    val misuse: Kvp025ReportCaseDocument,
    val legalPath: Kvp025ReportCaseDocument,
    val implementationCommits: List<Kvp025ImplementationCommitDocument>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp025ForbiddenWorkDocument>,
    val executedTestCount: Int,
)

@Serializable private enum class Kvp025ReportOutcome { COMPLETE }

@Serializable
private data class Kvp025ReportCaseDocument(
    val name: String,
    val outcome: Kvp025SemanticOutcome,
)

@Serializable
private data class Kvp025ImplementationCommitDocument(
    val revision: String,
    val changedPaths: List<String>,
)

@Serializable
private data class Kvp025ForbiddenWorkDocument(
    val description: String,
    val enforcementAuthority: String,
    val observedFailureCount: Int,
)

internal enum class Kvp025ProofReportFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    REPORT_MISMATCH,
}

internal data class Kvp025ImplementationCommit(
    val revision: DeliveryGeneration,
    val changedPaths: List<String>,
)

internal class AdmittedKvp025ImplementationScope internal constructor(
    val commits: List<Kvp025ImplementationCommit>,
)

internal data class Kvp025ProofReportContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val predecessor: AdmittedLegacyReceiptPrefix,
    val caseExpectation: Kvp025ProofCaseExpectation,
    val implementationScope: AdmittedKvp025ImplementationScope,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val observedHead: DeliveryGeneration,
)

internal class AdmittedKvp025ProofReport internal constructor(
    val canonicalDocument: String,
    val outputDigest: TaskProofOutputDigest,
    val observations: Map<String, String>,
)

internal sealed interface Kvp025ProofReportAdmission {
    data class Complete(val report: AdmittedKvp025ProofReport) : Kvp025ProofReportAdmission
    data class Rejected(val failure: Kvp025ProofReportFailure) : Kvp025ProofReportAdmission
}

private val kvp025ProofReportJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: complete KVP-025 packet, predecessor, test, scope, input, command, and
 * toolchain evidence -> canonical proof report.
 *
 * Preserves the graph definition and every observed proof authority in one closed COMPLETE
 * document. Expected malformed or mismatched documents remain finite
 * [Kvp025ProofReportFailure]. Raw JSON exits only at the Gradle report boundary.
 */
internal fun canonicalKvp025ProofReport(
    context: Kvp025ProofReportContext,
): String = encode(context.document())

/**
 * Proof transition: KVP-025 report JSON plus complete proof context ->
 * `Kvp025ProofReportAdmission`.
 *
 * Generated decoding and canonical equality establish the exact report closure. Expected
 * malformed, noncanonical, or mismatched evidence returns finite [Kvp025ProofReportFailure].
 */
internal fun admitKvp025ProofReport(
    raw: String,
    context: Kvp025ProofReportContext,
): Kvp025ProofReportAdmission {
    val document = try {
        kvp025ProofReportJson.decodeFromString(Kvp025ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejected(Kvp025ProofReportFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return rejected(Kvp025ProofReportFailure.MALFORMED_DOCUMENT)
    }
    val expected = context.document()
    if (document != expected) return rejected(Kvp025ProofReportFailure.REPORT_MISMATCH)
    if (raw != encode(expected)) {
        return rejected(Kvp025ProofReportFailure.NON_CANONICAL_DOCUMENT)
    }
    val observations = context.completeObservations()
    return Kvp025ProofReportAdmission.Complete(
        AdmittedKvp025ProofReport(
            raw,
            TaskProofOutputDigest(sha256(raw).value),
            observations,
        ),
    )
}

internal fun Kvp025ProofReportContext.completeObservations() = linkedMapOf(
        "misuseOutcome" to Kvp025SemanticOutcome.REJECTED.name,
        "legalPathOutcome" to Kvp025SemanticOutcome.COMPLETE.name,
        "implementationCommitCount" to implementationScope.commits.size.toString(),
        "executedTestCount" to caseExpectation.executedTestCount.toString(),
        "forbiddenEffectCount" to caseExpectation.observedFailureCount.toString(),
        "predecessorReceipt" to predecessor.frontierReceiptId.value,
)

private fun Kvp025ProofReportContext.document(): Kvp025ProofReportDocument {
    val task = packet.packet.task
    return Kvp025ProofReportDocument(
        schemaVersion = 1,
        programVersion = programVersion.value,
        taskId = task.id.value,
        taskDefinitionDigest = packet.packet.taskDefinitionDigest.value,
        dependencyReceiptDigests = mapOf(
            predecessor.frontierReceiptId.value to predecessor.frontierReceiptDigest.value,
        ),
        packetDigest = packet.documentDigest.value,
        relevantInputDigest = relevantInputDigest.value,
        commandDigest = commandDigest.value,
        toolchainDigest = toolchainDigest.value,
        outcome = Kvp025ReportOutcome.COMPLETE,
        misuse = Kvp025ReportCaseDocument(
            caseExpectation.misuseName,
            Kvp025SemanticOutcome.REJECTED,
        ),
        legalPath = Kvp025ReportCaseDocument(
            caseExpectation.legalPathName,
            Kvp025SemanticOutcome.COMPLETE,
        ),
        implementationCommits = implementationScope.commits.map {
            Kvp025ImplementationCommitDocument(it.revision.value, it.changedPaths)
        },
        allowedWrites = task.allowedWrites,
        forbiddenWork = task.forbiddenWork.map {
            Kvp025ForbiddenWorkDocument(
                it,
                "KVP025_GRAPH_SELECTED_RETIREMENT_TEST_SUITE",
                caseExpectation.observedFailureCount,
            )
        },
        executedTestCount = caseExpectation.executedTestCount,
    )
}

private fun encode(document: Kvp025ProofReportDocument) =
    kvp025ProofReportJson.encodeToString(Kvp025ProofReportDocument.serializer(), document) + "\n"

private fun rejected(failure: Kvp025ProofReportFailure) =
    Kvp025ProofReportAdmission.Rejected(failure)
