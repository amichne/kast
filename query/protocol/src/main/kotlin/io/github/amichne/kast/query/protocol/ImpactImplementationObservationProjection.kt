package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationExcludedTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationExclusionDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationOmissionDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOwnershipDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceProvenanceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRootDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryScopeExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryScopeMembershipAuthorityDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionSampleRetentionDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationScopeExclusion
import io.github.amichne.kast.relation.contract.ValueSourceOwnership
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance

internal fun RelationOmissionEvidence.impactImplementationOmission():
    ImpactProjected<ImpactImplementationOmissionDocument> =
    measurement
        .impactImplementationMeasurement()
        .impactZip(samples.locations.impactEach { it.impactImplementationOccurrence() })
        .impactMap { (measurement, samples) ->
            ImpactImplementationOmissionDocument(
                RelationProviderDocument.valueOf(provider.name),
                RelationLimitationDocument.valueOf(reason.name),
                measurement,
                samples,
                RelationOmissionSampleRetentionDocument.valueOf(this.samples.retention.name),
            )
        }

private fun RelationOmissionMeasurement.impactImplementationMeasurement():
    ImpactProjected<ImpactImplementationOmissionMeasurementDocument> =
    when (this) {
        RelationOmissionMeasurement.UnmeasuredOnPage ->
            Refinement.Refined(ImpactImplementationOmissionMeasurementDocument.UnmeasuredOnPage)
        is RelationOmissionMeasurement.ObservedOnPage ->
            items.value.implementationCount().impactMap(ImpactImplementationOmissionMeasurementDocument::ObservedOnPage)
    }

internal fun RelationScopeExclusion.impactImplementationExclusion():
    ImpactProjected<ImpactImplementationExclusionDocument> {
    val domain =
        relationDomainDocument(effectiveScope, effectiveConstraints)
            ?: return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    return subject.value
        .impactText()
        .impactZip(basis.identity.impactDocument())
        .impactZip(
            QueryRelationDomainFingerprint.parse(effectiveDomain.value)
                .impactFailure(ImpactPathProjectionFailure::DomainFingerprint)
        )
        .impactZip(occurrence.impactImplementationOccurrence())
        .impactZip(target.impactExcludedTarget(basis.identity))
        .impactMap { (exclusion, target) ->
            ImpactImplementationExclusionDocument(
                exclusion.first.first.first,
                exclusion.first.first.second,
                requestedDomain.requestedDomainDocument(),
                domain,
                exclusion.first.second,
                exclusion.second,
                target,
                QueryScopeExclusionReasonDocument.valueOf(reason.name),
                QueryScopeMembershipAuthorityDocument.valueOf(membershipAuthority.name),
            )
        }
}

private fun CompilerGroundedSymbolEvidence.impactExcludedTarget(
    basis: SemanticReadIdentity
): ImpactProjected<ImpactImplementationExcludedTargetDocument> =
    basis
        .impactDocument()
        .impactZip(file.stableValue.impactText())
        .impactZip(range.impactDocument())
        .impactZip(compilerIdentity.value.impactText())
        .impactZip(name.value.impactText())
        .impactMap { (target, name) ->
            ImpactImplementationExcludedTargetDocument(
                ImpactDeclarationReferenceDocument(
                    target.first.first.first,
                    target.first.first.second,
                    target.first.second,
                    target.second,
                ),
                name,
                kind.protocolKind(),
            )
        }

internal fun ValueSourceOwnership.impactDocument(): ImpactProjected<ImpactSourceOwnershipDocument> =
    declaration.impactDocument(basis).impactZip(root.impactDocument()).impactMap { (declaration, root) ->
        ImpactSourceOwnershipDocument(declaration, root)
    }

private fun ModelOwnedSourceRoot.impactDocument(): ImpactProjected<ImpactSourceRootDocument> {
    val kind =
        when (sourceKind) {
            WorkspaceSourceRootKind.PRODUCTION -> ImpactSourceKindDocument.PRODUCTION
            WorkspaceSourceRootKind.TEST -> ImpactSourceKindDocument.TEST
            WorkspaceSourceRootKind.UNKNOWN ->
                return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
        }
    val provenance =
        when (provenance) {
            WorkspaceSourceRootProvenance.AUTHORED -> ImpactSourceProvenanceDocument.AUTHORED
            WorkspaceSourceRootProvenance.GENERATED -> ImpactSourceProvenanceDocument.GENERATED
            WorkspaceSourceRootProvenance.UNKNOWN ->
                return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
        }
    return module.value
        .impactText()
        .impactZip(project.buildRoot.value.impactText())
        .impactZip(project.projectPath.value.impactText())
        .impactZip(sourceSet.value.impactText())
        .impactZip(sourceRoot.value.impactText())
        .impactMap { (owner, sourceRoot) ->
            ImpactSourceRootDocument(
                owner.first.first.first,
                owner.first.first.second,
                owner.first.second,
                owner.second,
                sourceRoot,
                kind,
                provenance,
            )
        }
}
