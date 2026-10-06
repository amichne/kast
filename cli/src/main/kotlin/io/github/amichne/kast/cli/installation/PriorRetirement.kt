package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.PublishedBrokerServiceCommand
import io.github.amichne.kast.distribution.contract.configuration.SavedConfigurationDocument
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Admission preserves the exact prior command and environment across update sealing and retirement. */
internal class PriorRetirement
private constructor(
    private val prior: Path,
    private val control: PriorServiceControl,
    val daemonExecutable: Path,
    val environment: Map<String, String>,
) {
    val executable: Path
        get() = control.executable

    val command: List<String>
        get() = control.command

    /** Restart the still selected prior without changing its workspace registrations. */
    fun restore(
        failure: InstallationFailure,
        candidate: Path,
        request: InstallationRequest,
    ): InstallationOutcome.Recovery {
        if (Thread.currentThread().isInterrupted)
            return InstallationOutcome.RecoveryRequired(failure, InstallationFailure.INTERRUPTED)
        when (val admission = admitPrior(prior, candidate, request, PriorInspection.PAYLOAD)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return InstallationOutcome.RecoveryRequired(failure, admission.failure)
        }
        return when (
            executeInstallationChild(InstallationChildStage.PRIOR_RECOVERY, control.resumeCommand, environment)
        ) {
            InstallationChildOutcome.COMPLETED -> InstallationOutcome.RolledBack(failure)
            InstallationChildOutcome.EXIT_REJECTED,
            InstallationChildOutcome.DEADLINE_EXCEEDED,
            InstallationChildOutcome.IO_REJECTED,
            InstallationChildOutcome.INTERRUPTED ->
                InstallationOutcome.RecoveryRequired(failure, InstallationFailure.PRIOR_RECOVERY_REJECTED)
        }
    }

    companion object {
        fun admit(prior: Path, request: InstallationRequest): Refinement<PriorRetirement, InstallationFailure> {
            val control =
                when (val admitted = PriorServiceControl.admit(prior)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val saved =
                readPriorConfiguration(prior.resolve("config/environment"))
                    ?: return Refinement.Rejected(InstallationFailure.PRIOR_RETIREMENT_CONFIGURATION_REJECTED)
            val configuration =
                when (val parsed = SavedConfigurationDocument.parse(saved.toByteArray())) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(InstallationFailure.PRIOR_RETIREMENT_CONFIGURATION_REJECTED)
                }
            val recorded =
                PublishedBrokerServiceCommand.retirementEnvironment(
                    installationRoot = prior,
                    userHome = request.home.value,
                    codexHome = request.codexHome.value,
                )
            return Refinement.Refined(
                PriorRetirement(
                    prior,
                    control,
                    prior.resolve("bin/kast"),
                    (recorded?.values
                        ?: priorServiceRetirementEnvironment(
                            prior = prior,
                            home = request.home.value,
                            codexHome = request.codexHome.value,
                            path = System.getenv("PATH") ?: "/usr/bin:/bin",
                            configuration = configuration,
                        )) + ("KAST_OPTS" to request.jvmUserHomeOption.value),
                )
            )
        }
    }
}

internal fun retire(admitted: PriorRetirement): Refinement<Unit, InstallationFailure> =
    executeInstallationChild(
            InstallationChildStage.PRIOR_RETIREMENT,
            admitted.command,
            admitted.environment,
        )
        .priorRetirement()

internal sealed interface PriorServiceControl {
    val executable: Path
    val command: List<String>
    val resumeCommand: List<String>

    data class Private(override val executable: Path) : PriorServiceControl {
        override val command: List<String> = listOf(executable.toString(), "disable")
        override val resumeCommand: List<String> = listOf(executable.toString(), "bootstrap")
    }

    data class Legacy(override val executable: Path) : PriorServiceControl {
        override val command: List<String> = listOf(executable.toString(), "app-server", "disable")
        override val resumeCommand: List<String> = listOf(executable.toString(), "app-server", "enable")
    }

