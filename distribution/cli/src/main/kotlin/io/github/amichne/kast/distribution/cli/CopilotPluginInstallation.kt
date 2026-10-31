package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val copilotJson = Json { ignoreUnknownKeys = true }

internal fun installCopilotGuidance(
    root: Path,
    home: Path,
    composition: HarnessComposition,
    environment: Map<String, String>,
    execution: GuidanceProviderExecution = GuidanceProviderExecution.native(environment + ("HOME" to home.toString())),
): ProvisionOutcome {
    val result =
        try {
            withRegistrationLock(root) { installAdmittedCopilotGuidance(root, home, composition, execution) }
        } catch (_: ManagementRejected) {
            ProvisionOutcome.Rejected(
                ProvisionStage.PAYLOAD_ADMISSION,
                ProvisionFailure.INSTALLATION_UNAVAILABLE,
                "copilot",
            )
        }
    if (result is ProvisionOutcome.Installed || result is ProvisionOutcome.AlreadyInstalled) {
        try {
            disconnectHarness(root, home, Harness.COPILOT)
        } catch (_: ManagementRejected) {
            return incompleteCopilot(ProvisionStage.PROVIDER_REMOVAL, PluginInstallFailure.LegacyRegistrationRejected)
        }
    }
    return result
}

private fun installAdmittedCopilotGuidance(
    root: Path,
    home: Path,
    composition: HarnessComposition,
    execution: GuidanceProviderExecution,
): ProvisionOutcome {
    val execute = execution.command
    val bundle =
        when (val admission = AdmittedPluginBundle.admit(root, composition)) {
            is PluginObservation.Observed -> admission.value
            is PluginObservation.Rejected -> return rejectedCopilot(ProvisionStage.PAYLOAD_ADMISSION, admission.failure)
        }
    if (!admitLegacyCopilot(root, home))
        return rejectedCopilot(ProvisionStage.PROVIDER_INSPECTION, PluginInstallFailure.PluginConflict)
    val marketplace =
        when (val observation = observeCopilotMarketplace(bundle, execute)) {
            is PluginObservation.Observed -> observation.value
            is PluginObservation.Rejected ->
                return rejectedCopilot(ProvisionStage.MARKETPLACE_INSPECTION, observation.failure)
        }
    val plugin =
        when (val observation = observeCopilotPlugin(bundle, execute)) {
            is PluginObservation.Observed -> observation.value
            is PluginObservation.Rejected ->
                return rejectedCopilot(ProvisionStage.PLUGIN_INSPECTION, observation.failure)
        }
    if (marketplace == MarketplacePresence.ABSENT && plugin != PluginPresence.ABSENT)
        return rejectedCopilot(ProvisionStage.PLUGIN_INSPECTION, PluginInstallFailure.PluginConflict)
    when (val preparation = prepareOptionalMcp(bundle, home, execution.dependency)) {
        is PluginObservation.Observed -> Unit
        is PluginObservation.Rejected ->
            return rejectedCopilot(ProvisionStage.DEPENDENCY_PREPARATION, preparation.failure)
    }
    val plan = CopilotProvisionPlan(bundle, marketplace, plugin)
    return if (plan.current) ProvisionOutcome.AlreadyInstalled(bundle.plugin.toString())
    else applyCopilotPlan(plan, execute)
}

private fun admitLegacyCopilot(root: Path, home: Path): Boolean {
    val prior = admittedReceipt(root).registrations.singleOrNull { it.connection == HarnessConnection.COPILOT }
    val path =
        prior?.let { Path.of(it.destination) } ?: registrationDestinationFor(root, home, HarnessConnection.COPILOT)
    if (!Files.exists(path, NOFOLLOW_LINKS)) return true
    if (prior == null) return false
    return Files.isRegularFile(path, NOFOLLOW_LINKS) && sha256(path) == prior.payloadSha256
}

