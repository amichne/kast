package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.cli.EmbeddedCopilotExtensionResources
import io.github.amichne.kast.cli.results.VerifyExtensionResult
import java.nio.file.Files
import java.nio.file.Path

internal fun verifyCopilotExtension(cwd: Path): VerifyExtensionResult {
    val cliVersion = currentCliVersion()
    val extensionVersion = readInstalledCopilotExtensionVersion(cwd).orEmpty()
    return VerifyExtensionResult(
        ok = extensionVersion == cliVersion,
        cliVersion = cliVersion,
        extensionVersion = extensionVersion,
    )
}

private fun readInstalledCopilotExtensionVersion(cwd: Path): String? =
    installedCopilotExtensionVersionMarkerCandidates(cwd)
        .firstOrNull(Files::isRegularFile)
        ?.let(Files::readString)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun installedCopilotExtensionVersionMarkerCandidates(cwd: Path): List<Path> {
    val marker = EmbeddedCopilotExtensionResources.VERSION_MARKER_FILE_NAME
    return listOf(
        cwd.resolve(".github").resolve(marker),
        cwd.resolve(marker),
        cwd.resolve("..").resolve("..").resolve(marker),
    )
        .map { it.toAbsolutePath().normalize() }
        .distinct()
}
