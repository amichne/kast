package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.InstallCopilotExtensionOptions
import io.github.amichne.kast.cli.tty.CliFailure
import io.github.amichne.kast.cli.tty.defaultCliJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class InstallCopilotExtensionServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun installCopiesBundledCopilotExtensionTreeAndWritesVersionMarker() {
        val targetDir = tempDir.resolve(".github")
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.2.3"),
        )

        val result = service.install(
            InstallCopilotExtensionOptions(
                targetDir = targetDir,
                force = false,
            ),
        )

        assertEquals(targetDir.toAbsolutePath().normalize().toString(), result.installedAt)
        assertEquals("1.2.3", result.version)
        assertFalse(result.skipped)
        assertTrue(Files.isRegularFile(targetDir.resolve("agents/kast-orchestrator.md")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/hooks.json")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/session-start.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/record-paths.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/require-skills.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/skill-shadowing.json")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/export-session.py")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/session-end.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("hooks/resolve-kast-cli-path.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/_shared/lib.mjs")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kast/extension.mjs")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kast/scripts/resolve-kast.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kotlin-gradle-loop/extension.mjs")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kotlin-gradle-loop/scripts/gradle/run_task.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kotlin-gradle-loop/scripts/gradle/run_gradle_hook.sh")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kotlin-gradle-loop/scripts/parse/junit_results.py")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kotlin-gradle-loop/scripts/parse/jacoco_report.py")))
        assertTrue(
            Files.isRegularFile(
                targetDir.resolve("extensions/kotlin-gradle-loop/scripts/parse/kotlin_build_report.py"),
            ),
        )
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kotlin-gradle-loop/scripts/state/init_state.py")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kotlin-gradle-loop/scripts/state/get_state.py")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kotlin-gradle-loop/scripts/state/update_state.py")))
        assertTrue(Files.isRegularFile(targetDir.resolve("extensions/kotlin-gradle-loop/scripts/state/record_action.py")))
        assertFalse(Files.readString(targetDir.resolve("extensions/kast/extension.mjs")).contains(".agents"))
        assertFalse(Files.readString(targetDir.resolve("extensions/kotlin-gradle-loop/extension.mjs")).contains(".agents"))
        assertEquals(
            listOf("kast", "kotlin-gradle-loop"),
            readShadowedSkillIds(targetDir.resolve("hooks/skill-shadowing.json")),
        )
        assertEquals("1.2.3", Files.readString(targetDir.resolve(".kast-copilot-version")).trim())
        val resolveScript = Files.readString(targetDir.resolve("extensions/kast/scripts/resolve-kast.sh"))
        assertTrue(resolveScript.contains("\${HOME}/.kast/bin/kast"))
        assertFalse(resolveScript.contains(".local/bin/kast"))
        assertFalse(resolveScript.contains("for _ in 1 2 3 4 5 6"))

        val hookResolver = Files.readString(targetDir.resolve("hooks/resolve-kast-cli-path.sh"))
        assertTrue(hookResolver.contains("extensions/kast/scripts/resolve-kast.sh"))
        assertTrue(hookResolver.contains("read_config_binary_path"))
        assertTrue(hookResolver.contains("\${HOME}/.kast/bin/kast"))
        assertFalse(hookResolver.contains(".local/bin/kast"))

        val extensionSource = Files.readString(targetDir.resolve("extensions/kast/extension.mjs"))
        assertTrue(extensionSource.contains("""join(homedir(), ".kast", "bin", "kast")"""))
        assertTrue(extensionSource.contains("workspace ensure --workspace-root="))
        assertTrue(extensionSource.contains("install copilot-extension --target-dir="))
        assertFalse(extensionSource.contains("KAST EXTENSION BLOCKED"))
    }

    @Test
    fun installMarksPackagedExecutableResourcesExecutableOnPosix() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.getFileStore(tempDir).supportsFileAttributeView("posix"),
            "POSIX executable bits are not supported",
        )
        val targetDir = tempDir.resolve(".github")
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.2.3"),
        )

        service.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))

        listOf(
            "hooks/session-start.sh",
            "extensions/kast/extension.mjs",
            "extensions/kotlin-gradle-loop/extension.mjs",
            "extensions/kotlin-gradle-loop/scripts/parse/junit_results.py",
        ).forEach { relativePath ->
            assertTrue(
                Files.isExecutable(targetDir.resolve(relativePath)),
                "Expected installed resource $relativePath to be executable",
            )
        }
    }

    @Test
    fun uninstallRemovesPackagedCopilotExtensionFilesAndLeavesForeignFiles() {
        val targetDir = tempDir.resolve(".github")
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.2.3"),
        )
        service.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))
        val foreignFile = targetDir.resolve("hooks/foreign.txt")
        Files.writeString(foreignFile, "keep")

        val result = service.install(
            InstallCopilotExtensionOptions(
                targetDir = targetDir,
                force = false,
                uninstall = true,
            ),
        )

        assertEquals(targetDir.toAbsolutePath().normalize().toString(), result.installedAt)
        assertEquals("1.2.3", result.version)
        assertFalse(result.skipped)
        assertFalse(Files.exists(targetDir.resolve(".kast-copilot-version")))
        EmbeddedCopilotExtensionResources.MANIFEST.forEach { relativePath ->
            assertFalse(
                Files.exists(targetDir.resolve(relativePath)),
                "Expected uninstall to remove packaged resource $relativePath",
            )
        }
        assertTrue(Files.isRegularFile(foreignFile))
        assertEquals("keep", Files.readString(foreignFile))
    }

    @Test
    fun uninstallSkipsWhenTargetDoesNotExist() {
        val targetDir = tempDir.resolve(".github")
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.2.3"),
        )

        val result = service.install(
            InstallCopilotExtensionOptions(
                targetDir = targetDir,
                force = false,
                uninstall = true,
            ),
        )

        assertTrue(result.skipped)
        assertEquals("1.2.3", result.version)
        assertFalse(Files.exists(targetDir))
    }

    @Test
    fun installSkipsWhenTheSameVersionIsAlreadyInstalled() {
        val targetDir = tempDir.resolve(".github")
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.2.3"),
        )
        val options = InstallCopilotExtensionOptions(
            targetDir = targetDir,
            force = false,
        )

        service.install(options)
        val result = service.install(options)

        assertTrue(result.skipped)
        assertEquals("1.2.3", result.version)
    }

    @Test
    fun installOverwritesOnlyManagedFilesWhenForced() {
        val targetDir = tempDir.resolve(".github")
        val initialService = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.0.0"),
        )
        val updatedService = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "2.0.0"),
        )

        initialService.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))
        val foreignFile = targetDir.resolve("foreign.txt")
        Files.writeString(foreignFile, "keep")

        val result = updatedService.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = true))

        assertFalse(result.skipped)
        assertEquals("2.0.0", Files.readString(targetDir.resolve(".kast-copilot-version")).trim())
        assertTrue(Files.exists(foreignFile), "Non-manifest files must be preserved during upgrade")
        assertEquals("keep", Files.readString(foreignFile))
    }

    @Test
    fun upgradeWithForcePreservesSubdirectoriesNotInManifest() {
        val targetDir = tempDir.resolve(".github")
        val initialService = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.0.0"),
        )
        val updatedService = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "2.0.0"),
        )

        initialService.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))
        val workflowFile = targetDir.resolve("workflows/ci.yml")
        Files.createDirectories(workflowFile.parent)
        Files.writeString(workflowFile, "name: CI")

        updatedService.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = true))

        assertTrue(Files.isDirectory(targetDir.resolve("workflows")), "workflows/ directory must survive upgrade")
        assertTrue(Files.isRegularFile(workflowFile), "workflows/ci.yml must survive upgrade")
        assertEquals("name: CI", Files.readString(workflowFile))
    }

    @Test
    fun installFailsWithoutForceWhenADifferentVersionIsAlreadyInstalled() {
        val targetDir = tempDir.resolve(".github")
        val initialService = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.0.0"),
        )
        val updatedService = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "2.0.0"),
        )

        initialService.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))

        val failure = assertThrows<CliFailure> {
            updatedService.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))
        }

        assertEquals("INSTALL_COPILOT_EXTENSION_ERROR", failure.code)
        assertTrue(failure.message.contains("--yes=true"))
    }

    @Test
    fun defaultTargetDirIsGithubDirectoryUnderCurrentWorkingDirectory() {
        val cwd = tempDir.resolve("workspace")
        Files.createDirectories(cwd)
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.2.3"),
            cwdProvider = { cwd },
        )

        val result = service.install(InstallCopilotExtensionOptions(targetDir = null, force = false))

        assertEquals(cwd.resolve(".github").toAbsolutePath().normalize().toString(), result.installedAt)
        assertTrue(Files.isRegularFile(cwd.resolve(".github/.kast-copilot-version")))
    }

    private fun readShadowedSkillIds(path: Path): List<String> = defaultCliJson()
        .parseToJsonElement(Files.readString(path))
        .jsonObject
        .getValue("skills")
        .jsonArray
        .map { skill -> skill.jsonObject.getValue("id").jsonPrimitive.content }


    @Test
    fun installRecordsManagedRepoInGlobalManifest() {
        val installRoot = tempDir.resolve("home/.kast")
        val workspace = tempDir.resolve("workspace")
        val targetDir = workspace.resolve(".github")
        Files.createDirectories(workspace)
        val manifestStore = InstallManifestStore(installRootProvider = { installRoot })
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(version = "1.2.3"),
            manifestStore = manifestStore,
            resolveScriptVerifier = { null },
            commandAvailability = { true },
        )

        service.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))

        val manifest = requireNotNull(manifestStore.read())
        assertEquals(
            listOf(
                ManagedRepo(
                    path = workspace.toAbsolutePath().normalize().toString(),
                    copilotExtensionVersion = "1.2.3",
                ),
            ),
            manifest.repos,
        )
    }

    @Test
    fun installCollectsWarningsFromPostInstallVerification() {
        val hooksJson = """
            {
              "version": 1,
              "hooks": {
                "sessionStart": [
                  {
                    "type": "command",
                    "bash": "bash .github/hooks/missing.sh",
                    "cwd": ".",
                    "timeoutSec": 30
                  }
                ]
              }
            }
        """.trimIndent()
        val targetDir = tempDir.resolve("workspace/.github")
        Files.createDirectories(targetDir.parent)
        val service = InstallCopilotExtensionService(
            embeddedCopilotExtensionResources = EmbeddedCopilotExtensionResources(
                version = "1.2.3",
                resourceReader = { relativePath ->
                    when (relativePath) {
                        "hooks/hooks.json" -> ByteArrayInputStream(hooksJson.toByteArray())
                        else -> EmbeddedCopilotExtensionResources::class.java.getResourceAsStream(
                            "/${EmbeddedCopilotExtensionResources.RESOURCE_ROOT}/$relativePath",
                        )
                    }
                },
            ),
            resolveScriptVerifier = { "resolve-kast-cli-path.sh could not resolve kast" },
            commandAvailability = { false },
        )

        val result = service.install(InstallCopilotExtensionOptions(targetDir = targetDir, force = false))

        assertTrue(result.warnings.any { it.contains(".github/hooks/missing.sh") })
        assertTrue(result.warnings.any { it.contains("resolve-kast-cli-path.sh could not resolve kast") })
        assertTrue(result.warnings.any { it.contains("python3") })
    }

}
