package io.github.amichne.kast.evidence.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedWorkspaceStateLocationTest {
    @Test
    fun `exact roots receive isolated durable database locations`() {
        val userState = KastUserStateRoot.parse("/var/lib/kast").refined()
        val first = HostedWorkspaceStateLocation.locate(
            userState,
            canonical("/workspace/first"),
        ).refined()
        val second = HostedWorkspaceStateLocation.locate(
            userState,
            canonical("/workspace/second"),
        ).refined()

        assertNotEquals(first.topologyDatabase, second.topologyDatabase)
        assertNotEquals(first.mutationDatabase, second.mutationDatabase)
        assertTrue(first.topologyDatabase.valueAtSqliteBoundary().endsWith("/topology.sqlite"))
        assertTrue(first.mutationDatabase.valueAtSqliteBoundary().endsWith("/mutation.sqlite"))
        assertTrue("/state/workspaces/" in first.topologyDatabase.valueAtSqliteBoundary())
        assertTrue("/tmp/" !in first.topologyDatabase.valueAtSqliteBoundary())
    }

    @Test
    fun `non canonical user state roots fail closed`() {
        listOf(
            "relative" to KastUserStateRootFailure.NOT_ABSOLUTE,
            "/var/../tmp" to KastUserStateRootFailure.NOT_NORMALIZED,
            "/var//tmp" to KastUserStateRootFailure.NOT_NORMALIZED,
        ).forEach { (raw, expected) ->
            val result = KastUserStateRoot.parse(raw)
            assertEquals(expected, (result as Refinement.Rejected).failure)
        }
    }

    private fun canonical(raw: String): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(raw)).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
