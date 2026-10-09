package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.PositiveLimitFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.relation.contract.CallbackSupplierCacheLookup
import io.github.amichne.kast.relation.contract.NamedRelationCacheLookup
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.topology.contract.SemanticModuleDependencies
import io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureCost
import io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureFailure
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class HostedReadCallbackPartitionsTest : HostedScopedCaptureFixture() {
    @Test
    fun `native capture capability is closed exactly once even when never consumed`() {
        var finishes = 0
        val capture =
            object : HostedCallbackDependencyCapture {
                override fun capture(
                    roots: Set<WorkspaceModuleIdentity>,
                    budget: ResourceBudget,
                ): Refinement<SemanticDependencySnapshot, SemanticDependencyCaptureFailure> =
                    throw AssertionError("No consumer requested a capture")

                override fun finishNativeRead() {
                    finishes++
                }
            }
        val selected =
            HostedReadCallbackPartitions(
                model,
                attempts,
                allowance,
                {
                    parentCalls++
                    parent
                },
                counts,
                capture,
            )
        HostedCallbackFactCache(selected, store, counts).finishNativeRead()
        selected.finishNativeRead()
        assertEquals(1, finishes)
        assertEquals(0, parentCalls)
        assertEquals(HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.NativeReadEnded), selected.whole())
    }

    @Test
    fun `named callee and caller consumers request their distinct authoritative universes`() {
        val script =
            Script(
                listOf(
                    Step(setOf(main), budget(), Refinement.Refined(captured(setOf(main)))),
                    Step(modules, budget(), Refinement.Refined(captured(modules))),
                )
            )
        val cache = HostedCallbackFactCache(partitions(capture = script::capture), store, counts)
        assertEquals(
            NamedRelationCacheLookup.Miss,
            cache.namedRelations.find(named(authority).batch.request) { throw AssertionError("No retained callees") },
        )
        assertEquals(
            NamedRelationCacheLookup.Miss,
            cache.namedRelations.find(named(authority, RelationMeaning.Callers).batch.request) {
                throw AssertionError("No retained callers")
            },
        )
        script.exhausted()
    }

    @Test
    fun `supplier lookup and publication consume one full workspace capture`() {
        val script = Script(listOf(Step(modules, budget(), Refinement.Refined(captured(modules)))))
        val cache = HostedCallbackFactCache(partitions(capture = script::capture), store, counts)
        val inventory = suppliers(authority)
        assertEquals(
            CallbackSupplierCacheLookup.Miss,
            cache.suppliers.find(inventory.root, inventory.domain) { unexpectedRestoration() },
        )
        cache.suppliers.retain(inventory)
        assertEquals(
            CallbackSupplierCacheLookup.Found(inventory),
            cache.suppliers.find(inventory.root, inventory.domain) {
                unexpectedRestoration()
            },
        )
        cache.finishNativeRead()
        assertEquals(
            CallbackSupplierCacheLookup.Miss,
            cache.suppliers.find(inventory.root, inventory.domain) {
                unexpectedRestoration()
            },
        )
        script.exhausted()
    }

    @Test
    fun `preparation is lazy and repeated forward consumers share only the subject closure`() {
        val script = Script(listOf(Step(setOf(main), budget(), Refinement.Refined(captured(setOf(main))))))
        val selected = partitions(capture = script::capture)
        assertEquals(0, script.calls)
        val first = available(selected.forward(endpoint))
        repeat(2) {
            val snapshot = available(selected.forward(endpoint))
            assertSame(first, snapshot)
            assertEquals(setOf(main), snapshot.inventory.closure.roots)
            assertEquals(setOf(main, dependency), snapshot.inventory.closure.modules)
            assertEquals(setOf(main, dependency), snapshot.inputs.modules.keys)
        }
        script.exhausted()
        assertEquals(1, script.calls)
        assertEquals(2, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_CAPTURES_SHARED])
    }

    @Test
    fun `whole capture supplies repeated whole and forward consumers without another effect`() {
        val script = Script(listOf(Step(modules, budget(), Refinement.Refined(captured(modules)))))
        val selected = partitions(capture = script::capture)
        assertEquals(modules, available(selected.whole()).inventory.closure.modules)
        assertEquals(modules, available(selected.whole()).inventory.closure.roots)
        assertEquals(setOf(main, dependency), available(selected.forward(endpoint)).inventory.closure.modules)
        script.exhausted()
        assertEquals(1, script.calls)
    }

    @Test
    fun `escalation captures full roots using aggregate optional cost clipped to current parent`() {
        val script =
            Script(
                listOf(
                    Step(setOf(main), budget(), Refinement.Refined(captured(setOf(main))), work = 6, nanos = 1_500_000),
                    Step(modules, budget(12, 7), Refinement.Refined(captured(modules)), work = 2, nanos = 500_000),
                )
            )
        val selected = partitions(capture = script::capture)
        selected.forward(endpoint)
        parent = Refinement.Refined(budget(12, 7))
        assertEquals(modules, available(selected.whole()).inventory.closure.modules)
        assertEquals(budget(32, 98), allowance.remaining(budget()).value())
        script.exhausted()
    }

    @Test
    fun `failure in either universe preserves its cause without disabling the other`() {
        for (first in Consumer.entries) {
            val next = if (first == Consumer.FORWARD) Consumer.WHOLE else Consumer.FORWARD
            val failure = SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE
            val localAllowance = HostedCallbackCaptureAllowance(budget())
            val script =
                Script(
                    listOf(
                        Step(
                            roots(first),
                            budget(),
                            Refinement.Rejected(failure),
                            work = 3,
                            nanos = 1,
                        ),
                        Step(
                            roots(next),
                            budget(37, 99),
                            Refinement.Refined(captured(roots(next))),
                        ),
                    ),
                    localAllowance,
                )
            val localAttempts =
                HostedCallbackDependencyAttempts(
                    counts,
                    setOf(
                        HostedCallbackDependencyUniverse.WholeWorkspace,
                        HostedCallbackDependencyUniverse.Forward(main),
                    ),
                )
            val selected =
                HostedReadCallbackPartitions(
                    model,
                    localAttempts,
                    localAllowance,
                    { Refinement.Refined(budget()) },
                    counts,
                    script::capture,
                )
            val rejected = select(selected, first)
            assertEquals(HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.Capture(failure)), rejected)
            assertEquals(rejected, select(selected, first))
            assertInstanceOf(
                HostedCallbackPartition.Available::class.java,
                select(selected, next),
            )
            script.exhausted()
        }
    }

    @Test
    fun `a fresh native attempt recaptures even with unchanged authority and request attempts`() {
        val script = Script(List(2) { Step(setOf(main), budget(), Refinement.Refined(captured(setOf(main)))) })
        val first = partitions(capture = script::capture)
        available(first.forward(endpoint))
        first.finishNativeRead()
        available(partitions(capture = script::capture).forward(endpoint))
        script.exhausted()
        assertEquals(2, script.calls)
    }

    @Test
    fun `closed native lifetime rejects every provider before parent or capture effects`() {
        val script = Script(listOf(Step(modules, budget(), Refinement.Refined(captured(modules)))))
        val selected = partitions(capture = script::capture)
        selected.whole()
        selected.finishNativeRead()
        selected.finishNativeRead()
        val calls = parentCalls
        assertEquals(HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.NativeReadEnded), selected.whole())
        assertEquals(
            HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.NativeReadEnded),
            selected.forward(endpoint),
        )
        assertEquals(calls, parentCalls)
        assertEquals(2, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_PARTITION_REJECTIONS])
        script.exhausted()
    }

    @Test
    fun `current parent rejection precedes even an already captured positive`() {
        val script = Script(listOf(Step(setOf(main), budget(), Refinement.Refined(captured(setOf(main))))))
        val selected = partitions(capture = script::capture)
        selected.forward(endpoint)
        parent = Refinement.Rejected(PositiveLimitFailure.NOT_POSITIVE)
        assertEquals(
            HostedCallbackPartition.Rejected(
                HostedCallbackPartitionFailure.ParentBudget(PositiveLimitFailure.NOT_POSITIVE)
            ),
            selected.forward(endpoint),
        )
        script.exhausted()
    }

    @Test
    fun `unowned and ambiguous model sources are rejected before budget admission or native enumeration`() {
        for ((paths, cause) in
            listOf(
                listOf("main" to "elsewhere") to HostedCallbackPartitionFailure.SourceNotInventoried,
                listOf("main" to "src", "other" to "src") to HostedCallbackPartitionFailure.AmbiguousSourceOwnership,
            )) {
            val script = Script(emptyList())
            val selected = partitions(model(paths), script::capture)
            assertEquals(HostedCallbackPartition.Rejected(cause), selected.forward(endpoint))
            script.exhausted()
        }
        assertEquals(0, parentCalls)
    }

    @Test
    fun `returned roots and original model identity must match before positive publication`() {
        val foreignModel = model()
        val foreignGraph =
            SemanticModuleDependencies.fromCompiler(
                    foreignModel,
                    modules.associateWith { if (it == main) setOf(dependency) else emptySet() },
                )
                .value()
        for (snapshot in listOf(captured(modules), captured(setOf(main), foreignGraph))) {
            val script = Script(List(2) { Step(setOf(main), budget(), Refinement.Refined(snapshot)) })
            val selected = partitions(capture = script::capture)
            repeat(2) {
                assertEquals(
                    HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.CaptureUniverseMismatch),
                    selected.forward(endpoint),
                )
            }
            script.exhausted()
        }
    }

    @Test
    fun `uncaptured module projection cannot manufacture complete empty source inventory`() {
        val selected = HostedCapturedCallbackPartitions(captured(setOf(main)))
        val unrelated = modules.single { it.value == "unrelated" }
        assertEquals(
            HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.UncapturedModules(setOf(unrelated))),
            selected.project(graph.closure(setOf(unrelated)).value()),
        )
    }

    @Test
    fun `full coverage from one root canonicalizes whole roots without more enumeration`() {
        val completeGraph =
            SemanticModuleDependencies.fromCompiler(
                    model,
                    modules.associateWith { if (it == main) modules - main else emptySet() },
                )
                .value()
        val script =
            Script(listOf(Step(setOf(main), budget(), Refinement.Refined(captured(setOf(main), completeGraph)))))
        val selected = partitions(capture = script::capture)
        selected.forward(endpoint)
        assertEquals(modules, available(selected.whole()).inventory.closure.roots)
        script.exhausted()
    }

    @Test
    fun `cancelled capture charges measured cost and propagates original cancellation`() {
        val cancelled = CancellationException("Preempted native capture")
        val selected = partitions { _, _ ->
            allowance.record(SemanticDependencyCaptureCost.fromBoundary(7, 1).value())
            throw cancelled
        }
        assertSame(cancelled, assertThrows(CancellationException::class.java) { selected.whole() })
        assertEquals(budget(33, 99), allowance.remaining(budget()).value())
    }

    private enum class Consumer {
        FORWARD,
        WHOLE,
    }

    private fun roots(consumer: Consumer) =
        when (consumer) {
            Consumer.FORWARD -> setOf(main)
            Consumer.WHOLE -> modules
        }

    private fun select(partitions: HostedReadCallbackPartitions, consumer: Consumer) =
        when (consumer) {
            Consumer.FORWARD -> partitions.forward(endpoint)
            Consumer.WHOLE -> partitions.whole()
        }

    private fun unexpectedRestoration(): Nothing = throw AssertionError("Unexpected compiler restoration")

    private fun available(partition: HostedCallbackPartition) =
        assertInstanceOf(HostedCallbackPartition.Available::class.java, partition).snapshot

    private data class Step(
        val roots: Set<WorkspaceModuleIdentity>,
        val budget: ResourceBudget,
        val result: Refinement<SemanticDependencySnapshot, SemanticDependencyCaptureFailure>,
        val work: Long = 0,
        val nanos: Long = 0,
    )

    private inner class Script(
        private val steps: List<Step>,
        private val meter: HostedCallbackCaptureAllowance = allowance,
    ) {
        var calls = 0
            private set

        fun capture(
            roots: Set<WorkspaceModuleIdentity>,
            budget: ResourceBudget,
        ): Refinement<SemanticDependencySnapshot, SemanticDependencyCaptureFailure> {
            if (calls >= steps.size) throw AssertionError("Unexpected capture")
            val step = steps[calls++]
            assertEquals(step.roots, roots)
            assertEquals(step.budget, budget)
            meter.record(SemanticDependencyCaptureCost.fromBoundary(step.work, step.nanos).value())
            return step.result
        }

        fun exhausted() = assertEquals(steps.size, calls, "Unconsumed capture expectations")
    }
}
