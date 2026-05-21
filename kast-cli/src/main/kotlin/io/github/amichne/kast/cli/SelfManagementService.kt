package io.github.amichne.kast.cli

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.kastConfigHome
import io.github.amichne.kast.cli.results.SelfDoctorResult
import io.github.amichne.kast.cli.results.SelfStatusResult
import io.github.amichne.kast.cli.results.SelfUninstallResult
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class SelfManagementService(
    private val manifestStore: InstallManifestStore = InstallManifestStore(),
    private val configHomeProvider: () -> Path = { kastConfigHome() },
    private val commandAvailability: (String) -> Boolean = ::commandAvailable,
    private val resolveScriptVerifier: (Path, String) -> String? = ::verifyResolveScript,
) {
    fun status(): SelfStatusResult {
        val manifest = manifestStore.read()
        return SelfStatusResult(
            installed = manifest != null,
            manifestPath = manifestStore.manifestPath().toString(),
            manifest = manifest,
        )
    }

    fun doctor(): SelfDoctorResult {
        val manifestPath = manifestStore.manifestPath()
        val manifest = manifestStore.read()
            ?: return SelfDoctorResult(
                installed = false,
                manifestPath = manifestPath.toString(),
                ok = false,
                issues = listOf("Install manifest not found at $manifestPath"),
                warnings = emptyList(),
            )

        val installRoot = manifestStore.installRoot()
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val binary = installRoot.resolve("bin/kast")
        if (!Files.isRegularFile(binary) || !Files.isExecutable(binary)) {
            issues += "Installed binary is missing or not executable: $binary"
        }

        val configFile = configHomeProvider().resolve("config.toml")
        if (!Files.isRegularFile(configFile)) {
            issues += "Config file is missing: $configFile"
        } else {
            runCatching {
                KastConfig.load(installRoot, configHome = configHomeProvider)
            }.onFailure { error ->
                issues += "Config file is invalid: ${error.message ?: configFile}"
            }
        }

        manifest.managedPaths.forEach { managedPath ->
            val resolved = installRoot.resolve(managedPath).normalize()
            if (!Files.exists(resolved)) {
                issues += "Managed path is missing: $managedPath"
            }
        }

        if (manifest.components.contains("backend")) {
            val classpath = installRoot.resolve("backends/current/runtime-libs/classpath.txt")
            if (!Files.isRegularFile(classpath)) {
                issues += "Standalone runtime libs are missing: ${classpath.parent}"
            }
        }

        if (!commandAvailability("python3")) {
            warnings += "python3 is not available; Copilot hook session export will be skipped"
        }

        manifest.repos.forEach { repo ->
            val repoRoot = Path.of(repo.path)
            if (!Files.isDirectory(repoRoot)) {
                issues += "Managed repo is missing: ${repo.path}"
                return@forEach
            }
            listOf(
                ".github/hooks/resolve-kast-cli-path.sh",
                ".github/extensions/kast/scripts/resolve-kast.sh",
            ).forEach { relativePath ->
                resolveScriptVerifier(repoRoot, relativePath)?.let(warnings::add)
            }
        }

        return SelfDoctorResult(
            installed = true,
            manifestPath = manifestPath.toString(),
            ok = issues.isEmpty(),
            issues = issues,
            warnings = warnings,
        )
    }

    fun uninstall(): SelfUninstallResult {
        val manifest = manifestStore.read()
            ?: return SelfUninstallResult(
                skipped = true,
                removedManagedPaths = emptyList(),
                cleanedShellRcFiles = emptyList(),
                removedManifest = false,
                removedInstallRoot = false,
            )

        val installRoot = manifestStore.installRoot()
        val removedManagedPaths = mutableListOf<String>()
        manifest.managedPaths
            .sortedByDescending { it.split('/').size }
            .forEach { managedPath ->
                val resolved = installRoot.resolve(managedPath).normalize()
                if (!resolved.startsWith(installRoot) || !Files.exists(resolved)) {
                    return@forEach
                }
                deletePathRecursively(resolved)
                deleteEmptyDirectoriesUpTo(resolved.parent, installRoot)
                removedManagedPaths += resolved.toString()
            }

        val cleanedShellRcFiles = manifest.shellRcPatches
            .mapNotNull { patch -> patch.file.takeIf { removeShellPatch(Path.of(it), patch.marker, installRoot.resolve("bin")) } }
            .distinct()

        val removedManifest = Files.deleteIfExists(manifestStore.manifestPath())
        val removedInstallRoot = deleteInstallRootIfEmpty(installRoot)

        return SelfUninstallResult(
            skipped = false,
            removedManagedPaths = removedManagedPaths,
            cleanedShellRcFiles = cleanedShellRcFiles,
            removedManifest = removedManifest,
            removedInstallRoot = removedInstallRoot,
        )
    }

    private fun deleteEmptyDirectoriesUpTo(directory: Path?, boundary: Path) {
        var current = directory
        while (current != null && current != boundary && current.startsWith(boundary)) {
            if (!Files.isDirectory(current) || Files.isSymbolicLink(current)) {
                return
            }
            val deleted = try {
                Files.deleteIfExists(current)
            } catch (_: DirectoryNotEmptyException) {
                false
            }
            if (!deleted) {
                return
            }
            current = current.parent
        }
    }

    private fun deleteInstallRootIfEmpty(installRoot: Path): Boolean {
        if (!Files.isDirectory(installRoot)) {
            return false
        }
        return try {
            Files.newDirectoryStream(installRoot).use { stream ->
                if (stream.iterator().hasNext()) {
                    false
                } else {
                    Files.deleteIfExists(installRoot)
                }
            }
        } catch (_: DirectoryNotEmptyException) {
            false
        }
    }

    private fun removeShellPatch(file: Path, marker: String, installBinDir: Path): Boolean {
        if (!Files.isRegularFile(file)) {
            return false
        }
        val lines = Files.readAllLines(file)
        val filtered = mutableListOf<String>()
        var removed = false
        var skipUntil: String? = null
        var skipPathLine = false
        lines.forEach { line ->
            when {
                skipPathLine -> {
                    skipPathLine = false
                    if (line.contains(installBinDir.toString())) {
                        removed = true
                        return@forEach
                    }
                    filtered += line
                }
                skipUntil != null -> {
                    if (line == skipUntil) {
                        removed = true
                        skipUntil = null
                    }
                    return@forEach
                }
                line == marker && marker == PATH_MARKER -> {
                    removed = true
                    skipPathLine = true
                    return@forEach
                }
                line == marker && marker == KAST_ENV_SOURCE_START_MARKER -> {
                    removed = true
                    skipUntil = KAST_ENV_SOURCE_END_MARKER
                    return@forEach
                }
                line == marker && marker == COMPLETION_START_MARKER -> {
                    removed = true
                    skipUntil = COMPLETION_END_MARKER
                    return@forEach
                }
                line == marker -> {
                    removed = true
                    return@forEach
                }
                else -> filtered += line
            }
        }
        if (removed) {
            Files.writeString(file, filtered.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()))
        }
        return removed
    }

    private fun deletePathRecursively(path: Path) {
        if (Files.isSymbolicLink(path) || Files.isRegularFile(path)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    exc?.let { throw it }
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private companion object {
        private const val PATH_MARKER = "# Added by the Kast installer"
        private const val COMPLETION_START_MARKER = "# >>> Kast completion >>>"
        private const val COMPLETION_END_MARKER = "# <<< Kast completion <<<"
        private const val KAST_ENV_SOURCE_START_MARKER = "# >>> kast env >>>"
        private const val KAST_ENV_SOURCE_END_MARKER = "# <<< kast env <<<"

        private fun commandAvailable(command: String): Boolean = runCatching {
            ProcessBuilder("bash", "-lc", "command -v $command >/dev/null 2>&1")
                .start()
                .waitFor() == 0
        }.getOrDefault(false)

        private fun verifyResolveScript(repoRoot: Path, relativePath: String): String? {
            val script = repoRoot.resolve(relativePath).normalize()
            if (!Files.isRegularFile(script)) return "Resolve script is missing: $relativePath"
            if (!Files.isExecutable(script)) return "Resolve script is not executable: $relativePath"
            return null
        }
    }
}
