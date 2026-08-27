package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp031ProofReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp031ReportOutcome,
    val misuse: Kvp031ReportCaseDocument,
    val legalPath: Kvp031ReportCaseDocument,
    val implementationCommits: List<Kvp031ImplementationCommitDocument>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp031ForbiddenWorkDocument>,
    val observedRepositoryHead: String,
)

@Serializable private enum class Kvp031ReportOutcome { COMPLETE }

@Serializable
private data class Kvp031ReportCaseDocument(
    val name: String,
    val outcome: Kvp031SemanticOutcome,
)

@Serializable
private data class Kvp031ImplementationCommitDocument(
    val revision: String,
    val changedPaths: List<String>,
)

@Serializable
private data class Kvp031ForbiddenWorkDocument(
    val description: String,
    val enforcementCaseName: String,
)

internal data class Kvp031ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp031Dependencies,
    val cases: Kvp031ProofCaseExpectation,
    val implementationScope: AdmittedKvp031ImplementationScope,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val observedHead: DeliveryGeneration,
)

internal enum class Kvp031ProofReportFailure {
    MALFORMED_DOCUMENT,
    MALFORMED_OBSERVED_HEAD,
    NON_CANONICAL_DOCUMENT,
    REPORT_MISMATCH,
}

internal class AdmittedKvp031ProofReport internal constructor(
    val outputDigest: TaskProofOutputDigest,
    val observations: Map<String, String>,
    val observedRepositoryHead: DeliveryGeneration,
)

internal sealed interface Kvp031ProofReportAdmission {
    data class Complete(val report: AdmittedKvp031ProofReport) : Kvp031ProofReportAdmission
    data class Rejected(val failure: Kvp031ProofReportFailure) : Kvp031ProofReportAdmission
}

internal data class Kvp031PriorProofScopeCandidate(
    val reportHead: DeliveryGeneration,
    val commits: List<Kvp031ImplementationCommit>,
)

internal sealed interface Kvp031PriorProofScopeAdmission {
    data class Complete(val candidate: Kvp031PriorProofScopeCandidate) :
        Kvp031PriorProofScopeAdmission

    data object Rejected : Kvp031PriorProofScopeAdmission
}

private val kvp031ProofReportJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: complete KVP-031 packet, dependency, scope, input, test, command, and
 * toolchain evidence -> canonical COMPLETE report JSON.
 *
 * Preserves every admitted authority without writable status. Raw JSON exits only at the Gradle
 * report boundary.
 */
internal fun canonicalKvp031ProofReport(context: Kvp031ProofContext): String =
    encode(context.document(context.observedHead))

/**
 * Proof transition: KVP-031 report JSON plus complete context ->
 * `Kvp031ProofReportAdmission`.
 *
 * Generated decoding and canonical equality establish the exact content closure while preserving
 * the report's original observed head across unrelated later heads. Expected malformed or
 * mismatched evidence remains finite rejection.
 */
