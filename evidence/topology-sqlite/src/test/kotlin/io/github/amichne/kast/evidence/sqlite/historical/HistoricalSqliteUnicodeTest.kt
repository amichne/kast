package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.evidence.sqlite.historical.cli.OfflineResponse
import io.github.amichne.kast.evidence.sqlite.historical.cli.historicalOfflineCommand
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.topology.contract.historical.GitObjectId
import io.github.amichne.kast.topology.contract.historical.HistoricalContext
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverage
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclaration
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationId
import io.github.amichne.kast.topology.contract.historical.HistoricalDependency
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalFile
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalHeader
import io.github.amichne.kast.topology.contract.historical.HistoricalModule
import io.github.amichne.kast.topology.contract.historical.HistoricalModuleId
import io.github.amichne.kast.topology.contract.historical.HistoricalName
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrence
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrenceOwner
import io.github.amichne.kast.topology.contract.historical.HistoricalPageRequest
import io.github.amichne.kast.topology.contract.historical.HistoricalPageSize
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalReadResult
import io.github.amichne.kast.topology.contract.historical.HistoricalReferenceContext
import io.github.amichne.kast.topology.contract.historical.HistoricalReferenceKind
import io.github.amichne.kast.topology.contract.historical.HistoricalRepositoryId
import io.github.amichne.kast.topology.contract.historical.HistoricalRevision
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput
import io.github.amichne.kast.topology.contract.historical.HistoricalSourceSet
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageQuery
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class HistoricalSqliteUnicodeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `supplementary text survives standalone publication exact selection and offline occurrence reads`() {
        val input = fixture()
        val snapshot = input.admit().value()
        val original = directory.toRealPath().resolve("original.sqlite")
        SqliteHistoricalSnapshotStore.publish(original, snapshot).value()
        val copied = directory.toRealPath().resolve("copied.sqlite")
        Files.copy(original, copied)
        Files.delete(original)

        SqliteHistoricalSnapshotStore.open(copied).value().use { reader ->
            assertEquals(snapshot.identity, reader.inventory.identity)
            assertEquals("producer-\uD801\uDC00", reader.inventory.header.producer.value)
            assertEquals(snapshot.modules, reader.inventory.modules)
            assertEquals(snapshot.files, reader.files(page()).read().rows)
            assertEquals(snapshot.declarations, reader.declarations(page()).read().rows)
            val selected = reader.select(input.declarations.first().id).read()
            val query =
                HistoricalUsageQuery.admit(
                        reader.inventory,
                        setOf(module("feature-\uD801\uDC00")),
                        setOf(selected.reference),
                        HistoricalPageSize.parse(10).value(),
                    )
                    .value()
            val rows = reader.usage(query).read().rows
            assertEquals(1, rows.size)
            assertEquals(input.occurrences.single(), rows.single().occurrence)
            assertEquals(12, rows.single().occurrence.range.startInclusive)
            assertEquals(14, rows.single().occurrence.range.endExclusive)
            assertEquals("\uD801\uDC00", SOURCE.substring(12, 14))
            assertEquals("fun \uD801\uDC00() = Unit", TARGET.substring(0, 15))
        }
        val offline =
            assertInstanceOf<OfflineResponse.Declarations>(
                historicalOfflineCommand(listOf("declarations", copied.toString()))
            )
        assertEquals(snapshot.identity.value, offline.manifest.snapshot)
        assertEquals("sample.\uD801\uDC00()", offline.declarations.first().identity.compiler)
        assertEquals("sample.\uD801\uDC00():Unit", offline.declarations.first().signature)
    }

    @Test
    fun `adjacent valid supplementary producer characters retain distinct historical identities`() {
        val first = fixture().admit().value()
        val input = fixture()
        val second = input.copy(header = input.header.copy(producer = name("producer-\uD801\uDC01"))).admit().value()
        assertNotEquals(first.identity, second.identity)
        listOf(first, second).forEachIndexed { index, snapshot ->
            val artifact = directory.toRealPath().resolve("distinct-$index.sqlite")
            SqliteHistoricalSnapshotStore.publish(artifact, snapshot).value()
            SqliteHistoricalSnapshotStore.open(artifact).value().use { reader ->
                assertEquals(snapshot.identity, reader.inventory.identity)
                assertEquals(snapshot.header.producer, reader.inventory.header.producer)
            }
        }
    }

    @Test
    fun `malformed stored UTF8 cannot replace decode into an unchanged compiler identity`() {
        val input = fixture()
        val target =
            input.declarations.first().id.copy(compiler = CompilerSymbolIdentity.parse("symbol:\uFFFD").value())
        val snapshot =
            input
                .copy(
                    declarations = listOf(input.declarations.first().copy(id = target), input.declarations.last()),
                    occurrences = listOf(input.occurrences.single().copy(target = target)),
                )
                .admit()
                .value()
        val artifact = directory.toRealPath().resolve("malformed-utf8.sqlite")
        SqliteHistoricalSnapshotStore.publish(artifact, snapshot).value()
        DriverManager.getConnection("jdbc:sqlite:$artifact").use { connection ->
            connection.createStatement().use { statement ->
                assertEquals(
                    1,
                    statement.executeUpdate(
                        "UPDATE historical_declaration SET compiler = CAST(X'73796D626F6C3AEDA080' AS TEXT) " +
                            "WHERE ordinal = 0"
                    ),
                )
                statement
                    .executeQuery(
                        "SELECT typeof(compiler), hex(compiler), compiler FROM historical_declaration WHERE ordinal = 0"
                    )
                    .use { rows ->
                        assertEquals(true, rows.next())
                        assertEquals("text", rows.getString(1))
                        assertEquals("73796D626F6C3AEDA080", rows.getString(2))
                        assertEquals("symbol:\uFFFD", rows.getString(3))
                    }
            }
        }
        val before = Files.readAllBytes(artifact)
        val rejected =
            assertInstanceOf<Refinement.Rejected<HistoricalStorageFailure>>(
                SqliteHistoricalSnapshotStore.open(artifact)
            )
        assertEquals(HistoricalStorageFailure.CONTENT_REJECTED, rejected.failure)
        assertArrayEquals(before, Files.readAllBytes(artifact))
    }

    private fun fixture(): HistoricalSnapshotInput {
        val modules =
            listOf("api-\uD801\uDC00", "feature-\uD801\uDC00").map {
                HistoricalModule(
                    module(it),
                    name(":\uD801\uDC00"),
                    name(it),
                    HistoricalSourceSet.Named(name("main-\uD801\uDC00")),
                )
            }
        val files =
            listOf("api/\uD801\uDC00.kt", "feature/\uD801\uDC00.kt").mapIndexed { index, file ->
                HistoricalFile(
                    path(file),
                    WorkspaceSourceContentHash.parse("a".repeat(64)).value(),
                    modules[index].id,
                    HistoricalFileProvenance.Generated(name("generator-\uD801\uDC00")),
                )
            }
        val declarations =
            listOf(
                declaration("sample.\uD801\uDC00()", files.first(), 15, "\uD801\uDC00", "sample.\uD801\uDC00():Unit"),
                declaration("feature.use()", files.last(), 18, "use", "feature.use():Unit"),
            )
        return HistoricalSnapshotInput(
            header(),
            modules,
            listOf(HistoricalDependency(modules.last().id, modules.first().id)),
            files,
            declarations,
            listOf(
                HistoricalOccurrence(
                    files.last().path,
                    ExactDeclarationTextRange.parse(12, 14).value(),
                    declarations.first().id,
                    HistoricalOccurrenceOwner.Declaration(declarations.last().id),
                    HistoricalReferenceKind.CALL,
                    HistoricalReferenceContext.CODE,
                )
            ),
            modules.flatMap { owner ->
                HistoricalFactFamily.entries.map { family ->
                    HistoricalCoverage(owner.id, family, HistoricalCoverageStatus.Complete)
                }
            },
        )
    }

    private fun declaration(compiler: String, file: HistoricalFile, end: Int, name: String, signature: String) =
        HistoricalDeclaration(
            HistoricalDeclarationId(
                CompilerSymbolIdentity.parse(compiler).value(),
                file.path,
                ExactDeclarationTextRange.parse(0, end).value(),
            ),
            name(name),
            name(signature),
            CompilerSymbolKind.FUNCTION,
        )

    private fun header() =
        HistoricalHeader(
            HistoricalRevision(
                HistoricalRepositoryId.parse("fixture:unicode").value(),
                GitObjectId.parse("1".repeat(40)).value(),
                GitObjectId.parse("2".repeat(40)).value(),
            ),
            HistoricalContext(digest("3"), digest("4"), digest("5")),
            HistoricalProfile.REFERENCES_V1,
            name("producer-\uD801\uDC00"),
        )

    private fun name(raw: String) = HistoricalName.parse(raw).value()

    private fun module(raw: String) = HistoricalModuleId.parse(raw).value()

    private fun path(raw: String) = WorkspaceSourcePath.parse(raw).value()

    private fun digest(raw: String) = HistoricalDigest.parse(raw.repeat(64)).value()

    private fun page() = HistoricalPageRequest(HistoricalPageSize.parse(10).value())

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value

    private fun <T> HistoricalReadResult<T>.read(): T = assertInstanceOf<HistoricalReadResult.Read<T>>(this).value

    private companion object {
        const val TARGET = "fun \uD801\uDC00() = Unit\n"
        const val SOURCE = "fun use() { \uD801\uDC00() }\n"
    }
}
