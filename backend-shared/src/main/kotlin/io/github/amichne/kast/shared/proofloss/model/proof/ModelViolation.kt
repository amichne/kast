package io.github.amichne.kast.shared.proofloss.model

sealed interface ModelViolation {
    data class DuplicatePredicateId(val id: PredicateId) : ModelViolation
    data class DuplicateBoundaryId(val id: BoundaryId) : ModelViolation
    data class DuplicatePredicateCallable(val callable: CallableKey) : ModelViolation
    data class DuplicateBoundaryCallable(val callable: CallableKey) : ModelViolation
    data class DuplicateObligation(
        val boundary: BoundaryId,
        val obligation: Obligation,
    ) : ModelViolation

    data class UnknownPredicate(
        val boundary: BoundaryId,
        val predicate: PredicateId,
    ) : ModelViolation

    data class ConflictingCallableRoles(
        val callable: CallableKey,
        val roles: Set<CallableRole>,
    ) : ModelViolation

    data class ConflictingMaterializerPredicate(val callable: CallableKey) : ModelViolation
}
