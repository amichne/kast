package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalCanonicalEncoding
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalFactPolicy
import io.github.amichne.kast.topology.contract.historical.HistoricalInventoryCounts
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrence
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrenceOwner
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInventory
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet

internal data class HistoricalArtifactAdmission(
    val inventory: HistoricalSnapshotInventory,
    val rowsExamined: Long,
    val retention: HistoricalSqliteRetention,
    val endpointIdentifiersRead: Long,
)

internal fun Connection.admitHistoricalArtifact(): Refinement<HistoricalArtifactAdmission, HistoricalStorageFailure> {
    val metadata =
        readArtifactMetadata().orReturn {
            return it
        }
    val digest = MessageDigest.getInstance("SHA-256")
    val snapshot = metadata.snapshot
    HistoricalCanonicalEncoding.update(digest, HistoricalCanonicalEncoding.header(snapshot.header))
    snapshot.modules.forEach { HistoricalCanonicalEncoding.update(digest, HistoricalCanonicalEncoding.module(it)) }
    snapshot.dependencies.forEach {
        HistoricalCanonicalEncoding.update(digest, HistoricalCanonicalEncoding.dependency(it))
    }
    val facts =
        streamArtifactFacts(snapshot, digest).orReturn {
            return it
        }
    snapshot.coverage.forEach { HistoricalCanonicalEncoding.update(digest, HistoricalCanonicalEncoding.coverage(it)) }
    return metadata.finish(digest, facts)
}

private data class HistoricalArtifactMetadata(val snapshot: HistoricalSnapshot, val identity: HistoricalDigest) {
    fun finish(
        digest: MessageDigest,
        facts: HistoricalArtifactFacts,
    ): Refinement<HistoricalArtifactAdmission, HistoricalStorageFailure> {
        val restored =
            HistoricalDigest.parse(
                    digest.digest().joinToString("") {
                        (it.toInt() and 255).toString(16).padStart(2, '0')
                    }
                )
                .storage()
                .orReturn {
                    return it
                }
        if (restored != identity) return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
        val inventory =
            HistoricalSnapshotInventory.fromStored(
                    identity,
                    snapshot.header,
                    snapshot.modules,
                    snapshot.coverage,
                    HistoricalInventoryCounts(
                        historicalCount(facts.files.rows),
                        historicalCount(facts.declarations.rows),
                        historicalCount(facts.occurrences.rows),
                    ),
                )
                .storage()
                .orReturn {
                    return it
                }
        val metadataRows = snapshot.historicalRowCount()
        val maximumFacts =
            maxOf(facts.files.maximumRetained, facts.declarations.maximumRetained, facts.occurrences.maximumRetained)
        return Refinement.Refined(
            HistoricalArtifactAdmission(
                inventory,
                metadataRows + facts.files.rows + facts.declarations.rows + facts.occurrences.rows,
                HistoricalSqliteRetention(historicalCount(metadataRows), historicalCount(maximumFacts)),
                facts.endpointIdentifiers,
            )
        )
    }
}

private fun Connection.readArtifactMetadata(): Refinement<HistoricalArtifactMetadata, HistoricalStorageFailure> {
    val (header, identity) =
        readHistoricalHeader().orReturn {
            return it
        }
    val modules =
        readHistoricalModules().orReturn {
            return it
        }
    val dependencies =
        readHistoricalDependencies().orReturn {
            return it
        }
    val coverage =
        readHistoricalCoverage().orReturn {
            return it
        }
    return HistoricalSnapshotInput(header, modules, dependencies, emptyList(), emptyList(), emptyList(), coverage)
        .admit()
        .storage()
        .map { HistoricalArtifactMetadata(it, identity) }
}

private data class HistoricalArtifactFacts(
    val files: HistoricalStreamWork,
    val declarations: HistoricalStreamWork,
    val occurrences: HistoricalStreamWork,
    val endpointIdentifiers: Long,
)

