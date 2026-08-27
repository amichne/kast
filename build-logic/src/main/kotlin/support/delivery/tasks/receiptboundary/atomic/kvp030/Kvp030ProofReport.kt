package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp030ProofReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp030ReportOutcome,
    val misuse: Kvp030ReportCaseDocument,
    val legalPath: Kvp030ReportCaseDocument,
    val implementationCommits: List<Kvp030ImplementationCommitDocument>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp030ForbiddenWorkDocument>,
    val observedRepositoryHead: String,
)

@Serializable private enum class Kvp030ReportOutcome { COMPLETE }

@Serializable
private data class Kvp030ReportCaseDocument(
    val name: String,
    val outcome: Kvp030SemanticOutcome,
)

@Serializable
private data class Kvp030ImplementationCommitDocument(
    val revision: String,
    val changedPaths: List<String>,
)

@Serializable
private data class Kvp030ForbiddenWorkDocument(
    val description: String,
    val enforcementCaseName: String,
)

internal data class Kvp030ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp030Dependencies,
    val cases: Kvp030ProofCaseExpectation,
    val implementationScope: AdmittedKvp030ImplementationScope,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val observedHead: DeliveryGeneration,
)

internal enum class Kvp030ProofReportFailure {
    MALFORMED_DOCUMENT,
    MALFORMED_OBSERVED_HEAD,
    NON_CANONICAL_DOCUMENT,
    REPORT_MISMATCH,
}

internal class AdmittedKvp030ProofReport internal constructor(
    val outputDigest: TaskProofOutputDigest,
    val observations: Map<String, String>,
    val observedRepositoryHead: DeliveryGeneration,
)

internal sealed interface Kvp030ProofReportAdmission {
    data class Complete(val report: AdmittedKvp030ProofReport) : Kvp030ProofReportAdmission
    data class Rejected(val failure: Kvp030ProofReportFailure) : Kvp030ProofReportAdmission
}

internal data class Kvp030PriorProofScopeCandidate(
    val reportHead: DeliveryGeneration,
    val commits: List<Kvp030ImplementationCommit>,
)

internal sealed interface Kvp030PriorProofScopeAdmission {
    data class Complete(val candidate: Kvp030PriorProofScopeCandidate) :
        Kvp030PriorProofScopeAdmission

    data object Rejected : Kvp030PriorProofScopeAdmission
}

private val kvp030ProofReportJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: complete KVP-030 packet, dependency, scope, input, test, command, and
 * toolchain evidence -> canonical COMPLETE report JSON.
 *
 * Preserves every admitted authority without writable status. Raw JSON exits only at the Gradle
 * report boundary.
 */
internal fun canonicalKvp030ProofReport(context: Kvp030ProofContext): String =
    encode(context.document(context.observedHead))

/**
 * Proof transition: KVP-030 report JSON plus complete context ->
 * `Kvp030ProofReportAdmission`.
 *
 * Generated decoding and canonical equality establish the exact content closure while preserving
 * the report's original observed head across unrelated later heads. Expected malformed or
 * mismatched evidence remains finite rejection.
 */