    companion object {
        fun admit(prior: Path): Refinement<PriorServiceControl, InstallationFailure> {
            val privateControl = prior.resolve("share/kast/libexec/kast-service")
            return when {
                Files.exists(privateControl, LinkOption.NOFOLLOW_LINKS) -> {
                    if (!(regularPriorFile(privateControl) && Files.isExecutable(privateControl)))
                        Refinement.Rejected(InstallationFailure.PRIOR_RETIREMENT_EXECUTABLE_REJECTED)
                    else Refinement.Refined(Private(privateControl))
                }
                Files.notExists(privateControl, LinkOption.NOFOLLOW_LINKS) -> {
                    val legacy = prior.resolve("bin/kast-complete")
                    if (!(regularPriorFile(legacy) && Files.isExecutable(legacy)))
                        Refinement.Rejected(InstallationFailure.PRIOR_RETIREMENT_EXECUTABLE_REJECTED)
                    else Refinement.Refined(Legacy(legacy))
                }
                else -> Refinement.Rejected(InstallationFailure.PRIOR_RETIREMENT_EXECUTABLE_REJECTED)
            }
        }
    }
}

internal fun admitPrior(
    prior: Path,
    target: Path,
    request: InstallationRequest,
    inspection: PriorInspection = PriorInspection.STATE,
): Refinement<Unit, InstallationFailure> {
    val lifecycle = target.resolve("share/kast/installation-lifecycle.py")
    if (!regularPriorFile(lifecycle)) return Refinement.Rejected(InstallationFailure.PRIOR_ADMISSION_FILE_REJECTED)
    val child =
        executeInstallationChild(
            inspection.stage,
            listOf(
                "python3",
                lifecycle.toString(),
                "--installation",
                prior.toString(),
                inspection.operation,
                "--json",
            ),
            mapOf(
                "HOME" to request.home.value.toString(),
                "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
                "CODEX_HOME" to request.codexHome.value.toString(),
            ),
        )
    return child.priorAdmission()
}

/** Live state becomes snapshot evidence only after the admitted service has retired. */
internal enum class PriorInspection(val operation: String, val stage: InstallationChildStage) {
    PAYLOAD("inspect-payload", InstallationChildStage.PRIOR_PAYLOAD_ADMISSION),
    STATE("inspect", InstallationChildStage.PRIOR_ADMISSION),
}

private fun InstallationChildOutcome.priorAdmission(): Refinement<Unit, InstallationFailure> =
    when (this) {
        InstallationChildOutcome.COMPLETED -> Refinement.Refined(Unit)
        InstallationChildOutcome.EXIT_REJECTED -> Refinement.Rejected(InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED)
        InstallationChildOutcome.DEADLINE_EXCEEDED ->
            Refinement.Rejected(InstallationFailure.PRIOR_ADMISSION_DEADLINE_EXCEEDED)
        InstallationChildOutcome.IO_REJECTED -> Refinement.Rejected(InstallationFailure.PRIOR_ADMISSION_IO_REJECTED)
        InstallationChildOutcome.INTERRUPTED -> Refinement.Rejected(InstallationFailure.PRIOR_ADMISSION_INTERRUPTED)
    }

private fun InstallationChildOutcome.priorRetirement(): Refinement<Unit, InstallationFailure> =
    when (this) {
        InstallationChildOutcome.COMPLETED -> Refinement.Refined(Unit)
        InstallationChildOutcome.EXIT_REJECTED ->
            Refinement.Rejected(InstallationFailure.PRIOR_RETIREMENT_EXIT_REJECTED)
        InstallationChildOutcome.DEADLINE_EXCEEDED ->
            Refinement.Rejected(InstallationFailure.PRIOR_RETIREMENT_DEADLINE_EXCEEDED)
        InstallationChildOutcome.IO_REJECTED -> Refinement.Rejected(InstallationFailure.PRIOR_RETIREMENT_IO_REJECTED)
        InstallationChildOutcome.INTERRUPTED -> Refinement.Rejected(InstallationFailure.PRIOR_RETIREMENT_INTERRUPTED)
    }

private fun regularPriorFile(path: Path): Boolean = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

private fun readPriorConfiguration(path: Path): String? =
    try {
        if (!regularPriorFile(path) || Files.size(path) > SavedConfigurationDocument.MAXIMUM_BYTES) null
        else Files.readString(path)
    } catch (_: java.io.IOException) {
        null
    }
