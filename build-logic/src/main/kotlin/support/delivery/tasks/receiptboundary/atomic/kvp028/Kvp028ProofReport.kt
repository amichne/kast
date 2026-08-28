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
    MALFORMED_OBSERVED_HEAD,
    NON_CANONICAL_DOCUMENT,
    REPORT_MISMATCH,
}

internal class AdmittedKvp028ProofReport internal constructor(
    val outputDigest: TaskProofOutputDigest,
    val observations: Map<String, String>,
    val observedRepositoryHead: DeliveryGeneration,
)

internal sealed interface Kvp028ProofReportAdmission {
    data class Complete(val report: AdmittedKvp028ProofReport) : Kvp028ProofReportAdmission
    data class Rejected(val failure: Kvp028ProofReportFailure) : Kvp028ProofReportAdmission
}

internal data class Kvp028PriorProofScopeCandidate(
    val reportHead: DeliveryGeneration,
    val commits: List<Kvp028ImplementationCommit>,
)

internal sealed interface Kvp028PriorProofScopeAdmission {
    data class Complete(val candidate: Kvp028PriorProofScopeCandidate) :
        Kvp028PriorProofScopeAdmission

    data object Rejected : Kvp028PriorProofScopeAdmission
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
    encode(context.document(context.observedHead))

/**
 * Proof transition: KVP-028 report JSON plus complete context ->
 * `Kvp028ProofReportAdmission`.
 *
 * Generated decoding and canonical equality establish the exact content closure while preserving
 * the report's original observed head across unrelated later heads. Expected malformed or
 * mismatched evidence remains finite rejection.
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
    val reportHead = when (val refined = refineDeliveryGeneration(
        document.observedRepositoryHead,
    )) {
        is DeliveryRefinement.Complete -> refined.value
        is DeliveryRefinement.Rejected -> return reportRejected(
            Kvp028ProofReportFailure.MALFORMED_OBSERVED_HEAD,
        )
    }
    val expected = context.document(reportHead)
    if (document != expected) return reportRejected(Kvp028ProofReportFailure.REPORT_MISMATCH)
    if (raw != encode(expected)) {
        return reportRejected(Kvp028ProofReportFailure.NON_CANONICAL_DOCUMENT)
    }
    return Kvp028ProofReportAdmission.Complete(
        AdmittedKvp028ProofReport(
            TaskProofOutputDigest(sha256(raw).value),
            context.completeObservations(),
            reportHead,
        ),
    )
}

/**
 * Proof transition: prior KVP-028 report JSON plus current static task authority ->
 * `Kvp028PriorProofScopeAdmission`.
 *
 * Establishes that the report's task definition, packet, cases, command, and toolchain remain
 * compatible enough to extract only typed commit and report-head candidates. Dependency and
 * relevant-input changes deliberately invalidate report reuse without erasing the prior Git scope;
 * the Git boundary must replay and admit that history before a fresh report is issued.
 */
internal fun admitKvp028PriorProofScope(
    raw: String,
    programVersion: TaskProofProgramVersion,
    packet: AdmittedTaskPacketFile,
    cases: Kvp028ProofCaseExpectation,
    commandDigest: TaskProofCommandDigest,
    toolchainDigest: ToolchainDigest,
): Kvp028PriorProofScopeAdmission {
    val document = try {
        kvp028ProofReportJson.decodeFromString(Kvp028ProofReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp028PriorProofScopeAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp028PriorProofScopeAdmission.Rejected
    }
    val reportHead = when (val refined = refineDeliveryGeneration(
        document.observedRepositoryHead,
    )) {
        is DeliveryRefinement.Complete -> refined.value
        is DeliveryRefinement.Rejected -> return Kvp028PriorProofScopeAdmission.Rejected
    }
    val commits = document.implementationCommits.map { commit ->
        val revision = when (val refined = refineDeliveryGeneration(commit.revision)) {
            is DeliveryRefinement.Complete -> refined.value
            is DeliveryRefinement.Rejected -> return Kvp028PriorProofScopeAdmission.Rejected
        }
        if (commit.changedPaths.isEmpty() || commit.changedPaths != commit.changedPaths.sorted()) {
            return Kvp028PriorProofScopeAdmission.Rejected
        }
        Kvp028ImplementationCommit(revision, commit.changedPaths)
    }
    val task = packet.packet.task
    if (
        document.schemaVersion != 1 ||
        document.programVersion != programVersion.value ||
        document.taskId != task.id.value ||
        document.taskDefinitionDigest != packet.packet.taskDefinitionDigest.value ||
        document.dependencyReceiptDigests.keys != packet.packet.receipt.dependencies
            .mapTo(linkedSetOf()) { it.value } ||
        document.packetDigest != packet.documentDigest.value ||
        document.relevantInputDigest.isBlank() ||
        document.commandDigest != commandDigest.value ||
        document.toolchainDigest != toolchainDigest.value ||
        document.outcome != Kvp028ReportOutcome.COMPLETE ||
        document.misuse != Kvp028ReportCaseDocument(
            cases.misuseName,
            Kvp028SemanticOutcome.REJECTED,
        ) ||
        document.legalPath != Kvp028ReportCaseDocument(
            cases.legalPathName,
            Kvp028SemanticOutcome.COMPLETE,
        ) ||
        document.allowedWrites != task.allowedWrites ||
        document.forbiddenWork != cases.forbiddenWork.map {
            Kvp028ForbiddenWorkDocument(it.description, it.enforcementCaseName)
        } ||
        commits.isEmpty() ||
        raw != encode(document)
    ) return Kvp028PriorProofScopeAdmission.Rejected
    return Kvp028PriorProofScopeAdmission.Complete(
        Kvp028PriorProofScopeCandidate(reportHead, commits),
    )
}

internal fun Kvp028ProofContext.receiptExpectation(
    report: AdmittedKvp028ProofReport,
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

private fun Kvp028ProofContext.document(
    reportHead: DeliveryGeneration,
): Kvp028ProofReportDocument {
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
        reportHead.value,
    )
}

private fun encode(document: Kvp028ProofReportDocument) =
    kvp028ProofReportJson.encodeToString(Kvp028ProofReportDocument.serializer(), document) + "\n"

private fun reportRejected(failure: Kvp028ProofReportFailure) =
    Kvp028ProofReportAdmission.Rejected(failure)
