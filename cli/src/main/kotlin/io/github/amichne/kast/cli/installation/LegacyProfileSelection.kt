package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable

internal data class LegacyProfileProperties(
    val ideaHome: String?,
    val configPath: String?,
    val pluginsPath: String?,
    val selector: String?,
    val vendor: String?,
    val userHome: String?,
    val operatingSystem: String?,
    val customizer: String?,
)

@Serializable
internal enum class LegacyProfileSelectionKind {
    EXPLICIT_CONFIG,
    DEFAULT_MACOS,
}

@Serializable
internal enum class LegacyProfileSelectionFailure {
    HOME_MISMATCH,
    CONFIG_INVALID,
    DEFAULT_FACTS_UNAVAILABLE,
    UNSUPPORTED_OS,
    CUSTOMIZER_UNSUPPORTED,
    PLUGINS_OVERRIDE,
}

internal data class LegacyProfilePaths(val ideaHome: Path, val profile: Path, val kind: LegacyProfileSelectionKind)

/** Mirrors pinned PathManager.getConfigDir and its macOS platformPath branch, without filesystem effects. */
internal fun selectLegacyProfile(
    selectedHome: Path,
    properties: LegacyProfileProperties,
): Refinement<LegacyProfilePaths, LegacyProfileSelectionFailure> {
    val explicitHome = properties.ideaHome
    if (explicitHome != null && Path.of(explicitHome).normalize() != selectedHome)
        return Refinement.Rejected(LegacyProfileSelectionFailure.HOME_MISMATCH)
    if (properties.customizer != null) return Refinement.Rejected(LegacyProfileSelectionFailure.CUSTOMIZER_UNSUPPORTED)
    val selected =
        if (properties.configPath != null) explicitLegacyProfile(selectedHome, properties)
        else defaultLegacyProfile(selectedHome, properties)
    return when (selected) {
        is Refinement.Rejected -> selected
        is Refinement.Refined -> {
            val plugins = properties.pluginsPath
            if (plugins != null && Path.of(plugins).normalize() != selected.value.profile.resolve("plugins"))
                Refinement.Rejected(LegacyProfileSelectionFailure.PLUGINS_OVERRIDE)
            else selected
        }
    }
}

private fun explicitLegacyProfile(
    selectedHome: Path,
    properties: LegacyProfileProperties,
): Refinement<LegacyProfilePaths, LegacyProfileSelectionFailure> {
    val raw = properties.configPath ?: return Refinement.Rejected(LegacyProfileSelectionFailure.CONFIG_INVALID)
    val unquoted =
        if (raw.length > 1 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.lastIndex) else raw
    val expanded =
        if (unquoted.startsWith("~/") && properties.userHome != null) properties.userHome + unquoted.substring(1)
        else unquoted
    val profile = Path.of(expanded).normalize()
    return if (profile.isAbsolute)
        Refinement.Refined(LegacyProfilePaths(selectedHome, profile, LegacyProfileSelectionKind.EXPLICIT_CONFIG))
    else Refinement.Rejected(LegacyProfileSelectionFailure.CONFIG_INVALID)
}

private fun defaultLegacyProfile(
    selectedHome: Path,
    properties: LegacyProfileProperties,
): Refinement<LegacyProfilePaths, LegacyProfileSelectionFailure> {
    if (properties.operatingSystem != "Mac OS X")
        return Refinement.Rejected(LegacyProfileSelectionFailure.UNSUPPORTED_OS)
    val selector = properties.selector
    val vendor = properties.vendor
    val userHome = properties.userHome
    if (selector == null || vendor == null || userHome == null)
        return Refinement.Rejected(LegacyProfileSelectionFailure.DEFAULT_FACTS_UNAVAILABLE)
    if (!SAFE_COMPONENT.matches(selector) || !SAFE_COMPONENT.matches(vendor))
        return Refinement.Rejected(LegacyProfileSelectionFailure.DEFAULT_FACTS_UNAVAILABLE)
    val home = Path.of(userHome)
    if (!home.isAbsolute || home.normalize() != home)
        return Refinement.Rejected(LegacyProfileSelectionFailure.DEFAULT_FACTS_UNAVAILABLE)
    val profile = home.resolve("Library/Application Support").resolve(vendor).resolve(selector)
    return Refinement.Refined(LegacyProfilePaths(selectedHome, profile, LegacyProfileSelectionKind.DEFAULT_MACOS))
}

private val SAFE_COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
