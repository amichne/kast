package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val WORKSPACE_GENERATIONS_DIRECTORY = "generations"
internal const val WORKSPACE_CURRENT_POINTER_FILE = "current.json"
internal const val WORKSPACE_DATABASE_FILE = "source-index.db"
internal const val WORKSPACE_REPOSITORY_OVERLAY_FILE = "repository-overlay.json"
internal const val WORKSPACE_REPOSITORY_BASE_FILE = "repository-base.db"

@Serializable
@JvmInline
value class PublishedWorkspaceIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Published workspace identity must not be blank" }
    }
}

@Serializable
@JvmInline
value class WorkspaceSemanticGeneration(val value: Long) {
    init {
        require(value > 0) { "Workspace semantic generation must be positive" }
    }

    fun next(): WorkspaceSemanticGeneration = WorkspaceSemanticGeneration(Math.addExact(value, 1))
}

data class WorkspaceDatabaseExportEvidence(
    val generationBefore: SourceIndexGeneration,
    val generationAfter: SourceIndexGeneration,
    val moduleProgressCount: Int,
    val incompleteModuleCount: Int,
    val pendingUpdateCount: Int,
    val sourceIndexSchemaVersion: SourceIndexSchemaVersion,
    val repositoryOverlay: OverlayManifest? = null,
) {
    init {
        require(moduleProgressCount >= 0) { "Module progress count must not be negative" }
        require(incompleteModuleCount >= 0) { "Incomplete module count must not be negative" }
        require(pendingUpdateCount >= 0) { "Pending update count must not be negative" }
    }

    val provesCompleteStableDatabase: Boolean
        get() = generationBefore == generationAfter &&
            moduleProgressCount > 0 &&
            incompleteModuleCount == 0 &&
            pendingUpdateCount == 0
}

@Serializable
@JvmInline
value class SourceIndexSchemaVersion(val value: Int) {
    init {
        require(value > 0) { "Source index schema version must be positive" }
    }
}

@Serializable
data class PublishedWorkspaceGenerationManifest(
    val generation: WorkspaceSemanticGeneration,
    val identity: PublishedWorkspaceIdentity,
    val sourceIndexGeneration: SourceIndexGeneration,
    val sourceIndexSchemaVersion: SourceIndexSchemaVersion,
    val databaseFile: String,
    val publishedAtEpochMillis: Long,
    val repositoryOverlayFile: String? = null,
) {
    init {
        require(isCanonicalGenerationDatabasePath(databaseFile)) {
            "Published database file must be a canonical generation-relative source-index.db path"
        }
        repositoryOverlayFile?.let { file ->
            require(isCanonicalLeaf(file) && file == WORKSPACE_REPOSITORY_OVERLAY_FILE) {
                "Published repository overlay must be the contained repository-overlay.json file"
            }
        }
        require(publishedAtEpochMillis >= 0) { "Publication time must not be negative" }
    }
}

class InvalidPublishedWorkspaceGenerationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class StaleWorkspaceGenerationCommitException(
    val expectedCurrent: PublishedWorkspaceGenerationManifest?,
    val actualCurrent: PublishedWorkspaceGenerationManifest?,
) : IllegalStateException(
    "Workspace generation commit expected ${expectedCurrent?.generation?.value ?: "no current generation"} " +
        "but found ${actualCurrent?.generation?.value ?: "no current generation"}",
)

/**
 * A validated generation whose current pointer crossed the atomic visibility boundary.
 *
 * [DurabilityUncertain] means live readers can use [manifest], but the directory entry might
 * resolve to either this generation or the prior valid generation after a process or machine
 * crash. Both immutable generations remain recoverable.
 */
sealed interface WorkspaceGenerationCommit {
    val manifest: PublishedWorkspaceGenerationManifest

    data class Durable(
        override val manifest: PublishedWorkspaceGenerationManifest,
    ) : WorkspaceGenerationCommit

    data class DurabilityUncertain(
        override val manifest: PublishedWorkspaceGenerationManifest,
        val cause: Exception,
    ) : WorkspaceGenerationCommit
}

/**
 * An immutable database generation that has passed export validation but is not visible to readers.
 */
class PreparedWorkspaceGeneration internal constructor(
    val manifest: PublishedWorkspaceGenerationManifest,
    val expectedCurrent: PublishedWorkspaceGenerationManifest?,
    internal val owner: UUID,
    internal val generationDirectory: Path,
) {
    internal var state: PreparedWorkspaceGenerationState = PreparedWorkspaceGenerationState.PREPARED
}

internal enum class PreparedWorkspaceGenerationState {
    PREPARED,
    COMMITTED,
    DISCARDED,
}

/**
 * Prepares immutable workspace databases and publishes one only through an atomic current pointer.
 * A prepared generation is invisible through [current] until [commit] succeeds.
 */
