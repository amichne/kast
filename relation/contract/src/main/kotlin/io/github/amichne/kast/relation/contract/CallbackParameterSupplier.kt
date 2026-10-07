package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** K2 proved that this exact call omitted the selected formal argument. */
@ConsistentCopyVisibility
data class CallbackArgumentOmission private constructor(val binding: CallbackArgumentBinding) {
    companion object {
        fun fromCompiler(binding: CallbackArgumentBinding): CallbackArgumentOmission = CallbackArgumentOmission(binding)
    }
}

sealed interface CallbackSupplierSelection {
    data class Explicit(val argument: ValueSite) : CallbackSupplierSelection

    data class Default(val omission: CallbackArgumentOmission, val declaration: CallbackDefaultBinding) :
        CallbackSupplierSelection
}

enum class CallbackSupplierFailure {
    BASIS_MISMATCH,
    FORMAL_MISMATCH,
    SELECTION_MISMATCH,
    VALUE_DESTINATION_MISMATCH,
    INCOMPLETE_SCAN,
    DUPLICATE_INPUT,
    MISSING_PARTITION,
    DISCONNECTED_PARTITION,
    DOMAIN_MISMATCH,
    UNPROVEN_OWNER,
}

/** A possible supplier keeps the call site, actual argument selection, and immutable value proof together. */
@ConsistentCopyVisibility
data class CallbackParameterSupplier
private constructor(
    val formal: CallbackParameterIdentity,
    val binding: CallbackArgumentBinding,
    val selection: CallbackSupplierSelection,
    val value: ImmutableCallbackValue,
) {
    val retainedBytes: Long =
        value.retainedBytes
            .addBytes(4096L)
            .addBytes(binding.invocation.enclosing.detachedTextUnits().multiplyBytes(2))
            .addBytes(binding.invocation.callable.detachedTextUnits().multiplyBytes(2))

    companion object {
        fun fromCompiler(
            binding: CallbackArgumentBinding,
            selection: CallbackSupplierSelection,
            value: ImmutableCallbackValue,
        ): Refinement<CallbackParameterSupplier, CallbackSupplierFailure> {
            if (binding.invocation.basis != value.source.basis)
                return Refinement.Rejected(CallbackSupplierFailure.BASIS_MISMATCH)
            val formal =
                when (val identity = binding.formalIdentity()) {
                    is Refinement.Refined -> identity.value
                    is Refinement.Rejected -> return Refinement.Rejected(CallbackSupplierFailure.FORMAL_MISMATCH)
                }
            when (val admitted = selection.admit(binding, formal, value)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(CallbackParameterSupplier(formal, binding, selection, value))
        }
    }
}

private fun CallbackSupplierSelection.admit(
    binding: CallbackArgumentBinding,
    formal: CallbackParameterIdentity,
    value: ImmutableCallbackValue,
): Refinement<Unit, CallbackSupplierFailure> {
    when (this) {
        is CallbackSupplierSelection.Explicit -> {
            val role =
                argument.role as? ValueRole.Argument
                    ?: return Refinement.Rejected(CallbackSupplierFailure.SELECTION_MISMATCH)
            if (role.call != binding.invocation || role.position != binding.position)
                return Refinement.Rejected(CallbackSupplierFailure.SELECTION_MISMATCH)
            if (value.destination != argument)
                return Refinement.Rejected(CallbackSupplierFailure.VALUE_DESTINATION_MISMATCH)
        }
        is CallbackSupplierSelection.Default -> {
            if (omission.binding != binding || declaration.parameter != formal)
                return Refinement.Rejected(CallbackSupplierFailure.SELECTION_MISMATCH)
            val destination = value.destination
            val declared = declaration.defaultValue
            if (
                destination.enclosing != formal.callable ||
                    destination.enclosing.file != declared.file ||
                    destination.range != declared.range
            )
                return Refinement.Rejected(CallbackSupplierFailure.VALUE_DESTINATION_MISMATCH)
        }
    }
    return Refinement.Refined(Unit)
}
