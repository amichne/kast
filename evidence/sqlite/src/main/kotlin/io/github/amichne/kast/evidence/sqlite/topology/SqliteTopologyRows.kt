package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologyGenerationDigest
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotManifest
import io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.ProvenanceFailure
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

internal data class SqliteTopologySnapshotRecord(
    val snapshotId: SqliteTopologySnapshotId,
    val snapshot: PublishedTopologySnapshot,
)

internal sealed interface SqliteTopologySnapshotLookup {
    data class Found(val record: SqliteTopologySnapshotRecord) : SqliteTopologySnapshotLookup
    data object Absent : SqliteTopologySnapshotLookup
}

internal enum class SqliteTopologySnapshotIdFailure {
    NON_POSITIVE,
}

@JvmInline
internal value class SqliteTopologySnapshotId private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<SqliteTopologySnapshotId,
         * SqliteTopologySnapshotIdFailure>`.
         *
         * Establishes a positive SQLite topology snapshot row identity. The closed expected
         * failure is [SqliteTopologySnapshotIdFailure]. Raw row IDs may enter only from JDBC.
         */
        internal fun restore(raw: Long): Refinement<SqliteTopologySnapshotId, SqliteTopologySnapshotIdFailure> =
            if (raw > 0L) Refinement.Refined(SqliteTopologySnapshotId(raw))
            else Refinement.Rejected(SqliteTopologySnapshotIdFailure.NON_POSITIVE)
    }
}

internal enum class SqliteTopologySymbolIdFailure {
    NON_POSITIVE,
}

@JvmInline
internal value class SqliteTopologySymbolId private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<SqliteTopologySymbolId,
         * SqliteTopologySymbolIdFailure>`.
         *
         * Establishes one positive location-bearing topology symbol row identity. The closed
         * expected failure is [SqliteTopologySymbolIdFailure]. Raw row IDs may enter only from
         * JDBC inside this adapter.
         */
        internal fun restore(
            raw: Long,
        ): Refinement<SqliteTopologySymbolId, SqliteTopologySymbolIdFailure> =
            if (raw > 0L) Refinement.Refined(SqliteTopologySymbolId(raw))
            else Refinement.Rejected(SqliteTopologySymbolIdFailure.NON_POSITIVE)
    }
}

private data class SqlitePublishedTopologySnapshot(
    override val identity: TopologyWorkspaceIdentity,
    override val manifest: TopologySnapshotManifest,
) : PublishedTopologySnapshot

internal fun Connection.findExactTopologySnapshot(
    identity: TopologyWorkspaceIdentity,
): SqliteTopologySnapshotLookup = prepareStatement(
    """SELECT snapshot_id, workspace_root, generation, source_state, digest,
              file_count, symbol_count, edge_count
       FROM topology_snapshot_v3
       WHERE workspace_root = ? AND generation = ? AND source_state = ?""",
).use { statement ->
    statement.setString(1, identity.lease.workspaceRoot.value)
    statement.setLong(2, identity.lease.generation.value)
    statement.setString(3, identity.sourceState.value)
    statement.executeQuery().use { rows -> rows.snapshotLookup() }
}

internal fun Connection.findLatestTopologySnapshot(
    root: CanonicalWorkspaceRoot,
): SqliteTopologySnapshotLookup = prepareStatement(
    """SELECT snapshot_id, workspace_root, generation, source_state, digest,
              file_count, symbol_count, edge_count
       FROM topology_snapshot_v3 WHERE workspace_root = ?
       ORDER BY snapshot_id DESC LIMIT 1""",
).use { statement ->
    statement.setString(1, root.value)
    statement.executeQuery().use { rows -> rows.snapshotLookup() }
}

internal fun Connection.insertTopologySnapshot(
    generation: CompleteTopologyGeneration,
): SqliteTopologySnapshotRecord {
    val manifest = TopologySnapshotManifest.from(generation)
    prepareStatement(
        """INSERT INTO topology_snapshot_v3(
               workspace_root, generation, source_state, digest,
               file_count, symbol_count, edge_count
           ) VALUES (?, ?, ?, ?, ?, ?, ?)""",
    ).use { statement ->
        statement.setString(1, generation.identity.lease.workspaceRoot.value)
        statement.setLong(2, generation.identity.lease.generation.value)
        statement.setString(3, generation.identity.sourceState.value)
        statement.setString(4, manifest.digest.value)
        statement.setInt(5, manifest.cardinalities.files)
        statement.setInt(6, manifest.cardinalities.symbols)
        statement.setInt(7, manifest.cardinalities.edges)
        if (statement.executeUpdate() != 1) corrupt("topology snapshot insert changed no row")
    }
    val snapshotId = createStatement().use { statement ->
        statement.executeQuery("SELECT last_insert_rowid()").use { rows ->
            if (!rows.next()) corrupt("topology snapshot insert returned no identity")
            SqliteTopologySnapshotId.restore(rows.getLong(1)).refined("snapshot identity")
        }
    }
    return SqliteTopologySnapshotRecord(
        snapshotId,
        SqlitePublishedTopologySnapshot(generation.identity, manifest),
    )
}

