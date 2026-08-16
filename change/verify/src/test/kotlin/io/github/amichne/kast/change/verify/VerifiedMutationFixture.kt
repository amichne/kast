package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AddDeclarationApplyRequest
import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.RequestedMutationWriteScope
import io.github.amichne.kast.change.apply.SourceWriteAccess
import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddDeclarationPlanResult
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidenceInput
import io.github.amichne.kast.change.contract.AddDeclarationSourceText
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.contract.EditableMutationTarget
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.MutationTargetObservation
import io.github.amichne.kast.change.contract.ObservedMutationTargetState
import io.github.amichne.kast.change.contract.KotlinIdentifier
import io.github.amichne.kast.change.contract.RenameSymbolChangePlan
import io.github.amichne.kast.change.contract.RenameSymbolOccurrence
import io.github.amichne.kast.change.contract.RenameSymbolOccurrenceRole
import io.github.amichne.kast.change.contract.RenameSymbolOccurrenceSet
import io.github.amichne.kast.change.contract.RenameSymbolPlanRequest
import io.github.amichne.kast.change.contract.RenameSymbolPlanResult
import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.change.plan.PureRenameSymbolPlanningService
import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitation
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
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
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
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
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.security.MessageDigest

internal class VerifiedMutationFixture {
    val sourceText = "package sample\n\nfun service(): Int = 0\n"
    private val root = canonicalRoot("/workspace")
    private val targetPath = Path.of("/workspace/app/src/main/kotlin/sample/Service.kt")
    private val anchorStart = sourceText.indexOf("fun service")
    private val anchorEnd = sourceText.lastIndexOf('\n')
    private val sourceRoot = sourceRoot()
    val workspace = workspace(11L, "state-11")
    val plan: AddDeclarationChangePlan =
        (PureAddDeclarationPlanningService().plan(planRequest()) as AddDeclarationPlanResult.Planned).plan
    val applied: AppliedUnverified by lazy { applyExactMutation(this) }
    val resultingWorkspace: PublishedWorkspace = workspace(12L, "state-12")

    fun request(): VerifiedMutationRequest = VerifiedMutationRequest(plan, applied)

    fun request(
        plan: ChangePlan,
        applied: AppliedUnverified,
    ): VerifiedMutationRequest = VerifiedMutationRequest(plan, applied)

    fun applyRequest(plan: ChangePlan = this.plan): AddDeclarationApplyRequest = AddDeclarationApplyRequest(
        plan,
        workspace,
        RequestedMutationWriteScope(
            workspace.root,
            plan.writes.entries.mapTo(linkedSetOf()) { it.source },
        ),
    )

    fun observedSource(plan: ChangePlan = this.plan): ObservedMutationSource = ObservedMutationSource.capture(
        plan.writes.entries.single().source,
        sourceText.toByteArray(),
        SourceWriteAccess.Writable,
    ).refined()

    fun completeEvidence(): AddDeclarationVerificationEvidence = AddDeclarationVerificationEvidence(
        source = applied.source,
        content = applied.postimage,
        relations = listOf(completeResultingRelation()),
        diagnostics = listOf(completeResultingDiagnostics()),
        observedDelta = observedDelta("sample", "added", AddDeclarationKind.FUNCTION),
    )

    fun renamePlan(): RenameSymbolChangePlan {
        val request = planRequest()
        val start = sourceText.indexOf("service")
        val occurrence = RenameSymbolOccurrence.admit(
            request.target.file,
            ExactDeclarationTextRange.parse(start, start + "service".length).refined(),
            KotlinIdentifier.parse("service").refined(),
            RenameSymbolOccurrenceRole.DECLARATION,
        ).refined()
        val result = PureRenameSymbolPlanningService().plan(
            RenameSymbolPlanRequest(
                request.target,
                KotlinIdentifier.parse("renamedService").refined(),
                RenameSymbolOccurrenceSet.admit(request.target, listOf(occurrence)).refined(),
                request.evidence,
            ),
        )
        return (result as RenameSymbolPlanResult.Planned).plan
    }

