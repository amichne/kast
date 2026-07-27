package io.github.amichne.kast.shared.proofloss.model

import io.github.amichne.kast.api.contract.NonEmptyList

class ProofModel private constructor(
    val predicates: List<PredicateDescriptor>,
    val boundaries: List<BoundaryDescriptor>,
) {
    private val predicateById = predicates.associateBy { it.id }
    private val boundaryById = boundaries.associateBy { it.id }
    private val predicateByCallable = predicates.associateBy { it.callable }
    private val boundaryByCallable = boundaries.associateBy { it.callable }
    private val predicateByMaterializer = predicates.flatMap { p -> p.materializers.map { it.callable to p } }.toMap()

    fun predicate(id: PredicateId): PredicateDescriptor = requireNotNull(predicateById[id])
    fun boundary(id: BoundaryId): BoundaryDescriptor = requireNotNull(boundaryById[id])
    fun predicateForCallable(key: CallableKey): PredicateDescriptor? = predicateByCallable[key]
    fun boundaryForCallable(key: CallableKey): BoundaryDescriptor? = boundaryByCallable[key]
    fun predicateForMaterializer(key: CallableKey): PredicateDescriptor? = predicateByMaterializer[key]
    fun materializer(key: CallableKey): MaterializerDescriptor? =
        predicates.asSequence().flatMap { it.materializers.asSequence() }.firstOrNull { it.callable == key }

    companion object {
        fun build(
            predicates: List<PredicateDescriptor>,
            boundaries: List<BoundaryDescriptor>,
        ): ModelBuildResult =
            validatedModel(
                predicates.map { it.copy(materializers = it.materializers.toSet()) },
                boundaries.map { it.copy(obligations = it.obligations.toList()) },
            )

        private fun validatedModel(
            predicates: List<PredicateDescriptor>,
            boundaries: List<BoundaryDescriptor>,
        ): ModelBuildResult = modelViolations(predicates, boundaries).let { violations ->
            if (violations.isEmpty()) ModelBuildResult.Valid(ProofModel(predicates, boundaries))
            else ModelBuildResult.Invalid(NonEmptyList(violations))
        }

        private fun modelViolations(
            predicates: List<PredicateDescriptor>,
            boundaries: List<BoundaryDescriptor>,
        ): List<ModelViolation> =
            duplicateDeclarationViolations(predicates, boundaries) +
                obligationViolations(predicates, boundaries) +
                materializerViolations(predicates) +
                callableRoleViolations(predicates, boundaries)

        private fun duplicateDeclarationViolations(
            predicates: List<PredicateDescriptor>,
            boundaries: List<BoundaryDescriptor>,
        ): List<ModelViolation> =
            duplicates(predicates) { it.id }.map(ModelViolation::DuplicatePredicateId) +
                duplicates(boundaries) { it.id }.map(ModelViolation::DuplicateBoundaryId) +
                duplicates(predicates) { it.callable }.map(ModelViolation::DuplicatePredicateCallable) +
                duplicates(boundaries) { it.callable }.map(ModelViolation::DuplicateBoundaryCallable)

        private fun obligationViolations(
            predicates: List<PredicateDescriptor>,
            boundaries: List<BoundaryDescriptor>,
        ): List<ModelViolation> = predicates.map { it.id }.toSet().let { knownPredicates ->
            boundaries.flatMap { boundary ->
                duplicates(boundary.obligations) { it }
                    .map { ModelViolation.DuplicateObligation(boundary.id, it) } +
                    boundary.obligations
                        .filterNot { it.predicate in knownPredicates }
                        .map { ModelViolation.UnknownPredicate(boundary.id, it.predicate) }
            }
        }

        private fun materializerViolations(
            predicates: List<PredicateDescriptor>,
        ): List<ModelViolation> = predicates
            .flatMap { predicate -> predicate.materializers.map { it.callable to predicate.id } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.distinct().size > 1 }
            .keys
            .map(ModelViolation::ConflictingMaterializerPredicate)

        private fun callableRoleViolations(
            predicates: List<PredicateDescriptor>,
            boundaries: List<BoundaryDescriptor>,
        ): List<ModelViolation> = (
            predicates.flatMap { predicate ->
                listOf(predicate.callable to CallableRole.PREDICATE) +
                    predicate.materializers.map { it.callable to CallableRole.MATERIALIZER }
            } + boundaries.map { it.callable to CallableRole.BOUNDARY }
            )
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, roles) -> roles.toSet() }
            .filterValues { it.size > 1 }
            .map { (callable, roles) -> ModelViolation.ConflictingCallableRoles(callable, roles) }

        private fun <T, K> duplicates(
            values: List<T>,
            key: (T) -> K,
        ): Set<K> =
            values.groupingBy(key).eachCount().filterValues { it > 1 }.keys
    }
}
