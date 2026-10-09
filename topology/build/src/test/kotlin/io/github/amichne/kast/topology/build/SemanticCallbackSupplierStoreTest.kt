package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.CompleteNamedRelationPartition
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.topology.contract.CompleteSemanticModuleSources
import io.github.amichne.kast.topology.contract.SemanticDependencyInventory
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.topology.contract.SemanticDependencySource
import io.github.amichne.kast.topology.contract.SemanticModuleDependencies
import io.github.amichne.kast.topology.contract.SemanticResolutionInputInventory
import io.github.amichne.kast.topology.contract.SemanticResolutionInputs
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
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

class SemanticCallbackSupplierStoreTest : SemanticCallbackStoreFixture() {
    @Test
    fun `supplier absence is reusable only after complete current universe validation`() {
        val prior = snapshot(owner.admit())
        val inventory = suppliers(prior.authority)
        assertEquals(SemanticCallbackPublication.Published, store.publishSuppliers(prior, inventory))
        assertInstanceOf(
            SemanticCallbackSupplierLookup.Current::class.java,
            store.findSuppliers(prior, inventory.root, inventory.domain),
        )
        val current = snapshot(owner.advance())
        val currentInventory = suppliers(current.authority)
        assertInstanceOf(
            SemanticCallbackSupplierLookup.Reusable::class.java,
            store.findSuppliers(current, currentInventory.root, currentInventory.domain),
        )
        val changed = snapshot(owner.advance(), 'b')
        val changedInventory = suppliers(changed.authority)
        assertInstanceOf(
            SemanticCallbackSupplierLookup.Invalidated::class.java,
            store.findSuppliers(changed, changedInventory.root, changedInventory.domain),
        )
        assertEquals(
            SemanticCallbackSupplierLookup.Missing,
            store.findSuppliers(changed, changedInventory.root, changedInventory.domain),
        )
    }

    @Test
    fun `supplier and body partitions share one capacity and retirement owner`() {
        val bounded =
            SemanticCallbackFactStore(
                ReadLimits.resolve(properties = mapOf(ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.propertyKey to "1"))
                    .value()
            )
        val current = snapshot(owner.admit())
        val body = summary(current.authority)
        val inventory = suppliers(current.authority)
        assertEquals(SemanticCallbackPublication.Published, bounded.publish(current, body))
        assertEquals(SemanticCallbackPublication.CapacityExceeded, bounded.publishSuppliers(current, inventory))
        assertInstanceOf(SemanticCallbackLookup.Current::class.java, bounded.find(current, body.formal))
        bounded.retire()
        assertInstanceOf(
            SemanticCallbackSupplierLookup.Rejected::class.java,
            bounded.findSuppliers(current, inventory.root, inventory.domain),
        )
    }

