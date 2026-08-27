package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@Serializable internal enum class Kvp034Outcome { COMPLETE, QUALIFIED, REJECTED }
@Serializable internal enum class Kvp034MetricSource { DIRECT, STATIC_PROOF, DYNAMIC_PROOF }

@Serializable
internal data class Kvp034EndpointDocument(
    val canonicalRoot: String,
    val hostKind: String,
    val processId: Long,
    val ideBuild: String,
    val kastPluginVersion: String,
    val runtimeEpoch: Long,
)

@Serializable
internal data class Kvp034OperationDocument(
    val operation: String,
    val status: String,
    val responseDigest: String,
)

@Serializable
internal data class Kvp034MetricDocument(
    val id: String,
    val predicate: String,
    val expected: String,
    val observed: String,
    val source: Kvp034MetricSource,
)

@Serializable
internal data class Kvp034InstalledReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp034Outcome,
    val repositoryHead: String,
    val endpoint: Kvp034EndpointDocument,
    val operations: List<Kvp034OperationDocument>,
    val metrics: List<Kvp034MetricDocument>,
)

@Serializable
private data class Kvp034NegativeEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: Kvp034Outcome,
    val rejectedFixtureCount: Int,
)

internal sealed interface Kvp034ReportAdmission {
    data class Complete(val report: Kvp034InstalledReportDocument) : Kvp034ReportAdmission
    data class Qualified(val report: Kvp034InstalledReportDocument) : Kvp034ReportAdmission
    data object Rejected : Kvp034ReportAdmission
}

internal sealed interface Kvp034NegativeEvidenceAdmission {
    data class Complete(val rejectedFixtureCount: Int) : Kvp034NegativeEvidenceAdmission
    data object Rejected : Kvp034NegativeEvidenceAdmission
}

private sealed interface Kvp034MetricAdmission {
    data object Complete : Kvp034MetricAdmission
    data object Rejected : Kvp034MetricAdmission
}

private val kvp034Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
}

/**
 * Proof transition: installed report JSON plus canonical metrics/exact head ->
 * `Kvp034ReportAdmission`.
 *
 * Establishes canonical bytes, exact endpoint/process identity, the ordered four-operation
 * journey, and every graph-owned metric predicate. Incomplete reports remain `Qualified`; any
 * malformed or contradictory report is `Rejected`. Raw JSON exists only at this boundary.
 */
