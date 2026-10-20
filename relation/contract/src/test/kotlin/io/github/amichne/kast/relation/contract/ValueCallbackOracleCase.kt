package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName

internal class ValueCallbackOracleCase(generation: Long = 1, supplyStart: Int = 20) {
    val host =
        flowEndpoint(name = "readOffers", start = 0, end = 300, generation = generation) as RelationEndpoint.Resolved
    val mapper =
        flowEndpoint(
            name = "Outcome.map",
            start = 400,
            end = 600,
            parameters = listOf("(String)->String"),
            generation = generation,
        )
            as RelationEndpoint.Resolved
    val callback = callbackOracleOwner(host, 40, 60)
    private val supply = ValueInvocation.fromCompiler(host, flowRange(supplyStart, 100), mapper).flowRefined()
    val supplyingFrame = ValueCallFrame.direct(supply, ValueExecutionOwner.declaration(mapper)).flowRefined()
    private val binding =
        CallbackArgumentBinding.fromCompiler(
                invocation = supply,
                invocationOwner = RelationCallableBody.Named.fromCompiler(host.evidence).flowRefined(),
                position = callbackOracleIndex(),
                parameter = RelationOccurrence.fromBoundary(mapper.file, 410, 430).flowRefined(),
            )
            .flowRefined()
    val selected =
        CallbackParameterInvocation.fromCompiler(
                RelationOccurrence.fromBoundary(mapper.file, 500, 520).flowRefined(),
                RelationCallableBody.Named.fromCompiler(mapper.evidence).flowRefined(),
            )
            .flowRefined()
    val flow =
        CallbackInvocationFlow.fromCompiler(
                basis = host.lease.identity,
                body = (callback.body as ValueExecutionBody.Anonymous).callable,
                binding = CallbackBindingEvidence.Bound(binding),
                invocations = listOf(selected),
                obligations = emptySet(),
                scan = CallbackInvocationScan.EXHAUSTIVE,
            )
            .flowRefined()
    val invocation =
        ValueFunctionInvocation.fromCompiler(mapper, selected.occurrence.range, callbackOracleBuiltin()).flowRefined()
    val formal =
        ValueFormalParameter.fromCompilerImplicit(
                callback,
                callbackOracleIndex(),
                SymbolDiscoveryCandidateName.parse("it").flowRefined(),
            )
            .flowRefined()
    val position = ValueFormalPosition.Parameter(formal)

    fun frame() =
        ValueCallFrame.callback(
                invocation = invocation,
                callee = callback,
                flow = flow,
                selected = selected,
                supplyingFrame = supplyingFrame,
            )
            .flowRefined()

    fun argument(stack: ValueCallStack = ValueCallStack.Empty.enter(supplyingFrame).flowRefined()) =
        ValueSite.fromCompiler(
                enclosing = mapper,
                range = flowRange(510, 515),
                role = ValueRole.FunctionArgument(invocation, callbackOracleIndex()),
                callStack = stack,
            )
            .flowRefined()

    fun entered(
        callbackFrame: ValueCallFrame = frame(),
        stack: ValueCallStack = argument().callStack.enter(callbackFrame).flowRefined(),
    ) =
        ValueSite.fromCompiler(
                enclosing = host,
                range = formal.range,
                role = ValueRole.Formal(position),
                executionOwner = callback,
                callStack = stack,
            )
            .flowRefined()
}

internal fun callbackOracleOwner(host: RelationEndpoint, start: Int, end: Int): ValueExecutionOwner {
    val range = flowRange(start, end)
    val signature =
        CanonicalCompilerSignature.function(
                rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(host.file, range),
                rawReceiverType = null,
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = listOf("String"),
                rawTypeParameterCount = 0,
            )
            .flowRefined() as CanonicalCompilerSignature.Function
    val body = RelationCallableBody.Anonymous.fromCompiler(host.file, range, signature).flowRefined()
    return ValueExecutionOwner.fromCompiler(host, body, host.lease.identity).flowRefined()
}

internal fun callbackOracleBuiltin(identity: String = "kotlin.Function1.invoke"): SourceLessCallable =
    SourceLessCallable.fromCompiler(
            signature =
                CanonicalCompilerSignature.function(
                        rawQualifiedIdentity = identity,
                        rawReceiverType = null,
                        rawContextReceiverTypes = emptyList(),
                        rawValueParameterTypes = listOf("String"),
                        rawTypeParameterCount = 0,
                    )
                    .flowRefined(),
            kind = CompilerSymbolKind.FUNCTION,
            origin = SourceLessCallableOrigin.LIBRARY,
            moduleKind = SourceLessCallableModuleKind.BUILTINS,
            moduleName = SourceLessCallableModuleName.parse("kotlin-stdlib").flowRefined(),
        )
        .flowRefined()

internal fun callbackOracleIndex() = ValueArgumentPosition.parse(0).flowRefined()
