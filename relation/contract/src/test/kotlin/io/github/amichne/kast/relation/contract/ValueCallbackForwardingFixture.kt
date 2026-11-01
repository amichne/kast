package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName

internal class ValueCallbackForwardingFixture {
    val fixture = CallbackInvocationFlowFixture()
    val relay =
        fixture.endpoint(
            name = "relay",
            start = 210,
            end = 400,
            parameters = listOf("(Payload)->Payload", "(Payload)->Payload"),
        )
    val terminal =
        fixture.endpoint(name = "terminal", start = 450, end = 650, parameters = listOf("(Payload)->Payload"))
    val body =
        RelationCallableBody.Anonymous.fromCompiler(
                fixture.file,
                flowRange(30, 70),
                CanonicalCompilerSignature.function(
                        rawQualifiedIdentity =
                            RelationCallableBody.Anonymous.sourceIdentity(fixture.file, flowRange(30, 70)),
                        rawReceiverType = null,
                        rawContextReceiverTypes = emptyList(),
                        rawValueParameterTypes = listOf("Payload"),
                        rawTypeParameterCount = 0,
                    )
                    .flowRefined() as CanonicalCompilerSignature.Function,
            )
            .flowRefined()
    val binding =
        CallbackArgumentBinding.fromCompiler(
                invocation = ValueInvocation.fromCompiler(fixture.caller, flowRange(20, 80), relay).flowRefined(),
                invocationOwner = fixture.supplyingOwner,
                position = ValueArgumentPosition.parse(0).flowRefined(),
                parameter = fixture.occurrence(225, 245),
            )
            .flowRefined()
    val targetBinding =
        CallbackArgumentBinding.fromCompiler(
                invocation = ValueInvocation.fromCompiler(relay, flowRange(300, 340), terminal).flowRefined(),
                invocationOwner = RelationCallableBody.Named.fromCompiler(relay.evidence).flowRefined(),
                position = ValueArgumentPosition.parse(0).flowRefined(),
                parameter = fixture.occurrence(465, 485),
            )
            .flowRefined()
    val forwarding =
        CallbackParameterForwarding.fromCompiler(
                binding.formalIdentity().flowRefined(),
                fixture.occurrence(310, 315),
                targetBinding,
            )
            .flowRefined()
    val invoked =
        CallbackParameterInvocation.fromCompiler(
                occurrence = fixture.occurrence(550, 580),
                owner = RelationCallableBody.Named.fromCompiler(terminal.evidence).flowRefined(),
                forwardings = listOf(forwarding),
            )
            .flowRefined()
    private val forwardingGraph =
        CompleteCallbackForwardingGraph.fromCompiler(
                root = binding.formalIdentity().flowRefined(),
                formals = listOf(binding.formalIdentity().flowRefined(), targetBinding.formalIdentity().flowRefined()),
                forwardings = listOf(forwarding),
                scan = CallbackInvocationScan.EXHAUSTIVE,
            )
            .flowRefined()
    val flow =
        CallbackInvocationFlow.fromCompiler(
                basis = fixture.lease.identity,
                body = body,
                binding = CallbackBindingEvidence.Bound(binding),
                invocations = listOf(invoked),
                obligations = emptySet(),
                scan = CallbackInvocationScan.EXHAUSTIVE,
                forwarding = CallbackForwardingEvidence.ExhaustedGraph(forwardingGraph),
            )
            .flowRefined()
    val graph = CompleteCallbackFlowGraph.admit(flow).flowRefined()
    val execution = ValueExecutionOwner.callback(fixture.caller, body, graph).flowRefined()
    val capture = (execution.context as ValueExecutionContext.Callback).selectedInvocation
    val supplierFrame = binding.frame
    val invocationFrame = targetBinding.frame
    val stack = stack(listOf(supplierFrame, invocationFrame))
    val parameter =
        ValueFormalParameter.fromCompiler(
                callable = fixture.caller,
                position = ValueArgumentPosition.parse(0).flowRefined(),
                name = SymbolDiscoveryCandidateName.parse("item").flowRefined(),
                range = flowRange(32, 36),
                executionOwner = execution,
            )
            .flowRefined()
    val source =
        ValueSite.fromCompiler(
                enclosing = terminal,
                range = flowRange(556, 560),
                role = ValueRole.ExpressionResult,
                callStack = stack,
            )
            .flowRefined()
    val target =
        ValueSite.fromCompiler(
                enclosing = fixture.caller,
                range = parameter.range,
                role = ValueRole.Formal(ValueFormalPosition.Parameter(parameter)),
                executionOwner = execution,
                callStack = stack,
            )
            .flowRefined()
    val entryOperation =
        ValueSourceOperation.CallbackArgumentEntry(
            invocation = capture,
            supplierFrame = supplierFrame,
            parameter = parameter,
            argumentRange = source.range,
        )
    val returnSource =
        ValueSite.fromCompiler(
                enclosing = fixture.caller,
                range = flowRange(50, 55),
                role = ValueRole.Return,
                executionOwner = execution,
                callStack = stack,
            )
            .flowRefined()
    val returnTarget =
        ValueSite.fromCompiler(
                enclosing = terminal,
                range = capture.invocation.occurrence.range,
                role = ValueRole.ExpressionResult,
                callStack = stack,
            )
            .flowRefined()
    val returnOperation = ValueSourceOperation.CallbackReturn(capture, invocationFrame)

    fun entry(
        stack: ValueCallStack = this.stack,
        operation: ValueSourceOperation.CallbackArgumentEntry = entryOperation,
    ) =
        ValueTransfer.fromCompiler(
            source = source.withState(callStack = stack).flowRefined(),
            target = target.withState(callStack = stack).flowRefined(),
            kind = ValueTransferKind.SOURCE_CALL_ENTRY,
            evidence = ValueSourceTransfer.fromCompiler(operation),
        )

    fun stack(frames: List<ValueCallFrame>) =
        frames.fold(ValueCallStack.Empty) { stack, frame -> stack.enter(frame).flowRefined() }

    fun changedSupplier() =
        ValueCallFrame.direct(
                ValueInvocation.fromCompiler(fixture.caller, flowRange(21, 81), relay).flowRefined(),
                supplierFrame.callee,
            )
            .flowRefined()
}
