package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CanonicalSemanticProjectRootTest {
    @Test
    fun `absolute normalized project root is retained independently from workspace identity`() {
        val path = Path.of("/runtime/project-store")
        val workspace = workspaceRoot()

        val refined = when (
            val result = CanonicalSemanticProjectRoot.fromCanonicalPath(workspace, path)
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error(result.failure)
        }

        assertEquals(path.toString(), refined.value)
        assertEquals(workspace, refined.workspaceRoot)
    }

    @Test
    fun `relative and non-normalized project roots fail closed`() {
        assertEquals(
            Refinement.Rejected(CanonicalSemanticProjectRootFailure.NOT_ABSOLUTE),
            CanonicalSemanticProjectRoot.fromCanonicalPath(
                workspaceRoot(),
                Path.of("runtime/project-store"),
            ),
        )
        assertEquals(
            Refinement.Rejected(CanonicalSemanticProjectRootFailure.NOT_NORMALIZED),
            CanonicalSemanticProjectRoot.fromCanonicalPath(
                workspaceRoot(),
                Path.of("/runtime/other/../project-store"),
            ),
        )
    }

    @Test
    fun `workspace and semantic project overlap fails closed`() {
        listOf(
            Path.of("/workspace/private-project"),
            Path.of("/"),
        ).forEach { overlappingPath ->
            assertEquals(
                Refinement.Rejected(CanonicalSemanticProjectRootFailure.OVERLAPS_WORKSPACE),
                CanonicalSemanticProjectRoot.fromCanonicalPath(
                    workspaceRoot(),
                    overlappingPath,
                ),
            )
        }
    }

    private fun workspaceRoot(): CanonicalWorkspaceRoot = when (
        val result = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace"))
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error(result.failure)
    }
}
