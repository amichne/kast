package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackEndpointReadmissions
import io.github.amichne.kast.relation.contract.CallbackSummaryCacheLookup
import io.github.amichne.kast.relation.contract.CallbackSupplierCacheLookup
import io.github.amichne.kast.relation.contract.NamedRelationCacheLookup
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.readmitNamedRelations
import io.github.amichne.kast.topology.contract.CompleteSemanticModuleSources
import io.github.amichne.kast.topology.contract.SemanticDependencyInventory
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.topology.contract.SemanticDependencySource
import io.github.amichne.kast.topology.contract.SemanticModuleDependencies
import io.github.amichne.kast.topology.contract.SemanticResolutionInputInventory
import io.github.amichne.kast.topology.contract.SemanticResolutionInputs
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedCallbackResolutionInputsTest : HostedSemanticFactFixture() {
    private val inputs = SemanticResolutionInputs(hash('a'), hash('b'), hash('c'))
    private val changed = inputs.copy(classpath = hash('d'))

    @Test
    fun `unrelated external change permits current compiler restoration of a forward formal`() {
        val prior = wideSnapshot(owner.admit(), inputs)
        val old = summary(prior.authority)
        HostedCallbackFactCache(prior, store, counts).retain(old)
        val current = wideSnapshot(owner.advance(), changed)
        val fresh = summary(current.authority)
        var restorations = 0
        assertEquals(
            CallbackSummaryCacheLookup.Found(fresh),
            HostedCallbackFactCache(current, store, counts).find(fresh.formal) {
                assertEquals(old, it)
                restorations++
                Refinement.Refined(fresh)
            },
        )
        assertEquals(1, restorations)
    }

    @Test
    fun `unrelated external change preserves a named callee partition with current endpoint proof`() {
        val prior = wideSnapshot(owner.admit(), inputs)
        HostedCallbackFactCache(prior, store, counts).namedRelations.retain(named(prior.authority))
        val current = wideSnapshot(owner.advance(), changed)
        val request = named(current.authority).batch.request
        var restorations = 0
        val found =
            HostedCallbackFactCache(current, store, counts).namedRelations.find(request) { partition ->
                restorations++
                val endpoints =
                    partition.endpoints.associateWith { endpoint ->
                        val resolved = endpoint as RelationEndpoint.Resolved
                        RelationEndpoint.resolve(
                                current.authority,
                                resolved.scope,
                                resolved.evidence,
                                resolved.constraints,
                            )
                            .value()
                    }
                CallbackEndpointReadmissions.fromCompiler(current.authority, endpoints)
                    .value()
                    .readmitNamedRelations(partition, request, RelationWorkCount.parse(1).value())
            }
        assertInstanceOf(NamedRelationCacheLookup.Found::class.java, found)
        assertEquals(request, (found as NamedRelationCacheLookup.Found).complete.batch.request)
        assertEquals(1, restorations)
    }

    @Test
    fun `whole supplier absence proof is invalidated before restoration on unrelated external change`() {
        val prior = wideSnapshot(owner.admit(), inputs)
        val inventory = suppliers(prior.authority)
        HostedCallbackFactCache(prior, store, counts).suppliers.retain(inventory)
        val current = wideSnapshot(owner.advance(), changed)
        assertEquals(
            CallbackSupplierCacheLookup.Miss,
            HostedCallbackFactCache(current, store, counts).suppliers.find(
                summary(current.authority).formal,
                inventory.domain,
            ) {
                error("The full supplier universe changed")
            },
        )
    }

    @Test
    fun `whole named caller absence proof is invalidated on unrelated external change`() {
        val prior = wideSnapshot(owner.admit(), inputs)
        HostedCallbackFactCache(prior, store, counts)
            .namedRelations
            .retain(named(prior.authority, RelationMeaning.Callers))
        val current = wideSnapshot(owner.advance(), changed)
        val request = named(current.authority, RelationMeaning.Callers).batch.request
        assertEquals(
            NamedRelationCacheLookup.Miss,
            HostedCallbackFactCache(current, store, counts).namedRelations.find(request) {
                error("The full caller universe changed")
            },
        )
    }

    private fun wideSnapshot(
        authority: LiveSemanticReadAuthority,
        unrelatedInputs: SemanticResolutionInputs,
    ): SemanticDependencySnapshot {
        val root = authority.workspaceRoot
        val model = wideModel(root)
        val graph =
            SemanticModuleDependencies.fromCompiler(
                    model,
                    model.sourceRoots.associate { it.module to emptySet<WorkspaceModuleIdentity>() },
                )
                .value()
        val closure = graph.closure(graph.modules).value()
        val sources =
            model.sourceRoots.map { source ->
                CompleteSemanticModuleSources.fromCompiler(
                        graph,
                        source.module,
                        if (source.module.value == "main")
                            listOf(
                                SemanticDependencySource(
                                    source,
                                    WorkspaceSourcePath.parse("src/Wrapper.kt").value(),
                                    hash('0'),
                                )
                            )
                        else emptyList(),
                    )
                    .value()
            }
        val inventory = SemanticDependencyInventory.admit(closure, sources).value()
        val resolution =
            SemanticResolutionInputInventory.fromCompiler(
                    closure,
                    graph.modules.associateWith { if (it.value == "unrelated") unrelatedInputs else inputs },
                )
                .value()
        return SemanticDependencySnapshot.fromCompiler(authority, inventory, resolution).value()
    }

    private fun wideModel(root: CanonicalWorkspaceRoot) =
        (WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf("main" to "src", "unrelated" to "unrelated/src").map { (name, path) ->
                    WorkspaceSourceRootBoundary(
                        name,
                        Path.of(root.value),
                        ":",
                        "main",
                        Path.of(root.value).resolve(path),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                },
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model

    private fun hash(digit: Char) = WorkspaceSourceContentHash.parse(digit.toString().repeat(64)).value()
}
