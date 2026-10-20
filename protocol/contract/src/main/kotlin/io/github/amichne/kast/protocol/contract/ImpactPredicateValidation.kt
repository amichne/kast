package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun BoundedProtocolList<ImpactValuePredicateDocument>.admitsPredicates(
    basis: ImpactSemanticBasisDocument
): Boolean =
    values.size <= MAX_IMPACT_PREDICATES &&
        values.distinct().size == values.size &&
        values.all {
            it.subject.site().isOnBasis(basis) &&
                when (it) {
                    is ImpactValuePredicateDocument.NonNull -> true
                    is ImpactValuePredicateDocument.Subtype ->
                        it.resolvedType.basis == basis && it.resolvedType.declarationFailure() is Refinement.Refined
                }
        }

internal fun ImpactPredicateSubjectDocument.site(): ImpactValueSiteReferenceDocument =
    ImpactValueSiteReferenceDocument(enclosing, range, role, executionOwner, valuePath, callStack)

private const val MAX_IMPACT_PREDICATES = 16

internal fun BoundedProtocolList<ImpactValuePredicateDocument>.hasSamePredicateIdentity(
    other: BoundedProtocolList<ImpactValuePredicateDocument>
): Boolean = values.size == other.values.size && values.all { first -> other.values.any { first.samePredicate(it) } }

private fun ImpactValuePredicateDocument.samePredicate(other: ImpactValuePredicateDocument): Boolean =
    subject.site().hasSameValueIdentity(other.subject.site()) &&
        when (this) {
            is ImpactValuePredicateDocument.NonNull -> other is ImpactValuePredicateDocument.NonNull
            is ImpactValuePredicateDocument.Subtype ->
                other is ImpactValuePredicateDocument.Subtype && resolvedType == other.resolvedType
        }
