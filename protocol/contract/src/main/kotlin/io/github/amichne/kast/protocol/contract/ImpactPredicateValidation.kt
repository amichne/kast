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
