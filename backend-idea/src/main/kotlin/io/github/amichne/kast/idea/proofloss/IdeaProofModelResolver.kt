package io.github.amichne.kast.idea.proofloss

import io.github.amichne.kast.shared.proofloss.model.*
import org.jetbrains.kotlin.psi.KtNamedFunction

internal data class IdeaMaterializerSpec(val declaration: KtNamedFunction, val total: Boolean)
internal data class IdeaPredicateSpec(val id: PredicateId, val declaration: KtNamedFunction, val subjectIndex: Int, val materializers: List<IdeaMaterializerSpec>)
internal data class IdeaBoundarySpec(val id: BoundaryId, val declaration: KtNamedFunction, val obligations: List<Pair<Int, PredicateId>>)
internal data class IdeaProofModelSpec(val predicates: List<IdeaPredicateSpec>, val boundaries: List<IdeaBoundarySpec>)

internal fun resolveIdeaProofModel(spec: IdeaProofModelSpec): ProofModel? {
    fun index(raw: Int) = (ArgumentIndex.parse(raw) as? ArgumentIndexParseResult.Valid)?.value
    val predicates = spec.predicates.map { source ->
        PredicateDescriptor(
            source.id,
            source.declaration.toProofCallableKey() ?: return null,
            index(source.subjectIndex) ?: return null,
            source.materializers.mapTo(mutableSetOf()) {
                val key = it.declaration.toProofCallableKey() ?: return null
                if (it.total) MaterializerDescriptor.Total(key) else MaterializerDescriptor.NullableWithExit(key)
            },
        )
    }
    val boundaries = spec.boundaries.map { source ->
        BoundaryDescriptor(source.id, source.declaration.toProofCallableKey() ?: return null,
            source.obligations.map { (argument, predicate) -> ProofObligation(index(argument) ?: return null, predicate) })
    }
    return (ProofModel.build(predicates, boundaries) as? ProofModelBuildResult.Valid)?.model
}
