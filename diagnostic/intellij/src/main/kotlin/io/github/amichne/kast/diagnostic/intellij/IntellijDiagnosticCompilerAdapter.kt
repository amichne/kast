package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile

internal sealed interface IntellijDiagnosticLeaseAdmission {
    data object Admitted : IntellijDiagnosticLeaseAdmission

    data class Rejected(
        val reason: DiagnosticCompilerRejection,
    ) : IntellijDiagnosticLeaseAdmission
}

/**
 * Proof transition: `(SemanticReadLease, SemanticReadLease) ->
 * IntellijDiagnosticLeaseAdmission`.
 *
 * Admitted proves exact canonical root and generation equality. Rejected preserves root mismatch
 * or generation movement as [DiagnosticCompilerRejection]. Raw identity extraction stays at the
 * workspace publication boundary.
 */
internal fun admitDiagnosticLease(
    current: SemanticReadLease,
    requested: SemanticReadLease,
): IntellijDiagnosticLeaseAdmission = when {
    current.workspaceRoot != requested.workspaceRoot ->
        IntellijDiagnosticLeaseAdmission.Rejected(
            DiagnosticCompilerRejection.WORKSPACE_ROOT_MISMATCH,
        )
    current.generation != requested.generation ->
        IntellijDiagnosticLeaseAdmission.Rejected(DiagnosticCompilerRejection.GENERATION_MOVED)
    else -> IntellijDiagnosticLeaseAdmission.Admitted
}

internal class IntellijDiagnosticCompilerQuery {
    /**
     * Proof transition: `(Project, SemanticReadLease, DiagnosticScope) ->
     * DiagnosticCompilation`.
     *
     * A non-rejected result establishes current lease equality, exact-file VFS and source-content
     * admission, request-local K2 analysis, detached facts, and complete or qualified per-file
     * coverage. [DiagnosticCompilerRejection] is the closed expected failure. Live Project, VFS,
     * PSI, read-action, and K2 values remain within this call; cancellation propagates.
     */
    suspend fun read(
        project: Project,
        currentLease: SemanticReadLease,
        scope: DiagnosticScope,
    ): DiagnosticCompilation {
        when (val admission = admitDiagnosticLease(currentLease, scope.lease)) {
            IntellijDiagnosticLeaseAdmission.Admitted -> Unit
            is IntellijDiagnosticLeaseAdmission.Rejected ->
                return DiagnosticCompilation.Rejected(admission.reason)
        }
        if (project.isDisposed) {
            return DiagnosticCompilation.Rejected(
                DiagnosticCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
            )
        }
        return try {
            readAction {
                val collector = IntellijDiagnosticCollector(scope)
                if (DumbService.isDumb(project)) {
                    scope.files.forEach { file ->
                        collector.recordLimitation(file, DiagnosticLimitationReason.INDEXING)
                    }
                    return@readAction collector.finish()
                }
                scope.files.forEach { file -> collectFile(project, scope, file, collector) }
                collector.finish()
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            DiagnosticCompilation.Rejected(DiagnosticCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
        } catch (_: LinkageError) {
            DiagnosticCompilation.Rejected(DiagnosticCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
        }
    }

    private fun collectFile(
        project: Project,
        scope: DiagnosticScope,
        file: DiagnosticSourceFile,
        collector: IntellijDiagnosticCollector,
    ) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(file.value)
        if (virtualFile == null || !virtualFile.isValid || virtualFile.isDirectory) {
            collector.recordLimitation(file, DiagnosticLimitationReason.FILE_UNAVAILABLE)
            return
        }
        if (!ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) {
            collector.recordLimitation(file, DiagnosticLimitationReason.OUTSIDE_SOURCE_CONTENT)
            return
        }
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        if (psiFile == null) {
            collector.recordLimitation(file, DiagnosticLimitationReason.PSI_UNAVAILABLE)
            return
        }
        val kotlinFile = psiFile as? KtFile
        if (kotlinFile == null) {
            collector.recordLimitation(file, DiagnosticLimitationReason.UNSUPPORTED_FILE_KIND)
            return
        }
        try {
            val diagnostics = analyze(kotlinFile) {
                kotlinFile.collectDiagnostics(
                    KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS,
                )
            }
            val detached = mutableListOf<io.github.amichne.kast.diagnostic.contract.DiagnosticFact>()
            diagnostics.forEach { diagnostic ->
                when (val projection = projectDiagnostic(scope, file, diagnostic)) {
                    is IntellijDiagnosticProjection.Projected -> detached += projection.facts
                    IntellijDiagnosticProjection.Rejected -> {
                        collector.recordLimitation(
                            file,
                            DiagnosticLimitationReason.UNSUPPORTED_DIAGNOSTIC,
                        )
                        return
                    }
                }
            }
            if (
                detached.any { fact ->
                    collector.accept(fact) == IntellijDiagnosticCollectionAdmission.REJECTED
                }
            ) {
                collector.recordLimitation(file, DiagnosticLimitationReason.ANALYSIS_UNAVAILABLE)
                return
            }
            collector.recordAnalyzed(file)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            collector.recordLimitation(file, DiagnosticLimitationReason.ANALYSIS_UNAVAILABLE)
        } catch (_: LinkageError) {
            collector.recordLimitation(file, DiagnosticLimitationReason.ANALYSIS_UNAVAILABLE)
        }
    }
}

/** Public native K2 boundary for exact-scope generation-bound diagnostic compilation. */
class IntellijDiagnosticCompilerAdapter private constructor(
    private val query: IntellijDiagnosticCompilerQuery,
) {
    constructor() : this(IntellijDiagnosticCompilerQuery())

    /**
     * Proof transition: `(Project, SemanticReadLease, DiagnosticScope) ->
     * DiagnosticCompilation`.
     *
     * Complete or qualified output carries only detached exact-scope diagnostics and coverage for
     * the requested generation. [DiagnosticCompilerRejection] is the closed expected failure.
     * Project, VFS, PSI, read-action, and K2 values never cross this boundary.
     */
    suspend fun read(
        project: Project,
        currentLease: SemanticReadLease,
        scope: DiagnosticScope,
    ): DiagnosticCompilation = query.read(project, currentLease, scope)
}
