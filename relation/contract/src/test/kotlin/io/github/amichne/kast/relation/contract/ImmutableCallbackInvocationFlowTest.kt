package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ImmutableCallbackInvocationFlowTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `unused immutable callback proves no static invocation only with exhausted uses`() =
        with(fixture) {
            val origin = ImmutableCallbackValueOrigin.Anonymous(body)
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val proof =
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            assertFalse(proof.hasStaticInvocation)
            assertEquals(origin, proof.origin)
            assertEquals(
                ImmutableCallbackInvocationFlowFailure.INVALID_SCAN,
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        emptyList(),
                        setOf(CallbackInvocationFlowCause.PARAMETER_ESCAPES),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .failure(),
            )
        }

    @Test
    fun `direct invocation preserves transported callback origin and snapshots all terminals`() =
        with(fixture) {
            val origin = ImmutableCallbackValueOrigin.Anonymous(body)
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val local = ValueSite.fromCompiler(caller, range(90, 100), ValueRole.LocalBinding).value()
            val read = ValueSite.fromCompiler(caller, range(110, 115), ValueRole.LocalRead).value()
            val transfers =
                listOf(
                    ValueTransfer.fromCompiler(source, local, ValueTransferKind.LOCAL_BINDING).value(),
                    ValueTransfer.fromCompiler(local, read, ValueTransferKind.LOCAL_READ).value(),
                )
            val value = ImmutableCallbackValue.fromCompiler(origin, source, read, transfers).value()
            val call =
                CallbackDirectInvocationBinding.fromCompiler(
                        caller.lease.identity,
                        occurrence(110, 120),
                        supplyingOwner,
                    )
                    .value()
            val uses = mutableListOf<ImmutableCallbackInvocationUse>(ImmutableCallbackInvocationUse.Direct(value, call))
            val proof =
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        uses,
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            uses.clear()
            assertEquals(1, proof.uses.size)
            assertEquals(true, proof.hasStaticInvocation)
            val wrong =
                CallbackDirectInvocationBinding.fromCompiler(
                        caller.lease.identity,
                        occurrence(150, 160),
                        supplyingOwner,
                    )
                    .value()
            assertEquals(
                ImmutableCallbackInvocationFlowFailure.DESTINATION_MISMATCH,
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        listOf(ImmutableCallbackInvocationUse.Direct(value, wrong)),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .failure(),
            )
        }
}
