package io.github.amichne.kast.indexstore.snapshot

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val generationBefore: Long,
    val generationAfter: Long,
    val incompleteModuleCount: Int,
    val pendingUpdateCount: Int,
) {
    init {
        require(generationBefore >= 0) { "Source index generation must not be negative" }
        require(generationAfter >= 0) { "Source index generation must not be negative" }
        require(incompleteModuleCount >= 0) { "Incomplete module count must not be negative" }
        require(pendingUpdateCount >= 0) { "Pending update count must not be negative" }
    }

    val provesCompleteStableDatabase: Boolean
        get() = generationBefore == generationAfter && incompleteModuleCount == 0 && pendingUpdateCount == 0
}

@Serializable
data class PublishedWorkspaceGenerationManifest(
    val generation: WorkspaceSemanticGeneration,
    val identity: PublishedWorkspaceIdentity,
    val databaseFile: String,
    val publishedAtEpochMillis: Long,
) {
    init {
        require(databaseFile.isNotBlank()) { "Published database file must not be blank" }
        require(publishedAtEpochMillis >= 0) { "Publication time must not be negative" }
    }
}

/**
 * Publishes an immutable workspace database before atomically replacing the current pointer.
 * A failed or interrupted publication cannot change the generation visible through [current].
 */
class WorkspaceGenerationStore(
    private val directory: Path,
    private val exportDatabase: (Path) -> WorkspaceDatabaseExportEvidence,
    private val beforePointerCommit: () -> Unit = {},
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val generationsDirectory = directory.resolve("generations")
    private val currentPointer = directory.resolve("current.json")

    fun publish(identity: PublishedWorkspaceIdentity): PublishedWorkspaceGenerationManifest = synchronized(lock) {
        Files.createDirectories(generationsDirectory)
        val generation = current()?.generation?.next() ?: WorkspaceSemanticGeneration(1)
        val candidate = generationsDirectory.resolve(".${UUID.randomUUID()}.candidate.db")
        try {
            val evidence = exportDatabase(candidate)
            require(evidence.provesCompleteStableDatabase) {
                "Workspace database export is incomplete or moved during publication"
            }
            require(Files.isRegularFile(candidate)) { "Workspace database exporter did not create a database" }
            sync(candidate)

            val databaseFile = "generation-${generation.value}-${UUID.randomUUID()}.db"
            val database = generationsDirectory.resolve(databaseFile)
            Files.move(candidate, database, StandardCopyOption.ATOMIC_MOVE)
            makeImmutable(database)
            syncDirectory(generationsDirectory)

            val manifest = PublishedWorkspaceGenerationManifest(
                generation = generation,
                identity = identity,
                databaseFile = databaseFile,
                publishedAtEpochMillis = nowEpochMillis(),
            )
            beforePointerCommit()
            publishPointer(manifest)
            manifest
        } finally {
            Files.deleteIfExists(candidate)
            // A database moved before an interruption is an unreferenced immutable candidate.
            // Keep it so pointer replacement remains the only visibility boundary.
        }
    }

    fun current(): PublishedWorkspaceGenerationManifest? = synchronized(lock) {
        if (!Files.exists(currentPointer)) return null
        require(Files.isRegularFile(currentPointer)) { "Workspace generation pointer is not a file" }
        val manifest = JSON.decodeFromString<PublishedWorkspaceGenerationManifest>(Files.readString(currentPointer))
        require(Files.isRegularFile(database(manifest))) { "Published workspace database is unavailable" }
        manifest
    }

    fun database(manifest: PublishedWorkspaceGenerationManifest): Path =
        generationsDirectory.resolve(manifest.databaseFile).toAbsolutePath().normalize().also { database ->
            require(database.parent == generationsDirectory.toAbsolutePath().normalize()) {
                "Published workspace database must stay inside its generation directory"
            }
        }

    private fun publishPointer(manifest: PublishedWorkspaceGenerationManifest) {
        Files.createDirectories(directory)
        val temporary = directory.resolve(".${currentPointer.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, JSON.encodeToString(manifest))
            sync(temporary)
            Files.move(
                temporary,
                currentPointer,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(directory)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sync(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
    }

    private fun syncDirectory(path: Path) {
        runCatching {
            FileChannel.open(path, StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
    }

    private fun makeImmutable(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ),
            )
        }.getOrElse {
            check(path.toFile().setReadOnly()) { "Published workspace database could not be made immutable" }
        }
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
