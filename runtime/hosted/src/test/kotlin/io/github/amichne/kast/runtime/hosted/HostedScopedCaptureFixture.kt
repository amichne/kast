package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.PositiveLimitFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.topology.contract.CompleteSemanticModuleSources
import io.github.amichne.kast.topology.contract.SemanticDependencyInventory
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.topology.contract.SemanticDependencySource
import io.github.amichne.kast.topology.contract.SemanticModuleDependencies
import io.github.amichne.kast.topology.contract.SemanticResolutionInputInventory
import io.github.amichne.kast.topology.contract.SemanticResolutionInputs
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path

/** Detached model and source observations exercise the production partition rule, not native compiler capture. */
internal abstract class HostedScopedCaptureFixture : HostedSemanticFactFixture() {
    protected val authority = owner.admit()

    protected fun model(
        paths: List<Pair<String, String>> =
            listOf("main" to "src", "dependency" to "dependency/src", "unrelated" to "unrelated/src")
    ) =
        (WorkspaceSearchScopeModel.compile(
                authority.workspaceRoot,
                ImportedWorkspaceModelState.COMPLETE,
                paths.map { (name, path) ->
                    WorkspaceSourceRootBoundary(
                        name,
                        Path.of("/workspace"),
                        ":",
                        "main",
                        Path.of("/workspace").resolve(path),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                },
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model

    protected val model = model()
    protected val main = model.sourceRoots.single { it.module.value == "main" }.module
    protected val dependency = model.sourceRoots.single { it.module.value == "dependency" }.module
    protected val modules = model.sourceRoots.mapTo(linkedSetOf()) { it.module }
    protected val endpoint = summary(authority).formal.callable
    protected val graph =
        SemanticModuleDependencies.fromCompiler(
                model,
                modules.associateWith {
                    if (it == main) setOf(dependency) else emptySet()
                },
            )
            .value()
    protected val allowance = HostedCallbackCaptureAllowance(budget())
    protected var parent: Refinement<ResourceBudget, PositiveLimitFailure> = Refinement.Refined(budget())
    protected var parentCalls = 0
    protected val attempts =
        HostedCallbackDependencyAttempts(
            counts,
            modules.mapTo(linkedSetOf<HostedCallbackDependencyUniverse>()) {
                HostedCallbackDependencyUniverse.Forward(it)
            } + HostedCallbackDependencyUniverse.WholeWorkspace,
        )

    protected fun captured(
        roots: Set<WorkspaceModuleIdentity>,
        selectedGraph: SemanticModuleDependencies = graph,
    ): SemanticDependencySnapshot {
        val closure = selectedGraph.closure(roots).value()
        val hash = WorkspaceSourceContentHash.parse("a".repeat(64)).value()
        val sources =
            closure.modules.map { module ->
                val root = selectedGraph.model.sourceRoots.single { it.module == module }
                CompleteSemanticModuleSources.fromCompiler(
                        selectedGraph,
                        module,
                        if (module == main)
                            listOf(
                                SemanticDependencySource(
                                    root,
                                    WorkspaceSourcePath.parse("src/Wrapper.kt").value(),
                                    hash,
                                )
                            )
                        else emptyList(),
                    )
                    .value()
            }
        val inventory = SemanticDependencyInventory.admit(closure, sources).value()
        val inputs =
            SemanticResolutionInputInventory.fromCompiler(
                    closure,
                    closure.modules.associateWith { SemanticResolutionInputs(hash, hash, hash) },
                )
                .value()
        return SemanticDependencySnapshot.fromCompiler(authority, inventory, inputs).value()
    }

    protected fun budget(work: Long = 40, millis: Long = 100) =
        ResourceBudget(
            ResultLimit.parse(8).value(),
            WorkUnitLimit.parse(work).value(),
            ElapsedTimeLimitMillis.parse(millis).value(),
        )

    protected fun partitions(
        selectedModel: WorkspaceSearchScopeModel = model,
        capture:
            (Set<WorkspaceModuleIdentity>, ResourceBudget) -> Refinement<
                    SemanticDependencySnapshot,
                    io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureFailure,
                >,
    ) =
        HostedReadCallbackPartitions(
            selectedModel,
            attempts,
            allowance,
            {
                parentCalls++
                parent
            },
            counts,
            HostedCallbackDependencyCapture(capture),
        )
}
