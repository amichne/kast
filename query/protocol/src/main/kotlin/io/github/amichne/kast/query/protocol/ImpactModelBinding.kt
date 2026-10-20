package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactBoundaryKindDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactCallablePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentifierDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentityDocument
import io.github.amichne.kast.protocol.contract.ImpactModelValuePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.hasSameValueIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelCallableReference
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

internal fun ImpactModelIdentityDocument.domainIdentity():
    Refinement<ContractModelIdentity, ImpactModelAdmissionFailure> {
    val id =
        when (val value = id.domainIdentifier()) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    val provenance =
        when (val value = provenance.domainIdentifier()) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    val version =
        when (val value = ModelVersion.parse(version.value)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(ImpactModelAdmissionFailure.Version(value.failure))
        }
    return Refinement.Refined(ContractModelIdentity(id, version, provenance))
}

internal fun ImpactModelIdentifierDocument.domainIdentifier():
    Refinement<ModelIdentifier, ImpactModelAdmissionFailure> =
    when (val admitted = ModelIdentifier.parse(value)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(ImpactModelAdmissionFailure.Identifier(admitted.failure))
    }

internal fun ImpactCallablePositionDocument.bind(
    current: List<RevalidatedRelationEndpoint>
): Refinement<ExactModelCallablePosition, ImpactModelAdmissionFailure> {
    val exact = current.filter { declaration.matchesDeclaration(it.endpoint) }
    val candidates = exact.filter { declaration.basis.matchesBasis(it.endpoint.lease.identity) }
    if (candidates.isEmpty())
        return Refinement.Rejected(
            if (exact.isEmpty()) ImpactModelAdmissionFailure.MISSING_DECLARATION
            else ImpactModelAdmissionFailure.STALE_DECLARATION
        )
    // Repeated observations and scope-only variation do not change exact declaration identity.
    val selected = candidates.first()
    val position =
        when (val syntax = position) {
            ImpactModelValuePositionDocument.Result -> ModelValuePosition.Result
            is ImpactModelValuePositionDocument.Argument ->
                when (val parsed = ValueArgumentPosition.parse(syntax.index.value)) {
                    is Refinement.Refined -> ModelValuePosition.Argument(parsed.value)
                    is Refinement.Rejected ->
                        return Refinement.Rejected(ImpactModelAdmissionFailure.Position(parsed.failure))
                }
        }
    val endpoint = selected.endpoint
    return when (
        val admitted =
            ExactModelCallablePosition.admit(
                ModelCallableReference(
                    endpoint.lease.identity,
                    endpoint.compilerIdentity,
                    endpoint.file,
                    endpoint.range,
                    position,
                ),
                selected,
            )
    ) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(ImpactModelAdmissionFailure.Callable(admitted.failure))
    }
}

internal fun ImpactBoundaryPositionDocument.bind(
    current: List<BoundaryPosition>
): Refinement<BoundaryPosition, ImpactModelAdmissionFailure> {
    val selected = current.filter {
        site.matchesSite(it.site) &&
            kind.matchesKind(it.kind) &&
            contract.id.value == it.contract.id.value &&
            contract.version.value == it.contract.version.value &&
            slot.value == it.slot.value
    }
    return if (selected.isEmpty()) Refinement.Rejected(ImpactModelAdmissionFailure.MISSING_BOUNDARY_POSITION)
    else Refinement.Refined(selected.first())
}

internal fun ImpactDeclarationReferenceDocument.matchesDeclaration(current: RelationEndpoint): Boolean =
    compilerIdentity.value == current.compilerIdentity.value &&
        file.value == current.file.stableValue &&
        range.start.value == current.range.startInclusive &&
        range.end.value == current.range.endExclusive

internal fun ImpactSemanticBasisDocument.matchesBasis(current: SemanticReadIdentity): Boolean =
    when (this) {
        is ImpactSemanticBasisDocument.Published ->
            current is SemanticReadIdentity.Published &&
                root.value == current.workspaceRoot.value &&
                generation.value == current.lease.generation.value
        is ImpactSemanticBasisDocument.Live ->
            current is SemanticReadIdentity.Live &&
                root.value == current.workspaceRoot.value &&
                host.value == current.reference.host.value.toString() &&
                epoch.value == current.reference.epoch.value &&
                referenceVersion.value == current.reference.version &&
                contentView.name == current.reference.contentView.name
    }

internal fun ImpactValueSiteReferenceDocument.matchesSite(current: ValueSite): Boolean =
    when (val projected = current.impactDocument()) {
        is Refinement.Refined -> hasSameValueIdentity(projected.value)
        is Refinement.Rejected -> false
    }

private fun ImpactBoundaryKindDocument.matchesKind(current: BoundaryKind): Boolean =
    when (this) {
        ImpactBoundaryKindDocument.SERIALIZATION -> current == BoundaryKind.SERIALIZATION
        ImpactBoundaryKindDocument.PERSISTENCE -> current == BoundaryKind.PERSISTENCE
        ImpactBoundaryKindDocument.EXTERNAL_SYSTEM -> current == BoundaryKind.EXTERNAL_SYSTEM
    }