internal fun admitKvp030ProofReport(
    raw: String,
    context: Kvp030ProofContext,
): Kvp030ProofReportAdmission {
    val document = try {
        kvp030ProofReportJson.decodeFromString(Kvp030ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return reportRejected(Kvp030ProofReportFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return reportRejected(Kvp030ProofReportFailure.MALFORMED_DOCUMENT)
    }
    val reportHead = when (val refined = refineDeliveryGeneration(
        document.observedRepositoryHead,
    )) {
        is DeliveryRefinement.Complete -> refined.value
        is DeliveryRefinement.Rejected -> return reportRejected(
            Kvp030ProofReportFailure.MALFORMED_OBSERVED_HEAD,
        )
    }
    val expected = context.document(reportHead)
    if (document != expected) return reportRejected(Kvp030ProofReportFailure.REPORT_MISMATCH)
    if (raw != encode(expected)) {
        return reportRejected(Kvp030ProofReportFailure.NON_CANONICAL_DOCUMENT)
    }
    return Kvp030ProofReportAdmission.Complete(
        AdmittedKvp030ProofReport(
            TaskProofOutputDigest(sha256(raw).value),
            context.completeObservations(),
            reportHead,
        ),
    )
}

/**
 * Proof transition: prior KVP-030 report JSON plus the current stable content closure ->
 * `Kvp030PriorProofScopeAdmission`.
 *
 * Establishes that every non-head, non-scope report field still matches the current graph packet,
 * dependencies, relevant inputs, cases, command, and toolchain. It extracts only typed commit and
 * report-head candidates; the Git boundary must replay and admit that history before reuse.
 */
internal fun admitKvp030PriorProofScope(
    raw: String,
    programVersion: TaskProofProgramVersion,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp030Dependencies,
    cases: Kvp030ProofCaseExpectation,
    relevantInputDigest: RelevantInputDigest,
    commandDigest: TaskProofCommandDigest,
    toolchainDigest: ToolchainDigest,
): Kvp030PriorProofScopeAdmission {
    val document = try {
        kvp030ProofReportJson.decodeFromString(Kvp030ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp030PriorProofScopeAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp030PriorProofScopeAdmission.Rejected
    }
    val reportHead = when (val refined = refineDeliveryGeneration(
        document.observedRepositoryHead,
    )) {
        is DeliveryRefinement.Complete -> refined.value
        is DeliveryRefinement.Rejected -> return Kvp030PriorProofScopeAdmission.Rejected
    }
    val commits = document.implementationCommits.map { commit ->
        val revision = when (val refined = refineDeliveryGeneration(commit.revision)) {
            is DeliveryRefinement.Complete -> refined.value
            is DeliveryRefinement.Rejected -> return Kvp030PriorProofScopeAdmission.Rejected
        }
        if (commit.changedPaths.isEmpty() || commit.changedPaths != commit.changedPaths.sorted()) {
            return Kvp030PriorProofScopeAdmission.Rejected
        }
        Kvp030ImplementationCommit(revision, commit.changedPaths)
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
        document.outcome != Kvp030ReportOutcome.COMPLETE ||
        document.misuse != Kvp030ReportCaseDocument(
            cases.misuseName,
            Kvp030SemanticOutcome.REJECTED,
        ) ||
        document.legalPath != Kvp030ReportCaseDocument(
            cases.legalPathName,
            Kvp030SemanticOutcome.COMPLETE,
        ) ||
        document.allowedWrites != task.allowedWrites ||
        document.forbiddenWork != cases.forbiddenWork.map {
            Kvp030ForbiddenWorkDocument(it.description, it.enforcementCaseName)
        } ||
        commits.isEmpty() ||
        raw != encode(document)
    ) return Kvp030PriorProofScopeAdmission.Rejected
    return Kvp030PriorProofScopeAdmission.Complete(
        Kvp030PriorProofScopeCandidate(reportHead, commits),
    )
}

internal fun Kvp030ProofContext.receiptExpectation(
    report: AdmittedKvp030ProofReport,
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
            "KVP-030 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun Kvp030ProofContext.completeObservations() = linkedMapOf(
    "misuseOutcome" to Kvp030SemanticOutcome.REJECTED.name,
    "legalPathOutcome" to Kvp030SemanticOutcome.COMPLETE.name,
    "implementationCommitCount" to implementationScope.commits.size.toString(),
    "testSuiteOutcome" to Kvp030SemanticOutcome.COMPLETE.name,
    "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    "predecessorReceiptCount" to dependencies.digests.size.toString(),
)

private fun Kvp030ProofContext.document(
    reportHead: DeliveryGeneration,
): Kvp030ProofReportDocument {
    val task = packet.packet.task
    return Kvp030ProofReportDocument(
        1,
        programVersion.value,
        task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        packet.documentDigest.value,
        relevantInputDigest.value,
        commandDigest.value,
        toolchainDigest.value,
        Kvp030ReportOutcome.COMPLETE,
        Kvp030ReportCaseDocument(cases.misuseName, Kvp030SemanticOutcome.REJECTED),
        Kvp030ReportCaseDocument(cases.legalPathName, Kvp030SemanticOutcome.COMPLETE),
        implementationScope.commits.map {
            Kvp030ImplementationCommitDocument(it.revision.value, it.changedPaths)
        },
        task.allowedWrites,
        cases.forbiddenWork.map {
            Kvp030ForbiddenWorkDocument(it.description, it.enforcementCaseName)
        },
        reportHead.value,
    )
}

private fun encode(document: Kvp030ProofReportDocument) =
    kvp030ProofReportJson.encodeToString(Kvp030ProofReportDocument.serializer(), document) + "\n"

private fun reportRejected(failure: Kvp030ProofReportFailure) =
    Kvp030ProofReportAdmission.Rejected(failure)
