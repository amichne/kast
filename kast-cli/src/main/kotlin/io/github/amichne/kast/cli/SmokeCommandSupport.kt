package io.github.amichne.kast.cli

import java.nio.file.Files
import java.nio.file.Path

internal class SmokeCommandSupport(
    private val environmentLookup: (String) -> String? = System::getenv,
    private val propertyLookup: (String) -> String? = System::getProperty,
    private val currentCommandPathProvider: () -> Path? = ::currentCommandPath,
) {
    fun plan(options: SmokeOptions): CliExternalProcess {
        val launcherPath = resolveLauncherPath()
        val smokeScriptPath = resolveSmokeScriptPath(launcherPath)
        val command = buildList {
            add("bash")
            add(smokeScriptPath.toString())
            add("--workspace-root=${options.workspaceRoot}")
            options.fileFilter?.let { add("--file=$it") }
            options.sourceSetFilter?.let { add("--source-set=$it") }
            options.symbolFilter?.let { add("--symbol=$it") }
            add("--format=${options.format.cliValue}")
            launcherPath?.let { add("--kast=$it") }
        }
        return CliExternalProcess(
            command = command,
            workingDirectory = options.workspaceRoot,
        )
    }

    private fun resolveLauncherPath(): Path? {
        listOfNotNull(
            environmentLookup("KAST_LAUNCHER_PATH"),
            propertyLookup("kast.wrapper"),
        ).firstNotNullOfOrNull(::resolveExecutablePath)
            ?.let { return it }

        currentCommandPathProvider()
            ?.toAbsolutePath()
            ?.normalize()
            ?.takeIf(Files::isExecutable)
            ?.let { return it }

        return environmentLookup("KAST_CLI_PATH")
            ?.let(::resolveExecutablePath)
    }

    private fun resolveSmokeScriptPath(launcherPath: Path?): Path {
        listOfNotNull(
            environmentLookup("KAST_SMOKE_SCRIPT"),
            propertyLookup("kast.smoke.script"),
        ).asSequence()
            .map(::resolvePath)
            .firstOrNull(Files::isRegularFile)
            ?.let { return it }

        smokeScriptCandidates(launcherPath)
            .firstOrNull(Files::isRegularFile)
            ?.let { return it }

        throw CliFailure(
            code = "SMOKE_SETUP_ERROR",
            message = "Could not locate bundled smoke.sh for `kast smoke`; set KAST_SMOKE_SCRIPT or rebuild the portable layout",
        )
    }

    private fun smokeScriptCandidates(launcherPath: Path?): Sequence<Path> {
        val searchRoots = linkedSetOf<Path>()
        ancestorChain(launcherPath?.parent).forEach(searchRoots::add)
        ancestorChain(resolveWorkingDirectory()).forEach(searchRoots::add)
        return searchRoots.asSequence()
            .map { root -> root.resolve("smoke.sh").toAbsolutePath().normalize() }
    }

    private fun resolveWorkingDirectory(): Path {
        val rawWorkingDirectory = propertyLookup("user.dir") ?: "."
        val workingDirectory = Path.of(rawWorkingDirectory).toAbsolutePath().normalize()
        return if (Files.isDirectory(workingDirectory)) {
            workingDirectory
        } else {
            workingDirectory.parent ?: workingDirectory
        }
    }

    private fun ancestorChain(start: Path?): List<Path> = buildList {
        var current = start?.toAbsolutePath()?.normalize()
        while (current != null) {
            add(current)
            current = current.parent
        }
    }

    private fun resolveExecutablePath(rawPath: String): Path? = rawPath
        .takeIf(String::isNotBlank)
        ?.let(::resolvePath)
        ?.takeIf(Files::isExecutable)

    private fun resolvePath(rawPath: String): Path = Path.of(rawPath).toAbsolutePath().normalize()
}
