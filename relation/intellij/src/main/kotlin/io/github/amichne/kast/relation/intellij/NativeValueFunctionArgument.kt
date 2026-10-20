package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFunctionInvocation
import io.github.amichne.kast.relation.contract.ValueFunctionInvocationFailure
import io.github.amichne.kast.relation.contract.ValueRole
import org.jetbrains.kotlin.psi.KtCallElement

/** Source-less invoke evidence remains distinct from a source declaration or an ordinary method named invoke. */
internal fun nativeFunctionArgument(
    call: KtCallElement,
    argument: NativeMappedArgument.Function,
    executionOwner: ValueExecutionOwner,
): Refinement<ValueRole.FunctionArgument, ValueFlowUnsupportedCause> =
    when (val invocation = nativeFunctionInvocation(call, argument.callable, executionOwner)) {
        is Refinement.Refined -> Refinement.Refined(ValueRole.FunctionArgument(invocation.value, argument.position))
        is Refinement.Rejected -> invocation
    }

internal fun nativeFunctionInvocation(
    call: KtCallElement,
    callable: SourceLessCallable,
    executionOwner: ValueExecutionOwner,
): Refinement<ValueFunctionInvocation, ValueFlowUnsupportedCause> =
    when (
        val value =
            ValueFunctionInvocation.fromCompiler(
                enclosing = executionOwner.enclosing,
                range = sourceValueRange(call.valueInvocationExpression()),
                callable = callable,
                executionOwner = executionOwner,
            )
    ) {
        is Refinement.Refined -> value
        is Refinement.Rejected ->
            Refinement.Rejected(
                when (value.failure) {
                    ValueFunctionInvocationFailure.NOT_BUILTIN_INVOKE -> ValueFlowUnsupportedCause.UNMODELED_CALL
                    ValueFunctionInvocationFailure.OWNER_MISMATCH,
                    ValueFunctionInvocationFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER ->
                        ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE
                }
            )
    }
