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
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class IdeaIndexSemanticAdmission(
    private val project: Project,
    private val inspectProject: () -> Inspection = { inspect(project, IdeaSemanticAdmissionOperations.idea()) },
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val maxWaitMillis: Long = TimeUnit.MINUTES.toMillis(5),
    private val pollIntervalMillis: Long = 250L,
) {
    private val status = AtomicReference<Status>(Status.Pending("compiler-backed semantic admission has not started"))

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
                        status.set(Status.Ready)
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

    fun status(): Status = status.get()

    fun fail(detail: String) {
        status.set(Status.Failed(detail))
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
        data object Ready : Status

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
