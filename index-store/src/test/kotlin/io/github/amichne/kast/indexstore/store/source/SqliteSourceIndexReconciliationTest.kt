package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleSourceSetIdentity
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.GradleProjectPath
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.IndexedPackageEvidence
import io.github.amichne.kast.indexstore.api.index.IndexedPackageUnprovenReason
import io.github.amichne.kast.indexstore.api.index.WorkspaceRelativeGradleBuildRoot
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SourceIndexPageReadObserver
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.kastCacheDirectory
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SqliteSourceIndexReconciliationTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `pending update reconciliation applies only latest file state and marks prior rows applied`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val path = normalized.resolve("src/Pending.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.appendPendingUpdate(
                op = "upsert_file",
                path = path,
                payload = """{"identifiers":["OldName"],"packageName":"old.pkg","moduleName":":old","imports":["old.Import"],"wildcardImports":[]}""",
                sessionId = "session-1",
            )
            store.appendPendingUpdate(
                op = "upsert_file",
                path = path,
                payload = """{"identifiers":["NewName"],"packageName":"new.pkg","moduleName":":new","imports":["new.Import"],"wildcardImports":["new.wild"]}""",
                sessionId = "session-2",
            )

            assertEquals(1, store.reconcilePendingUpdates())

            val snapshot = store.loadSourceIndexSnapshot()
            assertFalse(snapshot.candidatePathsByIdentifier.containsKey("OldName"))
            assertEquals(listOf(path), snapshot.candidatePathsByIdentifier.getValue("NewName"))
            assertFalse(snapshot.packageByPath.containsKey(path))
            assertEquals(
                IndexedPackageEvidence.Unproven(IndexedPackageUnprovenReason.NOT_SCANNED),
                store.packageEvidenceForFile(path),
            )
            assertEquals(listOf("new.Import"), snapshot.importsByPath.getValue(path))
            assertEquals(listOf("new.wild"), snapshot.wildcardImportPackagesByPath.getValue(path))
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM pending_updates WHERE applied = 1")
                assertTrue(rs.next())
                assertEquals(2, rs.getInt(1))
            }
        }
    }

    @Test
    fun `full source index rebuild clears stale symbol references`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate("/src/Caller.kt", "Caller")),
                manifest = mapOf("/src/Caller.kt" to 1L),
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 1,
                targetFqName = "lib.Removed",
                targetPath = "/src/Removed.kt",
                targetOffset = 1,
            )

            store.saveFullIndex(
                updates = listOf(fileUpdate("/src/Other.kt", "Other")),
                manifest = mapOf("/src/Other.kt" to 2L),
            )

            assertTrue(store.referencesToSymbol("lib.Removed").isEmpty())
        }
    }

    @Test
    fun `removing a file clears inbound and outbound symbol references`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate("/src/Caller.kt", "Caller"), fileUpdate("/src/Target.kt", "Target")),
                manifest = mapOf("/src/Caller.kt" to 1L, "/src/Target.kt" to 1L),
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = "/src/Target.kt",
                targetOffset = 1,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Target.kt",
                sourceOffset = 2,
                targetFqName = "demo.Other",
                targetPath = "/src/Other.kt",
                targetOffset = 1,
            )

            store.removeFile("/src/Target.kt")

            assertTrue(store.referencesToSymbol("demo.Target").isEmpty())
            assertTrue(store.referencesFromFile("/src/Target.kt").isEmpty())
        }
    }

    @Test
    fun `reference-only cleanup does not replace source index manifest`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate("/src/Caller.kt", "Caller")),
                manifest = mapOf("/src/Caller.kt" to 123L),
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Stale.kt",
                sourceOffset = 1,
                targetFqName = "demo.Caller",
                targetPath = "/src/Caller.kt",
                targetOffset = 1,
            )

            store.removeReferencesOutsideSources(listOf("/src/Caller.kt"))

            assertEquals(mapOf("/src/Caller.kt" to 123L), store.loadManifest())
            assertTrue(store.referencesFromFile("/src/Stale.kt").isEmpty())
        }
    }

    @Test
    fun `source index entry points reject Kotlin script paths`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val sourcePath = "/src/Caller.kt"
        val scriptPath = "/build.gradle.kts"

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    fileUpdate(sourcePath, "Caller"),
                    fileUpdate(scriptPath, "GradleScript"),
                ),
                manifest = mapOf(sourcePath to 1L, scriptPath to 2L),
            )

            val snapshot = store.loadSourceIndexSnapshot()
            assertEquals(listOf(sourcePath), snapshot.candidatePathsByIdentifier.getValue("Caller"))
            assertFalse(snapshot.candidatePathsByIdentifier.containsKey("GradleScript"))
            assertEquals(mapOf(sourcePath to 1L), store.loadManifest())
        }
    }

}
