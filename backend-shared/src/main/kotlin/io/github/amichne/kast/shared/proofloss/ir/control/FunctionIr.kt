package io.github.amichne.kast.shared.proofloss.ir

data class FunctionIr(
    val id: FunctionId,
    val parameters: Set<TrackedValueId>,
    val body: Block,
)
