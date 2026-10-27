package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CompleteCallbackFlowGraph
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueImplementationCoverage
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Initial callback admission uses the same exhausted implementation inventory and selected-frame scan as value entry.
 */
internal class NativeInitialImplementationCapture(
    private val context: IntellijCallbackFlowContext,
    private val allowance: IntellijRelationAllowance,
    private val projection: IntellijK2RelationProjection,
    private val recordFailure: (io.github.amichne.kast.relation.contract.ValueCallbackInputFailure) -> Unit,
) {
    fun read(
        literal: KtFunction,
        host: PsiElement,
        enclosing: RelationEndpoint,
    ): Refinement<NativeValueExecution, ValueFlowUnsupportedCause> {
        when (val permission = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected ->
                return failed(
                    io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.Callback(permission.failure)
                )
        }
        val supply =
            classifyCallbackFunctionSupply(literal) as? IntellijCallbackLambdaSupply.Argument ?: return unavailable()
        val call = supplyingCallbackCall(supply.argument) ?: return unavailable()
        val mapped =
            when (val read = nativeArgumentBinding(call, supply.expression, context.observation)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return unavailable()
            }
        val declared = mapped.declaration as? KtNamedFunction ?: return unavailable()
        if (declared.bodyExpression != null) return unavailable()
        val target =
            when (val selected = context.target(declared)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected ->
                    return failed(
                        io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.Callback(selected.failure)
                    )
            }
        val caller =
            when (val read = caller(call, host, enclosing)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        return source(call, supply, mapped, target, caller, literal, enclosing)
    }

    private fun caller(
        call: KtCallElement,
        host: PsiElement,
        enclosing: RelationEndpoint,
    ): Refinement<NativeValueExecution, ValueFlowUnsupportedCause> {
        return when (val plain = nativeValueExecutionOwner(call.valueInvocationExpression(), host, enclosing)) {
            is Refinement.Refined -> plain
            is Refinement.Rejected ->
                when (
                    val captured =
                        nativeValueCaptureOwner(
                            element = call.valueInvocationExpression(),
                            host = host,
                            enclosing = enclosing,
                            scope = context.scope,
                            projection = projection,
                            allowance = allowance,
                            admitWork = { allowance.admitCallbackWork(context.scope.request.budget.resources) },
                            expiry = {
                                if (allowance.elapsedLimitReached(context.scope.request.budget.resources))
                                    CallbackExpiryAdmission.EXPIRED
                                else CallbackExpiryAdmission.CURRENT
                            },
                        )
                ) {
                    is Refinement.Refined -> captured
                    is Refinement.Rejected -> return captured
                }
        }
    }

    private fun source(
        call: KtCallElement,
        supply: IntellijCallbackLambdaSupply.Argument,
        mapped: NativeArgument,
        target: RelationEndpoint,
        caller: NativeValueExecution,
        literal: KtFunction,
        enclosing: RelationEndpoint,
    ): Refinement<NativeValueExecution, ValueFlowUnsupportedCause> {
        val invocation =
            when (
                val read =
                    ValueInvocation.fromCompiler(
                        enclosing = enclosing,
                        range = sourceValueRange(call.valueInvocationExpression()),
                        callable = target,
                        executionOwner = caller.owner,
                    )
            ) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return unavailable()
            }
        val source =
            when (
                val read =
                    ValueSite.fromCompiler(
                        enclosing = enclosing,
                        range = sourceValueRange(supply.expression),
                        role = ValueRole.Argument(invocation, mapped.position),
                        executionOwner = caller.owner,
                    )
            ) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return unavailable()
            }
        return implementations(source, invocation, call, literal, enclosing)
    }

    private fun implementations(
        source: ValueSite,
        invocation: ValueInvocation,
        call: KtCallElement,
        literal: KtFunction,
        enclosing: RelationEndpoint,
    ): Refinement<NativeValueExecution, ValueFlowUnsupportedCause> {
        val dispatch =
            when (val read = dispatch(source, invocation)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val alternatives = mutableListOf<CompleteCallbackFlowGraph>()
        for (fact in dispatch.batch.facts) {
            when (val permission = context.permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected ->
                    return failed(
                        io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.Callback(permission.failure)
                    )
            }
            when (val alternative = alternative(source, invocation, call, literal, dispatch, fact)) {
                is Refinement.Refined -> alternatives += alternative.value
                is Refinement.Rejected -> return alternative
            }
        }
        val graph =
            when (val combined = CompleteCallbackFlowGraph.combine(alternatives)) {
                is Refinement.Refined -> combined.value
                is Refinement.Rejected ->
                    return failed(
                        io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.Graph(combined.failure)
                    )
            }
        val body = context.anonymous(literal) ?: return unavailable()
        return when (val owner = ValueExecutionOwner.callback(enclosing, body, graph)) {
            is Refinement.Refined -> Refinement.Refined(NativeValueExecution(literal, owner.value))
            is Refinement.Rejected -> owner
        }
    }

    private fun dispatch(
        source: ValueSite,
        invocation: ValueInvocation,
    ): Refinement<io.github.amichne.kast.relation.contract.ValueImplementationDispatch, ValueFlowUnsupportedCause> {
        val request = ValueFlowRequest(source, context.scope.request.budget, context.scope.request.boundary)
        val dispatch =
            NativeValueImplementationDispatch(
                    request = request,
                    scope = context.scope,
                    projection = projection,
                    allowance = allowance,
                )
                .forCallable(invocation.callable)
        when (dispatch) {
            NativeValueArgumentDispatch.Direct -> return unavailable()
            is NativeValueArgumentDispatch.Unsupported -> return Refinement.Rejected(dispatch.cause)
            is NativeValueArgumentDispatch.Rejected ->
                return failed(
                    io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.ImplementationRejected(
                        dispatch.cause
                    )
                )
            is NativeValueArgumentDispatch.Implementations ->
                if (dispatch.evidence.coverage !is ValueImplementationCoverage.Exhausted)
                    return failed(
                        io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.ImplementationIncomplete(
                            dispatch.evidence
                        )
                    )
        }
        return Refinement.Refined(dispatch.evidence)
    }

    private fun alternative(
        source: ValueSite,
        invocation: ValueInvocation,
        call: KtCallElement,
        literal: KtFunction,
        dispatch: io.github.amichne.kast.relation.contract.ValueImplementationDispatch,
        fact: io.github.amichne.kast.relation.contract.RelationFact,
    ): Refinement<CompleteCallbackFlowGraph, ValueFlowUnsupportedCause> {
        val frame =
            when (val read = nativeValueImplementationFrame(invocation, dispatch, fact, context.scope, projection)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val formal =
            when (val read = formal(frame, source)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        return graph(call, literal, frame, formal)
    }

    private fun formal(
        frame: io.github.amichne.kast.relation.contract.ValueCallFrame,
        source: ValueSite,
    ): Refinement<CallbackParameterIdentity, ValueFlowUnsupportedCause> {
        val function =
            when (val restored = context.declaration(frame.callee.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> restored.declaration as? KtNamedFunction
                is IntellijRelationSubjectLookup.Rejected -> null
            } ?: return unavailable()
        val position = (source.role as ValueRole.Argument).position
        val parameter = function.valueParameters.getOrNull(position.value) ?: return unavailable()
        val occurrence = context.occurrence(parameter) ?: return unavailable()
        val formal =
            when (val read = CallbackParameterIdentity.fromCompiler(frame.callee.enclosing, position, occurrence)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return unavailable()
            }
        return Refinement.Refined(formal)
    }

    private fun graph(
        call: KtCallElement,
        literal: KtFunction,
        frame: io.github.amichne.kast.relation.contract.ValueCallFrame,
        formal: CallbackParameterIdentity,
    ): Refinement<CompleteCallbackFlowGraph, ValueFlowUnsupportedCause> {
        val function =
            when (val restored = context.declaration(frame.callee.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> restored.declaration as? KtNamedFunction
                is IntellijRelationSubjectLookup.Rejected -> null
            } ?: return unavailable()
        val parameter = function.valueParameters.getOrNull(formal.position.value) ?: return unavailable()
        val summaries = CallbackParameterSummaries(context.scope.request.budget, context.observation)
        val selected =
            when (
                val read =
                    IntellijCallbackSupplierCalls(context, summaries)
                        .readExact(
                            call = call,
                            function = function,
                            parameter = parameter,
                            formal = formal,
                            frame = frame,
                        )
            ) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected ->
                    return failed(
                        io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.Callback(read.failure)
                    )
            }
        val suppliers =
            when (val read = IntellijCallbackSupplierValues(context, summaries).read(selected, formal)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected ->
                    return failed(
                        io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.Callback(read.failure)
                    )
            }
        if (suppliers.size != 1) return unavailable()
        val flow =
            readSelectedCallbackInputFlow(
                supplier = suppliers.single(),
                frames = listOf(frame),
                literal = literal,
                context = context,
                summaries = summaries,
            )
        return admit(flow)
    }

    private fun admit(
        flow: CallbackInvocationFlowRead
    ): Refinement<CompleteCallbackFlowGraph, ValueFlowUnsupportedCause> {
        when (flow) {
            is CallbackInvocationFlowRead.Observed -> Unit
            is CallbackInvocationFlowRead.Unavailable ->
                return failed(io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.Callback(flow.cause))
            is CallbackInvocationFlowRead.ContractRejected ->
                return failed(io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.Contract(flow.cause))
            is CallbackInvocationFlowRead.Immutable -> return unavailable()
        }
        when (val graph = CompleteCallbackFlowGraph.admit(flow.flow)) {
            is Refinement.Refined -> return graph
            is Refinement.Rejected ->
                return failed(io.github.amichne.kast.relation.contract.ValueCallbackInputFailure.Graph(graph.failure))
        }
    }

    private fun failed(
        failure: io.github.amichne.kast.relation.contract.ValueCallbackInputFailure
    ): Refinement.Rejected<ValueFlowUnsupportedCause> {
        recordFailure(failure)
        return Refinement.Rejected(failure.nativeCallbackInputCause())
    }

    private fun unavailable(): Refinement.Rejected<ValueFlowUnsupportedCause> =
        Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
}
