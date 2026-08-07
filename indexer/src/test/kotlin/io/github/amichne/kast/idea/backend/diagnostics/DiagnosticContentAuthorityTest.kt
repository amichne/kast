package io.github.amichne.kast.idea.backend.diagnostics

import io.github.amichne.kast.api.contract.NormalizedPath
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticContentAuthorityTest {
    @Test
    fun `saved VFS content behind disk is not diagnostic hash authority`() {
        val observation = DiagnosticContentObservation.saved(
            filePath = NormalizedPath.parse("/workspace/src/App.kt"),
            vfsContent = "fun before() = Unit".toByteArray(),
            diskContent = "fun after() = Unit".toByteArray(),
        )

        assertTrue(DiagnosticContentAuthority.derive(observation) is DiagnosticContentAuthority.VfsBehindDisk)
    }
}
