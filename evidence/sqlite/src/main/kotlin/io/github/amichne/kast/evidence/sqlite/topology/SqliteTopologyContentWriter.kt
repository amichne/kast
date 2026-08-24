package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyNodeIdentity
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import java.sql.Connection

internal fun Connection.insertTopologyContent(
    snapshotId: SqliteTopologySnapshotId,
    generation: CompleteTopologyGeneration,
) {
    generation.files.forEach { complete -> insertFile(snapshotId, complete.file) }
    val symbolIds: Map<TopologyNodeIdentity, SqliteTopologySymbolId> =
        generation.symbols.associate { symbol ->
            symbol.nodeIdentity to insertSymbol(snapshotId, symbol)
        }
    generation.edges.forEach { edge ->
        insertEdge(
            snapshotId,
            edge,
            symbolIds.getValue(edge.source.nodeIdentity),
            symbolIds.getValue(edge.target.nodeIdentity),
        )
    }
}

private fun Connection.insertFile(snapshotId: SqliteTopologySnapshotId, file: TopologySourceFile) {
    prepareStatement(
        """INSERT INTO topology_file_v2(
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
): SqliteTopologySymbolId {
    prepareStatement(
        """INSERT INTO topology_symbol_v2(
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
    return createStatement().use { statement ->
        statement.executeQuery("SELECT last_insert_rowid()").use { rows ->
            if (!rows.next()) corrupt("topology symbol insert returned no identity")
            SqliteTopologySymbolId.restore(rows.getLong(1)).refined("symbol identity")
        }
    }
}

private fun Connection.insertEdge(
    snapshotId: SqliteTopologySnapshotId,
    edge: TopologyEdge,
    sourceId: SqliteTopologySymbolId,
    targetId: SqliteTopologySymbolId,
) {
    prepareStatement(
        """INSERT INTO topology_edge_v2(
               snapshot_id, edge_kind, source_symbol_id, target_symbol_id,
               occurrence_file_path, start_offset, end_offset
           ) VALUES (?, ?, ?, ?, ?, ?, ?)""",
    ).use { statement ->
        statement.setLong(1, snapshotId.value)
        statement.setString(2, edge.kind.name)
        statement.setLong(3, sourceId.value)
        statement.setLong(4, targetId.value)
        statement.setString(5, edge.source.file.path.value)
        statement.setInt(6, edge.occurrence.startInclusive)
        statement.setInt(7, edge.occurrence.endExclusive)
        if (statement.executeUpdate() != 1) corrupt("topology edge insert changed no row")
    }
}

private fun SourceRootProvenance.sqliteName(): String = when (this) {
    SourceRootProvenance.Authored -> "AUTHORED"
    SourceRootProvenance.Generated -> "GENERATED"
    is SourceRootProvenance.Unknown -> "UNKNOWN_EXCLUDED"
}

private fun ExactDeclarationQualifiedIdentity.sqliteValue(): String? = when (this) {
    is ExactDeclarationQualifiedIdentity.Available -> value
    ExactDeclarationQualifiedIdentity.Unavailable -> null
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(field: String): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> corrupt("invalid $field: $failure")
}

private fun corrupt(message: String): Nothing = throw SqliteTopologyCorruption(message)
