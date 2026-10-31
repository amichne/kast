package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable internal data class GuidanceFile(val path: String, val sha256: String)

@Serializable
internal data class ManagedGuidance(val harness: Harness, val destination: String, val files: List<GuidanceFile>)

internal fun installGuidance(
    root: Path,
    home: Path,
    target: ProvisionTarget,
    environment: Map<String, String>,
): ProvisionOutcome =
    when (target) {
        is ProvisionTarget.Skill -> exportCliSkill(root, Path.of(target.output ?: ".agents/skills"))
        is ProvisionTarget.Provider ->
            when (val contract = inspectGuidanceCommand(root, home, environment)) {
                is PluginObservation.Rejected ->
                    ProvisionOutcome.Rejected(
                        ProvisionStage.NATIVE_CONTRACT_INSPECTION,
                        pluginFailure(contract.failure),
                        target.harness.publicName,
                    )
                is PluginObservation.Observed ->
                    when (target.harness) {
                        Harness.CODEX -> codexGuidance(root, home, target.composition, environment)
                        Harness.COPILOT -> installCopilotGuidance(root, home, target.composition, environment)
                        Harness.PI -> installPiGuidance(root, home, target.composition, environment)
                    }
            }
    }

internal fun presentProvisioning(outcome: ProvisionOutcome, json: Boolean) {
    if (json) println(managementJson.encodeToString(outcome))
    when (outcome) {
        is ProvisionOutcome.Installed -> {
            if (!json) println("Installed Kast guidance at ${outcome.destination}")
            System.err.println(FRESH_GUIDANCE_SESSION)
        }
        is ProvisionOutcome.AlreadyInstalled -> {
            System.err.println("kast: guidance at ${outcome.destination} is already installed")
            System.err.println(FRESH_GUIDANCE_SESSION)
            if (!json) println("ALREADY_INSTALLED ${outcome.destination}")
        }
        is ProvisionOutcome.Rejected -> {
            if (!json) println("REJECTED ${outcome.stage} ${outcome.reason}")
            System.err.println("kast: ${outcome.stage}: ${outcome.reason}: ${outcome.destination}")
            if (outcome.reason == ProvisionFailure.SKILL_EXISTS)
                System.err.println("kast: select another --output directory or explicitly remove the existing skill")
            kotlin.system.exitProcess(1)
        }
        is ProvisionOutcome.RecoveryRequired -> {
            if (!json) println("RECOVERY_REQUIRED ${outcome.stage} ${outcome.reason}")
            System.err.println(
                "kast: ${outcome.stage}: ${outcome.reason}: ${outcome.destination}; " +
                    "inspect provider resources before another setup"
            )
            kotlin.system.exitProcess(1)
        }
    }
}

private const val FRESH_GUIDANCE_SESSION = "kast: start a fresh agent session to use the selected guidance"

private fun codexGuidance(
    root: Path,
    home: Path,
    composition: HarnessComposition,
    environment: Map<String, String>,
): ProvisionOutcome {
    val rawHome = environment["CODEX_HOME"] ?: home.resolve(".codex").toString()
    val codexHome =
        when (val admitted = ConnectionDirectory.admit(rawHome)) {
            is ConnectionDirectoryAdmission.Admitted -> admitted.directory.path
            is ConnectionDirectoryAdmission.Rejected ->
                return ProvisionOutcome.Rejected(
                    ProvisionStage.DESTINATION_ADMISSION,
                    ProvisionFailure.DESTINATION_UNAVAILABLE,
                    rawHome,
                )
        }
    return when (
        val outcome =
            installCodexPlugin(
                root,
                home,
                codexHome = codexHome,
                executeCodex = { runGuidanceProvider(it, environment + ("HOME" to home.toString())) },
                composition = composition,
                executeDependency = { arguments, selected ->
                    runGuidanceProvider(arguments, environment + selected, MCP_SETUP_SECONDS)
                },
            )
    ) {
        is PluginInstallOutcome.Installed -> ProvisionOutcome.Installed(codexHome.toString())
        is PluginInstallOutcome.AlreadyInstalled -> ProvisionOutcome.AlreadyInstalled(codexHome.toString())
        is PluginInstallOutcome.Rejected ->
            ProvisionOutcome.Rejected(
                provisionStage(outcome.stage),
                pluginFailure(outcome.failure),
                codexHome.toString(),
            )
        is PluginInstallOutcome.RecoveryRequired ->
            ProvisionOutcome.RecoveryRequired(
                provisionStage(outcome.stage),
                pluginFailure(outcome.failure),
                codexHome.toString(),
            )
    }
}

