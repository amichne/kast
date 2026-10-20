package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactPredicateSubjectDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePredicateDocument
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates

internal fun ValuePredicates.impactDocument(): ImpactProjected<BoundedProtocolList<ImpactValuePredicateDocument>> =
    entries.impactEach { predicate ->
        predicate.subject.occurrence.impactDocument().impactThen { site ->
            val subject =
                ImpactPredicateSubjectDocument(
                    site.enclosing,
                    site.range,
                    site.role,
                    site.executionOwner,
                    site.valuePath,
                    site.callStack,
                )
            when (predicate) {
                is ValuePredicate.Subtype ->
                    predicate.resolvedType.impactDeclaration().impactMap { type ->
                        ImpactValuePredicateDocument.Subtype(subject, type)
                    }
                is ValuePredicate.NonNull ->
                    io.github.amichne.kast.kernel.Refinement.Refined(ImpactValuePredicateDocument.NonNull(subject))
            }
        }
    }
