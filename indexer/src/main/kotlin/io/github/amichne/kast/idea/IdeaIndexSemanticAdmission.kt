package io.github.amichne.kast.idea

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import io.github.amichne.kast.evidence.contract.WorkspacePublicationCommit
import io.github.amichne.kast.idea.backend.semantic.WorkspaceSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class IdeaIndexSemanticAdmission(
    private val project: Project,
    private val inspectProject: () -> Inspection = {
        inspectSemanticAdmission(project, IdeaSemanticAdmissionOperations.idea())
    },
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val maxWaitMillis: Long = TimeUnit.MINUTES.toMillis(5),
    private val pollIntervalMillis: Long = 250L,
) : WorkspaceSemanticReadAuthority {
    private val status = AtomicReference<Status>(Status.Pending("compiler-backed semantic admission has not started"))
    private val revision = AtomicLong(0)
    private val transitionLock = ReentrantLock()
    private val readersDrained = transitionLock.newCondition()
    private var activeReaders = 0
    private var activeMutation = false

    init {
        require(maxWaitMillis >= 0) { "maxWaitMillis must not be negative" }
        require(pollIntervalMillis > 0) { "pollIntervalMillis must be positive" }
    }

    fun await(cancelled: () -> Boolean) {
        val startedAtNanos = nanoTime()
        try {
            while (true) {
                if (cancelled() || project.isDisposed || Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Kast source-index semantic admission was cancelled")
                }
                val inspection = ReadAction
                    .nonBlocking(Callable(inspectProject))
                    .expireWhen(cancelled)
                    .executeSynchronously()
                val pending = when (inspection) {
                    Inspection.Ready -> {
                        status.set(Status.Pending("compiler model is ready; workspace generation is not verified"))
                        return
                    }
                    is Inspection.Pending -> inspection.also {
                        status.set(Status.Pending(it.detail))
                    }
                }
                val elapsedMillis = elapsedMillisSince(startedAtNanos)
                if (elapsedMillis >= maxWaitMillis) {
                    throw IllegalStateException(
                        "Kast source index cannot become READY because compiler-backed semantic admission timed out: " +
                            pending.detail,
                    )
                }
                try {
                    pause(minOf(pollIntervalMillis, maxWaitMillis - elapsedMillis))
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
            }
        } catch (failure: Throwable) {
            status.set(
                Status.Failed(
                    failure.message?.takeIf(String::isNotBlank)
                        ?: failure::class.qualifiedName.orEmpty(),
                ),
            )
            throw failure
        }
    }

    override fun status(): Status = status.get()

    fun fail(detail: String) {
        transitionLock.withLock {
            revision.incrementAndGet()
            status.set(Status.Failed(detail))
        }
    }

    fun dirty(detail: String) {
        require(detail.isNotBlank()) { "Dirty semantic-admission detail must not be blank" }
        transitionLock.withLock {
            revision.incrementAndGet()
            status.set(Status.Pending(detail))
        }
    }

    fun beginRecoveryAudit(detail: String): RecoveryAuditToken {
        require(detail.isNotBlank()) { "Recovery-audit detail must not be blank" }
        return transitionLock.withLock {
            val admissionStatus = status.get()
            val ready = admissionStatus as? Status.Ready
                ?: throw RecoveryAuditAdmissionUnavailableException(admissionStatus)
            val auditRevision = revision.incrementAndGet()
            status.set(Status.Pending(detail))
            while (activeReaders > 0 || activeMutation) readersDrained.await()
            val currentRevision = revision.get()
            if (currentRevision != auditRevision) {
                throw RecoveryAuditAdmissionInvalidatedException(
                    expectedRevision = auditRevision,
                    actualRevision = currentRevision,
                )
            }
            RecoveryAuditToken(
                revision = auditRevision,
                generation = ready.generation,
            )
        }
    }

    fun restoreReadyAfterRecoveryAudit(token: RecoveryAuditToken): RecoveryAuditRestoration =
        transitionLock.withLock {
            if (revision.get() != token.revision || status.get() !is Status.Pending) {
                return@withLock RecoveryAuditRestoration.Invalidated
            }
            status.set(Status.Ready(token.generation))
            RecoveryAuditRestoration.Restored(token.generation)
        }

    fun beginReconciliation(detail: String): ReconciliationToken {
        require(detail.isNotBlank()) { "Reconciliation detail must not be blank" }
        return transitionLock.withLock {
            val nextRevision = revision.incrementAndGet()
            status.set(Status.Pending(detail))
            while (activeReaders > 0 || activeMutation) readersDrained.await()
            ReconciliationToken(nextRevision)
        }
    }

    fun beginMutation(detail: String): WorkspaceMutationToken {
        require(detail.isNotBlank()) { "Workspace mutation detail must not be blank" }
        return transitionLock.withLock {
            val admissionStatus = status.get()
            val ready = admissionStatus as? Status.Ready
                ?: throw WorkspaceMutationAdmissionUnavailableException(admissionStatus)
            val mutationRevision = revision.incrementAndGet()
            status.set(Status.Pending(detail))
            while (activeReaders > 0 || activeMutation) readersDrained.await()
            val currentRevision = revision.get()
            if (currentRevision != mutationRevision) {
                throw WorkspaceMutationAdmissionInvalidatedException(
                    expectedRevision = mutationRevision,
                    actualRevision = currentRevision,
                )
            }
            activeMutation = true
            WorkspaceMutationToken(ready.generation, ::releaseMutation)
        }
    }

    fun publishReady(
        token: ReconciliationToken,
        publish: () -> WorkspacePublicationCommit,
    ): ReadyPublication {
        if (transitionLock.withLock { revision.get() != token.revision }) {
            return ReadyPublication.InvalidatedBeforeCommit
        }
        val commit = publish()
        return transitionLock.withLock {
            if (revision.get() != token.revision) {
                return@withLock ReadyPublication.InvalidatedAfterCommit(commit)
            }
            status.set(Status.Ready(commit.publication))
            ReadyPublication.Admitted(commit)
        }
    }

    override fun openRead(): WorkspaceReadToken = transitionLock.withLock {
        val ready = status.get() as? Status.Ready
            ?: error("Workspace semantic generation is not READY")
        activeReaders += 1
        WorkspaceReadToken(
            revision = revision.get(),
            generation = ready.generation,
            release = ::releaseRead,
        )
    }

    override fun isReadCurrent(token: WorkspaceReadToken): Boolean = transitionLock.withLock {
        val ready = status.get() as? Status.Ready ?: return@withLock false
        revision.get() == token.revision && ready.generation == token.generation
    }

    override fun isReconciliationCurrent(token: ReconciliationToken): Boolean = transitionLock.withLock {
        status.get() is Status.Pending && revision.get() == token.revision
    }

    private fun releaseRead() {
        transitionLock.withLock {
            check(activeReaders > 0) { "Workspace semantic read lease was released without an active reader" }
            activeReaders -= 1
            if (activeReaders == 0) readersDrained.signalAll()
        }
    }

    private fun releaseMutation() {
        transitionLock.withLock {
            check(activeMutation) { "Workspace mutation permit was released without an active mutation" }
            activeMutation = false
            readersDrained.signalAll()
        }
    }

    class ReconciliationToken internal constructor(internal val revision: Long)

    class RecoveryAuditToken internal constructor(
        internal val revision: Long,
        val generation: PublishedWorkspaceGeneration,
    )

    class WorkspaceReadToken internal constructor(
        internal val revision: Long,
        val generation: PublishedWorkspaceGeneration,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    class WorkspaceMutationToken internal constructor(
        val generation: PublishedWorkspaceGeneration,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    sealed class WorkspaceMutationAdmissionException(message: String) : IllegalStateException(message)

    class WorkspaceMutationAdmissionUnavailableException internal constructor(
        val admissionStatus: Status,
    ) : WorkspaceMutationAdmissionException("Workspace mutation requires READY semantic admission") {
        init {
            require(admissionStatus !is Status.Ready) {
                "READY semantic admission cannot be represented as unavailable"
            }
        }
    }

    class WorkspaceMutationAdmissionInvalidatedException internal constructor(
        val expectedRevision: Long,
        val actualRevision: Long,
    ) : WorkspaceMutationAdmissionException(
        "Workspace mutation admission moved while waiting for active semantic reads to finish",
    )

    sealed class RecoveryAuditAdmissionException(message: String) : IllegalStateException(message)

    class RecoveryAuditAdmissionUnavailableException internal constructor(
        val admissionStatus: Status,
    ) : RecoveryAuditAdmissionException("Workspace recovery audit requires READY semantic admission") {
        init {
            require(admissionStatus !is Status.Ready) {
                "READY semantic admission cannot be represented as unavailable"
            }
        }
    }

    class RecoveryAuditAdmissionInvalidatedException internal constructor(
        val expectedRevision: Long,
        val actualRevision: Long,
    ) : RecoveryAuditAdmissionException(
        "Workspace recovery audit moved while waiting for active semantic reads to finish",
    )

    sealed interface RecoveryAuditRestoration {
        data class Restored(val generation: PublishedWorkspaceGeneration) : RecoveryAuditRestoration

        data object Invalidated : RecoveryAuditRestoration
    }

    sealed interface ReadyPublication {
        data class Admitted(val commit: WorkspacePublicationCommit) : ReadyPublication

        data object InvalidatedBeforeCommit : ReadyPublication

        data class InvalidatedAfterCommit(
            val commit: WorkspacePublicationCommit,
        ) : ReadyPublication
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long =
        ((nanoTime() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)

    sealed interface Inspection {
        data object Ready : Inspection

        data class Pending(val detail: String) : Inspection {
            init {
                require(detail.isNotBlank()) { "Pending semantic-admission detail must not be blank" }
            }
        }
    }

    sealed interface Status {
        data class Ready(val generation: PublishedWorkspaceGeneration) : Status

        data class Pending(val detail: String) : Status {
            init {
                require(detail.isNotBlank()) { "Pending semantic-admission detail must not be blank" }
            }
        }

        data class Failed(val detail: String) : Status {
            init {
                require(detail.isNotBlank()) { "Failed semantic-admission detail must not be blank" }
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
