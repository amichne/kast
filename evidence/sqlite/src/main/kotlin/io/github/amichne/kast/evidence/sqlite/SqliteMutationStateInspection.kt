package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.contract.LiveChangePlan
import io.github.amichne.kast.change.contract.LiveChangePlanLookup
import io.github.amichne.kast.change.verify.HistoricalLiveAddDeclarationReceipt
import io.github.amichne.kast.change.verify.HistoricalLiveChangeReceipt
import io.github.amichne.kast.change.verify.HistoricalLiveReplaceBodyReceipt
import io.github.amichne.kast.change.verify.LiveChangeReceiptLookup
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

@kotlinx.serialization.Serializable
enum class SqliteMutationStateInspectionFailure {
    PATH_REJECTED,
    SCHEMA_REJECTED,
    CAPACITY_EXCEEDED,
    RECORD_REJECTED,
    WORKSPACE_MISMATCH,
    UNSETTLED_MUTATION,
    CHECKPOINT_REQUIRED,
    STORAGE_UNAVAILABLE,
}

sealed interface SqliteMutationStateInspectionResult {
    /** Snapshot evidence only; cleanup must retain retirement and physical file identity separately. */
    class Settled internal constructor(val database: Path) : SqliteMutationStateInspectionResult

    data class Rejected(val failure: SqliteMutationStateInspectionFailure) : SqliteMutationStateInspectionResult
}

/** Read-only inspection never initializes schema, checkpoints WAL, recovers mutations, or creates state. */
object SqliteMutationStateInspection {
    fun inspect(database: Path, workspaceDigest: String): SqliteMutationStateInspectionResult =
        try {
            inspectDatabasePath(database, workspaceDigest)
            ensureSqliteDriver()
            // immutable avoids even shared-memory writes. Nonempty WAL was excluded because immutable ignores WAL.
            DriverManager.getConnection("jdbc:sqlite:${database.toUri().toASCIIString()}?mode=ro&immutable=1").use {
                connection ->
                connection.createStatement().use { it.execute("PRAGMA query_only = ON") }
                connection.autoCommit = false
                connection.inspectSchemaAndBounds()
                connection.inspectRecords(workspaceDigest)
                SqliteMutationStateInspectionResult.Settled(database)
            }
        } catch (failure: InspectionRejected) {
            SqliteMutationStateInspectionResult.Rejected(failure.failure)
        } catch (_: SQLException) {
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.STORAGE_UNAVAILABLE)
        } catch (_: java.io.IOException) {
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.PATH_REJECTED)
        } catch (_: ReflectiveOperationException) {
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.STORAGE_UNAVAILABLE)
        } catch (_: LinkageError) {
            SqliteMutationStateInspectionResult.Rejected(SqliteMutationStateInspectionFailure.STORAGE_UNAVAILABLE)
        }
}

private fun inspectDatabasePath(database: Path, workspaceDigest: String) {
    if (!workspaceDigest.matches(Regex("[0-9a-f]{64}")) || database.parent?.fileName.toString() != workspaceDigest)
        reject(SqliteMutationStateInspectionFailure.PATH_REJECTED)
    if (!database.isAbsolute || database.normalize() != database)
        reject(SqliteMutationStateInspectionFailure.PATH_REJECTED)
    if (!Files.isRegularFile(database, NOFOLLOW_LINKS) || database.toRealPath() != database)
        reject(SqliteMutationStateInspectionFailure.PATH_REJECTED)
    if (Files.size(database) > MAXIMUM_DATABASE_BYTES) reject(SqliteMutationStateInspectionFailure.CAPACITY_EXCEEDED)
    inspectWal(database.resolveSibling("${database.fileName}-wal"))
}

private fun inspectWal(wal: Path) {
    if (!Files.exists(wal, NOFOLLOW_LINKS)) return
    if (!Files.isRegularFile(wal, NOFOLLOW_LINKS)) reject(SqliteMutationStateInspectionFailure.PATH_REJECTED)
    if (Files.size(wal) != 0L) reject(SqliteMutationStateInspectionFailure.CHECKPOINT_REQUIRED)
}

