package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.evidence.sqlite.historical.cli.HistoricalUsageOracle
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.topology.contract.historical.HistoricalName
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalReadResult
import io.github.amichne.kast.topology.contract.historical.HistoricalSelectedDeclaration
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class HistoricalSqliteReaderIsolationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `admitted reader preserves bytes after same inode mutation beyond page cache`() {
        val artifact = directory.toRealPath().resolve("in-place.sqlite")
        val snapshot = largeSnapshot()
        SqliteHistoricalSnapshotStore.publish(artifact, snapshot).value()
        assertTrue(Files.size(artifact) > 8L * 1024 * 1024)
        val inode = Files.readAttributes(artifact, BasicFileAttributes::class.java).fileKey()
        assertNotNull(inode)
        SqliteHistoricalSnapshotStore.open(artifact).value().use { reader ->
            replaceFirstSignature(artifact)
            assertEquals(inode, Files.readAttributes(artifact, BasicFileAttributes::class.java).fileKey())
            assertEquals("external-mutation()", firstSignature(artifact))
            val selected =
                assertInstanceOf<HistoricalReadResult.Read<HistoricalSelectedDeclaration>>(
                        reader.select(snapshot.declarations.first().id)
                    )
                    .value
            assertEquals(snapshot.identity, reader.inventory.identity)
            assertTrue(
                snapshot.declarations.first() == selected.declaration,
                "Reader returned bytes changed after admission: ${selected.declaration.signature.value.take(24)}",
            )
        }
    }

    @Test
    fun `reader owns copied bytes until close and reports exact copy work`() {
        val artifact = directory.toRealPath().resolve("owned.sqlite")
        val copies = Files.createDirectory(directory.toRealPath().resolve("reader-copies"))
        val snapshot = HistoricalUsageOracle.snapshot()
        SqliteHistoricalSnapshotStore.publish(artifact, snapshot).value()
        val bytes = Files.size(artifact)
        SqliteHistoricalSnapshotStore.open(artifact, copies).value().use { reader ->
            assertEquals(bytes, reader.openWork.artifactBytes.value)
            assertEquals(bytes, reader.openWork.copiedBytesRead.value)
            assertEquals(bytes, reader.openWork.copiedBytesWritten.value)
            Files.list(copies).use { paths -> assertEquals(1L, paths.count()) }
            assertEquals(snapshot.identity, reader.inventory.identity)
        }
        Files.list(copies).use { paths -> assertEquals(0L, paths.count()) }
        assertEquals(bytes, Files.size(artifact))
    }

    @Test
    fun `failed admission removes its private copy and preserves source artifact`() {
        val artifact = directory.toRealPath().resolve("rejected.sqlite")
        val copies = Files.createDirectory(directory.toRealPath().resolve("rejected-copies"))
        SqliteHistoricalSnapshotStore.publish(artifact, HistoricalUsageOracle.snapshot()).value()
        replaceFirstSignature(artifact)
        val bytes = Files.readAllBytes(artifact)
        val outcome = SqliteHistoricalSnapshotStore.open(artifact, copies)
        assertEquals(
            HistoricalStorageFailure.CONTENT_REJECTED,
            assertInstanceOf<Refinement.Rejected<HistoricalStorageFailure>>(outcome).failure,
        )
        Files.list(copies).use { paths -> assertEquals(0L, paths.count()) }
        org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, Files.readAllBytes(artifact))
    }

    private fun largeSnapshot(): HistoricalSnapshot {
        val original = HistoricalUsageOracle.snapshot(HistoricalProfile.STRUCTURE_V1)
        val template = original.declarations.first()
        val signature = HistoricalName.parse("original()" + "x".repeat(8192)).value()
        val declarations =
            (0 until 1024).map { ordinal ->
                template.copy(
                    id =
                        template.id.copy(
                            compiler =
                                CompilerSymbolIdentity.parse("isolation.${ordinal.toString().padStart(4, '0')}()")
                                    .value(),
                            range = ExactDeclarationTextRange.parse(ordinal * 2, ordinal * 2 + 1).value(),
                        ),
                    signature = signature,
                )
            }
        return HistoricalSnapshotInput(
                original.header,
                original.modules,
                original.dependencies,
                original.files,
                declarations,
                emptyList(),
                original.coverage,
            )
            .admit()
            .value()
    }

    private fun replaceFirstSignature(artifact: Path) {
        DriverManager.getConnection("jdbc:sqlite:$artifact").use { connection ->
            connection.prepareStatement("UPDATE historical_declaration SET signature = ? WHERE ordinal = 0").use {
                statement ->
                statement.setString(1, "external-mutation()")
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun firstSignature(artifact: Path): String =
        DriverManager.getConnection("jdbc:sqlite:$artifact").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT signature FROM historical_declaration WHERE ordinal = 0").use { rows ->
                    assertTrue(rows.next())
                    rows.getString(1)
                }
            }
        }

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
