package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SelfManagementServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun statusReadsInstallManifest() {
        val installRoot = tempDir.resolve("home/.kast")
        val manifestStore = InstallManifestStore(installRootProvider = { installRoot })
        val manifest = InstallManifest(
            version = "1.2.3",
            installedAt = "2026-01-01T00:00:00Z",
            platform = "macos-arm64",
            components = listOf("cli", "backend"),
            managedPaths = listOf("bin", "current"),
            repos = listOf(
                ManagedRepo(
                    path = tempDir.resolve("workspace").toString(),
                    copilotExtensionVersion = "1.2.3",
                ),
            ),
        )
        manifestStore.write(manifest)
        val service = SelfManagementService(
            manifestStore = manifestStore,
            configHomeProvider = { tempDir.resolve("config") },
            commandAvailability = { true },
            resolveScriptVerifier = { _, _ -> null },
        )

        val result = service.status()

        assertTrue(result.installed)
        assertEquals(manifest, result.manifest)
    }

    @Test
    fun doctorReportsMissingManagedPathAndPythonWarning() {
        val installRoot = tempDir.resolve("home/.kast")
        val repoRoot = tempDir.resolve("workspace")
        val manifestStore = InstallManifestStore(installRootProvider = { installRoot })
        Files.createDirectories(installRoot.resolve("bin"))
        val binary = installRoot.resolve("bin/kast")
        Files.writeString(binary, "#!/usr/bin/env bash\n")
        binary.toFile().setExecutable(true)
        Files.createDirectories(tempDir.resolve("config"))
        Files.writeString(
            tempDir.resolve("config/config.toml"),
            "[cli]\nbinaryPath = \"$binary\"\n",
        )
        manifestStore.write(
            InstallManifest(
                version = "1.2.3",
                installedAt = "2026-01-01T00:00:00Z",
                platform = "macos-arm64",
                components = listOf("cli"),
                managedPaths = listOf("bin/kast", "missing/path"),
                repos = listOf(ManagedRepo(path = repoRoot.toString(), copilotExtensionVersion = "1.2.3")),
            ),
        )
        val service = SelfManagementService(
            manifestStore = manifestStore,
            configHomeProvider = { tempDir.resolve("config") },
            commandAvailability = { command -> command != "python3" },
            resolveScriptVerifier = { _, _ -> null },
        )

        val result = service.doctor()

        assertFalse(result.ok)
        assertTrue(result.issues.any { issue -> issue.contains("missing/path") })
        assertTrue(result.warnings.any { warning -> warning.contains("python3") })
    }

    @Test
    fun uninstallRemovesManagedPathsAndShellRcPatches() {
        val installRoot = tempDir.resolve("home/.kast")
        val manifestStore = InstallManifestStore(installRootProvider = { installRoot })
        val binFile = installRoot.resolve("bin/kast")
        Files.createDirectories(binFile.parent)
        Files.writeString(binFile, "#!/usr/bin/env bash\n")
        val skillDir = installRoot.resolve("lib/skills/kast")
        Files.createDirectories(skillDir)
        Files.writeString(skillDir.resolve("SKILL.md"), "skill")
        val bashrc = tempDir.resolve("home/.bashrc")
        Files.createDirectories(bashrc.parent)
        Files.writeString(
            bashrc,
            """
            before
            # Added by the Kast installer
            export PATH="${installRoot.resolve("bin")}:${'$'}PATH"
            # >>> kast env >>>
            [[ -f "${tempDir.resolve("config/env")}" ]] && source "${tempDir.resolve("config/env")}"
            # <<< kast env <<<
            after
            """.trimIndent() + "\n",
        )
        manifestStore.write(
            InstallManifest(
                version = "1.2.3",
                installedAt = "2026-01-01T00:00:00Z",
                platform = "macos-arm64",
                components = listOf("cli", "skill"),
                managedPaths = listOf("bin/kast", "lib/skills/kast"),
                shellRcPatches = listOf(
                    ShellRcPatch(file = bashrc.toString(), marker = "# Added by the Kast installer"),
                    ShellRcPatch(file = bashrc.toString(), marker = "# >>> kast env >>>"),
                ),
            ),
        )
        val service = SelfManagementService(
            manifestStore = manifestStore,
            configHomeProvider = { tempDir.resolve("config") },
            commandAvailability = { true },
            resolveScriptVerifier = { _, _ -> null },
        )

        val result = service.uninstall()

        assertFalse(Files.exists(binFile))
        assertFalse(Files.exists(skillDir))
        assertFalse(Files.exists(installRoot.resolve(".manifest.json")))
        assertFalse(Files.exists(installRoot))
        assertFalse(Files.readString(bashrc).contains("# Added by the Kast installer"))
        assertFalse(Files.readString(bashrc).contains("# >>> kast env >>>"))
        assertTrue(result.removedManagedPaths.any { removed -> removed.endsWith("bin/kast") })
        assertTrue(result.cleanedShellRcFiles.contains(bashrc.toString()))
    }
}
