package io.github.amichne.kast.shared.proofloss.analysis

import io.github.amichne.kast.shared.proofloss.ir.FunctionId
import io.github.amichne.kast.shared.proofloss.ir.SourceSpan
import io.github.amichne.kast.shared.proofloss.ir.TrackedValueId
import io.github.amichne.kast.shared.proofloss.model.ArgumentIndex
import io.github.amichne.kast.shared.proofloss.model.BoundaryId
import io.github.amichne.kast.shared.proofloss.model.CallableKey
import io.github.amichne.kast.shared.proofloss.model.PredicateId

data class ProofLossFinding(
    val functionId: FunctionId,
    val predicate: PredicateId,
    val predicateCallable: CallableKey,
    val boundary: BoundaryId,
    val boundaryCallable: CallableKey,
    val argumentIndex: ArgumentIndex,
    val subject: TrackedValueId,
    val boundaryArgument: TrackedValueId,
    val proof: ProofEstablishment,
    val boundaryCall: SourceSpan,
    val valuePath: List<TrackedValueId>,
    val suggestedMaterializers: Set<CallableKey>,
)
