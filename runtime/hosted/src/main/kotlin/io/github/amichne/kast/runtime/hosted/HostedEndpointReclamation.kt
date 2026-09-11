package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Only an exclusively locked, unchanged endpoint whose recorded process is absent can be retired. */
internal object HostedEndpointReclamation {
    fun prepare(
        directory: Path,
        root: CanonicalWorkspaceRoot,
        lock: FileLock,
        observer: HostedEndpointObserver,
    ): Refinement<Unit, HostedEndpointFailure> {
        val socket = directory.resolve("host.sock")
        val descriptor = directory.resolve("endpoint.json")
        if (!Files.exists(socket, NOFOLLOW_LINKS) && !Files.exists(descriptor, NOFOLLOW_LINKS))
            return Refinement.Refined(Unit)
        observer.observe(HostedEndpointStage.RECLAMATION_ADMISSION, HostedEndpointOutcome.STARTED)
        return try {
            when (val admitted = DeadHostedEndpoint.admit(directory, root, lock)) {
                is Refinement.Rejected -> reject(observer)
                is Refinement.Refined -> {
                    observer.observe(HostedEndpointStage.RECLAMATION_ADMISSION, HostedEndpointOutcome.COMPLETED)
                    observer.observe(HostedEndpointStage.RECLAMATION_RETIREMENT, HostedEndpointOutcome.STARTED)
                    when (val retired = admitted.value.retire(lock)) {
                        is Refinement.Refined -> {
                            observer.observe(
                                HostedEndpointStage.RECLAMATION_RETIREMENT,
                                HostedEndpointOutcome.COMPLETED,
                            )
                            retired
                        }
                        is Refinement.Rejected -> {
                            observer.rejected(HostedEndpointStage.RECLAMATION_RETIREMENT, retired.failure)
                            retired
                        }
                    }
                }
            }
        } catch (_: java.io.IOException) {
            reject(observer)
        } catch (_: SecurityException) {
            reject(observer)
        }
    }

    private fun reject(observer: HostedEndpointObserver): Refinement.Rejected<HostedEndpointFailure> {
        observer.rejected(HostedEndpointStage.RECLAMATION_ADMISSION, HostedEndpointFailure.OWNERSHIP_CONFLICT)
        return Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
    }
}

/** Captures physical file identities and the strictly admitted dead process descriptor together. */
internal class DeadHostedEndpoint
private constructor(
    private val directory: HostedEndpointArtifact,
    private val lockFile: HostedEndpointArtifact,
    private val socket: HostedEndpointArtifact,
    private val descriptor: HostedEndpointArtifact,
    private val owner: DeadHostedEndpointOwner,
) {
    fun retire(lock: FileLock): Refinement<Unit, HostedEndpointFailure> {
        if (!lock.isValid || !owner.remainsAbsent()) return rejected()
        if (listOf(directory, lockFile, socket, descriptor).any { it.verify() is Refinement.Rejected })
            return rejected()
        Files.delete(socket.path)
        if (descriptor.verify() is Refinement.Rejected) return rejected()
        Files.delete(descriptor.path)
        return Refinement.Refined(Unit)
    }

    companion object {
        fun admit(
            directory: Path,
            root: CanonicalWorkspaceRoot,
            lock: FileLock,
        ): Refinement<DeadHostedEndpoint, HostedEndpointFailure> {
            if (!lock.isValid) return rejected()
            val parent =
                when (val observed = HostedEndpointArtifact.capture(directory, HostedEndpointArtifactKind.DIRECTORY)) {
                    is Refinement.Refined -> observed.value
                    is Refinement.Rejected -> return observed
                }
            val lockFile =
                when (
                    val observed =
                        HostedEndpointArtifact.capture(directory.resolve("owner.lock"), HostedEndpointArtifactKind.FILE)
                ) {
                    is Refinement.Refined -> observed.value
                    is Refinement.Rejected -> return observed
                }
            val socket =
                when (val observed = HostedEndpointArtifact.socket(directory)) {
                    is Refinement.Refined -> observed.value
                    is Refinement.Rejected -> return observed
                }
            val descriptor =
                when (val observed = HostedEndpointArtifact.descriptor(directory)) {
                    is Refinement.Refined -> observed.value
                    is Refinement.Rejected -> return observed
                }
            if (listOf(lockFile, socket, descriptor).any { it.owner != parent.owner }) return rejected()
            val owner =
                when (val parsed = DeadHostedEndpointOwner.read(descriptor.path, root, socket.path)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return parsed
                }
            if (listOf(parent, lockFile, socket, descriptor).any { it.verify() is Refinement.Rejected })
                return rejected()
            return Refinement.Refined(
                DeadHostedEndpoint(
                    directory = parent,
                    lockFile = lockFile,
                    socket = socket,
                    descriptor = descriptor,
                    owner = owner,
                )
            )
        }

        private fun rejected() = Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
    }
}
