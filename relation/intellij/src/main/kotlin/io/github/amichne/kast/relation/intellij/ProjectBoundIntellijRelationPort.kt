package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ReadLimitParameter

import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

import com.intellij.openapi.project.Project
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
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
    ): RelationCompilerPort {
        val compiledModel = WorkspaceSearchScopeModelCompilation.Compiled(model)
        return RelationCompilerPort { request ->
            val scope = request.subject.scope
            if (scope.generatedSources == SymbolGeneratedSourcePolicy.INCLUDE ||
                scope is SymbolSearchScope.Workspace && scope.libraries == SymbolLibraryPolicy.INCLUDE
            ) {
                return@RelationCompilerPort RelationCompilation.Rejected(RelationCompilerRejection.SCOPE_REJECTED)
            }
            val query = IntellijRelationCompilerQuery(IntellijRelationScopeCompiler { path ->
                fileAdmission.admits(path, request.searchConstraints.sourceSets)
            }, observation, limits)
            query.read(project, authority, request, compiledModel)
        }
    }
}
