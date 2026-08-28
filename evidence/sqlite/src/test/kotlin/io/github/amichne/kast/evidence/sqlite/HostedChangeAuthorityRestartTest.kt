package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostedChangeAuthorityRestartTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `exact source state keeps its generation across reopen and changed source advances it`() {
        val state = location("workspace")
        val first = issue(state, "source-v1")
        val reopened = issue(state, "source-v1")
        val changed = issue(state, "source-v2")

        assertEquals(0L, first.generation.value)
        assertEquals(first, reopened)
        assertEquals(1L, changed.generation.value)
    }

    @Test
    fun `semantic generation authority is isolated by canonical root`() {
        assertEquals(0L, issue(location("one"), "source-v1").generation.value)
        assertEquals(0L, issue(location("two"), "source-v1").generation.value)
    }

    private fun location(name: String): HostedWorkspaceStateLocation {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(
            temporary.resolve(name).toAbsolutePath().normalize(),
        ).refined()
        return HostedWorkspaceStateLocation.locate(
            KastUserStateRoot.parse(temporary.toAbsolutePath().normalize().toString()).refined(),
            root,
        ).refined()
    }

    private fun issue(
        location: HostedWorkspaceStateLocation,
        state: String,
    ): HostedWorkspaceGenerationIssuance.Issued = when (
        val issued = SqliteHostedWorkspaceGenerationAuthority.issue(
            location.mutationDatabase,
            WorkspaceStateIdentity.parse(state).refined(),
        )
    ) {
        is HostedWorkspaceGenerationIssuance.Issued -> issued
        is HostedWorkspaceGenerationIssuance.Rejected -> error(issued.failure.toString())
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
