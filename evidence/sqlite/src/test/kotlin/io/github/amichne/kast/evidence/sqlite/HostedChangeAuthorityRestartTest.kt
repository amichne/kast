package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    @Test
    fun `source lineage resumes its latest durable state and generation`() {
        val state = location("lineage")
        val first = resume(state, "source-basis")

        val advanced = advance(state, "source-basis", "source-v2")
        val reopened = resume(state, "source-basis")

        assertEquals(0L, first.generation.value)
        assertEquals("source-basis", first.sourceState.value)
        assertEquals(1L, advanced.generation.value)
        assertEquals("source-v2", reopened.sourceState.value)
        assertEquals(advanced.generation, reopened.generation)
    }

    @Test
    fun `source lineage advances monotonically when a bounded basis returns`() {
        val state = location("returning-basis")

        val first = resume(state, "basis-a")
        val changed = resume(state, "basis-b")
        val returned = resume(state, "basis-a")

        assertEquals(0L, first.generation.value)
        assertEquals(1L, changed.generation.value)
        assertEquals(2L, returned.generation.value)
        assertNotEquals(first.sourceState, returned.sourceState)
    }

    @Test
    fun `source lineage never advances to a previously issued older generation`() {
        val state = location("older-issued-state")
        val older = issue(state, "source-older")
        val current = resume(state, "source-current")

        val rejected = SqliteHostedWorkspaceGenerationAuthority.advance(
            state.mutationDatabase,
            current.sourceState,
            WorkspaceStateIdentity.parse("source-older").refined(),
        )

        assertEquals(0L, older.generation.value)
        assertEquals(1L, current.generation.value)
        assertEquals(
            HostedWorkspaceGenerationIssuance.Rejected(
                HostedWorkspaceGenerationFailure.STALE_SOURCE_STATE,
            ),
            rejected,
        )
    }

    @Test
    fun `source lineage rejects an advance from stale state`() {
        val state = location("stale-lineage")
        resume(state, "source-basis")
        advance(state, "source-basis", "source-v2")

        val stale = SqliteHostedWorkspaceGenerationAuthority.advance(
            state.mutationDatabase,
            WorkspaceStateIdentity.parse("source-basis").refined(),
            WorkspaceStateIdentity.parse("source-v3").refined(),
        )

        assertEquals(
            HostedWorkspaceGenerationIssuance.Rejected(
                HostedWorkspaceGenerationFailure.STALE_SOURCE_STATE,
            ),
            stale,
        )
    }

    @Test
    fun `source lineage rejects an advance from a corrupt current generation`() {
        val state = location("corrupt-current-lineage")
        resume(state, "source-basis")
        corruptCurrentGeneration(state, 999L)

        val corrupted = SqliteHostedWorkspaceGenerationAuthority.advance(
            state.mutationDatabase,
            WorkspaceStateIdentity.parse("source-basis").refined(),
            WorkspaceStateIdentity.parse("source-v2").refined(),
        )

        assertEquals(
            HostedWorkspaceGenerationIssuance.Rejected(
                HostedWorkspaceGenerationFailure.CORRUPT_STATE,
            ),
            corrupted,
        )
    }

    @Test
    fun `idempotent source advance rejects a corrupt current generation`() {
        val state = location("corrupt-idempotent-lineage")
        resume(state, "source-basis")
        advance(state, "source-basis", "source-v2")
        corruptCurrentGeneration(state, 999L)

        val corrupted = SqliteHostedWorkspaceGenerationAuthority.advance(
            state.mutationDatabase,
            WorkspaceStateIdentity.parse("source-basis").refined(),
            WorkspaceStateIdentity.parse("source-v2").refined(),
        )

        assertEquals(
            HostedWorkspaceGenerationIssuance.Rejected(
                HostedWorkspaceGenerationFailure.CORRUPT_STATE,
            ),
            corrupted,
        )
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

    private fun resume(
        location: HostedWorkspaceStateLocation,
        basis: String,
    ): HostedWorkspaceGenerationResumption.Resumed = when (
        val resumed = SqliteHostedWorkspaceGenerationAuthority.resume(
            location.mutationDatabase,
            WorkspaceStateIdentity.parse(basis).refined(),
        )
    ) {
        is HostedWorkspaceGenerationResumption.Resumed -> resumed
        is HostedWorkspaceGenerationResumption.Rejected -> error(resumed.failure.toString())
    }

    private fun advance(
        location: HostedWorkspaceStateLocation,
        prior: String,
        next: String,
    ): HostedWorkspaceGenerationIssuance.Issued = when (
        val issued = SqliteHostedWorkspaceGenerationAuthority.advance(
            location.mutationDatabase,
            WorkspaceStateIdentity.parse(prior).refined(),
            WorkspaceStateIdentity.parse(next).refined(),
        )
    ) {
        is HostedWorkspaceGenerationIssuance.Issued -> issued
        is HostedWorkspaceGenerationIssuance.Rejected -> error(issued.failure.toString())
    }

    private fun corruptCurrentGeneration(location: HostedWorkspaceStateLocation, generation: Long) {
        DriverManager.getConnection(
            "jdbc:sqlite:${location.mutationDatabase.valueAtSqliteBoundary()}",
        ).use { connection ->
            connection.prepareStatement(
                "UPDATE hosted_workspace_source_lineage SET current_generation = ?",
            ).use { statement ->
                statement.setLong(1, generation)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