private fun Connection.inspectSchemaAndBounds() {
    val names =
        createStatement().use { statement ->
            statement.executeQuery("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").use {
                it.schemaNames()
            }
        }
    if (names != TABLES.map { it.name }.toSet()) reject(SqliteMutationStateInspectionFailure.SCHEMA_REJECTED)
    var count = 0L
    for (table in TABLES) {
        inspectColumns(table)
        count += recordCount(table)
        if (count > MAXIMUM_RECORDS) reject(SqliteMutationStateInspectionFailure.CAPACITY_EXCEEDED)
        for (column in table.columns.filter { it.type == "TEXT" }) inspectFieldBound(table, column)
    }
    inspectIntegrity()
}

private fun ResultSet.schemaNames(): Set<String> {
    val found = mutableSetOf<String>()
    while (next()) found += inspectSchemaRow().name
    return found
}

private fun ResultSet.inspectSchemaRow(): StoredTable {
    val table =
        TABLES.singleOrNull { it.name == getString("name") }
            ?: reject(SqliteMutationStateInspectionFailure.SCHEMA_REJECTED)
    if (getString("type") != "table" || normalizeSchema(getString("sql")) != normalizeSchema(table.schema))
        reject(SqliteMutationStateInspectionFailure.SCHEMA_REJECTED)
    return table
}

private fun Connection.inspectColumns(table: StoredTable) {
    val actual =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_xinfo(${table.name})").use { it.columns() }
        }
    if (actual != table.columns) reject(SqliteMutationStateInspectionFailure.SCHEMA_REJECTED)
}

private fun ResultSet.columns(): List<StoredColumn> {
    val columns = mutableListOf<StoredColumn>()
    while (next()) {
        if (getInt("hidden") != 0 || getString("dflt_value") != null)
            reject(SqliteMutationStateInspectionFailure.SCHEMA_REJECTED)
        columns += StoredColumn(getString("name"), getString("type"), getInt("notnull"), getInt("pk"))
    }
    return columns
}

private fun Connection.recordCount(table: StoredTable): Long =
    createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM ${table.name}").use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }

private fun Connection.inspectFieldBound(table: StoredTable, column: StoredColumn) {
    val query =
        "SELECT 1 FROM ${table.name} " + "WHERE length(CAST(${column.name} AS BLOB)) > $MAXIMUM_FIELD_BYTES LIMIT 1"
    createStatement().use { statement ->
        statement.executeQuery(query).use { rows ->
            if (rows.next()) reject(SqliteMutationStateInspectionFailure.CAPACITY_EXCEEDED)
        }
    }
}

private fun Connection.inspectIntegrity() =
    createStatement().use { statement ->
        statement.executeQuery("PRAGMA foreign_key_check").use { rows ->
            if (rows.next()) reject(SqliteMutationStateInspectionFailure.RECORD_REJECTED)
        }
        statement.executeQuery("PRAGMA quick_check(1)").use { rows ->
            if (!rows.next() || rows.getString(1) != "ok" || rows.next())
                reject(SqliteMutationStateInspectionFailure.SCHEMA_REJECTED)
        }
    }

private fun Connection.inspectRecords(workspaceDigest: String) {
    val plans = readPlans(workspaceDigest)
    val receipts = readReceipts(workspaceDigest, plans)
    val settled = readSettledBindings(plans, receipts)
    inspectAttempts(plans, settled)
    if (receipts.keys.any { it !in settled }) reject(SqliteMutationStateInspectionFailure.UNSETTLED_MUTATION)
}

private fun Connection.readPlans(workspaceDigest: String): Map<String, LiveChangePlan> {
    val plans = linkedMapOf<String, LiveChangePlan>()
    for (raw in identities("live_change_plan", "identity")) {
        val plan =
            when (val loaded = loadLiveChangePlan(planIdentity(raw))) {
                is LiveChangePlanLookup.Found -> loaded.plan
                LiveChangePlanLookup.Missing,
                is LiveChangePlanLookup.Rejected -> reject(SqliteMutationStateInspectionFailure.RECORD_REJECTED)
            }
        inspectWorkspace(plan, workspaceDigest)
        plans[plan.planId.value] = plan
    }
    return plans
}

