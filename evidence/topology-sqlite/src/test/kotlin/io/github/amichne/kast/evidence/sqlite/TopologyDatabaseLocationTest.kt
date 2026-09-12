package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.contract.TopologyDatabaseLocation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class TopologyDatabaseLocationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `exact location creates the topology store and survives reopening`() {
        val location = location(temporary.toRealPath().resolve("user-state"))

        assertInstanceOf<SqliteTopologySnapshotStoreOpening.Opened>(SqliteTopologySnapshotStore.open(location))
        assertInstanceOf<SqliteTopologySnapshotStoreOpening.Opened>(SqliteTopologySnapshotStore.open(location))
        assertTrue(Files.isRegularFile(Path.of(location.valueAtSqliteBoundary())))
    }

    @Test
    fun `non directory parent retains its finite admission failure`() {
        val location = location(temporary.toRealPath())
        val parent = Path.of(location.valueAtSqliteBoundary()).parent
        Files.createDirectories(parent.parent)
        Files.createFile(parent)

        assertEquals(
            SqliteTopologySnapshotStoreOpening.Rejected(SqliteTopologySnapshotStoreFailure.PARENT_NOT_DIRECTORY),
            SqliteTopologySnapshotStore.open(location),
        )
    }

    @Test
    fun `ancestor alias cannot acquire topology database authority`() {
        val root = temporary.toRealPath()
        val physical = Files.createDirectory(root.resolve("physical"))
        val alias = Files.createSymbolicLink(root.resolve("alias"), physical)

        assertEquals(
            SqliteTopologySnapshotStoreOpening.Rejected(SqliteTopologySnapshotStoreFailure.SYMLINK_NOT_ALLOWED),
            SqliteTopologySnapshotStore.open(location(alias)),
        )
        assertTrue(
            Files.walk(physical).use { paths -> paths.noneMatch { it.fileName.toString() == "topology.sqlite" } }
        )
    }

    private fun location(root: Path): TopologyDatabaseLocation =
        HostedWorkspaceStateLocation.locate(
                KastUserStateRoot.parse(root.toString()).refined(),
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            )
            .refined()
            .topologyDatabase

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