internal fun admitKvp034Report(
    raw: String,
    expectedMetrics: List<MetricRequirement>,
    expectedHead: DeliveryGeneration,
): Kvp034ReportAdmission {
    val document = try {
        kvp034Json.decodeFromString(Kvp034InstalledReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp034ReportAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp034ReportAdmission.Rejected
    }
    if (encodeKvp034Report(document) != raw) return Kvp034ReportAdmission.Rejected
    if (
        document.schemaVersion != 1 || document.taskId != "KVP-034" ||
        document.repositoryHead != expectedHead.value ||
        document.endpoint.canonicalRoot.isBlank() || document.endpoint.hostKind != "IDE_PROJECT" ||
        document.endpoint.processId <= 0 || document.endpoint.runtimeEpoch < 0 ||
        document.operations.map { it.operation } != KVP034_OPERATIONS ||
        document.operations.any {
            it.status != "complete" || !it.responseDigest.matches(Regex("[0-9a-f]{64}"))
        }
    ) return Kvp034ReportAdmission.Rejected
    val expected = expectedMetrics.sortedBy { it.id }
    if (document.metrics.map { it.id } != expected.map { it.id }) {
        return Kvp034ReportAdmission.Rejected
    }
    val metricsComplete = document.metrics.zip(expected).all { (observed, requirement) ->
        observed.predicate == requirement.predicate &&
            observed.expected == requirement.value.metricText() &&
            admitMetric(observed, requirement) == Kvp034MetricAdmission.Complete
    }
    return when {
        document.outcome == Kvp034Outcome.COMPLETE && metricsComplete ->
            Kvp034ReportAdmission.Complete(document)
        document.outcome == Kvp034Outcome.QUALIFIED -> Kvp034ReportAdmission.Qualified(document)
        else -> Kvp034ReportAdmission.Rejected
    }
}

/**
 * Proof transition: raw bounded negative-evidence JSON -> `Kvp034NegativeEvidenceAdmission`.
 *
 * Establishes canonical KVP-034 identity and the exact complete mutation count. Malformed,
 * noncanonical, wrong-task, or incomplete data is closed rejection; raw JSON exists only here.
 */
internal fun admitKvp034NegativeEvidence(
    raw: String,
    expectedCount: Int,
): Kvp034NegativeEvidenceAdmission {
    val document = try {
        kvp034Json.decodeFromString(Kvp034NegativeEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp034NegativeEvidenceAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp034NegativeEvidenceAdmission.Rejected
    }
    return if (
        document.schemaVersion == 1 && document.taskId == "KVP-034" &&
        document.outcome == Kvp034Outcome.REJECTED &&
        document.rejectedFixtureCount == expectedCount &&
        encodeNegative(document) == raw
    ) Kvp034NegativeEvidenceAdmission.Complete(expectedCount)
    else Kvp034NegativeEvidenceAdmission.Rejected
}

@UntrackedTask(because = "Exercises every graph-owned installed metric misuse")
abstract class Kvp034InstalledNegativeProofTask : DefaultTask() {
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    @TaskAction fun prove() {
        val metrics = KastVfsPassiveReusedIndexProgram.validated.program.installedMetrics
        val canonical = fixture(metrics)
        val rejected = metrics.indices.count { index ->
            val metric = canonical.metrics[index]
            val mutated = canonical.copy(metrics = canonical.metrics.toMutableList().apply {
                this[index] = metric.copy(observed = wrongValue(metric.expected))
            })
            admitKvp034Report(
                encodeKvp034Report(mutated),
                metrics,
                DeliveryGeneration(FIXTURE_HEAD),
            ) is Kvp034ReportAdmission.Rejected
        }
        if (rejected != metrics.size) throw GradleException(
            "KVP-034 negative proof admitted ${metrics.size - rejected} metric mutations",
        )
        val evidence = Kvp034NegativeEvidenceDocument(
            1, "KVP-034", Kvp034Outcome.REJECTED, rejected,
        )
        writeTextAtomically(evidenceFile.get().asFile.toPath(), encodeNegative(evidence))
        logger.lifecycle("KVP-034 rejected all {} installed-metric misuses", rejected)
    }
}

internal fun encodeKvp034Report(document: Kvp034InstalledReportDocument): String =
    kvp034Json.encodeToString(Kvp034InstalledReportDocument.serializer(), document) + "\n"
private fun encodeNegative(document: Kvp034NegativeEvidenceDocument): String =
    kvp034Json.encodeToString(Kvp034NegativeEvidenceDocument.serializer(), document) + "\n"

private fun fixture(metrics: List<MetricRequirement>) = Kvp034InstalledReportDocument(
    1, "KVP-034", Kvp034Outcome.COMPLETE, FIXTURE_HEAD,
    Kvp034EndpointDocument("/fixture", "IDE_PROJECT", 1, "262.1", "fixture", 0),
    KVP034_OPERATIONS.map { Kvp034OperationDocument(it, "complete", "0".repeat(64)) },
    metrics.sortedBy { it.id }.map {
        Kvp034MetricDocument(
            it.id, it.predicate, it.value.metricText(), it.value.metricText(),
            Kvp034MetricSource.DIRECT,
        )
    },
)
/** Raw observed metric plus graph requirement -> closed predicate admission. */
private fun admitMetric(
    observed: Kvp034MetricDocument,
    expected: MetricRequirement,
): Kvp034MetricAdmission = when (expected.predicate) {
    "equals" -> if (observed.observed == expected.value.metricText()) {
        Kvp034MetricAdmission.Complete
    } else Kvp034MetricAdmission.Rejected
    "atMost" -> {
        val value = observed.observed.toLongOrNull()
        val maximum = (expected.value as? Number)?.toLong()
        if (value != null && maximum != null && value <= maximum) {
            Kvp034MetricAdmission.Complete
        } else Kvp034MetricAdmission.Rejected
    }
    else -> Kvp034MetricAdmission.Rejected
}
private fun Any.metricText(): String = toString()
private fun wrongValue(expected: String) = when (expected) {
    "true" -> "false"
    "false" -> "true"
    else -> expected.toLongOrNull()?.plus(1)?.toString() ?: "$expected-mutated"
}
private val KVP034_OPERATIONS = listOf(
    "workspace.inspect", "symbol.discover", "symbol.resolve", "symbol.describe",
)
private const val FIXTURE_HEAD = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
