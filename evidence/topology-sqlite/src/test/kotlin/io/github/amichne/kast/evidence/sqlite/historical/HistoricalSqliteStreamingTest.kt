package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.evidence.sqlite.historical.cli.HistoricalUsageOracle
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class HistoricalSqliteStreamingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `offline admission holds at most two fact rows while preserving full digest and counts`() {
        val artifact = directory.toRealPath().resolve("stream.sqlite")
        val snapshot = HistoricalUsageOracle.snapshot()
        SqliteHistoricalSnapshotStore.publish(artifact, snapshot).value()
        SqliteHistoricalSnapshotStore.open(artifact).value().use { reader ->
            println(reader.admissionRetention)
            println("joined endpoint identities: ${reader.admissionEndpointIdentifiers.value}")
            assertEquals(7L, reader.admissionEndpointIdentifiers.value)
            assertEquals(snapshot.identity, reader.inventory.identity)
            assertEquals(4L, reader.inventory.counts.files.value)
            assertEquals(6L, reader.inventory.counts.declarations.value)
            assertEquals(4L, reader.inventory.counts.occurrences.value)
            assertEquals(34L, reader.admissionRetention.metadataRows.value)
            assertTrue(
                reader.admissionRetention.maximumFactRows.value <= 2,
                "actual retained logical facts: ${reader.admissionRetention.maximumFactRows.value}",
            )
        }
    }

    @Test
    fun `Unicode file ordering follows Kotlin canonical order instead of SQLite text collation`() {
        val snapshot = unicodeSnapshot()
        val artifact = directory.toRealPath().resolve("unicode.sqlite")
        SqliteHistoricalSnapshotStore.publish(artifact, snapshot).value()
        SqliteHistoricalSnapshotStore.open(artifact).value().use { reader ->
            val page =
                assertInstanceOf<io.github.amichne.kast.topology.contract.historical.HistoricalReadResult.Read<*>>(
                        reader.files(
                            io.github.amichne.kast.topology.contract.historical.HistoricalPageRequest(
                                io.github.amichne.kast.topology.contract.historical.HistoricalPageSize.parse(10).value()
                            )
                        )
                    )
                    .value
            val files =
                assertInstanceOf<io.github.amichne.kast.topology.contract.historical.HistoricalInventoryPage<*>>(page)
            assertEquals(
                listOf("a.kt", "z.kt", "\uD800\uDC00.kt", "\uE000.kt"),
                files.rows.map {
                    assertInstanceOf<io.github.amichne.kast.topology.contract.historical.HistoricalFile>(it).path.value
                },
            )
            assertEquals(snapshot.identity, reader.inventory.identity)
        }
    }

    @Test
    fun `self reference counts both actual joined endpoint identifier parses`() {
        val original = HistoricalUsageOracle.snapshot()
        val reference = original.occurrences[1]
        val self =
            reference.copy(
                sourceFile = reference.target.file,
                owner =
                    io.github.amichne.kast.topology.contract.historical.HistoricalOccurrenceOwner.Declaration(
                        reference.target
                    ),
            )
        val snapshot =
            io.github.amichne.kast.topology.contract.historical
                .HistoricalSnapshotInput(
                    original.header,
                    original.modules,
                    original.dependencies,
                    original.files,
                    original.declarations,
                    listOf(self),
                    original.coverage,
                )
                .admit()
                .value()
        val artifact = directory.toRealPath().resolve("self.sqlite")
        SqliteHistoricalSnapshotStore.publish(artifact, snapshot).value()
        SqliteHistoricalSnapshotStore.open(artifact).value().use { reader ->
            assertEquals(snapshot.identity, reader.inventory.identity)
            assertEquals(1L, reader.inventory.counts.occurrences.value)
            assertEquals(2L, reader.admissionEndpointIdentifiers.value)
        }
    }

    private fun unicodeSnapshot(): io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot {
        val original =
            HistoricalUsageOracle.snapshot(
                io.github.amichne.kast.topology.contract.historical.HistoricalProfile.STRUCTURE_V1
            )
        val names = listOf("\uE000.kt", "\uD800\uDC00.kt", "z.kt", "a.kt")
        val paths =
            original.files
                .mapIndexed { index, file ->
                    file.path to
                        io.github.amichne.kast.workspace.contract.WorkspaceSourcePath.parse(names[index]).value()
                }
                .toMap()
        val input =
            io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput(
                original.header,
                original.modules,
                original.dependencies,
                original.files.map { it.copy(path = paths.getValue(it.path)) },
                original.declarations.map {
                    it.copy(
                        id = it.id.copy(file = paths.getValue(it.id.file)),
                        signature =
                            io.github.amichne.kast.topology.contract.historical.HistoricalName.parse(
                                    "signature:\uD800\uDC00:valid"
                                )
                                .value(),
                    )
                },
                emptyList(),
                original.coverage,
            )
        return input.admit().value()
    }

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
