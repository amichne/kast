package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Existing SDK controls hold time constant; production still owns the original request's admission rule. */
internal fun nativeScopeTestAdmission(
    request: RelationRequest,
    observation: IntellijReadObservation,
): NativeRelationScopeAdmission {
    val collector = IntellijRelationCollector(request, clockNanoseconds = { 0L }, observation = observation)
    return NativeRelationScopeAdmission(observation) { collector.admitProviderCallback(ProgressManager::checkCanceled) }
}
