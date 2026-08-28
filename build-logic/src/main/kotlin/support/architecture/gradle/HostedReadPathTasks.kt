package support.architecture.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import support.architecture.ArchitecturePolicyValidation
import support.architecture.HostedReadInjectionVerification
import support.architecture.HostedReadPathDerivation
import support.architecture.HostedReadPathDeriver
import support.architecture.HostedReadPathReportAdmission
import support.architecture.HostedReadPathPolicy
import support.architecture.HostedReadPathReportPolicy
import support.architecture.HostedReadReportMutationVerification
import support.architecture.Kvp018PredecessorReceiptId
import support.architecture.Kvp018PredecessorReceiptRefinement
import support.architecture.Kvp018PredecessorReceipts
import support.architecture.ModuleId
import support.architecture.admitHostedReadPathReport
import support.architecture.encodeHostedReadPathReport
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

@UntrackedTask(because = "Re-derives every fixed KVP-018 forbidden hosted-read classification")
abstract class VerifyNoHostedRepositoryWalkNegativeTask : DefaultTask() {
    @TaskAction
    fun verify() {
        val architecture = when (val result = canonicalArchitecturePolicy()) {
            is ArchitecturePolicyValidation.Valid -> result.architecture
            is ArchitecturePolicyValidation.Invalid -> reject(
                HostedReadPathTaskFailure.CanonicalArchitectureRejected(result.failures),
            )
        }
        val module = architecture.modules[ModuleId.WORKSPACE_INTELLIJ_READ] ?: reject(
            HostedReadPathTaskFailure.HostedModuleUnavailable(ModuleId.WORKSPACE_INTELLIJ_READ),
        )
        when (val result = HostedReadPathPolicy.verifyInjectedAuthorities(module)) {
            is HostedReadInjectionVerification.Complete -> logger.lifecycle(
                "KVP-018 detected all {} forbidden hosted-read injections",
                result.proof.observations().size,
            )
            is HostedReadInjectionVerification.Rejected -> {
                reject(
                    HostedReadPathTaskFailure.InjectionRejected(
                        result.first,
                        result.additional,
                    ),
                )
            }
        }
    }
}

@CacheableTask
abstract class VerifyNoHostedRepositoryWalkTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compiledClassDirectories: ConfigurableFileCollection

    @get:Input
    abstract val requiredClassNames: ListProperty<String>

    @get:Input
    abstract val runtimeProjectArtifactIdentities: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeProjectArtifactFiles: ConfigurableFileCollection

    @get:Input
    abstract val runtimeExternalArtifactIdentities: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeExternalArtifactFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp016CompletionReceipt: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val kvp017CompletionReceipt: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val classes = when (val result = loadHostedReadClassInputs(compiledClassDirectories.files)) {
            is HostedReadClassInputResult.Loaded -> result.classes
            is HostedReadClassInputResult.Rejected -> reject(
                HostedReadPathTaskFailure.ClassInputRejected(result.failure),
            )
        }
        val architecture = when (val result = canonicalArchitecturePolicy()) {
            is ArchitecturePolicyValidation.Valid -> result.architecture
            is ArchitecturePolicyValidation.Invalid -> reject(
                HostedReadPathTaskFailure.CanonicalArchitectureRejected(result.failures),
            )
        }
        val projectJars = when (val result = loadHostedReadProjectInputs(
            runtimeProjectArtifactIdentities.get(),
            runtimeProjectArtifactFiles.files,
        )) {
            is HostedReadProjectInputResult.Loaded -> result.jars
            is HostedReadProjectInputResult.Rejected -> reject(
                HostedReadPathTaskFailure.ProjectInputRejected(result.failure),
            )
        }
        val externalJars = when (val result = loadHostedReadExternalInputs(
            runtimeExternalArtifactIdentities.get(),
            runtimeExternalArtifactFiles.files,
        )) {
            is HostedReadExternalInputResult.Loaded -> result.jars
            is HostedReadExternalInputResult.Rejected -> reject(
                HostedReadPathTaskFailure.ExternalInputRejected(result.failure),
            )
        }
        val proof = when (val result = HostedReadPathDeriver.derive(
            architecture,
            classes,
            requiredClassNames.get().toSet(),
            projectJars,
            externalJars,
        )) {
            is HostedReadPathDerivation.Derived -> result.proof
            is HostedReadPathDerivation.Rejected -> reject(
                HostedReadPathTaskFailure.DerivationRejected(result),
            )
        }
        val predecessorArtifacts = listOf(
            Kvp018PredecessorReceiptId.KVP_016_COMPLETE to
                kvp016CompletionReceipt.get().asFile.toPath(),
            Kvp018PredecessorReceiptId.KVP_017_COMPLETE to
                kvp017CompletionReceipt.get().asFile.toPath(),
        ).map { (id, path) ->
            when (val observation = observeKvp018PredecessorReceipt(id, path)) {
                is HostedReadPredecessorReceiptObservation.Observed -> observation.artifact
                is HostedReadPredecessorReceiptObservation.Rejected -> reject(observation.failure)
            }
        }
        val predecessors = when (val result = Kvp018PredecessorReceipts.refine(
            predecessorArtifacts,
        )) {
            is Kvp018PredecessorReceiptRefinement.Admitted -> result.receipts
            is Kvp018PredecessorReceiptRefinement.Rejected -> reject(
                HostedReadPathTaskFailure.PredecessorSetRejected(result.failure),
            )
        }
        val report = encodeHostedReadPathReport(proof, predecessors)
        val admittedReport = when (val admission = admitHostedReadPathReport(
            report,
            proof,
            predecessors,
        )) {
            is HostedReadPathReportAdmission.Admitted -> admission.report
            is HostedReadPathReportAdmission.Rejected -> reject(
                HostedReadPathTaskFailure.ReportRejected(admission.failure),
            )
        }
        val verifiedReport = when (val mutations = HostedReadPathReportPolicy.verifyMutations(
            admittedReport,
            proof,
            predecessors,
        )) {
            is HostedReadReportMutationVerification.Complete -> mutations
            is HostedReadReportMutationVerification.Rejected -> reject(
                HostedReadPathTaskFailure.MutationProofRejected(mutations),
            )
        }
        val target = reportFile.get().asFile.toPath()
        try {
            Files.createDirectories(target.parent)
            Files.writeString(
                target,
                verifiedReport.canonicalDocumentAtReportBoundary(),
                StandardCharsets.UTF_8,
            )
        } catch (_: IOException) {
            reject(HostedReadPathTaskFailure.ReportWriteRejected(target))
        } catch (_: SecurityException) {
            reject(HostedReadPathTaskFailure.ReportWriteRejected(target))
        }
        logger.lifecycle(
            "KVP-018 admitted {} hosted classes at {}",
            proof.inventory.classes().size,
            proof.inventory.digest.value,
        )
    }

}

private fun reject(failure: HostedReadPathTaskFailure): Nothing =
    throw GradleException("KVP-018 ${failure.renderAtGradleBoundary()}")