internal fun admitKvp031ProofReport(
    raw: String,
    context: Kvp031ProofContext,
): Kvp031ProofReportAdmission {
    val document = try {
        kvp031ProofReportJson.decodeFromString(Kvp031ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return reportRejected(Kvp031ProofReportFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return reportRejected(Kvp031ProofReportFailure.MALFORMED_DOCUMENT)
    }
    val reportHead = when (val refined = refineDeliveryGeneration(
        document.observedRepositoryHead,
    )) {
        is DeliveryRefinement.Complete -> refined.value
        is DeliveryRefinement.Rejected -> return reportRejected(
            Kvp031ProofReportFailure.MALFORMED_OBSERVED_HEAD,
        )
    }
    val expected = context.document(reportHead)
    if (document != expected) return reportRejected(Kvp031ProofReportFailure.REPORT_MISMATCH)
    if (raw != encode(expected)) {
        return reportRejected(Kvp031ProofReportFailure.NON_CANONICAL_DOCUMENT)
    }
    return Kvp031ProofReportAdmission.Complete(
        AdmittedKvp031ProofReport(
            TaskProofOutputDigest(sha256(raw).value),
            context.completeObservations(),
            reportHead,
        ),
    )
}

/**
 * Proof transition: prior KVP-031 report JSON plus the current stable content closure ->
 * `Kvp031PriorProofScopeAdmission`.
 *
 * Establishes that every non-head, non-scope report field still matches the current graph packet,
 * dependencies, relevant inputs, cases, command, and toolchain. It extracts only typed commit and
 * report-head candidates; the Git boundary must replay and admit that history before reuse.
 */
internal fun admitKvp031PriorProofScope(
    raw: String,
    programVersion: TaskProofProgramVersion,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp031Dependencies,
    cases: Kvp031ProofCaseExpectation,
    relevantInputDigest: RelevantInputDigest,
    commandDigest: TaskProofCommandDigest,
    toolchainDigest: ToolchainDigest,
): Kvp031PriorProofScopeAdmission {
    val document = try {
        kvp031ProofReportJson.decodeFromString(Kvp031ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp031PriorProofScopeAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp031PriorProofScopeAdmission.Rejected
    }
    val reportHead = when (val refined = refineDeliveryGeneration(
        document.observedRepositoryHead,
    )) {
        is DeliveryRefinement.Complete -> refined.value
        is DeliveryRefinement.Rejected -> return Kvp031PriorProofScopeAdmission.Rejected
    }
    val commits = document.implementationCommits.map { commit ->
        val revision = when (val refined = refineDeliveryGeneration(commit.revision)) {
            is DeliveryRefinement.Complete -> refined.value
            is DeliveryRefinement.Rejected -> return Kvp031PriorProofScopeAdmission.Rejected
        }
        if (commit.changedPaths.isEmpty() || commit.changedPaths != commit.changedPaths.sorted()) {
            return Kvp031PriorProofScopeAdmission.Rejected
        }
        Kvp031ImplementationCommit(revision, commit.changedPaths)
    }
    val task = packet.packet.task
    if (
        document.schemaVersion != 1 ||
        document.programVersion != programVersion.value ||
        document.taskId != task.id.value ||
        document.taskDefinitionDigest != packet.packet.taskDefinitionDigest.value ||
        document.dependencyReceiptDigests != dependencies.digests ||
        document.packetDigest != packet.documentDigest.value ||
        document.relevantInputDigest != relevantInputDigest.value ||
        document.commandDigest != commandDigest.value ||
        document.toolchainDigest != toolchainDigest.value ||
        document.outcome != Kvp031ReportOutcome.COMPLETE ||
        document.misuse != Kvp031ReportCaseDocument(
            cases.misuseName,
            Kvp031SemanticOutcome.REJECTED,
        ) ||
        document.legalPath != Kvp031ReportCaseDocument(
            cases.legalPathName,
            Kvp031SemanticOutcome.COMPLETE,
        ) ||
        document.allowedWrites != task.allowedWrites ||
        document.forbiddenWork != cases.forbiddenWork.map {
            Kvp031ForbiddenWorkDocument(it.description, it.enforcementCaseName)
        } ||
        commits.isEmpty() ||
        raw != encode(document)
    ) return Kvp031PriorProofScopeAdmission.Rejected
    return Kvp031PriorProofScopeAdmission.Complete(
        Kvp031PriorProofScopeCandidate(reportHead, commits),
    )
}

internal fun Kvp031ProofContext.receiptExpectation(
    report: AdmittedKvp031ProofReport,
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
            "KVP-031 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun Kvp031ProofContext.completeObservations() = linkedMapOf(
    "misuseOutcome" to Kvp031SemanticOutcome.REJECTED.name,
    "legalPathOutcome" to Kvp031SemanticOutcome.COMPLETE.name,
    "implementationCommitCount" to implementationScope.commits.size.toString(),
    "testSuiteOutcome" to Kvp031SemanticOutcome.COMPLETE.name,
    "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    "predecessorReceiptCount" to dependencies.digests.size.toString(),
)

private fun Kvp031ProofContext.document(
    reportHead: DeliveryGeneration,
): Kvp031ProofReportDocument {
    val task = packet.packet.task
    return Kvp031ProofReportDocument(
        1,
        programVersion.value,
        task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        packet.documentDigest.value,
        relevantInputDigest.value,
        commandDigest.value,
        toolchainDigest.value,
        Kvp031ReportOutcome.COMPLETE,
        Kvp031ReportCaseDocument(cases.misuseName, Kvp031SemanticOutcome.REJECTED),
        Kvp031ReportCaseDocument(cases.legalPathName, Kvp031SemanticOutcome.COMPLETE),
        implementationScope.commits.map {
            Kvp031ImplementationCommitDocument(it.revision.value, it.changedPaths)
        },
        task.allowedWrites,
        cases.forbiddenWork.map {
            Kvp031ForbiddenWorkDocument(it.description, it.enforcementCaseName)
        },
        reportHead.value,
    )
}

private fun encode(document: Kvp031ProofReportDocument) =
    kvp031ProofReportJson.encodeToString(Kvp031ProofReportDocument.serializer(), document) + "\n"

private fun reportRejected(failure: Kvp031ProofReportFailure) =
    Kvp031ProofReportAdmission.Rejected(failure)

