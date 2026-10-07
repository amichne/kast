package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackAliasForwardingTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `immutable alias forwarding retains the entire exact route`() =
        with(fixture) {
            val source = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val receiver = endpoint("sink", 410, 500, listOf("()->Unit"))
            val mapped =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(target, range(340, 360), receiver).value(),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            val start = ValueSite.fromCompiler(target, range(270, 275), ValueRole.ExpressionResult).value()
            val local = ValueSite.fromCompiler(target, range(260, 280), ValueRole.LocalBinding).value()
            val read = ValueSite.fromCompiler(target, range(348, 353), ValueRole.LocalRead).value()
            val transfers =
                mutableListOf(
                    ValueTransfer.fromCompiler(start, local, ValueTransferKind.LOCAL_BINDING).value(),
                    ValueTransfer.fromCompiler(local, read, ValueTransferKind.LOCAL_READ).value(),
                )
            val aliased =
                CallbackParameterForwarding.fromCompiler(source, occurrence(348, 353), mapped, transfers).value()
            val direct = CallbackParameterForwarding.fromCompiler(source, occurrence(348, 353), mapped).value()
            assertEquals(transfers, aliased.callableTransfers)
            assertNotEquals(direct, aliased)
            assertNotEquals(direct.canonicalProjection(), aliased.canonicalProjection())
            assertTrue(aliased.retainedBytes > direct.retainedBytes)
            transfers.clear()
            assertEquals(2, aliased.callableTransfers.size)
        }

    @Test
    fun `alias forwarding rejects a disconnected path and wrong arrival`() =
        with(fixture) {
            val source = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val receiver = endpoint("sink", 410, 500, listOf("()->Unit"))
            val mapped =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(target, range(340, 360), receiver).value(),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            val start = ValueSite.fromCompiler(target, range(270, 275), ValueRole.ExpressionResult).value()
            val local = ValueSite.fromCompiler(target, range(260, 280), ValueRole.LocalBinding).value()
            val other = ValueSite.fromCompiler(target, range(300, 320), ValueRole.LocalBinding).value()
            val read = ValueSite.fromCompiler(target, range(348, 353), ValueRole.LocalRead).value()
            val bindingTransfer = ValueTransfer.fromCompiler(start, local, ValueTransferKind.LOCAL_BINDING).value()
            val disconnected = ValueTransfer.fromCompiler(other, read, ValueTransferKind.LOCAL_READ).value()
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_CALLABLE_TRANSFER_PATH,
                CallbackParameterForwarding.fromCompiler(
                        source,
                        occurrence(348, 353),
                        mapped,
                        listOf(bindingTransfer, disconnected),
                    )
                    .failure(),
            )
            val connected = ValueTransfer.fromCompiler(local, read, ValueTransferKind.LOCAL_READ).value()
            assertEquals(
                CallbackInvocationFlowFailure.INVALID_CALLABLE_TRANSFER_PATH,
                CallbackParameterForwarding.fromCompiler(
                        source,
                        occurrence(355, 357),
                        mapped,
                        listOf(bindingTransfer, connected),
                    )
                    .failure(),
            )
        }

    @Test
    fun `alias forwarding rejects evidence belonging to a different callable`() =
        with(fixture) {
            val source = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val receiver = endpoint("sink", 410, 500, listOf("()->Unit"))
            val mapped =
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(target, range(340, 360), receiver).value(),
                        RelationCallableBody.Named.fromCompiler(target.evidence).value(),
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            val start = ValueSite.fromCompiler(caller, range(30, 35), ValueRole.ExpressionResult).value()
            val local = ValueSite.fromCompiler(caller, range(20, 40), ValueRole.LocalBinding).value()
            val read = ValueSite.fromCompiler(caller, range(50, 55), ValueRole.LocalRead).value()
            val foreign =
                listOf(
                    ValueTransfer.fromCompiler(start, local, ValueTransferKind.LOCAL_BINDING).value(),
                    ValueTransfer.fromCompiler(local, read, ValueTransferKind.LOCAL_READ).value(),
                )
            assertEquals(
                CallbackInvocationFlowFailure.CALLABLE_TRANSFER_BINDING_MISMATCH,
                CallbackParameterForwarding.fromCompiler(source, occurrence(348, 353), mapped, foreign).failure(),
            )
        }
}
