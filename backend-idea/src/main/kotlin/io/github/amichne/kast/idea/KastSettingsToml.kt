package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig

internal fun KastSettingsState.toWorkspaceToml(defaults: KastConfig = KastConfig.defaults()): String {
    val sections = buildList {
        add(
            tomlSection(
                "runtime",
                "defaultBackend" to runtimeDefaultBackend.changedFrom(defaults.runtime.defaultBackend.value),
                "strictPluginMatching" to runtimeStrictPluginMatching.changedFrom(defaults.runtime.strictPluginMatching.value),
            ),
        )
        add(
            tomlSection(
                "projectOpen",
                "profileAutoInit" to projectOpenProfileAutoInit.changedFrom(defaults.projectOpen.profileAutoInit.value),
                "profile" to projectOpenProfile.changedFrom(defaults.projectOpen.profile.value),
                "autoExcludeGit" to projectOpenAutoExcludeGit.changedFrom(defaults.projectOpen.autoExcludeGit.value),
                "gradleLoadEnabled" to projectOpenGradleLoadEnabled.changedFrom(defaults.projectOpen.gradleLoadEnabled.value),
            ),
        )
        add(tomlSection("backends.idea", "enabled" to backendsIdeaEnabled.changedFrom(defaults.backends.idea.enabled.value)))
    }.filter(String::isNotBlank)

    if (sections.isEmpty()) return ""
    return sections.joinToString(separator = System.lineSeparator() + System.lineSeparator(), postfix = System.lineSeparator())
}

internal fun mergePublicWorkspaceToml(
    existingToml: String,
    state: KastSettingsState,
    defaults: KastConfig = KastConfig.defaults(),
): String {
    return mergeManagedToml(existingToml, state.toWorkspaceToml(defaults), managedPublicKeys)
}

internal fun mergeGlobalCodexHooksToml(
    existingToml: String,
    state: KastSettingsState,
    defaults: KastConfig = KastConfig.defaults(),
): String = mergeManagedToml(
    existingToml,
    tomlSection(
        "codex.hooks",
        "enabled" to state.codexHooksEnabled.changedFrom(defaults.codex.hooks.enabled.value),
        "sessionStart" to state.codexSessionStartEnabled.changedFrom(defaults.codex.hooks.sessionStart.value),
        "postToolUse" to state.codexPostToolUseEnabled.changedFrom(defaults.codex.hooks.postToolUse.value),
    ),
    managedGlobalCodexHookKeys,
)

private fun mergeManagedToml(
    existingToml: String,
    managedToml: String,
    managedKeys: Set<String>,
): String {
    val preserved = removeManagedSettings(existingToml, managedKeys).trimEnd()
    val managed = managedToml.trimEnd()
    val merged = listOf(preserved, managed)
        .filter(String::isNotBlank)
        .joinToString(separator = System.lineSeparator() + System.lineSeparator())
    return if (merged.isBlank()) "" else merged + System.lineSeparator()
}

private fun tomlSection(
    name: String,
    vararg entries: Pair<String, Any?>,
): String {
    val activeEntries = entries.filter { (_, value) -> value != null }
    if (activeEntries.isEmpty()) return ""
    return buildString {
        appendLine("[$name]")
        activeEntries.forEach { (key, value) ->
            appendLine("$key = ${tomlValue(checkNotNull(value))}")
        }
    }.trimEnd()
}

private fun <T> T?.changedFrom(defaultValue: T): T? = takeIf { it != defaultValue }

private fun tomlValue(value: Any): String = when (value) {
    is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    is Boolean -> value.toString()
    is Number -> value.toString()
    else -> error("Unsupported TOML value type: ${value::class.java.name}")
}

private val managedPublicKeys = setOf(
    "runtime.defaultbackend",
    "runtime.strictpluginmatching",
    "projectopen.profileautoinit",
    "projectopen.profile",
    "projectopen.autoexcludegit",
    "projectopen.gradleloadenabled",
    "backends.idea.enabled",
)

private val managedGlobalCodexHookKeys = setOf(
    "codex.hooks.enabled",
    "codex.hooks.sessionstart",
    "codex.hooks.posttooluse",
)

private fun removeManagedSettings(toml: String, managedKeys: Set<String>): String {
    if (toml.isBlank()) return ""
    val blocks = toml.lineSequence()
        .fold(mutableListOf(SectionBlock(""))) { blocks, line ->
            val section = line.tomlSectionName()
            if (section != null) {
                blocks.add(SectionBlock(section))
            }
            blocks.last().lines.add(line)
            blocks
        }
    return blocks
        .mapNotNull { block -> block.withoutManagedKeys(managedKeys) }
        .flatMap { it.lines }
        .joinToString(separator = System.lineSeparator())
        .trimEnd()
        .let { if (it.isBlank()) "" else it + System.lineSeparator() }
}

private data class SectionBlock(
    val name: String,
    val lines: MutableList<String> = mutableListOf(),
) {
    fun withoutManagedKeys(managedKeys: Set<String>): SectionBlock? {
        val filtered = copy(lines = lines.filterNot { line -> isManagedLine(line, managedKeys) }.toMutableList())
        val managedSections = managedKeys.map { it.substringBeforeLast(".") }.toSet()
        if (filtered.name.normalizedConfigPath() !in managedSections) return filtered
        val hasEntries = filtered.lines.any { line -> line.isTomlEntry() }
        return filtered.takeIf { hasEntries }
    }

    private fun isManagedLine(line: String, managedKeys: Set<String>): Boolean {
        val separator = line.withoutTomlComment().indexOf('=')
        if (separator <= 0) return false
        val key = line.substring(0, separator).trim()
        return listOf(name, key)
            .filter(String::isNotBlank)
            .joinToString(".")
            .normalizedConfigPath() in managedKeys
    }
}

private fun String.tomlSectionName(): String? {
    val trimmed = withoutTomlComment().trim()
    return trimmed
        .takeIf { it.startsWith("[") && it.endsWith("]") }
        ?.removePrefix("[")
        ?.removeSuffix("]")
}

private fun String.isTomlEntry(): Boolean =
    withoutTomlComment().trim().let { it.isNotBlank() && !it.startsWith("[") && '=' in it }

private fun String.withoutTomlComment(): String {
    var quoted = false
    var quote = '\u0000'
    var escaped = false
    forEachIndexed { index, char ->
        when {
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            quoted && char == quote -> quoted = false
            !quoted && (char == '"' || char == '\'') -> {
                quoted = true
                quote = char
            }
            !quoted && char == '#' -> return substring(0, index)
        }
    }
    return this
}

private fun String.normalizedConfigPath(): String =
    split('.').joinToString(".") { segment -> segment.filterNot { it == '-' || it == '_' }.lowercase() }
