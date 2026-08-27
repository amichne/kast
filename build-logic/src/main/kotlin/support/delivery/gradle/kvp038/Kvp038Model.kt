package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.util.GradleVersion

@Serializable internal enum class Kvp038Outcome { COMPLETE, QUALIFIED, REJECTED }

@Serializable
internal data class Kvp038CleanCheckoutDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp038Outcome,
    val repositoryHead: String,
    val detachedHead: String,
    val projectionDiffClean: Boolean,
    val structuralGatesPassed: Boolean,
    val hostedAssetsBuilt: Boolean,
    val installedAcceptancePassed: Boolean,
    val currentWorktreeOutputCount: Int,
    val reusedGradleCacheCount: Int,
    val untrackedFixtureCount: Int,
)

@Serializable
private data class Kvp038NegativeDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp038Outcome,
    val namedCase: String,
    val rejectedFixtureCount: Int,
)

internal data class Kvp038Cases(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<String>,
)

internal sealed interface Kvp038CaseAdmission {
    data class Complete(val cases: Kvp038Cases) : Kvp038CaseAdmission
    data object Rejected : Kvp038CaseAdmission
}

internal sealed interface Kvp038ReportAdmission {
    data class Complete(val report: Kvp038CleanCheckoutDocument) : Kvp038ReportAdmission
    data class Qualified(val report: Kvp038CleanCheckoutDocument) : Kvp038ReportAdmission
    data object Rejected : Kvp038ReportAdmission
}

internal sealed interface Kvp038NegativeAdmission {
    data class Complete(val rejectedFixtureCount: Int) : Kvp038NegativeAdmission
    data object Rejected : Kvp038NegativeAdmission
}

private val kvp038Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
}

/**
 * Proof transition: `TaskPacket -> Kvp038CaseAdmission`.
 *
 * Establishes the exact KVP-038 command and graph-named misuse/legal cases. A mismatched packet is
 * closed [Kvp038CaseAdmission.Rejected]; raw command strings are extracted only here.
 */
internal fun admitKvp038Cases(packet: TaskPacket): Kvp038CaseAdmission {
    if (
        packet.task.id.value != "KVP-038" ||
        packet.proofCommand.command != "./gradlew proveKVP038" ||
        packet.proofCommand.misuse.command != "./gradlew cleanCheckoutNegativeProof" ||
        packet.proofCommand.legalPath.command != "./gradlew ideHostedCleanCheckoutAcceptance"
    ) return Kvp038CaseAdmission.Rejected
    return Kvp038CaseAdmission.Complete(Kvp038Cases(
        packet.proofCommand.misuse.namedCase,
        packet.proofCommand.legalPath.namedCase,
        packet.task.forbiddenWork,
    ))
}

/**
 * Proof transition: `String -> Kvp038ReportAdmission`.
 *
 * Establishes the complete detached-head, fresh-cache, projection, structural, asset, installed,
 * and cleanliness invariants. Malformed or incomplete evidence is closed rejection and an explicit
 * qualified outcome stays qualified. Raw shell evidence is extracted only at this task boundary.
 */
