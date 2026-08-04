package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.jdbc.SqliteJdbcDriverBootstrap
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.sql.DriverManager
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed interface WorkspaceDatabaseRecovery {
    data class Rebased(val manifest: PublishedWorkspaceGenerationManifest) : WorkspaceDatabaseRecovery

    data object NoPublishedGeneration : WorkspaceDatabaseRecovery
}

internal class WorkspaceDatabaseRecoveryOperator(
    private val validator: PublishedWorkspaceGenerationValidator,
    private val directorySync: (Path) -> Unit,
) {
    fun recover(
        database: Path,
        current: () -> PublishedWorkspaceGenerationManifest?,
    ): WorkspaceDatabaseRecovery {
        val mutableDatabase = database.toAbsolutePath().normalize()
        require(mutableDatabase.fileName.toString() == WORKSPACE_DATABASE_FILE) {
            "Mutable workspace database must be named $WORKSPACE_DATABASE_FILE"
        }
        val parent = requireNotNull(mutableDatabase.parent) {
            "Mutable workspace database must have a parent directory"
        }
        val mutableOverlay = parent.resolve(WORKSPACE_REPOSITORY_OVERLAY_FILE)
        val databaseStage = parent.resolve(".$WORKSPACE_DATABASE_FILE.recovering")
        val overlayStage = parent.resolve(".$WORKSPACE_REPOSITORY_OVERLAY_FILE.recovering")
        val liveArtifacts = listOf(
            mutableDatabase,
            mutableOverlay,
            sidecar(mutableDatabase, "-wal"),
            sidecar(mutableDatabase, "-shm"),
        )
        val recoveryArtifacts = listOf(databaseStage, overlayStage)
        Files.createDirectories(parent)
        return try {
            deleteAll(recoveryArtifacts + liveArtifacts)
            val published = current()
                ?: return WorkspaceDatabaseRecovery.NoPublishedGeneration.also { directorySync(parent) }
            val publishedDatabase = validator.database(published)
            Files.copy(publishedDatabase, databaseStage)
            require(Files.mismatch(publishedDatabase, databaseStage) == -1L) {
                "Recovered workspace database does not exactly match the published generation"
            }
            makeWritable(databaseStage, "Recovered workspace database")
            validator.validateDatabase(
                database = databaseStage,
                expectedSchema = published.sourceIndexSchemaVersion,
                expectedGeneration = published.sourceIndexGeneration,
            )
            sync(databaseStage)

            validator.repositoryOverlay(published)?.let { publishedOverlay ->
                Files.copy(publishedOverlay, overlayStage)
                require(Files.mismatch(publishedOverlay, overlayStage) == -1L) {
                    "Recovered repository overlay does not exactly match the published generation"
                }
                makeWritable(overlayStage, "Recovered repository overlay")
                sync(overlayStage)
                Files.move(
                    overlayStage,
                    mutableOverlay,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            check(current() == published) {
                "Published workspace generation moved during mutable database recovery"
            }
            Files.move(
                databaseStage,
                mutableDatabase,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            directorySync(parent)
            WorkspaceDatabaseRecovery.Rebased(published)
        } catch (failure: Throwable) {
            (recoveryArtifacts + liveArtifacts).forEach { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            try {
                directorySync(parent)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun deleteAll(paths: List<Path>) {
        paths.forEach { path -> Files.deleteIfExists(path) }
    }

    private fun sync(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
    }

    private fun makeWritable(path: Path, description: String) {
        check(path.toFile().setWritable(true, true) || Files.isWritable(path)) {
            "$description could not be made writable"
        }
    }

    private fun sidecar(database: Path, suffix: String): Path =
        database.resolveSibling(database.fileName.toString() + suffix)
}

internal class PublishedWorkspaceGenerationValidator(
    private val generationsDirectory: Path,
    private val json: Json,
) {
    fun database(manifest: PublishedWorkspaceGenerationManifest): Path =
        resolveGenerationFile(manifest.databaseFile)

    fun repositoryOverlay(manifest: PublishedWorkspaceGenerationManifest): Path? =
        manifest.repositoryOverlayFile?.let { file ->
            require(isCanonicalLeaf(file) && file == WORKSPACE_REPOSITORY_OVERLAY_FILE) {
                "Published repository overlay path is invalid"
            }
            val database = database(manifest)
            database.resolveSibling(file).toAbsolutePath().normalize().also { descriptor ->
                require(descriptor.parent == database.parent) {
                    "Published repository overlay must stay beside its generation database"
                }
            }
        }

    fun validateManifestFiles(manifest: PublishedWorkspaceGenerationManifest) {
        val publishedDatabase = database(manifest)
        requireRegularContainedFile(publishedDatabase, "Published workspace database")
        validateDatabase(
            database = publishedDatabase,
            expectedSchema = manifest.sourceIndexSchemaVersion,
            expectedGeneration = manifest.sourceIndexGeneration,
        )

        repositoryOverlay(manifest)?.let { descriptor ->
            requireRegularContainedFile(descriptor, "Published repository overlay")
            val overlay = json.decodeFromString<OverlayManifest>(Files.readString(descriptor))
            val base = validateRepositoryOverlay(overlay, manifest.sourceIndexSchemaVersion)
            val expectedBase = publishedDatabase.resolveSibling(WORKSPACE_REPOSITORY_BASE_FILE)
                .toAbsolutePath()
                .normalize()
            require(base == expectedBase) {
                "Published repository base must be the database contained in its generation directory"
            }
            requireRegularContainedFile(base, "Published repository base database")
        }
    }

    fun validateDatabase(
        database: Path,
        expectedSchema: SourceIndexSchemaVersion,
        expectedGeneration: SourceIndexGeneration,
    ) {
        require(expectedSchema.value == SOURCE_INDEX_SCHEMA_VERSION) {
            "Published workspace database schema ${expectedSchema.value} is not supported"
        }
        val identity = readDatabaseIdentity(database)
        require(identity.schemaVersion == expectedSchema.value) {
            "Published workspace database schema ${identity.schemaVersion} does not match ${expectedSchema.value}"
        }
        require(identity.sourceIndexGeneration == expectedGeneration.value) {
            "Published workspace database generation ${identity.sourceIndexGeneration} does not match " +
                expectedGeneration.value
        }
    }

    fun validateRepositoryOverlay(
        overlay: OverlayManifest,
        expectedSchema: SourceIndexSchemaVersion,
    ): Path {
        require(overlay.base.compatibility == overlay.target.compatibility) {
            "Published repository overlay compatibility does not match"
        }
        require(overlay.base.indexSchema == expectedSchema.value) {
            "Published repository overlay schema does not match its workspace database"
        }
        val rawBase = requireNotNull(overlay.baseDatabase) {
            "Published repository overlay has no repository base database"
        }
        val base = Path.of(rawBase).toAbsolutePath().normalize()
        require(Path.of(rawBase).isAbsolute && Files.isRegularFile(base, LinkOption.NOFOLLOW_LINKS)) {
            "Published repository base database is unavailable"
        }
        val identity = readDatabaseIdentity(base)
        require(identity.schemaVersion == expectedSchema.value) {
            "Published repository base schema ${identity.schemaVersion} does not match ${expectedSchema.value}"
        }
        return base
    }

    private fun readDatabaseIdentity(database: Path): DatabaseIdentity {
        SqliteJdbcDriverBootstrap.ensureRegistered()
        val url = "jdbc:sqlite:${database.toUri().toASCIIString()}?mode=ro&immutable=1"
        return DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement("SELECT version, generation FROM schema_version LIMIT 1").use { statement ->
                statement.executeQuery().use { rows ->
                    require(rows.next()) { "Published workspace database has no schema identity" }
                    DatabaseIdentity(rows.getInt(1), rows.getLong(2))
                }
            }
        }
    }

    private fun resolveGenerationFile(raw: String): Path {
        require(isCanonicalGenerationDatabasePath(raw)) {
            "Published workspace database path is invalid"
        }
        val resolved = generationsDirectory.resolve(raw).toAbsolutePath().normalize()
        require(resolved.startsWith(generationsDirectory) && resolved.parent?.parent == generationsDirectory) {
            "Published workspace database must stay inside one generation directory"
        }
        return resolved
    }

    private fun requireRegularContainedFile(path: Path, description: String) {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "$description is unavailable or symbolic"
        }
        val realGenerationsDirectory = generationsDirectory.toRealPath()
        require(path.toRealPath().startsWith(realGenerationsDirectory)) {
            "$description must stay inside the generations directory"
        }
    }

    private data class DatabaseIdentity(
        val schemaVersion: Int,
        val sourceIndexGeneration: Long,
    )
}

internal fun isCanonicalGenerationDatabasePath(raw: String): Boolean {
    if (raw.isBlank() || '\\' in raw) return false
    val path = runCatching { Path.of(raw) }.getOrNull() ?: return false
    return !path.isAbsolute &&
        path.nameCount == 2 &&
        path.normalize().toString() == raw &&
        path.fileName.toString() == WORKSPACE_DATABASE_FILE &&
        path.none { segment -> segment.toString() == ".." }
}

internal fun isCanonicalLeaf(raw: String): Boolean {
    if (raw.isBlank() || '\\' in raw) return false
    val path = runCatching { Path.of(raw) }.getOrNull() ?: return false
    return !path.isAbsolute && path.nameCount == 1 && path.normalize().toString() == raw
}
