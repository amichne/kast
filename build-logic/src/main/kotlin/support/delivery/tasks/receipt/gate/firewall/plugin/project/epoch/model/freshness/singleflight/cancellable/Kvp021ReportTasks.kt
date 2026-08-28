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
abstract class GenerateKvp021CancellableReadReportTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp019CompletionReceipt: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp020CompletionReceipt: RegularFileProperty

    @get:OutputFile abstract val reportFile: RegularFileProperty

    /** Generates and re-admits the exact predecessor-digest-bound KVP-021 report. */
    @TaskAction fun generate() {
        val predecessors = admittedPredecessors(
            kvp019CompletionReceipt,
            kvp020CompletionReceipt,
        )
        val canonical = canonicalKvp021CancellableReadReport(predecessors)
        when (val admission = AdmittedKvp021CancellableReadReport.admit(
            canonical,
            predecessors,
        )) {
            is Kvp021CancellableReadReportAdmission.Admitted -> writeTextAtomically(
                reportFile.get().asFile.toPath(),
                admission.report.canonicalDocument,
            )
            is Kvp021CancellableReadReportAdmission.Rejected -> throw GradleException(
                "KVP-021 report rejected: ${admission.failure}",
            )
        }
    }
}

@UntrackedTask(because = "Rejects every fixed KVP-021 report and gate-evidence mutation")
abstract class VerifyKvp021CancellableReadReportNegativeTask : DefaultTask() {
    @get:InputFile abstract val reportFile: RegularFileProperty
    @get:InputFile abstract val kvp019CompletionReceipt: RegularFileProperty
    @get:InputFile abstract val kvp020CompletionReceipt: RegularFileProperty

    @TaskAction fun verify() {
        val predecessors = admittedPredecessors(
            kvp019CompletionReceipt,
            kvp020CompletionReceipt,
        )
        val canonical = when (val observation = observeKvp021CancellableReadReport(
            reportFile.get().asFile.toPath(),
            predecessors,
        )) {
            is Kvp021ReportFileObservation.Observed -> observation.report.canonicalDocument
            is Kvp021ReportFileObservation.Rejected -> throw GradleException(
                "KVP-021 report rejected: ${observation.failure}",
            )
        }
        when (val result = verifyKvp021ReportMutations(canonical, predecessors)) {
            Kvp021ReportMutationVerification.Complete -> Unit
            is Kvp021ReportMutationVerification.Rejected -> throw GradleException(
                "KVP-021 report mutation rejected incorrectly: ${result.failure}",
            )
        }
        val head = AuthorityGitRevision("0".repeat(40))
        val gate = canonicalKvp021GateExecution(
            Kvp021GateCommand.RED,
            head,
            Kvp021GateExecutionPhase.COMPLETE,
        )
        when (val result = verifyKvp021GateMutations(gate, Kvp021GateCommand.RED, head)) {
            Kvp021GateMutationVerification.Complete -> Unit
            is Kvp021GateMutationVerification.Rejected -> throw GradleException(
                "KVP-021 gate mutation rejected incorrectly: ${result.failure}",
            )
        }
    }
}

private fun admittedPredecessors(
    kvp019: RegularFileProperty,
    kvp020: RegularFileProperty,
): Kvp021ReportPredecessors = when (val result = observeKvp021ReportPredecessors(
    kvp019.get().asFile.toPath(),
    kvp020.get().asFile.toPath(),
)) {
    is Kvp021ReportPredecessorObservation.Observed -> result.predecessors
    is Kvp021ReportPredecessorObservation.Rejected -> throw GradleException(
        "KVP-021 predecessor set rejected: ${result.failure}",
    )
}
