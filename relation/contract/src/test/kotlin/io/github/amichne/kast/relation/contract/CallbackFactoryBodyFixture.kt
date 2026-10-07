package io.github.amichne.kast.relation.contract

internal fun CallbackInvocationFlowFixture.bodyCalls(
    body: RelationCallableBody.Anonymous,
    calls: List<CallbackFactoryBodyCall> = emptyList(),
): CallbackFactoryBodyCalls.Exhaustive =
    CallbackFactoryBodyCalls.Exhaustive.fromCompiler(body, calls, CallbackInvocationScan.EXHAUSTIVE).value()

internal fun CallbackInvocationFlowFixture.captureFormal(binding: CallbackArgumentBinding): CallbackParameterIdentity =
    CallbackParameterIdentity.fromCompiler(binding.invocation.callable, binding.position, binding.parameter).value()
