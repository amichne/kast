package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalOpenWork
import io.github.amichne.kast.topology.contract.historical.HistoricalReadCount
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CancellationException

enum class HistoricalStorageFailure {
    PATH_REJECTED,
    SCHEMA_REJECTED,
    PROFILE_REJECTED,
    CONTENT_REJECTED,
    STORAGE_UNAVAILABLE,
    CAPACITY_EXCEEDED,
    ATOMIC_PUBLICATION_UNAVAILABLE,
    DECLARATION_NOT_FOUND,
    CURSOR_MISMATCH,
}

data class HistoricalPublicationWork(
    val stagedBatches: HistoricalReadCount,
    val committedBatches: HistoricalReadCount,
    val storedRows: HistoricalReadCount,
    val verificationPasses: HistoricalReadCount,
    val verificationRows: HistoricalReadCount,
    val artifactBytes: HistoricalReadCount,
)

data class HistoricalSqlitePublication(val identity: HistoricalDigest, val work: HistoricalPublicationWork)

internal enum class HistoricalSqliteFaultPoint {
    BEFORE_COMMIT,
    BEFORE_PUBLICATION,
}

internal fun interface HistoricalSqliteFaultInjector {
    fun observe(point: HistoricalSqliteFaultPoint)

    data object Disabled : HistoricalSqliteFaultInjector {
        override fun observe(point: HistoricalSqliteFaultPoint) = Unit
    }
}

/** Explicit artifact publication and offline admission. No workspace or semantic capability enters this adapter. */
object SqliteHistoricalSnapshotStore {
    fun publish(
        destination: Path,
        snapshot: HistoricalSnapshot,
    ): Refinement<HistoricalSqlitePublication, HistoricalStorageFailure> =
        publish(destination, snapshot, HistoricalSqliteFaultInjector.Disabled)

    internal fun publish(
        destination: Path,
        snapshot: HistoricalSnapshot,
        faults: HistoricalSqliteFaultInjector,
    ): Refinement<HistoricalSqlitePublication, HistoricalStorageFailure> =
        publishPrepared(destination, snapshot.identity, snapshot.historicalRowCount(), faults = faults) {
            writeCandidate(it, snapshot, faults)
        }

