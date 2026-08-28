package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.util.GradleVersion

@Serializable internal enum class Kvp040Outcome { COMPLETE, QUALIFIED, REJECTED }

@Serializable
internal enum class Kvp040CoverageArea {
    ACTUAL_EXACT_HEAD_DIFF,
    GENERATED_PROJECTIONS,
    SCHEMAS,
    MODULE_EDGES,
    FORBIDDEN_EFFECTS,
    PUBLIC_BEHAVIOR,
    INSTALLED_EVIDENCE,
}

@Serializable internal enum class Kvp040FindingSeverity { LOW, MEDIUM, HIGH, CRITICAL }
@Serializable internal enum class Kvp040FindingValidity { VALID, INVALID }

@Serializable
internal data class Kvp040CoverageDocument(
    val area: Kvp040CoverageArea,
    val authority: String,
    val evidenceDigest: String,
)

@Serializable
internal data class Kvp040FindingDocument(
    val id: String,
    val severity: Kvp040FindingSeverity,
    val location: String,
    val validity: Kvp040FindingValidity,
    val evidenceDigest: String,
    val requiredGateReruns: List<String>,
)

@Serializable
internal data class Kvp040ReviewDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp040Outcome,
    val repositoryHead: String,
    val baseHead: String,
    val diffDigest: String,
    val changedFileCount: Int,
    val coverage: List<Kvp040CoverageDocument>,
    val findings: List<Kvp040FindingDocument>,
    val unresolvedValidFindingCount: Int,
)

@Serializable
private data class Kvp040NegativeDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp040Outcome,
    val namedCase: String,
    val rejectedFixtureCount: Int,
)

internal data class Kvp040Cases(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<String>,
)

internal sealed interface Kvp040CaseAdmission {
    data class Complete(val cases: Kvp040Cases) : Kvp040CaseAdmission
    data object Rejected : Kvp040CaseAdmission
}

internal sealed interface Kvp040ReviewAdmission {
    data class Complete(val review: Kvp040ReviewDocument) : Kvp040ReviewAdmission
    data class Qualified(val review: Kvp040ReviewDocument) : Kvp040ReviewAdmission
    data object Rejected : Kvp040ReviewAdmission
}

internal sealed interface Kvp040NegativeAdmission {
    data class Complete(val rejectedFixtureCount: Int) : Kvp040NegativeAdmission
    data object Rejected : Kvp040NegativeAdmission
}

internal sealed interface Kvp040ReviewDecoding {
    data class Complete(val review: Kvp040ReviewDocument) : Kvp040ReviewDecoding
    data object Rejected : Kvp040ReviewDecoding
}

internal val kvp040Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
}

/**
 * Proof transition: `(Kvp040ReviewDocument, DeliveryGeneration) -> Kvp040ReviewAdmission`.
 *
 * Establishes exact-head identity, complete seven-area authority coverage, canonical finding
 * identities, and an exact unresolved-valid-finding count. Missing, stale, qualified, malformed,
 * or unsupported claims remain closed [Kvp040ReviewAdmission]; raw extraction is permitted only
 * in the generated JSON codec boundary.
 */
internal fun admitKvp040Review(
    review: Kvp040ReviewDocument,
    head: DeliveryGeneration,
): Kvp040ReviewAdmission {
    val digest = Regex("[0-9a-f]{64}")
    val revision = Regex("[0-9a-f]{40}")
    val findingsValid = review.findings.map { it.id }.distinct().size == review.findings.size &&
        review.findings.all { finding ->
            finding.id.isNotBlank() && finding.location.isNotBlank() &&
                digest.matches(finding.evidenceDigest) &&
                (finding.validity != Kvp040FindingValidity.VALID ||
                    finding.requiredGateReruns.isNotEmpty()) &&
                finding.requiredGateReruns.all(String::isNotBlank)
        }
    val completeShape = review.schemaVersion == 1 && review.taskId == "KVP-040" &&
        review.repositoryHead == head.value && revision.matches(review.repositoryHead) &&
        revision.matches(review.baseHead) && review.baseHead != review.repositoryHead &&
        digest.matches(review.diffDigest) && review.changedFileCount > 0 &&
        review.coverage.map { it.area } == Kvp040CoverageArea.entries &&
        review.coverage.all { it.authority.isNotBlank() && digest.matches(it.evidenceDigest) } &&
        findingsValid && review.unresolvedValidFindingCount ==
        review.findings.count { it.validity == Kvp040FindingValidity.VALID }
    if (!completeShape) return Kvp040ReviewAdmission.Rejected
    return when (review.outcome) {
        Kvp040Outcome.COMPLETE -> Kvp040ReviewAdmission.Complete(review)
        Kvp040Outcome.QUALIFIED -> Kvp040ReviewAdmission.Qualified(review)
        Kvp040Outcome.REJECTED -> Kvp040ReviewAdmission.Rejected
    }
}

