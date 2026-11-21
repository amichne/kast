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
        "managed-retire" -> return retireManagedInstallation(root, environment)
        "preflight-reset",
        "reset" -> return internalPublicReset(root, environment, arguments[1])
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
    return when (presentForceReset(resetPublicInstallation(scope), true)) {
        ForceResetExit.COMPLETE -> InternalInstallExit.COMPLETED
        ForceResetExit.INCOMPLETE -> InternalInstallExit.ACTIVATION_REJECTED
    }
}

private enum class InternalInstallExit(val code: Int) {
    COMPLETED(0),
    ACTIVATION_REJECTED(1),
}

private fun retireManagedInstallation(root: Path, environment: Map<String, String>): InternalInstallExit {
    val home = managedRetirementHome(environment)
    // Only managed products are retired here; an installation without a product selection is not owned.
    if (
        java.nio.file.Files.notExists(
            root.resolve("installation/share/kast/product-selection.json"),
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
    )
        throw ManagementRejected("managed-retirement", "product selection is absent")
    return when (val result = retireManagedProduct(root, home, environment)) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> InternalInstallExit.COMPLETED
        is io.github.amichne.kast.kernel.Refinement.Rejected ->
            throw ManagementRejected("managed-retirement", result.failure.name)
    }
}

private fun managedRetirementHome(environment: Map<String, String>): Path =
    Path.of(environment["HOME"] ?: throw ManagementRejected("managed-retirement", "HOME unavailable"))
