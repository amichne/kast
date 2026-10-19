package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelationCallbackStorageTest {
    private val case = CallbackInvocationFlowFixture()

    @Test
    fun `observation storage counts an observed owner proof once and keeps its full identity`(): Unit =
        with(case) {
            val base =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Bound(binding),
                        listOf(nested),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val complete = base.withOwnerBindings(listOf(owner())).value()
            assertStorage(
                observation(CallbackInvocationFlowRead.Observed(base)),
                base.retainedBytes,
                observation(CallbackInvocationFlowRead.Observed(complete)),
                complete.retainedBytes,
            )
        }

    @Test
    fun `observation storage counts an immutable transport proof once and keeps its full identity`(): Unit =
        with(case) {
            val origin = ImmutableCallbackValueOrigin.Anonymous(body)
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val local = ValueSite.fromCompiler(caller, range(90, 95), ValueRole.LocalBinding).value()
            val destination = ValueSite.fromCompiler(caller, range(100, 105), ValueRole.LocalRead).value()
            val transported =
                ImmutableCallbackValue.fromCompiler(
                        origin,
                        source,
                        destination,
                        listOf(
                            ValueTransfer.fromCompiler(source, local, ValueTransferKind.LOCAL_BINDING).value(),
                            ValueTransfer.fromCompiler(local, destination, ValueTransferKind.LOCAL_READ).value(),
                        ),
                    )
                    .value()
            val direct =
                ImmutableCallbackInvocationUse.Direct(
                    transported,
                    CallbackDirectInvocationBinding.fromCompiler(lease.identity, occurrence(100, 110), supplyingOwner)
                        .value(),
                )
            fun flow(uses: List<ImmutableCallbackInvocationUse>) =
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        uses,
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val base = flow(emptyList())
            val used = flow(listOf(direct))
            assertStorage(
                observation(CallbackInvocationFlowRead.Immutable(base)),
                base.retainedBytes,
                observation(CallbackInvocationFlowRead.Immutable(used)),
                used.retainedBytes,
            )
        }

    private fun assertStorage(
        base: RelationCallbackObservation,
        baseFlowBytes: Long,
        expanded: RelationCallbackObservation,
        expandedFlowBytes: Long,
    ) {
        assertTrue(expandedFlowBytes > baseFlowBytes)
        assertEquals(expandedFlowBytes - baseFlowBytes, expanded.retainedBytes - base.retainedBytes)
        assertTrue(base.retainedBytes >= baseFlowBytes + 4096L)
        assertNotEquals(base.canonicalProjection(), expanded.canonicalProjection())
        assertNotEquals(0, base.compareTo(expanded))
        for (value in listOf(base, expanded)) {
            assertTrue(value.retainedBytes >= value.canonicalProjection().toByteArray(Charsets.UTF_8).size * 4L)
        }
    }

    private fun owner(): CallbackBodyBinding =
        with(case) {
            val occurrence = occurrence(255, 305)
            val binding =
                CallbackBindingEvidence.Direct(
                    CallbackDirectInvocationBinding.fromCompiler(
                            lease.identity,
                            occurrence,
                            RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        )
                        .value()
                )
            val body = nested.owner as RelationCallableBody.Anonymous
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        binding,
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.NOT_APPLICABLE,
                    )
                    .value()
            CallbackBodyBinding.fromCompiler(
                    body,
                    CallbackBodySupply.DirectInvocation(occurrence),
                    binding,
                    setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                )
                .value()
                .withExecution(flow)
                .value()
        }

    private fun observation(flow: CallbackInvocationFlowRead): RelationCallbackObservation =
        with(case) {
            val budget =
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(32).value(),
                        WorkUnitLimit.parse(100).value(),
                        ElapsedTimeLimitMillis.parse(1000).value(),
                    ),
                    RelationByteLimit.parse(100_000).value(),
                )
            RelationCallbackObservation.fromNativeBoundary(
                    RelationRequest.start(caller, RelationMeaning.Callees, budget),
                    occurrence(40, 41),
                    endpoint("sink", 610, 700, emptyList()).evidence,
                    caller.evidence,
                    occurrence(body.range.startInclusive, body.range.endExclusive),
                    CallbackNamedCallPolicy.Excluded(CallbackExclusionReason.NON_INLINE_ARGUMENT, occurrence(20, 80)),
                    flow,
                    CallbackResultProjection.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN),
                )
                .value()
        }
}
