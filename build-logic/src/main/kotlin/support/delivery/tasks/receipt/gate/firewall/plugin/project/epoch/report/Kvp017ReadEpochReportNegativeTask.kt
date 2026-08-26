package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Executes deterministic KVP-017 report mutation fixtures")
abstract class VerifyKvp017ReadEpochReportNegativeTask : DefaultTask() {
    @get:InputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verifyNegativeCases() {
        val canonical = reportFile.get().asFile.readText()
        val admitted = AdmittedKvp017ReadEpochReport.admit(canonical)
        if (admitted !is Kvp017ReadEpochReportAdmission.Admitted ||
            admitted.report.canonicalDocument != canonicalKvp017ReadEpochReport()
        ) throw GradleException("canonical KVP-017 report was rejected")

        val mutations = listOf(
            Kvp017ReadEpochReportFailure.MALFORMED_DOCUMENT to "{",
            Kvp017ReadEpochReportFailure.NON_CANONICAL_DOCUMENT to
                canonical.removeSuffix("\n"),
            Kvp017ReadEpochReportFailure.SCHEMA_VERSION_MISMATCH to
                canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            Kvp017ReadEpochReportFailure.AUTHORITY_MISMATCH to
                canonical.replaceFirst("\"READ_EPOCH\"", "\"OPEN_PROJECT\""),
            Kvp017ReadEpochReportFailure.IDE_BUILD_MISMATCH to
                canonical.replaceFirst("262.9437.185", "262.9437.186"),
            Kvp017ReadEpochReportFailure.COMPARISON_SCOPE_MISMATCH to canonical.replaceFirst(
                "\"ADMITTED_PROJECT_RUNTIME\"",
                "\"GLOBAL_RUNTIME\"",
            ),
            Kvp017ReadEpochReportFailure.SIGNAL_SET_MISMATCH to canonical.replaceFirst(
                "\"ROOT_MODEL\",\n        \"DUMB_MODE_TRACKER\"",
                "\"DUMB_MODE_TRACKER\",\n        \"ROOT_MODEL\"",
            ),
            Kvp017ReadEpochReportFailure.COMPARISON_RELATION_SET_MISMATCH to
                canonical.replaceFirst(
                    "\"SAME\",\n        \"MOVED\"",
                    "\"MOVED\",\n        \"SAME\"",
                ),
            Kvp017ReadEpochReportFailure.OBSERVATION_FAILURE_SET_MISMATCH to
                canonical.replaceFirst(
                    "\"WRONG_THREAD\",\n        \"PROJECT_DISPOSED\"",
                    "\"PROJECT_DISPOSED\",\n        \"WRONG_THREAD\"",
                ),
            Kvp017ReadEpochReportFailure.OBSERVATION_BOUND_MISMATCH to
                canonical.replaceFirst("\"maxVfsEventsPerBatch\": 4096", "\"maxVfsEventsPerBatch\": 4095"),
            Kvp017ReadEpochReportFailure.CASE_SET_MISMATCH to
                canonical.replaceFirst("\"sampleCount\": 2", "\"sampleCount\": 3"),
            Kvp017ReadEpochReportFailure.FORBIDDEN_CONTRACT_OBSERVED to canonical.replaceFirst(
                "\"primitiveCounterEscapeCount\": 0",
                "\"primitiveCounterEscapeCount\": 1",
            ),
            Kvp017ReadEpochReportFailure.FORBIDDEN_EFFECT_OBSERVED to canonical.replaceFirst(
                "\"vfsTraversalCount\": 0",
                "\"vfsTraversalCount\": 1",
            ),
        )
        mutations.forEach { (expected, mutated) ->
            val rejected = AdmittedKvp017ReadEpochReport.admit(mutated)
            val observed = (rejected as? Kvp017ReadEpochReportAdmission.Rejected)?.failure
            if (observed != expected) {
                throw GradleException(
                    "KVP-017 mutation expected $expected but observed $observed",
                )
            }
        }
    }
}
