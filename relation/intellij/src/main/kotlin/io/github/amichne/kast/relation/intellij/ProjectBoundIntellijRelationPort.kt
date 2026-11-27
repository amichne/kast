package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission

/** Reuses the native one-hop reader without workspace lookup or publication dependencies. */
object ProjectBoundIntellijRelationPort {
    fun create(
        project: Project,
        authority: SemanticReadAuthority,
        model: WorkspaceSearchScopeModel,
        fileAdmission: IntellijSemanticSourceFileAdmission,
        observation: IntellijReadObservation = IntellijReadObservation.None,
        limits: ReadLimits = ReadLimits.Default,
        summaries: io.github.amichne.kast.relation.contract.CallbackSummaryCachePreparationPort =
            io.github.amichne.kast.relation.contract.CallbackSummaryCachePreparationPort.Disabled,
    ): RelationCompilerPort {
        val compiledModel = WorkspaceSearchScopeModelCompilation.Compiled(model)
        val inventories = IntellijReferenceInventoryReuse.Recent(limits)
        return RelationCompilerPort { request ->
            if (request.unsupportedByBoundHost()) {
                return@RelationCompilerPort RelationCompilation.Rejected(RelationCompilerRejection.SCOPE_REJECTED)
            }
            val query =
                IntellijRelationCompilerQuery(
                    IntellijRelationScopeCompiler(fileAdmission::admits),
                    observation,
                    limits,
                    summaries,
                    inventories,
                )
            query.read(project, authority, request, compiledModel)
        }
    }
}

/**
 * A binary subject is admitted with its declaration, so its library scope is allowed. Its destinations must stay in
 * source. An explicit destination can include generated source. A subject issued from such a destination keeps that
 * scope; [AdmittedRelationScopes] admits it only from authored source.
 */
private fun RelationRequest.unsupportedByBoundHost(): Boolean =
    (searchScope as? SymbolSearchScope.Workspace)?.libraries == SymbolLibraryPolicy.INCLUDE
