package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompleteCallbackSupplyTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `selected default or argument completeness cannot omit an independently required formal`() =
        with(fixture) {
            val supply = supplied()
            val second =
                CallbackParameterIdentity.fromCompiler(
                        target,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(212, 224),
                    )
                    .value()
            assertEquals(
                CompleteCallbackSuppliesFailure.FORMAL_INVENTORY_MISMATCH,
                CompleteCallbackSupplies.fromCompiler(listOf(supply), listOf(supply.supplier.formal, second)).failure(),
            )
            assertEquals(
                CompleteCallbackSuppliesFailure.DUPLICATE,
                CompleteCallbackSupplies.fromCompiler(listOf(supply, supply), listOf(supply.supplier.formal)).failure(),
            )
            val source = mutableListOf(supply)
            val group = CompleteCallbackSupplies.fromCompiler(source, listOf(supply.supplier.formal)).value()
            source.clear()
            assertEquals(listOf(supply), group.values)
            assertEquals(emptyList<CallbackParameterInvocation>(), group.values.single().summary.invocations)
        }

    @Test
    fun `supplier mapping cannot borrow another formal summary`() =
        with(fixture) {
            val supply = supplied()
            val other =
                CallbackParameterIdentity.fromCompiler(
                        target,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(212, 224),
                    )
                    .value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        other,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            assertEquals(
                ImmutableCallbackInvocationFlowFailure.FORMAL_MISMATCH,
                CompleteCallbackSupply.fromCompiler(supply.supplier, summary).failure(),
            )
        }

    private fun supplied(): CompleteCallbackSupply =
        with(fixture) {
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val argument =
                ValueSite.fromCompiler(caller, body.range, ValueRole.Argument(invocation, binding.position)).value()
            val value =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        source,
                        argument,
                        listOf(ValueTransfer.fromCompiler(source, argument, ValueTransferKind.ARGUMENT).value()),
                    )
                    .value()
            val supplier =
                CallbackParameterSupplier.fromCompiler(binding, CallbackSupplierSelection.Explicit(argument), value)
                    .value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        supplier.formal,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            CompleteCallbackSupply.fromCompiler(supplier, summary).value()
        }
}
