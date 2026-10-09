package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackSummaryCachePort
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection

/** Optional dependency work is reachable only after subject and both scopes have been admitted. */
internal inline fun <Admission> admitThenPrepareRelation(
    admit: () -> Refinement<Admission, RelationCompilerRejection>,
    prepare: () -> CallbackSummaryCachePort,
    evaluate: (Admission, CallbackSummaryCachePort) -> RelationCompilation,
): RelationCompilation =
    when (val admission = admit()) {
        is Refinement.Rejected -> RelationCompilation.Rejected(admission.failure)
        is Refinement.Refined -> evaluate(admission.value, prepare())
    }
