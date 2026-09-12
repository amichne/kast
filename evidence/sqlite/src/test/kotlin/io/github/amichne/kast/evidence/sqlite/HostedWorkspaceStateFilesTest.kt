package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostedWorkspaceStateFilesTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `typed location creates durable parents and opens mutation storage without constructing a topology index`() {
        val state =
            HostedWorkspaceStateLocation.locate(
                    KastUserStateRoot.parse(temporary.toRealPath().toString()).refined(),
                    CanonicalWorkspaceRoot.fromCanonicalPath(temporary.resolve("workspace")).refined(),
                )
                .refined()

        assertInstanceOf(
            SqliteMutationRecoveryJournalOpenResult.Opened::class.java,
            SqliteMutationRecoveryJournal.open(state.mutationDatabase),
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            Path.of(state.topologyDatabase.valueAtSqliteBoundary()).toFile().exists()
        )
        assertTrue(Path.of(state.mutationDatabase.valueAtSqliteBoundary()).toFile().isFile)
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
