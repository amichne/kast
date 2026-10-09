package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
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

class SemanticDependencyInventoryTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
    private val model =
        (WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf("entry", "library", "independent").map { name ->
                    WorkspaceSourceRootBoundary(
                        name,
                        Path.of("/workspace"),
                        ":$name",
                        "main",
                        Path.of("/workspace/$name/src"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                },
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model
    private val roots = model.sourceRoots.associateBy { it.module.value }
    private val entry = roots.getValue("entry").module
    private val library = roots.getValue("library").module
    private val independent = roots.getValue("independent").module
    private val graph =
        SemanticModuleDependencies.fromCompiler(
                model,
                mapOf(entry to setOf(library), library to emptySet(), independent to emptySet()),
            )
            .refined()

    @Test
    fun `module completion admission preserves its exact graph and module owner`() {
        val completed = completed("entry")
        org.junit.jupiter.api.Assertions.assertSame(completed, completed.admitOwner(graph, entry).refined())
        assertEquals(
            Refinement.Rejected(SemanticInventoryFailure.ModuleCoverage(setOf(library), setOf(entry))),
            completed.admitOwner(graph, library),
        )
        val equalGraph =
            SemanticModuleDependencies.fromCompiler(
                    model,
                    mapOf(entry to setOf(library), library to emptySet(), independent to emptySet()),
                )
                .refined()
        assertEquals(
            Refinement.Rejected(SemanticInventoryFailure.DependencyGraphMismatch),
            completed.admitOwner(equalGraph, entry),
        )
    }

    @Test
    fun `missing module inventory cannot establish an empty dependency closure`() {
        val closure = graph.closure(setOf(entry)).refined()
        val result = SemanticDependencyInventory.admit(closure, listOf(completed("entry")))
        assertEquals(
            SemanticInventoryFailure.ModuleCoverage(setOf(library), emptySet()),
            (result as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `explicit empty inventories establish absence until a supplier is added`() {
        val closure = graph.closure(setOf(entry)).refined()
        val prior =
            SemanticDependencyInventory.admit(
                    closure,
                    listOf(completed("entry"), completed("library")),
                )
                .refined()
        val current =
            SemanticDependencyInventory.admit(
                    closure,
                    listOf(completed("entry"), completed("library", file("library", "Supplier.kt", 'a'))),
                )
                .refined()
        val changed = assertInstanceOf(SemanticInventoryComparison.Changed::class.java, prior.compare(current))
        assertEquals(setOf(WorkspaceSourcePath.parse("library/src/Supplier.kt").refined()), changed.added)
        assertEquals(emptySet<WorkspaceSourcePath>(), changed.modified)
        assertEquals(emptySet<WorkspaceSourcePath>(), changed.removed)
    }

    @Test
    fun `deleted source invalidates complete inventory with exact removed identity`() {
        val closure = graph.closure(setOf(entry)).refined()
        val prior =
            SemanticDependencyInventory.admit(
                    closure,
                    listOf(completed("entry"), completed("library", file("library", "Supplier.kt", 'a'))),
                )
                .refined()
        val current =
            SemanticDependencyInventory.admit(closure, listOf(completed("entry"), completed("library"))).refined()
        assertEquals(
            SemanticInventoryComparison.Changed(
                emptySet(),
                setOf(WorkspaceSourcePath.parse("library/src/Supplier.kt").refined()),
                emptySet(),
            ),
            prior.compare(current),
        )
    }

    @Test
    fun `changed compiler dependency edge invalidates identical empty source inventories`() {
        val changed =
            SemanticModuleDependencies.fromCompiler(
                    model,
                    mapOf(entry to emptySet(), library to emptySet(), independent to emptySet()),
                )
                .refined()
        val roots = setOf(entry, library, independent)
        val prior =
            SemanticDependencyInventory.admit(
                    graph.closure(roots).refined(),
                    listOf(completed("entry"), completed("library"), completed("independent")),
                )
                .refined()
        val current =
            SemanticDependencyInventory.admit(
                    changed.closure(roots).refined(),
                    roots.map { CompleteSemanticModuleSources.fromCompiler(changed, it, emptyList()).refined() },
                )
                .refined()
        assertEquals(prior.files, current.files)
        assertEquals(SemanticInventoryComparison.DomainChanged, prior.compare(current))
    }

    @Test
    fun `body change invalidates consumers while independent closure remains equal`() {
        val closure = graph.closure(setOf(entry)).refined()
        val prior =
            SemanticDependencyInventory.admit(
                    closure,
                    listOf(completed("entry"), completed("library", file("library", "Library.kt", 'a'))),
                )
                .refined()
        val current =
            SemanticDependencyInventory.admit(
                    closure,
                    listOf(completed("entry"), completed("library", file("library", "Library.kt", 'b'))),
                )
                .refined()
        val changed = assertInstanceOf(SemanticInventoryComparison.Changed::class.java, prior.compare(current))
        assertEquals(setOf(WorkspaceSourcePath.parse("library/src/Library.kt").refined()), changed.modified)
        val unaffected =
            SemanticDependencyInventory.admit(
                    graph.closure(setOf(independent)).refined(),
                    listOf(completed("independent")),
                )
                .refined()
        assertEquals(SemanticInventoryComparison.Unchanged, unaffected.compare(unaffected))
    }

    @Test
    fun `module dependency cycles terminate and retain all reachable modules`() {
        val cyclic =
            SemanticModuleDependencies.fromCompiler(
                    model,
                    mapOf(entry to setOf(library), library to setOf(entry), independent to emptySet()),
                )
                .refined()
        assertEquals(setOf(entry, library), cyclic.closure(setOf(entry)).refined().modules)
    }

    @Test
    fun `missing graph entry is unproven even when it appears irrelevant`() {
        val result =
            SemanticModuleDependencies.fromCompiler(
                model,
                mapOf(entry to setOf(library), independent to emptySet()),
            )
        assertEquals(
            SemanticDependencyFailure.MissingModules(setOf(library)),
            (result as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `duplicate files and foreign source roots reject completion`() {
        val source = file("entry", "Entry.kt", 'a')
        assertInstanceOf(
            Refinement.Rejected::class.java,
            CompleteSemanticModuleSources.fromCompiler(graph, entry, listOf(source, source)),
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            CompleteSemanticModuleSources.fromCompiler(graph, library, listOf(source)),
        )
    }

    private fun file(module: String, name: String, digit: Char) =
        SemanticDependencySource(
            roots.getValue(module),
            WorkspaceSourcePath.parse("$module/src/$name").refined(),
            WorkspaceSourceContentHash.parse(digit.toString().repeat(64)).refined(),
        )

    private fun completed(module: String, vararg files: SemanticDependencySource) =
        CompleteSemanticModuleSources.fromCompiler(graph, roots.getValue(module).module, files.toList()).refined()
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