private fun applyCopilotPlan(
    plan: CopilotProvisionPlan,
    execute: (List<String>) -> ProcessObservation,
): ProvisionOutcome {
    if (plan.marketplace == MarketplacePresence.OWNED_OTHER) {
        when (val removal = retireCopilotMarketplace(plan, execute)) {
            is PluginObservation.Observed -> Unit
            is PluginObservation.Rejected -> return incompleteCopilot(ProvisionStage.PROVIDER_REMOVAL, removal.failure)
        }
    }
    if (plan.marketplace != MarketplacePresence.ADMITTED) {
        when (val registration = registerCopilotMarketplace(plan.bundle, execute)) {
            is PluginObservation.Observed -> Unit
            is PluginObservation.Rejected ->
                return incompleteCopilot(ProvisionStage.MARKETPLACE_REGISTRATION, registration.failure)
        }
    }
    when (val installed = pluginProcessResult(execute(listOf("copilot", "plugin", "install", "kast@kast")))) {
        is PluginObservation.Observed -> Unit
        is PluginObservation.Rejected -> return incompleteCopilot(ProvisionStage.PLUGIN_INSTALLATION, installed.failure)
    }
    return when (val admitted = observeCopilotPlugin(plan.bundle, execute)) {
        is PluginObservation.Observed ->
            if (admitted.value == PluginPresence.CURRENT) ProvisionOutcome.Installed(plan.bundle.plugin.toString())
            else incompleteCopilot(ProvisionStage.RESOURCE_INSPECTION, PluginInstallFailure.VerificationRejected)
        is PluginObservation.Rejected -> incompleteCopilot(ProvisionStage.RESOURCE_INSPECTION, admitted.failure)
    }
}

private fun retireCopilotMarketplace(
    plan: CopilotProvisionPlan,
    execute: (List<String>) -> ProcessObservation,
): PluginObservation<Unit> {
    val commands = buildList {
        if (plan.plugin != PluginPresence.ABSENT) add(listOf("copilot", "plugin", "uninstall", "kast@kast"))
        add(listOf("copilot", "plugin", "marketplace", "remove", "kast"))
    }
    for (command in commands) when (val result = pluginProcessResult(execute(command))) {
        is PluginObservation.Observed -> Unit
        is PluginObservation.Rejected -> return result
    }
    return PluginObservation.Observed(Unit)
}

private fun registerCopilotMarketplace(
    bundle: AdmittedPluginBundle,
    execute: (List<String>) -> ProcessObservation,
): PluginObservation<Unit> {
    when (
        val result =
            pluginProcessResult(
                execute(listOf("copilot", "plugin", "marketplace", "add", bundle.marketplace.toString()))
            )
    ) {
        is PluginObservation.Observed -> Unit
        is PluginObservation.Rejected -> return result
    }
    return when (val admitted = observeCopilotMarketplace(bundle, execute)) {
        is PluginObservation.Observed ->
            if (admitted.value == MarketplacePresence.ADMITTED) PluginObservation.Observed(Unit)
            else PluginObservation.Rejected(PluginInstallFailure.VerificationRejected)
        is PluginObservation.Rejected -> admitted
    }
}

private fun observeCopilotMarketplace(
    bundle: AdmittedPluginBundle,
    execute: (List<String>) -> ProcessObservation,
): PluginObservation<MarketplacePresence> =
    when (val result = pluginProcessResult(execute(listOf("copilot", "plugin", "marketplace", "list", "--json")))) {
        is PluginObservation.Rejected -> result
        is PluginObservation.Observed ->
            try {
                admitCopilotMarketplace(
                    copilotJson.decodeFromString<List<CopilotMarketplaceRecord>>(result.value),
                    bundle,
                )
            } catch (_: SerializationException) {
                PluginObservation.Rejected(PluginInstallFailure.ObservationRejected)
            }
    }

private fun observeCopilotPlugin(
    bundle: AdmittedPluginBundle,
    execute: (List<String>) -> ProcessObservation,
): PluginObservation<PluginPresence> =
    when (val result = pluginProcessResult(execute(listOf("copilot", "plugin", "list", "--json")))) {
        is PluginObservation.Rejected -> result
        is PluginObservation.Observed ->
            try {
                admitCopilotPlugin(copilotJson.decodeFromString<List<CopilotPluginRecord>>(result.value), bundle)
            } catch (_: SerializationException) {
                PluginObservation.Rejected(PluginInstallFailure.ObservationRejected)
            }
    }

private fun rejectedCopilot(stage: ProvisionStage, failure: PluginInstallFailure) =
    ProvisionOutcome.Rejected(stage, pluginFailure(failure), "copilot")

private fun incompleteCopilot(stage: ProvisionStage, failure: PluginInstallFailure) =
    ProvisionOutcome.RecoveryRequired(stage, pluginFailure(failure), "copilot")
