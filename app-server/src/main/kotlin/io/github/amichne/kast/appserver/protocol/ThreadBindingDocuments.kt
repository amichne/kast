package io.github.amichne.kast.appserver.protocol

import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.CatalogDigest
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline
internal value class ThreadRecordKey private constructor(val value: String) {
    companion object {
        fun of(thread: BrokerThreadId) = ThreadRecordKey(threadRecordDigest(thread.value))
    }
}

internal fun threadRecordDigest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
        "%02x".format(it)
    }

@Serializable
internal data class ThreadRecordDocument(val version: Int, val key: String, val binding: StoredThreadBinding) {
    fun validate(expected: ThreadRecordKey): Refinement<StoredThreadBinding, ThreadCatalogStoreFailure> {
        if (version != VERSION) return Refinement.Rejected(ThreadCatalogStoreFailure.VERSION_UNSUPPORTED)
        val thread =
            BrokerThreadId.admit(binding.document.threadId)
                ?: return Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
        if (key != expected.value || ThreadRecordKey.of(thread) != expected)
            return Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
        return Refinement.Refined(binding)
    }

    companion object {
        const val VERSION = 3
    }
}

@Serializable
internal sealed interface StoredThreadBinding {
    val document: ThreadBindingDocument

    @Serializable
    @SerialName("current")
    data class Current(override val document: ThreadBindingDocument) : StoredThreadBinding

    @Serializable
    @SerialName("new_conversation_required")
    data class Legacy(override val document: ThreadBindingDocument) : StoredThreadBinding
}

@Serializable
internal data class ThreadBindingDocument(
    val threadId: String,
    val catalogDigest: String,
    val cwd: String,
    val workspaceRoot: String,
    val workspaceId: String,
    val owner: StoredThreadOwner,
) {
    /** Historical shape validation is pure and never treats an old path as current filesystem authority. */
    fun validateHistory(): Refinement<ThreadBindingDocument, ThreadCatalogStoreFailure> {
        if (BrokerThreadId.admit(threadId) == null || CatalogDigest.admit(catalogDigest) == null)
            return Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
        val paths =
            when (val result = paths()) {
                is Refinement.Rejected -> return result
                is Refinement.Refined -> result.value
            }
        if (threadRecordDigest(paths.root.toString()) != workspaceId)
            return Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
        return when (val admitted = owner.admit()) {
            is Refinement.Rejected -> admitted
            is Refinement.Refined -> Refinement.Refined(this)
        }
    }

    fun admit(): Refinement<ThreadCatalogBinding, ThreadCatalogStoreFailure> {
        val digest =
            CatalogDigest.admit(catalogDigest) ?: return Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
        val paths =
            when (val result = paths()) {
                is Refinement.Rejected -> return result
                is Refinement.Refined -> result.value
            }
        val admittedOwner =
            when (val result = owner.admit()) {
                is Refinement.Rejected -> return result
                is Refinement.Refined -> result.value
            }
        return when (val binding = ThreadCatalogBinding.admit(threadId, digest, paths.cwd, paths.root, admittedOwner)) {
            is Refinement.Rejected -> Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
            is Refinement.Refined ->
                if (binding.value.workspace.id.value == workspaceId) binding
                else Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
        }
    }

    private fun paths(): Refinement<StoredBindingPaths, ThreadCatalogStoreFailure> =
        try {
            val directory = Path.of(cwd)
            val root = Path.of(workspaceRoot)
            if (!directory.isAbsolute || !root.isAbsolute)
                Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
            else if (directory.normalize() != directory || root.normalize() != root)
                Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
            else if (!directory.startsWith(root)) Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
            else Refinement.Refined(StoredBindingPaths(directory, root))
        } catch (_: InvalidPathException) {
            Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
        }

    companion object {
        fun from(binding: ThreadCatalogBinding) =
            ThreadBindingDocument(
                binding.threadId.value,
                binding.catalogDigest.value,
                binding.workingDirectory.path.toString(),
                binding.workspace.root.path.toString(),
                binding.workspace.id.value,
                StoredThreadOwner.from(binding.owner),
            )
    }
}

/** Normalized historical paths carry no current existence or canonical-filesystem proof. */
private data class StoredBindingPaths(val cwd: Path, val root: Path)

@Serializable
internal sealed interface StoredThreadOwner {
    @Serializable @SerialName("protocolFixture") data object Fixture : StoredThreadOwner

    @Serializable
    @SerialName("installation")
    data class Installation(val installationId: String, val stateEpoch: String) : StoredThreadOwner

    fun admit(): Refinement<ThreadBindingOwner, ThreadCatalogStoreFailure> =
        when (this) {
            Fixture -> Refinement.Refined(ThreadBindingOwner.ProtocolFixture)
            is Installation ->
                when (val result = ThreadBindingOwner.admit(installationId, stateEpoch)) {
                    is Refinement.Rejected -> Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
                    is Refinement.Refined -> result
                }
        }

    companion object {
        fun from(owner: ThreadBindingOwner): StoredThreadOwner =
            when (owner) {
                ThreadBindingOwner.ProtocolFixture -> Fixture
                is ThreadBindingOwner.Installation ->
                    Installation(owner.installationId.value, owner.stateEpoch.value.toString())
            }
    }
}

@Serializable internal data class ThreadStoreLayout(val version: Int)

@Serializable
internal data class LegacyThreadStoreDocument(val version: Int, val bindings: List<LegacyThreadBindingDocument>)

@Serializable
internal data class LegacyThreadBindingDocument(
    val threadId: String,
    val catalogDigest: String,
    val cwd: String,
    val workspaceRoot: String,
    val workspaceId: String,
    val ownerKind: String,
    val installationId: String? = null,
    val stateEpoch: String? = null,
) {
    fun admitHistory(): Refinement<ThreadBindingDocument, ThreadCatalogStoreFailure> {
        val owner =
            when (ownerKind) {
                "protocolFixture" ->
                    if (installationId == null && stateEpoch == null) StoredThreadOwner.Fixture else return rejected()
                "installation" ->
                    StoredThreadOwner.Installation(installationId ?: return rejected(), stateEpoch ?: return rejected())
                else -> return rejected()
            }
        return ThreadBindingDocument(threadId, catalogDigest, cwd, workspaceRoot, workspaceId, owner).validateHistory()
    }

    private fun rejected() = Refinement.Rejected(ThreadCatalogStoreFailure.BINDING_REJECTED)
}
