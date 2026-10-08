package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactBoundaryKindDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationFailure
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationLimit
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequestFailure
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector

internal fun ImpactModelDocument.Boundary.positions(): List<ImpactBoundaryPositionDocument> =
    rules.values.flatMap { rule ->
        when (rule) {
            is ImpactBoundaryRuleDocument.Continuation -> listOf(rule.source, rule.target)
            is ImpactBoundaryRuleDocument.Terminal -> listOf(rule.source)
        }
    }

internal fun ImpactValueSiteReferenceDocument.siteRequest(
    current: List<SymbolSelector>,
    budget: RelationBudget,
): Refinement<ValueSiteRevalidationRequest, QueryImpactSourceFailureCode> {
    val owner =
        current.firstOrNull {
            enclosing.matchesDeclaration(RelationEndpoint.subject(it)) &&
                enclosing.basis.matchesBasis(it.lease.identity)
        } ?: return Refinement.Rejected(QueryImpactSourceFailureCode.MISSING_DECLARATION)
    val anchor =
        when (val admitted = range.domainRange()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val role =
        when (val syntax = role) {
            ImpactValueRoleDocument.ExpressionResult -> ValueSiteRoleClaim.ExpressionResult
            ImpactValueRoleDocument.LocalBinding -> ValueSiteRoleClaim.LocalBinding
            ImpactValueRoleDocument.LocalRead -> ValueSiteRoleClaim.LocalRead
            ImpactValueRoleDocument.Return -> ValueSiteRoleClaim.Return
            ImpactValueRoleDocument.PropertyAssignment -> ValueSiteRoleClaim.PropertyAssignment
            is ImpactValueRoleDocument.Argument -> {
                val callable =
                    current.firstOrNull {
                        syntax.invocation.callable.matchesDeclaration(RelationEndpoint.subject(it)) &&
                            syntax.invocation.callable.basis.matchesBasis(it.lease.identity)
                    } ?: return Refinement.Rejected(QueryImpactSourceFailureCode.MISSING_DECLARATION)
                val invocationAnchor =
                    when (val admitted = syntax.invocation.range.domainRange()) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return admitted
                    }
                val position =
                    when (val admitted = ValueArgumentPosition.parse(syntax.index.value)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return Refinement.Rejected(admitted.failure.impactFailure())
                    }
                ValueSiteRoleClaim.Argument(invocationAnchor, callable, position)
            }
        }
    return when (val admitted = ValueSiteRevalidationRequest.create(owner, anchor, role, budget)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(admitted.failure.impactFailure())
    }
}

internal fun ImpactBoundaryPositionDocument.attachToNative(
    current: ValueSite
): Refinement<BoundaryPosition, ImpactModelAdmissionFailure> {
    if (!site.matchesSite(current)) return Refinement.Rejected(ImpactModelAdmissionFailure.MISSING_BOUNDARY_POSITION)
    val contractId =
        when (val admitted = contract.id.domainIdentifier()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val version =
        when (val admitted = ModelVersion.parse(contract.version.value)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(ImpactModelAdmissionFailure.Version(admitted.failure))
        }
    val slot =
        when (val admitted = slot.domainIdentifier()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val kind =
        when (kind) {
            ImpactBoundaryKindDocument.SERIALIZATION -> BoundaryKind.SERIALIZATION
            ImpactBoundaryKindDocument.PERSISTENCE -> BoundaryKind.PERSISTENCE
            ImpactBoundaryKindDocument.EXTERNAL_SYSTEM -> BoundaryKind.EXTERNAL_SYSTEM
        }
    // Native proof establishes the site only. These values remain supplied, reviewed model vocabulary.
    return Refinement.Refined(BoundaryPosition.at(current, kind, BoundaryContractIdentity(contractId, version), slot))
}

private fun ImpactSourceRangeDocument.domainRange():
    Refinement<ExactDeclarationTextRange, QueryImpactSourceFailureCode> =
    when (val admitted = ExactDeclarationTextRange.parse(start.value, end.value)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(QueryImpactSourceFailureCode.INVALID_ANCHOR)
    }

internal fun ValueSiteRevalidationRequestFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ValueSiteRevalidationRequestFailure.BASIS_MISMATCH -> QueryImpactSourceFailureCode.BASIS_MISMATCH
        ValueSiteRevalidationRequestFailure.ANCHOR_OUTSIDE_ENCLOSING ->
            QueryImpactSourceFailureCode.ANCHOR_OUTSIDE_ENCLOSING
        ValueSiteRevalidationRequestFailure.INVOCATION_OUTSIDE_ENCLOSING ->
            QueryImpactSourceFailureCode.INVOCATION_OUTSIDE_ENCLOSING
        ValueSiteRevalidationRequestFailure.ARGUMENT_OUTSIDE_INVOCATION ->
            QueryImpactSourceFailureCode.ARGUMENT_OUTSIDE_INVOCATION
        ValueSiteRevalidationRequestFailure.ARGUMENT_POSITION_UNAVAILABLE ->
            QueryImpactSourceFailureCode.BINDING_POSITION_UNAVAILABLE
    }

internal fun ValueSiteRevalidationFailure.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ValueSiteRevalidationFailure.BASIS_MISMATCH -> QueryImpactSourceFailureCode.BASIS_MISMATCH
        ValueSiteRevalidationFailure.ENCLOSING_MISMATCH -> QueryImpactSourceFailureCode.ENCLOSING_MISMATCH
        ValueSiteRevalidationFailure.ANCHOR_MISMATCH -> QueryImpactSourceFailureCode.ANCHOR_MISMATCH
        ValueSiteRevalidationFailure.ROLE_MISMATCH -> QueryImpactSourceFailureCode.ROLE_MISMATCH
        ValueSiteRevalidationFailure.INVOCATION_MISMATCH -> QueryImpactSourceFailureCode.INVOCATION_MISMATCH
        ValueSiteRevalidationFailure.CALLABLE_MISMATCH -> QueryImpactSourceFailureCode.CALLABLE_MISMATCH
        ValueSiteRevalidationFailure.ARGUMENT_POSITION_MISMATCH ->
            QueryImpactSourceFailureCode.ARGUMENT_POSITION_MISMATCH
    }

