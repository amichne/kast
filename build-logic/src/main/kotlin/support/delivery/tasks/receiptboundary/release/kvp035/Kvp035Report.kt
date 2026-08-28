package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable internal enum class Kvp035Outcome { COMPLETE, QUALIFIED, REJECTED }
@Serializable internal enum class Kvp035AssetKind { CONTROL, IDE_PLUGIN }

@Serializable
internal data class Kvp035AssetDocument(
    val kind: Kvp035AssetKind,
    val name: String,
    val bytes: Long,
    val sha256: String,
)

@Serializable
internal data class Kvp035ReleaseReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp035Outcome,
    val release: String,
    val assets: List<Kvp035AssetDocument>,
    val combinedBytes: Long,
    val maximumCombinedBytes: Long,
)

@Serializable
private data class Kvp035NegativeReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp035Outcome,
    val rejectedFixtureCount: Int,
)

internal sealed interface Kvp035ReportAdmission {
    data class Complete(val report: Kvp035ReleaseReportDocument) : Kvp035ReportAdmission
    data class Qualified(val report: Kvp035ReleaseReportDocument) : Kvp035ReportAdmission
    data object Rejected : Kvp035ReportAdmission
}

internal sealed interface Kvp035NegativeAdmission {
    data class Complete(val rejectedFixtureCount: Int) : Kvp035NegativeAdmission
    data object Rejected : Kvp035NegativeAdmission
}

private val kvp035Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
}

/**
 * Proof transition: bounded release JSON -> `Kvp035ReportAdmission`.
 *
 * Establishes canonical two-payload identity, bound versions and digests, exact byte accounting,
 * and the 80 MiB ceiling. An explicitly incomplete report is `Qualified`; malformed or
 * contradictory data is `Rejected`. Raw JSON exists only at this proof boundary.
 */
internal fun admitKvp035Report(raw: String): Kvp035ReportAdmission {
    val document = try {
        kvp035Json.decodeFromString(Kvp035ReleaseReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp035ReportAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp035ReportAdmission.Rejected
    }
    if (encodeKvp035Report(document) != raw) return Kvp035ReportAdmission.Rejected
    val version = document.release.removePrefix("v")
    val expectedNames = listOf(
        "kast-control-v$version-macos-aarch64.tar.gz",
        "kast-ide-plugin-$version.zip",
    )
    val structurallyComplete = document.schemaVersion == 1 && document.taskId == "KVP-035" &&
        document.release == "v$version" && version.isNotBlank() &&
        document.assets.map { it.kind } == Kvp035AssetKind.entries &&
        document.assets.map { it.name } == expectedNames &&
        document.assets.all { it.bytes > 0 && it.sha256.matches(SHA256) } &&
        document.combinedBytes == document.assets.sumOf { it.bytes } &&
        document.maximumCombinedBytes == MAXIMUM_COMBINED_BYTES &&
        document.combinedBytes <= document.maximumCombinedBytes
    return when {
        structurallyComplete && document.outcome == Kvp035Outcome.COMPLETE ->
            Kvp035ReportAdmission.Complete(document)
        document.outcome == Kvp035Outcome.QUALIFIED -> Kvp035ReportAdmission.Qualified(document)
        else -> Kvp035ReportAdmission.Rejected
    }
}

/**
 * Proof transition: bounded negative JSON `String -> Kvp035NegativeAdmission`.
 *
 * Establishes canonical KVP-035 identity and exactly five rejected misuse fixtures. Malformed or
 * incomplete input is closed [Kvp035NegativeAdmission.Rejected] data; raw JSON exists only here.
 */
internal fun admitKvp035Negative(raw: String): Kvp035NegativeAdmission {
    val document = try {
        kvp035Json.decodeFromString(Kvp035NegativeReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp035NegativeAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp035NegativeAdmission.Rejected
    }
    return if (
        document.schemaVersion == 1 && document.taskId == "KVP-035" &&
        document.outcome == Kvp035Outcome.REJECTED && document.rejectedFixtureCount == 5 &&
        kvp035Json.encodeToString(Kvp035NegativeReportDocument.serializer(), document) + "\n" == raw
    ) Kvp035NegativeAdmission.Complete(document.rejectedFixtureCount)
    else Kvp035NegativeAdmission.Rejected
}

internal fun encodeKvp035Report(document: Kvp035ReleaseReportDocument): String =
    kvp035Json.encodeToString(Kvp035ReleaseReportDocument.serializer(), document) + "\n"

private val SHA256 = Regex("[0-9a-f]{64}")
private const val MAXIMUM_COMBINED_BYTES = 80L * 1024L * 1024L
