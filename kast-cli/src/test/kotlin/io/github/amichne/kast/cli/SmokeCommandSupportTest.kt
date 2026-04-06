package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.setPosixFilePermissions

class SmokeCommandSupportTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `plan resolves nearest packaged smoke script and current launcher`() {
        val distRoot = tempDir.resolve("dist").resolve("kast").createDirectories()
        val launcher = distRoot.resolve("bin").createDirectories().resolve("kast").createFile()
        launcher.setPosixFilePermissions(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val smokeScript = distRoot.resolve("smoke.sh").createFile()
        val workspaceRoot = tempDir.resolve("workspace").createDirectories()
        val support = SmokeCommandSupport(
            environmentLookup = { key ->
                when (key) {
                    "KAST_LAUNCHER_PATH" -> launcher.toString()
                    else -> null
                }
            },
            propertyLookup = { key ->
                when (key) {
                    "user.dir" -> tempDir.toString()
                    else -> null
                }
            },
            currentCommandPathProvider = { null },
        )

        val process = support.plan(
            SmokeOptions(
                workspaceRoot = workspaceRoot,
                fileFilter = "CliCommandCatalog.kt",
                sourceSetFilter = ":kast-cli:test",
                symbolFilter = "KastCli",
                format = SmokeOutputFormat.JSON,
            ),
        )

        assertEquals(
            listOf(
                "bash",
                smokeScript.toString(),
                "--workspace-root=${workspaceRoot}",
                "--file=CliCommandCatalog.kt",
                "--source-set=:kast-cli:test",
                "--symbol=KastCli",
                "--format=json",
                "--kast=${launcher}",
            ),
            process.command,
        )
        assertEquals(workspaceRoot, process.workingDirectory)
    }

    @Test
    fun `plan fails when no smoke script can be located`() {
        val support = SmokeCommandSupport(
            environmentLookup = { null },
            propertyLookup = { key -> if (key == "user.dir") tempDir.toString() else null },
            currentCommandPathProvider = { null },
        )

        val failure = assertThrows<CliFailure> {
            support.plan(
                SmokeOptions(
                    workspaceRoot = tempDir,
                    fileFilter = null,
                    sourceSetFilter = null,
                    symbolFilter = null,
                    format = SmokeOutputFormat.JSON,
                ),
            )
        }

        assertEquals("SMOKE_SETUP_ERROR", failure.code)
        assertTrue(failure.message.contains("smoke.sh"))
    }
}
