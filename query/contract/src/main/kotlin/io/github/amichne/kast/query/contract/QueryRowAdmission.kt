package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement

private sealed interface AdmittedRowKind {
    data object Symbol : AdmittedRowKind

    data class Binding(val mode: QueryJoinMode.Inner) : AdmittedRowKind
}

/** Each step consumes the row kind proven by its predecessor, before any semantic effects. */
internal fun admitQueryRows(
    source: QuerySourceSyntax,
    steps: List<QueryStepSyntax>,
    output: QueryOutputSyntax,
): Refinement<Unit, QueryPlanAdmissionFailure> {
    if (steps.any(::hasIncompleteRight)) return Refinement.Rejected(QueryPlanAdmissionFailure.IncompleteRightInput)
    var rowKind = source.rowKind()
    for (step in steps) {
        rowKind =
            when (val admitted = rowKind.admit(step)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
    }
    val bindings = rowKind is AdmittedRowKind.Binding
    return if (bindings == (output is QueryOutputSyntax.BindingRows)) Refinement.Refined(Unit)
    else Refinement.Rejected(QueryPlanAdmissionFailure.OutputTypeMismatch)
}

private fun QuerySourceSyntax.rowKind(): AdmittedRowKind =
    when (this) {
        is QuerySourceSyntax.Symbols,
        is QuerySourceSyntax.Location,
        is QuerySourceSyntax.ExactReferences -> AdmittedRowKind.Symbol
        is QuerySourceSyntax.Retained ->
            when (val retained = result) {
                is QueryRetainedResult.Symbols -> AdmittedRowKind.Symbol
                is QueryRetainedResult.Bindings -> AdmittedRowKind.Binding(retained.mode)
            }
    }

private fun AdmittedRowKind.admit(step: QueryStepSyntax): Refinement<AdmittedRowKind, QueryPlanAdmissionFailure> =
    when (this) {
        AdmittedRowKind.Symbol ->
            when (step) {
                is QueryStepSyntax.ProjectBinding -> Refinement.Rejected(QueryPlanAdmissionFailure.OutputTypeMismatch)
                is QueryStepSyntax.Join ->
                    Refinement.Refined(
                        (step.mode as? QueryJoinMode.Inner)?.let(AdmittedRowKind::Binding) ?: AdmittedRowKind.Symbol
                    )
                else -> Refinement.Refined(AdmittedRowKind.Symbol)
            }
        is AdmittedRowKind.Binding -> project(step)
    }

private fun AdmittedRowKind.Binding.project(
    step: QueryStepSyntax
): Refinement<AdmittedRowKind, QueryPlanAdmissionFailure> =
    when {
        step !is QueryStepSyntax.ProjectBinding -> Refinement.Rejected(QueryPlanAdmissionFailure.OutputTypeMismatch)
        step.name != mode.leftName && step.name != mode.rightName ->
            Refinement.Rejected(QueryPlanAdmissionFailure.UnknownBindingName(step.name))
        else -> Refinement.Refined(AdmittedRowKind.Symbol)
    }

private fun hasIncompleteRight(step: QueryStepSyntax): Boolean =
    when (step) {
        is QueryStepSyntax.Difference -> QueryCompleteMembership.from(step.right) is Refinement.Rejected
        is QueryStepSyntax.Join ->
            step.mode is QueryJoinMode.Anti && QueryCompleteMembership.from(step.right) is Refinement.Rejected
        else -> false
    }
