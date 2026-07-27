package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkspaceFilesResultTest {
    @Test
    fun `workspace result echoes a nonblank snapshot handle`() {
        val result = WorkspaceFilesResult(
            modules = emptyList(),
            snapshotToken = "65ce31a2-b82c-4f8a-a425-03430ef548f9",
        )

        assertEquals("65ce31a2-b82c-4f8a-a425-03430ef548f9", result.snapshotToken)
    }

    @Test
    fun `workspace result rejects a blank snapshot handle`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFilesResult(
                modules = emptyList(),
                snapshotToken = " ",
            )
        }
    }
}
