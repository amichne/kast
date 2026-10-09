package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SemanticResolutionInputInventoryTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).value()
    private val owner = MovingLiveReadAuthorityFixture(root)
    private val model =
        (WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf("subject", "dependency", "unrelated").map { module ->
                    WorkspaceSourceRootBoundary(
                        module,
                        Path.of("/workspace"),
                        ":",
                        "main",
                        Path.of("/workspace/$module/src"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                },
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model
    private val subject = model.sourceRoots.single { it.module.value == "subject" }.module
    private val dependency = model.sourceRoots.single { it.module.value == "dependency" }.module
    private val unrelated = model.sourceRoots.single { it.module.value == "unrelated" }.module
    private val edges = mapOf(subject to setOf(dependency), dependency to emptySet(), unrelated to emptySet())
    private val graph = SemanticModuleDependencies.fromCompiler(model, edges).value()
    private val full = graph.closure(graph.modules).value()
    private val forward = graph.closure(setOf(subject)).value()
    private val stable = SemanticResolutionInputs(hash('a'), hash('b'), hash('c'))
    private val values = graph.modules.associateWith { stable }

    @Test
    fun `input admission requires exactly the closed module universe and detaches caller state`() {
        assertEquals(
            Refinement.Rejected(SemanticResolutionInputFailure.MissingModules(setOf(dependency))),
            SemanticResolutionInputInventory.fromCompiler(full, values - dependency),
        )
        assertEquals(
            Refinement.Rejected(SemanticResolutionInputFailure.UnknownModules(setOf(unrelated))),
            SemanticResolutionInputInventory.fromCompiler(forward, values),
        )
        val mutable = values.toMutableMap()
        val admitted = SemanticResolutionInputInventory.fromCompiler(full, mutable).value()
        mutable.clear()
        assertEquals(values, admitted.modules)
    }

    @Test
    fun `narrowing retains every forward dependency and the original complete graph proof`() {
        val admitted = SemanticResolutionInputInventory.fromCompiler(full, values).value().narrow(forward).value()
        assertEquals(mapOf(subject to stable, dependency to stable), admitted.modules)
        assertSame(graph, admitted.closure.graph)
        assertEquals(setOf(subject, dependency, unrelated), admitted.closure.graph.modules)
    }

    @Test
    fun `narrowing cannot manufacture missing inputs or substitute another graph`() {
        val admitted = SemanticResolutionInputInventory.fromCompiler(forward, values - unrelated).value()
        assertEquals(
            Refinement.Rejected(SemanticResolutionInputFailure.MissingModules(setOf(unrelated))),
            admitted.narrow(full),
        )
        val otherGraph = SemanticModuleDependencies.fromCompiler(model, edges).value()
        assertEquals(
            Refinement.Rejected(SemanticResolutionInputFailure.ForeignGraph),
            admitted.narrow(otherGraph.closure(setOf(subject)).value()),
        )
    }

    @Test
    fun `unrelated SDK change rejects whole workspace reuse but preserves forward reuse`() {
        val oldInputs = SemanticResolutionInputInventory.fromCompiler(full, values).value()
        val priorAuthority = owner.admit()
        val priorFull = snapshot(priorAuthority, full, oldInputs)
        val priorForward = snapshot(priorAuthority, forward, oldInputs.narrow(forward).value())
        val newInputs =
            SemanticResolutionInputInventory.fromCompiler(
                    full,
                    values + (unrelated to stable.copy(sdk = hash('d'))),
                )
                .value()
        val currentAuthority = owner.advance()
        assertEquals(
            Refinement.Rejected(SemanticSnapshotReuseFailure.ResolutionInputsChanged),
            snapshot(currentAuthority, full, newInputs).reuseFrom(priorFull),
        )
        assertInstanceOf(
            Refinement.Refined::class.java,
            snapshot(currentAuthority, forward, newInputs.narrow(forward).value()).reuseFrom(priorForward),
        )
    }

    @Test
    fun `each retained dependency input still rejects forward reuse when changed`() {
        val oldInputs = SemanticResolutionInputInventory.fromCompiler(forward, values - unrelated).value()
        val prior = snapshot(owner.admit(), forward, oldInputs)
        val authority = owner.advance()
        for (changed in
            listOf(
                stable.copy(sdk = hash('d')),
                stable.copy(classpath = hash('e')),
                stable.copy(compilerConfiguration = hash('f')),
            )) {
            val currentInputs =
                SemanticResolutionInputInventory.fromCompiler(
                        forward,
                        mapOf(subject to stable, dependency to changed),
                    )
                    .value()
            assertEquals(
                Refinement.Rejected(SemanticSnapshotReuseFailure.ResolutionInputsChanged),
                snapshot(authority, forward, currentInputs).reuseFrom(prior),
            )
        }
    }

    @Test
    fun `snapshot cannot bind sources to a different resolution input domain`() {
        val inputs = SemanticResolutionInputInventory.fromCompiler(full, values).value()
        assertEquals(
            Refinement.Rejected(SemanticSnapshotAdmissionFailure.ResolutionInputDomainMismatch),
            SemanticDependencySnapshot.fromCompiler(owner.admit(), inventory(forward), inputs),
        )
    }

    @Test
    fun `snapshot rejects input evidence from a separately issued graph even with equal edges`() {
        val otherGraph = SemanticModuleDependencies.fromCompiler(model, edges).value()
        val inputs =
            SemanticResolutionInputInventory.fromCompiler(
                    otherGraph.closure(setOf(subject)).value(),
                    values - unrelated,
                )
                .value()
        val authority = owner.admit()
        assertEquals(
            Refinement.Rejected(SemanticSnapshotAdmissionFailure.ResolutionInputDomainMismatch),
            SemanticDependencySnapshot.fromCompiler(authority, inventory(forward), inputs),
        )
        assertInstanceOf(Refinement.Refined::class.java, authority.withCurrentOwner { Unit })
    }

    private fun inventory(closure: SemanticDependencyClosure) =
        SemanticDependencyInventory.admit(
                closure,
                closure.modules.map { CompleteSemanticModuleSources.fromCompiler(graph, it, emptyList()).value() },
            )
            .value()

    private fun snapshot(
        authority: LiveSemanticReadAuthority,
        closure: SemanticDependencyClosure,
        inputs: SemanticResolutionInputInventory,
    ) = SemanticDependencySnapshot.fromCompiler(authority, inventory(closure), inputs).value()

    private fun hash(digit: Char) = WorkspaceSourceContentHash.parse(digit.toString().repeat(64)).value()
}

private fun <V, F> Refinement<V, F>.value(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
