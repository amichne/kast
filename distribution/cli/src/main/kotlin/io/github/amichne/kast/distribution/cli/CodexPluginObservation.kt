package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable private data class CodexMarketplaceListing(val marketplaces: List<CodexMarketplace>)

@Serializable
private data class CodexMarketplace(
    val name: String,
    val root: String,
    val marketplaceSource: CodexMarketplaceSource? = null,
)

@Serializable private data class CodexMarketplaceSource(val sourceType: String, val source: String)

@Serializable private data class CodexPluginListing(val installed: List<CodexPlugin>)

@Serializable
private data class CodexPlugin(
    val pluginId: String,
    val name: String,
    val marketplaceName: String,
    val version: String,
    val installed: Boolean,
    val enabled: Boolean,
    val source: CodexPluginSource,
    val marketplaceSource: CodexMarketplaceSource? = null,
)

@Serializable private data class CodexPluginSource(val source: String, val path: String? = null)

// Codex owns additional catalog policy fields and remote source properties. Only these identity facts are consumed.
private val pluginObservationJson = Json { ignoreUnknownKeys = true }

internal enum class MarketplacePresence {
    ABSENT,
    ADMITTED,
}

internal enum class PluginPresence {
    ABSENT,
    ADMITTED,
    CURRENT,
}

@Suppress("ReturnCount")
internal fun observePluginMarketplace(
    bundle: AdmittedPluginBundle,
    execute: (List<String>) -> ProcessObservation,
): PluginObservation<MarketplacePresence> {
    val output =
        when (val process = pluginProcessResult(execute(listOf("codex", "plugin", "marketplace", "list", "--json")))) {
            is PluginObservation.Observed -> process.value
            is PluginObservation.Rejected -> return process
        }
    val entries =
        try {
            pluginObservationJson.decodeFromString<CodexMarketplaceListing>(output).marketplaces
        } catch (_: SerializationException) {
            return PluginObservation.Rejected(PluginInstallFailure.ObservationRejected)
        }
    val selected = entries.filter { it.name == KAST_MARKETPLACE_NAME }
    if (selected.isEmpty()) return PluginObservation.Observed(MarketplacePresence.ABSENT)
    if (selected.size != 1) return PluginObservation.Rejected(PluginInstallFailure.MarketplaceConflict)
    val marketplace = selected.single()
    return if (
        matchesMarketplacePath(marketplace.root, bundle) &&
            marketplace.marketplaceSource?.let { admitsMarketplaceSource(it, bundle) } == true
    )
        PluginObservation.Observed(MarketplacePresence.ADMITTED)
    else PluginObservation.Rejected(PluginInstallFailure.MarketplaceConflict)
}

@Suppress("ReturnCount", "ComplexCondition")
internal fun observeInstalledPlugin(
    bundle: AdmittedPluginBundle,
    execute: (List<String>) -> ProcessObservation,
): PluginObservation<PluginPresence> {
    val output =
        when (
            val process =
                pluginProcessResult(
                    execute(listOf("codex", "plugin", "list", "--marketplace", KAST_MARKETPLACE_NAME, "--json"))
                )
        ) {
            is PluginObservation.Observed -> process.value
            is PluginObservation.Rejected -> return process
        }
    val entries =
        try {
            pluginObservationJson.decodeFromString<CodexPluginListing>(output).installed
        } catch (_: SerializationException) {
            return PluginObservation.Rejected(PluginInstallFailure.ObservationRejected)
        }
    val selected = entries.filter {
        it.pluginId == KAST_PLUGIN_ID || (it.name == "kast" && it.marketplaceName == KAST_MARKETPLACE_NAME)
    }
    if (selected.isEmpty()) return PluginObservation.Observed(PluginPresence.ABSENT)
    if (selected.size != 1) return PluginObservation.Rejected(PluginInstallFailure.PluginConflict)
    val plugin = selected.single()
    if (!ownsPlugin(plugin, bundle)) return PluginObservation.Rejected(PluginInstallFailure.PluginConflict)
    return PluginObservation.Observed(
        if (plugin.enabled && plugin.version == bundle.version.value) PluginPresence.CURRENT
        else PluginPresence.ADMITTED
    )
}

private fun ownsPlugin(plugin: CodexPlugin, bundle: AdmittedPluginBundle): Boolean =
    plugin.pluginId == KAST_PLUGIN_ID &&
        plugin.name == "kast" &&
        plugin.marketplaceName == KAST_MARKETPLACE_NAME &&
        plugin.installed &&
        plugin.source.source == "local" &&
        plugin.source.path?.let { matchesPluginPath(it, bundle) } == true &&
        plugin.marketplaceSource?.let { admitsMarketplaceSource(it, bundle) } == true

private fun admitsMarketplaceSource(source: CodexMarketplaceSource, bundle: AdmittedPluginBundle): Boolean =
    source.sourceType == "local" && matchesMarketplacePath(source.source, bundle)

private fun matchesMarketplacePath(raw: String, bundle: AdmittedPluginBundle): Boolean =
    matchesPath(raw, bundle.marketplace)

private fun matchesPluginPath(raw: String, bundle: AdmittedPluginBundle): Boolean = matchesPath(raw, bundle.plugin)

private fun matchesPath(raw: String, admitted: Path): Boolean =
    try {
        val observed = Path.of(raw)
        observed.isAbsolute && observed.normalize() == observed && observed == admitted
    } catch (_: IllegalArgumentException) {
        false
    }
