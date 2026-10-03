package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Syntax anchors request native proof; exact references preserve the selected producer callable. */
@Serializable
data class QueryImpactProducerDocument(
    val enclosing: ProtocolText,
    val callable: ProtocolText,
    val anchor: ImpactSourceRangeDocument,
)

/** A locator for fresh revalidation, separate from the supplied model's identity claim. */
@Serializable
data class QueryImpactDeclarationDocument(
    val reference: ProtocolText,
    val declaration: ImpactDeclarationReferenceDocument,
)

@Serializable
enum class QueryImpactFlowDocument {
    KOTLIN_FORWARD_V1
}

/** Required B/P/D/F/M recipe. The native admission result is retained by the existing query checkpoint. */
@Serializable
data class QueryImpactSourceDocument(
    @ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 32)
    val seeds: BoundedProtocolList<QueryImpactProducerDocument>,
    @ProtocolCollectionConstraint(maximumItems = 128)
    val declarations: BoundedProtocolList<QueryImpactDeclarationDocument>,
    val domain: QueryExpansionScopeDocument,
    val flow: QueryImpactFlowDocument,
    @ProtocolCollectionConstraint(maximumItems = 32) val models: BoundedProtocolList<ImpactModelDocument>,
    @ProtocolCollectionConstraint(maximumItems = 128)
    val requestedSites: BoundedProtocolList<ImpactValueSiteReferenceDocument> = EmptyImpactRequestedSites,
)

