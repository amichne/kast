package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.InstallCopilotExtensionOptions
import io.github.amichne.kast.cli.results.InstallCopilotExtensionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

internal class InstallCopilotExtensionService(
    embeddedCopilotExtensionResources: EmbeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(),
    cwdProvider: () -> Path = { Path.of(System.getProperty("user.dir", ".")) },
    private val manifestStore: InstallManifestStore = InstallManifestStore(),
    private val resolveScriptVerifier: (Path) -> String? = ::defaultResolveScriptWarning,
    private val commandAvailability: (String) -> Boolean = ::defaultCommandAvailability,
) : InstallEmbeddedResourceService<InstallCopilotExtensionOptions, InstallCopilotExtensionResult>(
    bundle = embeddedCopilotExtensionResources,
    errorCode = "INSTALL_COPILOT_EXTENSION_ERROR",
    installedDescription = "kast Copilot extension",
    cwdProvider = cwdProvider,
) {
    override fun installRequest(
        options: InstallCopilotExtensionOptions,
        cwd: Path,
    ): InstallEmbeddedResourceRequest = InstallEmbeddedResourceRequest(
        targetPath = options.targetDir ?: cwd.resolve(".github"),
        force = options.force,
        uninstall = options.uninstall,
    )

    override fun result(
        installedAt: String,
        version: String,
        skipped: Boolean,
    ): InstallCopilotExtensionResult = InstallCopilotExtensionResult(
        installedAt = installedAt,
        version = version,
        skipped = skipped,
    )

    override fun postInstall(targetPath: Path, result: InstallCopilotExtensionResult): InstallCopilotExtensionResult {
        manifestStore.recordRepo(repoRoot(targetPath), result.version)
        val warnings = verify(targetPath)
        return if (warnings.isEmpty()) result else result.copy(warnings = warnings)
    }

    private fun verify(installedAt: Path): List<String> {
        val warnings = mutableListOf<String>()
        val hooksJson = installedAt.resolve("hooks/hooks.json")
        if (!Files.isRegularFile(hooksJson)) {
            warnings.add("hooks/hooks.json not found after install")
            return warnings
        }

        val parsedHooks = try {
            Json.parseToJsonElement(Files.readString(hooksJson))
        } catch (e: Exception) {
            warnings.add("hooks/hooks.json is not valid JSON: ${e.message}")
            return warnings
        }

        referencedHookScripts(parsedHooks).forEach { relativePath ->
            val file = repoRoot(installedAt).resolve(relativePath).normalize()
            if (!Files.isRegularFile(file)) {
                warnings.add("$relativePath not found after install")
            } else if (!Files.isExecutable(file)) {
                warnings.add("$relativePath is not executable after install")
            }
        }

        resolveScriptVerifier(installedAt)?.let(warnings::add)
        if (!commandAvailability("python3")) {
            warnings.add("python3 is not available; Copilot hooks that export session data require python3")
        }
        return warnings
    }

    private fun referencedHookScripts(hooksJson: kotlinx.serialization.json.JsonElement): List<String> {
        val hooks = hooksJson.jsonObject["hooks"]?.jsonObject ?: return emptyList()
        return hooks.values
            .flatMap { hookList -> hookList.jsonArray }
            .mapNotNull { hook -> hook.jsonObject["bash"]?.jsonPrimitive?.contentOrNull }
            .mapNotNull(::extractHookScript)
            .distinct()
    }

    private fun extractHookScript(command: String): String? {
        val match = HOOK_SCRIPT_REGEX.find(command) ?: return null
        return match.groupValues[1]
    }

    private fun repoRoot(targetPath: Path): Path =
        if (targetPath.fileName?.toString() == ".github" && targetPath.parent != null) {
            targetPath.parent
        } else {
            targetPath
        }

    private companion object {
        private val HOOK_SCRIPT_REGEX = Regex("(?:^|\\s)(\\.github/[^\\s]+\\.sh|hooks/[^\\s]+\\.sh)(?:$|\\s)")

        private fun defaultCommandAvailability(command: String): Boolean = runCatching {
            ProcessBuilder("bash", "-lc", "command -v $command >/dev/null 2>&1")
                .start()
                .waitFor() == 0
        }.getOrDefault(false)

        private fun defaultResolveScriptWarning(installedAt: Path): String? {
            val script = installedAt.resolve("hooks/resolve-kast-cli-path.sh")
            if (!Files.isRegularFile(script)) {
                return "hooks/resolve-kast-cli-path.sh not found after install"
            }
            if (!Files.isExecutable(script)) {
                return "hooks/resolve-kast-cli-path.sh is not executable after install"
            }
            return runCatching {
                val process = ProcessBuilder("bash", script.toString())
                    .directory(if (installedAt.fileName?.toString() == ".github") installedAt.parent.toFile() else installedAt.toFile())
                    .start()
                val stdout = process.inputStream.bufferedReader().readText().trim()
                val stderr = process.errorStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                when {
                    exitCode != 0 -> "resolve-kast-cli-path.sh failed: ${if (stderr.isNotBlank()) stderr else "exit $exitCode"}"
                    stdout.isBlank() -> "resolve-kast-cli-path.sh did not print a kast binary path"
                    !Files.isExecutable(Path.of(stdout)) -> "resolve-kast-cli-path.sh resolved a non-executable path: $stdout"
                    else -> null
                }
            }.getOrElse { error -> "resolve-kast-cli-path.sh failed: ${error.message}" }
        }
    }
}
