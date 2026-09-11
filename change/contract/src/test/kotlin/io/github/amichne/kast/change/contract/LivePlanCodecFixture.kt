package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.symbol.contract.symbolSelectorFingerprint
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadContentView
import io.github.amichne.kast.workspace.contract.IdeReadEpochRevision
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.assertInstanceOf

internal fun detachedLivePlan(): LiveAddDeclarationChangePlan {
    val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
    val model = fixtureModel(root)
    val basis =
        LiveChangeBasis.observe(
                LiveSemanticReadReference(
                    workspaceRoot = root,
                    host = IdeReadHostLifetime.fromBoundary(UUID(0, 1)),
                    epoch = IdeReadEpochRevision.parse(7).refined(),
                    contentView = IdeReadContentView.SAVED_PSI_COMMITTED,
                    version = LiveSemanticReadReference.VERSION,
                ),
                model,
            )
            .refined()
    val target = fixtureTarget(root, basis)
    val evidence = fixtureEvidence()
    val verificationScope = fixtureVerificationScope(target, evidence)
    return LiveAddDeclarationChangePlan.issue(
        AdmittedLiveAddDeclarationPlanInput.restore(
                LivePlanningTarget(
                    basis,
                    target,
                    WorkspaceSourceContentHash.parse(sha256Hex("package sample\nfun service() = 1\n".toByteArray()))
                        .refined(),
                ),
                LivePlannedDeclaration(
                    AddDeclarationSourceText.parse("fun added() = 1").refined(),
                    ExpectedAddDeclarationDelta.admit("sample", "added", AddDeclarationKind.FUNCTION).refined(),
                ),
                LivePlanningEvidence(evidence, verificationScope),
            )
            .refined()
    )
}

private fun fixtureModel(root: CanonicalWorkspaceRoot): WorkspaceSearchScopeModel {
    val model =
        when (
            val result =
                WorkspaceSearchScopeModel.compile(
                    root,
                    ImportedWorkspaceModelState.COMPLETE,
                    listOf(
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = "app",
                            linkedBuildRoot = Path.of(root.value),
                            gradleProjectPath = ":app",
                            sourceSetName = "main",
                            sourceRoot = Path.of("/workspace/app/src/main/kotlin"),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        ),
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = "support",
                            linkedBuildRoot = Path.of(root.value),
                            gradleProjectPath = ":support",
                            sourceSetName = "test",
                            sourceRoot = Path.of("/workspace/support/src/test/kotlin"),
                            sourceKind = WorkspaceSourceRootKind.TEST,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        ),
                    ),
                )
        ) {
            is WorkspaceSearchScopeModelCompilation.Compiled -> result.model
            is WorkspaceSearchScopeModelCompilation.Rejected -> error(result.failures.toString())
        }
    return model
}

private fun fixtureTarget(root: CanonicalWorkspaceRoot, basis: LiveChangeBasis): PlannedDeclarationIdentity {
    val file =
        assertInstanceOf<SymbolDiscoveryFileIdentity.Workspace>(
            SymbolDiscoveryFileIdentity.fromBoundary(
                    root,
                    Path.of("/workspace/app/src/main/kotlin/sample/Service.kt"),
                    "file:///workspace/app/src/main/kotlin/sample/Service.kt",
                )
                .refined()
        )
    val compiler =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file = file,
                rawStartInclusive = 15,
                rawEndExclusive = 32,
                rawName = "service",
                rawQualifiedIdentity = "sample.service",
                kind = CompilerSymbolKind.FUNCTION,
                signature =
                    CanonicalCompilerSignature.function(
                            rawQualifiedIdentity = "sample.service",
                            rawReceiverType = null,
                            rawContextReceiverTypes = emptyList(),
                            rawValueParameterTypes = emptyList(),
                            rawTypeParameterCount = 0,
                        )
                        .refined(),
            )
            .refined()
    val scope = fixtureScope()
    val constraints = fixtureConstraints()
    val target =
        PlannedDeclarationIdentity.restore(
                basis = basis,
                evidence = compiler,
                fingerprint =
                    symbolSelectorFingerprint(
                        identity = SemanticReadIdentity.Live(basis.reference),
                        scope = scope,
                        evidence = compiler,
                        constraints = constraints,
                    ),
                scope = scope,
                constraints = constraints,
            )
            .refined()
    return target
}

private fun fixtureScope(): SymbolSearchScope {
    val scope =
        SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
    return scope
}

private fun fixtureConstraints(): SymbolDiscoveryConstraints {
    val constraints =
        SymbolDiscoveryConstraints(
            directory =
                SymbolDiscoveryDirectoryConstraint(
                    SymbolDiscoveryDirectory.parse("app/src/main/kotlin").refined(),
                    SymbolDiscoveryContainment.DESCENDANTS,
                ),
            packageName =
                SymbolDiscoveryPackageConstraint(
                    SymbolDiscoveryPackage.parse("sample").refined(),
                    SymbolDiscoveryContainment.DIRECT,
                ),
            declarationKinds = SymbolDiscoveryDeclarationKinds.from(setOf(CompilerSymbolKind.FUNCTION)).refined(),
            sourceSets =
                SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse("main").refined())).refined(),
        )
    return constraints
}

private fun fixtureEvidence(): DurableAddDeclarationPlanningEvidence {
    val projections = listOf("relation:complete", "traversal:complete", "diagnostic:complete")
    val fingerprint = sha256Hex(buildString { projections.forEach(::appendPlanningField) }.toByteArray())
    val evidence =
        DurableAddDeclarationPlanningEvidence.restore(
                relations =
                    listOf(
                        DurableAddDeclarationRelationEvidence(
                            AddDeclarationRelationMeaning.REFERENCES,
                            ChangePlanningEvidenceProjection.fromProven(projections[0]),
                            StableRelationEvidenceDigest.fromProven("a".repeat(64)),
                        )
                    ),
                traversals = listOf(ChangePlanningEvidenceProjection.fromProven(projections[1])),
                diagnostics = listOf(ChangePlanningEvidenceProjection.fromProven(projections[2])),
                fingerprint = ChangePlanningEvidenceFingerprintDocument(fingerprint),
            )
            .refined()
    return evidence
}

private fun fixtureVerificationScope(
    target: PlannedDeclarationIdentity,
    evidence: DurableAddDeclarationPlanningEvidence,
): LiveAddDeclarationVerificationScope {
    val relationBudget =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(100).refined(),
                WorkUnitLimit.parse(1000).refined(),
                ElapsedTimeLimitMillis.parse(2000).refined(),
            ),
            RelationByteLimit.parse(10000).refined(),
        )
    val verificationScope =
        LiveAddDeclarationVerificationScope.restore(
                relations = listOf(LivePlannedRelationRead(RelationMeaning.References, relationBudget)),
                traversals =
                    listOf(
                        LivePlannedTraversal(
                            RelationMeaning.References,
                            TraversalBudget(
                                records = ResultLimit.parse(100).refined(),
                                returnedBytes = TraversalByteLimit.parse(10000).refined(),
                                workUnits = WorkUnitLimit.parse(1000).refined(),
                                elapsedTime = ElapsedTimeLimitMillis.parse(2000).refined(),
                                depth = TraversalDepthLimit.parse(5).refined(),
                                frontier = TraversalFrontierLimit.parse(10).refined(),
                                oneHop = relationBudget,
                            ),
                        )
                    ),
                diagnostics = listOf(LivePlannedDiagnosticScope.restore(listOf(target.file.path)).refined()),
                evidence = evidence,
            )
            .refined()
    return verificationScope
}

private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
