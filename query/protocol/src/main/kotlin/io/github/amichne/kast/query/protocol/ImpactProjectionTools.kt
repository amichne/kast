package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentifierDocument
import io.github.amichne.kast.protocol.contract.ImpactModelVersionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText

internal typealias ImpactProjected<T> = Refinement<T, ImpactPathProjectionFailure>

internal inline fun <T, F> Refinement<T, F>.impactFailure(
    transform: (F) -> ImpactPathProjectionFailure
): ImpactProjected<T> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected -> Refinement.Rejected(transform(failure))
    }

internal inline fun <T, U> ImpactProjected<T>.impactMap(transform: (T) -> U): ImpactProjected<U> =
    when (this) {
        is Refinement.Refined -> Refinement.Refined(transform(value))
        is Refinement.Rejected -> this
    }

internal inline fun <T, U> ImpactProjected<T>.impactThen(transform: (T) -> ImpactProjected<U>): ImpactProjected<U> =
    when (this) {
        is Refinement.Refined -> transform(value)
        is Refinement.Rejected -> this
    }

internal fun <T, U> ImpactProjected<T>.impactZip(other: ImpactProjected<U>): ImpactProjected<Pair<T, U>> =
    impactThen { first ->
        other.impactMap { first to it }
    }

internal fun <T> List<T>.impactBounded(): ImpactProjected<BoundedProtocolList<T>> =
    BoundedProtocolList.create(this).impactFailure(ImpactPathProjectionFailure::Collection)

internal fun <T, U> Iterable<T>.impactEach(
    transform: (T) -> ImpactProjected<U>
): ImpactProjected<BoundedProtocolList<U>> {
    val values = mutableListOf<U>()
    for (entry in this) when (val result = transform(entry)) {
        is Refinement.Refined -> values += result.value
        is Refinement.Rejected -> return result
    }
    return values.impactBounded()
}

internal fun String.impactText() = ProtocolText.parse(this).impactFailure(ImpactPathProjectionFailure::Text)

internal fun String.impactId() =
    ImpactModelIdentifierDocument.parse(this).impactFailure(ImpactPathProjectionFailure::Model)

internal fun Int.impactOffset() = ProtocolOffset.parse(this).impactFailure(ImpactPathProjectionFailure::Offset)

internal fun Int.impactVersion() =
    ImpactModelVersionDocument.parse(this).impactFailure(ImpactPathProjectionFailure::Model)

internal fun Long.impactRevision() =
    ImpactEvidenceRevisionDocument.parse(this).impactFailure(ImpactPathProjectionFailure::Model)
