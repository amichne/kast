package io.github.amichne.kast.evidence.sqlite.historical

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
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalFile
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalHeader
import io.github.amichne.kast.topology.contract.historical.HistoricalInventoryPage
import io.github.amichne.kast.topology.contract.historical.HistoricalModule
import io.github.amichne.kast.topology.contract.historical.HistoricalModuleId
import io.github.amichne.kast.topology.contract.historical.HistoricalName
import io.github.amichne.kast.topology.contract.historical.HistoricalPageRequest
import io.github.amichne.kast.topology.contract.historical.HistoricalPageSize
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalReadResult
import io.github.amichne.kast.topology.contract.historical.HistoricalRepositoryId
import io.github.amichne.kast.topology.contract.historical.HistoricalRevision
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput
import io.github.amichne.kast.topology.contract.historical.HistoricalSourceSet
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class SqliteHistoricalSnapshotStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `standalone copied artifact preserves explicit empty modules and location distinct declarations`() {
        val input = fixture()
        val snapshot = input.admit().value()
        val original = directory.toRealPath().resolve("original.sqlite")
        val publication = SqliteHistoricalSnapshotStore.publish(original, snapshot).value()
        println(publication.work)
        assertEquals(20L, publication.work.storedRows.value)
        assertEquals(2L, publication.work.verificationPasses.value)
        assertEquals(40L, publication.work.verificationRows.value)
        assertEquals(1L, publication.work.committedBatches.value)
        assertEquals(Files.size(original), publication.work.artifactBytes.value)
        val copied = directory.toRealPath().resolve("copied.sqlite")
        Files.copy(original, copied)
        Files.delete(original)

        SqliteHistoricalSnapshotStore.open(copied).value().use { opened ->
            assertEquals(snapshot.identity, opened.inventory.identity)
            assertEquals(listOf("api", "empty", "rootless"), opened.inventory.modules.map { it.id.value })
            assertEquals(input.header.revision, opened.inventory.header.revision)
            assertEquals(
                listOf("Api.kt", "Other.kt"),
                assertInstanceOf<HistoricalReadResult.Read<HistoricalInventoryPage<HistoricalDeclaration>>>(
                        opened.declarations(HistoricalPageRequest(HistoricalPageSize.parse(10).value()))
                    )
                    .value
                    .rows
                    .map { it.id.file.value },
            )
            assertEquals(
                1,
                assertInstanceOf<HistoricalReadResult.Read<HistoricalInventoryPage<HistoricalDeclaration>>>(
                        opened.declarations(HistoricalPageRequest(HistoricalPageSize.parse(10).value()))
                    )
                    .value
                    .rows
                    .map { it.id.compiler.value }
                    .distinct()
                    .size,
            )
            assertFalse(Files.exists(copied.resolveSibling("copied.sqlite-wal")))
            assertFalse(Files.exists(copied.resolveSibling("copied.sqlite-shm")))
        }
    }

    @Test
    fun `changed persisted signature is rejected without artifact replacement`() {
        val artifact = directory.toRealPath().resolve("corrupt.sqlite")
        SqliteHistoricalSnapshotStore.publish(artifact, fixture().admit().value()).value()
        mutate(artifact, "UPDATE historical_declaration SET signature = 'changed()'")
        val before = Files.readAllBytes(artifact)
        val failure =
            assertInstanceOf<Refinement.Rejected<HistoricalStorageFailure>>(
                SqliteHistoricalSnapshotStore.open(artifact)
            )
        assertEquals(HistoricalStorageFailure.CONTENT_REJECTED, failure.failure)
        assertArrayEquals(before, Files.readAllBytes(artifact))
    }

    @Test
    fun `reordered declaration ordinals are rejected despite unchanged logical facts`() {
        val artifact = directory.toRealPath().resolve("reordered.sqlite")
        SqliteHistoricalSnapshotStore.publish(artifact, fixture().admit().value()).value()
        mutate(artifact, "UPDATE historical_declaration SET ordinal = ordinal + 10")
        mutate(artifact, "UPDATE historical_declaration SET ordinal = 11 - ordinal")
        val rejected =
            assertInstanceOf<Refinement.Rejected<HistoricalStorageFailure>>(
                SqliteHistoricalSnapshotStore.open(artifact)
            )
        assertEquals(HistoricalStorageFailure.CONTENT_REJECTED, rejected.failure)
    }

    @Test
    fun `file ordinal gaps are rejected despite unchanged logical facts`() {
        val artifact = directory.toRealPath().resolve("gap.sqlite")
        SqliteHistoricalSnapshotStore.publish(artifact, fixture().admit().value()).value()
        mutate(artifact, "UPDATE historical_file SET ordinal = ordinal + 10")
        val rejected =
            assertInstanceOf<Refinement.Rejected<HistoricalStorageFailure>>(
                SqliteHistoricalSnapshotStore.open(artifact)
            )
        assertEquals(HistoricalStorageFailure.CONTENT_REJECTED, rejected.failure)
    }

    @Test
    fun `unsupported schema is rejected without initialization or replacement`() {
        val artifact = directory.toRealPath().resolve("future.sqlite")
        SqliteHistoricalSnapshotStore.publish(artifact, fixture().admit().value()).value()
        mutate(artifact, "PRAGMA user_version = 999")
        val before = Files.readAllBytes(artifact)
        val failure =
            assertInstanceOf<Refinement.Rejected<HistoricalStorageFailure>>(
                SqliteHistoricalSnapshotStore.open(artifact)
            )
        assertEquals(HistoricalStorageFailure.SCHEMA_REJECTED, failure.failure)
        assertArrayEquals(before, Files.readAllBytes(artifact))
    }

    @Test
    fun `failure before publication preserves the previous artifact`() {
        val artifact = directory.toRealPath().resolve("publication.sqlite")
        val prior = fixture().admit().value()
        SqliteHistoricalSnapshotStore.publish(artifact, prior).value()
        val bytes = Files.readAllBytes(artifact)
        val secondInput = fixture().let { it.copy(header = it.header.copy(producer = name("second-producer"))) }
        val result =
            SqliteHistoricalSnapshotStore.publish(artifact, secondInput.admit().value()) { point ->
                if (point == HistoricalSqliteFaultPoint.BEFORE_PUBLICATION)
                    throw java.io.IOException("controlled publication failure")
            }
        assertInstanceOf<Refinement.Rejected<HistoricalStorageFailure>>(result)
        assertArrayEquals(bytes, Files.readAllBytes(artifact))
        SqliteHistoricalSnapshotStore.open(artifact).value().use { opened ->
            assertEquals(prior.identity, opened.inventory.identity)
        }
    }

    @Test
    fun `sidecar state is rejected before immutable admission`() {
        val artifact = directory.toRealPath().resolve("sidecar.sqlite")
        SqliteHistoricalSnapshotStore.publish(artifact, fixture().admit().value()).value()
        Files.writeString(artifact.resolveSibling("sidecar.sqlite-wal"), "uncommitted state")
        val before = Files.readAllBytes(artifact)
        val rejected =
            assertInstanceOf<Refinement.Rejected<HistoricalStorageFailure>>(
                SqliteHistoricalSnapshotStore.open(artifact)
            )
        assertEquals(HistoricalStorageFailure.CONTENT_REJECTED, rejected.failure)
        assertArrayEquals(before, Files.readAllBytes(artifact))
    }

    @Test
    fun `opened reader remains pinned when another artifact replaces the path`() {
        val artifact = directory.toRealPath().resolve("replacement.sqlite")
        val original = fixture().admit().value()
        SqliteHistoricalSnapshotStore.publish(artifact, original).value()
        SqliteHistoricalSnapshotStore.open(artifact).value().use { opened ->
            val changed =
                fixture()
                    .let { input ->
                        input.copy(declarations = input.declarations.map { it.copy(signature = name("changed()")) })
                    }
                    .admit()
                    .value()
            val published = SqliteHistoricalSnapshotStore.publish(artifact, changed).value()
            assertEquals(3L, published.work.verificationPasses.value)
            assertEquals(60L, published.work.verificationRows.value)
            assertEquals(original.identity, opened.inventory.identity)
            val rows =
                assertInstanceOf<HistoricalReadResult.Read<HistoricalInventoryPage<HistoricalDeclaration>>>(
                        opened.declarations(HistoricalPageRequest(HistoricalPageSize.parse(10).value()))
                    )
                    .value
                    .rows
            assertEquals(listOf("api()", "api()"), rows.map { it.signature.value })
            SqliteHistoricalSnapshotStore.open(artifact).value().use { replacement ->
                assertEquals(changed.identity, replacement.inventory.identity)
            }
        }
    }

    @Test
    fun `cancellation before commit preserves previous publication and removes private candidate`() {
        val artifact = directory.toRealPath().resolve("cancelled.sqlite")
        val original = fixture().admit().value()
        SqliteHistoricalSnapshotStore.publish(artifact, original).value()
        val before = Files.readAllBytes(artifact)
        org.junit.jupiter.api.assertThrows<java.util.concurrent.CancellationException> {
            SqliteHistoricalSnapshotStore.publish(artifact, original) { point ->
                if (point == HistoricalSqliteFaultPoint.BEFORE_COMMIT)
                    throw java.util.concurrent.CancellationException("controlled cancellation")
            }
        }
        assertArrayEquals(before, Files.readAllBytes(artifact))
        Files.list(directory.toRealPath()).use { paths -> assertEquals(listOf(artifact), paths.toList()) }
    }

    private fun fixture(): HistoricalSnapshotInput {
        val api = HistoricalModuleId.parse("api").value()
        val empty = HistoricalModuleId.parse("empty").value()
        val rootless = HistoricalModuleId.parse("rootless").value()
        val modules =
            listOf(api, empty, rootless).map {
                HistoricalModule(
                    it,
                    name(":"),
                    name(it.value),
                    if (it == rootless) HistoricalSourceSet.Rootless else HistoricalSourceSet.Named(name("main")),
                )
            }
        val files = fixtureFiles(api)
        return HistoricalSnapshotInput(
            fixtureHeader(),
            modules,
            emptyList(),
            files,
            files.map {
                HistoricalDeclaration(
                    HistoricalDeclarationId(
                        CompilerSymbolIdentity.parse("same-id").value(),
                        it.path,
                        ExactDeclarationTextRange.parse(0, 12).value(),
                    ),
                    name("api"),
                    name("api()"),
                    CompilerSymbolKind.FUNCTION,
                )
            },
            emptyList(),
            modules.flatMap { module ->
                HistoricalFactFamily.entries.map { family ->
                    HistoricalCoverage(
                        module.id,
                        family,
                        if (family == HistoricalFactFamily.REFERENCES) HistoricalCoverageStatus.NotCollected
                        else HistoricalCoverageStatus.Complete,
                    )
                }
            },
        )
    }

    private fun fixtureFiles(api: HistoricalModuleId): List<HistoricalFile> =
        listOf("Api.kt", "Other.kt").map {
            HistoricalFile(
                path(it),
                WorkspaceSourceContentHash.parse("a".repeat(64)).value(),
                api,
                HistoricalFileProvenance.Tracked(GitObjectId.parse("b".repeat(40)).value()),
            )
        }

    private fun fixtureHeader(): HistoricalHeader =
        HistoricalHeader(
            HistoricalRevision(
                HistoricalRepositoryId.parse("fixture:repository").value(),
                GitObjectId.parse("1".repeat(40)).value(),
                GitObjectId.parse("2".repeat(40)).value(),
            ),
            HistoricalContext(digest("3"), digest("4"), digest("5")),
            HistoricalProfile.STRUCTURE_V1,
            name("fixture-1"),
        )

    private fun mutate(path: Path, sql: String) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun name(value: String) = HistoricalName.parse(value).value()

    private fun path(value: String) = WorkspaceSourcePath.parse(value).value()

    private fun digest(character: String) = HistoricalDigest.parse(character.repeat(64)).value()

    private fun <T, F> Refinement<T, F>.value(): T =
        assertInstanceOf<Refinement.Refined<T>>(this, "actual: $this").value
}