    internal fun publishPrepared(
        destination: Path,
        identity: HistoricalDigest,
        rows: Long,
        extraVerificationRows: Long = 0,
        faults: HistoricalSqliteFaultInjector = HistoricalSqliteFaultInjector.Disabled,
        write: (Path) -> Refinement<Unit, HistoricalStorageFailure>,
    ): Refinement<HistoricalSqlitePublication, HistoricalStorageFailure> = historicalEffect {
        admitHistoricalPath(destination, mustExist = false).orReturn {
            return@historicalEffect it
        }
        val (priorPasses, priorRows) =
            priorPublicationWork(destination).orReturn {
                return@historicalEffect it
            }
        val temporary = Files.createTempFile(destination.parent, ".kast-topology-", ".sqlite")
        try {
            write(temporary).orReturn {
                return@historicalEffect it
            }
            val restored =
                openHistoricalConnection(temporary)
                    .use { connection ->
                        connection.examineHistoricalSchema().flatMap { connection.admitHistoricalArtifact() }
                    }
                    .orReturn {
                        return@historicalEffect it
                    }
            if (restored.inventory.identity != identity)
                return@historicalEffect Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
            val bytes = Files.size(temporary)
            val extraPasses = if (extraVerificationRows > 0) 1 else 0
            val work =
                publicationWork(rows, priorPasses + extraPasses, priorRows + extraVerificationRows, bytes).orReturn {
                    return@historicalEffect it
                }
            faults.observe(HistoricalSqliteFaultPoint.BEFORE_PUBLICATION)
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            Refinement.Refined(HistoricalSqlitePublication(identity, work))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun priorPublicationWork(destination: Path): Refinement<Pair<Long, Long>, HistoricalStorageFailure> =
        if (Files.exists(destination, NOFOLLOW_LINKS))
            openHistoricalConnection(destination).use { connection ->
                connection
                    .examineHistoricalSchema()
                    .flatMap { connection.admitHistoricalArtifact() }
                    .map { 1L to it.rowsExamined }
            }
        else Refinement.Refined(0L to 0L)

    private fun writeCandidate(
        path: Path,
        snapshot: HistoricalSnapshot,
        faults: HistoricalSqliteFaultInjector,
    ): Refinement<Unit, HistoricalStorageFailure> =
        historicalWriteConnection(path).use { connection ->
            connection.initializeHistoricalSchema()
            connection.autoCommit = false
            var completion = HistoricalTransactionCompletion.ROLLBACK
            try {
                connection.writeHistoricalSnapshot(snapshot)
                when (val restored = connection.readHistoricalSnapshot()) {
                    is Refinement.Refined -> {
                        faults.observe(HistoricalSqliteFaultPoint.BEFORE_COMMIT)
                        connection.commit()
                        completion = HistoricalTransactionCompletion.COMMITTED
                        Refinement.Refined(Unit)
                    }
                    is Refinement.Rejected -> restored
                }
            } finally {
                if (completion == HistoricalTransactionCompletion.ROLLBACK) connection.rollback()
            }
        }

    fun open(path: Path): Refinement<SqliteHistoricalSnapshotReader, HistoricalStorageFailure> =
        open(path, Path.of(System.getProperty("java.io.tmpdir")))

    internal fun open(
        path: Path,
        privateCopyRoot: Path,
    ): Refinement<SqliteHistoricalSnapshotReader, HistoricalStorageFailure> = historicalEffect {
        admitHistoricalPath(path, mustExist = true).flatMap {
            val image =
                HistoricalPrivateReaderImage.copy(path, privateCopyRoot).orReturn {
                    return@historicalEffect it
                }
            var ownership = HistoricalConnectionOwnership.CLOSE
            try {
                val result = openPrivateImage(image)
                if (result is Refinement.Refined) ownership = HistoricalConnectionOwnership.TRANSFER
                result
            } finally {
                if (ownership == HistoricalConnectionOwnership.CLOSE) image.close()
            }
        }
    }

    private fun openPrivateImage(
        image: HistoricalPrivateReaderImage
    ): Refinement<SqliteHistoricalSnapshotReader, HistoricalStorageFailure> {
        val connection = openHistoricalConnection(image.path)
        var ownership = HistoricalConnectionOwnership.CLOSE
        try {
            val result =
                connection
                    .examineHistoricalSchema()
                    .flatMap { connection.admitHistoricalArtifact() }
                    .map { admitted ->
                        SqliteHistoricalSnapshotReader(
                            connection,
                            image,
                            admitted.inventory,
                            HistoricalOpenWork(
                                historicalCount(admitted.rowsExamined),
                                image.copiedBytes,
                                image.copiedBytes,
                                image.copiedBytes,
                            ),
                            admitted.retention,
                            historicalCount(admitted.endpointIdentifiersRead),
                        )
                    }
            if (result is Refinement.Refined) ownership = HistoricalConnectionOwnership.TRANSFER
            return result
        } finally {
            if (ownership == HistoricalConnectionOwnership.CLOSE) connection.close()
        }
    }
}

private enum class HistoricalTransactionCompletion {
    COMMITTED,
    ROLLBACK,
}

private enum class HistoricalConnectionOwnership {
    CLOSE,
    TRANSFER,
}

private fun publicationWork(
    rows: Long,
    priorPasses: Long,
    priorRows: Long,
    bytes: Long,
): Refinement<HistoricalPublicationWork, HistoricalStorageFailure> {
    val batch =
        HistoricalReadCount.parse(1).storage().orReturn {
            return it
        }
    val stored =
        HistoricalReadCount.parse(rows).storage().orReturn {
            return it
        }
    val passes =
        HistoricalReadCount.parse(2 + priorPasses).storage().orReturn {
            return it
        }
    val examined =
        HistoricalReadCount.parse(2 * rows + priorRows).storage().orReturn {
            return it
        }
    val artifact =
        HistoricalReadCount.parse(bytes).storage().orReturn {
            return it
        }
    return Refinement.Refined(HistoricalPublicationWork(batch, batch, stored, passes, examined, artifact))
}

internal fun HistoricalSnapshot.historicalRowCount(): Long =
    1L +
        modules.size +
        dependencies.size +
        files.size +
        declarations.size +
        occurrences.size +
        coverage.size +
        coverage.sumOf { value ->
            when (val status = value.status) {
                io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus.Complete,
                io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus.NotCollected -> 0
                is io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus.OutsideProfile -> 1
                is io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus.Qualified ->
                    status.reasons.size
            }
        }

internal fun openHistoricalConnection(path: Path): Connection {
    ensureHistoricalDriver()
    return DriverManager.getConnection("jdbc:sqlite:${path.toUri().toASCIIString()}?mode=ro&immutable=1").also {
        connection ->
        connection.createStatement().use { it.execute("PRAGMA query_only = ON") }
    }
}

internal fun historicalWriteConnection(path: Path): Connection {
    ensureHistoricalDriver()
    return DriverManager.getConnection("jdbc:sqlite:$path")
}

private fun ensureHistoricalDriver() {
    Class.forName("org.sqlite.JDBC", true, SqliteHistoricalSnapshotStore::class.java.classLoader)
}

internal fun admitHistoricalPath(path: Path, mustExist: Boolean): Refinement<Unit, HistoricalStorageFailure> {
    historicalPathShape(path).orReturn {
        return it
    }
    if ((mustExist || Files.exists(path, NOFOLLOW_LINKS)) && !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
        return Refinement.Rejected(HistoricalStorageFailure.PATH_REJECTED)
    }
    if (Files.exists(path, NOFOLLOW_LINKS) && Files.size(path) > MAXIMUM_HISTORICAL_BYTES) {
        return Refinement.Rejected(HistoricalStorageFailure.CAPACITY_EXCEEDED)
    }
    for (suffix in listOf("-wal", "-shm", "-journal")) {
        val sidecar = path.resolveSibling("${path.fileName}$suffix")
        if (Files.exists(sidecar, NOFOLLOW_LINKS)) return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
    }
    return Refinement.Refined(Unit)
}

private fun historicalPathShape(path: Path): Refinement<Unit, HistoricalStorageFailure> {
    if (!path.isAbsolute || path.normalize() != path || path.parent == null)
        return Refinement.Rejected(HistoricalStorageFailure.PATH_REJECTED)
    if (
        !Files.isDirectory(path.parent, NOFOLLOW_LINKS) ||
            path.parent.toRealPath() != path.parent ||
            Files.isSymbolicLink(path)
    ) {
        return Refinement.Rejected(HistoricalStorageFailure.PATH_REJECTED)
    }
    return Refinement.Refined(Unit)
}

internal fun Connection.examineHistoricalSchema(
    expected: List<String> = historicalSchema
): Refinement<Unit, HistoricalStorageFailure> {
    examineHistoricalVersion().orReturn {
        return it
    }
    val actual =
        queryHistorical("SELECT sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") { rows ->
                val raw = rows.getString(1)
                if (raw == null) Refinement.Rejected(HistoricalStorageFailure.SCHEMA_REJECTED)
                else Refinement.Refined(normalizedHistoricalSchema(raw))
            }
            .orReturn {
                return it
            }
    if (actual.toSet() != expected.map(::normalizedHistoricalSchema).toSet())
        return Refinement.Rejected(HistoricalStorageFailure.SCHEMA_REJECTED)
    return examineHistoricalIntegrity()
}

private fun Connection.examineHistoricalVersion(): Refinement<Unit, HistoricalStorageFailure> =
    createStatement().use { statement ->
        statement.executeQuery("PRAGMA application_id").use { rows ->
            if (!rows.next() || rows.getInt(1) != HISTORICAL_APPLICATION_ID)
                return Refinement.Rejected(HistoricalStorageFailure.SCHEMA_REJECTED)
        }
        statement.executeQuery("PRAGMA user_version").use { rows ->
            if (!rows.next() || rows.getInt(1) != HISTORICAL_SCHEMA_VERSION)
                return Refinement.Rejected(HistoricalStorageFailure.SCHEMA_REJECTED)
        }
        Refinement.Refined(Unit)
    }

private fun Connection.examineHistoricalIntegrity(): Refinement<Unit, HistoricalStorageFailure> =
    createStatement().use { statement ->
        statement.executeQuery("PRAGMA foreign_key_check").use { rows ->
            if (rows.next()) return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
        }
        statement.executeQuery("PRAGMA quick_check(1)").use { rows ->
            if (!rows.next() || rows.getString(1) != "ok" || rows.next())
                return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
        }
        Refinement.Refined(Unit)
    }

private fun normalizedHistoricalSchema(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

internal inline fun <T> historicalEffect(
    block: () -> Refinement<T, HistoricalStorageFailure>
): Refinement<T, HistoricalStorageFailure> =
    try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: AtomicMoveNotSupportedException) {
        Refinement.Rejected(HistoricalStorageFailure.ATOMIC_PUBLICATION_UNAVAILABLE)
    } catch (_: SQLException) {
        Refinement.Rejected(HistoricalStorageFailure.STORAGE_UNAVAILABLE)
    } catch (_: java.io.IOException) {
        Refinement.Rejected(HistoricalStorageFailure.STORAGE_UNAVAILABLE)
    } catch (_: SecurityException) {
        Refinement.Rejected(HistoricalStorageFailure.PATH_REJECTED)
    }

internal const val MAXIMUM_HISTORICAL_BYTES = 1024L * 1024 * 1024
