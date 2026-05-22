package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.client.fields.CliBinaryPath
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.intellij.actions.KastBinaryResolution
import io.github.amichne.kast.intellij.actions.KastInstallCommandResult
import io.github.amichne.kast.intellij.actions.resolveConfiguredKastBinary
import io.github.amichne.kast.intellij.actions.runKastInstallCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class KastInstallActionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun resolveConfiguredKastBinaryUsesExecutableCliBinaryPath() {
        val kastBinary = Files.createFile(tempDir.resolve("kast"))
        assertTrue(kastBinary.toFile().setExecutable(true))

        val result = resolveConfiguredKastBinary(tempDir) { configWithBinaryPath(kastBinary) }

        assertEquals(KastBinaryResolution.Found(kastBinary.toAbsolutePath().normalize()), result)
    }

    @Test
    fun resolveConfiguredKastBinaryRejectsMissingCliBinaryPath() {
        val missingBinary = tempDir.resolve("missing-kast")

        val result = resolveConfiguredKastBinary(tempDir) { configWithBinaryPath(missingBinary) }

        assertTrue(result is KastBinaryResolution.NotExecutable)
        val message = (result as KastBinaryResolution.NotExecutable).message
        assertTrue(message.contains(missingBinary.toAbsolutePath().normalize().toString()))
        assertTrue(message.contains("[cli] binaryPath"))
        assertTrue(message.contains("config.toml"))
    }

    @Test
    fun installsCopilotExtensionFromConfiguredCliBinaryPath() {
        val workspaceRoot = tempDir.resolve("workspace")
        Files.createDirectories(workspaceRoot)
        val kastBinary = fakeRustKastBinary()

        val resolution = resolveConfiguredKastBinary(workspaceRoot) { configWithBinaryPath(kastBinary) }
        assertEquals(KastBinaryResolution.Found(kastBinary), resolution)

        val result = runKastInstallCommand(
            kastBinary = (resolution as KastBinaryResolution.Found).path,
            workspaceRoot = workspaceRoot,
            args = listOf(
                "install",
                "copilot-extension",
                "--target-dir=" + workspaceRoot.resolve(".github"),
                "--yes=true",
            ),
            timeout = 90,
            timeoutUnit = TimeUnit.SECONDS,
        )

        assertEquals(KastInstallCommandResult.Success, result)
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(".github/.kast-copilot-version")))
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(".github/extensions/kast/extension.mjs")))
    }

    private fun configWithBinaryPath(path: Path): KastConfig {
        val defaults = KastConfig.defaults()
        return defaults.copy(
            cli = defaults.cli.copy(
                binaryPath = CliBinaryPath(path.toString()),
            ),
        )
    }

    private fun fakeRustKastBinary(): Path {
        val kastBinary = tempDir.resolve("kast")
        Files.writeString(
            kastBinary,
            """
            #!/usr/bin/env bash
            set -euo pipefail
            if [[ "${'$'}1" == "install" && "${'$'}2" == "copilot-extension" ]]; then
              target_dir=""
              for arg in "${'$'}@"; do
                case "${'$'}arg" in
                  --target-dir=*) target_dir="${'$'}{arg#--target-dir=}" ;;
                esac
              done
              [[ -n "${'$'}target_dir" ]] || exit 2
              mkdir -p "${'$'}target_dir/extensions/kast"
              printf '%s\n' test > "${'$'}target_dir/.kast-copilot-version"
              printf '%s\n' '// fake extension' > "${'$'}target_dir/extensions/kast/extension.mjs"
              exit 0
            fi
            exit 64
            """.trimIndent(),
        )
        assertTrue(kastBinary.toFile().setExecutable(true))
        return kastBinary.toAbsolutePath().normalize()
    }
}