@Serializable
enum class QueryImpactSourceFailureCode(internal val recoveryAction: ReadRecoveryAction) {
    EMPTY_PRODUCERS(ReadRecoveryAction.CORRECT_REQUEST),
    DUPLICATE_REQUESTED_SITE(ReadRecoveryAction.CORRECT_REQUEST),
    STALE_REQUESTED_SITE(ReadRecoveryAction.REACQUIRE_AUTHORITY),
    UNSUPPORTED_REQUESTED_SITE(ReadRecoveryAction.CORRECT_REQUEST),
    UNRESOLVED_REQUESTED_SITE(ReadRecoveryAction.CORRECT_REQUEST),
    TOO_MANY_REQUESTED_SITES(ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE),
    DUPLICATE_PRODUCER(ReadRecoveryAction.CORRECT_REQUEST),
    TOO_MANY_PRODUCERS(ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE),
    TOO_MANY_DECLARATIONS(ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE),
    TOO_MANY_MODELS(ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE),
    DUPLICATE_DECLARATION(ReadRecoveryAction.CORRECT_REQUEST),
    INVALID_ANCHOR(ReadRecoveryAction.CORRECT_REQUEST),
    BASIS_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    ANCHOR_OUTSIDE_ENCLOSING(ReadRecoveryAction.CORRECT_REQUEST),
    ENCLOSING_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    CALLABLE_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    ANCHOR_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    ROLE_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    STALE_ENCLOSING(ReadRecoveryAction.REACQUIRE_AUTHORITY),
    STALE_CALLABLE(ReadRecoveryAction.REACQUIRE_AUTHORITY),
    UNRESOLVED_INVOCATION(ReadRecoveryAction.CORRECT_REQUEST),
    UNSUPPORTED_INVOCATION(ReadRecoveryAction.CORRECT_REQUEST),
    OWNER_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    AUTHORITY_MOVED(ReadRecoveryAction.REACQUIRE_AUTHORITY),
    NATIVE_UNAVAILABLE(ReadRecoveryAction.REPORT_FAILURE),
    GRANT_TOO_SMALL(ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE),
    OUTSIDE_DOMAIN(ReadRecoveryAction.CORRECT_REQUEST),
    TIME_LIMIT_REACHED(ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE),
    WORK_LIMIT_REACHED(ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE),
    BYTE_LIMIT_REACHED(ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE),
    WORK_RECEIPT_EXCEEDS_GRANT(ReadRecoveryAction.REPORT_FAILURE),
    DECLARATION_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    STALE_DECLARATION(ReadRecoveryAction.REACQUIRE_AUTHORITY),
    DUPLICATE_MODEL_REFERENCE(ReadRecoveryAction.CORRECT_REQUEST),
    DUPLICATE_SOURCE_SET(ReadRecoveryAction.CORRECT_REQUEST),
    SOURCE_SET_REJECTED(ReadRecoveryAction.CORRECT_REQUEST),
    EMPTY_SOURCE_SET(ReadRecoveryAction.CORRECT_REQUEST),
    DIRECTORY_REJECTED(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_KIND_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    MISSING_DECLARATION(ReadRecoveryAction.CORRECT_REQUEST),
    MISSING_BOUNDARY_POSITION(ReadRecoveryAction.CORRECT_REQUEST),
    IDENTIFIER_INVALID(ReadRecoveryAction.CORRECT_REQUEST),
    VERSION_NOT_POSITIVE(ReadRecoveryAction.CORRECT_REQUEST),
    POSITION_NEGATIVE(ReadRecoveryAction.CORRECT_REQUEST),
    BINDING_BASIS_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    BINDING_CALLABLE_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    BINDING_DECLARATION_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    BINDING_POSITION_UNAVAILABLE(ReadRecoveryAction.CORRECT_REQUEST),
    DOMAIN_EMPTY(ReadRecoveryAction.CORRECT_REQUEST),
    DOMAIN_TOO_LARGE(ReadRecoveryAction.CORRECT_REQUEST),
    DOMAIN_DUPLICATE_STATE(ReadRecoveryAction.CORRECT_REQUEST),
    STATE_UNDECLARED(ReadRecoveryAction.CORRECT_REQUEST),
    RULE_MODEL_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    RULE_WRONG_POSITION(ReadRecoveryAction.CORRECT_REQUEST),
    RULE_CALLABLE_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    RULE_BASIS_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    BOUNDARY_SOURCE_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    BOUNDARY_TARGET_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    BOUNDARY_BASIS_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    BOUNDARY_KIND_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_MALFORMED_DOCUMENT(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_EMPTY_RULES(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_DUPLICATE_RULE_ID(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_INVALID_STATE_DOMAIN(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_UNDECLARED_STATE(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_INVALID_DECLARATION(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_INVALID_BASIS(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_INVALID_RANGE(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_INVALID_POSITION(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_INVALID_INVOCATION(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_INCOMPATIBLE_BOUNDARY_KIND(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_INVALID_ASSUMPTIONS(ReadRecoveryAction.CORRECT_REQUEST),
    MODEL_INVALID_TERMINAL(ReadRecoveryAction.CORRECT_REQUEST),
    STALE_BOUNDARY_SITE(ReadRecoveryAction.REACQUIRE_AUTHORITY),
    UNSUPPORTED_BOUNDARY_SITE(ReadRecoveryAction.CORRECT_REQUEST),
    UNRESOLVED_BOUNDARY_SITE(ReadRecoveryAction.CORRECT_REQUEST),
    OWNER_UNAVAILABLE(ReadRecoveryAction.CORRECT_REQUEST),
    NESTED_EXECUTION(ReadRecoveryAction.CORRECT_REQUEST),
    INVOCATION_OUTSIDE_ENCLOSING(ReadRecoveryAction.CORRECT_REQUEST),
    ARGUMENT_OUTSIDE_INVOCATION(ReadRecoveryAction.CORRECT_REQUEST),
    INVOCATION_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    ARGUMENT_POSITION_MISMATCH(ReadRecoveryAction.CORRECT_REQUEST),
    EXTERNAL_CALL(ReadRecoveryAction.CORRECT_REQUEST),
    UNMODELED_CALL(ReadRecoveryAction.CORRECT_REQUEST),
    MUTABLE_CONTROL_FLOW(ReadRecoveryAction.CORRECT_REQUEST),
    UNSUPPORTED_EXPRESSION(ReadRecoveryAction.CORRECT_REQUEST),
    UNRESOLVED_REFERENCE(ReadRecoveryAction.CORRECT_REQUEST),
    UNSUPPORTED_PROPERTY(ReadRecoveryAction.CORRECT_REQUEST),
    UNSUPPORTED_RETURN(ReadRecoveryAction.CORRECT_REQUEST),
    RESULT_LIMIT_REACHED(ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE),
}

@Serializable
sealed interface QueryImpactSourceFailureDocument {
    @Serializable
    @SerialName("ADMISSION")
    data class Admission(val cause: QueryImpactSourceFailureCode, val position: ProtocolOffset) :
        QueryImpactSourceFailureDocument

    @Serializable
    @SerialName("REFERENCE")
    data class Reference(val reason: QueryReferenceRejectionReason, val position: ProtocolOffset) :
        QueryImpactSourceFailureDocument
}

/** Omitted target selection means an empty universe, never inferred targets. */
val EmptyImpactRequestedSites: BoundedProtocolList<ImpactValueSiteReferenceDocument> =
    (BoundedProtocolList.create(emptyList<ImpactValueSiteReferenceDocument>()) as Refinement.Refined).value
