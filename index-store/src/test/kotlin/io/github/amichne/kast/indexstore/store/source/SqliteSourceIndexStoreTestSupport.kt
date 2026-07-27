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

internal fun fileUpdate(path: String, identifier: String): FileIndexUpdate =
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

internal fun key(tree: GitObjectId, producer: ProducerVersion) =
    io.github.amichne.kast.indexstore.snapshot.SnapshotKey(
        treeOid = tree,
        buildClasspathFingerprint = io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint.parse(
            "8".repeat(64),
        ),
        indexSchema = SOURCE_INDEX_SCHEMA_VERSION,
        producerVersion = producer,
    )

internal fun gradleProject(
    buildRoot: String,
    projectPath: String,
): BuildQualifiedGradleProjectIdentity =
    BuildQualifiedGradleProjectIdentity(
        buildRoot = WorkspaceRelativeGradleBuildRoot.parse(buildRoot),
        projectPath = GradleProjectPath.parse(projectPath),
    )

internal fun writeKotlinFile(path: Path): Path {
    Files.createDirectories(path.parent)
    Files.writeString(path, "package demo\n")
    return path.toAbsolutePath().normalize()
}

internal fun copySourceIndexDatabase(
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

internal fun assertSchemaUsesInternedPaths(dbPath: Path) {
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

internal fun tableCount(
    conn: java.sql.Connection,
    tableName: String,
    whereClause: String,
): Int =
    conn.prepareStatement("SELECT COUNT(*) FROM $tableName WHERE $whereClause").use { stmt ->
        val rs = stmt.executeQuery()
        assertTrue(rs.next())
        rs.getInt(1)
    }

internal fun ftsMatches(conn: java.sql.Connection, query: String): List<String> =
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

internal fun assertTableColumns(
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
