package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType

/** The actual anonymous extension receiver remains separate from the enclosing dispatch receiver. */
class ReviewedCallbackReceiver
private constructor(
    val binding: ReviewedCallbackValueBinding,
    val declared: DeclaredCallbackValueBinding,
    val kind: ValueReceiverKind,
    val type: CanonicalCompilerType,
) {
    val body
        get() = binding.body

    val executionOwner
        get() = ValueExecutionOwner.declaredCallback(declared)

    val retainedBytes: Long
        get() =
            binding.retainedBytes.addBytes(declared.retainedBytes).addBytes(type.value.length.toLong().multiplyBytes(2))

    companion object {
        fun fromCompiler(
            binding: ReviewedCallbackValueBinding,
            declared: DeclaredCallbackValueBinding,
            body: RelationCallableBody.Anonymous,
            kind: ValueReceiverKind,
            type: CanonicalCompilerType,
        ): Refinement<ReviewedCallbackReceiver, ValueFlowUnsupportedCause> {
            if (body != binding.body || body != declared.body)
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            if (!binding.admitsCallbackReceiver() || kind != ValueReceiverKind.EXTENSION)
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            val receiver =
                body.signature.receiver as? CanonicalCompilerReceiver.Present
                    ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            if (
                receiver.type != type ||
                    body.signature.contextReceivers.isNotEmpty() ||
                    body.signature.valueParameters.isNotEmpty()
            )
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return when (val composed = binding.composeDeclared(declared)) {
                is Refinement.Refined ->
                    Refinement.Refined(ReviewedCallbackReceiver(composed.value, declared, kind, type))
                is Refinement.Rejected -> composed
            }
        }
    }

    internal fun target(source: ValueSite): Refinement<ValueSite, ValueFlowUnsupportedCause> {
        if (!source.matchesScopeReceiver(binding))
            return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        return when (
            val value =
                ValueSite.fromCompiler(
                    enclosing = binding.enclosing,
                    range = body.range,
                    role = ValueRole.Formal(ValueFormalPosition.Receiver(kind)),
                    executionOwner = executionOwner,
                    valuePath = source.valuePath,
                    conditions = source.conditions,
                    callStack = source.callStack,
                )
        ) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedCallbackReceiver &&
            binding == other.binding &&
            declared == other.declared &&
            kind == other.kind &&
            type == other.type

    override fun hashCode(): Int = listOf(binding, declared, kind, type).hashCode()
}

private fun ReviewedCallbackValueBinding.admitsCallbackReceiver(): Boolean =
    selected.operation in
        setOf(
            ReviewedLibraryOperation.RUN_RECEIVER,
            ReviewedLibraryOperation.WITH,
            ReviewedLibraryOperation.APPLY,
        )

private fun ValueSite.matchesScopeReceiver(binding: ReviewedCallbackValueBinding): Boolean {
    if (basis != binding.basis || executionOwner != binding.parentExecutionOwner) return false
    if (!binding.occurrence.range.containsValueRange(range) || range.endExclusive > binding.body.range.startInclusive)
        return false
    val call = scopeActualInvocation(binding) ?: return false
    return call.range == binding.occurrence.range &&
        call.signature == binding.target.signature &&
        call.callable.valueIdentity == ValueDeclarationIdentity.fromCompiler(binding.target)
}

private fun ValueSite.scopeActualInvocation(binding: ReviewedCallbackValueBinding): ValueInvocation? =
    when (val actual = role) {
        is ValueRole.Receiver ->
            if (
                binding.selected.operation == ReviewedLibraryOperation.WITH ||
                    actual.kind != ValueReceiverKind.EXTENSION
            )
                null
            else actual.call
        is ValueRole.Argument ->
            if (binding.selected.operation != ReviewedLibraryOperation.WITH || actual.position.value != 0) null
            else actual.call
        else -> null
    }
