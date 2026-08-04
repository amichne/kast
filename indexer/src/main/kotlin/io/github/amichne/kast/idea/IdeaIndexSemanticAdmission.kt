package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.idea.backend.semantic.WorkspaceSemanticReadAuthority
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class IdeaIndexSemanticAdmission(
    private val project: Project,
    private val inspectProject: () -> Inspection = { inspect(project, IdeaSemanticAdmissionOperations.idea()) },
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
        publish: () -> WorkspaceGenerationCommit,
    ): ReadyPublication {
        if (transitionLock.withLock { revision.get() != token.revision }) {
            return ReadyPublication.InvalidatedBeforeCommit
        }
        val commit = publish()
        return transitionLock.withLock {
            if (revision.get() != token.revision) {
                return@withLock ReadyPublication.InvalidatedAfterCommit(commit)
            }
            status.set(Status.Ready(commit.manifest))
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

    class WorkspaceReadToken internal constructor(
        internal val revision: Long,
        val generation: PublishedWorkspaceGenerationManifest,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    class WorkspaceMutationToken internal constructor(
        val generation: PublishedWorkspaceGenerationManifest,
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

    sealed interface ReadyPublication {
        data class Admitted(val commit: WorkspaceGenerationCommit) : ReadyPublication

        data object InvalidatedBeforeCommit : ReadyPublication

        data class InvalidatedAfterCommit(
            val commit: WorkspaceGenerationCommit,
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
        data class Ready(val generation: PublishedWorkspaceGenerationManifest) : Status

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

        fun inspect(
            project: Project,
            operations: IdeaSemanticAdmissionOperations,
        ): Inspection = ApplicationManager.getApplication().runReadAction<Inspection> {
            if (DumbService.isDumb(project)) {
                return@runReadAction Inspection.Pending("IDEA indexing is still in progress")
            }
            val kotlinFileType = FileTypeManager.getInstance().findFileTypeByName("Kotlin")
                ?: return@runReadAction Inspection.Pending("the Kotlin file type is unavailable")
            val kotlinModules = ModuleManager.getInstance(project).modules
                .asSequence()
                .filterNot(Module::isDisposed)
                .mapNotNull { module ->
                    val representative = FileTypeIndex.getFiles(
                        kotlinFileType,
                        GlobalSearchScope.moduleScope(module),
                    ).asSequence()
                        .filter { file -> file.isValid && !file.isDirectory }
                        .minByOrNull { file -> file.path }
                    representative?.let { file -> module to file }
                }
                .sortedBy { (module, _) -> module.name }
                .toList()
            if (kotlinModules.isEmpty()) {
                return@runReadAction Inspection.Pending("no Kotlin source module has been admitted to the project model")
            }

            val javaPsi = JavaPsiFacade.getInstance(project)
            kotlinModules.forEach { (module, representative) ->
                val roots = ModuleRootManager.getInstance(module)
                if (roots.sdk == null) {
                    return@runReadAction Inspection.Pending("module ${module.name} has no SDK")
                }
                if (roots.orderEntries.any { entry -> !entry.isValid }) {
                    return@runReadAction Inspection.Pending("module ${module.name} has unresolved order entries")
                }
                val compilerScope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
                if (javaPsi.findClass("java.nio.file.Path", compilerScope) == null) {
                    return@runReadAction Inspection.Pending(
                        "JDK symbol java.nio.file.Path is unresolved in module ${module.name}",
                    )
                }
                if (javaPsi.findClass("kotlin.jvm.internal.Intrinsics", compilerScope) == null) {
                    return@runReadAction Inspection.Pending(
                        "Kotlin runtime symbol kotlin.jvm.internal.Intrinsics is unresolved in module ${module.name}",
                    )
                }
                val ktFile = PsiManager.getInstance(project).findFile(representative) as? KtFile
                    ?: return@runReadAction Inspection.Pending(
                        "IDEA has not created Kotlin PSI for ${representative.path}",
                    )
                try {
                    operations.collectDiagnostics(ktFile)
                } catch (error: ProcessCanceledException) {
                    throw error
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    return@runReadAction Inspection.Pending(
                        "Kotlin analysis is unavailable for ${representative.path}: " +
                            (error.message?.takeIf(String::isNotBlank) ?: error::class.qualifiedName),
                    )
                }
            }
            Inspection.Ready
        }
    }
}
