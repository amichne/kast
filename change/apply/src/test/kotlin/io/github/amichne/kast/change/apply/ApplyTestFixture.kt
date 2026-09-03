package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddDeclarationPlanResult
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidenceInput
import io.github.amichne.kast.change.contract.AddDeclarationSourceText
import io.github.amichne.kast.change.contract.AddFileChangePlan
import io.github.amichne.kast.change.contract.AddFilePlanRequest
import io.github.amichne.kast.change.contract.AddFilePlanResult
import io.github.amichne.kast.change.contract.AddFileTargetObservation
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.contract.CreatableKotlinFileTarget
import io.github.amichne.kast.change.contract.EditableMutationTarget
import io.github.amichne.kast.change.contract.ExistingDeclarationSourceText
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.KotlinFileSourceText
import io.github.amichne.kast.change.contract.KotlinIdentifier
import io.github.amichne.kast.change.contract.MutationTargetObservation
import io.github.amichne.kast.change.contract.ObservedMutationTargetState
import io.github.amichne.kast.change.contract.RenameSymbolChangePlan
import io.github.amichne.kast.change.contract.RenameSymbolOccurrence
import io.github.amichne.kast.change.contract.RenameSymbolOccurrenceRole
import io.github.amichne.kast.change.contract.RenameSymbolOccurrenceSet
import io.github.amichne.kast.change.contract.RenameSymbolPlanRequest
import io.github.amichne.kast.change.contract.RenameSymbolPlanResult
import io.github.amichne.kast.change.contract.ReplaceDeclarationChangePlan
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanRequest
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanResult
import io.github.amichne.kast.change.contract.ReplaceDeclarationTarget
import io.github.amichne.kast.change.contract.ReplacementDeclarationSourceText
import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.change.plan.PureAddFilePlanningService
import io.github.amichne.kast.change.plan.PureRenameSymbolPlanningService
import io.github.amichne.kast.change.plan.PureReplaceDeclarationPlanningService
import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
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
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
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