    fun renameEvidence(applied: AppliedUnverified): RenameSymbolVerificationEvidence =
        RenameSymbolVerificationEvidence(
            applied.source,
            applied.postimage,
            listOf(completeResultingDiagnostics()),
            ObservedRenameSymbolDelta.fromCompilerBoundary(
                KotlinIdentifier.parse("service").refined(),
                KotlinIdentifier.parse("renamedService").refined(),
                0,
                1,
                0,
                0,
            ).refined(),
        )

    fun observedDelta(
        packageName: String,
        declarationName: String,
        kind: AddDeclarationKind,
    ): ObservedAddDeclarationDelta = ObservedAddDeclarationDelta.fromCompilerBoundary(
        packageName,
        declarationName,
        kind,
        1,
    ).refined()

    fun qualifiedResultingRelation(): RelationReadResult {
        val batch = relationBatch(selector(resultingWorkspace), RelationMeaning.References)
        val qualified = RelationCompilation.qualified(
            batch,
            setOf(RelationLimitation.PROVIDER_INCOMPLETE),
            RelationWorkOffset.parse(1L).refined(),
        ).refined()
        return RelationReadResult.Qualified(qualified.batch, qualified.coverage)
    }

    fun changedResultingRelation(): RelationReadResult = completeRelation(
        selector(resultingWorkspace),
        RelationMeaning.Callers,
    )

    fun qualifiedResultingDiagnostics(): DiagnosticCheckResult {
        val scope = diagnosticScope(resultingWorkspace)
        val qualified = DiagnosticCompilation.qualified(
            DiagnosticBatch.empty(scope),
            emptyList(),
            setOf(DiagnosticLimitation(scope.files.single(), DiagnosticLimitationReason.INDEXING)),
        ).refined()
        return DiagnosticCheckResult.Qualified(qualified.batch, qualified.coverage)
    }

    fun erroredResultingDiagnostics(): DiagnosticCheckResult {
        val scope = diagnosticScope(resultingWorkspace)
        val fact = DiagnosticFact.fromBoundary(
            scope,
            scope.files.single(),
            0,
            1,
            DiagnosticSeverity.ERROR,
            "COMPILER_ERROR",
            "verification error",
        ).refined()
        val complete = DiagnosticCompilation.complete(
            DiagnosticBatch.create(scope, listOf(fact)).refined(),
        )
        return DiagnosticCheckResult.Complete(complete.batch, complete.coverage)
    }

    fun expandedResultingDiagnostics(): DiagnosticCheckResult {
        val scope = DiagnosticScope.fromCanonicalPaths(
            resultingWorkspace.readLease,
            listOf(targetPath, Path.of("/workspace/app/src/main/kotlin/sample/Other.kt")),
        ).refined()
        val complete = DiagnosticCompilation.complete(DiagnosticBatch.empty(scope))
        return DiagnosticCheckResult.Complete(complete.batch, complete.coverage)
    }

    private fun planRequest(): AddDeclarationPlanRequest = AddDeclarationPlanRequest(
        target = editableTarget(),
        declaration = AddDeclarationSourceText.parse("fun added(): Int = 1").refined(),
        expectedSemanticDelta = ExpectedAddDeclarationDelta.admit(
            "sample",
            "added",
            AddDeclarationKind.FUNCTION,
        ).refined(),
        evidence = AddDeclarationPlanningEvidenceInput(
            relations = listOf(completeRelation(selector(workspace), RelationMeaning.References)),
            traversals = listOf(completeTraversal()),
            diagnostics = listOf(completeDiagnostics(workspace)),
        ),
    )

    private fun editableTarget(): EditableMutationTarget = EditableMutationTarget.admit(
        MutationTargetObservation(
            workspace,
            selector(workspace),
            sourceRoot.owner,
            ObservedMutationTargetState(
                workspace.readLease,
                selector(workspace).file,
                WorkspaceSourceContentHash.parse(sha256(sourceText.toByteArray())).refined(),
            ),
        ),
    ).refined()