internal fun ValueFlowRejection.boundaryFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ValueFlowRejection.STALE_SITE -> QueryImpactSourceFailureCode.STALE_BOUNDARY_SITE
        ValueFlowRejection.OUTSIDE_DOMAIN -> QueryImpactSourceFailureCode.OUTSIDE_DOMAIN
        ValueFlowRejection.OWNER_UNAVAILABLE -> QueryImpactSourceFailureCode.OWNER_UNAVAILABLE
        ValueFlowRejection.AUTHORITY_MOVED -> QueryImpactSourceFailureCode.AUTHORITY_MOVED
        ValueFlowRejection.UNSUPPORTED_SEED -> QueryImpactSourceFailureCode.UNSUPPORTED_BOUNDARY_SITE
        ValueFlowRejection.UNRESOLVED_SEED -> QueryImpactSourceFailureCode.UNRESOLVED_BOUNDARY_SITE
        ValueFlowRejection.NATIVE_UNAVAILABLE -> QueryImpactSourceFailureCode.NATIVE_UNAVAILABLE
        ValueFlowRejection.GRANT_TOO_SMALL -> QueryImpactSourceFailureCode.GRANT_TOO_SMALL
        ValueFlowRejection.NESTED_EXECUTION -> QueryImpactSourceFailureCode.NESTED_EXECUTION
    }

internal fun ValueFlowUnsupportedCause.boundaryFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED -> QueryImpactSourceFailureCode.FINALLY_UNSUPPORTED
        ValueFlowUnsupportedCause.ABRUPT_COMPLETION -> QueryImpactSourceFailureCode.ABRUPT_COMPLETION
        ValueFlowUnsupportedCause.EXTERNAL_CALL -> QueryImpactSourceFailureCode.EXTERNAL_CALL
        ValueFlowUnsupportedCause.UNMODELED_CALL -> QueryImpactSourceFailureCode.UNMODELED_CALL
        ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW -> QueryImpactSourceFailureCode.MUTABLE_CONTROL_FLOW
        ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION -> QueryImpactSourceFailureCode.UNSUPPORTED_EXPRESSION
        ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE -> QueryImpactSourceFailureCode.UNRESOLVED_REFERENCE
        ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY -> QueryImpactSourceFailureCode.UNSUPPORTED_PROPERTY
        ValueFlowUnsupportedCause.UNSUPPORTED_RETURN -> QueryImpactSourceFailureCode.UNSUPPORTED_RETURN
        ValueFlowUnsupportedCause.NESTED_EXECUTION -> QueryImpactSourceFailureCode.NESTED_EXECUTION
        ValueFlowUnsupportedCause.OUTSIDE_DOMAIN -> QueryImpactSourceFailureCode.OUTSIDE_DOMAIN
        ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED -> QueryImpactSourceFailureCode.RESULT_LIMIT_REACHED
        ValueFlowUnsupportedCause.WORK_LIMIT_REACHED -> QueryImpactSourceFailureCode.WORK_LIMIT_REACHED
        ValueFlowUnsupportedCause.TIME_LIMIT_REACHED -> QueryImpactSourceFailureCode.TIME_LIMIT_REACHED
        ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED -> QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED
    }

internal fun ValueSiteRevalidationLimit.impactFailure(): QueryImpactSourceFailureCode =
    when (this) {
        ValueSiteRevalidationLimit.WORK_LIMIT_REACHED -> QueryImpactSourceFailureCode.WORK_LIMIT_REACHED
        ValueSiteRevalidationLimit.TIME_LIMIT_REACHED -> QueryImpactSourceFailureCode.TIME_LIMIT_REACHED
        ValueSiteRevalidationLimit.BYTE_LIMIT_REACHED -> QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED
    }
