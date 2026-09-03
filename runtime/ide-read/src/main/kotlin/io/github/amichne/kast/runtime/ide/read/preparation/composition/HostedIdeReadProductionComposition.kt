package io.github.amichne.kast.runtime.ide.read.composition

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntime
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimeCandidate
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparation
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparationFailure
import io.github.amichne.kast.runtime.ide.read.symbol.HostedCandidateSelectorAdmission
import io.github.amichne.kast.runtime.ide.read.symbol.HostedExactSelectorAdmission
import io.github.amichne.kast.runtime.ide.read.symbol.HostedSymbolDescription
import io.github.amichne.kast.runtime.ide.read.symbol.HostedSymbolDescriptionCapability
import io.github.amichne.kast.runtime.ide.read.symbol.HostedSymbolDescriptionPreparation
import io.github.amichne.kast.runtime.ide.read.symbol.HostedSymbolDiscovery
import io.github.amichne.kast.runtime.ide.read.symbol.HostedSymbolDiscoveryPreparation
import io.github.amichne.kast.runtime.ide.read.symbol.HostedSymbolResolution
import io.github.amichne.kast.runtime.ide.read.symbol.HostedSymbolResolutionCapability
import io.github.amichne.kast.runtime.ide.read.symbol.HostedSymbolResolutionPreparation
import io.github.amichne.kast.runtime.ide.read.workspace.HostedWorkspaceInspection
import io.github.amichne.kast.runtime.ide.read.workspace.HostedWorkspaceInspectionPreparation
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolCompilation
import io.github.amichne.kast.symbol.contract.SymbolDescriptionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.intellij.IntellijSymbolCompilerAdapter
import io.github.amichne.kast.symbol.intellij.IntellijSymbolExactCompilerAdapter
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel

/** Finite failures before endpoint generation may activate a complete hosted runtime. */
enum class HostedIdeReadProductionCompositionFailure {
    MODEL_UNAVAILABLE,
}

/** Closed preparation of the native/model authority required by the exact four routes. */
sealed interface HostedIdeReadProductionCompositionPreparation {
    data class Prepared(
        val composition: PreparedHostedIdeReadProductionComposition,
    ) : HostedIdeReadProductionCompositionPreparation

    data class Rejected(
        val failure: HostedIdeReadProductionCompositionFailure,
    ) : HostedIdeReadProductionCompositionPreparation
}

/**
 * Complete native/model authority that has not yet consumed an endpoint evidence generation.
 *
 * The retained values are an exact hosted Project capability, a detached compiled scope, stateless
 * IntelliJ adapters, and one endpoint-scoped detached selector owner. No live Project, VFS, PSI,
 * index result, scope, or compiler object is retained.
 */
