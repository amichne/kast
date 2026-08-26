package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@CacheableTask
abstract class GenerateKvp020SingleFlightReportTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp014CompletionReceipt: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp019CompletionReceipt: RegularFileProperty

    @get:OutputFile abstract val reportFile: RegularFileProperty

    /** Generates and re-admits the exact predecessor-bound KVP-020 report. */
    @TaskAction fun generate() {
        val predecessors = admittedPredecessors()
        val canonical = canonicalKvp020SingleFlightReport(predecessors)
        when (val admission = AdmittedKvp020SingleFlightReport.admit(canonical, predecessors)) {
            is Kvp020SingleFlightReportAdmission.Admitted -> writeTextAtomically(
                reportFile.get().asFile.toPath(),
                admission.report.canonicalDocument,
            )
            is Kvp020SingleFlightReportAdmission.Rejected -> throw GradleException(
                "KVP-020 report rejected: ${admission.failure}",
            )
        }
    }

    private fun admittedPredecessors(): Kvp020ReportPredecessors = when (
        val result = observeKvp020ReportPredecessors(
            kvp014CompletionReceipt.get().asFile.toPath(),
            kvp019CompletionReceipt.get().asFile.toPath(),
        )
    ) {
        is Kvp020PredecessorObservation.Observed -> result.predecessors
        is Kvp020PredecessorObservation.Rejected -> throw GradleException(
            "KVP-020 predecessor set rejected: ${result.failure}",
        )
    }
}

@UntrackedTask(because = "Rejects every fixed KVP-020 report mutation")
abstract class VerifyKvp020SingleFlightReportNegativeTask : DefaultTask() {
    @get:InputFile abstract val reportFile: RegularFileProperty
    @get:InputFile abstract val kvp014CompletionReceipt: RegularFileProperty
    @get:InputFile abstract val kvp019CompletionReceipt: RegularFileProperty

    @TaskAction fun verify() {
        val predecessors = when (val observed = observeKvp020ReportPredecessors(
            kvp014CompletionReceipt.get().asFile.toPath(),
            kvp019CompletionReceipt.get().asFile.toPath(),
        )) {
            is Kvp020PredecessorObservation.Observed -> observed.predecessors
            is Kvp020PredecessorObservation.Rejected -> throw GradleException(
                "KVP-020 predecessor set rejected: ${observed.failure}",
            )
        }
        val canonical = when (val observed = observeKvp020SingleFlightReport(
            reportFile.get().asFile.toPath(),
            predecessors,
        )) {
            is Kvp020SingleFlightReportFileObservation.Observed -> observed.canonical
            is Kvp020SingleFlightReportFileObservation.Rejected -> throw GradleException(
                "KVP-020 report rejected: ${observed.failure}",
            )
        }
        when (val result = verifyKvp020SingleFlightReportMutations(canonical, predecessors)) {
            Kvp020SingleFlightReportMutationVerification.Complete -> Unit
            is Kvp020SingleFlightReportMutationVerification.Rejected -> throw GradleException(
                "KVP-020 report mutation rejected incorrectly: ${result.failure}",
            )
        }
        when (val result = verifyKvp020TransitionSetMutation(canonical, predecessors)) {
            Kvp020SingleFlightReportMutationVerification.Complete -> Unit
            is Kvp020SingleFlightReportMutationVerification.Rejected -> throw GradleException(
                "KVP-020 transition mutation rejected incorrectly: ${result.failure}",
            )
        }
    }
}
