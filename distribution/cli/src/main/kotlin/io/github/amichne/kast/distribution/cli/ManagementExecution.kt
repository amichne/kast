package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlin.system.exitProcess

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
internal fun perform(command: ManagementCommand, output: ManagementOutput = ManagementOutput()) {
    val environment = System.getenv()
    if (command is ManagementCommand.PublicQuery) {
        exitProcess(runInstalledPublicQuery(command.arguments, Path.of("").toAbsolutePath(), environment, output))
    }
    val home = environment["HOME"] ?: throw ManagementRejected("environment", "HOME is unavailable")
    val root =
        when (val resolved = resolveManagementRoot(environment)) {
            is ManagementRootResolution.Selected -> resolved.root
            is ManagementRootResolution.Rejected -> throw ManagementRejected("environment", resolved.failure.reason)
        }
    when (command) {
        is ManagementCommand.PublicQuery -> error("Public query dispatch already returned")
        is ManagementCommand.Status -> printManagementStatus(root, command.json, output, command.scope)
        is ManagementCommand.Stop -> executeWorkspaceStop(root, command, environment, output)
        is ManagementCommand.Install ->
            presentProvisioning(installGuidance(root, Path.of(home), command.target, environment), command.json, output)
        is ManagementCommand.Versions -> printSelectableBuilds(command.json, output)
        is ManagementCommand.Upgrade -> upgradeSelectedBuild(root, Path.of(home), command, environment, output)
        ManagementCommand.Uninstall -> uninstallInstallation(root, Path.of(home), present = output::message)
        is ManagementCommand.ForceUninstall -> {
            val scope =
                when (val admitted = PublicResetScope.admit(root, environment)) {
                    is PublicResetAdmission.Admitted -> admitted.scope
                    is PublicResetAdmission.Rejected -> throw ManagementRejected("public-reset", admitted.failure.name)
                }
            when (presentForceReset(resetPublicInstallation(scope), command.json, output)) {
                ForceResetExit.COMPLETE -> Unit
                ForceResetExit.INCOMPLETE -> exitProcess(1)
            }
        }
        is ManagementCommand.UninstallHarness ->
            when (val removal = uninstallHarness(root, Path.of(home), command.harness, environment)) {
                is PluginObservation.Observed -> output.message("Removed ${command.harness.publicName} integration")
                is PluginObservation.Rejected ->
                    throw ManagementRejected("uninstall-harness", pluginFailure(removal.failure).name)
            }
        ManagementCommand.Version -> println(MANAGEMENT_VERSION)
        ManagementCommand.Help -> Unit
    }
}