internal fun pluginFailure(failure: PluginInstallFailure): ProvisionFailure =
    when (failure) {
        PluginInstallFailure.InstallationUnavailable -> ProvisionFailure.INSTALLATION_UNAVAILABLE
        PluginInstallFailure.PayloadRejected -> ProvisionFailure.PAYLOAD_REJECTED
        PluginInstallFailure.MarketplaceConflict,
        PluginInstallFailure.PluginConflict,
        PluginInstallFailure.LegacyRegistrationRejected -> ProvisionFailure.PROVIDER_CONFLICT
        PluginInstallFailure.ProcessUnavailable -> ProvisionFailure.PROVIDER_UNAVAILABLE
        PluginInstallFailure.VerificationRejected -> ProvisionFailure.RESOURCE_MISMATCH
        PluginInstallFailure.ObservationRejected,
        PluginInstallFailure.InstallationLockUnavailable,
        is PluginInstallFailure.ProcessExited -> ProvisionFailure.PROVIDER_REJECTED
    }

private fun provisionStage(stage: PluginInstallStage): ProvisionStage =
    when (stage) {
        PluginInstallStage.INSTALLATION_LOCK -> ProvisionStage.INSTALLATION_LOCK
        PluginInstallStage.PAYLOAD_ADMISSION -> ProvisionStage.PAYLOAD_ADMISSION
        PluginInstallStage.LEGACY_REGISTRATION_INSPECTION -> ProvisionStage.LEGACY_REGISTRATION_INSPECTION
        PluginInstallStage.MARKETPLACE_INSPECTION -> ProvisionStage.MARKETPLACE_INSPECTION
        PluginInstallStage.PLUGIN_INSPECTION -> ProvisionStage.PLUGIN_INSPECTION
        PluginInstallStage.MARKETPLACE_REGISTRATION -> ProvisionStage.MARKETPLACE_REGISTRATION
        PluginInstallStage.PLUGIN_INSTALLATION -> ProvisionStage.PLUGIN_INSTALLATION
        PluginInstallStage.VERIFICATION -> ProvisionStage.RESOURCE_INSPECTION
        PluginInstallStage.LEGACY_REGISTRATION_REMOVAL -> ProvisionStage.LEGACY_REGISTRATION_REMOVAL
        PluginInstallStage.DEPENDENCY_PREPARATION -> ProvisionStage.DEPENDENCY_PREPARATION
    }

internal fun prepareOptionalMcp(
    bundle: AdmittedPluginBundle,
    home: Path,
    execute: (List<String>, Map<String, String>) -> ProcessObservation = { arguments, environment ->
        runGuidanceProvider(arguments, environment, timeoutSeconds = MCP_SETUP_SECONDS)
    },
): PluginObservation<Unit> {
    if (bundle.composition == HarnessComposition.CliOnly) return PluginObservation.Observed(Unit)
    val script = bundle.plugin.resolve("skills/kast/scripts/server.py")
    return when (
        val result =
            pluginProcessResult(
                execute(
                    listOf("uv", "sync", "--locked", "--script", script.toString()),
                    mapOf("HOME" to home.toString()),
                )
            )
    ) {
        is PluginObservation.Observed -> PluginObservation.Observed(Unit)
        is PluginObservation.Rejected -> result
    }
}