private fun Connection.readReceipts(
    workspaceDigest: String,
    plans: Map<String, LiveChangePlan>,
): Map<String, HistoricalLiveChangeReceipt> {
    val receipts = linkedMapOf<String, HistoricalLiveChangeReceipt>()
    for (raw in identities("live_change_receipt", "plan_identity")) {
        val receipt =
            when (val loaded = loadLiveChangeReceipt(planIdentity(raw))) {
                is LiveChangeReceiptLookup.Found -> loaded.receipt
                LiveChangeReceiptLookup.Missing,
                is LiveChangeReceiptLookup.Rejected -> reject(SqliteMutationStateInspectionFailure.RECORD_REJECTED)
            }
        if (receipt.plan.planId.value !in plans) reject(SqliteMutationStateInspectionFailure.WORKSPACE_MISMATCH)
        inspectWorkspace(receipt.plan, workspaceDigest)
        receipts[receipt.plan.planId.value] = receipt
    }
    return receipts
}

private fun inspectWorkspace(plan: LiveChangePlan, workspaceDigest: String) {
    if (digest(plan.basis.observation.reference.workspaceRoot.value) != workspaceDigest)
        reject(SqliteMutationStateInspectionFailure.WORKSPACE_MISMATCH)
}

private fun Connection.readSettledBindings(
    plans: Map<String, LiveChangePlan>,
    receipts: Map<String, HistoricalLiveChangeReceipt>,
): Set<String> {
    val settled = mutableSetOf<String>()
    for (raw in identities("mutation_recovery", "plan_binding")) {
        val record = recoveryRecord(raw)
        val plan = plans[record.binding.value] ?: reject(SqliteMutationStateInspectionFailure.WORKSPACE_MISMATCH)
        if (
            record.preparation.plannedWrites.map { it.source.value } != plan.writes.entries.map { it.source.path.value }
        )
            reject(SqliteMutationStateInspectionFailure.RECORD_REJECTED)
        inspectSettlement(record, receipts)
        settled += record.binding.value
    }
    return settled
}

private fun Connection.recoveryRecord(raw: String): MutationRecoveryRecord {
    val binding =
        when (val parsed = MutationPlanBinding.parse(raw)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> reject(SqliteMutationStateInspectionFailure.RECORD_REJECTED)
        }
    return when (val loaded = loadMutationRecovery(binding)) {
        is MutationRecoveryLoadResult.Found -> loaded.record
        is MutationRecoveryLoadResult.Absent,
        is MutationRecoveryLoadResult.Rejected -> reject(SqliteMutationStateInspectionFailure.RECORD_REJECTED)
    }
}

private fun inspectSettlement(record: MutationRecoveryRecord, receipts: Map<String, HistoricalLiveChangeReceipt>) {
    when (record) {
        is MutationRecoveryRecord.PreWriteDurable,
        is MutationRecoveryRecord.RecoveryRequired -> reject(SqliteMutationStateInspectionFailure.UNSETTLED_MUTATION)
        is MutationRecoveryRecord.RolledBack ->
            if (record.binding.value in receipts) reject(SqliteMutationStateInspectionFailure.RECORD_REJECTED)
        is MutationRecoveryRecord.AppliedWritesDurable -> {
            val receipt =
                receipts[record.binding.value] ?: reject(SqliteMutationStateInspectionFailure.UNSETTLED_MUTATION)
            inspectReceiptChain(record, receipt)
        }
    }
}

private fun inspectReceiptChain(
    record: MutationRecoveryRecord.AppliedWritesDurable,
    receipt: HistoricalLiveChangeReceipt,
) {
    val recovery =
        when (receipt) {
            is HistoricalLiveAddDeclarationReceipt -> receipt.recovery
            is HistoricalLiveReplaceBodyReceipt -> receipt.recovery
        }
    if (
        recovery.binding.value != record.binding.value ||
            recovery.preparedDigest.value != record.priorDigest.value ||
            recovery.appliedDigest.value != record.digest.value
    )
        reject(SqliteMutationStateInspectionFailure.RECORD_REJECTED)
}

private fun Connection.inspectAttempts(plans: Map<String, LiveChangePlan>, settled: Set<String>) {
    for (raw in identities("live_change_attempt", "identity")) {
        val binding = planIdentity(raw).value.removePrefix("plan:")
        if (binding !in plans) reject(SqliteMutationStateInspectionFailure.RECORD_REJECTED)
        if (binding !in settled) reject(SqliteMutationStateInspectionFailure.UNSETTLED_MUTATION)
    }
}

