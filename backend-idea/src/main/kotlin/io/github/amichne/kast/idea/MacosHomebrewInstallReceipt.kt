package io.github.amichne.kast.idea

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

internal data class MacosHomebrewInstallReceipt(
    val cliBinary: Path,
    val formulaPrefix: Path,
    val cliVersion: PluginVersion,
    val caskToken: String,
    val pluginVersion: PluginVersion,
)

internal enum class MacosHomebrewReceiptFailure {
    MISSING,
    INVALID,
    VERSION_MISMATCH,
    MISSING_BINARY,
}

internal sealed interface MacosHomebrewReceiptLoadResult {
    data class Loaded(val receipt: MacosHomebrewInstallReceipt) : MacosHomebrewReceiptLoadResult

    data class Rejected(
        val failure: MacosHomebrewReceiptFailure,
        val message: String,
    ) : MacosHomebrewReceiptLoadResult
}

internal object MacosHomebrewReceiptLoader {
    private const val schemaVersion = 1
    private const val authority = "macos-homebrew"

    fun load(
        path: Path = defaultMacosHomebrewReceiptPath(),
        expectedPluginVersion: PluginVersion,
    ): MacosHomebrewReceiptLoadResult {
        if (!Files.isRegularFile(path)) {
            return rejected(
                MacosHomebrewReceiptFailure.MISSING,
                "Kast macOS Homebrew install receipt is missing at $path; rerun the Kast macOS installer.",
            )
        }
        val root = runCatching { Json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrElse { error ->
                return rejected(
                    MacosHomebrewReceiptFailure.INVALID,
                    "Kast macOS Homebrew install receipt is invalid at $path: ${error.message}",
                )
            }
        return parse(root, path, expectedPluginVersion)
    }

    private fun parse(
        root: JsonObject,
        path: Path,
        expectedPluginVersion: PluginVersion,
    ): MacosHomebrewReceiptLoadResult {
        val parsedSchemaVersion = root.int("schemaVersion")
        val parsedAuthority = root.string("authority")
        val cli = root.objectValue("cli")
        val plugin = root.objectValue("plugin")
        val cliBinary = cli?.path("binary")
        val formulaPrefix = cli?.path("formulaPrefix")
        val cliVersion = cli?.string("version")
        val caskToken = plugin?.string("caskToken")
        val pluginVersion = plugin?.string("version")
        val updatedAt = root.string("updatedAt")
        if (
            parsedSchemaVersion != schemaVersion ||
            parsedAuthority != authority ||
            cliBinary == null ||
            formulaPrefix == null ||
            !cliBinary.isAbsolute ||
            !formulaPrefix.isAbsolute ||
            cliVersion.isNullOrBlank() ||
            caskToken.isNullOrBlank() ||
            pluginVersion.isNullOrBlank() ||
            updatedAt.isNullOrBlank()
        ) {
            return rejected(
                MacosHomebrewReceiptFailure.INVALID,
                "Kast macOS Homebrew install receipt has an invalid authority projection at $path.",
            )
        }
        if (cliVersion != expectedPluginVersion.value || pluginVersion != expectedPluginVersion.value) {
            return rejected(
                MacosHomebrewReceiptFailure.VERSION_MISMATCH,
                "Kast macOS Homebrew receipt versions do not match plugin ${expectedPluginVersion.value} at $path.",
            )
        }
        if (
            !Files.isDirectory(formulaPrefix) ||
            !Files.isRegularFile(cliBinary) ||
            !Files.isExecutable(cliBinary)
        ) {
            return rejected(
                MacosHomebrewReceiptFailure.MISSING_BINARY,
                "Kast Homebrew CLI binary is missing or not executable at $cliBinary; rerun the Kast macOS installer.",
            )
        }
        val canonicalFormulaPrefix = runCatching { formulaPrefix.toRealPath() }.getOrNull()
        val canonicalCliBinary = runCatching { cliBinary.toRealPath() }.getOrNull()
        if (
            canonicalFormulaPrefix == null ||
            canonicalCliBinary == null ||
            !canonicalCliBinary.startsWith(canonicalFormulaPrefix)
        ) {
            return rejected(
                MacosHomebrewReceiptFailure.INVALID,
                "Kast Homebrew CLI binary at $cliBinary resolves outside its formula prefix; rerun the Kast macOS installer.",
            )
        }
        return MacosHomebrewReceiptLoadResult.Loaded(
            MacosHomebrewInstallReceipt(
                cliBinary = canonicalCliBinary,
                formulaPrefix = canonicalFormulaPrefix,
                cliVersion = PluginVersion(cliVersion),
                caskToken = caskToken,
                pluginVersion = PluginVersion(pluginVersion),
            ),
        )
    }

    private fun rejected(
        failure: MacosHomebrewReceiptFailure,
        message: String,
    ): MacosHomebrewReceiptLoadResult.Rejected =
        MacosHomebrewReceiptLoadResult.Rejected(failure, message)

    private fun JsonObject.objectValue(key: String): JsonObject? =
        runCatching { get(key)?.jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { get(key)?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun JsonObject.path(key: String): Path? =
        string(key)
            ?.takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { Path.of(raw) }.getOrNull() }
}

internal fun defaultMacosHomebrewReceiptPath(
    userHome: Path = Path.of(System.getProperty("user.home")),
): Path = userHome.resolve("Library/Application Support/Kast/homebrew-install.json")
