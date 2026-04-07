package io.github.amichne.kast.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

internal class InstallService(
    private val generateName: (instancesRoot: Path, binDir: Path) -> String = ::generateUniqueInstanceName,
) {
    fun install(options: InstallOptions): InstallResult {
        if (!Files.isRegularFile(options.archivePath)) {
            throw CliFailure(
                code = "INSTALL_ERROR",
                message = "Archive not found: ${options.archivePath}",
            )
        }

        val instanceName = options.instanceName ?: generateName(options.instancesRoot, options.binDir)

        if (!instanceName.matches(Regex("[a-zA-Z0-9._-]+"))) {
            throw CliFailure(
                code = "INSTALL_ERROR",
                message = "Instance name may contain only letters, digits, dot, underscore, and dash",
            )
        }

        val tmpDir = Files.createTempDirectory("kast-install-")
        try {
            val stagingDir = tmpDir.resolve("extract")
            extractZip(options.archivePath, stagingDir)

            val extractedKast = stagingDir.resolve("kast")
            if (!Files.isDirectory(extractedKast)) {
                throw CliFailure(
                    code = "INSTALL_ERROR",
                    message = "Archive ${options.archivePath.fileName} did not contain the expected kast/ directory",
                )
            }

            val instanceRoot = options.instancesRoot.resolve(instanceName)
            if (Files.exists(instanceRoot)) {
                instanceRoot.toFile().deleteRecursively()
            }
            Files.createDirectories(instanceRoot.parent)
            Files.move(extractedKast, instanceRoot)

            val kastLauncher = instanceRoot.resolve("kast")
            val kastNative = instanceRoot.resolve("bin/kast")
            if (!Files.isRegularFile(kastLauncher)) {
                throw CliFailure(
                    code = "INSTALL_ERROR",
                    message = "Installed archive did not contain the kast launcher",
                )
            }
            if (!Files.isRegularFile(kastNative)) {
                throw CliFailure(
                    code = "INSTALL_ERROR",
                    message = "Installed archive did not contain the kast native binary",
                )
            }
            kastLauncher.toFile().setExecutable(true)
            kastNative.toFile().setExecutable(true)

            Files.createDirectories(options.binDir)
            val launcherPath = options.binDir.resolve("kast-$instanceName")
            launcherPath.toFile().writeText(
                buildString {
                    appendLine("#!/usr/bin/env bash")
                    appendLine("set -euo pipefail")
                    appendLine($$"exec \"$$instanceRoot/kast\" \"$@\"")
                },
            )
            launcherPath.toFile().setExecutable(true)

            return InstallResult(
                instanceName = instanceName,
                instanceRoot = instanceRoot.toString(),
                launcherPath = launcherPath.toString(),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    private fun extractZip(archivePath: Path, outputDir: Path) {
        ZipFile(archivePath.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = outputDir.resolve(entry.name).normalize()
                if (!target.startsWith(outputDir)) {
                    throw CliFailure(
                        code = "INSTALL_ERROR",
                        message = "Archive contains an unsafe path entry: ${entry.name}",
                    )
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }
}

private val ADJECTIVES = listOf(
    "agile", "amber", "brisk", "cedar", "clever", "copper", "coral",
    "dapper", "ember", "granite", "juniper", "nimble", "quiet", "silver",
    "spruce", "steady", "swift", "vivid",
)
private val ANIMALS = listOf(
    "badger", "falcon", "fox", "gecko", "heron", "kestrel", "lynx",
    "marten", "otter", "owl", "raven", "stoat", "swift", "tiger",
    "weasel", "wolf", "wren", "yak",
)

private fun generateUniqueInstanceName(instancesRoot: Path, binDir: Path): String {
    repeat(20) {
        val name = "${ADJECTIVES.random()}-${ANIMALS.random()}"
        if (!Files.exists(instancesRoot.resolve(name)) && !Files.exists(binDir.resolve("kast-$name"))) {
            return name
        }
    }
    throw CliFailure(
        code = "INSTALL_ERROR",
        message = "Could not generate a unique instance name; pass --instance explicitly",
    )
}
