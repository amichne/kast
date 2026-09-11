package conventions.jsoncontracts

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class JsonContractScanRequest(
    val root: String,
    val baseline: String,
    val sources: List<String>,
    val report: String,
)

/** Standalone parser process: its runtime contains the pinned compiler and no Kotlin Gradle plugin. */
object JsonContractsMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Expected one generated JSON contract scan request." }
        val request = Json.decodeFromString<JsonContractScanRequest>(Files.readString(Path.of(arguments.single())))
        val root = Path.of(request.root)
        val sources = mutableListOf<JsonContractSource>()
        val failures = mutableListOf<JsonContractViolation>()
        for (path in request.sources) {
            if (!validJsonContractPath(path)) {
                failures += JsonContractViolation.InvalidSource(path, 1, JsonContractSourceFailure.INVALID_PATH)
                continue
            }
            try {
                sources += JsonContractSource(path, Files.readString(root.resolve(path)))
            } catch (_: IOException) {
                failures += JsonContractViolation.InvalidSource(path, 1, JsonContractSourceFailure.UNREADABLE_SOURCE)
            }
        }
        val report =
            try {
                JsonContractGuard().verify(sources, Files.readString(Path.of(request.baseline)))
            } catch (_: IOException) {
                JsonContractReport(
                    JsonContractEvidence.KOTLIN_PSI_SYNTAX,
                    emptyList(),
                    listOf(JsonContractViolation.InvalidBaseline(JsonContractBaselineFailure.UNREADABLE_DOCUMENT)),
                )
            }
        val complete = report.copy(violations = report.violations + failures)
        val output = Path.of(request.report)
        Files.createDirectories(output.parent)
        Files.writeString(output, REPORT_JSON.encodeToString(complete) + "\n")
        println(
            "JSON contract syntax check: ${complete.findings.size} fingerprints, ${complete.violations.size} violations. Report: $output"
        )
        if (complete.violations.isNotEmpty()) exitProcess(1)
    }

    private val REPORT_JSON = Json { prettyPrint = true }
}
