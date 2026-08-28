package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.util.GradleVersion

@Serializable internal enum class Kvp039Outcome { COMPLETE, QUALIFIED, REJECTED }

@Serializable
internal data class Kvp039ExactHeadCiDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp039Outcome,
    val repositoryHead: String,
    val workflowDigest: String,
    val predecessorReceiptDigest: String,
    val exactHeadCheckoutCount: Int,
    val requiredGateCount: Int,
    val mergeHeadCheckoutCount: Int,
)

@Serializable
private data class Kvp039NegativeDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp039Outcome,
    val namedCase: String,
    val rejectedFixtureCount: Int,
)

internal data class Kvp039Cases(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<String>,
)

internal sealed interface Kvp039CaseAdmission {
    data class Complete(val cases: Kvp039Cases) : Kvp039CaseAdmission
    data object Rejected : Kvp039CaseAdmission
}

internal sealed interface Kvp039WorkflowAdmission {
    data class Complete(val report: Kvp039ExactHeadCiDocument) : Kvp039WorkflowAdmission
    data class Qualified(val report: Kvp039ExactHeadCiDocument) : Kvp039WorkflowAdmission
    data object Rejected : Kvp039WorkflowAdmission
}

internal sealed interface Kvp039NegativeAdmission {
    data class Complete(val rejectedFixtureCount: Int) : Kvp039NegativeAdmission
    data object Rejected : Kvp039NegativeAdmission
}

private val kvp039Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
}

/**
 * Proof transition: `TaskPacket -> Kvp039CaseAdmission`.
 *
 * Establishes the exact graph-owned KVP-039 command and named misuse/legal cases. A mismatched
 * packet is closed [Kvp039CaseAdmission.Rejected]; raw command strings are extracted only here.
 */
internal fun admitKvp039Cases(packet: TaskPacket): Kvp039CaseAdmission {
    if (
        packet.task.id.value != "KVP-039" ||
        packet.proofCommand.command != "./gradlew proveKVP039" ||
        packet.proofCommand.misuse.command != "./gradlew exactHeadCiNegativeProof" ||
        packet.proofCommand.legalPath.command != "./gradlew verifyExactHeadCiContract"
    ) return Kvp039CaseAdmission.Rejected
    return Kvp039CaseAdmission.Complete(Kvp039Cases(
        packet.proofCommand.misuse.namedCase,
        packet.proofCommand.legalPath.namedCase,
        packet.task.forbiddenWork,
    ))
}

/**
 * Proof transition: `(workflow bytes, DeliveryGeneration, KVP-038 receipt digest) ->
 * Kvp039WorkflowAdmission`.
 *
 * Establishes that ordinary pull-request CI checks out the pull-request head with full history and
 * executes every required repository, release, architecture, runtime, and test gate. Missing,
 * merge-head, or ambiguous evidence is closed rejection; raw workflow text is extracted only here.
 */
internal fun admitKvp039Workflow(
    raw: String,
    head: DeliveryGeneration,
    predecessorDigest: String,
): Kvp039WorkflowAdmission {
    if (raw.isBlank() || predecessorDigest.length != 64) return Kvp039WorkflowAdmission.Rejected
    val exactHeadCount = raw.windowed(EXACT_HEAD_CHECKOUT.length, 1)
        .count { it == EXACT_HEAD_CHECKOUT }
    val mergeHeadCount = MERGE_HEAD_CHECKOUTS.sumOf { token ->
        raw.windowed(token.length, 1).count { it == token }
    }
    val requiredGateCount = REQUIRED_CI_GATES.count(raw::contains)
    val complete = exactHeadCount >= 2 && mergeHeadCount == 0 &&
        requiredGateCount == REQUIRED_CI_GATES.size && raw.contains("pull_request:") &&
        raw.contains("fetch-depth: 0")
    val report = Kvp039ExactHeadCiDocument(
        1,
        "KVP-039",
        if (complete) Kvp039Outcome.COMPLETE else Kvp039Outcome.REJECTED,
        head.value,
        sha256(raw).value,
        predecessorDigest,
        exactHeadCount,
        requiredGateCount,
        mergeHeadCount,
    )
    return if (complete) Kvp039WorkflowAdmission.Complete(report)
    else Kvp039WorkflowAdmission.Rejected
}

/**
 * Proof transition: `(String, Kvp039Cases) -> Kvp039NegativeAdmission`.
 *
 * Establishes rejection of one exact graph-named stale-or-merge-head fixture. Malformed or
 * noncanonical JSON is closed rejection; raw JSON is extracted only at this task boundary.
 */
internal fun admitKvp039Negative(raw: String, cases: Kvp039Cases): Kvp039NegativeAdmission {
    val document = try {
        kvp039Json.decodeFromString(Kvp039NegativeDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp039NegativeAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp039NegativeAdmission.Rejected
    }
    return if (
        document.schemaVersion == 1 && document.taskId == "KVP-039" &&
        document.outcome == Kvp039Outcome.REJECTED && document.namedCase == cases.misuseName &&
        document.rejectedFixtureCount == 1 && encodeKvp039Negative(cases) == raw
    ) Kvp039NegativeAdmission.Complete(1) else Kvp039NegativeAdmission.Rejected
}

internal fun encodeKvp039Report(document: Kvp039ExactHeadCiDocument): String =
    kvp039Json.encodeToString(Kvp039ExactHeadCiDocument.serializer(), document) + "\n"

internal fun encodeKvp039Negative(cases: Kvp039Cases): String = kvp039Json.encodeToString(
    Kvp039NegativeDocument.serializer(),
    Kvp039NegativeDocument(1, "KVP-039", Kvp039Outcome.REJECTED, cases.misuseName, 1),
) + "\n"

internal fun TaskPacket.kvp039CommandDigest() = TaskProofCommandDigest(sha256(canonicalJson(
    listOf(proofCommand.command, proofCommand.misuse.command, proofCommand.legalPath.command),
)).value)

internal fun currentKvp039ToolchainDigest() = ToolchainDigest(sha256(canonicalJson(mapOf(
    "gradle" to GradleVersion.current().version,
    "javaRuntime" to System.getProperty("java.runtime.version"),
    "javaVendor" to System.getProperty("java.vendor"),
    "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
))).value)

internal const val KVP039_WORKFLOW_PATH = ".github/workflows/ci.yml"
private const val EXACT_HEAD_CHECKOUT =
    "ref: \${{ github.event.pull_request.head.sha || github.sha }}"
private val MERGE_HEAD_CHECKOUTS = listOf(
    "ref: \${{ github.sha }}",
    "refs/pull/\${{ github.event.pull_request.number }}/merge",
)
private val REQUIRED_CI_GATES = listOf(
    "python3 .github/scripts/check-repository-shape.py --root .",
    "python3 .github/scripts/release/verify-release-contract.py --root .",
    "bash packaging/test-installer.sh",
    "test",
    "verifyKastModuleGraph",
    "verifyForbiddenEffects",
    "verifyNoLegacyArchitecture",
    "runtimeDeliveryMvpAcceptance",
)
