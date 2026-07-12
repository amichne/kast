package io.github.amichne.kast.shared.proofloss.model

sealed interface MaterializerDescriptor {
    val callable: CallableKey

    data class Total(override val callable: CallableKey) : MaterializerDescriptor
    data class NullableWithExit(override val callable: CallableKey) : MaterializerDescriptor
}

data class PredicateDescriptor(
    val id: PredicateId,
    val callable: CallableKey,
    val subjectArgumentIndex: ArgumentIndex,
    val materializers: Set<MaterializerDescriptor> = emptySet(),
)

data class Obligation(
    val argumentIndex: ArgumentIndex,
    val predicate: PredicateId,
)

data class BoundaryDescriptor(
    val id: BoundaryId,
    val callable: CallableKey,
    val obligations: List<Obligation>,
)
