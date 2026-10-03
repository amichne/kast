package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.relation.contract.BoundaryModelFailure
import io.github.amichne.kast.relation.contract.ModelBindingFailure
import io.github.amichne.kast.relation.contract.ModelIdentifierFailure
import io.github.amichne.kast.relation.contract.ModelVersionFailure
import io.github.amichne.kast.relation.contract.RepresentationDomainFailure
import io.github.amichne.kast.relation.contract.RepresentationRuleFailure
import io.github.amichne.kast.relation.contract.RepresentationStateFailure
import io.github.amichne.kast.relation.contract.ValueArgumentPositionFailure

sealed interface ImpactModelAdmissionFailure {
    data object MODEL_KIND_MISMATCH : ImpactModelAdmissionFailure

    data object MISSING_DECLARATION : ImpactModelAdmissionFailure

    data object STALE_DECLARATION : ImpactModelAdmissionFailure

    data object MISSING_BOUNDARY_POSITION : ImpactModelAdmissionFailure

    data class Identifier(val cause: ModelIdentifierFailure) : ImpactModelAdmissionFailure

    data class Version(val cause: ModelVersionFailure) : ImpactModelAdmissionFailure

    data class Position(val cause: ValueArgumentPositionFailure) : ImpactModelAdmissionFailure

    data class Callable(val cause: ModelBindingFailure) : ImpactModelAdmissionFailure

    data class Domain(val cause: RepresentationDomainFailure) : ImpactModelAdmissionFailure

    data class State(val cause: RepresentationStateFailure) : ImpactModelAdmissionFailure

    data class Rule(val cause: RepresentationRuleFailure) : ImpactModelAdmissionFailure

    data class Boundary(val cause: BoundaryModelFailure) : ImpactModelAdmissionFailure
}
