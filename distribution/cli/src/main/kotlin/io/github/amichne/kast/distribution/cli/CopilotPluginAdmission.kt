package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.Serializable

@Serializable internal data class CopilotMarketplaceRecord(val name: String, val source: String, val isDefault: Boolean)

@Serializable
internal data class CopilotPluginRecord(
    val name: String,
    val marketplace: String,
    val version: String,
    val enabled: Boolean,
    val source: String,
    val installedFrom: String? = null,
)

internal data class CopilotProvisionPlan(
    val bundle: AdmittedPluginBundle,
    val marketplace: MarketplacePresence,
    val plugin: PluginPresence,
) {
    val current: Boolean
        get() = marketplace == MarketplacePresence.ADMITTED && plugin == PluginPresence.CURRENT
}

internal fun admitCopilotMarketplace(
    entries: List<CopilotMarketplaceRecord>,
    bundle: AdmittedPluginBundle,
): PluginObservation<MarketplacePresence> {
    val selected = entries.filter { it.name == KAST_MARKETPLACE_NAME }
    if (selected.isEmpty()) return PluginObservation.Observed(MarketplacePresence.ABSENT)
    if (selected.size != 1) return PluginObservation.Rejected(PluginInstallFailure.MarketplaceConflict)
    val marketplace = selected.single()
    if (!ownedCopilotMarketplace(marketplace, bundle))
        return PluginObservation.Rejected(PluginInstallFailure.MarketplaceConflict)
    return PluginObservation.Observed(
        if (marketplace.source == "Local: ${bundle.marketplace}") MarketplacePresence.ADMITTED
        else MarketplacePresence.OWNED_OTHER
    )
}

private fun ownedCopilotMarketplace(entry: CopilotMarketplaceRecord, bundle: AdmittedPluginBundle): Boolean =
    !entry.isDefault &&
        entry.source.startsWith("Local: ") &&
        bundle.ownsMarketplace(entry.source.removePrefix("Local: "))

internal fun admitCopilotPlugin(
    entries: List<CopilotPluginRecord>,
    bundle: AdmittedPluginBundle,
): PluginObservation<PluginPresence> {
    val selected = entries.filter { it.name == "kast" || it.marketplace == "kast" }
    if (selected.isEmpty()) return PluginObservation.Observed(PluginPresence.ABSENT)
    if (selected.size != 1) return PluginObservation.Rejected(PluginInstallFailure.PluginConflict)
    val plugin = selected.single()
    if (!ownedCopilotPlugin(plugin, bundle)) return PluginObservation.Rejected(PluginInstallFailure.PluginConflict)
    return PluginObservation.Observed(
        if (currentCopilotPlugin(plugin, bundle)) PluginPresence.CURRENT else PluginPresence.ADMITTED
    )
}

private fun ownedCopilotPlugin(plugin: CopilotPluginRecord, bundle: AdmittedPluginBundle): Boolean =
    plugin.name == "kast" && plugin.marketplace == "kast" && ownedCopilotSource(plugin, bundle)

private fun ownedCopilotSource(plugin: CopilotPluginRecord, bundle: AdmittedPluginBundle): Boolean =
    plugin.source == "live" && plugin.installedFrom?.let(bundle::ownsMarketplace) == true

private fun currentCopilotPlugin(plugin: CopilotPluginRecord, bundle: AdmittedPluginBundle): Boolean =
    plugin.version == bundle.version.value && plugin.enabled && selectedCopilotResources(plugin, bundle)

private fun selectedCopilotResources(plugin: CopilotPluginRecord, bundle: AdmittedPluginBundle): Boolean =
    plugin.installedFrom == bundle.marketplace.toString() && bundle.resourcesMatch(bundle.plugin)
