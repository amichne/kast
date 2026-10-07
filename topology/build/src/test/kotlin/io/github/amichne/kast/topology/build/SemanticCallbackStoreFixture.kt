package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierPartition
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.topology.contract.CompleteSemanticModuleSources
import io.github.amichne.kast.topology.contract.SemanticDependencyInventory
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.topology.contract.SemanticDependencySource
import io.github.amichne.kast.topology.contract.SemanticModuleDependencies
import io.github.amichne.kast.topology.contract.SemanticResolutionInputs
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path

abstract class SemanticCallbackStoreFixture {
    protected val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).value()
    protected val owner = MovingLiveReadAuthorityFixture(root)
    protected val model =
        (WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        "main",
                        Path.of("/workspace"),
                        ":",
                        "main",
                        Path.of("/workspace/src"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model
    protected val sourceRoot = model.sourceRoots.single()
    protected val graph = SemanticModuleDependencies.fromCompiler(model, mapOf(sourceRoot.module to emptySet())).value()
    protected val file =
        SymbolDiscoveryFileIdentity.fromBoundary(
                root,
                Path.of("/workspace/src/Wrapper.kt"),
                "file:///workspace/src/Wrapper.kt",
            )
            .value()
    protected val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file,
                0,
                100,
                "wrapper",
                "fixture.wrapper",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function(
                        "fixture.wrapper",
                        null,
                        emptyList(),
                        listOf("()->Unit"),
                        0,
                    )
                    .value(),
            )
            .value()
    protected val store = SemanticCallbackFactStore(ReadLimits.Default)

    protected fun named(authority: LiveSemanticReadAuthority, meaning: RelationMeaning): RelationCompilation.Complete {
        val request =
            RelationRequest.start(
                summary(authority).formal.callable,
                meaning,
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(8).value(),
                        WorkUnitLimit.parse(32).value(),
                        ElapsedTimeLimitMillis.parse(1000).value(),
                    ),
                    RelationByteLimit.parse(100000).value(),
                ),
            )
        return RelationCompilation.complete(
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(0).value(),
                    RelationWorkCount.parse(0).value(),
                    RelationResultCount.parse(0).value(),
                )
                .value()
        )
    }

    protected fun suppliers(authority: LiveSemanticReadAuthority): CompleteCallbackSupplierInventory {
        val formal = summary(authority).formal
        val partition =
            CompleteCallbackSupplierPartition.fromCompiler(
                    formal,
                    emptyList(),
                    emptyList(),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .value()
        return CompleteCallbackSupplierInventory.fromCompiler(
                formal,
                RelationScopeFingerprint.from(formal.callable),
                listOf(partition),
            )
            .value()
    }

    protected fun snapshot(
        authority: LiveSemanticReadAuthority,
        digit: Char = 'a',
        includeSource: Boolean = true,
    ): SemanticDependencySnapshot {
        val hash = WorkspaceSourceContentHash.parse(digit.toString().repeat(64)).value()
        val sources =
            CompleteSemanticModuleSources.fromCompiler(
                    graph,
                    sourceRoot.module,
                    if (includeSource)
                        listOf(
                            SemanticDependencySource(
                                sourceRoot,
                                WorkspaceSourcePath.parse("src/Wrapper.kt").value(),
                                hash,
                            )
                        )
                    else emptyList(),
                )
                .value()
        val inventory =
            SemanticDependencyInventory.admit(graph.closure(setOf(sourceRoot.module)).value(), listOf(sources)).value()
        val external = WorkspaceSourceContentHash.parse("0".repeat(64)).value()
        return SemanticDependencySnapshot.fromCompiler(
                authority,
                inventory,
                SemanticResolutionInputs(external, external, external),
            )
            .value()
    }

    protected fun summary(authority: LiveSemanticReadAuthority, parameterStart: Int = 10): CallbackParameterSummary {
        val endpoint =
            RelationEndpoint.resolve(
                    authority,
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                    evidence,
                )
                .value()
        val formal =
            CallbackParameterIdentity.fromCompiler(
                    endpoint,
                    ValueArgumentPosition.parse(0).value(),
                    RelationOccurrence.fromBoundary(file, parameterStart, parameterStart + 10).value(),
                )
                .value()
        return CallbackParameterSummary.fromCompiler(
                formal,
                emptyList(),
                emptySet(),
                scan = CallbackInvocationScan.EXHAUSTIVE,
            )
            .value()
    }

    protected fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
