@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackFactoryCapture
import io.github.amichne.kast.relation.contract.CallbackFactoryCaptureContent
import io.github.amichne.kast.relation.contract.CallbackFactoryReturn
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

private data class NativeFactoryResolution(
    val lexicalOwner: CompilerGroundedSymbolEvidence,
    val activeFactories: Set<KtNamedFunction>,
)

private data class NativeFactoryCall(val function: KtNamedFunction, val arguments: List<NativeFactoryArgument>)

/** Resolves one exact factory call. PSI and K2 never escape the current read. */
internal class IntellijCallbackFactoryReturn(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    fun read(
        call: KtCallExpression,
        lexicalOwner: CompilerGroundedSymbolEvidence,
        activeFactories: Set<KtNamedFunction>,
    ): Refinement<List<ImmutableCallbackValue>, CallbackInvocationFlowCause> {
        when (val permit = context.permit()) {
            is Refinement.Rejected -> return permit
            is Refinement.Refined -> Unit
        }
        val mapped =
            when (val mapped = map(call)) {
                is Refinement.Refined -> mapped.value
                is Refinement.Rejected -> return mapped
            }
        if (mapped.function in activeFactories) return rejected(CallbackInvocationFlowCause.CALLBACK_CYCLE)
        if (activeFactories.size >= MAXIMUM_FACTORY_DEPTH)
            return rejected(CallbackInvocationFlowCause.WORK_LIMIT_REACHED)
        val factory =
            when (val target = context.target(mapped.function)) {
                is Refinement.Refined -> target.value
                is Refinement.Rejected -> return target
            }
        val enclosing =
            context.endpoint(lexicalOwner)
                ?: return rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val owner = context.owner(call) ?: return rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val range =
            context.range(call.valueInvocationExpression())
                ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val invocation =
            when (val admitted = ValueInvocation.fromCompiler(enclosing, range, factory)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        return FactoryInvocation(mapped, invocation, owner, NativeFactoryResolution(lexicalOwner, activeFactories))
            .read()
    }

    private inner class FactoryInvocation(
        private val mapped: NativeFactoryCall,
        private val invocation: ValueInvocation,
        private val owner: RelationCallableBody,
        private val resolution: NativeFactoryResolution,
    ) {
        private val factory = invocation.callable as io.github.amichne.kast.relation.contract.RelationEndpoint.Resolved
        private val enclosing = invocation.enclosing
        private val resolver = IntellijImmutableCallbackValueResolver(context, summaries)
        private val lexicalOwner = resolution.lexicalOwner
        private val activeFactories = resolution.activeFactories
        private val active = activeFactories + mapped.function
        private val captureValues =
            IntellijFactoryCaptureValues(
                context,
                summaries,
                NativeFactoryValueContext(invocation, lexicalOwner, activeFactories, active),
            )

        fun read(): Refinement<List<ImmutableCallbackValue>, CallbackInvocationFlowCause> {
            val transparent =
                when (val found = nativeTransparentReturnedParameter(mapped.function)) {
                    NativeTransparentReturnedParameter.Unresolved ->
                        return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
                    NativeTransparentReturnedParameter.NotTransparent -> null
                    is NativeTransparentReturnedParameter.Formal ->
                        mapped.function.valueParameters.indexOf(found.parameter)
                }
            if (transparent != null && !mapped.arguments[transparent].default)
                return when (val position = ValueArgumentPosition.parse(transparent)) {
                    is Refinement.Refined ->
                        captureValues.transparent(position.value, mapped.arguments[transparent].expression)
                    is Refinement.Rejected -> rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                }
            val captures =
                when (val read = captures()) {
                    is Refinement.Refined -> read.value
                    is Refinement.Rejected -> return read
                }
            val returned =
                when (val read = returned(transparent, captures)) {
                    is Refinement.Refined -> read.value
                    is Refinement.Rejected -> return read
                }
            return results(returned, captures, transparent)
        }

        private fun captures(): Refinement<List<CallbackFactoryCapture>, CallbackInvocationFlowCause> {
            val captures = mutableListOf<CallbackFactoryCapture>()
            for ((index, argument) in mapped.arguments.withIndex()) {
                when (val permit = context.permit()) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return permit
                }
                when (val read = capture(index, argument)) {
                    is Refinement.Refined -> captures += read.value
                    is Refinement.Rejected -> return read
                }
            }
            return Refinement.Refined(captures)
        }

        private fun capture(
            index: Int,
            argument: NativeFactoryArgument,
        ): Refinement<CallbackFactoryCapture, CallbackInvocationFlowCause> {
            val position =
                when (val parsed = ValueArgumentPosition.parse(index)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                }
            val parameter =
                context.occurrence(mapped.function.valueParameters[index])
                    ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            val binding =
                when (val admitted = CallbackArgumentBinding.fromCompiler(invocation, owner, position, parameter)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                }
            val site =
                context.site(
                    argument.expression,
                    if (argument.default) factory else enclosing,
                    if (argument.default) ValueRole.ExpressionResult else ValueRole.Argument(invocation, position),
                ) ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            val selection =
                when (val selected = captureValues.selection(argument, binding, site)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return selected
                }
            val content =
                when (val resolved = captureValues.content(argument, site)) {
                    is Refinement.Refined -> resolved.value
                    is Refinement.Rejected -> return resolved
                }
            val captured =
                when (val admitted = CallbackFactoryCapture.fromCompiler(binding, selection, content)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                }
            when (val retained = summaries.retention.admit(captured.retainedBytes)) {
                is Refinement.Refined -> return Refinement.Refined(captured)
                is Refinement.Rejected -> return retained
            }
        }

        private fun returned(
            transparent: Int?,
            captures: List<CallbackFactoryCapture>,
        ): Refinement<List<ImmutableCallbackValue>, CallbackInvocationFlowCause> {
            val returns =
                when (val found = callbackFactoryReturns(mapped.function, context::permit)) {
                    is Refinement.Refined -> found.value
                    is Refinement.Rejected -> return found
                }
            val returnedValues = mutableListOf<ImmutableCallbackValue>()
            if (transparent != null) {
                val content =
                    captures[transparent].content as? CallbackFactoryCaptureContent.Callable
                        ?: return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
                returnedValues += content.values
            } else
                for (expression in returns) when (
                    val resolved = resolver.resolve(expression, factory.evidence, active)
                ) {
                    is Refinement.Refined -> returnedValues += resolved.value
                    is Refinement.Rejected -> return resolved
                }
            return Refinement.Refined(returnedValues)
        }

        private fun results(
            returnedValues: List<ImmutableCallbackValue>,
            captures: List<CallbackFactoryCapture>,
            transparent: Int?,
        ): Refinement<List<ImmutableCallbackValue>, CallbackInvocationFlowCause> {
            val values = mutableListOf<ImmutableCallbackValue>()
            val bodies =
                returnedValues.mapNotNull { (it.origin as? ImmutableCallbackValueOrigin.Anonymous)?.body }.toSet()
            for (value in returnedValues) {
                when (val captured = IntellijFactoryCaptureAudit(context).read(mapped.function, value)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return captured
                }
                val exactCaptures =
                    when (val read = exactCaptures(value, captures, bodies, transparent)) {
                        is Refinement.Refined -> read.value
                        is Refinement.Rejected -> return read
                    }
                when (val read = result(value, exactCaptures)) {
                    is Refinement.Refined -> values += read.value
                    is Refinement.Rejected -> return read
                }
            }
            return Refinement.Refined(values.distinct())
        }

        private fun exactCaptures(
            value: ImmutableCallbackValue,
            captures: List<CallbackFactoryCapture>,
            bodies: Set<RelationCallableBody.Anonymous>,
            transparent: Int?,
        ): Refinement<List<CallbackFactoryCapture>, CallbackInvocationFlowCause> {
            val exactCaptures = mutableListOf<CallbackFactoryCapture>()
            for (capture in captures) {
                val content = capture.content
                if (content !is CallbackFactoryCaptureContent.Callable || transparent != null) {
                    exactCaptures += capture
                    continue
                }
                val invocations =
                    when (
                        val found =
                            IntellijFactoryCapturedInvocations(context, summaries)
                                .read(mapped.function, capture.binding.position.value, bodies)
                    ) {
                        is Refinement.Refined ->
                            found.value.filter {
                                it.owner == (value.origin as? ImmutableCallbackValueOrigin.Anonymous)?.body
                            }
                        is Refinement.Rejected -> return found
                    }
                when (
                    val admitted =
                        CallbackFactoryCapture.fromCompiler(
                            capture.binding,
                            capture.selection,
                            CallbackFactoryCaptureContent.Callable(content.values, invocations),
                        )
                ) {
                    is Refinement.Refined -> exactCaptures += admitted.value
                    is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                }
            }
            return Refinement.Refined(exactCaptures)
        }

        private fun result(
            value: ImmutableCallbackValue,
            exactCaptures: List<CallbackFactoryCapture>,
        ): Refinement<ImmutableCallbackValue, CallbackInvocationFlowCause> {
            val bodyCalls =
                when (val read = IntellijFactoryBodyCalls(context).read(mapped.function, value.origin, exactCaptures)) {
                    is Refinement.Refined -> read.value
                    is Refinement.Rejected -> return read
                }
            val proof =
                when (val admitted = CallbackFactoryReturn.fromCompiler(invocation, value, exactCaptures, bodyCalls)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                }
            val result =
                when (
                    val admitted =
                        ImmutableCallbackValue.fromCompiler(
                            ImmutableCallbackValueOrigin.Returned(proof),
                            invocation.resultSite(),
                            invocation.resultSite(),
                            emptyList(),
                        )
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                }
            when (val retained = summaries.retention.admit(result.retainedBytes)) {
                is Refinement.Refined -> return Refinement.Refined(result)
                is Refinement.Rejected -> return retained
            }
        }
    }

    private fun map(call: KtCallExpression): Refinement<NativeFactoryCall, CallbackInvocationFlowCause> =
        analyze(call) { mapFactory(call) }

    private fun org.jetbrains.kotlin.analysis.api.KaSession.mapFactory(
        call: KtCallExpression
    ): Refinement<NativeFactoryCall, CallbackInvocationFlowCause> {

        val resolved = call.resolveCall() ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val symbol =
            resolved.signature.symbol as? KaNamedFunctionSymbol
                ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val function = symbol.psi as? KtNamedFunction ?: return rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        if (!admitsFactory(symbol)) return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        if (function.containingFile.virtualFile?.let { context.scope.nativeScope.contains(it) } != true)
            return rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        val arguments = mutableListOf<NativeFactoryArgument>()
        for ((index, parameter) in symbol.valueParameters.withIndex()) {
            val supplied = resolved.valueArgumentMapping.filter { (_, mapped) -> mapped.symbol == parameter }.keys
            when (
                val argument =
                    factoryArgument(
                        supplied,
                        function.valueParameters.getOrNull(index),
                        parameter.returnType is KaFunctionType,
                    )
            ) {
                is Refinement.Refined -> arguments += argument.value
                is Refinement.Rejected -> return argument
            }
        }
        return Refinement.Refined(NativeFactoryCall(function, arguments))
    }

    private fun rejected(cause: CallbackInvocationFlowCause) = Refinement.Rejected(cause)
}

internal fun returnsCallable(function: KtNamedFunction): Boolean =
    analyze(function) { function.symbol.returnType is KaFunctionType }

private const val MAXIMUM_FACTORY_DEPTH = 64

private fun org.jetbrains.kotlin.analysis.api.KaSession.admitsFactory(symbol: KaNamedFunctionSymbol): Boolean =
    symbol.returnType is KaFunctionType && withoutFactoryReceivers(symbol)

private fun org.jetbrains.kotlin.analysis.api.KaSession.withoutFactoryReceivers(
    symbol: KaNamedFunctionSymbol
): Boolean =
    symbol.receiverParameter == null &&
        symbol.contextReceivers.isEmpty() &&
        symbol.containingDeclaration !is org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol

private fun factoryArgument(
    supplied: Set<KtExpression>,
    parameter: org.jetbrains.kotlin.psi.KtParameter?,
    callable: Boolean,
): Refinement<NativeFactoryArgument, CallbackInvocationFlowCause> {
    val expression =
        when (supplied.size) {
            0 -> parameter?.defaultValue
            1 -> supplied.single()
            else -> null
        } ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
    return Refinement.Refined(NativeFactoryArgument(expression, supplied.isEmpty(), callable))
}
