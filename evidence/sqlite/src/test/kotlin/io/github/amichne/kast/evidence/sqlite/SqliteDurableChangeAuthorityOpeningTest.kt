package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteDurableChangeAuthorityOpeningTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `one opening retains usable change and recovery capabilities`() {
        val location = HostedWorkspaceStateLocation.locate(
            KastUserStateRoot.parse(temporary.toString()).refined(),
            CanonicalWorkspaceRoot.fromCanonicalPath(temporary.resolve("workspace")).refined(),
        ).refined()
        val opened = when (
            val result = SqliteDurableChangeAuthority.openHosted(location.mutationDatabase)
        ) {
            is SqliteHostedMutationAuthorityOpenResult.Opened -> result
            is SqliteHostedMutationAuthorityOpenResult.Rejected -> error(result.failure.toString())
        }
        val fixture = MutationRecoveryEvidenceFixture()

        assertEquals(HostedDurableMutationAudit.Clean, opened.authority.auditMutationState())
        assertEquals(
            MutationRecoveryLoadResult.Absent(fixture.binding),
            opened.recoveryJournal.load(fixture.binding),
        )
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
