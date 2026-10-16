package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows

class ModuleImpactTest {
    @Test
    fun `diamond returns shortest consumer paths with deterministic ties`() {
        val modules = linkedSetOf(
            ModuleId.SYMBOL_CONTRACT,
            ModuleId.PROTOCOL_CONTRACT,
            ModuleId.KERNEL,
            ModuleId.DISTRIBUTION_CONTRACT,
            ModuleId.CLI,
            ModuleId.APP_SERVER,
        )
        val edges = linkedSetOf(
            edge(ModuleId.CLI, ModuleId.SYMBOL_CONTRACT),
            edge(ModuleId.SYMBOL_CONTRACT, ModuleId.KERNEL),
            edge(ModuleId.APP_SERVER, ModuleId.CLI),
            edge(ModuleId.CLI, ModuleId.PROTOCOL_CONTRACT),
            edge(ModuleId.PROTOCOL_CONTRACT, ModuleId.KERNEL),
            edge(ModuleId.KERNEL, ModuleId.DISTRIBUTION_CONTRACT),
            edge(ModuleId.APP_SERVER, ModuleId.SYMBOL_CONTRACT),
        )
        val expected = listOf(
            listOf(ModuleId.APP_SERVER, ModuleId.SYMBOL_CONTRACT, ModuleId.KERNEL),
            listOf(ModuleId.CLI, ModuleId.PROTOCOL_CONTRACT, ModuleId.KERNEL),
            listOf(ModuleId.PROTOCOL_CONTRACT, ModuleId.KERNEL),
            listOf(ModuleId.SYMBOL_CONTRACT, ModuleId.KERNEL),
        )

        assertEquals(expected, admitted(graph(modules, edges), ModuleId.KERNEL).dependencyPaths())
        assertEquals(
            expected,
            admitted(graph(modules.reversed().toSet(), edges.reversed().toSet()), ModuleId.KERNEL).dependencyPaths(),
        )
    }

    @Test
    fun `cycle terminates without returning the selected module`() {
        val graph = graph(
            setOf(ModuleId.KERNEL, ModuleId.CLI, ModuleId.SYMBOL_CONTRACT),
            setOf(
                edge(ModuleId.KERNEL, ModuleId.CLI),
                edge(ModuleId.CLI, ModuleId.SYMBOL_CONTRACT),
                edge(ModuleId.SYMBOL_CONTRACT, ModuleId.KERNEL),
                edge(ModuleId.SYMBOL_CONTRACT, ModuleId.SYMBOL_CONTRACT),
            ),
        )

        assertEquals(
            listOf(
                listOf(ModuleId.CLI, ModuleId.SYMBOL_CONTRACT, ModuleId.KERNEL),
                listOf(ModuleId.SYMBOL_CONTRACT, ModuleId.KERNEL),
            ),
            admitted(graph, ModuleId.KERNEL).dependencyPaths(),
        )
    }

    @Test
    fun `multiple selected modules yield nearest paths and remain separate from consumers`() {
        val graph = graph(
            setOf(
                ModuleId.KERNEL,
                ModuleId.CLI,
                ModuleId.PROTOCOL_CONTRACT,
                ModuleId.SYMBOL_CONTRACT,
                ModuleId.RUNTIME_HOSTED,
                ModuleId.APP_SERVER,
            ),
            setOf(
                edge(ModuleId.CLI, ModuleId.PROTOCOL_CONTRACT),
                edge(ModuleId.PROTOCOL_CONTRACT, ModuleId.KERNEL),
                edge(ModuleId.SYMBOL_CONTRACT, ModuleId.KERNEL),
                edge(ModuleId.RUNTIME_HOSTED, ModuleId.SYMBOL_CONTRACT),
                edge(ModuleId.RUNTIME_HOSTED, ModuleId.CLI),
                edge(ModuleId.APP_SERVER, ModuleId.KERNEL),
                edge(ModuleId.APP_SERVER, ModuleId.CLI),
            ),
        )
        val expected = listOf(
            listOf(ModuleId.APP_SERVER, ModuleId.CLI),
            listOf(ModuleId.PROTOCOL_CONTRACT, ModuleId.KERNEL),
            listOf(ModuleId.RUNTIME_HOSTED, ModuleId.CLI),
            listOf(ModuleId.SYMBOL_CONTRACT, ModuleId.KERNEL),
        )

        assertEquals(expected, admitted(graph, ModuleId.KERNEL, ModuleId.CLI).dependencyPaths())
        assertEquals(expected, admitted(graph, ModuleId.CLI, ModuleId.KERNEL).dependencyPaths())
    }

    @Test
    fun `isolated known module has no consumers`() {
        val graph = graph(
            setOf(ModuleId.KERNEL, ModuleId.CLI, ModuleId.PROTOCOL_CONTRACT),
            setOf(edge(ModuleId.CLI, ModuleId.PROTOCOL_CONTRACT)),
        )

        val impact = admitted(graph, ModuleId.KERNEL)

        assertEquals(setOf(ModuleId.KERNEL), impact.selected)
        assertTrue(impact.dependencyPaths().isEmpty())
    }

