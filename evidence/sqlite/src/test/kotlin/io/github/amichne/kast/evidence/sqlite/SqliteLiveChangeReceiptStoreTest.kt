package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.verify.HistoricalLiveAddDeclarationReceipt
import io.github.amichne.kast.change.verify.LiveAddDeclarationReceiptCodec
import io.github.amichne.kast.change.verify.LiveChangeReceiptIssuance
import io.github.amichne.kast.change.verify.LiveChangeReceiptLookup
import io.github.amichne.kast.change.verify.LiveChangeReceiptStoreFailure
import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class SqliteLiveChangeReceiptStoreTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `historical receipt survives replacement and returns its original before after approval and scope`() {
        val location = location()
        val receipt = fixture()
        val issued = assertInstanceOf<LiveChangeReceiptIssuance.Issued>(open(location).persistHistorical(receipt))
        val replacement = open(location)
        val restored = assertInstanceOf<LiveChangeReceiptLookup.Found>(replacement.loadReceipt(planIdentity(receipt)))
        assertEquals(issued.identity, restored.identity)
        assertEquals(
            LiveAddDeclarationReceiptCodec.encode(receipt),
            LiveAddDeclarationReceiptCodec.encode(restored.receipt),
        )
        assertEquals(receipt.before.reference, restored.receipt.before.reference)
        assertEquals(receipt.after.reference, restored.receipt.after.reference)
        assertEquals(receipt.approval.call, restored.receipt.approval.call)
        assertEquals(receipt.recovery, restored.receipt.recovery)
        assertEquals(
            issued.identity,
            assertInstanceOf<LiveChangeReceiptIssuance.Issued>(replacement.persistHistorical(receipt)).identity,
        )
        assertEquals(
            1,
            database(location) { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM live_change_receipt").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            },
        )
    }

    @Test
    fun `concurrent persistence creates one immutable receipt`() {
        val receipt = fixture()
        val store = open(location())
        val executor = Executors.newFixedThreadPool(4)
        val results =
            try {
                executor.invokeAll(List(8) { Callable { store.persistHistorical(receipt) } }).map { it.get() }
            } finally {
                executor.shutdownNow()
            }
        assertEquals(
            listOf(receipt.identity),
            results.map { assertInstanceOf<LiveChangeReceiptIssuance.Issued>(it).identity }.distinct(),
        )
    }

    @Test
    fun `missing corrupt and colliding records remain distinct and immutable`() {
        val location = location()
        val store = open(location)
        val receipt = fixture()
        val plan = planIdentity(receipt)
        assertEquals(LiveChangeReceiptLookup.Missing, store.loadReceipt(plan))
        assertInstanceOf<LiveChangeReceiptIssuance.Issued>(store.persistHistorical(receipt))
        database(location) { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE live_change_receipt SET document_sha256 = '${"0".repeat(64)}'")
            }
        }
        assertEquals(LiveChangeReceiptStoreFailure.CORRUPT_RECORD, rejected(open(location).loadReceipt(plan)))
        assertEquals(
            LiveChangeReceiptStoreFailure.IDENTITY_COLLISION,
            assertInstanceOf<LiveChangeReceiptIssuance.Rejected>(store.persistHistorical(receipt)).failure,
        )
        assertEquals(LiveChangeReceiptStoreFailure.CORRUPT_RECORD, rejected(open(location).loadReceipt(plan)))
    }

    @Test
    fun `unknown row versions including integers wider than Int fail closed`() {
        val location = location()
        val receipt = fixture()
        assertInstanceOf<LiveChangeReceiptIssuance.Issued>(open(location).persistHistorical(receipt))
        for (version in listOf(2L, 4294967297L)) {
            database(location) { connection ->
                connection.prepareStatement("UPDATE live_change_receipt SET codec_version = ?").use { statement ->
                    statement.setLong(1, version)
                    statement.executeUpdate()
                }
            }
            assertEquals(
                LiveChangeReceiptStoreFailure.VERSION_UNSUPPORTED,
                rejected(open(location).loadReceipt(planIdentity(receipt))),
            )
        }
    }

    @Test
    fun `recomputed storage checksum cannot hide tampered original invocation or receipt identity`() {
        val location = location()
        val receipt = fixture()
        val store = open(location)
        assertInstanceOf<LiveChangeReceiptIssuance.Issued>(store.persistHistorical(receipt))
        val changed = LiveAddDeclarationReceiptCodec.encode(receipt).replace("fixture-call", "different-call")
        val digest =
            MessageDigest.getInstance("SHA-256").digest(changed.toByteArray()).joinToString("") { byte ->
                "%02x".format(java.util.Locale.ROOT, byte)
            }
        database(location) { connection ->
            connection.prepareStatement("UPDATE live_change_receipt SET document = ?, document_sha256 = ?").use {
                statement ->
                statement.setString(1, changed)
                statement.setString(2, digest)
                statement.executeUpdate()
            }
        }
        assertEquals(
            LiveChangeReceiptStoreFailure.CORRUPT_RECORD,
            rejected(open(location).loadReceipt(planIdentity(receipt))),
        )
    }

    @Test
    fun `receipt table initialization preserves legacy and durable recovery evidence`() {
        val location = location()
        val legacy =
            assertInstanceOf<SqliteHostedMutationAuthorityOpenResult.Opened>(
                SqliteDurableChangeAuthority.openHosted(location)
            )
        val recovery = MutationRecoveryEvidenceFixture()
        assertInstanceOf<MutationRecoveryPersistResult.Durable<*>>(legacy.recoveryJournal.prepare(recovery.prepared))
        database(location) { connection ->
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
        assertInstanceOf<LiveChangeReceiptIssuance.Issued>(open(location).persistHistorical(fixture()))
        val reopened =
            assertInstanceOf<SqliteHostedMutationAuthorityOpenResult.Opened>(
                SqliteDurableChangeAuthority.openHosted(location)
            )
        assertEquals(
            recovery.prepared.digest,
            assertInstanceOf<MutationRecoveryLoadResult.Found>(reopened.recoveryJournal.load(recovery.binding))
                .record
                .digest,
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

    private fun location() =
        HostedWorkspaceStateLocation.locate(
                KastUserStateRoot.parse(temporary.toString()).refined(),
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            )
            .refined()
            .mutationDatabase

    private fun open(location: MutationDatabaseLocation) =
        assertInstanceOf<SqliteLiveChangeReceiptStoreOpenResult.Opened>(SqliteLiveChangeReceiptStore.open(location))
            .store

    private fun fixture() =
        LiveAddDeclarationReceiptCodec.decode(
                checkNotNull(javaClass.getResource("/live-add-declaration-receipt-v1.json")).readText()
            )
            .refined()

    private fun planIdentity(receipt: HistoricalLiveAddDeclarationReceipt) =
        checkNotNull(ChangePlanIdentity.parse("plan:${receipt.plan.planId.value}"))

    private fun rejected(result: LiveChangeReceiptLookup) =
        assertInstanceOf<LiveChangeReceiptLookup.Rejected>(result).failure

    private fun <T> database(location: MutationDatabaseLocation, action: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${location.valueAtSqliteBoundary()}").use(action)

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
