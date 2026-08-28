package support.delivery

import java.io.IOException
import java.nio.file.Files
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
abstract class GenerateKvp022EpochRevalidationReportTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp021CompletionReceipt: RegularFileProperty

    @get:OutputFile abstract val reportFile: RegularFileProperty

    /** Generates and re-admits the exact KVP-021 completion-digest-bound KVP-022 report. */
    @TaskAction fun generate() {
        val predecessor = admittedKvp022Predecessor(kvp021CompletionReceipt)
        val canonical = canonicalKvp022EpochRevalidationReport(predecessor)
        when (val admission = AdmittedKvp022EpochRevalidationReport.admit(
            canonical,
            predecessor,
        )) {
            is Kvp022EpochRevalidationReportAdmission.Admitted -> writeTextAtomically(
                reportFile.get().asFile.toPath(),
                admission.report.canonicalDocument,
            )
            is Kvp022EpochRevalidationReportAdmission.Rejected -> throw GradleException(
                "KVP-022 report rejected: ${admission.failure}",
            )
        }
    }
}

@UntrackedTask(because = "Rejects every fixed KVP-022 report, predecessor, and gate mutation")
abstract class VerifyKvp022EpochRevalidationReportNegativeTask : DefaultTask() {
    @get:InputFile abstract val reportFile: RegularFileProperty
    @get:InputFile abstract val kvp021CompletionReceipt: RegularFileProperty

    @TaskAction fun verify() {
        val predecessor = admittedKvp022Predecessor(kvp021CompletionReceipt)
        val canonical = when (val observation = observeKvp022EpochRevalidationReport(
            reportFile.get().asFile.toPath(),
            predecessor,
        )) {
            is Kvp022ReportFileObservation.Observed -> observation.report.canonicalDocument
            is Kvp022ReportFileObservation.Rejected -> throw GradleException(
                "KVP-022 report rejected: ${observation.failure}",
            )
        }
        when (val result = verifyKvp022ReportMutations(canonical, predecessor)) {
            Kvp022ReportMutationVerification.Complete -> Unit
            is Kvp022ReportMutationVerification.Rejected -> throw GradleException(
                "KVP-022 report mutation rejected incorrectly: ${result.failure}",
            )
        }
        when (val result = verifyKvp022PredecessorMutation(readPredecessorReceipt())) {
            Kvp022PredecessorMutationVerification.Complete -> Unit
            Kvp022PredecessorMutationVerification.MutationAdmitted -> throw GradleException(
                "KVP-022 forged predecessor receipt was admitted",
            )
            is Kvp022PredecessorMutationVerification.WrongFailure -> throw GradleException(
                "KVP-022 predecessor mutation rejected incorrectly: ${result.observed}",
            )
        }
        val head = AuthorityGitRevision("0".repeat(40))
        val gate = canonicalKvp022GateExecution(
            Kvp022GateCommand.RED,
            head,
            Kvp022GateExecutionPhase.COMPLETE,
        )
        when (val result = verifyKvp022GateMutations(gate, Kvp022GateCommand.RED, head)) {
            Kvp022GateMutationVerification.Complete -> Unit
            is Kvp022GateMutationVerification.Rejected -> throw GradleException(
                "KVP-022 gate mutation rejected incorrectly: ${result.failure}",
            )
        }
    }

    private fun readPredecessorReceipt(): String = try {
        Files.readString(kvp021CompletionReceipt.get().asFile.toPath())
    } catch (failure: IOException) {
        throw GradleException("KVP-022 predecessor receipt could not be read", failure)
    } catch (failure: SecurityException) {
        throw GradleException("KVP-022 predecessor receipt could not be read", failure)
    }
}

private fun admittedKvp022Predecessor(
    receipt: RegularFileProperty,
): Kvp022ReportPredecessor = when (val result = observeKvp022ReportPredecessor(
    receipt.get().asFile.toPath(),
)) {
    is Kvp022ReportPredecessorObservation.Observed -> result.predecessor
    is Kvp022ReportPredecessorObservation.Rejected -> throw GradleException(
        "KVP-022 predecessor rejected: ${result.failure}",
    )
}
