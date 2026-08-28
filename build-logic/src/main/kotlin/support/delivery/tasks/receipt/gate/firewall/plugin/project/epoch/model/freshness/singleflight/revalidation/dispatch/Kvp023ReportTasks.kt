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
abstract class GenerateKvp023ReadRuntimeReportTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp009CompletionReceipt: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp016CompletionReceipt: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp022CompletionReceipt: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    /** Generates and re-admits the exact three-predecessor-bound KVP-023 report. */
    @TaskAction fun generate() {
        val predecessors = admittedPredecessors()
        val canonical = canonicalKvp023ReadOnlyGraphReport(predecessors)
        when (val admission = AdmittedKvp023ReadOnlyGraphReport.admit(
            canonical,
            predecessors,
        )) {
            is Kvp023ReadOnlyGraphReportAdmission.Admitted -> writeTextAtomically(
                reportFile.get().asFile.toPath(),
                admission.report.canonicalDocument,
            )
            is Kvp023ReadOnlyGraphReportAdmission.Rejected -> throw GradleException(
                "KVP-023 report rejected: ${admission.failure}",
            )
        }
    }

    private fun admittedPredecessors() = observePredecessors(
        kvp009CompletionReceipt,
        kvp016CompletionReceipt,
        kvp022CompletionReceipt,
    )
}

@UntrackedTask(because = "Rejects every fixed KVP-023 report, predecessor, and gate mutation")
abstract class VerifyKvp023ReadRuntimeReportNegativeTask : DefaultTask() {
    @get:InputFile abstract val reportFile: RegularFileProperty
    @get:InputFile abstract val kvp009CompletionReceipt: RegularFileProperty
    @get:InputFile abstract val kvp016CompletionReceipt: RegularFileProperty
    @get:InputFile abstract val kvp022CompletionReceipt: RegularFileProperty

    @TaskAction fun verify() {
        val predecessors = observePredecessors(
            kvp009CompletionReceipt,
            kvp016CompletionReceipt,
            kvp022CompletionReceipt,
        )
        val canonical = when (val observation = observeKvp023ReadOnlyGraphReport(
            reportFile.get().asFile.toPath(),
            predecessors,
        )) {
            is Kvp023ReportFileObservation.Observed -> observation.report.canonicalDocument
            is Kvp023ReportFileObservation.Rejected -> throw GradleException(
                "KVP-023 report rejected: ${observation.failure}",
            )
        }
        when (val result = verifyKvp023ReportMutations(canonical, predecessors)) {
            Kvp023ReportMutationVerification.Complete -> Unit
            is Kvp023ReportMutationVerification.Rejected -> throw GradleException(
                "KVP-023 report mutation rejected incorrectly: ${result.failure}",
            )
        }
        val receiptBytes = listOf(
            readReceipt(kvp009CompletionReceipt),
            readReceipt(kvp016CompletionReceipt),
            readReceipt(kvp022CompletionReceipt),
        )
        when (val result = verifyKvp023PredecessorMutations(receiptBytes)) {
            Kvp023PredecessorMutationVerification.Complete -> Unit
            is Kvp023PredecessorMutationVerification.MutationAdmitted -> throw GradleException(
                "KVP-023 forged predecessor ${result.id.receiptId} was admitted",
            )
            is Kvp023PredecessorMutationVerification.WrongFailure -> throw GradleException(
                "KVP-023 predecessor ${result.id.receiptId} rejected incorrectly: " +
                    result.observed,
            )
        }
        val head = AuthorityGitRevision("0".repeat(40))
        val gate = canonicalKvp023GateExecution(
            Kvp023GateCommand.RED,
            head,
            Kvp023GateExecutionPhase.COMPLETE,
        )
        when (val result = verifyKvp023GateMutations(gate, Kvp023GateCommand.RED, head)) {
            Kvp023GateMutationVerification.Complete -> Unit
            is Kvp023GateMutationVerification.Rejected -> throw GradleException(
                "KVP-023 gate mutation rejected incorrectly: ${result.failure}",
            )
        }
    }

    private fun readReceipt(receipt: RegularFileProperty): String = try {
        Files.readString(receipt.get().asFile.toPath())
    } catch (failure: IOException) {
        throw GradleException("KVP-023 predecessor receipt could not be read", failure)
    } catch (failure: SecurityException) {
        throw GradleException("KVP-023 predecessor receipt could not be read", failure)
    }
}

private fun observePredecessors(
    kvp009: RegularFileProperty,
    kvp016: RegularFileProperty,
    kvp022: RegularFileProperty,
): Kvp023ReportPredecessors = when (val result = observeKvp023ReportPredecessors(
    kvp009.get().asFile.toPath(),
    kvp016.get().asFile.toPath(),
    kvp022.get().asFile.toPath(),
)) {
    is Kvp023PredecessorObservation.Observed -> result.predecessors
    is Kvp023PredecessorObservation.Rejected -> throw GradleException(
        "KVP-023 predecessors rejected: ${result.failure}",
    )
}