    @Test
    fun `formal forward closure cannot authorize absence in other supplier modules`() {
        val authority = owner.admit()
        val widerModel = widerModel()
        val modules = widerModel.sourceRoots.map { it.module }
        val widerGraph =
            SemanticModuleDependencies.fromCompiler(widerModel, modules.associateWith { emptySet() }).value()
        val selected = widerModel.sourceRoots.single { it.sourceRoot.value == "/workspace/src" }.module
        val narrowInventory =
            SemanticDependencyInventory.admit(
                    widerGraph.closure(setOf(selected)).value(),
                    listOf(CompleteSemanticModuleSources.fromCompiler(widerGraph, selected, emptyList()).value()),
                )
                .value()
        val hash = WorkspaceSourceContentHash.parse("a".repeat(64)).value()
        val narrow =
            SemanticDependencySnapshot.fromCompiler(
                    authority,
                    narrowInventory,
                    SemanticResolutionInputInventory.fromCompiler(
                            narrowInventory.closure,
                            narrowInventory.closure.modules.associateWith {
                                SemanticResolutionInputs(hash, hash, hash)
                            },
                        )
                        .value(),
                )
                .value()
        val inventory = suppliers(authority)
        assertEquals(
            SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.SupplierUniverseIncomplete),
            store.publishSuppliers(narrow, inventory),
        )
        assertEquals(
            SemanticCallbackSupplierLookup.Rejected(SemanticCallbackStoreFailure.SupplierUniverseIncomplete),
            store.findSuppliers(narrow, inventory.root, inventory.domain),
        )
        val callers = named(authority, RelationMeaning.Callers)
        assertEquals(
            SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.CallerUniverseIncomplete),
            store.publishNamed(narrow, CompleteNamedRelationPartition.fromCompiler(callers).value()),
        )
        assertEquals(
            SemanticNamedRelationLookup.Rejected(SemanticCallbackStoreFailure.CallerUniverseIncomplete),
            store.findNamed(narrow, callers.batch.request),
        )
    }

    @Test
    fun `new source in another module invalidates previously complete supplier absence`() {
        val widerModel = widerModel()
        val modules = widerModel.sourceRoots.map { it.module }.toSet()
        val widerGraph =
            SemanticModuleDependencies.fromCompiler(widerModel, modules.associateWith { emptySet() }).value()
        val hash = WorkspaceSourceContentHash.parse("a".repeat(64)).value()
        fun complete(
            authority: LiveSemanticReadAuthority,
            added: Boolean,
            forwardOnly: Boolean = false,
        ): SemanticDependencySnapshot {
            val roots = widerModel.sourceRoots.filter { !forwardOnly || it.sourceRoot.value == "/workspace/src" }
            val inventories = roots.map { source ->
                val path =
                    if (source.sourceRoot.value == "/workspace/src") "src/Wrapper.kt" else "other/src/NewSupplier.kt"
                val files =
                    if (source.sourceRoot.value == "/workspace/src" || added)
                        listOf(SemanticDependencySource(source, WorkspaceSourcePath.parse(path).value(), hash))
                    else emptyList()
                CompleteSemanticModuleSources.fromCompiler(widerGraph, source.module, files).value()
            }
            val inventory =
                SemanticDependencyInventory.admit(
                        widerGraph.closure(roots.map { it.module }.toSet()).value(),
                        inventories,
                    )
                    .value()
            return SemanticDependencySnapshot.fromCompiler(
                    authority,
                    inventory,
                    SemanticResolutionInputInventory.fromCompiler(
                            inventory.closure,
                            inventory.closure.modules.associateWith { SemanticResolutionInputs(hash, hash, hash) },
                        )
                        .value(),
                )
                .value()
        }
        val prior = complete(owner.admit(), false)
        val old = suppliers(prior.authority)
        assertEquals(SemanticCallbackPublication.Published, store.publishSuppliers(prior, old))
        val callers = named(prior.authority, RelationMeaning.Callers)
        val callees = named(prior.authority, RelationMeaning.Callees)
        assertEquals(
            SemanticCallbackPublication.Published,
            store.publishNamed(prior, CompleteNamedRelationPartition.fromCompiler(callers).value()),
        )
        assertEquals(
            SemanticCallbackPublication.Published,
            store.publishNamed(
                complete(prior.authority, false, true),
                CompleteNamedRelationPartition.fromCompiler(callees).value(),
            ),
        )
        val current = complete(owner.advance(), true)
        assertInstanceOf(
            SemanticNamedRelationLookup.Invalidated::class.java,
            store.findNamed(current, named(current.authority, RelationMeaning.Callers).batch.request),
        )
        assertInstanceOf(
            SemanticNamedRelationLookup.Reusable::class.java,
            store.findNamed(
                complete(current.authority, true, true),
                named(current.authority, RelationMeaning.Callees).batch.request,
            ),
        )
        val formal = summary(current.authority).formal
        assertInstanceOf(
            SemanticCallbackSupplierLookup.Invalidated::class.java,
            store.findSuppliers(current, formal, old.domain),
        )
        assertEquals(SemanticCallbackSupplierLookup.Missing, store.findSuppliers(current, formal, old.domain))
    }

    @Test
    fun `named direction is keyed and shares bounded capacity with supplier and body facts`() {
        val current = snapshot(owner.admit())
        val callers = named(current.authority, RelationMeaning.Callers)
        val partition = CompleteNamedRelationPartition.fromCompiler(callers).value()
        val bounded =
            SemanticCallbackFactStore(
                ReadLimits.resolve(properties = mapOf(ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.propertyKey to "1"))
                    .value()
            )
        assertEquals(SemanticCallbackPublication.Published, bounded.publishNamed(current, partition))
        assertInstanceOf(
            SemanticNamedRelationLookup.Reusable::class.java,
            bounded.findNamed(current, callers.batch.request),
        )
        assertEquals(
            SemanticNamedRelationLookup.Missing,
            bounded.findNamed(current, named(current.authority, RelationMeaning.Callees).batch.request),
        )
        assertEquals(SemanticCallbackPublication.CapacityExceeded, bounded.publish(current, summary(current.authority)))
        assertEquals(
            SemanticCallbackPublication.CapacityExceeded,
            bounded.publishSuppliers(current, suppliers(current.authority)),
        )
        bounded.retire()
        assertInstanceOf(
            SemanticNamedRelationLookup.Rejected::class.java,
            bounded.findNamed(current, callers.batch.request),
        )
    }

    private fun widerModel(): WorkspaceSearchScopeModel =
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
                    ),
                    WorkspaceSourceRootBoundary(
                        "other",
                        Path.of("/workspace"),
                        ":other",
                        "main",
                        Path.of("/workspace/other/src"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    ),
                ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model
}
