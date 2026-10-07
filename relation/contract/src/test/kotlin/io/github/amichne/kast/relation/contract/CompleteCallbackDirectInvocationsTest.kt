package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompleteCallbackDirectInvocationsTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `actual invocation snapshots every selected alternative and rejects empty inventory`() =
        with(fixture) {
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val value =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        source,
                        source,
                        emptyList(),
                    )
                    .value()
            val binding =
                CallbackDirectInvocationBinding.fromCompiler(caller.lease.identity, occurrence(30, 62), supplyingOwner)
                    .value()
            val values = mutableListOf(value)
            val proof = CompleteCallbackDirectInvocations.fromCompiler(values, binding).value()
            values.clear()
            assertEquals(listOf(ImmutableCallbackInvocationUse.Direct(value, binding)), proof.values)
            assertEquals(
                CompleteCallbackDirectInvocationsFailure.EMPTY,
                CompleteCallbackDirectInvocations.fromCompiler(emptyList(), binding).failure(),
            )
            assertEquals(
                CompleteCallbackDirectInvocationsFailure.DUPLICATE,
                CompleteCallbackDirectInvocations.fromCompiler(listOf(value, value), binding).failure(),
            )
        }

    @Test
    fun `actual invocation rejects a destination outside its call occurrence`() =
        with(fixture) {
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val value =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        source,
                        source,
                        emptyList(),
                    )
                    .value()
            val binding =
                CallbackDirectInvocationBinding.fromCompiler(
                        caller.lease.identity,
                        occurrence(150, 160),
                        supplyingOwner,
                    )
                    .value()
            assertEquals(
                CompleteCallbackDirectInvocationsFailure.InvalidUse(
                    ImmutableCallbackInvocationFlowFailure.DESTINATION_MISMATCH
                ),
                CompleteCallbackDirectInvocations.fromCompiler(listOf(value), binding).failure(),
            )
        }
}
