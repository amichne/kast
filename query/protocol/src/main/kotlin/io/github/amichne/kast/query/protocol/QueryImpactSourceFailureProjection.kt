package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactModelSyntaxFailure
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.relation.contract.BoundaryModelFailure
import io.github.amichne.kast.relation.contract.ModelBindingFailure
import io.github.amichne.kast.relation.contract.ModelIdentifierFailure
import io.github.amichne.kast.relation.contract.ModelVersionFailure
import io.github.amichne.kast.relation.contract.RepresentationDomainFailure
import io.github.amichne.kast.relation.contract.RepresentationRuleFailure
import io.github.amichne.kast.relation.contract.RepresentationStateFailure
import io.github.amichne.kast.relation.contract.ValueArgumentPositionFailure
import io.github.amichne.kast.relation.contract.ValueProducerSeedFailure
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection

internal fun ValueProducerSeedRejection.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ValueProducerSeedRejection.STALE_ENCLOSING -> QueryImpactSourceFailureCode.STALE_ENCLOSING
        ValueProducerSeedRejection.STALE_CALLABLE -> QueryImpactSourceFailureCode.STALE_CALLABLE
        ValueProducerSeedRejection.UNRESOLVED_INVOCATION -> QueryImpactSourceFailureCode.UNRESOLVED_INVOCATION
        ValueProducerSeedRejection.UNSUPPORTED_INVOCATION -> QueryImpactSourceFailureCode.UNSUPPORTED_INVOCATION
        ValueProducerSeedRejection.ANCHOR_MISMATCH -> QueryImpactSourceFailureCode.ANCHOR_MISMATCH
        ValueProducerSeedRejection.CALLABLE_MISMATCH -> QueryImpactSourceFailureCode.CALLABLE_MISMATCH
        ValueProducerSeedRejection.OWNER_MISMATCH -> QueryImpactSourceFailureCode.OWNER_MISMATCH
        ValueProducerSeedRejection.AUTHORITY_MOVED -> QueryImpactSourceFailureCode.AUTHORITY_MOVED
        ValueProducerSeedRejection.NATIVE_UNAVAILABLE -> QueryImpactSourceFailureCode.NATIVE_UNAVAILABLE
        ValueProducerSeedRejection.GRANT_TOO_SMALL -> QueryImpactSourceFailureCode.GRANT_TOO_SMALL
        ValueProducerSeedRejection.OUTSIDE_DOMAIN -> QueryImpactSourceFailureCode.OUTSIDE_DOMAIN
        ValueProducerSeedRejection.TIME_LIMIT_REACHED -> QueryImpactSourceFailureCode.TIME_LIMIT_REACHED
        ValueProducerSeedRejection.WORK_LIMIT_REACHED -> QueryImpactSourceFailureCode.WORK_LIMIT_REACHED
        ValueProducerSeedRejection.BYTE_LIMIT_REACHED -> QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED
    }

internal fun ValueProducerSeedFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ValueProducerSeedFailure.BASIS_MISMATCH -> QueryImpactSourceFailureCode.BASIS_MISMATCH
        ValueProducerSeedFailure.ENCLOSING_MISMATCH -> QueryImpactSourceFailureCode.ENCLOSING_MISMATCH
        ValueProducerSeedFailure.CALLABLE_MISMATCH -> QueryImpactSourceFailureCode.CALLABLE_MISMATCH
        ValueProducerSeedFailure.ANCHOR_MISMATCH -> QueryImpactSourceFailureCode.ANCHOR_MISMATCH
        ValueProducerSeedFailure.ROLE_MISMATCH -> QueryImpactSourceFailureCode.ROLE_MISMATCH
    }

internal fun QueryExpansionScopeFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        QueryExpansionScopeFailure.DUPLICATE_SOURCE_SET -> QueryImpactSourceFailureCode.DUPLICATE_SOURCE_SET
        QueryExpansionScopeFailure.SOURCE_SET_REJECTED -> QueryImpactSourceFailureCode.SOURCE_SET_REJECTED
        QueryExpansionScopeFailure.EMPTY_SOURCE_SET -> QueryImpactSourceFailureCode.EMPTY_SOURCE_SET
        QueryExpansionScopeFailure.DIRECTORY_REJECTED -> QueryImpactSourceFailureCode.DIRECTORY_REJECTED
    }

