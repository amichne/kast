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
import kotlinx.serialization.SerializationException
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
        val sourcePath = workspaceSourcePath(normalized, path)

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
            assertEquals(listOf(sourcePath), snapshot.candidatePathsByIdentifier.getValue("NewName"))
            assertFalse(snapshot.packageByPath.containsKey(sourcePath))
            assertEquals(
                IndexedPackageEvidence.Unproven(IndexedPackageUnprovenReason.NOT_SCANNED),
                store.packageEvidenceForFile(path),
            )
            assertEquals(listOf("new.Import"), snapshot.importsByPath.getValue(sourcePath))
            assertEquals(listOf("new.wild"), snapshot.wildcardImportPackagesByPath.getValue(sourcePath))
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
    fun `pending updates parse operation path and payload before persistence`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val sourcePath = normalized.resolve("src/Pending.kt").toString()
        val outsidePath = normalized.parent.resolve("Outside.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            assertThrows(SerializationException::class.java) {
                store.appendPendingUpdate(
                    op = "unknown",
                    path = sourcePath,
                    payload = null,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.appendPendingUpdate(
                    op = "upsert_file",
                    path = outsidePath,
                    payload = "{}",
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.appendPendingUpdate(
                    op = "upsert_file",
                    path = sourcePath,
                    payload = null,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.appendPendingUpdate(
                    op = "remove_file",
                    path = sourcePath,
                    payload = "{}",
                )
            }
            assertThrows(SerializationException::class.java) {
                store.appendPendingUpdate(
                    op = "upsert_file",
                    path = sourcePath,
                    payload = "{",
                )
            }
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.createStatement().use { stmt ->
                listOf("pending_updates", "path_prefixes").forEach { table ->
                    stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(0, rs.getInt(1), table)
                    }
                }
            }
        }
    }

    @Test
    fun `full source index rebuild clears stale symbol references`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/Caller.kt").toString()
        val removedPath = normalized.resolve("src/Removed.kt").toString()
        val otherPath = normalized.resolve("src/Other.kt").toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate(callerPath, "Caller")),
                manifest = mapOf(callerPath to 1L),
            )
            store.upsertSymbolReference(
                sourcePath = callerPath,
                sourceOffset = 1,
                targetFqName = "lib.Removed",
                targetPath = removedPath,
                targetOffset = 1,
            )

            store.saveFullIndex(
                updates = listOf(fileUpdate(otherPath, "Other")),
                manifest = mapOf(otherPath to 2L),
            )

            assertTrue(store.referencesToSymbol("lib.Removed").isEmpty())
        }
    }

    @Test
    fun `removing a file clears inbound and outbound symbol references`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/Caller.kt").toString()
        val targetPath = normalized.resolve("src/Target.kt").toString()
        val otherPath = normalized.resolve("src/Other.kt").toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate(callerPath, "Caller"), fileUpdate(targetPath, "Target")),
                manifest = mapOf(callerPath to 1L, targetPath to 1L),
            )
            store.upsertSymbolReference(
                sourcePath = callerPath,
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = targetPath,
                targetOffset = 1,
            )
            store.upsertSymbolReference(
                sourcePath = targetPath,
                sourceOffset = 2,
                targetFqName = "demo.Other",
                targetPath = otherPath,
                targetOffset = 1,
            )

            store.removeFile(targetPath)

            assertTrue(store.referencesToSymbol("demo.Target").isEmpty())
            assertTrue(store.referencesFromFile(targetPath).isEmpty())
        }
    }

    @Test
    fun `reference-only cleanup does not replace source index manifest`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/Caller.kt").toString()
        val callerSourcePath = workspaceSourcePath(normalized, callerPath)
        val stalePath = normalized.resolve("src/Stale.kt").toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate(callerPath, "Caller")),
                manifest = mapOf(callerPath to 123L),
            )
            store.upsertSymbolReference(
                sourcePath = stalePath,
                sourceOffset = 1,
                targetFqName = "demo.Caller",
                targetPath = callerPath,
                targetOffset = 1,
            )

            store.removeReferencesOutsideSources(listOf(callerPath))

            assertEquals(mapOf(callerSourcePath.rawPath to 123L), store.loadManifest())
            assertTrue(store.referencesFromFile(stalePath).isEmpty())
        }
    }

    @Test
    fun `source index entry points reject Kotlin script paths`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val sourcePath = normalized.resolve("src/Caller.kt").toString()
        val scriptPath = normalized.resolve("build.gradle.kts").toString()
        val sourceProof = workspaceSourcePath(normalized, sourcePath)

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
            assertEquals(listOf(sourceProof), snapshot.candidatePathsByIdentifier.getValue("Caller"))
            assertFalse(snapshot.candidatePathsByIdentifier.containsKey("GradleScript"))
            assertEquals(mapOf(sourceProof.rawPath to 1L), store.loadManifest())
        }
    }

}
