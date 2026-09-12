package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.LiveChangePlanIssuance
import io.github.amichne.kast.change.contract.LiveChangePlanLookup
import io.github.amichne.kast.change.contract.LiveChangePlanStoreFailure
import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class SqliteHostedChangeStoresTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `shared stores persist live plans and create only mutation tables`() {
        val location = location()
        val stores =
            assertInstanceOf<Refinement.Refined<SqliteHostedChangeStores>>(SqliteHostedChangeStores.open(location))
                .value
        val plan =
            assertInstanceOf<Refinement.Refined<LiveAddDeclarationChangePlan>>(
                    LiveAddDeclarationPlanCodec.decode(
                        checkNotNull(javaClass.getResource("/live-add-declaration-plan-v1.json")).readText()
                    )
                )
                .value
        val identity = assertInstanceOf<LiveChangePlanIssuance.Issued>(stores.plans.issuePlan(plan)).identity
        val restored =
            assertInstanceOf<Refinement.Refined<SqliteHostedChangeStores>>(SqliteHostedChangeStores.open(location))
                .value
        assertInstanceOf<LiveChangePlanLookup.Found>(restored.plans.loadPlan(identity))
        DriverManager.getConnection("jdbc:sqlite:${location.valueAtSqliteBoundary()}").use { connection ->
            connection.createStatement().use { statement ->
                val tables =
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                        buildSet { while (rows.next()) add(rows.getString(1)) }
                    }
                assertEquals(
                    setOf(
                        "mutation_recovery",
                        "mutation_recovery_planned_write",
                        "mutation_recovery_applied_write",
                        "live_change_plan",
                        "live_change_attempt",
                        "live_change_receipt",
                    ),
                    tables,
                )
                statement.executeUpdate("UPDATE live_change_plan SET codec_version = 2")
            }
        }
        assertEquals(
            LiveChangePlanLookup.Rejected(LiveChangePlanStoreFailure.VERSION_UNSUPPORTED),
            restored.plans.loadPlan(identity),
        )
    }

    @Test
    fun `database path failures retain their finite cause before schema effects`() {
        val location = location()
        val path = Path.of(location.valueAtSqliteBoundary())
        Files.createDirectories(path.parent)
        Files.createSymbolicLink(path, temporary.resolve("absent"))
        val failure =
            assertInstanceOf<Refinement.Rejected<SqliteHostedChangeStoresFailure>>(
                    SqliteHostedChangeStores.open(location)
                )
                .failure
        assertEquals(
            SqliteHostedChangeStoresFailure.Database(SqliteMutationRecoveryJournalOpenFailure.SYMLINK_NOT_ALLOWED),
            failure,
        )
        assertFalse(Files.exists(temporary.resolve("absent")))
    }

    private fun location(): MutationDatabaseLocation {
        val root = temporary.toRealPath()
        val state =
            assertInstanceOf<Refinement.Refined<KastUserStateRoot>>(
                    KastUserStateRoot.parse(root.resolve("state").toString())
                )
                .value
        val workspace =
            assertInstanceOf<Refinement.Refined<CanonicalWorkspaceRoot>>(CanonicalWorkspaceRoot.fromCanonicalPath(root))
                .value
        return assertInstanceOf<Refinement.Refined<HostedWorkspaceStateLocation>>(
                HostedWorkspaceStateLocation.locate(state, workspace)
            )
            .value
            .mutationDatabase
    }
}