/**
 * Proof transition: `TaskPacket -> Kvp040CaseAdmission`.
 *
 * Establishes the exact graph-owned misuse, legal path, proof command, and forbidden-work set.
 * Any changed command remains closed rejection; raw command strings are extracted only here.
 */
internal fun admitKvp040Cases(packet: TaskPacket): Kvp040CaseAdmission {
    if (
        packet.task.id.value != "KVP-040" ||
        packet.proofCommand.command != "./gradlew proveKVP040" ||
        packet.proofCommand.misuse.command != "./gradlew finalReviewNegativeProof" ||
        packet.proofCommand.legalPath.command != "./gradlew ideHostedFinalDiffReview"
    ) return Kvp040CaseAdmission.Rejected
    return Kvp040CaseAdmission.Complete(Kvp040Cases(
        packet.proofCommand.misuse.namedCase,
        packet.proofCommand.legalPath.namedCase,
        packet.task.forbiddenWork,
    ))
}

internal fun encodeKvp040Review(review: Kvp040ReviewDocument): String =
    kvp040Json.encodeToString(Kvp040ReviewDocument.serializer(), review) + "\n"

/**
 * Proof transition: `String -> Kvp040ReviewDecoding`.
 *
 * Establishes generated-schema decoding without yet claiming semantic review admission.
 * Malformed or unsupported JSON remains closed rejection; raw JSON is extracted only here.
 */
internal fun decodeKvp040Review(raw: String): Kvp040ReviewDecoding = try {
    Kvp040ReviewDecoding.Complete(
        kvp040Json.decodeFromString(Kvp040ReviewDocument.serializer(), raw),
    )
} catch (_: SerializationException) {
    Kvp040ReviewDecoding.Rejected
} catch (_: IllegalArgumentException) {
    Kvp040ReviewDecoding.Rejected
}

internal fun encodeKvp040Negative(cases: Kvp040Cases): String = kvp040Json.encodeToString(
    Kvp040NegativeDocument.serializer(),
    Kvp040NegativeDocument(1, "KVP-040", Kvp040Outcome.REJECTED, cases.misuseName, 1),
) + "\n"

/**
 * Proof transition: `(String, Kvp040Cases) -> Kvp040NegativeAdmission`.
 *
 * Establishes canonical evidence that exactly one graph-named misuse was rejected. Malformed,
 * noncanonical, or mismatched evidence remains closed rejection; raw JSON is extracted only here.
 */
internal fun admitKvp040Negative(raw: String, cases: Kvp040Cases): Kvp040NegativeAdmission {
    val document = try {
        kvp040Json.decodeFromString(Kvp040NegativeDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp040NegativeAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp040NegativeAdmission.Rejected
    }
    return if (
        document.schemaVersion == 1 && document.taskId == "KVP-040" &&
        document.outcome == Kvp040Outcome.REJECTED && document.namedCase == cases.misuseName &&
        document.rejectedFixtureCount == 1 && encodeKvp040Negative(cases) == raw
    ) Kvp040NegativeAdmission.Complete(1) else Kvp040NegativeAdmission.Rejected
}

internal fun TaskPacket.kvp040CommandDigest() = TaskProofCommandDigest(sha256(canonicalJson(
    listOf(proofCommand.command, proofCommand.misuse.command, proofCommand.legalPath.command),
)).value)

internal fun currentKvp040ToolchainDigest() = ToolchainDigest(sha256(canonicalJson(mapOf(
    "gradle" to GradleVersion.current().version,
    "javaRuntime" to System.getProperty("java.runtime.version"),
    "javaVendor" to System.getProperty("java.vendor"),
    "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
))).value)
