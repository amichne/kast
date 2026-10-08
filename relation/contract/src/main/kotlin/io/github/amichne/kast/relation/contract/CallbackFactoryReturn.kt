package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import java.util.Collections

sealed interface CallbackFactoryCaptureSelection {
    data class Explicit(val value: ValueSite) : CallbackFactoryCaptureSelection

    data class Default(val declaration: CallbackDefaultBinding) : CallbackFactoryCaptureSelection
}

sealed interface CallbackFactoryCaptureContent {
    data object Scalar : CallbackFactoryCaptureContent

    data class Callable(val values: List<ImmutableCallbackValue>, val invocations: List<CallbackParameterInvocation>) :
        CallbackFactoryCaptureContent
}

enum class CallbackFactoryReturnFailure {
    BASIS_MISMATCH,
    PRODUCER_MISMATCH,
    CAPTURE_INVENTORY_MISMATCH,
    CAPTURE_BINDING_MISMATCH,
    CAPTURE_VALUE_MISMATCH,
    UNPROVEN_CALLER,
    DEPTH_LIMIT_EXCEEDED,
    BODY_CALL_INVENTORY_MISMATCH,
}

/** Every factory formal retains the selected argument/default, including non-callable closure state. */
@ConsistentCopyVisibility
data class CallbackFactoryCapture
private constructor(
    val binding: CallbackArgumentBinding,
    val selection: CallbackFactoryCaptureSelection,
    val content: CallbackFactoryCaptureContent,
) {
    val retainedBytes: Long =
        4096L.addBytes(
            when (content) {
                CallbackFactoryCaptureContent.Scalar -> 0L
                is CallbackFactoryCaptureContent.Callable ->
                    content.values
                        .fold(0L) { size, value -> size.addBytes(value.retainedBytes) }
                        .addBytes(
                            content.invocations.fold(0L) { size, invocation -> size.addBytes(invocation.retainedBytes) }
                        )
            }
        )

    companion object {
        fun fromCompiler(
            binding: CallbackArgumentBinding,
            selection: CallbackFactoryCaptureSelection,
            content: CallbackFactoryCaptureContent,
        ): Refinement<CallbackFactoryCapture, CallbackFactoryReturnFailure> {
            val expected =
                when (val selected = selection.site(binding)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return selected
                }
            if (expected.basis != binding.invocation.basis)
                return Refinement.Rejected(CallbackFactoryReturnFailure.BASIS_MISMATCH)
            val frozen =
                when (val selected = content.freezeAt(expected)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return selected
                }
            return Refinement.Refined(CallbackFactoryCapture(binding, selection, frozen))
        }
    }
}

