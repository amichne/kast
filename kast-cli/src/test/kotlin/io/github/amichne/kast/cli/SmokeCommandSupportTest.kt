package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SmokeCommandSupportTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `plan throws not-yet-implemented`() {
        val support = SmokeCommandSupport()

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

        assertEquals("NOT_YET_IMPLEMENTED", failure.code)
    }
}