private fun Connection.streamArtifactFacts(
    snapshot: HistoricalSnapshot,
    digest: MessageDigest,
): Refinement<HistoricalArtifactFacts, HistoricalStorageFailure> {
    val moduleMap = snapshot.modules.associateBy { it.id }
    val files =
        streamHistoricalRows(
                "SELECT * FROM historical_file ORDER BY ordinal",
                compareBy { it.path },
                { rows ->
                    historicalFile(rows).flatMap { file ->
                        HistoricalFactPolicy.admitFile(file, moduleMap).storage().map { file }
                    }
                },
                HistoricalCanonicalEncoding::file,
                digest,
            )
            .orReturn {
                return it
            }
    val declarations =
        streamHistoricalRows(
                "SELECT * FROM historical_declaration ORDER BY ordinal",
                compareBy { it.id },
                { historicalDeclaration(it) },
                HistoricalCanonicalEncoding::declaration,
                digest,
            )
            .orReturn {
                return it
            }
    var endpointIdentifiers = 0L
    val occurrences =
        streamHistoricalRows(
                "$HISTORICAL_OCCURRENCE_QUERY ORDER BY o.ordinal",
                HistoricalCanonicalEncoding.occurrenceOrder,
                { rows ->
                    admittedOccurrence(rows).map { value ->
                        endpointIdentifiers += value.second
                        value.first
                    }
                },
                HistoricalCanonicalEncoding::occurrence,
                digest,
            )
            .orReturn {
                return it
            }
    return Refinement.Refined(HistoricalArtifactFacts(files, declarations, occurrences, endpointIdentifiers))
}

private fun admittedOccurrence(
    rows: ResultSet
): Refinement<Pair<HistoricalOccurrence, Long>, HistoricalStorageFailure> {
    val occurrence =
        historicalOccurrence(rows).orReturn {
            return it
        }
    val (closure, identifiersRead) =
        when (val owner = occurrence.owner) {
            is HistoricalOccurrenceOwner.Declaration -> setOf(occurrence.target, owner.id) to 2L
            is HistoricalOccurrenceOwner.FileScoped,
            is HistoricalOccurrenceOwner.Unavailable -> setOf(occurrence.target) to 1L
        }
    return HistoricalFactPolicy.admitOccurrence(occurrence, closure).storage().map {
        occurrence to identifiersRead
    }
}

private sealed interface HistoricalPrevious<out T> {
    data object Start : HistoricalPrevious<Nothing>

    data class Value<T>(val fact: T) : HistoricalPrevious<T>
}

private data class HistoricalStreamWork(val rows: Long, val maximumRetained: Long)

private fun <T> Connection.streamHistoricalRows(
    sql: String,
    order: Comparator<T>,
    parse: (ResultSet) -> Refinement<T, HistoricalStorageFailure>,
    encode: (T) -> List<String>,
    digest: MessageDigest,
): Refinement<HistoricalStreamWork, HistoricalStorageFailure> =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> rows.readHistoricalStream(order, parse, encode, digest) }
    }

private fun <T> ResultSet.readHistoricalStream(
    order: Comparator<T>,
    parse: (ResultSet) -> Refinement<T, HistoricalStorageFailure>,
    encode: (T) -> List<String>,
    digest: MessageDigest,
): Refinement<HistoricalStreamWork, HistoricalStorageFailure> {
    var previous: HistoricalPrevious<T> = HistoricalPrevious.Start
    var count = 0L
    var maximumRetained = 0L
    while (next()) {
        if (getLong("ordinal") != count) return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
        val current =
            parse(this).orReturn {
                return it
            }
        when (val prior = previous) {
            HistoricalPrevious.Start -> maximumRetained = 1
            is HistoricalPrevious.Value -> {
                maximumRetained = 2
                if (order.compare(prior.fact, current) >= 0)
                    return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
            }
        }
        HistoricalCanonicalEncoding.update(digest, encode(current))
        previous = HistoricalPrevious.Value(current)
        count++
    }
    return Refinement.Refined(HistoricalStreamWork(count, maximumRetained))
}
