package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.LiveChangePlan
import io.github.amichne.kast.change.contract.LiveChangePlanIssuance
import io.github.amichne.kast.change.verify.LiveAddDeclarationReceiptCodec
import io.github.amichne.kast.evidence.contract.AppliedRecoveryWriteSet
import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryPreparation
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.MutationRecoveryStage
import io.github.amichne.kast.evidence.contract.PlannedRecoveryWrite
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.evidence.contract.RecoveryRequirement
import io.github.amichne.kast.evidence.contract.RecoverySourcePath
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteMutationStateInspectionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `empty owned schema is settled and inspection preserves bytes and creates no files`() {
        val (database, _) = fixture()
        val before = Files.readAllBytes(database)
        val names =
            Files.list(database.parent).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
        assertInstanceOf(SqliteMutationStateInspectionResult.Settled::class.java, inspect(database))
        assertArrayEquals(before, Files.readAllBytes(database))
        assertEquals(
            names,
            Files.list(database.parent).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() },
        )
    }

    @Test
    fun `claimed application without recovery evidence is unsettled and retained`() {
        val (database, stores) = fixture()
        val plan =
            LiveAddDeclarationPlanCodec.decode(
                    checkNotNull(javaClass.getResource("/live-add-declaration-plan-v2.json")).readText()
                )
                .refined()
        val identity = (stores.plans.issuePlan(plan) as LiveChangePlanIssuance.Issued).identity
        stores.plans.claimApplication(identity)
        val before = Files.readAllBytes(database)
        assertEquals(
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.UNSETTLED_MUTATION),
            inspect(database),
        )
        assertArrayEquals(before, Files.readAllBytes(database))
    }

    @Test
    fun `plans with no attempt are settled but workspace reassignment is rejected`() {
        val (database, stores) = fixture()
        val plan =
            LiveAddDeclarationPlanCodec.decode(
                    checkNotNull(javaClass.getResource("/live-add-declaration-plan-v2.json")).readText()
                )
                .refined()
        stores.plans.issuePlan(plan)
        assertInstanceOf(SqliteMutationStateInspectionResult.Settled::class.java, inspect(database))
        val otherWorkspace = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/other")).refined()
        val state = KastUserStateRoot.parse(temporary.toRealPath().resolve(".kast").toString()).refined()
        val foreign =
            Path.of(
                HostedWorkspaceStateLocation.locate(state, otherWorkspace)
                    .refined()
                    .mutationDatabase
                    .valueAtSqliteBoundary()
            )
        Files.createDirectories(foreign.parent)
        Files.copy(database, foreign)
        assertEquals(
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.WORKSPACE_MISMATCH),
            inspect(foreign),
        )
    }

    @Test
    fun `only reconstructed rollback is settled without a verified receipt`() {
        for (stage in MutationRecoveryStage.entries) {
            val (database, stores) = fixture(Files.createDirectory(temporary.resolve(stage.name)))
            val plan =
                LiveAddDeclarationPlanCodec.decode(
                        checkNotNull(javaClass.getResource("/live-add-declaration-plan-v2.json")).readText()
                    )
                    .refined()
            val identity = (stores.plans.issuePlan(plan) as LiveChangePlanIssuance.Issued).identity
            stores.plans.claimApplication(identity)
            persistRecoveryStage(stores, plan, stage)
            val before = Files.readAllBytes(database)
            if (stage == MutationRecoveryStage.ROLLED_BACK)
                assertInstanceOf(SqliteMutationStateInspectionResult.Settled::class.java, inspect(database))
            else
                assertEquals(
                    SqliteMutationStateInspectionResult.Rejected(
                        SqliteMutationStateInspectionFailure.UNSETTLED_MUTATION
                    ),
                    inspect(database),
                    stage.name,
                )
            assertArrayEquals(before, Files.readAllBytes(database))
        }
    }

    @Test
    fun `applied journal requires a decoded receipt with its exact recovery chain`() {
        for (matching in listOf(true, false)) {
            val (database, stores) = fixture(Files.createDirectory(temporary.resolve(matching.toString())))
            val resource =
                if (matching) "/live-add-declaration-receipt-settled-journal.json"
                else "/live-add-declaration-receipt-v1-plan-v2.json"
            val receipt =
                LiveAddDeclarationReceiptCodec.decode(checkNotNull(javaClass.getResource(resource)).readText())
                    .refined()
            val plan = receipt.plan
            val identity = (stores.plans.issuePlan(plan) as LiveChangePlanIssuance.Issued).identity
            stores.plans.claimApplication(identity)
            val source = RecoverySourcePath.parse(plan.writes.entries.single().source.path.value).refined()
            val preparation =
                MutationRecoveryPreparation.admit(
                        MutationPlanBinding.parse(plan.planId.value).refined(),
                        listOf(
                            PlannedRecoveryWrite(source, RecoveryPreimage.fromBoundary("prior source".toByteArray()))
                        ),
                    )
                    .refined()
            val prepared = MutationRecoveryRecord.prepare(preparation)
            stores.journal.prepare(prepared)
            val applied =
                MutationRecoveryRecord.recordApplied(
                        prepared,
                        AppliedRecoveryWriteSet.admit(preparation.plannedWrites, listOf(source)).refined(),
                    )
                    .refined()
            stores.journal.recordApplied(prepared, applied)
            stores.receipts.persistHistorical(receipt)
            val before = Files.readAllBytes(database)
            if (matching) assertInstanceOf(SqliteMutationStateInspectionResult.Settled::class.java, inspect(database))
            else
                assertEquals(
                    SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.RECORD_REJECTED),
                    inspect(database),
                )
            assertArrayEquals(before, Files.readAllBytes(database))
        }
    }

    @Test
    fun `capacity rejection precedes decoding and retains invalid excess rows`() {
        val (database, _) = fixture()
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("INSERT INTO live_change_attempt(identity) VALUES (?)").use { statement ->
                for (index in 0..4096) {
                    statement.setString(1, "plan:" + index.toString(16).padStart(64, '0'))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.commit()
        }
        val before = Files.readAllBytes(database)
        assertEquals(
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.CAPACITY_EXCEEDED),
            inspect(database),
        )
        assertArrayEquals(before, Files.readAllBytes(database))
    }

    @Test
    fun `same columns without owning constraints are rejected and retained`() {
        val (database, _) = fixture()
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use {
                it.execute("DROP TABLE live_change_plan")
                it.execute(
                    """CREATE TABLE live_change_plan (
                    identity TEXT PRIMARY KEY NOT NULL,
                    plan_id TEXT NOT NULL,
                    codec_version INTEGER NOT NULL,
                    document TEXT NOT NULL,
                    document_sha256 TEXT NOT NULL
                ) WITHOUT ROWID"""
                )
            }
        }
        val before = Files.readAllBytes(database)
        assertEquals(
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.SCHEMA_REJECTED),
            inspect(database),
        )
        assertArrayEquals(before, Files.readAllBytes(database))
    }

    @Test
    fun `foreign schema and nonempty WAL fail closed without writes`() {
        val (database, _) = fixture()
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE foreign_state(value TEXT)") }
        }
        val before = Files.readAllBytes(database)
        assertEquals(
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.SCHEMA_REJECTED),
            inspect(database),
        )
        assertArrayEquals(before, Files.readAllBytes(database))
        val wal = database.resolveSibling("${database.fileName}-wal")
        Files.write(wal, byteArrayOf(1, 2, 3))
        assertEquals(
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.CHECKPOINT_REQUIRED),
            inspect(database),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(wal))
    }

    private fun persistRecoveryStage(
        stores: SqliteHostedChangeStores,
        plan: LiveChangePlan,
        stage: MutationRecoveryStage,
    ) {
        val source = RecoverySourcePath.parse(plan.writes.entries.single().source.path.value).refined()
        val preparation =
            MutationRecoveryPreparation.admit(
                    MutationPlanBinding.parse(plan.planId.value).refined(),
                    listOf(PlannedRecoveryWrite(source, RecoveryPreimage.fromBoundary("prior source".toByteArray()))),
                )
                .refined()
        val prepared = MutationRecoveryRecord.prepare(preparation)
        stores.journal.prepare(prepared)
        val applied =
            MutationRecoveryRecord.recordApplied(
                    prepared,
                    AppliedRecoveryWriteSet.admit(preparation.plannedWrites, listOf(source)).refined(),
                )
                .refined()
        if (stage != MutationRecoveryStage.PRE_WRITE_DURABLE) stores.journal.recordApplied(prepared, applied)
        when (stage) {
            MutationRecoveryStage.PRE_WRITE_DURABLE,
            MutationRecoveryStage.APPLIED_WRITES_DURABLE -> Unit
            MutationRecoveryStage.ROLLED_BACK ->
                stores.journal.recordTerminal(applied, MutationRecoveryRecord.rolledBack(applied))
            MutationRecoveryStage.RECOVERY_REQUIRED ->
                stores.journal.recordTerminal(
                    applied,
                    MutationRecoveryRecord.recoveryRequired(applied, RecoveryRequirement.ROLLBACK_REJECTED),
                )
        }
    }

    private fun inspect(database: Path) =
        SqliteMutationStateInspection.inspect(database, database.parent.fileName.toString())

    private fun fixture(directory: Path = temporary): Pair<Path, SqliteHostedChangeStores> {
        val state = KastUserStateRoot.parse(directory.toRealPath().resolve(".kast").toString()).refined()
        val workspace = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val location = HostedWorkspaceStateLocation.locate(state, workspace).refined().mutationDatabase
        val stores = SqliteHostedChangeStores.open(location).refined()
        return Path.of(location.valueAtSqliteBoundary()) to stores
    }
}