internal fun ImpactModelSyntaxFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ImpactModelSyntaxFailure.MALFORMED_DOCUMENT -> QueryImpactSourceFailureCode.MODEL_MALFORMED_DOCUMENT
        ImpactModelSyntaxFailure.EMPTY_RULES -> QueryImpactSourceFailureCode.MODEL_EMPTY_RULES
        ImpactModelSyntaxFailure.DUPLICATE_RULE_ID -> QueryImpactSourceFailureCode.MODEL_DUPLICATE_RULE_ID
        ImpactModelSyntaxFailure.INVALID_STATE_DOMAIN -> QueryImpactSourceFailureCode.MODEL_INVALID_STATE_DOMAIN
        ImpactModelSyntaxFailure.UNDECLARED_STATE -> QueryImpactSourceFailureCode.MODEL_UNDECLARED_STATE
        ImpactModelSyntaxFailure.INVALID_DECLARATION -> QueryImpactSourceFailureCode.MODEL_INVALID_DECLARATION
        ImpactModelSyntaxFailure.INVALID_BASIS -> QueryImpactSourceFailureCode.MODEL_INVALID_BASIS
        ImpactModelSyntaxFailure.INVALID_RANGE -> QueryImpactSourceFailureCode.MODEL_INVALID_RANGE
        ImpactModelSyntaxFailure.INVALID_POSITION -> QueryImpactSourceFailureCode.MODEL_INVALID_POSITION
        ImpactModelSyntaxFailure.INVALID_INVOCATION -> QueryImpactSourceFailureCode.MODEL_INVALID_INVOCATION
        ImpactModelSyntaxFailure.INCOMPATIBLE_BOUNDARY_KIND ->
            QueryImpactSourceFailureCode.MODEL_INCOMPATIBLE_BOUNDARY_KIND
        ImpactModelSyntaxFailure.INVALID_ASSUMPTIONS -> QueryImpactSourceFailureCode.MODEL_INVALID_ASSUMPTIONS
        ImpactModelSyntaxFailure.INVALID_TERMINAL -> QueryImpactSourceFailureCode.MODEL_INVALID_TERMINAL
    }

internal fun ModelIdentifierFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ModelIdentifierFailure.INVALID -> QueryImpactSourceFailureCode.IDENTIFIER_INVALID
    }

internal fun ModelVersionFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ModelVersionFailure.NOT_POSITIVE -> QueryImpactSourceFailureCode.VERSION_NOT_POSITIVE
    }

internal fun ValueArgumentPositionFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ValueArgumentPositionFailure.NEGATIVE -> QueryImpactSourceFailureCode.POSITION_NEGATIVE
    }

internal fun ModelBindingFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ModelBindingFailure.BASIS_MISMATCH -> QueryImpactSourceFailureCode.BINDING_BASIS_MISMATCH
        ModelBindingFailure.CALLABLE_MISMATCH -> QueryImpactSourceFailureCode.BINDING_CALLABLE_MISMATCH
        ModelBindingFailure.DECLARATION_MISMATCH -> QueryImpactSourceFailureCode.BINDING_DECLARATION_MISMATCH
        ModelBindingFailure.POSITION_UNAVAILABLE -> QueryImpactSourceFailureCode.BINDING_POSITION_UNAVAILABLE
    }

internal fun RepresentationDomainFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        RepresentationDomainFailure.EMPTY_DOMAIN -> QueryImpactSourceFailureCode.DOMAIN_EMPTY
        RepresentationDomainFailure.DOMAIN_TOO_LARGE -> QueryImpactSourceFailureCode.DOMAIN_TOO_LARGE
        RepresentationDomainFailure.DUPLICATE_STATE -> QueryImpactSourceFailureCode.DOMAIN_DUPLICATE_STATE
    }

internal fun RepresentationStateFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        RepresentationStateFailure.UNDECLARED_STATE -> QueryImpactSourceFailureCode.STATE_UNDECLARED
    }

internal fun RepresentationRuleFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        RepresentationRuleFailure.MODEL_MISMATCH -> QueryImpactSourceFailureCode.RULE_MODEL_MISMATCH
        RepresentationRuleFailure.WRONG_POSITION -> QueryImpactSourceFailureCode.RULE_WRONG_POSITION
        RepresentationRuleFailure.CALLABLE_MISMATCH -> QueryImpactSourceFailureCode.RULE_CALLABLE_MISMATCH
        RepresentationRuleFailure.BASIS_MISMATCH -> QueryImpactSourceFailureCode.RULE_BASIS_MISMATCH
    }

internal fun BoundaryModelFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        BoundaryModelFailure.SOURCE_MISMATCH -> QueryImpactSourceFailureCode.BOUNDARY_SOURCE_MISMATCH
        BoundaryModelFailure.TARGET_MISMATCH -> QueryImpactSourceFailureCode.BOUNDARY_TARGET_MISMATCH
        BoundaryModelFailure.BASIS_MISMATCH -> QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH
        BoundaryModelFailure.KIND_MISMATCH -> QueryImpactSourceFailureCode.BOUNDARY_KIND_MISMATCH
    }

internal fun ImpactModelAdmissionFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ImpactModelAdmissionFailure.MODEL_KIND_MISMATCH -> QueryImpactSourceFailureCode.MODEL_KIND_MISMATCH
        ImpactModelAdmissionFailure.MISSING_DECLARATION -> QueryImpactSourceFailureCode.MISSING_DECLARATION
        ImpactModelAdmissionFailure.STALE_DECLARATION -> QueryImpactSourceFailureCode.STALE_DECLARATION
        ImpactModelAdmissionFailure.MISSING_BOUNDARY_POSITION -> QueryImpactSourceFailureCode.MISSING_BOUNDARY_POSITION
        is ImpactModelAdmissionFailure.Identifier -> cause.impactFailure()
        is ImpactModelAdmissionFailure.Version -> cause.impactFailure()
        is ImpactModelAdmissionFailure.Position -> cause.impactFailure()
        is ImpactModelAdmissionFailure.Callable -> cause.impactFailure()
        is ImpactModelAdmissionFailure.Domain -> cause.impactFailure()
        is ImpactModelAdmissionFailure.State -> cause.impactFailure()
        is ImpactModelAdmissionFailure.Rule -> cause.impactFailure()
        is ImpactModelAdmissionFailure.Boundary -> cause.impactFailure()
    }
