package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path

/** Keeps imported ownership separate from the smaller admitted source domain. */
internal class RelationSourceDomainMembership(
    private val paths: RelationPathPolicy,
    ownershipRoots: List<Path>,
    private val directoryAdmission: (Path) -> Boolean,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    private val ownership = RelationSourceRootIndex(ownershipRoots)

    fun classifyExcluded(path: IntellijRelationNativePath): RelationProviderScopeAdmission {
        val absolute =
            when (path) {
                is IntellijRelationNativePath.Absolute -> path.value
                IntellijRelationNativePath.Relative,
                IntellijRelationNativePath.Unavailable -> return RelationProviderScopeAdmission.UNAVAILABLE
            }
        when (val owner = ownership.owner(absolute, observation)) {
            RelationPathOwner.Unowned -> return RelationProviderScopeAdmission.UNAVAILABLE
            is RelationPathOwner.Owned ->
                when (owner.cardinality) {
                    RelationRootCardinality.UNIQUE -> Unit
                    RelationRootCardinality.AMBIGUOUS -> return RelationProviderScopeAdmission.UNAVAILABLE
                }
        }
        return if (!paths.contains(absolute, observation) || !directoryAdmission(absolute)) {
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED
        } else {
            // An eligible imported owner rejected by the native scope does not prove a domain exit.
            RelationProviderScopeAdmission.UNAVAILABLE
        }
    }
}