/**
 * Proof transition: `(Connection, PublishedTopologySnapshot) -> TopologySnapshotContentRead`.
 *
 * Reconstructs and re-admits every persisted file, symbol, edge, manifest count, and digest for
 * the exact snapshot. [TopologySnapshotReadFailure.CORRUPT_SNAPSHOT] closes every malformed or
 * inconsistent row; SQL availability failures remain for the owning store boundary to classify.
 * Raw JDBC values do not leave this SQLite adapter.
 */
internal fun Connection.readTopologyContent(
    snapshot: PublishedTopologySnapshot,
): TopologySnapshotContentRead = try {
    reconstructTopologyContent(snapshot)
} catch (_: SqliteTopologyCorruption) {
    TopologySnapshotContentRead.Rejected(TopologySnapshotReadFailure.CORRUPT_SNAPSHOT)
}

private fun Connection.reconstructTopologyContent(
    snapshot: PublishedTopologySnapshot,
): TopologySnapshotContentRead {
    val record = when (val lookup = findExactTopologySnapshot(snapshot.identity)) {
        is SqliteTopologySnapshotLookup.Found -> lookup.record
        SqliteTopologySnapshotLookup.Absent -> corrupt("topology snapshot identity is absent")
    }
    if (record.snapshot.manifest != snapshot.manifest) {
        corrupt("topology snapshot manifest moved")
    }
    val files = readFiles(record)
    val symbols = readSymbols(record, files)
    val edges = readEdges(record, symbols)
    val complete = files.values.sorted().map { file ->
        CompleteTopologyFile.admit(
            file,
            symbols.values.filter { it.file == file }.sorted(),
            edges.filter { it.source.file == file }.sorted(),
        ).refined("complete topology file")
    }
    return when (val content = TopologySnapshotContent.admit(record.snapshot, complete)) {
        is Refinement.Refined -> TopologySnapshotContentRead.Loaded(content.value)
        is Refinement.Rejected -> corrupt("topology content rejected: ${content.failure}")
    }
}

private fun Connection.readFiles(
    record: SqliteTopologySnapshotRecord,
): Map<WorkspaceSourcePath, TopologySourceFile> = prepareStatement(
    """SELECT path, content_hash, module_name, build_root, project_path,
              source_set, source_root, provenance
       FROM topology_file_v3 WHERE snapshot_id = ? ORDER BY path""",
).use { statement ->
    statement.setLong(1, record.snapshotId.value)
    statement.executeQuery().use { rows ->
        buildMap {
            while (rows.next()) {
                val sourceRoot = SourceRoot.admit(
                    GradleSourceRootEvidence(
                        rows.getString("module_name"),
                        rows.getString("build_root"),
                        rows.getString("project_path"),
                        rows.getString("source_set"),
                        rows.getString("source_root"),
                        rows.getString("provenance").sourceRootProvenance(),
                    ),
                ).refined("source root")
                val path = WorkspaceSourcePath.parse(rows.getString("path")).refined("source path")
                val hash = WorkspaceSourceContentHash.parse(
                    rows.getString("content_hash"),
                ).refined("content hash")
                put(
                    path,
                    TopologySourceFile.restore(
                        record.snapshot.identity,
                        sourceRoot,
                        path,
                        hash,
                    ).refined("topology source file"),
                )
            }
        }
    }
}

private fun Connection.readSymbols(
    record: SqliteTopologySnapshotRecord,
    files: Map<WorkspaceSourcePath, TopologySourceFile>,
): Map<SqliteTopologySymbolId, TopologySymbol> = prepareStatement(
    """SELECT symbol_id, compiler_identity, compiler_signature, file_path, start_offset, end_offset, symbol_name,
              qualified_identity, symbol_kind
       FROM topology_symbol_v3 WHERE snapshot_id = ?
       ORDER BY compiler_identity, file_path, start_offset, end_offset""",
).use { statement ->
    statement.setLong(1, record.snapshotId.value)
    statement.executeQuery().use { rows ->
        buildMap {
            while (rows.next()) {
                val filePath = WorkspaceSourcePath.parse(
                    rows.getString("file_path"),
                ).refined("symbol file path")
                val file = files[filePath] ?: corrupt("symbol file absent")
                val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
                val fileIdentity = SymbolDiscoveryFileIdentity.fromBoundary(
                    file.workspace.lease.workspaceRoot,
                    absolute,
                    absolute.toUri().toString(),
                ).refined("symbol file identity")
                val compilerIdentity = CompilerSymbolIdentity.parse(
                    rows.getString("compiler_identity"),
                ).refined("compiler identity")
                val compilerSignature = CanonicalCompilerSignature.restoreCanonicalEncoding(
                    rows.getString("compiler_signature"),
                ).refined("compiler signature")
                val evidence = CompilerGroundedSymbolEvidence.restoreBoundary(
                    fileIdentity,
                    rows.getInt("start_offset"),
                    rows.getInt("end_offset"),
                    rows.getString("symbol_name"),
                    rows.getString("qualified_identity"),
                    enumValue<CompilerSymbolKind>(rows.getString("symbol_kind")),
                    compilerSignature,
                    compilerIdentity,
                ).refined("compiler evidence")
                put(
                    SqliteTopologySymbolId.restore(
                        rows.getLong("symbol_id"),
                    ).refined("symbol identity"),
                    TopologySymbol.admit(file, evidence).refined("topology symbol"),
                )
            }
        }
    }
}

