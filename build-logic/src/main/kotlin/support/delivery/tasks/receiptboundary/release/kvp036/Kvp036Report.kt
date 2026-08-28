package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable internal enum class Kvp036Outcome { COMPLETE, QUALIFIED, REJECTED }

@Serializable
internal data class Kvp036RetirementReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp036Outcome,
    val assetCount: Int,
    val retiredAuthorityCount: Int,
    val checks: List<String>,
)

@Serializable
private data class Kvp036NegativeReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp036Outcome,
    val rejectedFixtureCount: Int,
)

internal sealed interface Kvp036ReportAdmission {
    data class Complete(val report: Kvp036RetirementReportDocument) : Kvp036ReportAdmission
    data class Qualified(val report: Kvp036RetirementReportDocument) : Kvp036ReportAdmission
    data object Rejected : Kvp036ReportAdmission
}

internal sealed interface Kvp036NegativeAdmission {
    data class Complete(val rejectedFixtureCount: Int) : Kvp036NegativeAdmission
    data object Rejected : Kvp036NegativeAdmission
}

private val kvp036Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
}

/**
 * Proof transition: bounded retirement JSON -> `Kvp036ReportAdmission`.
 *
 * Establishes the closed COMPLETE outcome, exact two hosted assets, and a nonempty unique set of
 * retired authority checks. Explicit incompleteness is `Qualified`; malformed or contradictory
 * evidence is `Rejected`. Raw JSON exists only at this proof boundary.
 */
internal fun admitKvp036Report(raw: String): Kvp036ReportAdmission {
    val document = try {
        kvp036Json.decodeFromString(Kvp036RetirementReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp036ReportAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp036ReportAdmission.Rejected
    }
    if (encodeKvp036Report(document) != raw) return Kvp036ReportAdmission.Rejected
    val complete = document.schemaVersion == 1 && document.taskId == "KVP-036" &&
        document.assetCount == 2 && document.retiredAuthorityCount == document.checks.size &&
        document.checks.isNotEmpty() && document.checks == document.checks.distinct()
    return when {
        complete && document.outcome == Kvp036Outcome.COMPLETE ->
            Kvp036ReportAdmission.Complete(document)
        document.outcome == Kvp036Outcome.QUALIFIED -> Kvp036ReportAdmission.Qualified(document)
        else -> Kvp036ReportAdmission.Rejected
    }
}

/** Bounded negative JSON -> exactly five rejected KVP-036 misuse fixtures. */
internal fun admitKvp036Negative(raw: String): Kvp036NegativeAdmission {
    val document = try {
        kvp036Json.decodeFromString(Kvp036NegativeReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp036NegativeAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp036NegativeAdmission.Rejected
    }
    return if (
        document.schemaVersion == 1 && document.taskId == "KVP-036" &&
        document.outcome == Kvp036Outcome.REJECTED && document.rejectedFixtureCount == 5 &&
        kvp036Json.encodeToString(Kvp036NegativeReportDocument.serializer(), document) + "\n" == raw
    ) Kvp036NegativeAdmission.Complete(document.rejectedFixtureCount)
    else Kvp036NegativeAdmission.Rejected
}

internal fun encodeKvp036Report(document: Kvp036RetirementReportDocument): String =
    kvp036Json.encodeToString(Kvp036RetirementReportDocument.serializer(), document) + "\n"
