package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.result.WorkspaceModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkspaceModuleTest {
    @Test
    fun `module reports the exact returned page size and continuation`() {
        val module = WorkspaceModule(
            name = "main",
            sourceRoots = listOf("/workspace/src"),
            contentRoots = listOf("/workspace"),
            dependencyModuleNames = emptyList(),
            files = listOf("/workspace/src/First.kt", "/workspace/src/Second.kt"),
            fileCount = 3,
            nextPageToken = "00000000-0000-0000-0000-000000000338",
        )

        assertEquals(2, module.returnedFileCount)
        assertEquals("00000000-0000-0000-0000-000000000338", module.nextPageToken)
    }

    @Test
    fun `module rejects contradictory page and root evidence`() {
        assertThrows<IllegalArgumentException> {
            WorkspaceModule(
                name = "main",
                sourceRoots = listOf("/workspace/z", "/workspace/a"),
                contentRoots = listOf("/workspace"),
                dependencyModuleNames = emptyList(),
                fileCount = 0,
            )
        }
        assertThrows<IllegalArgumentException> {
            WorkspaceModule(
                name = "main",
                sourceRoots = listOf("/workspace/src"),
                contentRoots = listOf("/workspace"),
                dependencyModuleNames = emptyList(),
                files = listOf("/workspace/src/First.kt", "/workspace/src/Second.kt"),
                fileCount = 1,
            )
        }
        assertThrows<IllegalArgumentException> {
            WorkspaceModule(
                name = "main",
                sourceRoots = listOf("/workspace/src"),
                contentRoots = listOf("/workspace"),
                dependencyModuleNames = emptyList(),
                fileCount = 0,
                nextPageToken = "",
            )
        }
    }
}
