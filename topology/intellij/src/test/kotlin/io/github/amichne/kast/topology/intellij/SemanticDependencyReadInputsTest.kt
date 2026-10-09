package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class SemanticDependencyReadInputsTest : SemanticReadInputFixture() {
    @Test
    fun `failed graph preparation is shared across universes but a fresh read can observe again`() {
        val read = read()
        val rejected = Refinement.Rejected(SemanticDependencyCaptureFailure.MODULE_UNAVAILABLE)
        assertEquals(rejected, read.snapshot(setOf(main), budget(), { rejected }) { _, _, _ -> unexpected() })
        assertEquals(rejected, read.snapshot(modules, budget(), { unexpected() }) { _, _, _ -> unexpected() })
        assertEquals(1, counts[IntellijReadCounter.DEPENDENCY_GRAPH_UNAVAILABLE_MEMO_HITS])
        read.finishNativeRead()
        read()
            .snapshot(setOf(main), budget(), { Refinement.Refined(graph) }) { _, _, identity ->
                Refinement.Refined(module(graph, identity))
            }
            .value()
    }

    @Test
    fun `authority movement precedes any new graph or input observation`() {
        val read = read()
        read
            .snapshot(setOf(main), budget(), { Refinement.Refined(graph) }) { _, _, identity ->
                Refinement.Refined(module(graph, identity))
            }
            .value()
        owner.advance()
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.AUTHORITY_MOVED),
            read.snapshot(modules, budget(), { unexpected() }) { _, _, _ -> unexpected() },
        )
    }

    @Test
    fun `forward to whole escalation observes graph once and only newly required module inputs`() {
        val read = read()
        val observed = mutableListOf<WorkspaceModuleIdentity>()
        var graphs = 0
        val captureGraph = {
            graphs++
            Refinement.Refined(graph)
        }
        val captureModule:
            (
                io.github.amichne.kast.topology.contract.SemanticModuleDependencies,
                io.github.amichne.kast.topology.contract.SemanticDependencyClosure,
                WorkspaceModuleIdentity,
            ) -> SemanticCapture<NativeModuleInputs> =
            { supplied, closure, identity ->
                assertSame(graph, supplied)
                assertSame(graph, closure.graph)
                observed += identity
                Refinement.Refined(module(graph, identity))
            }
        val forward = read.snapshot(setOf(main), budget(), captureGraph, captureModule).value()
        assertEquals(setOf(main, dependency), forward.inventory.closure.modules)
        val whole = read.snapshot(modules, budget(), captureGraph, captureModule).value()
        assertEquals(modules, whole.inventory.closure.roots)
        assertSame(forward.inventory.closure.graph, whole.inventory.closure.graph)
        assertEquals(listOf(dependency, main, unrelated), observed)
        assertEquals(1, graphs)
        assertEquals(1, counts[IntellijReadCounter.DEPENDENCY_GRAPH_MEMO_HITS])
        assertEquals(2, counts[IntellijReadCounter.DEPENDENCY_MODULE_INPUT_MEMO_HITS])
    }

    @Test
    fun `failed escalation retains completed forward modules without manufacturing failed module completion`() {
        val read = read()
        var failures = 0
        val first =
            read.snapshot(modules, budget(), { Refinement.Refined(graph) }) { _, _, identity ->
                if (identity == unrelated) {
                    failures++
                    Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
                } else Refinement.Refined(module(graph, identity))
            }
        assertEquals(Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE), first)
        val next = read.snapshot(setOf(main), budget(), { unexpected() }) { _, _, _ -> unexpected() }.value()
        assertEquals(setOf(main, dependency), next.inputs.modules.keys)
        assertEquals(1, failures)
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE),
            read.snapshot(modules, budget(), { unexpected() }) { _, _, _ -> unexpected() },
        )
        assertEquals(1, failures)
        assertEquals(1, counts[IntellijReadCounter.DEPENDENCY_MODULE_INPUT_UNAVAILABLE_MEMO_HITS])
    }

    @Test
    fun `close removes all observations and a fresh scope needs fresh graph and inputs`() {
        val read = read()
        read
            .snapshot(setOf(main), budget(), { Refinement.Refined(graph) }) { _, _, identity ->
                Refinement.Refined(module(graph, identity))
            }
            .value()
        read.finishNativeRead()
        read.finishNativeRead()
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED),
            read.snapshot(modules, budget(), { unexpected() }) { _, _, _ -> unexpected() },
        )
        var graphs = 0
        var inputs = 0
        read()
            .snapshot(
                setOf(main),
                budget(),
                {
                    graphs++
                    Refinement.Refined(graph)
                },
            ) { _, _, identity ->
                inputs++
                Refinement.Refined(module(graph, identity))
            }
            .value()
        assertEquals(1, graphs)
        assertEquals(2, inputs)
    }

    @Test
    fun `excluded roots and foreign model graph are rejected before input collection`() {
        val read = read()
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.DEPENDENCY_CLOSURE_REJECTED),
            read.snapshot(emptySet(), budget(), { unexpected() }) { _, _, _ -> unexpected() },
        )
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.MODEL_ROOT_MISMATCH),
            read.snapshot(setOf(main), budget(), { Refinement.Refined(graph(model())) }) { _, _, _ -> unexpected() },
        )
    }

    @Test
    fun `foreign source graph cannot become a complete reused snapshot`() {
        val foreign = graph(model())
        val read = read()
        repeat(2) {
            assertEquals(
                Refinement.Rejected(SemanticDependencyCaptureFailure.SOURCE_MODULE_INVENTORY_REJECTED),
                read.snapshot(setOf(main), budget(), { Refinement.Refined(graph) }) { _, _, identity ->
                    Refinement.Refined(module(foreign, identity))
                },
            )
        }
    }

    @Test
    fun `cached paths still admit current time work and cancellation before reuse`() {
        val read = read()
        read
            .snapshot(setOf(main), budget(), { Refinement.Refined(graph) }) { _, _, identity ->
                Refinement.Refined(module(graph, identity))
            }
            .value()
        val expired = budget()
        now = 100_000_000
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.TIME_EXHAUSTED),
            read.snapshot(setOf(main), expired, { unexpected() }) { _, _, _ -> unexpected() },
        )
        val exhausted = budget(1)
        exhausted.step().value()
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.WORK_EXHAUSTED),
            read.snapshot(setOf(main), exhausted, { unexpected() }) { _, _, _ -> unexpected() },
        )
        val cancelled = CancellationException("Preempted read")
        checkCanceled = { throw cancelled }
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                read.snapshot(setOf(main), budget(), { unexpected() }) { _, _, _ -> unexpected() }
            },
        )
    }

    private fun unexpected(): Nothing = throw AssertionError("Unexpected native input effect")
}
