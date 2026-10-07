package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SemanticDependencySnapshotTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).value()
    private val owner = MovingLiveReadAuthorityFixture(root)
    private val model =
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
    private val module = model.sourceRoots.single().module
    private val graph = SemanticModuleDependencies.fromCompiler(model, mapOf(module to emptySet())).value()
    private val inventory =
        SemanticDependencyInventory.admit(
                graph.closure(setOf(module)).value(),
                listOf(CompleteSemanticModuleSources.fromCompiler(graph, module, emptyList()).value()),
            )
            .value()
    private val inputs = SemanticResolutionInputs(hash('a'), hash('b'), hash('c'))

    @Test
    fun `unchanged dependencies gain explicit current proof without reviving previous authority`() {
        val prior = SemanticDependencySnapshot.fromCompiler(owner.admit(), inventory, inputs).value()
        val current = SemanticDependencySnapshot.fromCompiler(owner.advance(), inventory, inputs).value()
        val proof = current.reuseFrom(prior).value()
        assertEquals(prior, proof.previous)
        assertEquals(current, proof.current)
        assertInstanceOf(Refinement.Rejected::class.java, prior.authority.withCurrentOwner { Unit })
        assertInstanceOf(Refinement.Refined::class.java, proof.current.authority.withCurrentOwner { Unit })
    }

    @Test
    fun `unchanged source bytes cannot hide a replaced classpath or compiler option`() {
        val prior = SemanticDependencySnapshot.fromCompiler(owner.admit(), inventory, inputs).value()
        val current =
            SemanticDependencySnapshot.fromCompiler(
                    owner.advance(),
                    inventory,
                    inputs.copy(classpath = hash('d')),
                )
                .value()
        assertEquals(
            SemanticSnapshotReuseFailure.ResolutionInputsChanged,
            (current.reuseFrom(prior) as Refinement.Rejected).failure,
        )
        val configuration =
            SemanticDependencySnapshot.fromCompiler(
                    current.authority,
                    inventory,
                    inputs.copy(compilerConfiguration = hash('e')),
                )
                .value()
        assertEquals(
            SemanticSnapshotReuseFailure.ResolutionInputsChanged,
            (configuration.reuseFrom(prior) as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `replaced SDK rejects reuse even when sources classpath and compiler settings match`() {
        val prior = SemanticDependencySnapshot.fromCompiler(owner.admit(), inventory, inputs).value()
        val current =
            SemanticDependencySnapshot.fromCompiler(owner.advance(), inventory, inputs.copy(sdk = hash('f'))).value()
        assertEquals(
            Refinement.Rejected(SemanticSnapshotReuseFailure.ResolutionInputsChanged),
            current.reuseFrom(prior),
        )
    }

    @Test
    fun `environment movement rejects otherwise identical inventories`() {
        val prior = SemanticDependencySnapshot.fromCompiler(owner.admit(), inventory, inputs).value()
        val current = SemanticDependencySnapshot.fromCompiler(owner.advanceEnvironment(), inventory, inputs).value()
        assertInstanceOf(
            SemanticSnapshotReuseFailure.Environment::class.java,
            (current.reuseFrom(prior) as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `proof admission rejects retirement or intervening epoch movement`() {
        val prior = SemanticDependencySnapshot.fromCompiler(owner.admit(), inventory, inputs).value()
        val current = SemanticDependencySnapshot.fromCompiler(owner.advance(), inventory, inputs).value()
        owner.advance()
        assertInstanceOf(Refinement.Rejected::class.java, current.reuseFrom(prior))
        owner.retire()
        assertInstanceOf(
            Refinement.Rejected::class.java,
            SemanticDependencySnapshot.fromCompiler(current.authority, inventory, inputs),
        )
    }

    private fun hash(digit: Char) = WorkspaceSourceContentHash.parse(digit.toString().repeat(64)).value()
}

private fun <V, F> Refinement<V, F>.value(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
