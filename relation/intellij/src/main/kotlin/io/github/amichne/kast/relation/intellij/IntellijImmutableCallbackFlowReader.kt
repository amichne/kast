package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentOmission
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.CallbackSupplierSelection
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationFlow
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationUse
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtExpression

/** Enumerates every local use of one immutable source. Unsupported arrivals remain typed obligations. */
internal class IntellijImmutableCallbackFlowReader(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    fun readDefault(
        prepared: PreparedCallbackFlow,
        origin: ImmutableCallbackValueOrigin,
        transported: ImmutableCallbackValue? = null,
    ): Refinement<ImmutableCallbackInvocationFlow, CallbackInvocationFlowFailure> {
        val declared =
            prepared.origin as? PreparedCallbackOrigin.Default
                ?: return Refinement.Rejected(CallbackInvocationFlowFailure.UNBOUND_INVOCATION)
        val formal = declared.binding.parameter
        val source =
            transported?.source
                ?: when (
                    val admitted = ValueSite.fromCompiler(formal.callable, origin.range, ValueRole.ExpressionResult)
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
                }
        val value =
            transported
                ?: when (val admitted = ImmutableCallbackValue.fromCompiler(origin, source, source, emptyList())) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
                }
        val enumeration = ImmutableCallbackEnumeration(context, summaries, origin, source)
        return when (val selected = defaultUses(prepared, declared, value, enumeration)) {
            is Refinement.Refined -> enumeration.complete()
            is Refinement.Rejected -> selected
        }
    }

    private fun defaultUses(
        prepared: PreparedCallbackFlow,
        declared: PreparedCallbackOrigin.Default,
        value: ImmutableCallbackValue,
        enumeration: ImmutableCallbackEnumeration,
    ): Refinement<Unit, CallbackInvocationFlowFailure> {
        val selected =
            when (
                val read =
                    IntellijCallbackSupplierCalls(context, summaries)
                        .read(prepared.function, prepared.parameter, declared.binding.parameter)
            ) {
                is Refinement.Refined -> read.value.filter { it.selection == NativeCallbackSupplierSelection.DEFAULT }
                is Refinement.Rejected -> return enumeration.obligation(read.failure)
            }
        // Exclude explicit overrides before value resolution and formal-body work.
        if (selected.isEmpty()) return Refinement.Refined(Unit)
        return when (val read = IntellijCallbackParameterSummaryReader(context, summaries).read(prepared)) {
            is CallbackParameterSummaryRead.Unavailable -> {
                enumeration.obligations += read.additional
                enumeration.obligation(read.cause)
            }
            is CallbackParameterSummaryRead.ContractRejected -> Refinement.Rejected(read.cause)
            is CallbackParameterSummaryRead.Available ->
                retainDefaults(selected, declared, value, read.summary, enumeration)
        }
    }

    private fun retainDefaults(
        selected: List<NativeCallbackSupplierCall>,
        declared: PreparedCallbackOrigin.Default,
        value: ImmutableCallbackValue,
        summary: io.github.amichne.kast.relation.contract.CallbackParameterSummary,
        enumeration: ImmutableCallbackEnumeration,
    ): Refinement<Unit, CallbackInvocationFlowFailure> {
        enumeration.obligations += summary.obligations
        for (call in selected) {
            val enclosing =
                call.binding.invocation.enclosing as? RelationEndpoint.Resolved
                    ?: return enumeration.obligation(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
            when (val admitted = enumeration.readOwner(call.call, call.binding.invocationOwner, enclosing)) {
                is Refinement.Refined -> if (enumeration.stopped) return Refinement.Refined(Unit)
                is Refinement.Rejected -> return admitted
            }
            val selection =
                CallbackSupplierSelection.Default(CallbackArgumentOmission.fromCompiler(call.binding), declared.binding)
            val supplier =
                when (val admitted = CallbackParameterSupplier.fromCompiler(call.binding, selection, value)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(CallbackInvocationFlowFailure.CALLABLE_TRANSFER_BINDING_MISMATCH)
                }
            when (val retained = summaries.retention.admit(supplier.retainedBytes)) {
                is Refinement.Refined -> enumeration.uses += ImmutableCallbackInvocationUse.Supplied(supplier, summary)
                is Refinement.Rejected -> return enumeration.obligation(retained.failure)
            }
        }
        return Refinement.Refined(Unit)
    }

    fun read(
        expression: KtExpression,
        lexicalOwner: CompilerGroundedSymbolEvidence,
        origin: ImmutableCallbackValueOrigin,
    ): Refinement<ImmutableCallbackInvocationFlow, CallbackInvocationFlowFailure> {
        val endpoint =
            context.endpoint(lexicalOwner)
                ?: return Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
        val source =
            when (val value = ValueSite.fromCompiler(endpoint, origin.range, ValueRole.ExpressionResult)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
            }
        val initial =
            when (val value = ImmutableCallbackValue.fromCompiler(origin, source, source, emptyList())) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
            }
        return ImmutableCallbackEnumeration(context, summaries, origin, source).read(expression, initial)
    }
}

internal fun transport(
    value: ImmutableCallbackValue,
    destination: ValueSite,
    kind: ValueTransferKind,
    evidence: ValueTransferEvidence = ValueTransferEvidence.Direct,
): Refinement<ImmutableCallbackValue, CallbackInvocationFlowCause> {
    val edge =
        when (val result = ValueTransfer.fromCompiler(value.destination, destination, kind, evidence)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected ->
                return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    return when (
        val result =
            ImmutableCallbackValue.fromCompiler(value.origin, value.source, destination, value.transfers + edge)
    ) {
        is Refinement.Refined -> result
        is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
    }
}

internal fun ImmutableCallbackInvocationFlowFailure.callbackFailure(): CallbackInvocationFlowFailure =
    when (this) {
        ImmutableCallbackInvocationFlowFailure.ORIGIN_MISMATCH -> CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT
        ImmutableCallbackInvocationFlowFailure.BASIS_MISMATCH -> CallbackInvocationFlowFailure.BASIS_MISMATCH
        ImmutableCallbackInvocationFlowFailure.DESTINATION_MISMATCH ->
            CallbackInvocationFlowFailure.CALLABLE_TRANSFER_BINDING_MISMATCH
        ImmutableCallbackInvocationFlowFailure.DUPLICATE_USE -> CallbackInvocationFlowFailure.DUPLICATE_INVOCATION
        ImmutableCallbackInvocationFlowFailure.MISSING_OBLIGATION -> CallbackInvocationFlowFailure.MISSING_OBLIGATION
        ImmutableCallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH ->
            CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH
        ImmutableCallbackInvocationFlowFailure.DUPLICATE_OWNER_BINDING ->
            CallbackInvocationFlowFailure.DUPLICATE_OWNER_BINDING
        ImmutableCallbackInvocationFlowFailure.FORMAL_MISMATCH -> CallbackInvocationFlowFailure.UNBOUND_INVOCATION
        ImmutableCallbackInvocationFlowFailure.INCOMPLETE_SUMMARY,
        ImmutableCallbackInvocationFlowFailure.INVALID_SCAN -> CallbackInvocationFlowFailure.INVALID_SCAN_PROOF
        ImmutableCallbackInvocationFlowFailure.UNPROVEN_SUPPLY_OWNER ->
            CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH
    }
