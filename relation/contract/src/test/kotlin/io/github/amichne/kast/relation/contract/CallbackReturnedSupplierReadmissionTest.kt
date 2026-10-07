package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackReturnedSupplierReadmissionTest {
    private val fixture = CallbackInvocationFlowFixture()
    private val owner = MovingLiveReadAuthorityFixture(fixture.root)

    @Test
    fun `returned supplier restores factory captures and invoked callback evidence without moving lexical owners`() =
        with(fixture) {
            val prior = owner.admit()
            val old = returnedInventory(prior)
            val supplierOwner = live(prior, caller)
            val formalOwner = live(prior, target)
            val factoryOwner = live(prior, endpoint("factory", 410, 600, listOf("()->Unit")))
            val bodyTarget = live(prior, endpoint("bodyTarget", 710, 790, emptyList()))
            val returnedBody = anonymous(470, 500)
            val capturedInvocation =
                CallbackParameterInvocation.fromCompiler(occurrence(480, 490), returnedBody).value()
            assertEquals(setOf(supplierOwner, formalOwner, factoryOwner, bodyTarget), old.requiredEndpoints().toSet())
            val current = owner.advance()
            val endpoints =
                old.requiredEndpoints().associateWith { endpoint ->
                    val resolved = endpoint as RelationEndpoint.Resolved
                    RelationEndpoint.resolve(current, resolved.scope, resolved.evidence, resolved.constraints).value()
                }
            val restored = CallbackEndpointReadmissions.fromCompiler(current, endpoints).value().readmit(old).value()
            val restoredValue = restored.partitions.single().suppliers.single().value
            val restoredFactory = (restoredValue.origin as ImmutableCallbackValueOrigin.Returned).factory
            val restoredCapture = restoredFactory.captures.single()
            val restoredContent = restoredCapture.content as CallbackFactoryCaptureContent.Callable
            assertEquals(
                current,
                ((restoredFactory.bodyCalls as CallbackFactoryBodyCalls.Exhaustive).calls.first()
                        as CallbackFactoryBodyCall.Named)
                    .target
                    .lease,
            )
            assertEquals(current.identity, restoredFactory.invocation.basis)
            assertEquals(current.identity, restoredFactory.returnedValue.source.basis)
            assertEquals(current.identity, restoredCapture.binding.invocation.basis)
            assertEquals(current.identity, restoredContent.values.single().source.basis)
            assertEquals(returnedBody, restoredContent.invocations.single().owner)
            assertEquals(capturedInvocation.occurrence, restoredContent.invocations.single().occurrence)
            assertEquals(factoryOwner.compilerIdentity, restoredFactory.returnedValue.source.enclosing.compilerIdentity)
            assertEquals(
                supplierOwner.compilerIdentity,
                restoredContent.values.single().source.enclosing.compilerIdentity,
            )
            assertEquals(old.requiredSourceFiles(), restored.requiredSourceFiles())
        }

    @Test
    fun `missing named target in returned body cannot be reused under current authority`() =
        with(fixture) {
            val old = returnedInventory(owner.admit())
            val factory =
                (old.partitions.single().suppliers.single().value.origin as ImmutableCallbackValueOrigin.Returned)
                    .factory
            val target =
                ((factory.bodyCalls as CallbackFactoryBodyCalls.Exhaustive).calls.first()
                        as CallbackFactoryBodyCall.Named)
                    .target
            val current = owner.advance()
            val mapped =
                old.requiredEndpoints()
                    .filter { it != target }
                    .associateWith { endpoint ->
                        val resolved = endpoint as RelationEndpoint.Resolved
                        RelationEndpoint.resolve(current, resolved.scope, resolved.evidence, resolved.constraints)
                            .value()
                    }
            assertEquals(
                CallbackSummaryReadmissionFailure.MissingEndpoint(target.valueIdentity),
                CallbackEndpointReadmissions.fromCompiler(current, mapped).value().readmit(old).failure(),
            )
        }

    private fun live(
        authority: io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority,
        endpoint: RelationEndpoint.Resolved,
    ): RelationEndpoint.Resolved =
        with(fixture) {
            RelationEndpoint.resolve(authority, endpoint.scope, endpoint.evidence, endpoint.constraints).value()
        }

    private fun returnedInventory(prior: io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority) =
        with(fixture) {
            fun live(endpoint: RelationEndpoint.Resolved) =
                RelationEndpoint.resolve(prior, endpoint.scope, endpoint.evidence, endpoint.constraints).value()
            val supplierOwner = live(caller)
            val formalOwner = live(target)
            val factoryOwner = live(endpoint("factory", 410, 600, listOf("()->Unit")))
            val factoryCall = ValueInvocation.fromCompiler(supplierOwner, range(20, 80), factoryOwner).value()
            val returnedBody = anonymous(470, 500)
            val returnedSource =
                ValueSite.fromCompiler(factoryOwner, returnedBody.range, ValueRole.ExpressionResult).value()
            val returned =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(returnedBody),
                        returnedSource,
                        returnedSource,
                        emptyList(),
                    )
                    .value()
            val capture = capture(factoryCall, supplierOwner, returnedBody)
            val bodyTarget = live(endpoint("bodyTarget", 710, 790, emptyList()))
            val content = capture.content as CallbackFactoryCaptureContent.Callable
            val calls =
                bodyCalls(
                    returnedBody,
                    listOf(
                        CallbackFactoryBodyCall.Named(occurrence(472, 475), bodyTarget),
                        CallbackFactoryBodyCall.Captured(content.invocations.single(), captureFormal(capture.binding)),
                    ),
                )
            val factory = CallbackFactoryReturn.fromCompiler(factoryCall, returned, listOf(capture), calls).value()
            suppliedInventory(supplierOwner, formalOwner, factoryCall, factory)
        }

    private fun suppliedInventory(
        supplierOwner: RelationEndpoint.Resolved,
        formalOwner: RelationEndpoint.Resolved,
        factoryCall: ValueInvocation,
        factory: CallbackFactoryReturn,
    ) =
        with(fixture) {
            val suppliedCall = ValueInvocation.fromCompiler(supplierOwner, range(10, 100), formalOwner).value()
            val suppliedBinding =
                CallbackArgumentBinding.fromCompiler(suppliedCall, supplyingOwner, binding.position, binding.parameter)
                    .value()
            val destination =
                ValueSite.fromCompiler(
                        supplierOwner,
                        factoryCall.range,
                        ValueRole.Argument(suppliedCall, binding.position),
                    )
                    .value()
            val suppliedValue =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Returned(factory),
                        factoryCall.resultSite(),
                        destination,
                        listOf(
                            ValueTransfer.fromCompiler(
                                    factoryCall.resultSite(),
                                    destination,
                                    ValueTransferKind.ARGUMENT,
                                )
                                .value()
                        ),
                    )
                    .value()
            val supplier =
                CallbackParameterSupplier.fromCompiler(
                        suppliedBinding,
                        CallbackSupplierSelection.Explicit(destination),
                        suppliedValue,
                    )
                    .value()
            supplierInventory(supplier)
        }

    private fun capture(
        factoryCall: ValueInvocation,
        supplierOwner: RelationEndpoint.Resolved,
        returnedBody: RelationCallableBody.Anonymous,
    ): CallbackFactoryCapture =
        with(fixture) {
            val captureBinding =
                CallbackArgumentBinding.fromCompiler(
                        factoryCall,
                        supplyingOwner,
                        ValueArgumentPosition.parse(0).value(),
                        occurrence(425, 445),
                    )
                    .value()
            val capturedBody = anonymous(40, 60)
            val capturedSource =
                ValueSite.fromCompiler(supplierOwner, capturedBody.range, ValueRole.ExpressionResult).value()
            val selected =
                ValueSite.fromCompiler(
                        supplierOwner,
                        capturedBody.range,
                        ValueRole.Argument(factoryCall, captureBinding.position),
                    )
                    .value()
            val capturedValue =
                ImmutableCallbackValue.fromCompiler(
                        ImmutableCallbackValueOrigin.Anonymous(capturedBody),
                        capturedSource,
                        selected,
                        listOf(
                            ValueTransfer.fromCompiler(capturedSource, selected, ValueTransferKind.ARGUMENT).value()
                        ),
                    )
                    .value()
            val capturedInvocation =
                CallbackParameterInvocation.fromCompiler(occurrence(480, 490), returnedBody).value()
            return@with CallbackFactoryCapture.fromCompiler(
                    captureBinding,
                    CallbackFactoryCaptureSelection.Explicit(selected),
                    CallbackFactoryCaptureContent.Callable(listOf(capturedValue), listOf(capturedInvocation)),
                )
                .value()
        }
}
