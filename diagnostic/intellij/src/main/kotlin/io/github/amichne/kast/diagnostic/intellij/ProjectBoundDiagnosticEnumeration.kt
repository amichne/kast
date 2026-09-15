package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.IdFilter
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeEnumerator
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectFileClassification
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectFileIndexClassifier
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.idea.KotlinFileType

/** Streams the existing Kotlin file-type index; never requests its eager containing-file iterator. */
internal fun projectBoundDiagnosticEnumeration(
    project: Project,
    authority: SemanticReadAuthority,
    limits: ReadLimits,
    admitsFile: (Path) -> Boolean,
): DiagnosticScopeEnumerator = DiagnosticScopeEnumerator { request, budget ->
    val start = System.nanoTime()
    val allowance =
        DiagnosticEnumerationAllowance(diagnosticEnumerationBudget(limits, budget)) {
            (System.nanoTime() - start) / 1_000_000
        }
    if (Registry.`is`("indexing.filetype.over.vfs"))
        return@DiagnosticScopeEnumerator DiagnosticEnumerationResult.Rejected(
            DiagnosticEnumerationFailure.IndexModeUnsupported
        )
    guardedDiagnosticEnumeration {
        readAction { DiagnosticIndexScope(project, authority, limits, admitsFile).collect(request, allowance) }
    }
}

private class DiagnosticIndexScope(
    private val project: Project,
    private val authority: SemanticReadAuthority,
    private val limits: ReadLimits,
    private val admitsFile: (Path) -> Boolean,
) {
    fun collect(
        request: DiagnosticEnumerationRequest,
        allowance: DiagnosticEnumerationAllowance,
    ): DiagnosticEnumerationResult {
        if (!diagnosticHostReady(project, request.query, authority)) {
            return DiagnosticEnumerationResult.Rejected(DiagnosticScopeResolutionFailure.WORKSPACE_NOT_READY)
        }
        when (val admitted = admitDiagnosticQueryPath(request.query)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return DiagnosticEnumerationResult.Rejected(admitted.failure)
        }
        val scope = diagnosticEnumerationScope(project, request.query, admitsFile)
        return diagnosticEnumerationAttempt(
            request,
            allowance,
            limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value.toLong(),
        ) { collector ->
            collectIndexedDiagnosticFiles(project, authority, limits, scope, collector)
        }
    }
}

internal suspend fun guardedDiagnosticEnumeration(
    block: suspend () -> DiagnosticEnumerationResult
): DiagnosticEnumerationResult =
    try {
        block()
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        DiagnosticEnumerationResult.Rejected(DiagnosticScopeResolutionFailure.UNAVAILABLE)
    } catch (_: LinkageError) {
        DiagnosticEnumerationResult.Rejected(DiagnosticScopeResolutionFailure.UNAVAILABLE)
    }

private fun collectIndexedDiagnosticFiles(
    project: Project,
    authority: SemanticReadAuthority,
    limits: ReadLimits,
    scope: GlobalSearchScope,
    collector: BoundedDiagnosticEnumeration,
) {
    FileBasedIndex.getInstance()
        .processValues(
            FileTypeIndex.NAME,
            KotlinFileType.INSTANCE,
            null,
            { file, _ ->
                ProgressManager.checkCanceled()
                collector.accept { observeIndexedDiagnosticFile(project, authority, file, limits) }
            },
            scope,
            IdFilter.ACCEPT_ALL,
        )
}

private fun observeIndexedDiagnosticFile(
    project: Project,
    authority: SemanticReadAuthority,
    file: VirtualFile,
    limits: ReadLimits,
): Refinement<DiagnosticSourceFile, DiagnosticScopeResolutionFailure> {
    val path = Path.of(file.path)
    if (file.canonicalPath?.let(Path::of) != path)
        return Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
    return when (IntellijProjectFileIndexClassifier.classify(project, file, limits = limits)) {
        is IntellijProjectFileClassification.Source ->
            when (val admitted = DiagnosticScope.fromCanonicalPaths(authority, listOf(path))) {
                is Refinement.Refined -> Refinement.Refined(admitted.value.files.single())
                is Refinement.Rejected -> Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
            }
        is IntellijProjectFileClassification.Rejected ->
            Refinement.Rejected(DiagnosticScopeResolutionFailure.UNAVAILABLE)
        is IntellijProjectFileClassification.NotSource,
        is IntellijProjectFileClassification.Library ->
            Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
    }
}

private fun diagnosticEnumerationBudget(limits: ReadLimits, budget: ResourceBudget): ResourceBudget {
    val scope = diagnosticScopeBudget(limits)
    return ResourceBudget(
        scope.resultLimit,
        if (scope.workUnitLimit.value < budget.workUnitLimit.value) scope.workUnitLimit else budget.workUnitLimit,
        if (scope.elapsedTimeLimit.value < budget.elapsedTimeLimit.value) scope.elapsedTimeLimit
        else budget.elapsedTimeLimit,
    )
}

private fun diagnosticEnumerationScope(
    project: Project,
    query: DiagnosticScopeQuery,
    admitsFile: (Path) -> Boolean,
): GlobalSearchScope =
    object : GlobalSearchScope(project) {
        override fun contains(file: VirtualFile): Boolean {
            ProgressManager.checkCanceled()
            return file.isValid && diagnosticIndexContains(query.path, Path.of(file.path), admitsFile)
        }

        override fun isSearchInModuleContent(module: Module): Boolean = true

        override fun isSearchInLibraries(): Boolean = false
    }

internal fun diagnosticIndexContains(scope: Path, file: Path, admitsFile: (Path) -> Boolean): Boolean =
    file.startsWith(scope) && admitsFile(file)

private fun admitDiagnosticQueryPath(query: DiagnosticScopeQuery): Refinement<Unit, DiagnosticScopeResolutionFailure> {
    val requested =
        LocalFileSystem.getInstance().findFileByNioFile(query.path)
            ?: return Refinement.Rejected(DiagnosticScopeResolutionFailure.UNAVAILABLE)
    return if (!requested.isValid || requested.canonicalPath?.let(Path::of) != query.path) {
        Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
    } else Refinement.Refined(Unit)
}

private fun diagnosticHostReady(
    project: Project,
    query: DiagnosticScopeQuery,
    authority: SemanticReadAuthority,
): Boolean = !project.isDisposed && query.lease == authority && !DumbService.isDumb(project)
