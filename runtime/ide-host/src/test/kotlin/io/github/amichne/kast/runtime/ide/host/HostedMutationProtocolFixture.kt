package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddDeclarationPlanResult
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidenceInput
import io.github.amichne.kast.change.contract.AddDeclarationSourceText
import io.github.amichne.kast.change.contract.EditableMutationTarget
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.MutationTargetObservation
import io.github.amichne.kast.change.contract.ObservedMutationTargetState
import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RelationWorkOffset
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.security.MessageDigest

internal class HostedMutationProtocolFixture {
    private val sourcePreimage = "fun service(): Int = 0".toByteArray()
    private val workspaceRoot = CanonicalWorkspaceRoot
        .fromCanonicalPath(Path.of("/workspace"))
        .refined()
    private val lease = SemanticReadLease(
        workspaceRoot,
        EvidenceGeneration.parse(11L).refined(),
    )
    private val targetPath = Path.of("/workspace/app/src/main/kotlin/sample/Service.kt")
    private val target = editableTarget()

    val plan = when (val planned = PureAddDeclarationPlanningService().plan(
        AddDeclarationPlanRequest(
            target,
            AddDeclarationSourceText.parse("fun added(): Int = 1").refined(),
            ExpectedAddDeclarationDelta.admit(
                packageName = "sample",
                declarationName = "added",
                declarationKind = AddDeclarationKind.FUNCTION,
            ).refined(),
            AddDeclarationPlanningEvidenceInput(
                relations = listOf(completeRelation()),
                traversals = listOf(completeTraversal()),
                diagnostics = listOf(completeDiagnostic()),
            ),
        ),
    )) {
        is AddDeclarationPlanResult.Planned -> planned.plan
        is AddDeclarationPlanResult.Rejected -> error(planned.failure.toString())
    }

    val selector: SymbolSelector
        get() = target.selector

    val workspace: PublishedWorkspace
        get() = targetWorkspace

    fun successorWorkspace(generation: Long): PublishedWorkspace = PublishedWorkspace.publish(
        ReconciledWorkspace.admit(
            WorkspaceCandidate(
                workspaceRoot,
                WorkspaceStateIdentity.parse("state-$generation").refined(),
            ),
            WorkspaceEvidenceKind.entries.toSet(),
            targetWorkspace.sourceRoots,
        ).refined(),
        EvidenceGeneration.parse(generation).refined(),
    )

    val applied: AppliedUnverified = AppliedUnverified.restore(
        plan,
        plan.priorLease,
        plan.workspaceState,
        WorkspaceSourceContentHash.parse(sha256("fun service(): Int = 0\nfun added(): Int = 1".toByteArray()))
            .refined(),
        MutationPlanBinding.parse(plan.planId.value).refined(),
    ).refined()

    private val targetWorkspace: PublishedWorkspace
        get() = PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                WorkspaceCandidate(
                    workspaceRoot,
                    WorkspaceStateIdentity.parse("state-11").refined(),
                ),
                WorkspaceEvidenceKind.entries.toSet(),
                listOf(target.sourceRoot),
            ).refined(),
            lease.generation,
        )

    private fun editableTarget(): EditableMutationTarget {
        val sourceRoot = SourceRoot.admit(
            GradleSourceRootEvidence(
                ideaModuleName = "app",
                workspaceRelativeBuildRoot = ".",
                gradleProjectPath = ":app",
                sourceSetName = "main",
                workspaceRelativeSourceRoot = "app/src/main/kotlin",
                provenance = SourceRootProvenance.Authored,
            ),
        ).refined()
        val selector = selector(sourceRoot)
        val workspace = PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                WorkspaceCandidate(
                    workspaceRoot,
                    WorkspaceStateIdentity.parse("state-11").refined(),
                ),
                WorkspaceEvidenceKind.entries.toSet(),
                listOf(sourceRoot),
            ).refined(),
            lease.generation,
        )
        return EditableMutationTarget.admit(
            MutationTargetObservation(
                workspace,
                selector,
                sourceRoot.owner,
                ObservedMutationTargetState(
                    lease,
                    selector.file,
                    WorkspaceSourceContentHash.parse(sha256(sourcePreimage)).refined(),
                ),
            ),
        ).refined()
    }

    private fun selector(sourceRoot: SourceRoot): SymbolSelector {
        val scope = SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.INCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(lease, scope),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                SymbolDiscoveryPattern.parse("service").refined(),
                SymbolDiscoveryMatch.FUZZY,
            ),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(4L).refined(),
                    ElapsedTimeLimitMillis.parse(100L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "service",
            lease,
            targetPath,
            "file://$targetPath",
            10,
        ).refined()
        val batch = SymbolDiscoveryBatch.create(
            request,
            listOf(candidate),
            candidate.projectedUtf8Size(),
            SymbolDiscoveryWorkCount.parse(1L).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
        val selection = SymbolDiscoverySelection.select(batch, 0).refined()
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            location.file,
            location.offset.value,
            17,
            "service",
            "sample.Service.service",
            CompilerSymbolKind.FUNCTION,
            CompilerSymbolIdentity.parse("FUNCTION|sample.Service.service").refined(),
        ).refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun completeRelation() = RelationCompilation.complete(
        RelationBatch.create(
            RelationRequest.start(selector, RelationMeaning.References, relationBudget()),
            emptyList(),
            RelationByteCount.parse(0L).refined(),
            RelationWorkCount.parse(0L).refined(),
        ).refined(),
    ).let { complete -> io.github.amichne.kast.relation.contract.RelationReadResult.Complete(
        complete.batch,
        complete.coverage,
    ) }

    private fun completeTraversal() = TraversalPage.fromBoundary(
        TraversalPlan.start(selector, RelationMeaning.References, traversalBudget()).refined(),
        emptyList(),
        encodedBytes = 0L,
        examinedWorkUnits = 0L,
        elapsedMillis = 0L,
        expandedFrontier = 0,
    ).refined().let(io.github.amichne.kast.traversal.contract.TraversalResult::complete)

    private fun completeDiagnostic() = DiagnosticBatch.empty(
        DiagnosticScope.fromCanonicalPaths(lease, listOf(targetPath)).refined(),
    ).let(DiagnosticCompilation::complete).let { complete ->
        io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult.Complete(
            complete.batch,
            complete.coverage,
        )
    }

    private fun relationBudget(): RelationBudget = RelationBudget(
        ResourceBudget(
            ResultLimit.parse(8).refined(),
            WorkUnitLimit.parse(8L).refined(),
            ElapsedTimeLimitMillis.parse(1_000L).refined(),
        ),
        RelationByteLimit.parse(10_000L).refined(),
    )

    private fun traversalBudget(): TraversalBudget = TraversalBudget(
        ResultLimit.parse(8).refined(),
        TraversalByteLimit.parse(10_000L).refined(),
        WorkUnitLimit.parse(8L).refined(),
        ElapsedTimeLimitMillis.parse(1_000L).refined(),
        TraversalDepthLimit.parse(2).refined(),
        TraversalFrontierLimit.parse(8).refined(),
        relationBudget(),
    )
}

internal fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error(failure.toString())
}

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
