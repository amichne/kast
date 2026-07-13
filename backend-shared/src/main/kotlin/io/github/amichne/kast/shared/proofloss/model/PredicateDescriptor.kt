package io.github.amichne.kast.shared.proofloss.model

data class PredicateDescriptor(
    val id: PredicateId,
    val callable: CallableKey,
    val subjectArgumentIndex: ArgumentIndex,
    val materializers: Set<MaterializerDescriptor> = emptySet(),
)
