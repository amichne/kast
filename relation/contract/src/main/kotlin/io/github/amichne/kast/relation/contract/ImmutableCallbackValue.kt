package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import java.util.Collections

/** Compiler-resolved source identity before transport to a particular supplier occurrence. */
sealed interface ImmutableCallbackValueOrigin {
    val file: SymbolDiscoveryFileIdentity
    val range: ExactDeclarationTextRange

    data class Anonymous(val body: RelationCallableBody.Anonymous) : ImmutableCallbackValueOrigin {
        override val file = body.file
        override val range = body.range
    }

    data class Returned(val factory: CallbackFactoryReturn) : ImmutableCallbackValueOrigin {
        override val file = factory.invocation.enclosing.file
        override val range = factory.invocation.range
    }

    data class Named(
        val occurrence: RelationOccurrence,
        val target: RelationEndpoint.Resolved,
        val receivers: CallbackReferenceReceivers,
    ) : ImmutableCallbackValueOrigin {
        override val file = occurrence.file
        override val range = occurrence.range
    }
}

enum class ImmutableCallbackValueFailure {
    ORIGIN_MISMATCH,
    TARGET_NOT_CALLABLE,
    BASIS_MISMATCH,
    RECEIVER_OUTSIDE_REFERENCE,
    DISCONNECTED_TRANSPORT,
    UNSUPPORTED_TRANSPORT,
}

/** Exact immutable transport. Neither a matching name nor containment proves value equality. */
@ConsistentCopyVisibility
data class ImmutableCallbackValue
private constructor(
    val origin: ImmutableCallbackValueOrigin,
    val source: ValueSite,
    val destination: ValueSite,
    val transfers: List<ValueTransfer>,
) {
    val retainedBytes: Long =
        source.retainedBytes
            .addBytes(destination.retainedBytes)
            .addBytes(
                transfers.fold(4096L) { bytes, edge ->
                    bytes.addBytes(edge.source.retainedBytes).addBytes(edge.target.retainedBytes)
                }
            )
            .addBytes(
                when (origin) {
                    is ImmutableCallbackValueOrigin.Returned -> origin.factory.retainedBytes
                    is ImmutableCallbackValueOrigin.Anonymous ->
                        origin.body.signature.canonicalEncoding().value.length.toLong().multiplyBytes(2)
                    is ImmutableCallbackValueOrigin.Named -> origin.target.detachedTextUnits().multiplyBytes(2)
                }
            )

    companion object {
        /** Native K2 confirms each origin and local transfer; this factory retains their identity and order. */
        fun fromCompiler(
            origin: ImmutableCallbackValueOrigin,
            source: ValueSite,
            destination: ValueSite,
            transfers: List<ValueTransfer>,
        ): Refinement<ImmutableCallbackValue, ImmutableCallbackValueFailure> {
            if (
                source.enclosing.file != origin.file ||
                    source.range != origin.range ||
                    source.role != ValueRole.ExpressionResult
            )
                return Refinement.Rejected(ImmutableCallbackValueFailure.ORIGIN_MISMATCH)
            if (source.basis != destination.basis || transfers.any { it.source.basis != source.basis })
                return Refinement.Rejected(ImmutableCallbackValueFailure.BASIS_MISMATCH)
            when (val admitted = origin.admitSource(source)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            if (transfers.any { !it.admitsImmutableCallbackTransport() })
                return Refinement.Rejected(ImmutableCallbackValueFailure.UNSUPPORTED_TRANSPORT)
            if (!connectedCallbackTransport(source, destination, transfers))
                return Refinement.Rejected(ImmutableCallbackValueFailure.DISCONNECTED_TRANSPORT)
            return Refinement.Refined(
                ImmutableCallbackValue(origin, source, destination, Collections.unmodifiableList(transfers.toList()))
            )
        }
    }
}

private fun ValueTransfer.admitsImmutableCallbackTransport(): Boolean =
    source.enclosing.valueIdentity == target.enclosing.valueIdentity &&
        when (kind) {
            ValueTransferKind.LOCAL_BINDING,
            ValueTransferKind.LOCAL_READ,
            ValueTransferKind.ARGUMENT,
            ValueTransferKind.BRANCH_ALTERNATIVE,
            ValueTransferKind.WRAPPER_RETURN -> true
            ValueTransferKind.RETURN,
            ValueTransferKind.PROPERTY_ASSIGNMENT -> false
        }

/** Follows only retained return/capture proofs; equal targets or lexical containment never establish provenance. */
fun ImmutableCallbackValue.tracesSource(origin: ImmutableCallbackValueOrigin, source: ValueSite): Boolean {
    if (this.origin == origin && this.source == source) return true
    val factory = (this.origin as? ImmutableCallbackValueOrigin.Returned)?.factory ?: return false
    if (factory.returnedValue.tracesSource(origin, source)) return true
    return factory.captures.any { capture ->
        val content = capture.content as? CallbackFactoryCaptureContent.Callable ?: return@any false
        content.invocations.isNotEmpty() && content.values.any { it.tracesSource(origin, source) }
    }
}

private fun connectedCallbackTransport(
    source: ValueSite,
    destination: ValueSite,
    transfers: List<ValueTransfer>,
): Boolean =
    if (transfers.isEmpty()) source == destination
    else
        transfers.first().source == source &&
            transfers.last().target == destination &&
            transfers.zipWithNext().all { (left, right) -> left.target == right.source }

private fun ImmutableCallbackValueOrigin.admitSource(
    source: ValueSite
): Refinement<Unit, ImmutableCallbackValueFailure> =
    when (this) {
        is ImmutableCallbackValueOrigin.Anonymous -> Refinement.Refined(Unit)
        is ImmutableCallbackValueOrigin.Returned ->
            if (source == factory.invocation.resultSite()) Refinement.Refined(Unit)
            else Refinement.Rejected(ImmutableCallbackValueFailure.ORIGIN_MISMATCH)
        is ImmutableCallbackValueOrigin.Named -> admitNamedSource(source)
    }

private fun ImmutableCallbackValueOrigin.Named.admitNamedSource(
    source: ValueSite
): Refinement<Unit, ImmutableCallbackValueFailure> {
    if (target.signature !is CanonicalCompilerSignature.Function)
        return Refinement.Rejected(ImmutableCallbackValueFailure.TARGET_NOT_CALLABLE)
    if (target.lease.identity != source.basis) return Refinement.Rejected(ImmutableCallbackValueFailure.BASIS_MISMATCH)
    for (receiver in listOf(receivers.dispatch, receivers.extension)) {
        if (
            receiver is CallbackReferenceReceiver.Bound &&
                (receiver.occurrence.file != file || !range.containsValueRange(receiver.occurrence.range))
        )
            return Refinement.Rejected(ImmutableCallbackValueFailure.RECEIVER_OUTSIDE_REFERENCE)
    }
    return Refinement.Refined(Unit)
}