class PreparedHostedIdeReadProductionComposition internal constructor(
    private val project: HostedIdeReadProject,
    private val scope: WorkspaceSearchScopeModelCompilation.Compiled,
) {
    private val selectors = HostedSelectorAuthority()
    private val discoveryAdapter = IntellijSymbolCompilerAdapter()
    private val exactAdapter = IntellijSymbolExactCompilerAdapter()

    /**
     * Proof transition: `(PreparedHostedIdeReadProductionComposition, EvidenceGeneration) ->
     * HostedIdeReadRuntimePreparation`.
     *
     * Binds one endpoint generation to exactly workspace inspection, native discovery, exact
     * resolution, and exact description before issuing [HostedIdeReadRuntime]. Route preparation
     * failures remain the closed [HostedIdeReadRuntimePreparation] rejection. Raw Project lookup
     * is confined to each native request and matches only one open exact-root Project.
     */
    fun activate(generation: EvidenceGeneration): HostedIdeReadRuntimePreparation {
        val lease = SemanticReadLease(scope.model.workspaceRoot, generation)
        val workspace = when (val prepared = HostedWorkspaceInspection.prepare(project, generation)) {
            is HostedWorkspaceInspectionPreparation.Prepared -> prepared.inspection
            is HostedWorkspaceInspectionPreparation.Rejected -> return unavailableRuntime()
        }
        val discovery = when (val prepared = HostedSymbolDiscovery.prepare(project) { request, read ->
            if (read.canonicalRoot != lease.workspaceRoot) {
                rejectedDiscovery(SymbolDiscoverRejection.WORKSPACE_NOT_READY)
            } else {
                executeDiscovery(lease, request)
            }
        }) {
            is HostedSymbolDiscoveryPreparation.Prepared -> prepared.discovery
            is HostedSymbolDiscoveryPreparation.Rejected -> return unavailableRuntime()
        }
        val resolution = when (val prepared = HostedSymbolResolution.prepare(project) { request, read ->
            if (read.canonicalRoot != lease.workspaceRoot) {
                HostedCandidateSelectorAdmission.Rejected(
                    SymbolResolveRejection.WORKSPACE_NOT_READY,
                )
            } else {
                admitResolution(lease, request.candidateSelector)
            }
        }) {
            is HostedSymbolResolutionPreparation.Prepared -> prepared.resolution
            is HostedSymbolResolutionPreparation.Rejected -> return unavailableRuntime()
        }
        val description = when (val prepared = HostedSymbolDescription.prepare(project) { request, read ->
            if (read.canonicalRoot != lease.workspaceRoot) {
                HostedExactSelectorAdmission.Rejected(
                    SymbolDescribeRejection.WORKSPACE_NOT_READY,
                )
            } else {
                admitDescription(lease, request.exactSelector)
            }
        }) {
            is HostedSymbolDescriptionPreparation.Prepared -> prepared.description
            is HostedSymbolDescriptionPreparation.Rejected -> return unavailableRuntime()
        }
        return HostedIdeReadRuntime.prepare(
            HostedIdeReadRuntimeCandidate.Complete(
                project,
                lease,
                workspace,
                discovery,
                resolution,
                description,
            ),
        )
    }

    private suspend fun executeDiscovery(
        lease: SemanticReadLease,
        request: io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest,
    ): io.github.amichne.kast.kernel.OperationOutcome<
        io.github.amichne.kast.protocol.contract.SymbolDiscoverResult,
        io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        > = when (val admitted = admitHostedDiscoveryRequest(lease, request)) {
        is HostedDiscoveryRequestAdmission.Admitted -> {
            val live = exactProject() ?: return rejectedDiscovery(
                SymbolDiscoverRejection.WORKSPACE_NOT_READY,
            )
            when (val compiled = discoveryAdapter.compile(live, admitted.request, scope)) {
                is SymbolCompilation.Compiled -> compiled.outcome.hostedOutcome(selectors)
                is SymbolCompilation.Rejected -> rejectedDiscovery(
                    compiled.reason.hostedRejection(),
                )
            }
        }
        HostedDiscoveryRequestAdmission.Rejected -> rejectedDiscovery()
    }

    private fun admitResolution(
        lease: SemanticReadLease,
        token: io.github.amichne.kast.protocol.contract.ProtocolText,
    ): HostedCandidateSelectorAdmission = when (val lookup = selectors.candidate(token)) {
        is HostedCandidateLookup.Found -> if (lookup.selector.lease != lease) {
            HostedCandidateSelectorAdmission.Rejected(SymbolResolveRejection.CANDIDATE_STALE)
        } else if (
            lookup.selector !is CandidateSelector.Declaration
        ) {
            HostedCandidateSelectorAdmission.Rejected(
                SymbolResolveRejection.CANDIDATE_NOT_DECLARATION,
            )
        } else {
            val selection = lookup.selector.selection
            HostedCandidateSelectorAdmission.Admitted(
                HostedSymbolResolutionCapability {
                    val live = exactProject()
                    if (live == null) {
                        rejectedResolve(SymbolResolveRejection.WORKSPACE_NOT_READY)
                    } else {
                        when (val compiled = exactAdapter.resolve(
                            live,
                            lease,
                            SymbolResolutionRequest(selection),
                            scope,
                        )) {
                            is SymbolResolutionCompilation.Resolved,
                            is SymbolResolutionCompilation.Rejected,
                                -> compiled.hostedOutcome(selectors)
                        }
                    }
                },
            )
        }
        HostedCandidateLookup.Missing ->
            HostedCandidateSelectorAdmission.Rejected(SymbolResolveRejection.CANDIDATE_STALE)
    }

    private fun admitDescription(
        lease: SemanticReadLease,
        token: io.github.amichne.kast.protocol.contract.ProtocolText,
    ): HostedExactSelectorAdmission = when (val lookup = selectors.exact(token)) {
        is HostedExactLookup.Found -> if (lookup.selector.lease == lease) {
            HostedExactSelectorAdmission.Admitted(
                HostedSymbolDescriptionCapability {
                    val live = exactProject()
                    if (live == null) {
                        rejectedDescribe(SymbolDescribeRejection.WORKSPACE_NOT_READY)
                    } else {
                        when (val compiled = exactAdapter.describe(
                            live,
                            lease,
                            ExactSymbolRequest(lookup.selector),
                            scope,
                        )) {
                            is SymbolDescriptionCompilation.Described,
                            is SymbolDescriptionCompilation.Rejected,
                                -> compiled.hostedOutcome(token)
                        }
                    }
                },
            )
        } else {
            HostedExactSelectorAdmission.Rejected(SymbolDescribeRejection.SELECTOR_STALE)
        }
        HostedExactLookup.Missing ->
            HostedExactSelectorAdmission.Rejected(SymbolDescribeRejection.SELECTOR_STALE)
    }

    private fun exactProject(): Project? = ProjectManager.getInstance().openProjects.singleOrNull {
        candidate -> !candidate.isDisposed && candidate.isOpen &&
            candidate.basePath == scope.model.workspaceRoot.value
    }
}

/** Sole preparation boundary for the installed exact-root hosted read graph. */
object HostedIdeReadProductionComposition {
    /**
     * Proof transition: `(HostedIdeReadProject, DetachedIdeWorkspaceModel) ->
     * HostedIdeReadProductionCompositionPreparation`.
     *
     * Establishes exact root equality and a complete detached Gradle-owned search-scope model
     * before returning the generation-free native composition capability. Model rejection remains
     * finite [HostedIdeReadProductionCompositionFailure.MODEL_UNAVAILABLE].
     */
    fun prepare(
        project: HostedIdeReadProject,
        model: DetachedIdeWorkspaceModel,
    ): HostedIdeReadProductionCompositionPreparation {
        if (project.canonicalRoot.value != model.canonicalRoot.value) {
            return HostedIdeReadProductionCompositionPreparation.Rejected(
                HostedIdeReadProductionCompositionFailure.MODEL_UNAVAILABLE,
            )
        }
        return when (val compiled = model.compileHostedSearchScope()) {
            is WorkspaceSearchScopeModelCompilation.Compiled ->
                HostedIdeReadProductionCompositionPreparation.Prepared(
                    PreparedHostedIdeReadProductionComposition(project, compiled),
                )
            is WorkspaceSearchScopeModelCompilation.Rejected ->
                HostedIdeReadProductionCompositionPreparation.Rejected(
                    HostedIdeReadProductionCompositionFailure.MODEL_UNAVAILABLE,
                )
        }
    }
}

private fun unavailableRuntime(): HostedIdeReadRuntimePreparation =
    HostedIdeReadRuntimePreparation.Rejected(
        HostedIdeReadRuntimePreparationFailure.PARTIAL_RUNTIME,
    )
