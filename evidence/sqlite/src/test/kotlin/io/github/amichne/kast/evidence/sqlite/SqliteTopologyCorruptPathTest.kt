package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class SqliteTopologyCorruptPathTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `malformed persisted source root is corrupt`() {
        val generation = generation()
        val database = tempDir.resolve("malformed-root/topology.sqlite").also {
            Files.createDirectories(it.parent)
        }
        val published = assertInstanceOf(
            TopologyPublicationResult.Published::class.java,
            store(database).publish(generation),
        ).snapshot
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.prepareStatement(
                "UPDATE topology_file_v2 SET source_root = ?",
            ).use { statement ->
                statement.setString(1, "src" + 0.toChar() + "/main/kotlin")
                statement.executeUpdate()
            }
        }

        assertEquals(
            TopologySnapshotContentRead.Rejected(
                TopologySnapshotReadFailure.CORRUPT_SNAPSHOT,
            ),
            store(database).read(published),
        )
    }

    private fun generation(): CompleteTopologyGeneration {
        val sourceRoot = SourceRoot.admit(
            GradleSourceRootEvidence(
                "root.main",
                ".",
                ":",
                "main",
                "src/main/kotlin",
                SourceRootProvenance.Authored,
            ),
        ).refined()
        val candidate = WorkspaceCandidate(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            WorkspaceStateIdentity.parse("source-state").refined(),
        )
        val workspace = PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                candidate,
                WorkspaceEvidenceKind.entries.toSet(),
                listOf(sourceRoot),
            ).refined(),
            EvidenceGeneration.parse(7).refined(),
        )
        val source = TopologySourceFile.admit(
            workspace,
            sourceRoot,
            WorkspaceSourcePath.parse("src/main/kotlin/Source.kt").refined(),
            WorkspaceSourceContentHash.parse("a".repeat(64)).refined(),
        ).refined()
        return CompleteTopologyGeneration.admit(
            workspace,
            listOf(source),
            listOf(CompleteTopologyFile.admit(source, emptyList(), emptyList()).refined()),
        ).refined()
    }

    private fun store(path: Path): SqliteTopologySnapshotStore = when (
        val opened = SqliteTopologySnapshotStore.open(path)
    ) {
        is SqliteTopologySnapshotStoreOpening.Opened -> opened.store
        is SqliteTopologySnapshotStoreOpening.Rejected -> error(opened.failure)
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
