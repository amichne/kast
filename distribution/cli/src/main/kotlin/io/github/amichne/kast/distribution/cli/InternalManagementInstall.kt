package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlin.system.exitProcess

internal fun executeInternalInstall(arguments: List<String>) {
    val exit =
        try {
            internalInstall(arguments, System.getenv())
        } catch (failure: ManagementRejected) {
            System.err.println("kast: ${failure.stage}: ${failure.reason}")
            exitProcess(1)
        }
    if (exit != InternalInstallExit.COMPLETED) exitProcess(exit.code)
}

@Suppress("ThrowsCount")
private fun internalInstall(arguments: List<String>, environment: Map<String, String>): InternalInstallExit {
    if (arguments.size != 2) throw ManagementRejected("installer-protocol", "invalid request")
    if ("KAST_INSTALL_ROOT" !in environment)
        throw ManagementRejected("installer-protocol", "installation root unavailable")
    val root =
        when (val resolved = resolveManagementRoot(environment)) {
            is ManagementRootResolution.Selected -> resolved.root
            is ManagementRootResolution.Rejected ->
                throw ManagementRejected("installer-protocol", resolved.failure.reason)
        }
    when (arguments[1]) {
        "preflight" -> {
            println(preflightPublicExecutable(root, environment).path)
            return InternalInstallExit.COMPLETED
        }
        "managed-retire" -> return retireManagedInstallation(root, environment)
        "managed-publish" -> return publishManagedInstallation(root, environment)
        "preflight-reset",
        "reset" -> return internalPublicReset(root, environment, arguments[1])
        "restore-connections" ->
            return when (val restored = restorePublicResetConnections(root, environment)) {
                LifecycleEffect.Completed -> InternalInstallExit.COMPLETED
                is LifecycleEffect.Rejected -> throw ManagementRejected("reset-connections", restored.failure.name)
            }
        "commit" -> return completeInternalInstall(root, environment, InstallationCompletion.STAGED)
        "commit-active" -> return completeInternalInstall(root, environment, InstallationCompletion.ACTIVATE)
        else -> throw ManagementRejected("installer-protocol", "invalid request")
    }
}

private fun internalPublicReset(root: Path, environment: Map<String, String>, action: String): InternalInstallExit {
    val scope =
        when (val admission = PublicResetScope.admit(root, environment)) {
            is PublicResetAdmission.Admitted -> admission.scope
            is PublicResetAdmission.Rejected -> throw ManagementRejected("public-reset", admission.failure.name)
        }
    if (action == "preflight-reset") {
        println(scope.command)
        return InternalInstallExit.COMPLETED
    }
    when (val saved = savePublicResetConnections(root, environment)) {
        LifecycleEffect.Completed -> Unit
        is LifecycleEffect.Rejected -> throw ManagementRejected("reset-connections", saved.failure.name)
    }
    return when (presentForceReset(resetPublicInstallation(scope), true)) {
        ForceResetExit.COMPLETE -> InternalInstallExit.COMPLETED
        ForceResetExit.INCOMPLETE -> InternalInstallExit.ACTIVATION_REJECTED
    }
}

private fun completeInternalInstall(
    root: Path,
    environment: Map<String, String>,
    completion: InstallationCompletion,
): InternalInstallExit {
    val channel =
        when (environment["KAST_MANAGEMENT_CHANNEL"]) {
            "stable" -> ReleaseChannel.STABLE
            "developer" -> ReleaseChannel.DEVELOPER
            else -> throw ManagementRejected("installer-protocol", "release channel unavailable")
        }
    val result = completePublicInstallation(root, environment, channel, completion)
    val destination = result.destination
    println(destination.path)
    if (!destination.onPath)
        System.err.println("kast: installed ${destination.path}; add ${destination.path.parent} to PATH")
    return when (result) {
        is InstallationCompletionResult.Published -> InternalInstallExit.COMPLETED
        is InstallationCompletionResult.ActivationRejected -> InternalInstallExit.ACTIVATION_REJECTED
    }
}

private enum class InternalInstallExit(val code: Int) {
    COMPLETED(0),
    ACTIVATION_REJECTED(1),
}

private fun retireManagedInstallation(root: Path, environment: Map<String, String>): InternalInstallExit {
    val home = managedRetirementHome(environment)
    val admitted = InstallationLifecycle.admit(root, home, environment, LifecycleExecution())
    if (admitted !is LifecycleAdmission.Admitted) throw ManagementRejected("managed-retirement", "ownership unproven")
    return when (val result = admitted.lifecycle.fenceAndRetire(LifecycleOperation.STOP_FORCE)) {
        LifecycleEffect.Completed -> InternalInstallExit.COMPLETED
        is LifecycleEffect.Rejected -> throw ManagementRejected("managed-retirement", result.failure.name)
    }
}

private fun publishManagedInstallation(root: Path, environment: Map<String, String>): InternalInstallExit {
    val selected =
        environment["KAST_MANAGEMENT_SELECTION"]
            ?: throw ManagementRejected("managed-publication", "selection unavailable")
    val publication = ManagementPublication.Selected.admit(root, Path.of(selected))
    val channel =
        if (publication.binding.identity.mainRevision == null) ReleaseChannel.STABLE else ReleaseChannel.DEVELOPER
    val destination = commitPublicExecutable(root, environment, channel, publication)
    println(destination.path)
    return InternalInstallExit.COMPLETED
}

private fun managedRetirementHome(environment: Map<String, String>): Path =
    Path.of(environment["HOME"] ?: throw ManagementRejected("managed-retirement", "HOME unavailable"))
