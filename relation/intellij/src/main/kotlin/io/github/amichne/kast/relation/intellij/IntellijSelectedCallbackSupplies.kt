@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplies
import io.github.amichne.kast.relation.contract.CompleteCallbackSupply
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter

internal sealed interface SelectedCallbackSuppliesRead {
    data object NotRequired : SelectedCallbackSuppliesRead

    data class Formal(val upstream: NativeCallbackSupplierFormal.Found) : SelectedCallbackSuppliesRead

    data class Direct(val invocations: io.github.amichne.kast.relation.contract.CompleteCallbackDirectInvocations) :
        SelectedCallbackSuppliesRead

    data class Available(val supplies: CompleteCallbackSupplies) : SelectedCallbackSuppliesRead

    data class Unavailable(
        val cause: CallbackInvocationFlowCause,
        val additional: Set<CallbackInvocationFlowCause> = emptySet(),
    ) : SelectedCallbackSuppliesRead
}

private data class SelectedCallbackArgument(
    val position: ValueArgumentPosition,
    val parameter: KtParameter,
    val expression: KtExpression,
    val default: Boolean,
)

private sealed interface SelectedCallbackCall {
    data class DeclaredContracts(
        val bindings: List<io.github.amichne.kast.relation.contract.CallbackDependencyContract>
    ) : SelectedCallbackCall

    data object NoCallbackParameters : SelectedCallbackCall

    data class Mapped(val function: KtNamedFunction, val arguments: List<SelectedCallbackArgument>) :
        SelectedCallbackCall
}

