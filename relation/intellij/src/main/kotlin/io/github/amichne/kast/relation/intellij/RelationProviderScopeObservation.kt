package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Observes the existing decision before candidate capacity and confirmation; no source identity is exported. */
internal fun RelationProviderScopeAdmission.observed(
    observation: IntellijReadObservation
): RelationProviderScopeAdmission {
    observation.count(
        when (this) {
            RelationProviderScopeAdmission.ADMITTED -> IntellijReadCounter.RELATION_PROVIDER_SCOPE_ADMITTED
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED ->
                IntellijReadCounter.RELATION_PROVIDER_SCOPE_SOURCE_EXCLUDED
            RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED ->
                IntellijReadCounter.RELATION_PROVIDER_SCOPE_LIBRARY_EXCLUDED
            RelationProviderScopeAdmission.UNAVAILABLE -> IntellijReadCounter.RELATION_PROVIDER_SCOPE_UNAVAILABLE
        }
    )
    return this
}
