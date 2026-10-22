package io.github.amichne.kast.appserver.core

import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal suspend fun <Runtime, Input, Output, Failure> BrokerTool<Runtime, Input, Output, Failure>.invokeWithinMode(
    runtime: Runtime,
    input: Input,
    context: BrokerInvocationContext,
): ProviderCall<Output> =
    when (executionMode(input)) {
        QueryExecutionModeDocument.BOUNDED -> withTimeout(invocationBudget.value) { invoke(runtime, input, context) }
        QueryExecutionModeDocument.RUNNER_CONTROLLED -> invoke(runtime, input, context)
    }.also { currentCoroutineContext().ensureActive() }
