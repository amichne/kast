package io.github.amichne.kast.appserver.protocol

import io.github.amichne.kast.appserver.WorkspaceRegistration
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.core.CatalogDigest
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

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

    data class Rejected(val failure: ThreadCatalogStoreFailure) : ThreadStoreRead
}

internal sealed interface ThreadStoreWrite {
    data object Written : ThreadStoreWrite

    data class Rejected(val failure: ThreadCatalogStoreFailure) : ThreadStoreWrite
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
        return if (previous == null || previous.sameBinding(binding)) ThreadStoreWrite.Written
        else ThreadStoreWrite.Rejected(ThreadCatalogStoreFailure.BINDING_CONFLICT)
    }
}

@Serializable
internal enum class ThreadCatalogStoreFailure {
    PATH_REJECTED,
    DOCUMENT_UNREADABLE,
    DOCUMENT_TOO_LARGE,
    DOCUMENT_MALFORMED,
    VERSION_UNSUPPORTED,
    DUPLICATE_THREAD_ID,
    BINDING_REJECTED,
    BINDING_CONFLICT,
    STORE_BUSY,
    STORE_REJECTED,
    MIGRATION_REJECTED,
    NEW_CONVERSATION_REQUIRED,
}

internal sealed interface FileThreadCatalogStoreOpen {
    data class Opened(val store: FileThreadCatalogStore) : FileThreadCatalogStoreOpen

    data class Rejected(val failure: ThreadCatalogStoreFailure) : FileThreadCatalogStoreOpen
}
