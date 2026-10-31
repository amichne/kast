package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlin.system.exitProcess

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
internal fun perform(command: ManagementCommand) {
    val environment = System.getenv()
    if (command is ManagementCommand.PublicQuery) {
        exitProcess(runInstalledPublicQuery(command.arguments, Path.of("").toAbsolutePath(), environment))
    }
    val home = environment["HOME"] ?: throw ManagementRejected("environment", "HOME is unavailable")
    val root =
        when (val resolved = resolveManagementRoot(environment)) {
            is ManagementRootResolution.Selected -> resolved.root
            is ManagementRootResolution.Rejected -> throw ManagementRejected("environment", resolved.failure.reason)
        }
    when (command) {
        is ManagementCommand.PublicQuery -> error("Public query dispatch already returned")
        is ManagementCommand.Status -> printManagementStatus(root, command.json)
        is ManagementCommand.Install ->
            presentProvisioning(installGuidance(root, Path.of(home), command.target, environment), command.json)
        is ManagementCommand.Versions -> printSelectableBuilds(command.json)
        is ManagementCommand.Disconnect -> {
            val removed = disconnectHarness(root, Path.of(home), command.harness)
            println(
                if (removed) "Removed ${command.harness.publicName} registration"
                else "${command.harness.publicName} was not registered by this installation"
            )
        }
        is ManagementCommand.Lifecycle -> {
            val outcome = executeInstallationLifecycle(root, Path.of(home), environment, command.operation)
            if (command.json) println(outcome.asJson())
            else
                when (outcome) {
                    is LifecycleOutcome.Stopped ->
                        println("Kast shutdown verified; automatic restart and new tool calls are disabled")
                    is LifecycleOutcome.Reinstalled ->
                        println("Reinstalled Kast ${outcome.version}; reconnect affected harnesses")
                    is LifecycleOutcome.Rejected ->
                        System.err.println(
                            "kast: ${outcome.stage}: ${outcome.failure}; ${lifecycleRecovery(outcome.failure)}"
                        )
                    is LifecycleOutcome.Pending ->
                        System.err.println(
                            "kast: installed ${outcome.version}; ${outcome.stage}: ${outcome.failure}; " +
                                lifecycleRecovery(outcome.failure)
                        )
                }
            if (outcome is LifecycleOutcome.Rejected || outcome is LifecycleOutcome.Pending) exitProcess(1)
        }
        is ManagementCommand.Reset -> {
            val scope =
                when (val admitted = PublicResetScope.admit(root, environment)) {
                    is PublicResetAdmission.Admitted -> admitted.scope
                    is PublicResetAdmission.Rejected -> throw ManagementRejected("public-reset", admitted.failure.name)
                }
            val outcome =
                when (command.operation) {
                    ForceResetOperation.UNINSTALL -> resetPublicInstallation(scope)
                    ForceResetOperation.REINSTALL -> reinstallPublicInstallation(scope, environment)
                }
            when (presentForceReset(outcome, command.json)) {
                ForceResetExit.COMPLETE -> Unit
                ForceResetExit.INCOMPLETE -> exitProcess(1)
            }
        }
        is ManagementCommand.Upgrade -> upgradeSelectedBuild(root, Path.of(home), command, environment)
        ManagementCommand.Uninstall -> uninstallInstallation(root, Path.of(home))
        ManagementCommand.Version -> println(MANAGEMENT_VERSION)
        ManagementCommand.Help -> Unit
    }
}

private fun lifecycleRecovery(failure: LifecycleFailure): String =
    when (failure) {
        LifecycleFailure.HOST_RESTART_REQUIRED -> "quit the selected IntelliJ IDEA, then repeat this command"
        LifecycleFailure.REGISTRATION_REPAIR_REQUIRED -> "repair the recorded harness registration with kast install"
        LifecycleFailure.OWNERSHIP_UNPROVEN,
        LifecycleFailure.FENCE_REJECTED,
        LifecycleFailure.CHILD_REJECTED,
        LifecycleFailure.CHILD_DEADLINE_EXCEEDED,
        LifecycleFailure.REQUEST_OWNERSHIP_UNPROVEN,
        LifecycleFailure.REQUESTS_DID_NOT_RETIRE,
        LifecycleFailure.HOST_OBSERVATION_REJECTED,
        LifecycleFailure.INSTALLATION_REJECTED,
        LifecycleFailure.FILESYSTEM_REJECTED ->
            "inspect kast status --json; shutdown fencing and recovery evidence are retained"
    }
