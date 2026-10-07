package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackSupplierInventoryTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `empty suppliers require an explicitly exhausted root partition`() =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val domain = RelationScopeFingerprint.from(target)
            assertEquals(
                CallbackSupplierFailure.MISSING_PARTITION,
                CompleteCallbackSupplierInventory.fromCompiler(formal, domain, emptyList()).failure(),
            )
            assertEquals(
                CallbackSupplierFailure.INCOMPLETE_SCAN,
                CompleteCallbackSupplierPartition.fromCompiler(
                        formal,
                        emptyList(),
                        emptyList(),
                        CallbackInvocationScan.INCOMPLETE,
                    )
                    .failure(),
            )
            val empty =
                CompleteCallbackSupplierPartition.fromCompiler(
                        formal,
                        emptyList(),
                        emptyList(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val admitted = CompleteCallbackSupplierInventory.fromCompiler(formal, domain, listOf(empty)).value()
            assertEquals(listOf(empty), admitted.partitions)
        }

    @Test
    fun `supplier selection keeps exact argument position and rejects a different destination`() =
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
            assertEquals(binding, supplier.binding)
            val other =
                ValueSite.fromCompiler(
                        caller,
                        body.range,
                        ValueRole.Argument(invocation, ValueArgumentPosition.parse(0).value()),
                    )
                    .value()
            assertEquals(
                CallbackSupplierFailure.SELECTION_MISMATCH,
                CallbackParameterSupplier.fromCompiler(binding, CallbackSupplierSelection.Explicit(other), value)
                    .failure(),
            )
            assertEquals(
                CallbackSupplierFailure.VALUE_DESTINATION_MISMATCH,
                CallbackParameterSupplier.fromCompiler(
                        binding,
                        CallbackSupplierSelection.Explicit(argument),
                        ImmutableCallbackValue.fromCompiler(value.origin, source, source, emptyList()).value(),
                    )
                    .failure(),
            )
        }

    @Test
    fun `incoming forwarding requires its own exhausted source partition`() =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val receiver = endpoint("receive", 410, 500, listOf("()->Unit"))
            val mapped =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(target, range(260, 280), receiver).value(),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            val root = mapped.formalIdentity().value()
            val edge = CallbackParameterForwarding.fromCompiler(formal, occurrence(268, 273), mapped).value()
            val receiverPartition =
                CompleteCallbackSupplierPartition.fromCompiler(
                        root,
                        emptyList(),
                        listOf(edge),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val domain = RelationScopeFingerprint.from(receiver)
            assertEquals(
                CallbackSupplierFailure.MISSING_PARTITION,
                CompleteCallbackSupplierInventory.fromCompiler(root, domain, listOf(receiverPartition)).failure(),
            )
            val upstream =
                CompleteCallbackSupplierPartition.fromCompiler(
                        formal,
                        emptyList(),
                        emptyList(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val inventory =
                CompleteCallbackSupplierInventory.fromCompiler(root, domain, listOf(receiverPartition, upstream))
                    .value()
            assertEquals(listOf(root, formal), inventory.partitions.map { it.formal })
            assertEquals(
                CallbackSupplierFailure.DISCONNECTED_PARTITION,
                CompleteCallbackSupplierInventory.fromCompiler(formal, domain, listOf(receiverPartition, upstream))
                    .failure(),
            )
        }
}
