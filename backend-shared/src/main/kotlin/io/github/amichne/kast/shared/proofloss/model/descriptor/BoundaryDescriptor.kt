package io.github.amichne.kast.shared.proofloss.model

data class BoundaryDescriptor(
    val id: BoundaryId,
    val callable: CallableKey,
    val obligations: List<Obligation>,
)
