package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.jdbc.SqliteJdbcDriverBootstrap
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val WORKSPACE_GENERATION_TEST_JSON = Json { encodeDefaults = true }

internal fun workspaceGenerationStore(
    publicationDirectory: Path,
    content: String,
    overlay: OverlayManifest? = null,
): WorkspaceGenerationStore = workspaceGenerationStore(
    publicationDirectory = publicationDirectory,
    content = { content },
    overlay = overlay,
)

internal fun workspaceGenerationStore(
    publicationDirectory: Path,
    content: () -> String,
    overlay: OverlayManifest? = null,
): WorkspaceGenerationStore = WorkspaceGenerationStore(publicationDirectory, exportDatabase = { target ->
    writeDatabase(target, content = content())
    stableEvidence(overlay)
})

internal fun writeDatabase(
    target: Path,
    generation: Long = 7,
    schemaVersion: Int = SOURCE_INDEX_SCHEMA_VERSION,
    content: String,
) {
    Files.createDirectories(target.toAbsolutePath().normalize().parent)
    SqliteJdbcDriverBootstrap.ensureRegistered()
    DriverManager.getConnection("jdbc:sqlite:$target").use { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE schema_version(version INTEGER NOT NULL, generation INTEGER NOT NULL)",
            )
            statement.execute("INSERT INTO schema_version(version, generation) VALUES ($schemaVersion, $generation)")
            statement.execute("CREATE TABLE test_payload(value TEXT NOT NULL)")
        }
        connection.prepareStatement("INSERT INTO test_payload(value) VALUES (?)").use { statement ->
            statement.setString(1, content)
            statement.executeUpdate()
        }
    }
}

internal fun readPayload(database: Path): String {
    SqliteJdbcDriverBootstrap.ensureRegistered()
    return DriverManager.getConnection("jdbc:sqlite:${database.toUri().toASCIIString()}?mode=ro").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT value FROM test_payload").use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }
}

internal fun mutableSidecar(database: Path, suffix: String): Path =
    database.resolveSibling(database.fileName.toString() + suffix)

internal fun writePointer(path: Path, manifest: PublishedWorkspaceGenerationManifest) {
    Files.writeString(path, WORKSPACE_GENERATION_TEST_JSON.encodeToString(manifest))
}

internal fun stableEvidence(overlay: OverlayManifest? = null) = WorkspaceDatabaseExportEvidence(
    generationBefore = SourceIndexGeneration(7),
    generationAfter = SourceIndexGeneration(7),
    moduleProgressCount = 1,
    incompleteModuleCount = 0,
    pendingUpdateCount = 0,
    sourceIndexSchemaVersion = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
    repositoryOverlay = overlay,
)
