package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class TraversalOmissionCompositionTest {
    private val fixture = TraversalTestFixture()
    private val a = fixture.selector("a", 10)
    private val b = fixture.selector("b", 20)

    @Test
    fun `depth one preserves provider measurement samples and unknown omission counts`() {
        lateinit var expected: io.github.amichne.kast.relation.contract.RelationOmissionEvidence
        val relations = RelationOperations { request ->
            val initial = fixture.terminalRelationResult(request)
            expected = measuredOmission(request.providerCursor.provider)
            val batch = initial.batch.withOmissions(listOf(expected)).refined()
            val compilation =
                io.github.amichne.kast.relation.contract.RelationCompilation.qualifiedTerminal(
                        batch,
                        setOf(RelationLimitation.UNRESOLVED_TARGET, RelationLimitation.UNSUPPORTED_ITEM),
                    )
                    .refined()
            io.github.amichne.kast.relation.contract.RelationReadResult.Qualified(batch, compilation.coverage)
        }
        val result =
            assertInstanceOf(
                TraversalResult.Qualified::class.java,
                runSuspend {
                    traversalOperations(relations, TraversalNanoClock { 0L }).run(fixture.plan(a, depth = 1))
                },
            )
        val partial = result.page.partialExpansions.single()
        assertEquals(a.fingerprint.value, partial.entry.node.fingerprint.value)
        assertEquals(0, partial.entry.depth.value)
        assertEquals(0, partial.knownMinimum.value)
        assertEquals(expected, partial.omissions.first())
        assertEquals(
            io.github.amichne.kast.relation.contract.RelationOmissionSampleRetention.TRUNCATED,
            partial.omissions.first().samples.retention,
        )
        assertEquals(
            io.github.amichne.kast.relation.contract.RelationOmissionMeasurement.UnmeasuredOnPage,
            partial.omissions.last().measurement,
        )
        assertEquals(emptyList<Any>(), partial.omissions.last().samples.locations)
        assertEquals(expected.remediation, partial.omissions.first().remediation)
    }

    @Test
    fun `terminal omission retains its original measured page through checkpoint and replay`() {
        val relations = RelationOperations { request ->
            if (request.subject.name.value == "a") {
                val initial = fixture.terminalRelationResult(request, listOf(fixture.endpoint(request.subject, b)))
                val evidence =
                    io.github.amichne.kast.relation.contract.RelationOmissionEvidence.fromObservedPage(
                            request.providerCursor.provider,
                            RelationLimitation.UNRESOLVED_TARGET,
                            io.github.amichne.kast.relation.contract.RelationOmissionMeasurement.ObservedOnPage(
                                io.github.amichne.kast.relation.contract.RelationWorkCount.parse(2).refined()
                            ),
                            io.github.amichne.kast.relation.contract.RelationOmissionSamples.observed(
                                listOf(
                                    io.github.amichne.kast.relation.contract.RelationOccurrence.fromBoundary(
                                            a.file,
                                            130,
                                            132,
                                        )
                                        .refined()
                                )
                            ),
                        )
                        .refined()
                io.github.amichne.kast.relation.contract.RelationReadResult.Qualified(
                    initial.batch.withOmissions(listOf(evidence)).refined(),
                    initial.coverage,
                )
            } else fixture.completeRelationResult(request, emptyList())
        }
        val operations = traversalOperations(relations, TraversalNanoClock { 0L })
        val firstPlan = fixture.plan(a, aggregateRecords = 1, oneHop = fixture.relationBudget(records = 1))
        val first = assertInstanceOf(TraversalResult.Qualified::class.java, runSuspend { operations.run(firstPlan) })
        val continuation =
            assertInstanceOf(TraversalQualification.Resumable::class.java, first.qualification).continuation
        assertEquals(first.page.partialExpansions, continuation.checkpoint.retainedOmissions)
        val next = TraversalPlan.resume(a, RelationMeaning.Callees, firstPlan.budget, continuation).refined()
        val second = assertInstanceOf(TraversalResult.Qualified::class.java, runSuspend { operations.run(next) })
        val replay = assertInstanceOf(TraversalResult.Qualified::class.java, runSuspend { operations.run(next) })
        assertEquals(emptyList<Any>(), second.page.partialExpansions)
        assertEquals(first.page.partialExpansions, second.page.inheritedOmissions)
        assertEquals(second.page.inheritedOmissions, replay.page.inheritedOmissions)
        assertEquals(1, replay.page.inheritedOmissions.size)
    }

    @Test
    fun `repeated inherited unmeasured omissions preserve resumable checkpoints`() {
        var reads = 0
        val relations = RelationOperations { request ->
            reads += 1
            val batch = fixture.completeRelationResult(request, emptyList()).batch
            val inventory = (0..2).map { traversalFilteredLocator(request, "item-$it", 100 + it) }
            val compilation =
                if (reads < 3) {
                    val state = traversalCalleeState(request, inventory, 1)
                    io.github.amichne.kast.relation.contract.RelationCompilation.qualifiedResumable(
                            batch,
                            setOf(RelationLimitation.UNSUPPORTED_ITEM, RelationLimitation.RESULT_LIMIT_REACHED),
                            state.providerCursor,
                            providerState = state,
                        )
                        .refined()
                } else {
                    io.github.amichne.kast.relation.contract.RelationCompilation.qualifiedTerminal(
                            batch,
                            setOf(RelationLimitation.UNSUPPORTED_ITEM),
                        )
                        .refined()
                }
            io.github.amichne.kast.relation.contract.RelationReadResult.Qualified(batch, compilation.coverage)
        }
        val operations = traversalOperations(relations, TraversalNanoClock { 0L })
        val initial = fixture.plan(a, aggregateRecords = 1, depth = 1, oneHop = fixture.relationBudget(records = 1))
        var plan = initial
        repeat(2) {
            val result = assertInstanceOf(TraversalResult.Qualified::class.java, runSuspend { operations.run(plan) })
            val resumable = assertInstanceOf(TraversalQualification.Resumable::class.java, result.qualification)
            val retained = resumable.continuation.checkpoint.retainedOmissions.single()
            assertEquals(setOf(RelationLimitation.UNSUPPORTED_ITEM), retained.limitations)
            assertEquals(0, retained.knownMinimum.value)
            assertEquals(
                io.github.amichne.kast.relation.contract.RelationOmissionMeasurement.UnmeasuredOnPage,
                retained.omissions.single().measurement,
            )
            plan = TraversalPlan.resume(a, RelationMeaning.Callees, initial.budget, resumable.continuation).refined()
        }
        val terminal = assertInstanceOf(TraversalResult.Qualified::class.java, runSuspend { operations.run(plan) })
        assertInstanceOf(TraversalQualification.TerminalIncomplete::class.java, terminal.qualification)
        assertEquals(1, terminal.page.inheritedOmissions.size)
        assertEquals(3, reads)
    }

    @Test
    fun `equal page measurements remain distinct before strict checkpoint admission`() {
        val plan = fixture.plan(a, depth = 1)
        val checkpoint = io.github.amichne.kast.traversal.contract.TraversalCheckpoint.initial(plan)
        val entry = checkpoint.frontier.single()
        val request =
            io.github.amichne.kast.relation.contract.RelationRequest.start(
                a,
                RelationMeaning.Callees,
                plan.budget.oneHop,
            )
        val initial = fixture.terminalRelationResult(request)
        val batch = initial.batch.withOmissions(listOf(measuredOmission(request.providerCursor.provider))).refined()
        val partial =
            io.github.amichne.kast.traversal.contract.TraversalPartialExpansion.create(
                    plan,
                    entry,
                    io.github.amichne.kast.relation.contract.RelationReadResult.Qualified(batch, initial.coverage),
                    io.github.amichne.kast.traversal.contract.TraversalExpansionRemainder.NOT_EXPLORED,
                )
                .refined()
        val state = MutableTraversalState.from(checkpoint)
        state.retainOmissions(partial)
        state.retainOmissions(partial)
        assertEquals(listOf(partial, partial), state.retainedOmissions)
    }

    private fun measuredOmission(provider: io.github.amichne.kast.relation.contract.RelationProviderKind) =
        io.github.amichne.kast.relation.contract.RelationOmissionEvidence.fromObservedPage(
                provider,
                RelationLimitation.UNRESOLVED_TARGET,
                io.github.amichne.kast.relation.contract.RelationOmissionMeasurement.ObservedOnPage(
                    io.github.amichne.kast.relation.contract.RelationWorkCount.parse(4).refined()
                ),
                io.github.amichne.kast.relation.contract.RelationOmissionSamples.observed(
                    listOf(110, 120, 130, 140).map { offset ->
                        io.github.amichne.kast.relation.contract.RelationOccurrence.fromBoundary(
                                a.file,
                                offset,
                                offset + 2,
                            )
                            .refined()
                    }
                ),
            )
            .refined()

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