private fun planIdentity(raw: String): ChangePlanIdentity =
    ChangePlanIdentity.parse(raw) ?: reject(SqliteMutationStateInspectionFailure.RECORD_REJECTED)

private fun Connection.identities(table: String, column: String): List<String> =
    createStatement().use { statement ->
        statement.executeQuery("SELECT $column FROM $table ORDER BY $column").use { it.identities() }
    }

private fun ResultSet.identities(): List<String> {
    val result = mutableListOf<String>()
    while (next()) {
        if (result.size >= MAXIMUM_RECORDS) reject(SqliteMutationStateInspectionFailure.CAPACITY_EXCEEDED)
        result += getString(1)
    }
    return result
}

private fun digest(value: String): String =
    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

// Private filesystem/JDBC effect control flow; the public failure protocol remains closed data.
private class InspectionRejected(val failure: SqliteMutationStateInspectionFailure) : RuntimeException()

private fun reject(failure: SqliteMutationStateInspectionFailure): Nothing = throw InspectionRejected(failure)

private data class StoredColumn(val name: String, val type: String, val notNull: Int = 1, val primaryKey: Int = 0)

private data class StoredTable(val name: String, val columns: List<StoredColumn>, val schema: String)

private fun normalizeSchema(sql: String): String =
    sql.replace("CREATE TABLE IF NOT EXISTS", "CREATE TABLE").trim().replace(Regex("\\s+"), " ")

private const val MAXIMUM_RECORDS = 4096
private const val MAXIMUM_DATABASE_BYTES = 64L * 1024 * 1024
private const val MAXIMUM_FIELD_BYTES = 1024 * 1024
private val TABLES =
    listOf(
        StoredTable(
            "mutation_recovery",
            listOf(
                StoredColumn("plan_binding", "TEXT", primaryKey = 1),
                StoredColumn("stage", "TEXT"),
                StoredColumn("state_version", "INTEGER"),
                StoredColumn("recovery_requirement", "TEXT", notNull = 0),
                StoredColumn("record_digest", "TEXT"),
            ),
            MUTATION_RECOVERY_SCHEMA,
        ),
        StoredTable(
            "mutation_recovery_planned_write",
            listOf(
                StoredColumn("plan_binding", "TEXT", primaryKey = 1),
                StoredColumn("ordinal", "INTEGER", primaryKey = 2),
                StoredColumn("source_path", "TEXT"),
                StoredColumn("preimage_sha256", "TEXT"),
                StoredColumn("preimage_base64", "TEXT"),
            ),
            MUTATION_RECOVERY_PLANNED_WRITE_SCHEMA,
        ),
        StoredTable(
            "mutation_recovery_applied_write",
            listOf(
                StoredColumn("plan_binding", "TEXT", primaryKey = 1),
                StoredColumn("ordinal", "INTEGER", primaryKey = 2),
                StoredColumn("source_path", "TEXT"),
            ),
            MUTATION_RECOVERY_APPLIED_WRITE_SCHEMA,
        ),
        StoredTable(
            "live_change_plan",
            listOf(
                StoredColumn("identity", "TEXT", primaryKey = 1),
                StoredColumn("plan_id", "TEXT"),
                StoredColumn("codec_version", "INTEGER"),
                StoredColumn("document", "TEXT"),
                StoredColumn("document_sha256", "TEXT"),
            ),
            LIVE_CHANGE_PLAN_SCHEMA,
        ),
        StoredTable(
            "live_change_attempt",
            listOf(StoredColumn("identity", "TEXT", primaryKey = 1)),
            LIVE_CHANGE_ATTEMPT_SCHEMA,
        ),
        StoredTable(
            "live_change_receipt",
            listOf(
                StoredColumn("plan_identity", "TEXT", primaryKey = 1),
                StoredColumn("receipt_identity", "TEXT"),
                StoredColumn("codec_version", "INTEGER"),
                StoredColumn("document", "TEXT"),
                StoredColumn("document_sha256", "TEXT"),
            ),
            LIVE_CHANGE_RECEIPT_SCHEMA,
        ),
    )
