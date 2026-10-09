package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.topology.contract.CompleteSemanticModuleSources
import io.github.amichne.kast.topology.contract.SemanticModuleDependencies
import io.github.amichne.kast.topology.contract.SemanticResolutionInputs
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path

/** Pure model and input observations exercise production ownership and assembly, not native compiler/VFS execution. */
internal abstract class SemanticReadInputFixture {
    protected val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).value()
    protected val owner = MovingLiveReadAuthorityFixture(root)
    protected val authority = owner.admit()

    protected fun model() =
        (WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf("main", "dependency", "unrelated").map { name ->
                    WorkspaceSourceRootBoundary(
                        name,
                        Path.of("/workspace"),
                        ":",
                        "main",
                        Path.of("/workspace/$name/src"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                },
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model

    protected val model = model()
    protected val modules = model.sourceRoots.mapTo(linkedSetOf()) { it.module }
    protected val main = modules.single { it.value == "main" }
    protected val dependency = modules.single { it.value == "dependency" }
    protected val unrelated = modules.single { it.value == "unrelated" }

    protected fun graph(model: WorkspaceSearchScopeModel = this.model) =
        SemanticModuleDependencies.fromCompiler(
                model,
                modules.associateWith { if (it == main) setOf(dependency) else emptySet() },
            )
            .value()

    protected val graph = graph()
    protected val hash = WorkspaceSourceContentHash.parse("a".repeat(64)).value()
    protected val counts = Counts()
    protected var now = 0L
    protected var checkCanceled: () -> Unit = {}

    protected fun budget(work: Long = 100) =
        DependencyCaptureBudget(
            ResourceBudget(
                ResultLimit.parse(8).value(),
                WorkUnitLimit.parse(work).value(),
                ElapsedTimeLimitMillis.parse(100).value(),
            ),
            { now },
            { checkCanceled() },
            counts,
        )

    protected fun module(
        graph: SemanticModuleDependencies,
        identity: io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity,
    ) =
        NativeModuleInputs(
            CompleteSemanticModuleSources.fromCompiler(graph, identity, emptyList()).value(),
            SemanticResolutionInputs(hash, hash, hash),
        )

    protected fun read() = SemanticDependencyReadInputs(authority, model) { graph: SemanticModuleDependencies -> graph }

    protected class Counts : IntellijReadObservation {
        private val values = mutableMapOf<IntellijReadCounter, Int>()

        operator fun get(counter: IntellijReadCounter) = values[counter] ?: 0

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            values[counter] = get(counter) + amount
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
    }

    protected fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> throw AssertionError("Rejected fixture: $failure")
        }
}
