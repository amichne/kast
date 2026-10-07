package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackFactoryReturnTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `factory result preserves returned source and exact selected capture context`() =
        with(fixture) {
            val factory = endpoint("factory", 210, 400, listOf("Int"))
            val call = ValueInvocation.fromCompiler(caller, range(20, 80), factory).value()
            val returnedBody = anonymous(270, 300)
            val source = ValueSite.fromCompiler(factory, returnedBody.range, ValueRole.ExpressionResult).value()
            val returned =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(returnedBody),
                        source,
                        source,
                        emptyList(),
                    )
                    .value()
            val captureBinding =
                CallbackArgumentBinding.fromCompiler(
                        call,
                        supplyingOwner,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(225, 245),
                    )
                    .value()
            val selected =
                ValueSite.fromCompiler(caller, range(40, 45), ValueRole.Argument(call, captureBinding.position)).value()
            val capture =
                CallbackFactoryCapture.fromCompiler(
                        captureBinding,
                        CallbackFactoryCaptureSelection.Explicit(selected),
                        CallbackFactoryCaptureContent.Scalar,
                    )
                    .value()
            val captures = mutableListOf(capture)
            val proof = CallbackFactoryReturn.fromCompiler(call, returned, captures, bodyCalls(returnedBody)).value()
            captures.clear()
            val value =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Returned(proof),
                        call.resultSite(),
                        call.resultSite(),
                        emptyList(),
                    )
                    .value()
            assertEquals(listOf(capture), proof.captures)
            assertEquals(returned, proof.returnedValue)
            assertEquals(call.resultSite(), value.source)
            assertTrue(value.retainedBytes > returned.retainedBytes)
            assertEquals(
                CallbackFactoryReturnFailure.CAPTURE_INVENTORY_MISMATCH,
                CallbackFactoryReturn.fromCompiler(call, returned, emptyList(), bodyCalls(returnedBody)).failure(),
            )
        }

    @Test
    fun `another factory body cannot become this call result`() =
        with(fixture) {
            val source = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val returned =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        source,
                        source,
                        emptyList(),
                    )
                    .value()
            assertEquals(
                CallbackFactoryReturnFailure.PRODUCER_MISMATCH,
                CallbackFactoryReturn.fromCompiler(invocation, returned, emptyList(), bodyCalls(body)).failure(),
            )
        }

    @Test
    fun `captured callable traces only with exact returned-body invocation evidence`() =
        with(fixture) {
            val factory = endpoint("factory", 210, 400, listOf("()->Unit"))
            val call = ValueInvocation.fromCompiler(caller, range(20, 80), factory).value()
            val returnedBody = anonymous(270, 300)
            val source = ValueSite.fromCompiler(factory, returnedBody.range, ValueRole.ExpressionResult).value()
            val returned =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(returnedBody),
                        source,
                        source,
                        emptyList(),
                    )
                    .value()
            val captureBinding =
                CallbackArgumentBinding.fromCompiler(
                        call,
                        supplyingOwner,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(225, 245),
                    )
                    .value()
            val originalSource = ValueSite.fromCompiler(caller, body.range, ValueRole.ExpressionResult).value()
            val argument =
                ValueSite.fromCompiler(caller, body.range, ValueRole.Argument(call, captureBinding.position)).value()
            val capturedValue =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(body),
                        originalSource,
                        argument,
                        listOf(
                            ValueTransfer.fromCompiler(originalSource, argument, ValueTransferKind.ARGUMENT).value()
                        ),
                    )
                    .value()
            val invoked = CallbackParameterInvocation.fromCompiler(occurrence(280, 290), returnedBody).value()
            fun result(invocations: List<CallbackParameterInvocation>): ImmutableCallbackValue {
                val capture =
                    CallbackFactoryCapture.fromCompiler(
                            captureBinding,
                            CallbackFactoryCaptureSelection.Explicit(argument),
                            CallbackFactoryCaptureContent.Callable(listOf(capturedValue), invocations),
                        )
                        .value()
                val exactCalls =
                    bodyCalls(
                        returnedBody,
                        invocations.map { CallbackFactoryBodyCall.Captured(it, captureFormal(captureBinding)) },
                    )
                val proof = CallbackFactoryReturn.fromCompiler(call, returned, listOf(capture), exactCalls).value()
                val wrongCalls =
                    if (invocations.isEmpty())
                        bodyCalls(
                            returnedBody,
                            listOf(CallbackFactoryBodyCall.Captured(invoked, captureFormal(captureBinding))),
                        )
                    else bodyCalls(returnedBody)
                assertEquals(
                    CallbackFactoryReturnFailure.BODY_CALL_INVENTORY_MISMATCH,
                    CallbackFactoryReturn.fromCompiler(call, returned, listOf(capture), wrongCalls).failure(),
                )
                return ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Returned(proof),
                        call.resultSite(),
                        call.resultSite(),
                        emptyList(),
                    )
                    .value()
            }
            assertTrue(result(listOf(invoked)).tracesSource(capturedValue.origin, capturedValue.source))
            assertEquals(false, result(emptyList()).tracesSource(capturedValue.origin, capturedValue.source))
            val wrong =
                CallbackFactoryCapture.fromCompiler(
                        captureBinding,
                        CallbackFactoryCaptureSelection.Explicit(argument),
                        CallbackFactoryCaptureContent.Callable(listOf(capturedValue), listOf(nested)),
                    )
                    .value()
            assertEquals(
                CallbackFactoryReturnFailure.CAPTURE_VALUE_MISMATCH,
                CallbackFactoryReturn.fromCompiler(call, returned, listOf(wrong), bodyCalls(returnedBody)).failure(),
            )
        }
}
