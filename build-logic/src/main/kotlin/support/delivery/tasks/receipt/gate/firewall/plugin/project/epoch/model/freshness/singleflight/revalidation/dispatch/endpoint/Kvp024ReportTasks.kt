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
abstract class GenerateKvp024EndpointPublicationReportTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp013CompletionReceipt: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp023CompletionReceipt: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    /** Generates and re-admits the exact two-predecessor-bound KVP-024 report. */
    @TaskAction fun generate() {
        val predecessors = admittedPredecessors()
        val canonical = canonicalKvp024EndpointPublicationReport(predecessors)
        when (val admission = AdmittedKvp024EndpointPublicationReport.admit(
            canonical,
            predecessors,
        )) {
            is Kvp024EndpointPublicationReportAdmission.Admitted -> writeTextAtomically(
                reportFile.get().asFile.toPath(),
                admission.report.canonicalDocument,
            )
            is Kvp024EndpointPublicationReportAdmission.Rejected -> throw GradleException(
                "KVP-024 report rejected: ${admission.failure}",
            )
        }
    }

    private fun admittedPredecessors() = observePredecessors(
        kvp013CompletionReceipt,
        kvp023CompletionReceipt,
    )
}

@UntrackedTask(because = "Rejects every fixed KVP-024 report, predecessor, and gate mutation")
abstract class VerifyKvp024EndpointPublicationReportNegativeTask : DefaultTask() {
    @get:InputFile abstract val reportFile: RegularFileProperty
    @get:InputFile abstract val kvp013CompletionReceipt: RegularFileProperty
    @get:InputFile abstract val kvp023CompletionReceipt: RegularFileProperty

    @TaskAction fun verify() {
        val predecessors = observePredecessors(
            kvp013CompletionReceipt,
            kvp023CompletionReceipt,
        )
        val canonical = when (val observation = observeKvp024EndpointPublicationReport(
            reportFile.get().asFile.toPath(),
            predecessors,
        )) {
            is Kvp024ReportFileObservation.Observed -> observation.report.canonicalDocument
            is Kvp024ReportFileObservation.Rejected -> throw GradleException(
                "KVP-024 report rejected: ${observation.failure}",
            )
        }
        when (val result = verifyKvp024ReportMutations(canonical, predecessors)) {
            Kvp024ReportMutationVerification.Complete -> Unit
            is Kvp024ReportMutationVerification.Rejected -> throw GradleException(
                "KVP-024 report mutation rejected incorrectly: ${result.failure}",
            )
        }
        val receiptBytes = listOf(
            readReceipt(kvp013CompletionReceipt),
            readReceipt(kvp023CompletionReceipt),
        )
        when (val result = verifyKvp024PredecessorMutations(receiptBytes)) {
            Kvp024PredecessorMutationVerification.Complete -> Unit
            is Kvp024PredecessorMutationVerification.MutationAdmitted -> throw GradleException(
                "KVP-024 forged predecessor ${result.id.receiptId} was admitted",
            )
            is Kvp024PredecessorMutationVerification.WrongFailure -> throw GradleException(
                "KVP-024 predecessor ${result.id.receiptId} rejected incorrectly: " +
                    result.observed,
            )
        }
        val head = AuthorityGitRevision("0".repeat(40))
        val gate = canonicalKvp024GateExecution(
            Kvp024GateCommand.RED,
            head,
            Kvp024GateExecutionPhase.COMPLETE,
        )
        when (val result = verifyKvp024GateMutations(gate, Kvp024GateCommand.RED, head)) {
            Kvp024GateMutationVerification.Complete -> Unit
            is Kvp024GateMutationVerification.Rejected -> throw GradleException(
                "KVP-024 gate mutation rejected incorrectly: ${result.failure}",
            )
        }
    }

    private fun readReceipt(receipt: RegularFileProperty): String = try {
        Files.readString(receipt.get().asFile.toPath())
    } catch (failure: IOException) {
        throw GradleException("KVP-024 predecessor receipt could not be read", failure)
    } catch (failure: SecurityException) {
        throw GradleException("KVP-024 predecessor receipt could not be read", failure)
    }
}

private fun observePredecessors(
    kvp013: RegularFileProperty,
    kvp023: RegularFileProperty,
): Kvp024ReportPredecessors = when (val result = observeKvp024ReportPredecessors(
    kvp013.get().asFile.toPath(),
    kvp023.get().asFile.toPath(),
)) {
    is Kvp024PredecessorObservation.Observed -> result.predecessors
    is Kvp024PredecessorObservation.Rejected -> throw GradleException(
        "KVP-024 predecessors rejected: ${result.failure}",
    )
}
