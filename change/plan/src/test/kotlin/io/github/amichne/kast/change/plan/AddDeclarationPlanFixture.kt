package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidenceInput
import io.github.amichne.kast.change.contract.AddDeclarationSourceText
import io.github.amichne.kast.change.contract.EditableMutationTarget
import io.github.amichne.kast.change.contract.MutationTargetObservation
import io.github.amichne.kast.change.contract.ObservedMutationTargetState
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitation
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
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
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
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
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
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
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
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

internal class AddDeclarationPlanFixture {
    private val workspaceRoot = CanonicalWorkspaceRoot
        .fromCanonicalPath(Path.of("/workspace"))
        .refined()
    private val lease = SemanticReadLease(workspaceRoot, EvidenceGeneration.parse(11L).refined())
    private val targetPath = Path.of("/workspace/app/src/main/kotlin/sample/Service.kt")
    private val selector = selector()
    private val target = editableTarget()

    fun request(reverseEvidence: Boolean = false): AddDeclarationPlanRequest {
        val relations = listOf(
            completeRelation(RelationMeaning.References),
            completeRelation(RelationMeaning.Callers),
        ).ordered(reverseEvidence)
        val traversals = listOf(
            completeTraversal(RelationMeaning.References),
            completeTraversal(RelationMeaning.Callers),
        ).ordered(reverseEvidence)
        val diagnostics = listOf(
            completeDiagnostic(listOf(targetPath)),
            completeDiagnostic(listOf(targetPath, Path.of("/workspace/app/src/main/kotlin/sample/Other.kt"))),
        ).ordered(reverseEvidence)
        return AddDeclarationPlanRequest(
            target = target,
            declaration = AddDeclarationSourceText.parse("fun added(): Int = 1").refined(),
            expectedSemanticDelta = ExpectedAddDeclarationDelta.admit(
                packageName = "sample",
                declarationName = "added",
                declarationKind = AddDeclarationKind.FUNCTION,
            ).refined(),
            evidence = AddDeclarationPlanningEvidenceInput(relations, traversals, diagnostics),
        )
    }

    fun qualifiedRelation(): RelationReadResult {
        val batch = relationBatch(RelationMeaning.References)
        val qualified = RelationCompilation.qualified(
            batch,
            setOf(RelationLimitation.PROVIDER_INCOMPLETE),
            RelationWorkOffset.Zero,
        ).refined()
        return RelationReadResult.Qualified(qualified.batch, qualified.coverage)
    }

    fun rejectedTraversal(): TraversalResult =
        TraversalResult.Rejected(TraversalRejection.ReaderContractViolation)

    fun qualifiedDiagnostic(): DiagnosticCheckResult {
        val scope = diagnosticScope(listOf(targetPath))
        val batch = DiagnosticBatch.empty(scope)
        val file = scope.files.single()
        val qualified = DiagnosticCompilation.qualified(
            batch,
            emptyList(),
            setOf(DiagnosticLimitation(file, DiagnosticLimitationReason.FILE_UNAVAILABLE)),
        ).refined()
        return DiagnosticCheckResult.Qualified(qualified.batch, qualified.coverage)
    }

    private fun completeRelation(meaning: RelationMeaning): RelationReadResult {
        val batch = relationBatch(meaning)
        val complete = RelationCompilation.complete(batch)
        return RelationReadResult.Complete(complete.batch, complete.coverage)
    }

    private fun relationBatch(meaning: RelationMeaning): RelationBatch {
        val request = RelationRequest.start(selector, meaning, relationBudget())
        return RelationBatch.create(
            request,
            emptyList(),
            RelationByteCount.parse(0L).refined(),
            RelationWorkCount.parse(0L).refined(),
        ).refined()
    }

    private fun completeTraversal(meaning: RelationMeaning): TraversalResult {
        val plan = TraversalPlan.start(selector, meaning, traversalBudget()).refined()
        val page = TraversalPage.fromBoundary(
            plan,
            emptyList(),
            encodedBytes = 0L,
            examinedWorkUnits = 0L,
            elapsedMillis = 0L,
            expandedFrontier = 0,
        ).refined()
        return TraversalResult.complete(page)
    }

    private fun completeDiagnostic(paths: List<Path>): DiagnosticCheckResult {
        val batch = DiagnosticBatch.empty(diagnosticScope(paths))
        val complete = DiagnosticCompilation.complete(batch)
        return DiagnosticCheckResult.Complete(complete.batch, complete.coverage)
    }

    private fun diagnosticScope(paths: List<Path>): DiagnosticScope =
        DiagnosticScope.fromCanonicalPaths(lease, paths).refined()

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
        val workspace = PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                WorkspaceCandidate(workspaceRoot, WorkspaceStateIdentity("state-11")),
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
                    WorkspaceSourceContentHash.parse("a".repeat(64)).refined(),
                ),
            ),
        ).refined()
    }

    private fun selector(): SymbolSelector {
        val scope = SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.INCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(lease, scope),
            SymbolDiscoveryKind.SYMBOL,
            SymbolDiscoveryPattern.parse("service").refined(),
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
            location.offset.value + 7,
            "service",
            "sample.Service.service",
            CompilerSymbolKind.FUNCTION,
            CompilerSymbolIdentity.parse("function|sample.Service.service").refined(),
        ).refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun <T> List<T>.ordered(reverse: Boolean): List<T> =
        if (reverse) reversed() else this
}

internal fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error(failure.toString())
}
