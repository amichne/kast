package io.github.amichne.kast.appserver.protocol

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.WorkspaceRegistration
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.core.CatalogDigest
import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@JvmInline
internal value class BindingInstallationId private constructor(val value: String) {
    companion object {
        internal fun admit(raw: String): BindingInstallationId? =
            raw.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,256}")) }?.let(::BindingInstallationId)
    }
}

@JvmInline
internal value class BindingStateEpoch private constructor(val value: UUID) {
    companion object {
        internal fun admit(raw: String): BindingStateEpoch? =
            try {
                UUID.fromString(raw).takeIf { it.toString() == raw }?.let(::BindingStateEpoch)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}

internal enum class ThreadBindingOwnerFailure {
    INSTALLATION_REJECTED,
    EPOCH_REJECTED,
}

internal sealed interface ThreadBindingOwner {
    data object ProtocolFixture : ThreadBindingOwner

    data class Installation(val installationId: BindingInstallationId, val stateEpoch: BindingStateEpoch) :
        ThreadBindingOwner

    companion object {
        internal fun admit(
            installationId: String,
            stateEpoch: String,
        ): Refinement<Installation, ThreadBindingOwnerFailure> {
            val installation =
                BindingInstallationId.admit(installationId)
                    ?: return Refinement.Rejected(ThreadBindingOwnerFailure.INSTALLATION_REJECTED)
            val epoch =
                BindingStateEpoch.admit(stateEpoch)
                    ?: return Refinement.Rejected(ThreadBindingOwnerFailure.EPOCH_REJECTED)
            return Refinement.Refined(Installation(installation, epoch))
        }
    }
}

internal enum class ThreadCatalogBindingFailure {
    INVALID_THREAD_ID,
    WORKING_DIRECTORY_REJECTED,
    WORKSPACE_ROOT_REJECTED,
    WORKING_DIRECTORY_OUTSIDE_WORKSPACE,
}

internal class ThreadCatalogBinding
private constructor(
    val threadId: BrokerThreadId,
    val catalogDigest: CatalogDigest,
    val workingDirectory: CanonicalBrokerDirectory,
    val workspace: WorkspaceRegistration,
    val owner: ThreadBindingOwner,
) {
    internal fun sameBinding(other: ThreadCatalogBinding): Boolean =
        threadId == other.threadId &&
            catalogDigest == other.catalogDigest &&
            workingDirectory == other.workingDirectory &&
            workspace == other.workspace &&
            owner == other.owner

    companion object {
        internal fun admit(
            threadId: String,
            catalogDigest: CatalogDigest,
            workingDirectory: Path,
            workspaceRoot: Path = workingDirectory,
            owner: ThreadBindingOwner = ThreadBindingOwner.ProtocolFixture,
        ): Refinement<ThreadCatalogBinding, ThreadCatalogBindingFailure> {
            val admittedThread =
                BrokerThreadId.admit(threadId)
                    ?: return Refinement.Rejected(ThreadCatalogBindingFailure.INVALID_THREAD_ID)
            val admittedDirectory =
                CanonicalBrokerDirectory.admit(workingDirectory)
                    ?: return Refinement.Rejected(ThreadCatalogBindingFailure.WORKING_DIRECTORY_REJECTED)
            val admittedRoot =
                CanonicalBrokerDirectory.admit(workspaceRoot)
                    ?: return Refinement.Rejected(ThreadCatalogBindingFailure.WORKSPACE_ROOT_REJECTED)
            if (!admittedDirectory.path.startsWith(admittedRoot.path)) {
                return Refinement.Rejected(ThreadCatalogBindingFailure.WORKING_DIRECTORY_OUTSIDE_WORKSPACE)
            }
            return Refinement.Refined(
                ThreadCatalogBinding(
                    admittedThread,
                    catalogDigest,
                    admittedDirectory,
                    WorkspaceRegistration(admittedRoot),
                    owner,
                )
            )
        }
    }
}

internal sealed interface ThreadStoreRead {
    data class Found(val binding: ThreadCatalogBinding) : ThreadStoreRead

    data object Missing : ThreadStoreRead

    data object Rejected : ThreadStoreRead
}

internal enum class ThreadStoreWrite {
    WRITTEN,
    REJECTED,
}

internal interface ThreadCatalogStore {
    suspend fun read(threadId: String): ThreadStoreRead

    suspend fun write(binding: ThreadCatalogBinding): ThreadStoreWrite
}

internal class MemoryThreadCatalogStore : ThreadCatalogStore {
    private val bindings = ConcurrentHashMap<String, ThreadCatalogBinding>()

    override suspend fun read(threadId: String): ThreadStoreRead =
        bindings[threadId]?.let(ThreadStoreRead::Found) ?: ThreadStoreRead.Missing

    override suspend fun write(binding: ThreadCatalogBinding): ThreadStoreWrite {
        val previous = bindings.putIfAbsent(binding.threadId.value, binding)
        return if (previous == null || previous.sameBinding(binding)) ThreadStoreWrite.WRITTEN
        else ThreadStoreWrite.REJECTED
    }
}

internal enum class ThreadCatalogStoreFailure {
    PATH_REJECTED,
    DOCUMENT_UNREADABLE,
    DOCUMENT_TOO_LARGE,
    DOCUMENT_MALFORMED,
    VERSION_UNSUPPORTED,
    DUPLICATE_THREAD_ID,
    BINDING_REJECTED,
}

internal sealed interface FileThreadCatalogStoreOpen {
    data class Opened(val store: FileThreadCatalogStore) : FileThreadCatalogStoreOpen

    data class Rejected(val failure: ThreadCatalogStoreFailure) : FileThreadCatalogStoreOpen
}

/** Versioned durable proof for the catalog and canonical directory bound to each Codex thread. */
internal class FileThreadCatalogStore
private constructor(
    private val path: Path,
    initial: Map<String, ThreadCatalogBinding>,
) : ThreadCatalogStore {
    private val mutex = Mutex()
    private var bindings = initial

    override suspend fun read(threadId: String): ThreadStoreRead = mutex.withLock {
        bindings[threadId]?.let(ThreadStoreRead::Found) ?: ThreadStoreRead.Missing
    }

    override suspend fun write(binding: ThreadCatalogBinding): ThreadStoreWrite = mutex.withLock {
        bindings[binding.threadId.value]?.let { previous ->
            return@withLock if (previous.sameBinding(binding)) ThreadStoreWrite.WRITTEN else ThreadStoreWrite.REJECTED
        }
        val updated = bindings + (binding.threadId.value to binding)
        val written = withContext(Dispatchers.IO) { flush(updated) }
        if (written) {
            bindings = updated
            ThreadStoreWrite.WRITTEN
        } else {
            ThreadStoreWrite.REJECTED
        }
    }

    private fun flush(updated: Map<String, ThreadCatalogBinding>): Boolean {
        val parent = path.parent ?: return false
        if (!admittedParent(parent) || Files.isSymbolicLink(path)) return false
        val temporary = parent.resolve(".${path.fileName}.${UUID.randomUUID()}.tmp")
        val document =
            ThreadStoreDocument(
                version = STORE_VERSION,
                bindings =
                    updated.values
                        .sortedBy { binding -> binding.threadId.value }
                        .map { binding ->
                            ThreadStoreBindingDocument(
                                threadId = binding.threadId.value,
                                catalogDigest = binding.catalogDigest.value,
                                cwd = binding.workingDirectory.path.toString(),
                                workspaceRoot = binding.workspace.root.path.toString(),
                                workspaceId = binding.workspace.id.value,
                                ownerKind =
                                    if (binding.owner is ThreadBindingOwner.Installation) "installation"
                                    else "protocolFixture",
                                installationId =
                                    (binding.owner as? ThreadBindingOwner.Installation)?.installationId?.value,
                                stateEpoch =
                                    (binding.owner as? ThreadBindingOwner.Installation)?.stateEpoch?.value?.toString(),
                            )
                        },
            )
        val bytes = (STORE_JSON.encodeToString(document) + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAXIMUM_STORE_BYTES) return false
        return try {
            FileChannel.open(
                    temporary,
                    setOf(
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
                .use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                return false
            }
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            val attributes =
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            attributes.isRegularFile && !attributes.isSymbolicLink
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            try {
                Files.deleteIfExists(temporary)
            } catch (_: IOException) {
                // A temporary file is never accepted as the authoritative catalog.
            } catch (_: SecurityException) {
                // A temporary file is never accepted as the authoritative catalog.
            }
        }
    }

    companion object {
        internal fun open(path: Path): FileThreadCatalogStoreOpen {
            if (!path.isAbsolute || path.normalize() != path) return rejected(ThreadCatalogStoreFailure.PATH_REJECTED)
            val parent = path.parent
            if (parent == null || !admittedParent(parent) || Files.isSymbolicLink(path)) {
                return rejected(ThreadCatalogStoreFailure.PATH_REJECTED)
            }
            val raw =
                when (val read = readDocument(path)) {
                    ThreadStoreDocumentRead.Missing ->
                        return FileThreadCatalogStoreOpen.Opened(FileThreadCatalogStore(path, emptyMap()))
                    ThreadStoreDocumentRead.TooLarge -> return rejected(ThreadCatalogStoreFailure.DOCUMENT_TOO_LARGE)
                    ThreadStoreDocumentRead.Rejected -> return rejected(ThreadCatalogStoreFailure.DOCUMENT_UNREADABLE)
                    is ThreadStoreDocumentRead.Read -> read.text
                }
            val document =
                try {
                    STORE_JSON.decodeFromString<ThreadStoreDocument>(raw)
                } catch (_: SerializationException) {
                    return rejected(ThreadCatalogStoreFailure.DOCUMENT_MALFORMED)
                } catch (_: IllegalArgumentException) {
                    return rejected(ThreadCatalogStoreFailure.DOCUMENT_MALFORMED)
                }
            if (document.version != STORE_VERSION) {
                return rejected(ThreadCatalogStoreFailure.VERSION_UNSUPPORTED)
            }
            if (document.bindings.map(ThreadStoreBindingDocument::threadId).toSet().size != document.bindings.size) {
                return rejected(ThreadCatalogStoreFailure.DUPLICATE_THREAD_ID)
            }
            val admitted = linkedMapOf<String, ThreadCatalogBinding>()
            document.bindings.forEach { boundary ->
                val digest =
                    CatalogDigest.admit(boundary.catalogDigest)
                        ?: return rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
                val cwd =
                    try {
                        Path.of(boundary.cwd)
                    } catch (_: RuntimeException) {
                        return rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
                    }
                val owner =
                    when (boundary.ownerKind) {
                        "protocolFixture" ->
                            if (boundary.installationId == null && boundary.stateEpoch == null)
                                ThreadBindingOwner.ProtocolFixture
                            else return rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
                        "installation" ->
                            when (
                                val admission =
                                    ThreadBindingOwner.admit(boundary.installationId ?: "", boundary.stateEpoch ?: "")
                            ) {
                                is Refinement.Refined -> admission.value
                                is Refinement.Rejected -> return rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
                            }
                        else -> return rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
                    }
                val root =
                    try {
                        Path.of(boundary.workspaceRoot)
                    } catch (_: RuntimeException) {
                        return rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
                    }
                val binding =
                    when (val refinement = ThreadCatalogBinding.admit(boundary.threadId, digest, cwd, root, owner)) {
                        is Refinement.Refined -> refinement.value
                        is Refinement.Rejected -> return rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
                    }
                if (boundary.workspaceId != binding.workspace.id.value)
                    return rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
                admitted[binding.threadId.value] = binding
            }
            return FileThreadCatalogStoreOpen.Opened(FileThreadCatalogStore(path, admitted))
        }

        private fun readDocument(path: Path): ThreadStoreDocumentRead {
            val before =
                try {
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (_: NoSuchFileException) {
                    return ThreadStoreDocumentRead.Missing
                } catch (_: IOException) {
                    return ThreadStoreDocumentRead.Rejected
                } catch (_: SecurityException) {
                    return ThreadStoreDocumentRead.Rejected
                }
            if (!before.isRegularFile || before.isSymbolicLink || before.fileKey() == null)
                return ThreadStoreDocumentRead.Rejected
            if (before.size() > MAXIMUM_STORE_BYTES) return ThreadStoreDocumentRead.TooLarge
            return try {
                val output = ByteArrayOutputStream()
                Files.newByteChannel(
                        path,
                        setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                    )
                    .use { channel ->
                        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = channel.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAXIMUM_STORE_BYTES) {
                                return ThreadStoreDocumentRead.TooLarge
                            }
                            output.write(buffer.array(), 0, count)
                            buffer.clear()
                        }
                    }
                val after =
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                if (!after.isRegularFile || after.isSymbolicLink || after.fileKey() != before.fileKey()) {
                    ThreadStoreDocumentRead.Rejected
                } else {
                    ThreadStoreDocumentRead.Read(output.toString(Charsets.UTF_8))
                }
            } catch (_: IOException) {
                ThreadStoreDocumentRead.Rejected
            } catch (_: SecurityException) {
                ThreadStoreDocumentRead.Rejected
            }
        }

        private fun admittedParent(parent: Path): Boolean =
            try {
                parent.toRealPath() == parent && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }

        private fun rejected(failure: ThreadCatalogStoreFailure): FileThreadCatalogStoreOpen.Rejected =
            FileThreadCatalogStoreOpen.Rejected(failure)

        private const val STORE_VERSION = 2
        private const val MAXIMUM_STORE_BYTES = BrokerOperationalLimits.maximumThreadStoreBytes
        private val STORE_JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }
    }
}

@Serializable
private data class ThreadStoreDocument(
    val version: Int,
    val bindings: List<ThreadStoreBindingDocument>,
)

@Serializable
private data class ThreadStoreBindingDocument(
    val threadId: String,
    val catalogDigest: String,
    val cwd: String,
    val workspaceRoot: String,
    val workspaceId: String,
    val ownerKind: String,
    val installationId: String? = null,
    val stateEpoch: String? = null,
)

private sealed interface ThreadStoreDocumentRead {
    data class Read(val text: String) : ThreadStoreDocumentRead

    data object Missing : ThreadStoreDocumentRead

    data object TooLarge : ThreadStoreDocumentRead

    data object Rejected : ThreadStoreDocumentRead
}
