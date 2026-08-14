package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CurrentWorkspaceReadLeaseContractTest {
    @Test
    fun `current workspace lease retains one positive compiler epoch`() {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val epoch = CurrentWorkspaceEpoch.parse(7L).refined()

        assertEquals(
            CurrentWorkspaceReadLease(workspaceRoot = root, epoch = epoch),
            CurrentWorkspaceReadLease(root, epoch),
        )
    }

    @Test
    fun `non-positive compiler epochs cannot become current workspace evidence`() {
        assertTrue(CurrentWorkspaceEpoch.parse(0L) is Refinement.Rejected)
        assertTrue(CurrentWorkspaceEpoch.parse(-1L) is Refinement.Rejected)
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refinement but received $failure")
    }