    @Test
    fun `empty graph rejects before selection`() {
        assertRejected(
            ModuleImpactFailure.EmptyGraph,
            graph(emptySet(), emptySet()),
            listOf(ModuleId.KERNEL.projectPath),
        )
    }

    @Test
    fun `empty selection rejects`() {
        assertRejected(
            ModuleImpactFailure.EmptySelection,
            graph(setOf(ModuleId.KERNEL), emptySet()),
            emptyList(),
        )
    }

    @Test
    fun `unknown path and known module absent from the graph reject`() {
        val graph = graph(setOf(ModuleId.KERNEL), emptySet())

        assertRejected(ModuleImpactFailure.UnknownModule(":absent"), graph, listOf(":absent"))
        assertRejected(
            ModuleImpactFailure.UnknownModule(ModuleId.CLI.projectPath),
            graph,
            listOf(ModuleId.CLI.projectPath),
        )
    }

    @Test
    fun `duplicate selection rejects instead of collapsing input`() {
        assertRejected(
            ModuleImpactFailure.DuplicateSelection(ModuleId.KERNEL),
            graph(setOf(ModuleId.KERNEL), emptySet()),
            listOf(ModuleId.KERNEL.projectPath, ModuleId.KERNEL.projectPath),
        )
    }

    @Test
    fun `missing consumer or dependency endpoint rejects the whole graph`() {
        val missingConsumer = edge(ModuleId.CLI, ModuleId.KERNEL)
        val missingDependency = edge(ModuleId.KERNEL, ModuleId.CLI)

        for (edge in listOf(missingConsumer, missingDependency)) {
            assertRejected(
                ModuleImpactFailure.MissingEndpoint(edge),
                graph(setOf(ModuleId.KERNEL), setOf(edge)),
                listOf(ModuleId.KERNEL.projectPath),
            )
        }
    }

    @Test
    fun `admission copies mutable graph and selection inputs`() {
        val modules = mutableSetOf(ModuleId.KERNEL, ModuleId.CLI)
        val dependency = edge(ModuleId.CLI, ModuleId.KERNEL)
        val dependencies = mutableSetOf(dependency)
        val selection = mutableListOf(ModuleId.KERNEL.projectPath)
        val impact = assertInstanceOf<ModuleImpactAdmission.Accepted>(
            ModuleImpact.admit(graph(modules, dependencies), selection),
        ).impact

        modules.clear()
        modules.add(ModuleId.APP_SERVER)
        dependencies.clear()
        dependencies.add(edge(ModuleId.APP_SERVER, ModuleId.CLI))
        selection.clear()
        selection.add(ModuleId.CLI.projectPath)

        assertEquals(setOf(ModuleId.KERNEL, ModuleId.CLI), impact.modules)
        assertEquals(setOf(dependency), impact.dependencies)
        assertEquals(setOf(ModuleId.KERNEL), impact.selected)
        assertEquals(listOf(listOf(ModuleId.CLI, ModuleId.KERNEL)), impact.dependencyPaths())
    }

    @Test
    fun `admitted collections reject mutation through consumer casts`() {
        val impact = admitted(
            graph(
                setOf(ModuleId.KERNEL, ModuleId.CLI),
                setOf(edge(ModuleId.CLI, ModuleId.KERNEL)),
            ),
            ModuleId.KERNEL,
        )

        assertThrows<UnsupportedOperationException> { (impact.modules as MutableSet).clear() }
        assertThrows<UnsupportedOperationException> { (impact.dependencies as MutableSet).clear() }
        assertThrows<UnsupportedOperationException> { (impact.selected as MutableSet).clear() }
        assertEquals(listOf(listOf(ModuleId.CLI, ModuleId.KERNEL)), impact.dependencyPaths())
    }

    private fun edge(consumer: ModuleId, dependency: ModuleId) = ProjectDependencyObservation(consumer, dependency)

    private fun graph(modules: Set<ModuleId>, dependencies: Set<ProjectDependencyObservation>) = ObservedProjectGraph(
        modules,
        dependencies,
        emptySet(),
        ModuleRoleConventionObservation.Collected(emptyMap()),
    )

    private fun admitted(graph: ObservedProjectGraph, vararg selected: ModuleId): ModuleImpact =
        assertInstanceOf<ModuleImpactAdmission.Accepted>(
            ModuleImpact.admit(graph, selected.map(ModuleId::projectPath)),
        ).impact

    private fun assertRejected(
        expected: ModuleImpactFailure,
        graph: ObservedProjectGraph,
        selected: List<String>,
    ) {
        assertEquals(
            expected,
            assertInstanceOf<ModuleImpactAdmission.Rejected>(ModuleImpact.admit(graph, selected)).failure,
        )
    }
}
