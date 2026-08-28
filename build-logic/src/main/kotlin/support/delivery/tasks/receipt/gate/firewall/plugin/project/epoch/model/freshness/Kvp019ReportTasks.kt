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
abstract class GenerateKvp019VfsPassiveReportTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp017CompletionReceipt: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp018CompletionReceipt: RegularFileProperty

    @get:OutputFile abstract val reportFile: RegularFileProperty

    /** Generates and re-admits the exact predecessor-bound KVP-019 report. */
    @TaskAction fun generate() {
        val predecessors = admittedPredecessors()
        val canonical = canonicalKvp019VfsPassiveReport(predecessors)
        when (val admission = AdmittedKvp019VfsPassiveReport.admit(canonical, predecessors)) {
            is Kvp019ReportAdmission.Admitted -> writeTextAtomically(
                reportFile.get().asFile.toPath(),
                admission.report.canonicalDocument,
            )
            is Kvp019ReportAdmission.Rejected -> throw GradleException(
                "KVP-019 report rejected: ${admission.failure}",
            )
        }
    }

    private fun admittedPredecessors(): Kvp019ReportPredecessors = when (
        val result = observeKvp019ReportPredecessors(
            kvp017CompletionReceipt.get().asFile.toPath(),
            kvp018CompletionReceipt.get().asFile.toPath(),
        )
    ) {
        is Kvp019PredecessorObservation.Observed -> result.predecessors
        is Kvp019PredecessorObservation.Rejected -> throw GradleException(
            "KVP-019 predecessor set rejected: ${result.failure}",
        )
    }
}

@UntrackedTask(because = "Rejects every fixed KVP-019 report mutation")
abstract class VerifyKvp019VfsPassiveReportNegativeTask : DefaultTask() {
    @get:InputFile abstract val reportFile: RegularFileProperty
    @get:InputFile abstract val kvp017CompletionReceipt: RegularFileProperty
    @get:InputFile abstract val kvp018CompletionReceipt: RegularFileProperty

    @TaskAction fun verify() {
        val predecessors = when (val observed = observeKvp019ReportPredecessors(
            kvp017CompletionReceipt.get().asFile.toPath(),
            kvp018CompletionReceipt.get().asFile.toPath(),
        )) {
            is Kvp019PredecessorObservation.Observed -> observed.predecessors
            is Kvp019PredecessorObservation.Rejected -> throw GradleException(
                "KVP-019 predecessor set rejected: ${observed.failure}",
            )
        }
        val canonical = when (val observed = observeKvp019Report(
            reportFile.get().asFile.toPath(),
            predecessors,
        )) {
            is Kvp019ReportFileObservation.Observed -> observed.canonical
            is Kvp019ReportFileObservation.Rejected -> throw GradleException(
                "KVP-019 report rejected: ${observed.failure}",
            )
        }
        when (val result = verifyKvp019ReportMutations(canonical, predecessors)) {
            Kvp019ReportMutationVerification.Complete -> Unit
            is Kvp019ReportMutationVerification.Rejected -> throw GradleException(
                "KVP-019 report mutation rejected incorrectly: ${result.failure}",
            )
        }
    }
}