private fun Connection.readEdges(
    record: SqliteTopologySnapshotRecord,
    symbols: Map<SqliteTopologySymbolId, TopologySymbol>,
): List<TopologyEdge> = prepareStatement(
    """SELECT edge_kind, source_symbol_id, target_symbol_id, occurrence_file_path,
              start_offset, end_offset
       FROM topology_edge_v3 WHERE snapshot_id = ?
       ORDER BY edge_kind, source_symbol_id, target_symbol_id, occurrence_file_path,
                start_offset, end_offset""",
).use { statement ->
    statement.setLong(1, record.snapshotId.value)
    statement.executeQuery().use { rows ->
        buildList {
            while (rows.next()) {
                val sourceIdentity = SqliteTopologySymbolId.restore(
                    rows.getLong("source_symbol_id"),
                ).refined("edge source identity")
                val targetIdentity = SqliteTopologySymbolId.restore(
                    rows.getLong("target_symbol_id"),
                ).refined("edge target identity")
                val source = symbols[sourceIdentity]
                             ?: corrupt("edge source absent")
                val target = symbols[targetIdentity]
                             ?: corrupt("edge target absent")
                val occurrenceFile = WorkspaceSourcePath.parse(
                    rows.getString("occurrence_file_path"),
                ).refined("edge occurrence file path")
                add(
                    TopologyEdge.restore(
                        enumValue<TopologyEdgeKind>(rows.getString("edge_kind")),
                        source,
                        target,
                        occurrenceFile,
                        rows.getInt("start_offset"),
                        rows.getInt("end_offset"),
                    ).refined("topology edge"),
                )
            }
        }
    }
}

private fun ResultSet.snapshot(): SqliteTopologySnapshotRecord {
    val root = CanonicalWorkspaceRoot.fromCanonicalPath(
        Path.of(getString("workspace_root")),
    ).refined("workspace root")
    val generation = EvidenceGeneration.parse(getLong("generation")).refined("generation")
    val identity = TopologyWorkspaceIdentity(
        SemanticReadLease(root, generation),
        WorkspaceStateIdentity.parse(getString("source_state")).refined("source state"),
    )
    val digest = TopologyGenerationDigest.parse(getString("digest")).refined("digest")
    val manifest = TopologySnapshotManifest.restore(
        digest,
        getInt("file_count"),
        getInt("symbol_count"),
        getInt("edge_count"),
    ).refined("manifest")
    return SqliteTopologySnapshotRecord(
        SqliteTopologySnapshotId.restore(getLong("snapshot_id")).refined("snapshot identity"),
        SqlitePublishedTopologySnapshot(identity, manifest),
    )
}

private fun ResultSet.snapshotLookup(): SqliteTopologySnapshotLookup =
    if (next()) SqliteTopologySnapshotLookup.Found(snapshot())
    else SqliteTopologySnapshotLookup.Absent

private fun String.sourceRootProvenance(): SourceRootProvenance = when (this) {
    "AUTHORED" -> SourceRootProvenance.Authored
    "GENERATED" -> SourceRootProvenance.Generated
    "UNKNOWN_EXCLUDED" -> SourceRootProvenance.Unknown(ProvenanceFailure.ExcludedFromSourceModel)
    else -> corrupt("unknown source provenance")
}

private inline fun <reified Value : Enum<Value>> enumValue(raw: String): Value = try {
    enumValueOf(raw)
} catch (_: IllegalArgumentException) {
    corrupt("unknown ${Value::class.simpleName}")
}
private fun <Value, Failure> Refinement<Value, Failure>.refined(field: String): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> corrupt("invalid $field: $failure")
}

private fun corrupt(message: String): Nothing = throw SqliteTopologyCorruption(message)