internal fun admitKvp038Evidence(
    raw: String,
    expectedHead: DeliveryGeneration,
): Kvp038ReportAdmission {
    val entries = mutableListOf<Pair<String, String>>()
    raw.lineSequence().filter(String::isNotBlank).forEach { line ->
        val parts = line.split('=', limit = 2)
        if (parts.size != 2) return Kvp038ReportAdmission.Rejected
        entries += parts[0] to parts[1]
    }
    if (entries.map { it.first }.distinct().size != entries.size) {
        return Kvp038ReportAdmission.Rejected
    }
    val values = entries.toMap()
    if (values.keys != KVP038_EVIDENCE_FIELDS) return Kvp038ReportAdmission.Rejected
    val outcome = try {
        Kvp038Outcome.valueOf(values.getValue("outcome"))
    } catch (_: IllegalArgumentException) {
        return Kvp038ReportAdmission.Rejected
    }
    val booleanNames = listOf(
        "projectionDiffClean", "structuralGatesPassed", "hostedAssetsBuilt",
        "installedAcceptancePassed",
    )
    if (booleanNames.any { values.getValue(it) !in setOf("true", "false") }) {
        return Kvp038ReportAdmission.Rejected
    }
    val counts = try {
        listOf(
            values.getValue("currentWorktreeOutputCount").toInt(),
            values.getValue("reusedGradleCacheCount").toInt(),
            values.getValue("untrackedFixtureCount").toInt(),
        )
    } catch (_: NumberFormatException) {
        return Kvp038ReportAdmission.Rejected
    }
    if (counts.any { it < 0 }) return Kvp038ReportAdmission.Rejected
    val document = Kvp038CleanCheckoutDocument(
        try {
            values.getValue("schemaVersion").toInt()
        } catch (_: NumberFormatException) {
            return Kvp038ReportAdmission.Rejected
        },
        values.getValue("taskId"),
        outcome,
        values.getValue("repositoryHead"),
        values.getValue("detachedHead"),
        values.getValue("projectionDiffClean") == "true",
        values.getValue("structuralGatesPassed") == "true",
        values.getValue("hostedAssetsBuilt") == "true",
        values.getValue("installedAcceptancePassed") == "true",
        counts[0], counts[1], counts[2],
    )
    val identity = document.schemaVersion == 1 && document.taskId == "KVP-038" &&
        document.repositoryHead == expectedHead.value && document.detachedHead == expectedHead.value
    val complete = identity && document.projectionDiffClean && document.structuralGatesPassed &&
        document.hostedAssetsBuilt && document.installedAcceptancePassed &&
        document.currentWorktreeOutputCount == 0 && document.reusedGradleCacheCount == 0 &&
        document.untrackedFixtureCount == 0
    return when {
        complete && outcome == Kvp038Outcome.COMPLETE -> Kvp038ReportAdmission.Complete(document)
        identity && outcome == Kvp038Outcome.QUALIFIED -> Kvp038ReportAdmission.Qualified(document)
        else -> Kvp038ReportAdmission.Rejected
    }
}

/**
 * Proof transition: `(String, Kvp038Cases) -> Kvp038NegativeAdmission`.
 *
 * Establishes one rejection of the exact graph-named untracked-input misuse. Malformed or
 * mismatched JSON is closed rejection; raw JSON is extracted only at this Gradle boundary.
 */
internal fun admitKvp038Negative(
    raw: String,
    cases: Kvp038Cases,
): Kvp038NegativeAdmission {
    val document = try {
        kvp038Json.decodeFromString(Kvp038NegativeDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp038NegativeAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp038NegativeAdmission.Rejected
    }
    return if (
        document.schemaVersion == 1 && document.taskId == "KVP-038" &&
        document.outcome == Kvp038Outcome.REJECTED && document.namedCase == cases.misuseName &&
        document.rejectedFixtureCount == 1 &&
        encodeKvp038Negative(cases) == raw
    ) Kvp038NegativeAdmission.Complete(document.rejectedFixtureCount)
    else Kvp038NegativeAdmission.Rejected
}

internal fun encodeKvp038Report(document: Kvp038CleanCheckoutDocument): String =
    kvp038Json.encodeToString(Kvp038CleanCheckoutDocument.serializer(), document) + "\n"

internal fun encodeKvp038Negative(cases: Kvp038Cases): String = kvp038Json.encodeToString(
    Kvp038NegativeDocument.serializer(),
    Kvp038NegativeDocument(
        1, "KVP-038", Kvp038Outcome.REJECTED, cases.misuseName, 1,
    ),
) + "\n"

internal fun TaskPacket.kvp038CommandDigest() = TaskProofCommandDigest(sha256(canonicalJson(
    listOf(proofCommand.command, proofCommand.misuse.command, proofCommand.legalPath.command),
)).value)

internal fun currentKvp038ToolchainDigest() = ToolchainDigest(sha256(canonicalJson(mapOf(
    "gradle" to GradleVersion.current().version,
    "javaRuntime" to System.getProperty("java.runtime.version"),
    "javaVendor" to System.getProperty("java.vendor"),
    "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
))).value)

private val KVP038_EVIDENCE_FIELDS = setOf(
    "schemaVersion", "taskId", "outcome", "repositoryHead", "detachedHead",
    "projectionDiffClean", "structuralGatesPassed", "hostedAssetsBuilt",
    "installedAcceptancePassed", "currentWorktreeOutputCount", "reusedGradleCacheCount",
    "untrackedFixtureCount",
)