/** A factory boundary is its own proof: a returned source is never moved into the caller's lexical owner. */
@ConsistentCopyVisibility
data class CallbackFactoryReturn
private constructor(
    val invocation: ValueInvocation,
    val returnedValue: ImmutableCallbackValue,
    val captures: List<CallbackFactoryCapture>,
    val bodyCalls: CallbackFactoryBodyCalls,
) {
    val depth: Int =
        1 +
            maxOf(
                returnedValue.factoryDepth(),
                captures.maxOfOrNull { capture ->
                    when (val content = capture.content) {
                        CallbackFactoryCaptureContent.Scalar -> 0
                        is CallbackFactoryCaptureContent.Callable -> content.values.maxOf { it.factoryDepth() }
                    }
                } ?: 0,
            )
    val retainedBytes: Long =
        returnedValue.retainedBytes.addBytes(
            captures.fold(bodyCalls.retainedBytes.addBytes(4096L)) { size, capture ->
                size.addBytes(capture.retainedBytes)
            }
        )

    companion object {
        fun fromCompiler(
            invocation: ValueInvocation,
            returnedValue: ImmutableCallbackValue,
            captures: List<CallbackFactoryCapture>,
            bodyCalls: CallbackFactoryBodyCalls,
        ): Refinement<CallbackFactoryReturn, CallbackFactoryReturnFailure> {
            when (val admitted = returnedValue.admitProducer(invocation)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            val positions =
                (invocation.callable.signature as CanonicalCompilerCallableSignature).valueParameters.indices.toList()
            if (captures.map { it.binding.position.value }.sorted() != positions)
                return Refinement.Rejected(CallbackFactoryReturnFailure.CAPTURE_INVENTORY_MISMATCH)
            for (capture in captures) when (val admitted = capture.admitFactory(invocation, returnedValue)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            when (val admitted = bodyCalls.admitFactory(invocation, returnedValue, captures)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            val proof =
                CallbackFactoryReturn(
                    invocation,
                    returnedValue,
                    Collections.unmodifiableList(captures.sortedBy { it.binding.position.value }),
                    bodyCalls,
                )
            if (proof.depth > MAX_FACTORY_RETURN_DEPTH)
                return Refinement.Rejected(CallbackFactoryReturnFailure.DEPTH_LIMIT_EXCEEDED)
            return Refinement.Refined(proof)
        }
    }
}

private fun ImmutableCallbackValue.factoryDepth(): Int =
    when (val value = origin) {
        is ImmutableCallbackValueOrigin.Returned -> value.factory.depth
        is ImmutableCallbackValueOrigin.Anonymous,
        is ImmutableCallbackValueOrigin.Named -> 0
    }

private fun CallbackFactoryCaptureSelection.site(
    binding: CallbackArgumentBinding
): Refinement<ValueSite, CallbackFactoryReturnFailure> =
    when (this) {
        is CallbackFactoryCaptureSelection.Explicit -> explicitSite(binding)
        is CallbackFactoryCaptureSelection.Default -> defaultSite(binding)
    }

private fun CallbackFactoryCaptureSelection.Explicit.explicitSite(
    binding: CallbackArgumentBinding
): Refinement<ValueSite, CallbackFactoryReturnFailure> {
    val role =
        value.role as? ValueRole.Argument
            ?: return Refinement.Rejected(CallbackFactoryReturnFailure.CAPTURE_VALUE_MISMATCH)
    if (
        role.call != binding.invocation ||
            role.position != binding.position ||
            value.enclosing.valueIdentity != binding.invocation.enclosing.valueIdentity
    )
        return Refinement.Rejected(CallbackFactoryReturnFailure.CAPTURE_BINDING_MISMATCH)
    return Refinement.Refined(value)
}

private fun CallbackFactoryCaptureSelection.Default.defaultSite(
    binding: CallbackArgumentBinding
): Refinement<ValueSite, CallbackFactoryReturnFailure> {
    val formal = declaration.parameter
    if (
        formal.callable.valueIdentity != binding.invocation.callable.valueIdentity ||
            formal.position != binding.position ||
            formal.parameter != binding.parameter
    )
        return Refinement.Rejected(CallbackFactoryReturnFailure.CAPTURE_BINDING_MISMATCH)
    return when (
        val site = ValueSite.fromCompiler(formal.callable, declaration.defaultValue.range, ValueRole.ExpressionResult)
    ) {
        is Refinement.Refined -> site
        is Refinement.Rejected -> Refinement.Rejected(CallbackFactoryReturnFailure.CAPTURE_VALUE_MISMATCH)
    }
}

private fun CallbackFactoryCaptureContent.freezeAt(
    expected: ValueSite
): Refinement<CallbackFactoryCaptureContent, CallbackFactoryReturnFailure> =
    when (this) {
        CallbackFactoryCaptureContent.Scalar -> Refinement.Refined(this)
        is CallbackFactoryCaptureContent.Callable -> freezeAt(expected)
    }

private fun CallbackFactoryCaptureContent.Callable.freezeAt(
    expected: ValueSite
): Refinement<CallbackFactoryCaptureContent, CallbackFactoryReturnFailure> {
    if (invocations.distinct().size != invocations.size || values.isEmpty() || values.distinct().size != values.size)
        return Refinement.Rejected(CallbackFactoryReturnFailure.CAPTURE_VALUE_MISMATCH)
    if (values.any { it.destination != expected })
        return Refinement.Rejected(CallbackFactoryReturnFailure.CAPTURE_VALUE_MISMATCH)
    return Refinement.Refined(
        CallbackFactoryCaptureContent.Callable(
            Collections.unmodifiableList(values.toList()),
            Collections.unmodifiableList(invocations.toList()),
        )
    )
}

private fun ImmutableCallbackValue.admitProducer(
    invocation: ValueInvocation
): Refinement<Unit, CallbackFactoryReturnFailure> {
    if (source.basis != invocation.basis) return Refinement.Rejected(CallbackFactoryReturnFailure.BASIS_MISMATCH)
    if (
        destination.enclosing.valueIdentity != invocation.callable.valueIdentity ||
            source.enclosing.valueIdentity != invocation.callable.valueIdentity
    )
        return Refinement.Rejected(CallbackFactoryReturnFailure.PRODUCER_MISMATCH)
    if (destination.role != ValueRole.ExpressionResult && destination.role != ValueRole.LocalRead)
        return Refinement.Rejected(CallbackFactoryReturnFailure.PRODUCER_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun CallbackFactoryCapture.admitFactory(
    invocation: ValueInvocation,
    returnedValue: ImmutableCallbackValue,
): Refinement<Unit, CallbackFactoryReturnFailure> {
    val callable = content as? CallbackFactoryCaptureContent.Callable
    val anonymous = (returnedValue.origin as? ImmutableCallbackValueOrigin.Anonymous)?.body
    if (callable != null && callable.invocations.any { it.owner != anonymous })
        return Refinement.Rejected(CallbackFactoryReturnFailure.CAPTURE_VALUE_MISMATCH)
    if (binding.invocation != invocation)
        return Refinement.Rejected(CallbackFactoryReturnFailure.CAPTURE_BINDING_MISMATCH)
    val owner =
        binding.invocationOwner as? RelationCallableBody.Named
            ?: return Refinement.Rejected(CallbackFactoryReturnFailure.UNPROVEN_CALLER)
    if (
        owner.compilerIdentity != invocation.enclosing.compilerIdentity ||
            owner.file != invocation.enclosing.file ||
            owner.range != invocation.enclosing.range
    )
        return Refinement.Rejected(CallbackFactoryReturnFailure.UNPROVEN_CALLER)
    return Refinement.Refined(Unit)
}

private const val MAX_FACTORY_RETURN_DEPTH = 64