/** Every selected callback at an actual consumer call is retained as one atomic provider record. */
internal class IntellijSelectedCallbackSupplies(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    fun read(call: KtCallElement, lexicalOwner: CompilerGroundedSymbolEvidence): SelectedCallbackSuppliesRead {
        when (val permitted = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return unavailable(permitted.failure)
        }
        if (call is KtCallExpression)
            when (val direct = IntellijSelectedCallbackInvocation(context, summaries).read(call, lexicalOwner)) {
                SelectedCallbackInvocationRead.NotRequired -> Unit
                is SelectedCallbackInvocationRead.Formal -> return SelectedCallbackSuppliesRead.Formal(direct.upstream)
                is SelectedCallbackInvocationRead.Available ->
                    return SelectedCallbackSuppliesRead.Direct(direct.invocations)
                is SelectedCallbackInvocationRead.Unavailable -> return unavailable(direct.cause)
            }
        val mapped =
            when (val selected = map(call)) {
                is Refinement.Rejected -> return unavailable(selected.failure)
                is Refinement.Refined ->
                    when (val selectedCall = selected.value) {
                        SelectedCallbackCall.NoCallbackParameters -> return SelectedCallbackSuppliesRead.NotRequired
                        is SelectedCallbackCall.DeclaredContracts -> return SelectedCallbackSuppliesRead.NotRequired
                        is SelectedCallbackCall.Mapped -> selectedCall
                    }
            }
        if (returnsCallable(mapped.function, context.observation)) return deferredFactory(call, lexicalOwner)
        val invocation =
            when (val admitted = invocation(call, lexicalOwner, mapped.function)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unavailable(admitted.failure)
            }
        return selectedArguments(call, lexicalOwner, mapped, invocation)
    }

    private fun selectedArguments(
        call: KtCallElement,
        lexicalOwner: CompilerGroundedSymbolEvidence,
        mapped: SelectedCallbackCall.Mapped,
        invocation: ValueInvocation,
    ): SelectedCallbackSuppliesRead {
        val formals = mutableListOf<CallbackParameterIdentity>()
        val result = mutableListOf<CompleteCallbackSupply>()
        for (argument in mapped.arguments) {
            val formal =
                when (val read = formal(argument, invocation.callable)) {
                    is Refinement.Refined -> read.value
                    is Refinement.Rejected -> return unavailable(read.failure)
                }
            formals += formal
            when (val read = selected(call, lexicalOwner, mapped.function, invocation, argument, formal)) {
                is SelectedSuppliedValues.Available -> result += read.values
                is SelectedSuppliedValues.Unavailable -> return read.failure
            }
        }
        return when (val admitted = CompleteCallbackSupplies.fromCompiler(result, formals)) {
            is Refinement.Refined -> SelectedCallbackSuppliesRead.Available(admitted.value)
            is Refinement.Rejected -> unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun invocation(
        call: KtCallElement,
        lexicalOwner: CompilerGroundedSymbolEvidence,
        function: KtNamedFunction,
    ): Refinement<ValueInvocation, CallbackInvocationFlowCause> {
        val target =
            when (val found = context.target(function)) {
                is Refinement.Refined -> found.value
                is Refinement.Rejected -> return Refinement.Rejected(found.failure)
            }
        val enclosing =
            context.endpoint(lexicalOwner)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val range =
            context.range(call.valueInvocationExpression())
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        return when (val admitted = ValueInvocation.fromCompiler(enclosing, range, target)) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun selected(
        call: KtCallElement,
        lexicalOwner: CompilerGroundedSymbolEvidence,
        function: KtNamedFunction,
        invocation: ValueInvocation,
        argument: SelectedCallbackArgument,
        formal: CallbackParameterIdentity,
    ): SelectedSuppliedValues {
        val owner = context.owner(call) ?: return failed(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val binding =
            when (
                val admitted =
                    CallbackArgumentBinding.fromCompiler(invocation, owner, argument.position, formal.parameter)
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return failed(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        val prepared =
            PreparedCallbackFlow(
                function,
                argument.parameter,
                invocation.callable,
                PreparedCallbackOrigin.Argument(binding, call),
            )
        val summary =
            when (val read = IntellijCallbackParameterSummaryReader(context, summaries).read(prepared)) {
                is CallbackParameterSummaryRead.Available -> read.summary
                is CallbackParameterSummaryRead.Unavailable ->
                    return SelectedSuppliedValues.Unavailable(unavailable(read.cause, read.additional))
                is CallbackParameterSummaryRead.ContractRejected ->
                    return failed(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        val selection =
            if (argument.default) NativeCallbackSupplierSelection.DEFAULT else NativeCallbackSupplierSelection.EXPLICIT
        val selected = NativeCallbackSupplierCall(binding, argument.expression, selection, lexicalOwner)
        val values =
            when (val read = IntellijCallbackSupplierValues(context, summaries).read(selected, formal)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return failed(read.failure)
            }
        val result = mutableListOf<CompleteCallbackSupply>()
        for (supplier in values) {
            val complete =
                when (val admitted = CompleteCallbackSupply.fromCompiler(supplier, summary)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return failed(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                }
            when (val retained = summaries.retention.admit(complete.retainedBytes)) {
                is Refinement.Refined -> result += complete
                is Refinement.Rejected -> return failed(retained.failure)
            }
        }
        return SelectedSuppliedValues.Available(result)
    }

    private fun formal(
        argument: SelectedCallbackArgument,
        target: RelationEndpoint,
    ): Refinement<CallbackParameterIdentity, CallbackInvocationFlowCause> {
        val parameter =
            context.occurrence(argument.parameter)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        return when (val admitted = CallbackParameterIdentity.fromCompiler(target, argument.position, parameter)) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun deferredFactory(
        call: KtCallElement,
        owner: CompilerGroundedSymbolEvidence,
    ): SelectedCallbackSuppliesRead {
        val expression =
            call as? KtCallExpression ?: return unavailable(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        return when (val read = IntellijCallbackFactoryReturn(context, summaries).read(expression, owner, emptySet())) {
            is Refinement.Refined -> SelectedCallbackSuppliesRead.NotRequired
            is Refinement.Rejected -> unavailable(read.failure)
        }
    }

    private fun map(call: KtCallElement): Refinement<SelectedCallbackCall, CallbackInvocationFlowCause> =
        context.observation.observedAnalyze(call) { selectedCall(call) }

    private fun org.jetbrains.kotlin.analysis.api.KaSession.selectedCall(
        call: KtCallElement
    ): Refinement<SelectedCallbackCall, CallbackInvocationFlowCause> {
        val resolved =
            call.resolveCall() ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val symbol =
            resolved.signature.symbol as? KaFunctionSymbol
                ?: return Refinement.Refined(SelectedCallbackCall.NoCallbackParameters)
        val callbackParameters = symbol.valueParameters.withIndex().filter { it.value.returnType is KaFunctionType }
        if (callbackParameters.isEmpty()) return Refinement.Refined(SelectedCallbackCall.NoCallbackParameters)
        val function =
            symbol.psi as? KtNamedFunction ?: return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        if (function.containingFile.virtualFile?.let { context.scope.nativeScope.contains(it) } != true)
            return declaredContracts(call, resolved.valueArgumentMapping.keys.toList(), callbackParameters.size)
        val arguments = mutableListOf<SelectedCallbackArgument>()
        for ((index, parameter) in callbackParameters) {
            val values = resolved.valueArgumentMapping.filter { (_, mapped) -> mapped.symbol == parameter }.keys
            when (val selected = argument(index, function, values)) {
                is Refinement.Refined -> arguments += selected.value
                is Refinement.Rejected -> return selected
            }
        }
        return Refinement.Refined(SelectedCallbackCall.Mapped(function, arguments))
    }

    /** Literal callback observations own these proofs; no dependency body supplier record is required. */
    private fun declaredContracts(
        call: KtCallElement,
        expressions: List<KtExpression>,
        expected: Int,
    ): Refinement<SelectedCallbackCall, CallbackInvocationFlowCause> {
        val literals = expressions.filterIsInstance<org.jetbrains.kotlin.psi.KtLambdaExpression>()
        if (literals.size != expected) return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        val bindings = mutableListOf<io.github.amichne.kast.relation.contract.CallbackDependencyContract>()
        for (literal in literals) {
            when (val read = readCallbackDependencyContract(context, call, literal)) {
                is CallbackBindingPreparation.DependencyContract -> bindings += read.binding
                else -> return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
            }
        }
        return Refinement.Refined(SelectedCallbackCall.DeclaredContracts(bindings))
    }

    private fun argument(
        index: Int,
        function: KtNamedFunction,
        values: Collection<KtExpression>,
    ): Refinement<SelectedCallbackArgument, CallbackInvocationFlowCause> {
        val declared =
            function.valueParameters.getOrNull(index)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val expression =
            when (values.size) {
                0 -> declared.defaultValue
                1 -> values.single()
                else -> null
            } ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val position =
            when (val parsed = ValueArgumentPosition.parse(index)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        return Refinement.Refined(SelectedCallbackArgument(position, declared, expression, values.isEmpty()))
    }

    private fun unavailable(
        cause: CallbackInvocationFlowCause,
        additional: Set<CallbackInvocationFlowCause> = emptySet(),
    ) = SelectedCallbackSuppliesRead.Unavailable(cause, additional)

    private fun failed(cause: CallbackInvocationFlowCause) = SelectedSuppliedValues.Unavailable(unavailable(cause))
}

private sealed interface SelectedSuppliedValues {
    data class Available(val values: List<CompleteCallbackSupply>) : SelectedSuppliedValues

    data class Unavailable(val failure: SelectedCallbackSuppliesRead.Unavailable) : SelectedSuppliedValues
}

/**
 * A compiler-confirmed invoke cannot discharge its value obligation merely because an ordinary-call reader declined.
 */
internal fun SelectedCallbackSuppliesRead.requireInvocationProof(): SelectedCallbackSuppliesRead =
    when (this) {
        SelectedCallbackSuppliesRead.NotRequired ->
            SelectedCallbackSuppliesRead.Unavailable(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        is SelectedCallbackSuppliesRead.Available,
        is SelectedCallbackSuppliesRead.Direct,
        is SelectedCallbackSuppliesRead.Formal,
        is SelectedCallbackSuppliesRead.Unavailable -> this
    }
