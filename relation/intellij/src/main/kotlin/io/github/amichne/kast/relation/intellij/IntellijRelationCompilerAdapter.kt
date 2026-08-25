package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import kotlinx.coroutines.CancellationException

internal sealed interface IntellijRelationLeaseAdmission {
    data object Admitted : IntellijRelationLeaseAdmission

    data class Rejected(
        val reason: RelationCompilerRejection,
    ) : IntellijRelationLeaseAdmission
}

/**
 * Proof transition: `(SemanticReadLease, SemanticReadLease) ->
 * IntellijRelationLeaseAdmission`.
 *
 * Admitted proves exact canonical root and generation equality. Rejected preserves root mismatch
 * or generation movement as [RelationCompilerRejection]. Raw identity extraction stays at the
 * workspace publication boundary.
 */
internal fun admitRelationLease(
    current: SemanticReadLease,
    requested: SemanticReadLease,
): IntellijRelationLeaseAdmission = when {
    current.workspaceRoot != requested.workspaceRoot ->
        IntellijRelationLeaseAdmission.Rejected(
            RelationCompilerRejection.WORKSPACE_ROOT_MISMATCH,
        )
    current.generation != requested.generation ->
        IntellijRelationLeaseAdmission.Rejected(RelationCompilerRejection.GENERATION_MOVED)
    else -> IntellijRelationLeaseAdmission.Admitted
}

internal class IntellijRelationCompilerQuery(
    private val scopeCompiler: IntellijRelationScopeCompiler = IntellijRelationScopeCompiler(),
) {
    /**
     * Proof transition: `(Project, SemanticReadLease, RelationRequest,
     * WorkspaceSearchScopeModelCompilation) -> RelationCompilation`.
     *
     * A non-rejected result establishes current lease, exact retained scope, identical K2 subject,
     * K2-confirmed one-hop facts, request bounds, and terminal or resumable coverage.
     * [RelationCompilerRejection] is the closed expected failure. Live platform and compiler
     * values remain within the restartable read action; platform cancellation propagates.
     */
    suspend fun read(
        project: Project,
        currentLease: SemanticReadLease,
        request: RelationRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): RelationCompilation {
        when (val admission = admitRelationLease(currentLease, request.subject.lease)) {
            IntellijRelationLeaseAdmission.Admitted -> Unit
            is IntellijRelationLeaseAdmission.Rejected ->
                return RelationCompilation.Rejected(admission.reason)
        }
        if (project.isDisposed) {
            return RelationCompilation.Rejected(
                RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
            )
        }
        val scope = when (
            val compilation = scopeCompiler.compile(project, request, modelCompilation)
        ) {
            is IntellijRelationScopeCompilation.Compiled -> compilation.scope
            is IntellijRelationScopeCompilation.Rejected ->
                return RelationCompilation.Rejected(RelationCompilerRejection.SCOPE_REJECTED)
        }
        return try {
            readAction {
                val projection = IntellijK2RelationProjection(
                    project,
                    request.subject.lease.workspaceRoot,
                )
                val subject = when (
                    val lookup = projection.subject(scope, request.subject)
                ) {
                    is IntellijRelationSubjectLookup.Found -> lookup
                    is IntellijRelationSubjectLookup.Rejected ->
                        return@readAction RelationCompilation.Rejected(
                            lookup.reason.compilerRejection(),
                        )
                }
                val collector = IntellijRelationCollector(request)
                val termination = IntellijK2RelationSearch(
                    project,
                    scope,
                    projection,
                ).read(request, subject.plan(request), collector)
                collector.finish(termination)
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            RelationCompilation.Rejected(RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
        } catch (_: LinkageError) {
            RelationCompilation.Rejected(RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
        }
    }
}

/** Public native K2 boundary for exact one-hop relation compilation. */
class IntellijRelationCompilerAdapter private constructor(
    private val query: IntellijRelationCompilerQuery,
) {
    constructor() : this(IntellijRelationCompilerQuery())

    /**
     * Proof transition: `(Project, SemanticReadLease, RelationRequest,
     * WorkspaceSearchScopeModelCompilation) -> RelationCompilation`.
     *
     * Complete or qualified output carries only detached exact facts and coverage from the current
     * request. [RelationCompilerRejection] is the closed expected failure. Project, PSI, VFS,
     * native searches, and K2 session values never cross this method boundary.
     */
    suspend fun read(
        project: Project,
        currentLease: SemanticReadLease,
        request: RelationRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): RelationCompilation = query.read(project, currentLease, request, modelCompilation)
}

private fun IntellijRelationSubjectFailure.compilerRejection(): RelationCompilerRejection =
    when (this) {
        IntellijRelationSubjectFailure.STALE_SELECTOR -> RelationCompilerRejection.STALE_SELECTOR
        IntellijRelationSubjectFailure.OUTSIDE_SCOPE -> RelationCompilerRejection.OUTSIDE_SCOPE
        IntellijRelationSubjectFailure.AMBIGUOUS_SUBJECT ->
            RelationCompilerRejection.AMBIGUOUS_SUBJECT
        IntellijRelationSubjectFailure.UNSUPPORTED_SUBJECT ->
            RelationCompilerRejection.UNSUPPORTED_SUBJECT
        IntellijRelationSubjectFailure.COMPILER_IDENTITY_UNAVAILABLE ->
            RelationCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE
    }
