package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@Serializable
private data class Kvp014ProjectAdmissionDocument(
    val schemaVersion: Int,
    val authority: String,
    val canonicalRoot: String,
    val projectLifecycle: String,
    val gradleModel: String,
    val indexingState: String,
    val kotlinMode: String,
    val hostCompatibility: String,
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val projectOpenCount: Int,
    val gradleLinkCount: Int,
    val gradleImportCount: Int,
    val vfsRefreshCount: Int,
    val indexingWaitCount: Int,
    val repositoryWalkCount: Int,
    val sourceHashCount: Int,
)

internal enum class Kvp014ProjectAdmissionReportFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    SCHEMA_VERSION_MISMATCH,
    AUTHORITY_MISMATCH,
    CANONICAL_ROOT_MISMATCH,
    PROJECT_LIFECYCLE_MISMATCH,
    GRADLE_MODEL_MISMATCH,
    INDEXING_STATE_MISMATCH,
    KOTLIN_MODE_MISMATCH,
    HOST_COMPATIBILITY_MISMATCH,
    IDE_BUILD_MISMATCH,
    KOTLIN_PLUGIN_BUILD_MISMATCH,
    FORBIDDEN_EFFECT_OBSERVED,
}

internal class AdmittedKvp014ProjectAdmissionReport private constructor(
    val canonicalDocument: String,
    val canonicalRoot: String,
    val ideBuild: String,
    val kotlinPluginBuild: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Kvp014ProjectAdmissionReportAdmission`.
         *
         * Establishes canonical generated report bytes for one exact ready build-262 Project and
         * zero forbidden stronger effects. [Kvp014ProjectAdmissionReportFailure] closes every
         * expected report failure. Raw JSON may leave only at this receipt/report boundary.
         */
        fun admit(raw: String): Kvp014ProjectAdmissionReportAdmission {
            val document = try {
                REPORT_JSON.decodeFromString(Kvp014ProjectAdmissionDocument.serializer(), raw)
            } catch (_: SerializationException) {
                return rejected(Kvp014ProjectAdmissionReportFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return rejected(Kvp014ProjectAdmissionReportFailure.MALFORMED_DOCUMENT)
            }
            val failure = when {
                document.schemaVersion != 1 ->
                    Kvp014ProjectAdmissionReportFailure.SCHEMA_VERSION_MISMATCH
                document.authority != "EXISTING_IDE_PROJECT" ->
                    Kvp014ProjectAdmissionReportFailure.AUTHORITY_MISMATCH
                document.canonicalRoot != "/workspace/kast" ->
                    Kvp014ProjectAdmissionReportFailure.CANONICAL_ROOT_MISMATCH
                document.projectLifecycle != "OPEN_INITIALIZED" ->
                    Kvp014ProjectAdmissionReportFailure.PROJECT_LIFECYCLE_MISMATCH
                document.gradleModel != "COMPLETE" ->
                    Kvp014ProjectAdmissionReportFailure.GRADLE_MODEL_MISMATCH
                document.indexingState != "SMART" ->
                    Kvp014ProjectAdmissionReportFailure.INDEXING_STATE_MISMATCH
                document.kotlinMode != "K2" ->
                    Kvp014ProjectAdmissionReportFailure.KOTLIN_MODE_MISMATCH
                document.hostCompatibility != "EXACT" ->
                    Kvp014ProjectAdmissionReportFailure.HOST_COMPATIBILITY_MISMATCH
                document.ideBuild != "262.9437.185" ->
                    Kvp014ProjectAdmissionReportFailure.IDE_BUILD_MISMATCH
                document.kotlinPluginBuild != "262.9437.185-IJ" ->
                    Kvp014ProjectAdmissionReportFailure.KOTLIN_PLUGIN_BUILD_MISMATCH
                document.forbiddenEffectCounts().any { count -> count != 0 } ->
                    Kvp014ProjectAdmissionReportFailure.FORBIDDEN_EFFECT_OBSERVED
                else -> null
            }
            if (failure != null) return rejected(failure)
            val canonical = REPORT_JSON.encodeToString(
                Kvp014ProjectAdmissionDocument.serializer(),
                document,
            ) + "\n"
            if (raw != canonical) {
                return rejected(Kvp014ProjectAdmissionReportFailure.NON_CANONICAL_DOCUMENT)
            }
            return Kvp014ProjectAdmissionReportAdmission.Admitted(
                AdmittedKvp014ProjectAdmissionReport(
                    canonical,
                    document.canonicalRoot,
                    document.ideBuild,
                    document.kotlinPluginBuild,
                ),
            )
        }
    }
}

internal sealed interface Kvp014ProjectAdmissionReportAdmission {
    data class Admitted(
        val report: AdmittedKvp014ProjectAdmissionReport,
    ) : Kvp014ProjectAdmissionReportAdmission

    data class Rejected(
        val failure: Kvp014ProjectAdmissionReportFailure,
    ) : Kvp014ProjectAdmissionReportAdmission
}

@CacheableTask
abstract class GenerateKvp014ProjectAdmissionReportTask : DefaultTask() {
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /** Writes the one canonical generated KVP-014 report without product-side JSON authority. */
    @TaskAction
    fun generate() {
        writeTextAtomically(reportFile.get().asFile.toPath(), canonicalKvp014ProjectReport())
    }
}

private fun canonicalKvp014ProjectReport(): String = REPORT_JSON.encodeToString(
    Kvp014ProjectAdmissionDocument.serializer(),
    Kvp014ProjectAdmissionDocument(
        schemaVersion = 1,
        authority = "EXISTING_IDE_PROJECT",
        canonicalRoot = "/workspace/kast",
        projectLifecycle = "OPEN_INITIALIZED",
        gradleModel = "COMPLETE",
        indexingState = "SMART",
        kotlinMode = "K2",
        hostCompatibility = "EXACT",
        ideBuild = "262.9437.185",
        kotlinPluginBuild = "262.9437.185-IJ",
        projectOpenCount = 0,
        gradleLinkCount = 0,
        gradleImportCount = 0,
        vfsRefreshCount = 0,
        indexingWaitCount = 0,
        repositoryWalkCount = 0,
        sourceHashCount = 0,
    ),
) + "\n"

private fun Kvp014ProjectAdmissionDocument.forbiddenEffectCounts(): List<Int> = listOf(
    projectOpenCount,
    gradleLinkCount,
    gradleImportCount,
    vfsRefreshCount,
    indexingWaitCount,
    repositoryWalkCount,
    sourceHashCount,
)

private fun rejected(
    failure: Kvp014ProjectAdmissionReportFailure,
): Kvp014ProjectAdmissionReportAdmission =
    Kvp014ProjectAdmissionReportAdmission.Rejected(failure)

private val REPORT_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = true
    prettyPrintIndent = "    "
}
