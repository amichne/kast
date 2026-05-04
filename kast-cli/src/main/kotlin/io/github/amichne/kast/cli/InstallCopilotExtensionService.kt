package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.InstallCopilotExtensionOptions
import io.github.amichne.kast.cli.results.InstallCopilotExtensionResult
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
}