    private fun selector(workspace: PublishedWorkspace): SymbolSelector {
        val scope = SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.INCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(workspace.readLease, scope),
            SymbolDiscoveryKind.SYMBOL,
            SymbolDiscoveryPattern.parse("service").refined(),
            SymbolDiscoveryBudget(resourceBudget(), SymbolDiscoveryByteLimit.parse(10_000L).refined()),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "service",
            workspace.readLease,
            targetPath,
            "file://$targetPath",
            anchorStart,
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
            anchorStart,
            anchorEnd,
            "service",
            "sample.service",
            CompilerSymbolKind.FUNCTION,
            CompilerSymbolIdentity.parse("function|sample.service").refined(),
        ).refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun completeResultingRelation(): RelationReadResult = completeRelation(
        selector(resultingWorkspace),
        RelationMeaning.References,
    )

    private fun completeRelation(
        selector: SymbolSelector,
        meaning: RelationMeaning,
    ): RelationReadResult {
        val complete = RelationCompilation.complete(relationBatch(selector, meaning))
        return RelationReadResult.Complete(complete.batch, complete.coverage)
    }

    private fun relationBatch(
        selector: SymbolSelector,
        meaning: RelationMeaning,
    ): RelationBatch = RelationBatch.create(
        RelationRequest.start(selector, meaning, relationBudget()),
        emptyList(),
        RelationByteCount.parse(0L).refined(),
        RelationWorkCount.parse(0L).refined(),
    ).refined()

    private fun completeTraversal(): TraversalResult {
        val traversal = TraversalPlan.start(
            selector(workspace),
            RelationMeaning.References,
            traversalBudget(),
        ).refined()
        val page = TraversalPage.fromBoundary(traversal, emptyList(), 0L, 0L, 0L, 0).refined()
        return TraversalResult.complete(page)
    }

    private fun completeResultingDiagnostics(): DiagnosticCheckResult = completeDiagnostics(
        resultingWorkspace,
    )

    private fun completeDiagnostics(
        workspace: PublishedWorkspace,
        path: Path = targetPath,
    ): DiagnosticCheckResult {
        val complete = DiagnosticCompilation.complete(
            DiagnosticBatch.empty(diagnosticScope(workspace, path)),
        )
        return DiagnosticCheckResult.Complete(complete.batch, complete.coverage)
    }

    private fun diagnosticScope(
        workspace: PublishedWorkspace,
        path: Path = targetPath,
    ): DiagnosticScope = DiagnosticScope.fromCanonicalPaths(workspace.readLease, listOf(path)).refined()

    private fun workspace(generation: Long, state: String): PublishedWorkspace =
        PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                WorkspaceCandidate(root, WorkspaceStateIdentity(state)),
                WorkspaceEvidenceKind.entries.toSet(),
                listOf(sourceRoot),
            ).refined(),
            EvidenceGeneration.parse(generation).refined(),
        )

    private fun sourceRoot(): SourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence("app", ".", ":app", "main", "app/src/main/kotlin", SourceRootProvenance.Authored),
    ).refined()

    private fun canonicalRoot(raw: String): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(raw)).refined()

    private fun resourceBudget(): ResourceBudget = ResourceBudget(
        ResultLimit.parse(8).refined(),
        WorkUnitLimit.parse(8L).refined(),
        ElapsedTimeLimitMillis.parse(1_000L).refined(),
    )

    private fun relationBudget(): RelationBudget =
        RelationBudget(resourceBudget(), RelationByteLimit.parse(10_000L).refined())

    private fun traversalBudget(): TraversalBudget = TraversalBudget(
        ResultLimit.parse(8).refined(),
        TraversalByteLimit.parse(10_000L).refined(),
        WorkUnitLimit.parse(8L).refined(),
        ElapsedTimeLimitMillis.parse(1_000L).refined(),
        TraversalDepthLimit.parse(2).refined(),
        TraversalFrontierLimit.parse(8).refined(),
        relationBudget(),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