internal class ApplyTestFixture(
    private val classLike: Boolean = false,
) {
    val sourceText = if (classLike) {
        "package sample\n\nclass service {\n}\n"
    } else {
        "package sample\n\nfun service(): Int = 0\n"
    }
    private val root = canonicalRoot("/workspace")
    private val generation = EvidenceGeneration.parse(11L).refined()
    private val targetPath = Path.of("/workspace/app/src/main/kotlin/sample/Service.kt")
    private val anchorStart = sourceText.indexOf(if (classLike) "class service" else "fun service")
    private val anchorEnd = if (classLike) sourceText.indexOf('}') + 1 else sourceText.lastIndexOf('\n')
    private val sourceRoot = sourceRoot(SourceRootProvenance.Authored, ":app")
    val workspace = workspace()
    private val selector = selector()
    val plan = (PureAddDeclarationPlanningService().plan(planRequest()) as AddDeclarationPlanResult.Planned).plan

    fun request(
        plan: ChangePlan = this.plan,
        current: PublishedWorkspace = workspace,
        scope: RequestedMutationWriteScope = exactScope(plan, current),
    ): AddDeclarationApplyRequest = AddDeclarationApplyRequest(plan, current, scope)

    fun exactScope(
        plan: ChangePlan = this.plan,
        current: PublishedWorkspace = workspace,
    ): RequestedMutationWriteScope =
        RequestedMutationWriteScope(current.root, plan.writes.entries.mapTo(linkedSetOf()) { it.source })

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

    fun addFilePlan(): AddFileChangePlan {
        val path = Path.of("/workspace/app/src/main/kotlin/sample/Added.kt")
        val file = SymbolDiscoveryFileIdentity.fromBoundary(
            workspace.root,
            path,
            "file://$path",
        ).refined() as SymbolDiscoveryFileIdentity.Workspace
        val target = CreatableKotlinFileTarget.admit(
            AddFileTargetObservation(workspace, file, sourceRoot.owner),
        ).refined()
        val result = PureAddFilePlanningService().plan(
            AddFilePlanRequest(
                target,
                KotlinFileSourceText.parse("package sample\n\nclass Added\n").refined(),
            ),
        )
        return (result as AddFilePlanResult.Planned).plan
    }

    fun replaceDeclarationPlan(): ReplaceDeclarationChangePlan {
        val request = planRequest()
        val current = sourceText.substring(anchorStart, anchorEnd)
        val target = ReplaceDeclarationTarget.admit(
            request.target,
            ExistingDeclarationSourceText.parse(current).refined(),
        ).refined()
        val result = PureReplaceDeclarationPlanningService().plan(
            ReplaceDeclarationPlanRequest(
                target,
                ReplacementDeclarationSourceText.parse("fun service(): Int = 1").refined(),
                request.evidence,
            ),
        )
        return (result as ReplaceDeclarationPlanResult.Planned).plan
    }

    fun absent(plan: ChangePlan): ObservedAbsentMutationSource =
        ObservedAbsentMutationSource.fromPhysicalBoundary(
            plan.writes.entries.single().source,
            SourceWriteAccess.Writable,
        )

    fun existing(
        plan: ChangePlan,
        text: String,
    ): ObservedMutationSource = ObservedMutationSource.capture(
        plan.writes.entries.single().source,
        text.toByteArray(),
        SourceWriteAccess.Writable,
    ).refined()

    fun observed(
        text: String = sourceText,
        access: SourceWriteAccess = SourceWriteAccess.Writable,
    ): ObservedMutationSource = ObservedMutationSource.capture(
        plan.target.file,
        text.toByteArray(),
        access,
    ).refined()

    fun workspace(
        rawRoot: String = "/workspace",
        generationValue: Long = 11L,
        sourceState: String = "state-11",
        provenance: SourceRootProvenance = SourceRootProvenance.Authored,
        projectPath: String = ":app",
    ): PublishedWorkspace {
        val admittedRoot = canonicalRoot(rawRoot)
        val admittedGeneration = EvidenceGeneration.parse(generationValue).refined()
        return PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                WorkspaceCandidate(admittedRoot, WorkspaceStateIdentity(sourceState)),
                WorkspaceEvidenceKind.entries.toSet(),
                listOf(sourceRoot(provenance, projectPath)),
            ).refined(),
            admittedGeneration,
        )
    }

    fun otherFile(): SymbolDiscoveryFileIdentity.Workspace {
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "other",
            workspace.readLease,
            Path.of("/workspace/app/src/main/kotlin/sample/Other.kt"),
            "file:///workspace/app/src/main/kotlin/sample/Other.kt",
            0,
        ).refined()
        return (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).file
            as SymbolDiscoveryFileIdentity.Workspace
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
            relations = listOf(completeRelation()),
            traversals = listOf(completeTraversal()),
            diagnostics = listOf(completeDiagnostic()),
        ),
    )

    private fun editableTarget(): EditableMutationTarget = EditableMutationTarget.admit(
        MutationTargetObservation(
            workspace,
            selector,
            sourceRoot.owner,
            ObservedMutationTargetState(
                workspace.readLease,
                selector.file,
                WorkspaceSourceContentHash.parse(sha256(sourceText.toByteArray())).refined(),
            ),
        ),
    ).refined()

    private fun selector(): SymbolSelector {
        val scope = SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.INCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(workspace.readLease, scope),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                SymbolDiscoveryPattern.parse("service").refined(),
                SymbolDiscoveryMatch.FUZZY,
            ),
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
            if (classLike) CompilerSymbolKind.CLASSLIKE else CompilerSymbolKind.FUNCTION,
            if (classLike) {
                CanonicalCompilerSignature.classLike("sample.service").refined()
            } else {
                CanonicalCompilerSignature.function(
                    "sample.service",
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                ).refined()
            },
        ).refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun completeRelation(): RelationReadResult {
        val request = RelationRequest.start(selector, RelationMeaning.References, relationBudget())
        val batch = RelationBatch.create(
            request,
            emptyList(),
            RelationByteCount.parse(0L).refined(),
            RelationWorkCount.parse(0L).refined(),
            RelationResultCount.parse(0).refined(),
        ).refined()
        val complete = RelationCompilation.complete(batch)
        return RelationReadResult.Complete(complete.batch, complete.coverage)
    }

    private fun completeTraversal(): TraversalResult {
        val plan = TraversalPlan.start(selector, RelationMeaning.References, traversalBudget()).refined()
        val page = TraversalPage.fromBoundary(plan, emptyList(), 0L, 0L, 0L, 0).refined()
        return TraversalResult.complete(page)
    }

    private fun completeDiagnostic(): DiagnosticCheckResult {
        val scope = DiagnosticScope.fromCanonicalPaths(workspace.readLease, listOf(targetPath)).refined()
        val complete = DiagnosticCompilation.complete(DiagnosticBatch.empty(scope))
        return DiagnosticCheckResult.Complete(complete.batch, complete.coverage)
    }

    private fun sourceRoot(
        provenance: SourceRootProvenance,
        projectPath: String,
    ): SourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence(
            "app",
            ".",
            projectPath,
            "main",
            "app/src/main/kotlin",
            provenance,
        ),
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

internal fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error(failure.toString())
}
