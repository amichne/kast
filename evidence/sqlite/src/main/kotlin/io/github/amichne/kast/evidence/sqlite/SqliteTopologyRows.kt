package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
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

private data class SqlitePublishedTopologySnapshot(
    override val identity: TopologyWorkspaceIdentity,
    override val manifest: TopologySnapshotManifest,
) : PublishedTopologySnapshot

internal fun Connection.findExactTopologySnapshot(
    identity: TopologyWorkspaceIdentity,
): SqliteTopologySnapshotLookup = prepareStatement(
    """SELECT snapshot_id, workspace_root, generation, source_state, digest,
              file_count, symbol_count, edge_count
       FROM topology_snapshot
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
       FROM topology_snapshot WHERE workspace_root = ?
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
        """INSERT INTO topology_snapshot(
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

internal fun Connection.insertTopologyContent(
    snapshotId: SqliteTopologySnapshotId,
    generation: CompleteTopologyGeneration,
) {
    generation.files.forEach { complete -> insertFile(snapshotId, complete.file) }
    generation.symbols.forEach { symbol -> insertSymbol(snapshotId, symbol) }
    generation.edges.forEach { edge -> insertEdge(snapshotId, edge) }
}

private fun Connection.insertFile(snapshotId: SqliteTopologySnapshotId, file: TopologySourceFile) {
    prepareStatement(
        """INSERT INTO topology_file(
               snapshot_id, path, content_hash, module_name, build_root, project_path,
               source_set, source_root, provenance
           ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
    ).use { statement ->
        statement.setLong(1, snapshotId.value)
        statement.setString(2, file.path.value)
        statement.setString(3, file.contentHash.value)
        statement.setString(4, file.sourceRoot.owner.module.value)
        statement.setString(5, file.sourceRoot.owner.project.buildRoot.value)
        statement.setString(6, file.sourceRoot.owner.project.projectPath.value)
        statement.setString(7, file.sourceRoot.owner.sourceSet.value)
        statement.setString(8, file.sourceRoot.location.value)
        statement.setString(9, file.sourceRoot.provenance.sqliteName())
        if (statement.executeUpdate() != 1) corrupt("topology file insert changed no row")
    }
}

private fun Connection.insertSymbol(
    snapshotId: SqliteTopologySnapshotId,
    symbol: TopologySymbol,
) {
    prepareStatement(
        """INSERT INTO topology_symbol(
               snapshot_id, compiler_identity, file_path, start_offset, end_offset,
               symbol_name, qualified_identity, symbol_kind
           ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
    ).use { statement ->
        statement.setLong(1, snapshotId.value)
        statement.setString(2, symbol.evidence.compilerIdentity.value)
        statement.setString(3, symbol.file.path.value)
        statement.setInt(4, symbol.evidence.range.startInclusive)
        statement.setInt(5, symbol.evidence.range.endExclusive)
        statement.setString(6, symbol.evidence.name.value)
        statement.setString(7, symbol.evidence.qualifiedIdentity.sqliteValue())
        statement.setString(8, symbol.evidence.kind.name)
        if (statement.executeUpdate() != 1) corrupt("topology symbol insert changed no row")
    }
}

private fun Connection.insertEdge(snapshotId: SqliteTopologySnapshotId, edge: TopologyEdge) {
    prepareStatement(
        """INSERT INTO topology_edge(
               snapshot_id, edge_kind, source_identity, target_identity,
               occurrence_file_path, start_offset, end_offset
           ) VALUES (?, ?, ?, ?, ?, ?, ?)""",
    ).use { statement ->
        statement.setLong(1, snapshotId.value)
        statement.setString(2, edge.kind.name)
        statement.setString(3, edge.source.evidence.compilerIdentity.value)
        statement.setString(4, edge.target.evidence.compilerIdentity.value)
        statement.setString(5, edge.source.file.path.value)
        statement.setInt(6, edge.occurrence.startInclusive)
        statement.setInt(7, edge.occurrence.endExclusive)
        if (statement.executeUpdate() != 1) corrupt("topology edge insert changed no row")
    }
}

internal fun Connection.readTopologyContent(
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
       FROM topology_file WHERE snapshot_id = ? ORDER BY path""",
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
): Map<CompilerSymbolIdentity, TopologySymbol> = prepareStatement(
    """SELECT compiler_identity, file_path, start_offset, end_offset, symbol_name,
              qualified_identity, symbol_kind
       FROM topology_symbol WHERE snapshot_id = ? ORDER BY compiler_identity""",
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
                val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
                    fileIdentity,
                    rows.getInt("start_offset"),
                    rows.getInt("end_offset"),
                    rows.getString("symbol_name"),
                    rows.getString("qualified_identity"),
                    enumValue<CompilerSymbolKind>(rows.getString("symbol_kind")),
                    compilerIdentity,
                ).refined("compiler evidence")
                put(
                    compilerIdentity,
                    TopologySymbol.admit(file, evidence).refined("topology symbol"),
                )
            }
        }
    }
}

private fun Connection.readEdges(
    record: SqliteTopologySnapshotRecord,
    symbols: Map<CompilerSymbolIdentity, TopologySymbol>,
): List<TopologyEdge> = prepareStatement(
    """SELECT edge_kind, source_identity, target_identity, start_offset, end_offset
       FROM topology_edge WHERE snapshot_id = ?
       ORDER BY edge_kind, source_identity, target_identity, occurrence_file_path,
                start_offset, end_offset""",
).use { statement ->
    statement.setLong(1, record.snapshotId.value)
    statement.executeQuery().use { rows ->
        buildList {
            while (rows.next()) {
                val sourceIdentity = CompilerSymbolIdentity.parse(
                    rows.getString("source_identity"),
                ).refined("edge source identity")
                val targetIdentity = CompilerSymbolIdentity.parse(
                    rows.getString("target_identity"),
                ).refined("edge target identity")
                val source = symbols[sourceIdentity]
                             ?: corrupt("edge source absent")
                val target = symbols[targetIdentity]
                             ?: corrupt("edge target absent")
                add(
                    TopologyEdge.fromBoundary(
                        enumValue<TopologyEdgeKind>(rows.getString("edge_kind")),
                        source,
                        target,
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

private fun SourceRootProvenance.sqliteName(): String = when (this) {
    SourceRootProvenance.Authored -> "AUTHORED"
    SourceRootProvenance.Generated -> "GENERATED"
    is SourceRootProvenance.Unknown -> "UNKNOWN_EXCLUDED"
}

private fun String.sourceRootProvenance(): SourceRootProvenance = when (this) {
    "AUTHORED" -> SourceRootProvenance.Authored
    "GENERATED" -> SourceRootProvenance.Generated
    "UNKNOWN_EXCLUDED" -> SourceRootProvenance.Unknown(ProvenanceFailure.ExcludedFromSourceModel)
    else -> corrupt("unknown source provenance")
}

private fun ExactDeclarationQualifiedIdentity.sqliteValue(): String? = when (this) {
    is ExactDeclarationQualifiedIdentity.Available -> value
    ExactDeclarationQualifiedIdentity.Unavailable -> null
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
