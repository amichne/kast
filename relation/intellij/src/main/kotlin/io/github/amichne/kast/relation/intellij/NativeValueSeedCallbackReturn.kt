@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueCallBindingFailure
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallElement

/** An anonymous seed returns through its actual callback invocation and keeps the proven source supplier. */
internal class NativeValueSeedCallbackReturn(
    private val request: ValueFlowRequest,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val allowance: IntellijRelationAllowance,
) {
    private val source = request.source
    private val callbacks = NativeValueCallbackReturn(request, scope, projection, allowance)
    private val context =
        IntellijCallbackFlowContext(
            scope = scope,
            projection = projection,
            admitWork = { allowance.admitCallbackWork(request.budget.resources) },
            observation = projection.observation,
            callbackExpiry = ::expiry,
        )

    fun read(permit: () -> Boolean, consume: (NativeValueReturnEffect) -> Boolean): Boolean {
        if (source.callStack != ValueCallStack.Empty || source.executionOwner.body !is ValueExecutionBody.Anonymous)
            return consume(unsupportedSeedReturn())
        val body =
            when (val value = callbacks.callbackBody()) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return consume(value.failure)
            }
        val supplier =
            when (val value = supplier(body)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return consume(value.failure)
            }
        return when (
            val read =
                readCallbackInvocationFlow(
                    boundary = body.literal,
                    lexicalOwner = body.lexicalOwner,
                    scope = scope,
                    projection = projection,
                    admitWork = { allowance.admitCallbackWork(request.budget.resources) },
                    summaries = CallbackParameterSummaries(request.budget, projection.observation),
                    callbackExpiry = ::expiry,
                    retainedHost = supplier.host,
                )
        ) {
            is CallbackInvocationFlowRead.Observed ->
                observed(body = body, supplier = supplier.frame, flow = read.flow, permit = permit, consume = consume)
            is CallbackInvocationFlowRead.Unavailable ->
                consume(NativeValueReturnEffect.CallbackUnavailable(read.cause))
            is CallbackInvocationFlowRead.ContractRejected ->
                consume(NativeValueReturnEffect.CallbackContractRejected(read.cause))
            is CallbackInvocationFlowRead.Immutable ->
                consume(unsupportedSeedReturn(ValueFlowUnsupportedCause.UNMODELED_CALL))
        }
    }

    private fun supplier(
        body: NativeCallbackReturnBody
    ): Refinement<NativeSeedCallbackReturnSupplier, NativeValueReturnEffect> {
        val supply =
            when (val value = explicitSupply(body)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        val call = supplyingCallbackCall(supply.argument) ?: return unsupportedSupply()
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return unavailableSeedReturn(allowed.failure)
        }
        when (val direct = directSupplier(call)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return direct
        }
        val host =
            when (val value = projection.subject(scope, source.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> value
                is IntellijRelationSubjectLookup.Rejected -> return unsupportedSupply()
            }
        val currentHost =
            when (val current = RevalidatedRelationEndpoint.validate(source.enclosing, host.evidence)) {
                is Refinement.Refined -> current.value
                is Refinement.Rejected -> return unsupportedSupply()
            }
        val invocation =
            when (
                val value =
                    NativeValueCallerFrames(source, scope, projection)
                        .invocation(call, host.declaration, source.enclosing)
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return Refinement.Rejected(NativeValueReturnEffect.Transfer(value))
            }
        if (invocation.executionOwner.body != ValueExecutionBody.Declaration)
            return unavailableSeedReturn(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        return when (
            val frame = ValueCallFrame.direct(invocation, ValueExecutionOwner.declaration(invocation.callable))
        ) {
            is Refinement.Refined -> Refinement.Refined(NativeSeedCallbackReturnSupplier(frame.value, currentHost))
            is Refinement.Rejected -> unsupportedSupply()
        }
    }

    private fun explicitSupply(
        body: NativeCallbackReturnBody
    ): Refinement<IntellijCallbackLambdaSupply.Argument, NativeValueReturnEffect> =
        when (val supply = classifyCallbackFunctionSupply(body.literal)) {
            is IntellijCallbackLambdaSupply.Argument -> Refinement.Refined(supply)
            IntellijCallbackLambdaSupply.Stored -> unavailableSeedReturn(CallbackInvocationFlowCause.STORED_CALLBACK)
            is IntellijCallbackLambdaSupply.Returned ->
                unavailableSeedReturn(CallbackInvocationFlowCause.RETURNED_CALLBACK)
            is IntellijCallbackLambdaSupply.DefaultParameter,
            is IntellijCallbackLambdaSupply.Invocation,
            IntellijCallbackLambdaSupply.Unsupported -> unsupportedSupply()
        }

    private fun directSupplier(call: KtCallElement): Refinement<Unit, NativeValueReturnEffect> =
        projection.observation.observedAnalyze(call) {
            val symbol =
                call.resolveCall()?.signature?.symbol as? KaNamedFunctionSymbol
                    ?: return@observedAnalyze unavailableSeedReturn(
                        CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING
                    )
            if (symbol.origin != KaSymbolOrigin.SOURCE)
                return@observedAnalyze unavailableSeedReturn(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
            if (symbol.modality != KaSymbolModality.FINAL) return@observedAnalyze unsupportedSupply()
            Refinement.Refined(Unit)
        }

    private fun observed(
        body: NativeCallbackReturnBody,
        supplier: ValueCallFrame,
        flow: CallbackInvocationFlow,
        permit: () -> Boolean,
        consume: (NativeValueReturnEffect) -> Boolean,
    ): Boolean {
        if (flow.body != body.body || flow.basis != source.basis) return consume(unsupportedSeedReturn())
        return when (val binding = flow.binding) {
            is CallbackBindingEvidence.Bound -> {
                val bound = ValueCallFrame.direct(binding.binding.invocation, supplier.callee)
                if (bound !is Refinement.Refined || !sameSupplierProof(bound.value, supplier))
                    consume(unsupportedSeedReturn())
                else returns(supplier, flow, permit, consume)
            }
            is CallbackBindingEvidence.Unavailable ->
                (flow.obligations + binding.cause).all { consume(NativeValueReturnEffect.CallbackUnavailable(it)) }
            is CallbackBindingEvidence.Default,
            is CallbackBindingEvidence.Direct,
            is CallbackBindingEvidence.DependencyContract,
            is CallbackBindingEvidence.DeferredDependency ->
                (flow.obligations + CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY).all {
                    consume(NativeValueReturnEffect.CallbackUnavailable(it))
                }
        }
    }

    private fun sameSupplierProof(actual: ValueCallFrame, expected: ValueCallFrame): Boolean {
        val matched = actual.hasSameDirectProofAs(expected)
        projection.observation.count(
            if (matched) IntellijReadCounter.VALUE_FLOW_SEED_CALLBACK_SUPPLIER_MATCHED
            else IntellijReadCounter.VALUE_FLOW_SEED_CALLBACK_SUPPLIER_MISMATCH
        )
        if (!matched) recordSupplierMismatch(actual, expected)
        return matched
    }

    private fun recordSupplierMismatch(actual: ValueCallFrame, expected: ValueCallFrame) {
        val first = actual.invocation as? ValueInvocation ?: return
        val second = expected.invocation as? ValueInvocation ?: return
        if (
            first.enclosing.scope != second.enclosing.scope ||
                first.callable.scope != second.callable.scope ||
                first.executionOwner.enclosing.scope != second.executionOwner.enclosing.scope
        ) {
            projection.observation.count(IntellijReadCounter.VALUE_FLOW_SEED_CALLBACK_SUPPLIER_SCOPE_MISMATCH)
        }
        if (
            first.enclosing.constraints != second.enclosing.constraints ||
                first.callable.constraints != second.callable.constraints ||
                first.executionOwner.enclosing.constraints != second.executionOwner.enclosing.constraints
        ) {
            projection.observation.count(IntellijReadCounter.VALUE_FLOW_SEED_CALLBACK_SUPPLIER_CONSTRAINTS_MISMATCH)
        }
    }

    private fun returns(
        supplier: ValueCallFrame,
        flow: CallbackInvocationFlow,
        permit: () -> Boolean,
        consume: (NativeValueReturnEffect) -> Boolean,
    ): Boolean {
        val qualified = flow.obligations.isNotEmpty() || flow.scan != CallbackInvocationScan.EXHAUSTIVE
        if (qualified || flow.invocations.isEmpty()) {
            if (!consume(NativeValueReturnEffect.SeedCallbackReturnFlow(flow))) return false
        }
        for (selected in flow.invocations) {
            if (!permit()) return qualified || consume(NativeValueReturnEffect.SeedCallbackReturnFlow(flow))
            if (!selectedReturn(supplier, flow, selected, consume)) return false
        }
        return true
    }

    private fun selectedReturn(
        supplier: ValueCallFrame,
        flow: CallbackInvocationFlow,
        selected: CallbackParameterInvocation,
        consume: (NativeValueReturnEffect) -> Boolean,
    ): Boolean =
        when (val value = frame(supplier, flow, selected)) {
            is Refinement.Refined -> consume(NativeValueReturnEffect.Transfer(returned(value.value, supplier)))
            is Refinement.Rejected ->
                consume(NativeValueReturnEffect.SeedCallbackReturnFlow(flow)) && consume(value.failure)
        }

    private fun frame(
        supplier: ValueCallFrame,
        flow: CallbackInvocationFlow,
        selected: CallbackParameterInvocation,
    ): Refinement<ValueCallFrame, NativeValueReturnEffect> {
        if (selected.occurrence.file != supplier.callee.enclosing.file) return unsupportedSupply()
        val invocation =
            when (val value = callbacks.restoreInvocation(supplier.callee.enclosing, selected.occurrence.range)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        if (!invocation.executionOwner.hasSameProofAs(supplier.callee)) return unsupportedSupply()
        return when (
            val value =
                ValueCallFrame.callback(
                    invocation = invocation,
                    callee = source.executionOwner,
                    flow = flow,
                    selected = selected,
                    supplyingFrame = supplier,
                )
        ) {
            is Refinement.Refined -> value
            is Refinement.Rejected ->
                if (value.failure == ValueCallBindingFailure.CALLBACK_PROOF_LIMIT_REACHED)
                    Refinement.Rejected(
                        NativeValueReturnEffect.CallbackContractRejected(
                            CallbackInvocationFlowFailure.PROOF_LIMIT_REACHED
                        )
                    )
                else Refinement.Rejected(unsupportedSeedReturn())
        }
    }

    private fun returned(
        frame: ValueCallFrame,
        supplier: ValueCallFrame,
    ): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
        val stack =
            when (val value = ValueCallStack.Empty.enter(supplier)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return unsupportedReturnTransfer()
            }
        val target =
            when (val value = frame.invocation.resultSite().withState(source.valuePath, stack, source.predicates)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return unsupportedReturnTransfer()
            }
        return sourceTransfer(
            source = source,
            target = target,
            kind = ValueTransferKind.SOURCE_CALL_RETURN,
            operation = ValueSourceOperation.SeedCallbackReturn(frame),
        )
    }

    private fun expiry(): CallbackExpiryAdmission =
        if (allowance.elapsedLimitReached(request.budget.resources)) CallbackExpiryAdmission.EXPIRED
        else CallbackExpiryAdmission.CURRENT
}

private data class NativeSeedCallbackReturnSupplier(
    val frame: ValueCallFrame,
    val host: RevalidatedRelationEndpoint,
)

private fun unsupportedSeedReturn(cause: ValueFlowUnsupportedCause = ValueFlowUnsupportedCause.UNSUPPORTED_RETURN) =
    NativeValueReturnEffect.Transfer(Refinement.Rejected(cause))

private fun unsupportedReturnTransfer(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)

private fun unavailableSeedReturn(cause: CallbackInvocationFlowCause) =
    Refinement.Rejected(NativeValueReturnEffect.CallbackUnavailable(cause))

private fun unsupportedSupply() = unavailableSeedReturn(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
