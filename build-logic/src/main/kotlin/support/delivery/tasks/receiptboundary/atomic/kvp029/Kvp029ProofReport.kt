package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp029ProofReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp029ReportOutcome,
    val misuse: Kvp029ReportCaseDocument,
    val legalPath: Kvp029ReportCaseDocument,
    val implementationCommits: List<Kvp029ImplementationCommitDocument>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp029ForbiddenWorkDocument>,
    val observedRepositoryHead: String,
)

@Serializable private enum class Kvp029ReportOutcome { COMPLETE }

@Serializable
private data class Kvp029ReportCaseDocument(
    val name: String,
    val outcome: Kvp029SemanticOutcome,
)

@Serializable
private data class Kvp029ImplementationCommitDocument(
    val revision: String,
    val changedPaths: List<String>,
)

@Serializable
private data class Kvp029ForbiddenWorkDocument(
    val description: String,
    val enforcementCaseName: String,
)

internal data class Kvp029ProofContext(
    val programVersion: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp029Dependencies,
    val cases: Kvp029ProofCaseExpectation,
    val implementationScope: AdmittedKvp029ImplementationScope,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val observedHead: DeliveryGeneration,
)

internal enum class Kvp029ProofReportFailure {
    MALFORMED_DOCUMENT,
    MALFORMED_OBSERVED_HEAD,
    NON_CANONICAL_DOCUMENT,
    REPORT_MISMATCH,
}

internal class AdmittedKvp029ProofReport internal constructor(
    val outputDigest: TaskProofOutputDigest,
    val observations: Map<String, String>,
    val observedRepositoryHead: DeliveryGeneration,
)

internal sealed interface Kvp029ProofReportAdmission {
    data class Complete(val report: AdmittedKvp029ProofReport) : Kvp029ProofReportAdmission
    data class Rejected(val failure: Kvp029ProofReportFailure) : Kvp029ProofReportAdmission
}

internal data class Kvp029PriorProofScopeCandidate(
    val reportHead: DeliveryGeneration,
    val commits: List<Kvp029ImplementationCommit>,
)

internal sealed interface Kvp029PriorProofScopeAdmission {
    data class Complete(val candidate: Kvp029PriorProofScopeCandidate) :
        Kvp029PriorProofScopeAdmission

    data object Rejected : Kvp029PriorProofScopeAdmission
}

private val kvp029ProofReportJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: complete KVP-029 packet, dependency, scope, input, test, command, and
 * toolchain evidence -> canonical COMPLETE report JSON.
 *
 * Preserves every admitted authority without writable status. Raw JSON exits only at the Gradle
 * report boundary.
 */
internal fun canonicalKvp029ProofReport(context: Kvp029ProofContext): String =
    encode(context.document(context.observedHead))

/**
 * Proof transition: KVP-029 report JSON plus complete context ->
 * `Kvp029ProofReportAdmission`.
 *
 * Generated decoding and canonical equality establish the exact content closure while preserving
 * the report's original observed head across unrelated later heads. Expected malformed or
 * mismatched evidence remains finite rejection.
 */
