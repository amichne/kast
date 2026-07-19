package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

internal object MacosHomebrewReceiptLoader {
    private const val schemaVersion = 2
    private const val authority = "macos-homebrew"
    private val rootKeys = setOf("schemaVersion", "authority", "cli", "updatedAt")
    private val cliKeys = setOf("binary", "formulaPrefix", "version")
    private val canonicalKeyPatterns = (rootKeys + cliKeys).associateWith { key ->
        Regex("""(?:\{|,)\s*"${Regex.escape(key)}"\s*:""")
    }

    fun load(
        path: Path = defaultMacosHomebrewReceiptPath(),
    ): MacosHomebrewReceiptLoadResult {
        if (!Files.isRegularFile(path)) {
            return rejected(
                MacosHomebrewReceiptFailure.MISSING,
                "Kast macOS Homebrew CLI receipt is missing at $path; run `kast repair --for machine --apply` with the Homebrew-installed CLI.",
            )
        }
        val raw = runCatching { Files.readString(path) }.getOrElse { error ->
            return invalid(path, error.message)
        }
        if (canonicalKeyPatterns.any { (_, pattern) -> pattern.findAll(raw).count() != 1 }) {
            return invalid(path, "receipt keys must use each canonical spelling exactly once")
        }
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse { error ->
            return invalid(path, error.message)
        }
        return parse(root, path)
    }

    private fun parse(
        root: JsonObject,
        path: Path,
    ): MacosHomebrewReceiptLoadResult {
        val cli = root.objectValue("cli")
        val cliBinary = cli?.path("binary")
        val formulaPrefix = cli?.path("formulaPrefix")
        val cliVersion = cli?.string("version")
        if (
            root.keys != rootKeys ||
            cli?.keys != cliKeys ||
            root.int("schemaVersion") != schemaVersion ||
            root.string("authority") != authority ||
            cliBinary == null ||
            formulaPrefix == null ||
            !cliBinary.isAbsolute ||
            !formulaPrefix.isAbsolute ||
            cliVersion.isNullOrBlank() ||
            cliVersion.any(Char::isWhitespace) ||
            root.string("updatedAt").isNullOrBlank()
        ) {
            return invalid(path, "invalid schema-2 authority projection")
        }
        if (
            !Files.isDirectory(formulaPrefix) ||
            !Files.isRegularFile(cliBinary) ||
            !Files.isExecutable(cliBinary)
        ) {
            return rejected(
                MacosHomebrewReceiptFailure.MISSING_BINARY,
                "Kast Homebrew CLI binary is missing or not executable at $cliBinary; reinstall the Kast formula and run repair.",
            )
        }
        val canonicalFormulaPrefix = runCatching { formulaPrefix.toRealPath() }.getOrNull()
        val canonicalCliBinary = runCatching { cliBinary.toRealPath() }.getOrNull()
        if (
            canonicalFormulaPrefix == null ||
            canonicalCliBinary == null ||
            !canonicalCliBinary.startsWith(canonicalFormulaPrefix) ||
            canonicalFormulaPrefix.fileName?.toString() != cliVersion ||
            canonicalFormulaPrefix.parent?.fileName?.toString() != "kast" ||
            canonicalFormulaPrefix.parent?.parent?.fileName?.toString() != "Cellar"
        ) {
            return rejected(
                MacosHomebrewReceiptFailure.INVALID,
                "Kast Homebrew CLI binary and version do not form an exact Cellar/kast formula authority at $formulaPrefix; reinstall the formula and run repair.",
            )
        }
        return MacosHomebrewReceiptLoadResult.Loaded(
            MacosHomebrewInstallReceipt(
                cliBinary = canonicalCliBinary,
                formulaPrefix = canonicalFormulaPrefix,
                cliVersion = CliImplementationVersion(cliVersion),
            ),
        )
    }

    private fun invalid(path: Path, detail: String?): MacosHomebrewReceiptLoadResult.Rejected =
        rejected(
            MacosHomebrewReceiptFailure.INVALID,
            "Kast macOS Homebrew CLI receipt is invalid at $path; run `kast repair --for machine --apply`: $detail",
        )

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
