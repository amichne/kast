package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.util.GradleVersion

@Serializable internal enum class Kvp037Outcome { COMPLETE, QUALIFIED, REJECTED }

@Serializable
internal data class Kvp037FailureCaseDocument(
    val id: String,
    val outcome: Kvp037Outcome,
    val reason: String,
    val authority: String,
)

@Serializable
internal data class Kvp037InstalledObservationDocument(
    val status: Int,
    val reason: String,
    val spawnedIndexerCount: Int,
)

@Serializable
internal data class Kvp037ForbiddenEffectsDocument(
    val genericUnknownFailureCount: Int,
    val automaticFallbackCount: Int,
    val unsupportedTransportSuccessCount: Int,
    val nonOwnedPathDeletionCount: Int,
)

@Serializable
internal data class Kvp037FailureMatrixDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp037Outcome,
    val repositoryHead: String,
    val failureCases: List<Kvp037FailureCaseDocument>,
    val unsupportedOperations: List<String>,
    val installedObservation: Kvp037InstalledObservationDocument,
    val forbiddenEffects: Kvp037ForbiddenEffectsDocument,
)

@Serializable
private data class Kvp037NegativeDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp037Outcome,
    val rejectedFixtureCount: Int,
)

internal data class Kvp037Cases(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<String>,
)

internal data class Kvp037ProofContext(
    val version: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp037Dependencies,
    val relevantInputDigest: RelevantInputDigest,
    val scope: AdmittedKvp037ImplementationScope,
    val cases: Kvp037Cases,
    val report: Kvp037FailureMatrixDocument,
    val reportRaw: String,
)

internal sealed interface Kvp037CaseAdmission {
    data class Complete(val cases: Kvp037Cases) : Kvp037CaseAdmission
    data object Rejected : Kvp037CaseAdmission
}

internal sealed interface Kvp037ReportAdmission {
    data class Complete(val report: Kvp037FailureMatrixDocument) : Kvp037ReportAdmission
    data class Qualified(val report: Kvp037FailureMatrixDocument) : Kvp037ReportAdmission
    data object Rejected : Kvp037ReportAdmission
}

internal sealed interface Kvp037NegativeAdmission {
    data class Complete(val count: Int) : Kvp037NegativeAdmission
    data object Rejected : Kvp037NegativeAdmission
}

private val kvp037Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
}

/** Canonical packet -> graph-named KVP-037 misuse/legal cases or closed rejection. */
internal fun admitKvp037Cases(packet: TaskPacket): Kvp037CaseAdmission {
    if (
        packet.task.id.value != "KVP-037" ||
        packet.proofCommand.command != "./gradlew proveKVP037" ||
        packet.proofCommand.misuse.command != "./gradlew ideHostedFailureMatrixNegative" ||
        packet.proofCommand.legalPath.command != "./gradlew ideHostedFailureMatrixAcceptance"
    ) return Kvp037CaseAdmission.Rejected
    return Kvp037CaseAdmission.Complete(Kvp037Cases(
        packet.proofCommand.misuse.namedCase,
        packet.proofCommand.legalPath.namedCase,
        packet.task.forbiddenWork,
    ))
}

