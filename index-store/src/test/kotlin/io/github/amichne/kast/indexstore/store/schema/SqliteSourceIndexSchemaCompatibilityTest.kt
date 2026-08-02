package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class SqliteSourceIndexSchemaCompatibilityTest {
    @TempDir
    lateinit var workspaceRoot: Path

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
}
