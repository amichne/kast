package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A live native query keeps its private state until its exact child has been reaped. */
internal class ContainerQueryLease
private constructor(
    val workspace: AdmittedWorkspace,
    private val platform: ContainerPlatform,
    native: NativeLock,
) : AutoCloseable {
    private var state: State = State.Held(native)

    private sealed interface State {
        class Held(val native: NativeLock) : State

        data object Closed : State
    }

    private class NativeLock(val path: Path, val channel: FileChannel, val lock: FileLock) {
        var readers = 1
    }

    fun admitGeneration(): Refinement<AdmittedContainer, ContainerFailure> = whileActive {
        ContainerStore(workspace.directory.parent.parent, platform).admitGeneration(workspace)
    }

    @Synchronized
    fun <T> whileActive(action: () -> Refinement<T, ContainerFailure>): Refinement<T, ContainerFailure> =
        when (val current = state) {
            State.Closed -> Refinement.Rejected(ContainerFailure.LEASE_CLOSED)
            is State.Held ->
                if (current.native.lock.isValid) action() else Refinement.Rejected(ContainerFailure.LEASE_CLOSED)
        }

    @Synchronized
    override fun close() {
        when (val current = state) {
            State.Closed -> Unit
            is State.Held -> {
                state = State.Closed
                release(current.native)
            }
        }
    }

    companion object {
        // FileChannel forbids overlapping locks within a JVM, even for shared readers.
        // Keep one native lock until the last independently closeable request releases it.
        private val held = mutableMapOf<Path, NativeLock>()

        /** Shared admission spans initial staging; unrelated roots never wait for each other's copies. */
        fun <T> duringStoreAdmission(
            root: Path,
            action: () -> Refinement<T, ContainerFailure>,
        ): Refinement<T, ContainerFailure> = withStoreLifetime(root, true, action)

        /** The exterior lock survives deletion, so a waiting admission cannot recreate a removed store. */
        fun <T> duringStoreRemoval(
            root: Path,
            action: () -> Refinement<T, ContainerFailure>,
        ): Refinement<T, ContainerFailure> = withStoreLifetime(root, false, action)

        private fun <T> withStoreLifetime(
            root: Path,
            shared: Boolean,
            action: () -> Refinement<T, ContainerFailure>,
        ): Refinement<T, ContainerFailure> = containerFiles {
            if (root.parent.toRealPath() != root.parent)
                return@containerFiles Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
            when (val native = acquireNative(root.resolveSibling(".kast-workspaces.lifetime.lock"), shared)) {
                is Refinement.Rejected -> native
                is Refinement.Refined ->
                    try {
                        action()
                    } finally {
                        release(native.value)
                    }
            }
        }

        fun acquire(container: AdmittedContainer): Refinement<ContainerQueryLease, ContainerFailure> =
            acquireGeneration(container, true)

        /** Durable ownership can be leased even when the disposable generation needs repair. */
        fun acquire(
            workspace: AdmittedWorkspace,
            platform: ContainerPlatform,
        ): Refinement<ContainerQueryLease, ContainerFailure> = acquireWorkspace(workspace, platform, true)

        /** The registry owner retains every exclusive lease until its whole retirement completes. */
        fun <T> whileAllIdle(
            containers: List<AdmittedContainer>,
            action: () -> Refinement<T, ContainerFailure>,
        ): Refinement<T, ContainerFailure> {
            val leases = mutableListOf<ContainerQueryLease>()
            try {
                for (container in containers) {
                    // Legacy retirement still needs its migration owner and original registry lock.
                    if (container.owned.generation is ContainerGenerationSource.Legacy)
                        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
                    when (val lease = acquireGeneration(container, false)) {
                        is Refinement.Rejected -> return lease
                        is Refinement.Refined -> leases += lease.value
                    }
                }
                return action()
            } finally {
                leases.asReversed().forEach(ContainerQueryLease::close)
            }
        }

        /** Removal must prove both Host shutdown (caller) and the absence of Control readers (this lease). */
        fun <T> whileIdle(
            container: AdmittedContainer,
            action: () -> Refinement<T, ContainerFailure>,
        ): Refinement<T, ContainerFailure> =
            when (val lease = acquireGeneration(container, false)) {
                is Refinement.Rejected -> lease
                is Refinement.Refined -> lease.value.use { action() }
            }

        /** A detached reclamation receipt retains identity after generation files disappear. */
        fun <T> whileReclaiming(
            store: ContainerStore,
            id: ContainerId,
            action: () -> Refinement<T, ContainerFailure>,
        ): Refinement<T, ContainerFailure> = containerFiles {
            val locks =
                when (val private = privateContainerDirectory(store.root.resolve("locks"))) {
                    is Refinement.Rejected -> return@containerFiles private
                    is Refinement.Refined -> private.value
                }
            when (val native = acquireNative(locks.resolve("${id.text}.queries.lock"), false)) {
                is Refinement.Rejected -> native
                is Refinement.Refined ->
                    try {
                        action()
                    } finally {
                        release(native.value)
                    }
            }
        }

        private fun <T> withLegacyRegistryLock(
            workspace: AdmittedWorkspace,
            action: () -> Refinement<T, ContainerFailure>,
        ): Refinement<T, ContainerFailure> =
            when (workspace.generation) {
                ContainerGenerationSource.Current -> action()
                is ContainerGenerationSource.Legacy -> withContainerLock(workspace.directory.parent.parent, action)
            }

        private fun acquireGeneration(
            container: AdmittedContainer,
            shared: Boolean,
        ): Refinement<ContainerQueryLease, ContainerFailure> =
            when (val lease = acquireWorkspace(container.owned, container.runtime.manifest.platform, shared)) {
                is Refinement.Rejected -> lease
                is Refinement.Refined ->
                    retain(lease.value) {
                        when (val current = lease.value.admitGeneration()) {
                            is Refinement.Rejected -> current
                            is Refinement.Refined ->
                                if (current.value.runtime.archiveSha256 == container.runtime.archiveSha256)
                                    Refinement.Refined(Unit)
                                else Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
                        }
                    }
            }

        private fun acquireWorkspace(
            workspace: AdmittedWorkspace,
            platform: ContainerPlatform,
            shared: Boolean,
        ): Refinement<ContainerQueryLease, ContainerFailure> =
            withLegacyRegistryLock(workspace) {
                containerFiles {
                    val path =
                        when (val admitted = containerQueryLockPath(workspace)) {
                            is Refinement.Rejected -> return@containerFiles admitted
                            is Refinement.Refined -> admitted.value
                        }
                    when (val native = acquireNative(path, shared)) {
                        is Refinement.Rejected -> native
                        is Refinement.Refined -> {
                            val lease = ContainerQueryLease(workspace, platform, native.value)
                            retain(lease) { bindWorkspace(workspace, platform) }
                        }
                    }
                }
            }

        @Synchronized
        private fun acquireNative(path: Path, shared: Boolean): Refinement<NativeLock, ContainerFailure> {
            val existing = held[path]
            if (existing != null) {
                return if (shared && existing.lock.isShared && existing.lock.isValid) {
                    existing.readers++
                    Refinement.Refined(existing)
                } else Refinement.Rejected(ContainerFailure.BUSY)
            }
            val channel =
                FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    NOFOLLOW_LINKS,
                )
            val lock =
                try {
                    channel.tryLock(0, Long.MAX_VALUE, shared)
                } catch (_: java.nio.channels.OverlappingFileLockException) {
                    null
                } catch (failure: java.io.IOException) {
                    channel.close()
                    throw failure
                }
            return if (lock == null) {
                channel.close()
                Refinement.Rejected(ContainerFailure.BUSY)
            } else {
                val native = NativeLock(path, channel, lock)
                held[path] = native
                Refinement.Refined(native)
            }
        }

        @Synchronized
        private fun release(native: NativeLock) {
            native.readers--
            if (native.readers == 0) {
                try {
                    if (native.lock.isValid) native.lock.release()
                } finally {
                    held.remove(native.path)
                    native.channel.close()
                }
            }
        }

        /** Re-admit after the stable lock: a removed or replaced directory cannot borrow the old read proof. */
        private fun bindWorkspace(
            workspace: AdmittedWorkspace,
            platform: ContainerPlatform,
        ): Refinement<Unit, ContainerFailure> {
            val store = ContainerStore(workspace.directory.parent.parent, platform)
            return when (val read = store.readWorkspace(workspace.directory)) {
                is Refinement.Rejected -> read
                is Refinement.Refined ->
                    if (read.value.record() == workspace.record() && read.value.generation == workspace.generation)
                        Refinement.Refined(Unit)
                    else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            }
        }

        private fun retain(
            lease: ContainerQueryLease,
            admission: () -> Refinement<Unit, ContainerFailure>,
        ): Refinement<ContainerQueryLease, ContainerFailure> {
            var retained = false
            return try {
                val current =
                    when (val admitted = admission()) {
                        is Refinement.Rejected -> admitted
                        is Refinement.Refined -> Refinement.Refined(lease)
                    }
                retained = current is Refinement.Refined
                current
            } finally {
                if (!retained) lease.close()
            }
        }
    }
}