internal fun admitKvp029ProofReport(
    raw: String,
    context: Kvp029ProofContext,
): Kvp029ProofReportAdmission {
    val document = try {
        kvp029ProofReportJson.decodeFromString(Kvp029ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return reportRejected(Kvp029ProofReportFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return reportRejected(Kvp029ProofReportFailure.MALFORMED_DOCUMENT)
    }
    val reportHead = when (val refined = refineDeliveryGeneration(
        document.observedRepositoryHead,
    )) {
        is DeliveryRefinement.Complete -> refined.value
        is DeliveryRefinement.Rejected -> return reportRejected(
            Kvp029ProofReportFailure.MALFORMED_OBSERVED_HEAD,
        )
    }
    val expected = context.document(reportHead)
    if (document != expected) return reportRejected(Kvp029ProofReportFailure.REPORT_MISMATCH)
    if (raw != encode(expected)) {
        return reportRejected(Kvp029ProofReportFailure.NON_CANONICAL_DOCUMENT)
    }
    return Kvp029ProofReportAdmission.Complete(
        AdmittedKvp029ProofReport(
            TaskProofOutputDigest(sha256(raw).value),
            context.completeObservations(),
            reportHead,
        ),
    )
}

/**
 * Proof transition: prior KVP-029 report JSON plus the current stable content closure ->
 * `Kvp029PriorProofScopeAdmission`.
 *
 * Establishes that every non-head, non-scope report field still matches the current graph packet,
 * dependencies, relevant inputs, cases, command, and toolchain. It extracts only typed commit and
 * report-head candidates; the Git boundary must replay and admit that history before reuse.
 */
internal fun admitKvp029PriorProofScope(
    raw: String,
    programVersion: TaskProofProgramVersion,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp029Dependencies,
    cases: Kvp029ProofCaseExpectation,
    relevantInputDigest: RelevantInputDigest,
    commandDigest: TaskProofCommandDigest,
    toolchainDigest: ToolchainDigest,
): Kvp029PriorProofScopeAdmission {
    val document = try {
        kvp029ProofReportJson.decodeFromString(Kvp029ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp029PriorProofScopeAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp029PriorProofScopeAdmission.Rejected
    }
    val reportHead = when (val refined = refineDeliveryGeneration(
        document.observedRepositoryHead,
    )) {
        is DeliveryRefinement.Complete -> refined.value
        is DeliveryRefinement.Rejected -> return Kvp029PriorProofScopeAdmission.Rejected
    }
    val commits = document.implementationCommits.map { commit ->
        val revision = when (val refined = refineDeliveryGeneration(commit.revision)) {
            is DeliveryRefinement.Complete -> refined.value
            is DeliveryRefinement.Rejected -> return Kvp029PriorProofScopeAdmission.Rejected
        }
        if (commit.changedPaths.isEmpty() || commit.changedPaths != commit.changedPaths.sorted()) {
            return Kvp029PriorProofScopeAdmission.Rejected
        }
        Kvp029ImplementationCommit(revision, commit.changedPaths)
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
        document.outcome != Kvp029ReportOutcome.COMPLETE ||
        document.misuse != Kvp029ReportCaseDocument(
            cases.misuseName,
            Kvp029SemanticOutcome.REJECTED,
        ) ||
        document.legalPath != Kvp029ReportCaseDocument(
            cases.legalPathName,
            Kvp029SemanticOutcome.COMPLETE,
        ) ||
        document.allowedWrites != task.allowedWrites ||
        document.forbiddenWork != cases.forbiddenWork.map {
            Kvp029ForbiddenWorkDocument(it.description, it.enforcementCaseName)
        } ||
        commits.isEmpty() ||
        raw != encode(document)
    ) return Kvp029PriorProofScopeAdmission.Rejected
    return Kvp029PriorProofScopeAdmission.Complete(
        Kvp029PriorProofScopeCandidate(reportHead, commits),
    )
}

internal fun Kvp029ProofContext.receiptExpectation(
    report: AdmittedKvp029ProofReport,
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
            "KVP-029 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun Kvp029ProofContext.completeObservations() = linkedMapOf(
    "misuseOutcome" to Kvp029SemanticOutcome.REJECTED.name,
    "legalPathOutcome" to Kvp029SemanticOutcome.COMPLETE.name,
    "implementationCommitCount" to implementationScope.commits.size.toString(),
    "testSuiteOutcome" to Kvp029SemanticOutcome.COMPLETE.name,
    "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    "predecessorReceiptCount" to dependencies.digests.size.toString(),
)

private fun Kvp029ProofContext.document(
    reportHead: DeliveryGeneration,
): Kvp029ProofReportDocument {
    val task = packet.packet.task
    return Kvp029ProofReportDocument(
        1,
        programVersion.value,
        task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        packet.documentDigest.value,
        relevantInputDigest.value,
        commandDigest.value,
        toolchainDigest.value,
        Kvp029ReportOutcome.COMPLETE,
        Kvp029ReportCaseDocument(cases.misuseName, Kvp029SemanticOutcome.REJECTED),
        Kvp029ReportCaseDocument(cases.legalPathName, Kvp029SemanticOutcome.COMPLETE),
        implementationScope.commits.map {
            Kvp029ImplementationCommitDocument(it.revision.value, it.changedPaths)
        },
        task.allowedWrites,
        cases.forbiddenWork.map {
            Kvp029ForbiddenWorkDocument(it.description, it.enforcementCaseName)
        },
        reportHead.value,
    )
}

private fun encode(document: Kvp029ProofReportDocument) =
    kvp029ProofReportJson.encodeToString(Kvp029ProofReportDocument.serializer(), document) + "\n"

private fun reportRejected(failure: Kvp029ProofReportFailure) =
    Kvp029ProofReportAdmission.Rejected(failure)
