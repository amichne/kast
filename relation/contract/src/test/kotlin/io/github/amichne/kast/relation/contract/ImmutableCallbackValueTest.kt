package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImmutableCallbackValueTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `immutable alias retains origin and exact ordered destination`() =
        with(fixture) {
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val local = ValueSite.fromCompiler(caller, range(90, 100), ValueRole.LocalBinding).value()
            val read = ValueSite.fromCompiler(caller, range(110, 115), ValueRole.LocalRead).value()
            val transfers =
                listOf(
                    ValueTransfer.fromCompiler(source, local, ValueTransferKind.LOCAL_BINDING).value(),
                    ValueTransfer.fromCompiler(local, read, ValueTransferKind.LOCAL_READ).value(),
                )
            val value =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        source,
                        read,
                        transfers,
                    )
                    .value()
            assertEquals(body, (value.origin as ImmutableCallbackValueOrigin.Anonymous).body)
            assertEquals(source, value.source)
            assertEquals(read, value.destination)
            assertEquals(transfers, value.transfers)
            assertEquals(
                ImmutableCallbackValueFailure.DISCONNECTED_TRANSPORT,
                ImmutableCallbackValue.fromCompiler(value.origin, source, read, transfers.reversed()).failure(),
            )
        }

    @Test
    fun `unchanged occurrence without a transport cannot become another value`() =
        with(fixture) {
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val unrelated = ValueSite.fromCompiler(caller, range(110, 115), ValueRole.LocalRead).value()
            assertEquals(
                ImmutableCallbackValueFailure.DISCONNECTED_TRANSPORT,
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        source,
                        unrelated,
                        emptyList(),
                    )
                    .failure(),
            )
            assertEquals(
                ImmutableCallbackValueFailure.ORIGIN_MISMATCH,
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(anonymous(35, 45)),
                        source,
                        source,
                        emptyList(),
                    )
                    .failure(),
            )
        }

    @Test
    fun `property writes cannot be admitted as immutable transport`() =
        with(fixture) {
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val destination = ValueSite.fromCompiler(caller, range(110, 115), ValueRole.PropertyAssignment).value()
            val transfer =
                ValueTransfer.fromCompiler(source, destination, ValueTransferKind.PROPERTY_ASSIGNMENT).value()
            assertEquals(
                ImmutableCallbackValueFailure.UNSUPPORTED_TRANSPORT,
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        source,
                        destination,
                        listOf(transfer),
                    )
                    .failure(),
            )
        }

    @Test
    fun `compiler confirmed transparent return preserves source argument and exact call result`() =
        with(fixture) {
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val argument =
                ValueSite.fromCompiler(caller, body.range, ValueRole.Argument(invocation, binding.position)).value()
            val edges =
                listOf(
                    ValueTransfer.fromCompiler(source, argument, ValueTransferKind.ARGUMENT).value(),
                    ValueTransfer.fromCompiler(argument, invocation.resultSite(), ValueTransferKind.WRAPPER_RETURN)
                        .value(),
                )
            val value =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        source,
                        invocation.resultSite(),
                        edges,
                    )
                    .value()
            assertEquals(source, value.source)
            assertEquals(edges, value.transfers)
            val unrelated = ValueInvocation.fromCompiler(caller, range(90, 120), target).value()
            assertEquals(
                ValueTransferFailure.ROLE_MISMATCH,
                ValueTransfer.fromCompiler(argument, unrelated.resultSite(), ValueTransferKind.WRAPPER_RETURN)
                    .failure(),
            )
        }
}