/** Bounded deterministic matrix JSON -> closed complete, qualified, or rejected admission. */
internal fun admitKvp037Report(
    raw: String,
    expectedHead: DeliveryGeneration,
): Kvp037ReportAdmission {
    val document = try {
        kvp037Json.decodeFromString(Kvp037FailureMatrixDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp037ReportAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp037ReportAdmission.Rejected
    }
    if (encodeKvp037Report(document) != raw) return Kvp037ReportAdmission.Rejected
    val failures = document.failureCases
    val effects = document.forbiddenEffects
    val complete = document.schemaVersion == 1 && document.taskId == "KVP-037" &&
        document.repositoryHead == expectedHead.value && failures.size == 9 &&
        failures.map { it.id }.distinct().size == failures.size &&
        failures.all {
            it.id.isNotBlank() && it.outcome == Kvp037Outcome.REJECTED &&
                it.reason.isNotBlank() && it.authority.isNotBlank()
        } && document.unsupportedOperations.size == 8 &&
        document.unsupportedOperations == document.unsupportedOperations.sorted() &&
        document.unsupportedOperations.distinct().size == document.unsupportedOperations.size &&
        document.installedObservation.status == 4 &&
        document.installedObservation.reason == "ide-descriptor-read-rejected" &&
        document.installedObservation.spawnedIndexerCount == 0 &&
        effects.genericUnknownFailureCount == 0 && effects.automaticFallbackCount == 0 &&
        effects.unsupportedTransportSuccessCount == 0 && effects.nonOwnedPathDeletionCount == 0
    return when {
        complete && document.outcome == Kvp037Outcome.COMPLETE ->
            Kvp037ReportAdmission.Complete(document)
        document.outcome == Kvp037Outcome.QUALIFIED -> Kvp037ReportAdmission.Qualified(document)
        else -> Kvp037ReportAdmission.Rejected
    }
}

/** Bounded misuse JSON -> one rejection per graph-declared forbidden-work category. */
internal fun admitKvp037Negative(raw: String, expectedCount: Int): Kvp037NegativeAdmission {
    val document = try {
        kvp037Json.decodeFromString(Kvp037NegativeDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp037NegativeAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp037NegativeAdmission.Rejected
    }
    return if (
        document.schemaVersion == 1 && document.taskId == "KVP-037" &&
        document.outcome == Kvp037Outcome.REJECTED &&
        document.rejectedFixtureCount == expectedCount &&
        encodeKvp037Negative(document.rejectedFixtureCount) == raw
    ) Kvp037NegativeAdmission.Complete(document.rejectedFixtureCount)
    else Kvp037NegativeAdmission.Rejected
}

internal fun encodeKvp037Report(document: Kvp037FailureMatrixDocument): String =
    kvp037Json.encodeToString(Kvp037FailureMatrixDocument.serializer(), document) + "\n"

internal fun encodeKvp037Negative(count: Int): String = kvp037Json.encodeToString(
    Kvp037NegativeDocument.serializer(),
    Kvp037NegativeDocument(1, "KVP-037", Kvp037Outcome.REJECTED, count),
) + "\n"

/** Fully admitted KVP-037 context -> complete content-scoped receipt expectation. */
internal fun Kvp037ProofContext.receiptExpectation(): TaskProofReceiptExpectation {
    val output = packet.packet.task.outputs.single().path
    return when (val refined = TaskProofReceiptExpectation.refine(
        version.value,
        packet.packet.receipt.receiptId.value,
        packet.packet.task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        relevantInputDigest.value,
        packet.packet.kvp037CommandDigest().value,
        currentKvp037ToolchainDigest().value,
        linkedMapOf(
            "misuseOutcome" to "REJECTED",
            "legalPathOutcome" to "COMPLETE",
            "failureMatrixOutcome" to report.outcome.name,
            "failureCaseCount" to report.failureCases.size.toString(),
            "unsupportedOperationCount" to report.unsupportedOperations.size.toString(),
            "implementationCommitCount" to scope.commitCount.toString(),
            "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
            "predecessorReceiptCount" to dependencies.digests.size.toString(),
        ),
        mapOf(output to sha256(reportRaw).value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-037 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun TaskPacket.kvp037CommandDigest() = TaskProofCommandDigest(sha256(canonicalJson(
    listOf(proofCommand.command, proofCommand.misuse.command, proofCommand.legalPath.command),
)).value)

private fun currentKvp037ToolchainDigest() = ToolchainDigest(sha256(canonicalJson(mapOf(
    "gradle" to GradleVersion.current().version,
    "javaRuntime" to System.getProperty("java.runtime.version"),
    "javaVendor" to System.getProperty("java.vendor"),
    "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
))).value)
