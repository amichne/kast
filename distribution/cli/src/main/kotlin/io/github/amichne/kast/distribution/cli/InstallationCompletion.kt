package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal enum class InstallationCompletion {
    STAGED,
    ACTIVATE,
}

internal sealed interface InstallationCompletionResult {
    val destination: InstallDestination

    data class Published(override val destination: InstallDestination) : InstallationCompletionResult

    data class ActivationRejected(override val destination: InstallDestination, val failure: LifecycleFailure) :
        InstallationCompletionResult
}

/** Publication precedes recovery of an exact shutdown request; staged installs retain their lifecycle fence. */
internal fun completePublicInstallation(
    root: Path,
    environment: Map<String, String>,
    channel: ReleaseChannel,
    completion: InstallationCompletion,
    execution: LifecycleExecution = LifecycleExecution(),
): InstallationCompletionResult {
    val destination = commitPublicExecutable(root, environment, channel)
    val activated =
        when (completion) {
            InstallationCompletion.STAGED -> LifecycleEffect.Completed
            InstallationCompletion.ACTIVATE -> resumeCompletedInstallation(root, environment, execution)
        }
    return when (activated) {
        LifecycleEffect.Completed -> InstallationCompletionResult.Published(destination)
        is LifecycleEffect.Rejected -> InstallationCompletionResult.ActivationRejected(destination, activated.failure)
    }
}

/** No fence requires no lifecycle effects and establishes no new readiness claim. */
private fun resumeCompletedInstallation(
    root: Path,
    environment: Map<String, String>,
    execution: LifecycleExecution,
): LifecycleEffect {
    val path = root.resolve(SHUTDOWN_FENCE)
    try {
        if (Files.notExists(path, NOFOLLOW_LINKS)) return LifecycleEffect.Completed
    } catch (_: SecurityException) {
        return LifecycleEffect.Rejected(LifecycleFailure.FILESYSTEM_REJECTED)
    }
    execution.observe(LifecycleObservation.started(LifecycleOperation.REINSTALL, LifecycleStage.ACTIVATION))
    val result =
        when (val admitted = admitCompletedLifecycle(root, environment, execution)) {
            LifecycleAdmission.Rejected -> LifecycleEffect.Rejected(LifecycleFailure.OWNERSHIP_UNPROVEN)
            is LifecycleAdmission.Admitted -> admitted.lifecycle.resume()
        }
    execution.observe(
        when (result) {
            LifecycleEffect.Completed ->
                LifecycleObservation.completed(LifecycleOperation.REINSTALL, LifecycleStage.ACTIVATION)
            is LifecycleEffect.Rejected ->
                LifecycleObservation.rejected(LifecycleOperation.REINSTALL, LifecycleStage.ACTIVATION, result.failure)
        }
    )
    return result
}

private fun admitCompletedLifecycle(
    root: Path,
    environment: Map<String, String>,
    execution: LifecycleExecution,
): LifecycleAdmission {
    return try {
        val raw = environment["HOME"] ?: return LifecycleAdmission.Rejected
        val home = Path.of(raw)
        if (!home.isAbsolute || home.normalize() != home) LifecycleAdmission.Rejected
        else InstallationLifecycle.admit(root, home, environment, execution)
    } catch (_: InvalidPathException) {
        LifecycleAdmission.Rejected
    }
}
