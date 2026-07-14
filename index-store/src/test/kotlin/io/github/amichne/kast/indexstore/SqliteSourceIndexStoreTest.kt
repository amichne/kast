package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
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

class SqliteSourceIndexStoreTest {
    @TempDir
    lateinit var workspaceRoot: Path

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
                        IndexedPackageEvidence.CanonicalName.parse("com.example.`when`.Δ"),
                    ),
                ),
            )

            assertEquals(initialGeneration.value + 1, store.readGeneration().value)
            assertEquals(setOf(rootProject, includedProject), store.gradleProjectsForFile(path))
            assertEquals(setOf(integrationTest), store.gradleSourceSetsForFile(path))
            assertEquals(
                IndexedPackageEvidence.ProvenNamed(
                    IndexedPackageEvidence.CanonicalName.parse("com.example.`when`.Δ"),
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
    fun `claimed version eight missing provenance structures fails closed`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val dbPath = sourceIndexDatabasePath(normalized)
        Files.createDirectories(dbPath.parent)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)")
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES (8, 0)")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertThrows(IllegalStateException::class.java) { store.ensureSchema() }
            assertThrows(IllegalStateException::class.java) { store.readGeneration() }
        }
    }

    @Test
    fun `claimed version eight without package constraints fails closed`() {
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
    fun `claimed version eight without provenance foreign keys fails closed`() {
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
    fun `claimed version eight missing a provenance column fails closed`() {
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

    @Test
    fun `database is created under workspace cache directory`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
        }

        assertTrue(Files.isRegularFile(sourceIndexDatabasePath(normalized)))
        assertTrue(sourceIndexDatabasePath(normalized).startsWith(kastCacheDirectory(normalized)))
    }

    @Test
    fun `different workspace roots use different source index databases`() {
        val firstRoot = Files.createDirectories(workspaceRoot.resolve("first")).toAbsolutePath().normalize()
        val secondRoot = Files.createDirectories(workspaceRoot.resolve("second")).toAbsolutePath().normalize()

        SqliteSourceIndexStore(firstRoot).use { store -> store.ensureSchema() }
        SqliteSourceIndexStore(secondRoot).use { store -> store.ensureSchema() }

        val firstDatabase = sourceIndexDatabasePath(firstRoot)
        val secondDatabase = sourceIndexDatabasePath(secondRoot)
        assertNotEquals(firstDatabase, secondDatabase)
        assertTrue(Files.isRegularFile(firstDatabase), "first database missing at $firstDatabase")
        assertTrue(Files.isRegularFile(secondDatabase), "second database missing at $secondDatabase")
    }

    @Test
    fun `ensureSchema bootstraps sqlite driver when DriverManager registry is empty`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()

        withSqliteDriversDeregistered {
            SqliteSourceIndexStore(normalized).use { store ->
                assertTrue(store.ensureSchema())
            }
        }

        assertTrue(Files.isRegularFile(sourceIndexDatabasePath(normalized)))
    }

    @Test
    fun `schema version mismatch triggers full rebuild`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val cacheDir = kastCacheDirectory(normalized)
        Files.createDirectories(cacheDir)
        val dbPath = cacheDir.resolve("source-index.db")

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)")
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES (999, 0)")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertFalse(store.ensureSchema())
        }

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.prepareStatement("SELECT version FROM schema_version LIMIT 1").use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals(SOURCE_INDEX_SCHEMA_VERSION, rs.getInt(1))
            }
        }
    }

    @Test
    fun `head commit round-trips through schema version table`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()

            store.writeHeadCommit("abc123")

            assertEquals("abc123", store.readHeadCommit())
        }
    }

    @Test
    fun `schema creates persistent trigram FTS for FQ names`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store -> store.ensureSchema() }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO fq_names(fq_id, fq_name) VALUES (1, 'com.example.FooWidget')")
            }

            assertEquals(listOf("com.example.FooWidget"), ftsMatches(conn, "Widget"))

            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE fq_names SET fq_name = 'com.example.BarWidget' WHERE fq_id = 1")
            }

            assertEquals(emptyList<String>(), ftsMatches(conn, "FooWidget"))
            assertEquals(listOf("com.example.BarWidget"), ftsMatches(conn, "BarWidget"))

            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM fq_names WHERE fq_id = 1")
            }

            assertEquals(emptyList<String>(), ftsMatches(conn, "BarWidget"))
        }
    }

    @Test
    fun `prior schema rebuilds without preserving compatibility data`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val cacheDir = kastCacheDirectory(normalized)
        Files.createDirectories(cacheDir)
        val dbPath = cacheDir.resolve("source-index.db")

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)")
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES (4, 0)")
                stmt.execute(
                    """CREATE TABLE identifier_paths (
                        identifier TEXT NOT NULL,
                        path TEXT NOT NULL,
                        PRIMARY KEY (identifier, path)
                    )""",
                )
                stmt.execute(
                    """CREATE TABLE file_metadata (
                        path TEXT PRIMARY KEY,
                        package_name TEXT,
                        module_path TEXT,
                        source_set TEXT,
                        imports TEXT,
                        wildcard_imports TEXT
                    )""",
                )
                stmt.execute(
                    """CREATE TABLE file_manifest (
                        path TEXT PRIMARY KEY,
                        last_modified_millis INTEGER NOT NULL
                    )""",
                )
                stmt.execute(
                    """CREATE TABLE workspace_discovery (
                        cache_key TEXT PRIMARY KEY,
                        schema_version INTEGER NOT NULL,
                        payload TEXT NOT NULL
                    )""",
                )
                stmt.execute("INSERT INTO workspace_discovery (cache_key, schema_version, payload) VALUES ('modules', 1, '{}')")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertFalse(store.ensureSchema())
            store.writeHeadCommit("def456")

            assertEquals("def456", store.readHeadCommit())
            assertNull(store.readWorkspaceDiscovery("modules"))
            assertSchemaUsesInternedPaths(dbPath)
        }
    }

    @Test
    fun `source index snapshot round-trips identifiers and metadata`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/Caller.kt").toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    FileIndexUpdate(
                        path = callerPath,
                        identifiers = setOf("Caller", "call"),
                        packageName = "consumer",
                        modulePath = ":app",
                        sourceSet = "main",
                        imports = setOf("lib.Foo"),
                        wildcardImports = setOf("lib.internal"),
                        packageEvidence = IndexedPackageEvidence.ProvenNamed(
                            IndexedPackageEvidence.CanonicalName.parse("consumer"),
                        ),
                    ),
                ),
                manifest = mapOf(callerPath to 123L),
            )

            val snapshot = store.loadSourceIndexSnapshot()


            assertEquals(listOf(callerPath), snapshot.candidatePathsByIdentifier.getValue("Caller"))
            assertEquals(":app[main]", snapshot.moduleNameByPath.getValue(callerPath))
            assertEquals("consumer", snapshot.packageByPath.getValue(callerPath))
            assertEquals(listOf("lib.Foo"), snapshot.importsByPath.getValue(callerPath))
            assertEquals(listOf("lib.internal"), snapshot.wildcardImportPackagesByPath.getValue(callerPath))
            assertEquals(mapOf(callerPath to 123L), store.loadManifest())
        }
    }

    @Test
    fun `source index stores interned directory prefixes while returning absolute paths`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/main/Caller.kt").toString()
        val targetPath = normalized.resolve("src/test/Target.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    fileUpdate(callerPath, "Caller"),
                    fileUpdate(targetPath, "Target"),
                ),
                manifest = mapOf(callerPath to 1L, targetPath to 2L),
            )

            val snapshot = store.loadSourceIndexSnapshot()

            assertEquals(listOf(callerPath), snapshot.candidatePathsByIdentifier.getValue("Caller"))
            assertEquals(listOf(targetPath), snapshot.candidatePathsByIdentifier.getValue("Target"))
            assertEquals(mapOf(callerPath to 1L, targetPath to 2L), store.loadManifest())
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.prepareStatement("SELECT dir_path FROM path_prefixes ORDER BY dir_path").use { stmt ->
                val rs = stmt.executeQuery()
                val prefixes = buildList {
                    while (rs.next()) add(rs.getString(1))
                }
                assertEquals(listOf("src/main", "src/test"), prefixes)
            }
            conn.prepareStatement("PRAGMA table_info(identifier_paths)").use { stmt ->
                val rs = stmt.executeQuery()
                val columns = buildList {
                    while (rs.next()) add(rs.getString("name"))
                }
                assertFalse("path" in columns)
                assertTrue("prefix_id" in columns)
                assertTrue("filename" in columns)
            }
        }
    }

    @Test
    fun `source index stores FQ names and imports in interned relational tables`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/Caller.kt").toString()
        val targetPath = normalized.resolve("src/Foo.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    FileIndexUpdate(
                        path = callerPath,
                        identifiers = setOf("Caller"),
                        packageName = "consumer",
                        modulePath = ":app",
                        sourceSet = "main",
                        imports = setOf("lib.Foo", "kotlin.collections.List"),
                        wildcardImports = setOf("lib.internal"),
                        packageEvidence = IndexedPackageEvidence.ProvenNamed(
                            IndexedPackageEvidence.CanonicalName.parse("consumer"),
                        ),
                    ),
                ),
                manifest = mapOf(callerPath to 1L),
            )
            store.upsertSymbolReference(callerPath, 42, "lib.Foo", targetPath, 10)

            val snapshot = store.loadSourceIndexSnapshot()

            assertEquals("consumer", snapshot.packageByPath.getValue(callerPath))
            assertEquals(listOf("kotlin.collections.List", "lib.Foo"), snapshot.importsByPath.getValue(callerPath))
            assertEquals(listOf("lib.internal"), snapshot.wildcardImportPackagesByPath.getValue(callerPath))
            assertEquals("lib.Foo", store.referencesToSymbol("lib.Foo").single().targetFqName)
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            assertTableColumns(
                conn = conn,
                tableName = "file_metadata",
                present = setOf(
                    "prefix_id",
                    "filename",
                    "package_fq_id",
                    "package_state",
                    "package_unproven_reason",
                    "module_path",
                    "source_set",
                ),
                absent = setOf("path", "package_name", "module_name", "imports", "wildcard_imports"),
            )
            assertTableColumns(
                conn = conn,
                tableName = "symbol_references",
                present = setOf("src_prefix_id", "src_filename", "target_fq_id"),
                absent = setOf("source_path", "target_path", "target_fq_name"),
            )
            conn.prepareStatement(
                """SELECT fq.fq_name
                   FROM file_imports imports
                   JOIN fq_names fq ON fq.fq_id = imports.fq_id
                   ORDER BY fq.fq_name""",
            ).use { stmt ->
                val rs = stmt.executeQuery()
                val imports = buildList {
                    while (rs.next()) add(rs.getString(1))
                }
                assertEquals(listOf("kotlin.collections.List", "lib.Foo"), imports)
            }
            conn.prepareStatement(
                """SELECT fq.fq_name
                   FROM file_wildcard_imports imports
                   JOIN fq_names fq ON fq.fq_id = imports.fq_id""",
            ).use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("lib.internal", rs.getString(1))
            }
        }
    }

    @Test
    fun `restored source index decodes workspace paths under current workspace root`() {
        val originalRoot = workspaceRoot.resolve("original").toAbsolutePath().normalize()
        val restoredRoot = workspaceRoot.resolve("restored").toAbsolutePath().normalize()
        val originalPath = originalRoot.resolve("src/Portable.kt").toString()
        val restoredPath = restoredRoot.resolve("src/Portable.kt").toString()

        SqliteSourceIndexStore(originalRoot).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate(originalPath, "Portable")),
                manifest = mapOf(originalPath to 9L),
            )
        }
        copySourceIndexDatabase(originalRoot, restoredRoot)

        SqliteSourceIndexStore(restoredRoot).use { store ->
            assertTrue(store.ensureSchema())

            assertEquals(
                listOf(restoredPath),
                store.loadSourceIndexSnapshot().candidatePathsByIdentifier.getValue("Portable")
            )
            assertEquals(mapOf(restoredPath to 9L), store.loadManifest())
        }
    }

    @Test
    fun `paths outside workspace root round-trip through absolute sentinel prefix`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val externalPath = normalized.parent.resolve("external/Outside.kt").normalize().toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate(externalPath, "Outside")),
                manifest = mapOf(externalPath to 4L),
            )

            assertEquals(
                listOf(externalPath),
                store.loadSourceIndexSnapshot().candidatePathsByIdentifier.getValue("Outside")
            )
            assertEquals(mapOf(externalPath to 4L), store.loadManifest())
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.prepareStatement("SELECT dir_path FROM path_prefixes").use { stmt ->
                val rs = stmt.executeQuery()
                val prefixes = buildList {
                    while (rs.next()) add(rs.getString(1))
                }
                assertTrue(prefixes.any { it.startsWith("__kast_abs__/") })
            }
        }
    }

    @Test
    fun `incremental file indexing adds new prefixes to table and cache`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val firstPath = normalized.resolve("first/One.kt").toString()
        val secondPath = normalized.resolve("second/Two.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFileIndex(fileUpdate(firstPath, "One"))
            store.saveFileIndex(fileUpdate(secondPath, "Two"))

            val snapshot = store.loadSourceIndexSnapshot()

            assertEquals(listOf(firstPath), snapshot.candidatePathsByIdentifier.getValue("One"))
            assertEquals(listOf(secondPath), snapshot.candidatePathsByIdentifier.getValue("Two"))
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.prepareStatement("SELECT dir_path FROM path_prefixes ORDER BY dir_path").use { stmt ->
                val rs = stmt.executeQuery()
                val prefixes = buildList {
                    while (rs.next()) add(rs.getString(1))
                }
                assertEquals(listOf("first", "second"), prefixes)
            }

        }
    }

    @Test
    fun `symbol references round-trip and clear by source file`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 42,
                targetFqName = "lib.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Other.kt",
                sourceOffset = 7,
                targetFqName = "lib.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )

            assertEquals(2, store.referencesToSymbol("lib.Foo").size)
            store.clearReferencesFromFile("/src/Caller.kt")

            assertTrue(store.referencesFromFile("/src/Caller.kt").isEmpty())
            assertEquals(1, store.referencesToSymbol("lib.Foo").size)
        }
    }

    @Test
    fun `symbol reference pages bound high cardinality lookup work and continue deterministically`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            repeat(500) { index ->
                store.upsertSymbolReference(
                    sourcePath = "/src/Caller${index.toString().padStart(3, '0')}.kt",
                    sourceOffset = index,
                    targetFqName = "lib.HighCardinality",
                    targetPath = "/src/HighCardinality.kt",
                    targetOffset = 10,
                )
            }

            val first = store.generatedReferencePageToSymbol(
                targetFqName = "lib.HighCardinality",
                offset = NonNegativeInt(0),
                maxResults = PositiveInt(4),
            )
            val second = store.generatedReferencePageToSymbol(
                targetFqName = "lib.HighCardinality",
                offset = requireNotNull(first.page.nextOffset),
                maxResults = PositiveInt(4),
            )

            assertEquals(4, first.page.references.size)
            assertEquals(NonNegativeInt(4), first.page.nextOffset)
            assertEquals(4, second.page.references.size)
            assertEquals(NonNegativeInt(8), second.page.nextOffset)
            assertEquals(first.generation, second.generation)
            assertTrue(first.page.references.toSet().intersect(second.page.references.toSet()).isEmpty())
            assertEquals(
                (0 until 8).map { index -> "/src/Caller${index.toString().padStart(3, '0')}.kt" },
                first.page.references.map { it.sourcePath } + second.page.references.map { it.sourcePath },
            )
        }
    }

    @Test
    fun `reference generation and rows share one database snapshot across store connections`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val generationRead = CountDownLatch(1)
        val writerCommitted = CountDownLatch(1)
        val result = AtomicReference<io.github.amichne.kast.indexstore.api.reference.GeneratedSymbolReferencePage>()
        SqliteSourceIndexStore(normalized).use { writer ->
            writer.ensureSchema()
            writer.upsertSymbolReference(
                sourcePath = "/src/Before.kt",
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = "/src/Target.kt",
                targetOffset = 1,
            )
            val generationBeforeMutation = writer.readGeneration()
            SqliteSourceIndexStore(
                workspaceRoot = normalized,
                pageReadObserver = SourceIndexPageReadObserver {
                    generationRead.countDown()
                    assertTrue(writerCommitted.await(10, TimeUnit.SECONDS))
                },
            ).use { reader ->
                val readThread = thread(name = "source-index-snapshot-reader") {
                    result.set(
                        reader.generatedReferencePageToSymbol(
                            targetFqName = "demo.Target",
                            offset = NonNegativeInt(0),
                            maxResults = PositiveInt(10),
                        ),
                    )
                }
                assertTrue(generationRead.await(10, TimeUnit.SECONDS))
                writer.upsertSymbolReference(
                    sourcePath = "/src/After.kt",
                    sourceOffset = 2,
                    targetFqName = "demo.Target",
                    targetPath = "/src/Target.kt",
                    targetOffset = 1,
                )
                writerCommitted.countDown()
                readThread.join(10_000)
                assertFalse(readThread.isAlive, "snapshot reader did not complete")

                val page = requireNotNull(result.get())
                assertEquals(generationBeforeMutation, page.generation)
                assertEquals(listOf("/src/Before.kt"), page.page.references.map { it.sourcePath })
            }
        }
    }

    @Test
    fun `generation advances for every committed reference content transition`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            val initialGeneration = store.readGeneration()

            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = "/src/Target.kt",
                targetOffset = 1,
            )
            assertEquals(initialGeneration.value + 1, store.readGeneration().value)

            store.clearReferencesFromFile("/src/Caller.kt")
            assertEquals(initialGeneration.value + 2, store.readGeneration().value)

            store.saveFullIndex(
                updates = listOf(fileUpdate("/src/Rebuilt.kt", "Rebuilt")),
                manifest = mapOf("/src/Rebuilt.kt" to 1L),
            )
            assertEquals(initialGeneration.value + 3, store.readGeneration().value)

            store.appendPendingUpdate(
                op = "upsert_ref",
                path = "/src/Rebuilt.kt",
                payload = """{"sourceOffset":2,"targetFqName":"demo.Target"}""",
            )
            assertEquals(1, store.reconcilePendingUpdates())
            assertEquals(initialGeneration.value + 4, store.readGeneration().value)
        }
    }

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

    @Test
    fun `source file inventory groups existing Kotlin files by source root`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val mainRoot = normalized.resolve("src/main/kotlin")
        val testRoot = normalized.resolve("src/test/kotlin")
        val mainFile = writeKotlinFile(mainRoot.resolve("demo/Main.kt"))
        val otherMainFile = writeKotlinFile(mainRoot.resolve("demo/Other.kt"))
        val testFile = writeKotlinFile(testRoot.resolve("demo/MainTest.kt"))
        val scriptPath = normalized.resolve("build.gradle.kts").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    fileUpdate(mainFile.toString(), "Main"),
                    fileUpdate(otherMainFile.toString(), "Other"),
                    fileUpdate(testFile.toString(), "MainTest"),
                    fileUpdate(scriptPath, "GradleScript"),
                ),
                manifest = mapOf(
                    mainFile.toString() to 1L,
                    otherMainFile.toString() to 2L,
                    testFile.toString() to 3L,
                    scriptPath to 5L,
                ),
            )

            assertEquals(
                mapOf(
                    mainRoot to 2,
                    testRoot to 1,
                ),
                store.fileCountBySourceRoot(listOf(mainRoot, testRoot)),
            )
            assertEquals(
                mapOf(
                    mainRoot to listOf(mainFile, otherMainFile),
                    testRoot to listOf(testFile),
                ),
                store.filesBySourceRoot(listOf(mainRoot, testRoot)),
            )
            assertEquals(
                mapOf(
                    mainRoot to listOf(mainFile),
                    testRoot to listOf(testFile),
                ),
                store.filesBySourceRoot(listOf(mainRoot, testRoot), limitPerRoot = 1),
            )
        }
    }

    @Test
    fun `source file counts are grouped by source root without requiring files to exist`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val mainRoot = normalized.resolve("src/main/kotlin")
        val indexedButMissingFile = mainRoot.resolve("demo/Missing.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate(indexedButMissingFile, "Missing")),
                manifest = mapOf(indexedButMissingFile to 1L),
            )

            assertEquals(
                mapOf(mainRoot to 1),
                store.fileCountBySourceRoot(listOf(mainRoot)),
            )
            assertEquals(
                mapOf(mainRoot to emptyList<Path>()),
                store.filesBySourceRoot(listOf(mainRoot)),
            )
    }

    @Test
    fun `module index progress records pending indexing and completion state`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()

            store.initializeModuleProgress(
                mapOf(
                    ":app[main]" to 2,
                    ":lib[main]" to 1,
                ),
            )

            assertEquals("PENDING", store.moduleIndexStatus(":app[main]"))
            assertEquals(emptySet<String>(), store.completedModules())

            store.markModuleIndexing(":app[main]")
            assertEquals("INDEXING", store.moduleIndexStatus(":app[main]"))

            store.markModuleComplete(":app[main]", fileCount = 2)
            assertEquals("COMPLETE", store.moduleIndexStatus(":app[main]"))
            assertEquals(setOf(":app[main]"), store.completedModules())

            store.markModuleComplete(":lib[main]", fileCount = 1)
            assertEquals(setOf(":app[main]", ":lib[main]"), store.completedModules())
        }
    }
    }

    @Test
    fun `symbol reference entry points reject Kotlin script paths`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = "/build.gradle.kts",
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = "/src/Target.kt",
                targetOffset = 1,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 2,
                targetFqName = "demo.Script",
                targetPath = "/build.gradle.kts",
                targetOffset = 1,
            )

            assertTrue(store.referencesFromFile("/build.gradle.kts").isEmpty())
            val scriptReference = store.referencesToSymbol("demo.Script").single()
            assertEquals("/src/Caller.kt", scriptReference.sourcePath)
            assertEquals(null, scriptReference.targetPath)
            assertEquals(null, scriptReference.targetOffset)
        }
    }

    @Test
    fun `ensureSchema does not run compatibility cleanup for current schema`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val dbPath = sourceIndexDatabasePath(normalized)

        SqliteSourceIndexStore(normalized).use { store -> store.ensureSchema() }

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT OR IGNORE INTO path_prefixes (prefix_id, dir_path) VALUES (100, '')")
                stmt.execute("INSERT OR IGNORE INTO fq_names (fq_id, fq_name) VALUES (100, 'demo.GradleScript')")
                stmt.execute("INSERT OR IGNORE INTO fq_names (fq_id, fq_name) VALUES (101, 'demo.CaseSensitive')")
                stmt.execute("INSERT OR IGNORE INTO fq_names (fq_id, fq_name) VALUES (102, 'demo.ScriptTarget')")
                stmt.execute("INSERT INTO identifier_paths (identifier, prefix_id, filename) VALUES ('GradleScript', 100, 'build.gradle.kts')")
                stmt.execute("INSERT INTO identifier_paths (identifier, prefix_id, filename) VALUES ('CaseSensitive', 100, 'Foo.KT')")
                stmt.execute("INSERT INTO file_metadata (prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set) VALUES (100, 'build.gradle.kts', 100, 'PROVEN_NAMED', NULL, ':main', 'main')")
                stmt.execute("INSERT INTO file_metadata (prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set) VALUES (100, 'Foo.KT', 101, 'PROVEN_NAMED', NULL, ':main', 'main')")
                stmt.execute("INSERT INTO file_manifest (prefix_id, filename, last_modified_millis) VALUES (100, 'build.gradle.kts', 1)")
                stmt.execute("INSERT INTO file_manifest (prefix_id, filename, last_modified_millis) VALUES (100, 'Foo.KT', 1)")
                stmt.execute("INSERT INTO file_imports (prefix_id, filename, fq_id) VALUES (100, 'build.gradle.kts', 100)")
                stmt.execute("INSERT INTO file_wildcard_imports (prefix_id, filename, fq_id) VALUES (100, 'build.gradle.kts', 100)")
                stmt.execute(
                    """INSERT INTO symbol_references
                       (src_prefix_id, src_filename, source_offset, target_fq_id, tgt_prefix_id, tgt_filename, target_offset)
                       VALUES (100, 'build.gradle.kts', 1, 100, 100, 'build.gradle.kts', 1)""",
                )
                stmt.execute(
                    """INSERT INTO symbol_references
                       (src_prefix_id, src_filename, source_offset, target_fq_id, tgt_prefix_id, tgt_filename, target_offset)
                       VALUES (100, 'Caller.kt', 2, 102, 100, 'build.gradle.kts', 1)""",
                )
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertTrue(store.ensureSchema())
        }

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            assertEquals(1, tableCount(conn, "identifier_paths", "filename = 'build.gradle.kts'"))
            assertEquals(1, tableCount(conn, "identifier_paths", "filename = 'Foo.KT'"))
            assertEquals(1, tableCount(conn, "file_metadata", "filename = 'build.gradle.kts'"))
            assertEquals(1, tableCount(conn, "file_metadata", "filename = 'Foo.KT'"))
            assertEquals(1, tableCount(conn, "file_manifest", "filename = 'build.gradle.kts'"))
            assertEquals(1, tableCount(conn, "file_manifest", "filename = 'Foo.KT'"))
            assertEquals(1, tableCount(conn, "file_imports", "filename = 'build.gradle.kts'"))
            assertEquals(1, tableCount(conn, "file_wildcard_imports", "filename = 'build.gradle.kts'"))
            assertEquals(2, tableCount(conn, "symbol_references", "src_filename = 'build.gradle.kts' OR tgt_filename = 'build.gradle.kts'"))
            conn.prepareStatement("SELECT tgt_filename, target_offset FROM symbol_references WHERE src_filename = 'Caller.kt'").use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("build.gradle.kts", rs.getString("tgt_filename"))
                assertEquals(1, rs.getInt("target_offset"))
            }
        }
    }

    private fun fileUpdate(path: String, identifier: String): FileIndexUpdate =
        FileIndexUpdate(
            path = path,
            identifiers = setOf(identifier),
            packageName = "demo",
            modulePath = ":main",
            sourceSet = null,
            imports = emptySet(),
            wildcardImports = emptySet(),
            packageEvidence = IndexedPackageEvidence.ProvenNamed(
                IndexedPackageEvidence.CanonicalName.parse("demo"),
            ),
        )

    private fun gradleProject(
        buildRoot: String,
        projectPath: String,
    ): BuildQualifiedGradleProjectIdentity =
        BuildQualifiedGradleProjectIdentity(
            buildRoot = WorkspaceRelativeGradleBuildRoot.parse(buildRoot),
            projectPath = GradleProjectPath.parse(projectPath),
        )

    private fun writeKotlinFile(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, "package demo\n")
        return path.toAbsolutePath().normalize()
    }

    private fun copySourceIndexDatabase(
        originalRoot: Path,
        restoredRoot: Path,
    ) {
        val sourcePath = sourceIndexDatabasePath(originalRoot)
        DriverManager.getConnection("jdbc:sqlite:$sourcePath").use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("PRAGMA wal_checkpoint(FULL)") }
        }
        val restoredPath = sourceIndexDatabasePath(restoredRoot)
        Files.createDirectories(restoredPath.parent)
        Files.list(sourcePath.parent).use { files ->
            files
                .filter { it.fileName.toString().startsWith(sourcePath.fileName.toString()) }
                .forEach { file ->
                    Files.copy(file, restoredPath.parent.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
                }
        }
    }

    private fun assertSchemaUsesInternedPaths(dbPath: Path) {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'path_prefixes'")
                .use { stmt ->
                    val rs = stmt.executeQuery()
                    assertTrue(rs.next())
                }
            conn.prepareStatement("PRAGMA table_info(identifier_paths)").use { stmt ->
                val rs = stmt.executeQuery()
                val columns = buildList {
                    while (rs.next()) add(rs.getString("name"))
                }
                assertFalse("path" in columns)
                assertTrue("prefix_id" in columns)
                assertTrue("filename" in columns)
            }
        }
    }

    private fun tableCount(
        conn: java.sql.Connection,
        tableName: String,
        whereClause: String,
    ): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM $tableName WHERE $whereClause").use { stmt ->
            val rs = stmt.executeQuery()
            assertTrue(rs.next())
            rs.getInt(1)
        }

    private fun ftsMatches(conn: java.sql.Connection, query: String): List<String> =
        conn.prepareStatement(
            """SELECT fq_name
               FROM fq_names_fts
               WHERE fq_names_fts MATCH ?
               ORDER BY rank, fq_name""",
        ).use { stmt ->
            stmt.setString(1, "\"${query.lowercase()}\"")
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) add(rs.getString(1))
            }
        }

    private fun assertTableColumns(
        conn: java.sql.Connection,
        tableName: String,
        present: Set<String>,
        absent: Set<String>,
    ) {
        conn.prepareStatement("PRAGMA table_info($tableName)").use { stmt ->
            val rs = stmt.executeQuery()
            val columns = buildSet {
                while (rs.next()) add(rs.getString("name"))
            }
            present.forEach { column -> assertTrue(column in columns) }
            absent.forEach { column -> assertFalse(column in columns) }
        }
    }
}
