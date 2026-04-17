package io.github.amichne.kast.cli

import java.nio.file.Files
import java.nio.file.Path

internal class DemoCommandSupport(
    private val environmentLookup: (String) -> String? = System::getenv,
    private val propertyLookup: (String) -> String? = System::getProperty,
    private val currentCommandPathProvider: () -> Path? = ::currentCommandPath,
) {
    fun plan(options: DemoOptions): CliExternalProcess {
        val launcherPath = resolveLauncherPath()
        val demoScriptPath = resolveDemoScriptPath(launcherPath)
        val command = buildList {
            add("bash")
            add(demoScriptPath.toString())
            add("--workspace-root=${options.workspaceRoot}")
            options.symbolFilter?.let { add("--symbol=$it") }
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

    private fun resolveDemoScriptPath(launcherPath: Path?): Path {
        listOfNotNull(
            environmentLookup("KAST_DEMO_SCRIPT"),
            propertyLookup("kast.demo.script"),
        ).asSequence()
            .map(::resolvePath)
            .firstOrNull(Files::isRegularFile)
            ?.let { return it }

        demoScriptCandidates(launcherPath)
            .firstOrNull(Files::isRegularFile)
            ?.let { return it }

        throw CliFailure(
            code = "DEMO_SETUP_ERROR",
            message = "Could not locate bundled demo.sh for `kast demo`; set KAST_DEMO_SCRIPT or rebuild the portable layout",
        )
    }

    private fun demoScriptCandidates(launcherPath: Path?): Sequence<Path> {
        val searchRoots = linkedSetOf<Path>()
        ancestorChain(launcherPath?.parent).forEach(searchRoots::add)
        ancestorChain(resolveWorkingDirectory()).forEach(searchRoots::add)
        return searchRoots.asSequence()
            .map { root -> root.resolve("demo.sh").toAbsolutePath().normalize() }
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