class WorkspaceGenerationStore(
    private val directory: Path,
    private val exportDatabase: (Path) -> WorkspaceDatabaseExportEvidence,
    private val beforePointerCommit: () -> Unit = {},
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val directorySync: (Path) -> Unit = ::forceDirectory,
    private val deleteGenerationDirectory: (Path) -> Boolean = ::deleteDirectoryRecursively,
) {
    private val lock = Any()
    private val owner = UUID.randomUUID()
    private val normalizedDirectory = directory.toAbsolutePath().normalize()
    private val generationsDirectory = normalizedDirectory.resolve(WORKSPACE_GENERATIONS_DIRECTORY)
    private val currentPointer = normalizedDirectory.resolve(WORKSPACE_CURRENT_POINTER_FILE)
    private val commitLock = COMMIT_LOCKS.computeIfAbsent(normalizedDirectory) { Any() }
    private val validator = PublishedWorkspaceGenerationValidator(generationsDirectory, JSON)
    private val databaseRecovery = WorkspaceDatabaseRecoveryOperator(validator, directorySync)

    fun prepare(identity: PublishedWorkspaceIdentity): PreparedWorkspaceGeneration = synchronized(lock) {
        Files.createDirectories(generationsDirectory)
        val expectedCurrent = readCurrent()
        val generation = expectedCurrent?.generation?.next() ?: WorkspaceSemanticGeneration(1)
        val generationName = "generation-${generation.value}-${UUID.randomUUID()}"
        val stagingDirectory = generationsDirectory.resolve(".$generationName.preparing")
        val generationDirectory = generationsDirectory.resolve(generationName)
        var moved = false
        try {
            Files.createDirectory(stagingDirectory)
            val candidateDatabase = stagingDirectory.resolve(WORKSPACE_DATABASE_FILE)
            val evidence = exportDatabase(candidateDatabase)
            require(evidence.provesCompleteStableDatabase) {
                "Workspace database export is incomplete or moved during preparation"
            }
            require(Files.isRegularFile(candidateDatabase, LinkOption.NOFOLLOW_LINKS)) {
                "Workspace database exporter did not create a database"
            }
            validator.validateDatabase(
                database = candidateDatabase,
                expectedSchema = evidence.sourceIndexSchemaVersion,
                expectedGeneration = evidence.generationAfter,
            )
            sync(candidateDatabase)

            val repositoryOverlayFile = evidence.repositoryOverlay?.let { overlay ->
                val externalBase = validator.validateRepositoryOverlay(overlay, evidence.sourceIndexSchemaVersion)
                val publishedBase = stagingDirectory.resolve(WORKSPACE_REPOSITORY_BASE_FILE)
                Files.copy(externalBase, publishedBase)
                sync(publishedBase)
                makeImmutable(publishedBase)
                val containedOverlay = overlay.copy(
                    baseDatabase = generationDirectory.resolve(WORKSPACE_REPOSITORY_BASE_FILE)
                        .toAbsolutePath()
                        .normalize()
                        .toString(),
                )
                val descriptor = stagingDirectory.resolve(WORKSPACE_REPOSITORY_OVERLAY_FILE)
                Files.writeString(descriptor, JSON.encodeToString(containedOverlay))
                sync(descriptor)
                makeImmutable(descriptor)
                WORKSPACE_REPOSITORY_OVERLAY_FILE
            }
            makeImmutable(candidateDatabase)
            directorySync(stagingDirectory)

            Files.move(stagingDirectory, generationDirectory, StandardCopyOption.ATOMIC_MOVE)
            moved = true
            directorySync(generationsDirectory)

            val manifest = PublishedWorkspaceGenerationManifest(
                generation = generation,
                identity = identity,
                sourceIndexGeneration = evidence.generationAfter,
                sourceIndexSchemaVersion = evidence.sourceIndexSchemaVersion,
                databaseFile = "$generationName/$WORKSPACE_DATABASE_FILE",
                publishedAtEpochMillis = nowEpochMillis(),
                repositoryOverlayFile = repositoryOverlayFile,
            )
            validator.validateManifestFiles(manifest)
            PreparedWorkspaceGeneration(
                manifest = manifest,
                expectedCurrent = expectedCurrent,
                owner = owner,
                generationDirectory = generationDirectory,
            )
        } catch (failure: Throwable) {
            val failedDirectory = if (moved) generationDirectory else stagingDirectory
            if (!deleteDirectoryRecursively(failedDirectory) && Files.exists(failedDirectory, LinkOption.NOFOLLOW_LINKS)) {
                failure.addSuppressed(
                    IllegalStateException("Failed workspace generation could not be deleted: $failedDirectory"),
                )
            }
            throw failure
        }
    }

    fun commit(prepared: PreparedWorkspaceGeneration): WorkspaceGenerationCommit = synchronized(prepared) {
        requireOwnedPreparedGeneration(prepared)
        synchronized(commitLock) {
            synchronized(lock) {
                val actualCurrent = readCurrent()
                if (actualCurrent != prepared.expectedCurrent) {
                    throw StaleWorkspaceGenerationCommitException(prepared.expectedCurrent, actualCurrent)
                }
                validator.validateManifestFiles(prepared.manifest)
                beforePointerCommit()
                publishPointer(prepared.manifest) {
                    prepared.state = PreparedWorkspaceGenerationState.COMMITTED
                }
            }
        }
    }

    fun discard(prepared: PreparedWorkspaceGeneration) = synchronized(prepared) {
        require(prepared.owner == owner) { "Prepared workspace generation belongs to another store" }
        when (prepared.state) {
            PreparedWorkspaceGenerationState.PREPARED -> {
                check(
                    deleteGenerationDirectory(prepared.generationDirectory) ||
                        !Files.exists(prepared.generationDirectory, LinkOption.NOFOLLOW_LINKS),
                ) {
                    "Prepared workspace generation could not be discarded: ${prepared.generationDirectory}"
                }
                prepared.state = PreparedWorkspaceGenerationState.DISCARDED
            }
            PreparedWorkspaceGenerationState.DISCARDED -> Unit
            PreparedWorkspaceGenerationState.COMMITTED -> {
                error("A committed workspace generation cannot be discarded")
            }
        }
    }

    fun publish(identity: PublishedWorkspaceIdentity): PublishedWorkspaceGenerationManifest =
        commit(prepare(identity)).manifest

    fun current(): PublishedWorkspaceGenerationManifest? = synchronized(lock) { readCurrent() }

    /** Restores the mutable database before any [io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore] opens it. */
    fun recoverMutableWorkspaceDatabase(database: Path): WorkspaceDatabaseRecovery = synchronized(commitLock) {
        synchronized(lock) {
            databaseRecovery.recover(database, ::readCurrent)
        }
    }

    fun database(manifest: PublishedWorkspaceGenerationManifest): Path =
        validator.database(manifest)

    fun repositoryOverlay(manifest: PublishedWorkspaceGenerationManifest): Path? =
        validator.repositoryOverlay(manifest)

    private fun readCurrent(): PublishedWorkspaceGenerationManifest? {
        if (!Files.exists(currentPointer)) return null
        return try {
            require(Files.isRegularFile(currentPointer, LinkOption.NOFOLLOW_LINKS)) {
                "Workspace generation pointer is not a regular file"
            }
            val manifest = JSON.decodeFromString<PublishedWorkspaceGenerationManifest>(Files.readString(currentPointer))
            validator.validateManifestFiles(manifest)
            manifest
        } catch (failure: InvalidPublishedWorkspaceGenerationException) {
            throw failure
        } catch (failure: Throwable) {
            throw InvalidPublishedWorkspaceGenerationException(
                "Published workspace generation pointer is invalid",
                failure,
            )
        }
    }

    private fun requireOwnedPreparedGeneration(prepared: PreparedWorkspaceGeneration) {
        require(prepared.owner == owner) { "Prepared workspace generation belongs to another store" }
        require(prepared.state == PreparedWorkspaceGenerationState.PREPARED) {
            "Prepared workspace generation is already ${prepared.state.name.lowercase()}"
        }
    }

    private fun publishPointer(
        manifest: PublishedWorkspaceGenerationManifest,
        pointerReplaced: () -> Unit,
    ): WorkspaceGenerationCommit {
        Files.createDirectories(normalizedDirectory)
        val temporary = normalizedDirectory.resolve(".$WORKSPACE_CURRENT_POINTER_FILE.${UUID.randomUUID()}.tmp")
        var pointerIsCurrent = false
        try {
            Files.writeString(temporary, JSON.encodeToString(manifest))
            sync(temporary)
            Files.move(
                temporary,
                currentPointer,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            pointerIsCurrent = true
            pointerReplaced()
            return try {
                directorySync(normalizedDirectory)
                WorkspaceGenerationCommit.Durable(manifest)
            } catch (failure: Exception) {
                WorkspaceGenerationCommit.DurabilityUncertain(manifest, failure)
            }
        } finally {
            if (!pointerIsCurrent) Files.deleteIfExists(temporary)
        }
    }

    private fun sync(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
    }

    private fun makeImmutable(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ),
            )
        }.getOrElse {
            check(path.toFile().setReadOnly()) { "Published workspace generation file could not be made immutable" }
        }
    }

    private companion object {
        val COMMIT_LOCKS = ConcurrentHashMap<Path, Any>()
        val JSON = Json { encodeDefaults = true }
    }
}

private fun forceDirectory(path: Path) {
    FileChannel.open(path, StandardOpenOption.READ).use { channel -> channel.force(true) }
}

private fun deleteDirectoryRecursively(path: Path): Boolean = path.toFile().deleteRecursively()
