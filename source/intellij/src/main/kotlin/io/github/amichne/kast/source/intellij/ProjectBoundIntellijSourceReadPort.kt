package io.github.amichne.kast.source.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.source.contract.readScope
import io.github.amichne.kast.source.contract.SourceReadPort
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope

/** Saved, PSI-committed source reads bound to the host's original project and read authority. */
object ProjectBoundIntellijSourceReadPort {
    fun create(
        project: Project,
        authority: SemanticReadAuthority,
        model: WorkspaceSearchScopeModel,
        fileAdmission: IntellijSemanticSourceFileAdmission,
        continuations: IntellijSourceReadContinuations,
    ): SourceReadPort {
        return IntellijSourceReadPort(IntellijSourceRegionAccess { context, request, cursor ->
            when {
                model.workspaceRoot != authority.workspaceRoot || context.lease.workspaceRoot != authority.workspaceRoot ->
                    IntellijSourceRegionAccessResult.Rejected(IntellijSourceReadRejection.WORKSPACE_ROOT_MISMATCH)
                context.lease != authority ->
                    IntellijSourceRegionAccessResult.Rejected(IntellijSourceReadRejection.STALE_GENERATION)
                else -> {
                    val readScope = request.anchor.readScope()
                    if (readScope is SourceReadScope.Constrained &&
                        (readScope.scope.generatedSources == SymbolGeneratedSourcePolicy.INCLUDE ||
                            (readScope.scope as? SymbolSearchScope.Workspace)?.libraries == SymbolLibraryPolicy.INCLUDE)
                    ) {
                        return@IntellijSourceRegionAccess IntellijSourceRegionAccessResult.Rejected(
                            IntellijSourceReadRejection.OUTSIDE_SOURCE_SCOPE,
                        )
                    }
                    val sourceSets = when (val scope = readScope) {
                        SourceReadScope.ExactFile -> SymbolDiscoverySourceSets.All
                        is SourceReadScope.Constrained -> scope.constraints.sourceSets
                    }
                    LiveIntellijSourceRegionAccess(project) { path ->
                        admitsSourceReadScope(model, readScope, path) && fileAdmission.admits(path, sourceSets)
                    }
                        .select(context, request, cursor)
                }
            }
        }, continuations)
    }
}
