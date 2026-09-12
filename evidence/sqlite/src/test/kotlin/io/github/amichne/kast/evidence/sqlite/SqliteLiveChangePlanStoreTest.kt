package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.LiveChangeApplicationClaim
import io.github.amichne.kast.change.contract.LiveChangeApplicationHistory
import io.github.amichne.kast.change.contract.LiveChangePlanIssuance
import io.github.amichne.kast.change.contract.LiveChangePlanLookup
import io.github.amichne.kast.change.contract.LiveChangePlanStoreFailure
import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class SqliteLiveChangePlanStoreTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `concurrent attempts admit once and replacement cannot replay`() {
        val location = location()
        val store = open(location)
        val identity = assertInstanceOf<LiveChangePlanIssuance.Issued>(store.issuePlan(fixture())).identity
        assertEquals(LiveChangeApplicationHistory.NeverAttempted, store.applicationHistory(identity))
        val executor = Executors.newFixedThreadPool(4)
        val outcomes =
            try {
                executor.invokeAll(List(8) { Callable { store.claimApplication(identity) } }).map { it.get() }
            } finally {
                executor.shutdownNow()
            }
        assertEquals(1, outcomes.count { it is LiveChangeApplicationClaim.Claimed })
        assertEquals(7, outcomes.count { it == LiveChangeApplicationClaim.AlreadyAttempted })
        assertEquals(LiveChangeApplicationClaim.AlreadyAttempted, open(location).claimApplication(identity))
        assertEquals(LiveChangeApplicationHistory.Attempted, open(location).applicationHistory(identity))
    }

    @Test
    fun `issued live plan survives store replacement and remains idempotent`() {
        val location = location()
        val plan = fixture()
        val identity = assertInstanceOf<LiveChangePlanIssuance.Issued>(open(location).issuePlan(plan)).identity

        val replacement = open(location)
        val restored = assertInstanceOf<LiveChangePlanLookup.Found>(replacement.loadPlan(identity)).plan

        assertEquals("plan:${plan.planId.value}", identity.value)
        assertEquals(LiveAddDeclarationPlanCodec.encode(plan), LiveAddDeclarationPlanCodec.encode(restored))
        assertEquals(LiveChangePlanIssuance.Issued(identity), replacement.issuePlan(restored))
        assertEquals(
            1,
            database(location) { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM live_change_plan").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            },
        )
    }

    @Test
    fun `concurrent issuance shares one immutable durable identity`() {
        val location = location()
        val plan = fixture()
        val store = open(location)
        val executor = Executors.newFixedThreadPool(4)
        val identities =
            try {
                executor.invokeAll(List(8) { Callable { store.issuePlan(plan) } }).map {
                    assertInstanceOf<LiveChangePlanIssuance.Issued>(it.get()).identity
                }
            } finally {
                executor.shutdownNow()
            }
        assertEquals(1, identities.distinct().size)
        assertInstanceOf<LiveChangePlanLookup.Found>(open(location).loadPlan(identities.singleIdentity()))
    }

    @Test
    fun `missing and corrupt records remain distinct and issuance never repairs a collision`() {
        val location = location()
        val store = open(location)
        assertEquals(
            LiveChangePlanLookup.Missing,
            store.loadPlan(checkNotNull(ChangePlanIdentity.parse("plan:${"0".repeat(64)}"))),
        )
        val plan = fixture()
        val identity = assertInstanceOf<LiveChangePlanIssuance.Issued>(store.issuePlan(plan)).identity
        database(location) { connection ->
            connection.prepareStatement("UPDATE live_change_plan SET document_sha256 = ? WHERE identity = ?").use {
                statement ->
                statement.setString(1, "0".repeat(64))
                statement.setString(2, identity.value)
                statement.executeUpdate()
            }
        }
        assertEquals(
            LiveChangePlanStoreFailure.CORRUPT_RECORD,
            assertInstanceOf<LiveChangePlanLookup.Rejected>(open(location).loadPlan(identity)).failure,
        )
        assertEquals(
            LiveChangePlanStoreFailure.IDENTITY_COLLISION,
            assertInstanceOf<LiveChangePlanIssuance.Rejected>(store.issuePlan(plan)).failure,
        )
        assertEquals(
            LiveChangePlanStoreFailure.CORRUPT_RECORD,
            assertInstanceOf<LiveChangePlanLookup.Rejected>(open(location).loadPlan(identity)).failure,
        )
    }

    @Test
    fun `incompatible durable codec version is explicit`() {
        val location = location()
        val identity = assertInstanceOf<LiveChangePlanIssuance.Issued>(open(location).issuePlan(fixture())).identity
        database(location) { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE live_change_plan SET codec_version = 2")
            }
        }
        assertEquals(
            LiveChangePlanStoreFailure.VERSION_UNSUPPORTED,
            assertInstanceOf<LiveChangePlanLookup.Rejected>(open(location).loadPlan(identity)).failure,
        )
    }

    @Test
    fun `large stored versions cannot truncate into the supported codec version`() {
        val location = location()
        val identity = assertInstanceOf<LiveChangePlanIssuance.Issued>(open(location).issuePlan(fixture())).identity
        database(location) { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE live_change_plan SET codec_version = 4294967297")
            }
        }
        assertEquals(
            LiveChangePlanStoreFailure.VERSION_UNSUPPORTED,
            assertInstanceOf<LiveChangePlanLookup.Rejected>(open(location).loadPlan(identity)).failure,
        )
    }

    @Test
    fun `a recomputed storage digest does not authorize altered plan content`() {
        val location = location()
        val plan = fixture()
        val identity = assertInstanceOf<LiveChangePlanIssuance.Issued>(open(location).issuePlan(plan)).identity
        val changed = LiveAddDeclarationPlanCodec.encode(plan).replace("fun added() = 1", "fun added() = 2")
        val digest =
            java.security.MessageDigest.getInstance("SHA-256").digest(changed.toByteArray()).joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        database(location) { connection ->
            connection.prepareStatement("UPDATE live_change_plan SET document = ?, document_sha256 = ?").use { statement
                ->
                statement.setString(1, changed)
                statement.setString(2, digest)
                statement.executeUpdate()
            }
        }
        assertEquals(
            LiveChangePlanStoreFailure.CORRUPT_RECORD,
            assertInstanceOf<LiveChangePlanLookup.Rejected>(open(location).loadPlan(identity)).failure,
        )
    }

    @Test
    fun `opening live storage preserves legacy plan rows and durable recovery records`() {
        val location = location()
        val legacy =
            assertInstanceOf<SqliteMutationRecoveryJournalOpenResult.Opened>(
                SqliteMutationRecoveryJournal.open(location)
            )
        val recovery = MutationRecoveryEvidenceFixture()
        assertInstanceOf<MutationRecoveryPersistResult.Durable<*>>(legacy.journal.prepare(recovery.prepared))
        database(location) { connection ->
            connection.createStatement().use {
                it.execute(
                    """CREATE TABLE hosted_change_plan(
                        identity TEXT PRIMARY KEY, plan_id TEXT, document TEXT, document_sha256 TEXT
                    )"""
                )
            }
            connection
                .prepareStatement(
                    "INSERT INTO hosted_change_plan(identity, plan_id, document, document_sha256) VALUES (?, ?, ?, ?)"
                )
                .use { statement ->
                    statement.setString(1, "plan:${"a".repeat(64)}")
                    statement.setString(2, "b".repeat(64))
                    statement.setString(3, "legacy-record-preserved")
                    statement.setString(4, "c".repeat(64))
                    statement.executeUpdate()
                }
        }

        assertInstanceOf<LiveChangePlanIssuance.Issued>(open(location).issuePlan(fixture()))
        val reopened =
            assertInstanceOf<SqliteMutationRecoveryJournalOpenResult.Opened>(
                SqliteMutationRecoveryJournal.open(location)
            )

        assertEquals(
            recovery.prepared.digest,
            assertInstanceOf<MutationRecoveryLoadResult.Found>(reopened.journal.load(recovery.binding)).record.digest,
        )
        assertEquals(
            "legacy-record-preserved",
            database(location) { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT document FROM hosted_change_plan").use { rows ->
                        rows.next()
                        rows.getString(1)
                    }
                }
            },
        )
    }

    private fun location(): MutationDatabaseLocation =
        HostedWorkspaceStateLocation.locate(
                KastUserStateRoot.parse(temporary.toRealPath().toString()).refined(),
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            )
            .refined()
            .mutationDatabase

    private fun open(location: MutationDatabaseLocation) =
        assertInstanceOf<SqliteLiveChangePlanStoreOpenResult.Opened>(SqliteLiveChangePlanStore.open(location)).store

    private fun fixture(): LiveAddDeclarationChangePlan =
        LiveAddDeclarationPlanCodec.decode(
                checkNotNull(javaClass.getResource("/live-add-declaration-plan-v1.json")).readText()
            )
            .refined()

    private fun <T> database(location: MutationDatabaseLocation, action: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${location.valueAtSqliteBoundary()}").use(action)

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value

    private fun List<ChangePlanIdentity>.singleIdentity(): ChangePlanIdentity = distinct().single()
}
