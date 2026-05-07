package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.InstallCopilotExtensionOptions
import io.github.amichne.kast.cli.results.InstallCopilotExtensionResult
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

internal class InstallCopilotExtensionService(
    embeddedCopilotExtensionResources: EmbeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(),
    cwdProvider: () -> Path = { Path.of(System.getProperty("user.dir", ".")) },
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
        try {
            Json.parseToJsonElement(Files.readString(hooksJson))
        } catch (e: Exception) {
            warnings.add("hooks/hooks.json is not valid JSON: ${e.message}")
            return warnings
        }
        EmbeddedCopilotExtensionResources.MANIFEST
            .filter { it.endsWith(".sh") }
            .forEach { relativePath ->
                val file = installedAt.resolve(relativePath)
                if (!Files.isRegularFile(file)) {
                    warnings.add("$relativePath not found after install")
                } else if (!Files.isExecutable(file)) {
                    warnings.add("$relativePath is not executable after install")
                }
            }
        return warnings
    }
}
