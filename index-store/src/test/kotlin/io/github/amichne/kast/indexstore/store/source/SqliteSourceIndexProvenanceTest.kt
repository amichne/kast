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

class SqliteSourceIndexProvenanceTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `snapshot export carries complete stable progress and pending evidence`() {
        val tree = GitObjectId.parse("a".repeat(40))
        val producer = ProducerVersion.parse("test-producer")
        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.initializeModuleProgress(mapOf("main" to 1))
            store.markModuleComplete("main", 1)

            val exact = store.exportSnapshotDatabase(workspaceRoot.resolve("exact.db"), tree, producer)
            assertTrue(exact.proves(key(tree, producer)))

            store.appendPendingUpdate("remove_file", workspaceRoot.resolve("A.kt").toString(), null)
            val pending = store.exportSnapshotDatabase(workspaceRoot.resolve("pending.db"), tree, producer)
            assertFalse(pending.proves(key(tree, producer)))
            assertEquals(1, pending.pendingCount)
        }
    }

    @Test
    fun `typed Gradle and package provenance round-trips and advances generation on every transition`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val path = normalized.resolve("quality/kotlin/Feature.kt").toString()
        val rootProject = gradleProject(buildRoot = ".", projectPath = ":app")
        val includedProject = gradleProject(buildRoot = "included", projectPath = ":app")
        val integrationTest = BuildQualifiedGradleSourceSetIdentity(
            project = includedProject,
            sourceSet = GradleSourceSetName.parse("integrationTest"),
        )

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            val initialGeneration = store.readGeneration()
            store.saveFileIndex(
                fileUpdate(path, "Feature").copy(
                    gradleProjects = setOf(rootProject, includedProject),
                    gradleSourceSets = setOf(integrationTest),
                    packageEvidence = IndexedPackageEvidence.ProvenNamed(
                        IndexedPackageEvidence.CanonicalName.parse("com.example.when.Δ"),
                    ),
                ),
            )

            assertEquals(initialGeneration.value + 1, store.readGeneration().value)
            assertEquals(setOf(rootProject, includedProject), store.gradleProjectsForFile(path))
            assertEquals(setOf(integrationTest), store.gradleSourceSetsForFile(path))
            assertEquals(
                IndexedPackageEvidence.ProvenNamed(
                    IndexedPackageEvidence.CanonicalName.parse("com.example.when.Δ"),
                ),
                store.packageEvidenceForFile(path),
            )

            store.saveFileIndex(
                fileUpdate(path, "Feature").copy(
                    gradleProjects = setOf(rootProject),
                    packageEvidence = IndexedPackageEvidence.ProvenRoot,
                ),
            )

            assertEquals(initialGeneration.value + 2, store.readGeneration().value)
            assertEquals(setOf(rootProject), store.gradleProjectsForFile(path))
            assertTrue(store.gradleSourceSetsForFile(path).isEmpty())
            assertEquals(IndexedPackageEvidence.ProvenRoot, store.packageEvidenceForFile(path))

            store.saveFileIndex(
                fileUpdate(path, "Feature").copy(
                    packageEvidence = IndexedPackageEvidence.Unproven(
                        IndexedPackageUnprovenReason.SEMANTIC_ANALYSIS_FAILED,
                    ),
                ),
            )

            assertEquals(initialGeneration.value + 3, store.readGeneration().value)
            assertEquals(
                IndexedPackageEvidence.Unproven(IndexedPackageUnprovenReason.SEMANTIC_ANALYSIS_FAILED),
                store.packageEvidenceForFile(path),
            )

            store.removeFile(path)

            assertEquals(initialGeneration.value + 4, store.readGeneration().value)
            assertTrue(store.gradleProjectsForFile(path).isEmpty())
            assertTrue(store.gradleSourceSetsForFile(path).isEmpty())
            assertNull(store.packageEvidenceForFile(path))
        }
    }

    @Test
    fun `version seven schema is reset before provenance reads`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val dbPath = sourceIndexDatabasePath(normalized)
        Files.createDirectories(dbPath.parent)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)")
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES (7, 41)")
                stmt.execute("CREATE TABLE file_metadata (prefix_id INTEGER NOT NULL, filename TEXT NOT NULL)")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertFalse(store.ensureSchema())
            assertEquals(42, store.readGeneration().value)
            assertTrue(store.gradleProjectsForFile(normalized.resolve("src/App.kt").toString()).isEmpty())
        }
    }

    @Test
    fun `claimed current version missing required structures fails closed`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val dbPath = sourceIndexDatabasePath(normalized)
        Files.createDirectories(dbPath.parent)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)")
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES ($SOURCE_INDEX_SCHEMA_VERSION, 0)")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertThrows(IllegalStateException::class.java) { store.ensureSchema() }
            assertThrows(IllegalStateException::class.java) { store.readGeneration() }
        }
    }

    @Test
    fun `claimed current version without package constraints fails closed`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val dbPath = sourceIndexDatabasePath(normalized)
        SqliteSourceIndexStore(normalized).use { store -> store.ensureSchema() }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys=OFF")
                stmt.execute("ALTER TABLE file_metadata RENAME TO old_file_metadata")
                stmt.execute(
                    """CREATE TABLE file_metadata (
                        prefix_id INTEGER NOT NULL,
                        filename TEXT NOT NULL,
                        package_fq_id INTEGER,
                        package_state TEXT NOT NULL,
                        package_unproven_reason TEXT,
                        module_path TEXT,
                        source_set TEXT,
                        PRIMARY KEY (prefix_id, filename)
                    )""",
                )
                stmt.execute("DROP TABLE old_file_metadata")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertThrows(IllegalStateException::class.java) { store.ensureSchema() }
        }
    }

    @Test
    fun `claimed current version without provenance foreign keys fails closed`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val dbPath = sourceIndexDatabasePath(normalized)
        SqliteSourceIndexStore(normalized).use { store -> store.ensureSchema() }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys=OFF")
                stmt.execute("ALTER TABLE file_gradle_projects RENAME TO old_file_gradle_projects")
                stmt.execute(
                    """CREATE TABLE file_gradle_projects (
                        prefix_id INTEGER NOT NULL,
                        filename TEXT NOT NULL,
                        build_root TEXT NOT NULL,
                        project_path TEXT NOT NULL,
                        PRIMARY KEY (prefix_id, filename, build_root, project_path)
                    )""",
                )
                stmt.execute("DROP TABLE old_file_gradle_projects")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertThrows(IllegalStateException::class.java) { store.ensureSchema() }
        }
    }

    @Test
    fun `claimed current version missing a provenance column fails closed`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val dbPath = sourceIndexDatabasePath(normalized)
        SqliteSourceIndexStore(normalized).use { store -> store.ensureSchema() }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys=OFF")
                stmt.execute("ALTER TABLE file_gradle_source_sets RENAME TO old_file_gradle_source_sets")
                stmt.execute(
                    """CREATE TABLE file_gradle_source_sets (
                        prefix_id INTEGER NOT NULL,
                        filename TEXT NOT NULL,
                        build_root TEXT NOT NULL,
                        project_path TEXT NOT NULL,
                        PRIMARY KEY (prefix_id, filename, build_root, project_path)
                    )""",
                )
                stmt.execute("DROP TABLE old_file_gradle_source_sets")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertThrows(IllegalStateException::class.java) { store.ensureSchema() }
        }
    }

    @Test
    fun `failed provenance replacement rolls back evidence and generation together`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val path = normalized.resolve("src/App.kt").toString()
        val rootProject = gradleProject(buildRoot = ".", projectPath = ":app")
        val includedProject = gradleProject(buildRoot = "included", projectPath = ":app")
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFileIndex(
                fileUpdate(path, "App").copy(
                    gradleProjects = setOf(rootProject),
                    packageEvidence = IndexedPackageEvidence.ProvenRoot,
                ),
            )
        }
        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """CREATE TRIGGER reject_source_set_provenance
                       BEFORE INSERT ON file_gradle_source_sets
                       BEGIN
                           SELECT RAISE(FAIL, 'injected provenance failure');
                       END""",
                )
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            val generationBeforeFailure = store.readGeneration()
            val integrationTest = BuildQualifiedGradleSourceSetIdentity(
                project = includedProject,
                sourceSet = GradleSourceSetName.parse("integrationTest"),
            )

            assertThrows(SQLException::class.java) {
                store.saveFileIndex(
                    fileUpdate(path, "App").copy(
                        gradleProjects = setOf(rootProject, includedProject),
                        gradleSourceSets = setOf(integrationTest),
                        packageEvidence = IndexedPackageEvidence.ProvenNamed(
                            IndexedPackageEvidence.CanonicalName.parse("changed.pkg"),
                        ),
                    ),
                )
            }

            assertEquals(generationBeforeFailure, store.readGeneration())
            assertEquals(setOf(rootProject), store.gradleProjectsForFile(path))
            assertTrue(store.gradleSourceSetsForFile(path).isEmpty())
            assertEquals(IndexedPackageEvidence.ProvenRoot, store.packageEvidenceForFile(path))
        }
    }

    @Test
    fun `malformed Gradle identity and dangling package rows never decode as proof`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val path = normalized.resolve("src/App.kt").toString()
        val project = gradleProject(buildRoot = ".", projectPath = ":app")
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFileIndex(
                fileUpdate(path, "App").copy(
                    gradleProjects = setOf(project),
                    packageEvidence = IndexedPackageEvidence.ProvenNamed(
                        IndexedPackageEvidence.CanonicalName.parse("com.example"),
                    ),
                ),
            )
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys=OFF")
                stmt.execute("UPDATE file_gradle_projects SET build_root = '../outside'")
                stmt.execute("DELETE FROM fq_names WHERE fq_name = 'com.example'")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertThrows(IllegalArgumentException::class.java) { store.gradleProjectsForFile(path) }
            assertThrows(IllegalStateException::class.java) { store.packageEvidenceForFile(path) }
        }
    }

}
