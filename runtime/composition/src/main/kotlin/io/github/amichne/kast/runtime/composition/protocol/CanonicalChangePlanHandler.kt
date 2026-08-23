package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddDeclarationPlanResult
import io.github.amichne.kast.change.contract.AddFilePlanRequest
import io.github.amichne.kast.change.contract.AddFilePlanResult
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.contract.ChangePlanningFailure
import io.github.amichne.kast.change.contract.RenameSymbolPlanRequest
import io.github.amichne.kast.change.contract.RenameSymbolPlanResult
import io.github.amichne.kast.change.contract.RenameSymbolPlanningFailure
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanRequest
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanResult
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanningFailure
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.runtime.composition.ChangePlanningOperations
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Public change intent strengthened with selector authority before semantic admission. */
internal sealed interface AuthorizedChangeIntent {
    data class AddFile(
        val intent: ChangeIntentDocument.AddFile,
    ) : AuthorizedChangeIntent

    data class AddDeclaration(
        val selector: SymbolSelector,
        val declaration: io.github.amichne.kast.protocol.contract.ProtocolText,
    ) : AuthorizedChangeIntent

    data class ReplaceDeclaration(
        val selector: SymbolSelector,
        val replacement: io.github.amichne.kast.protocol.contract.ProtocolText,
    ) : AuthorizedChangeIntent

    data class RenameSymbol(
        val selector: SymbolSelector,
        val newName: io.github.amichne.kast.protocol.contract.ProtocolText,
    ) : AuthorizedChangeIntent
}

/** Strong operation-specific requests admitted from the closed public change intent. */
internal sealed interface ChangePlanAdmission {
    data class AddFile(val request: AddFilePlanRequest) : ChangePlanAdmission
    data class AddDeclaration(val request: AddDeclarationPlanRequest) : ChangePlanAdmission
    data class ReplaceDeclaration(val request: ReplaceDeclarationPlanRequest) : ChangePlanAdmission
    data class RenameSymbol(val request: RenameSymbolPlanRequest) : ChangePlanAdmission
    data class Rejected(val failure: ChangePlanAdmissionFailure) : ChangePlanAdmission
}

/** Finite failures while refining a public intent into one exact typed planning request. */
internal enum class ChangePlanAdmissionFailure {
    WORKSPACE_NOT_READY,
    SYMBOL_RESOLVE_REQUIRED,
    EDITABLE_TARGET_REQUIRED,
    RELATION_READ_REQUIRED,
    TOPOLOGY_BUILD_REQUIRED,
    REQUIRED_TRAVERSAL_INCOMPLETE,
    DIAGNOSTIC_CHECK_REQUIRED,
    INTENT_REJECTED,
}

/** Physical and semantic admission boundary used before pure change planning. */
internal fun interface ChangePlanAdmissionOperations {
    /**
     * Proof transition: `AuthorizedChangeIntent -> ChangePlanAdmission`.
     *
     * Input already preserves opaque-target resolution as a compiler-grounded selector. A typed
     * output additionally establishes the exact workspace lease, target ownership, source
     * preimage, compiler-derived intent, and required planning evidence for that closed intent.
     * [ChangePlanAdmissionFailure] closes expected boundary failure. Raw protocol text and live
     * compiler values may be extracted only inside the implementing outer adapter.
     */
    suspend fun admit(intent: AuthorizedChangeIntent): ChangePlanAdmission
}

internal class CanonicalChangePlanHandler(
    private val operations: ChangePlanningOperations,
    private val admission: ChangePlanAdmissionOperations,
    private val protocolAuthority: CanonicalProtocolAuthority,
    private val authority: CanonicalChangeAuthority,
) : OperationHandler<
    ChangePlanRequest,
    ChangePlanResult,
    ChangePlanQualification,
    ChangePlanRejection,
    > {
    override suspend fun execute(request: ChangePlanRequest): OperationOutcome<
        ChangePlanResult,
        ChangePlanQualification,
        ChangePlanRejection,
        > {
        val authorized = when (val result = authorize(request.intent)) {
            is ChangeIntentAuthorization.Authorized -> result.intent
            ChangeIntentAuthorization.MissingTarget -> return OperationOutcome.Rejected(
                ChangePlanRejection.SYMBOL_RESOLVE_REQUIRED,
            )
        }
        return when (val admitted = admission.admit(authorized)) {
            is ChangePlanAdmission.Rejected ->
                OperationOutcome.Rejected(admitted.failure.protocol())
            is ChangePlanAdmission.AddFile -> when (
                val result = operations.addFile.plan(admitted.request)
            ) {
                is AddFilePlanResult.Planned -> planned(result.plan)
            }
            is ChangePlanAdmission.AddDeclaration -> when (
                val result = operations.addDeclaration.plan(admitted.request)
            ) {
                is AddDeclarationPlanResult.Planned -> planned(result.plan)
                is AddDeclarationPlanResult.Rejected ->
                    OperationOutcome.Rejected(result.failure.protocol())
            }
            is ChangePlanAdmission.ReplaceDeclaration -> when (
                val result = operations.replaceDeclaration.plan(admitted.request)
            ) {
                is ReplaceDeclarationPlanResult.Planned -> planned(result.plan)
                is ReplaceDeclarationPlanResult.Rejected ->
                    OperationOutcome.Rejected(result.failure.protocol())
            }
            is ChangePlanAdmission.RenameSymbol -> when (
                val result = operations.renameSymbol.plan(admitted.request)
            ) {
                is RenameSymbolPlanResult.Planned -> planned(result.plan)
                is RenameSymbolPlanResult.Rejected ->
                    OperationOutcome.Rejected(result.failure.protocol())
            }
        }
    }

    private fun authorize(intent: ChangeIntentDocument): ChangeIntentAuthorization = when (intent) {
        is ChangeIntentDocument.AddFile -> ChangeIntentAuthorization.Authorized(
            AuthorizedChangeIntent.AddFile(intent),
        )
        is ChangeIntentDocument.AddDeclaration -> authorizeExact(intent.exactTarget) { selector ->
            AuthorizedChangeIntent.AddDeclaration(selector, intent.declaration)
        }
        is ChangeIntentDocument.ReplaceDeclaration -> authorizeExact(intent.exactTarget) { selector ->
            AuthorizedChangeIntent.ReplaceDeclaration(selector, intent.replacement)
        }
        is ChangeIntentDocument.RenameSymbol -> authorizeExact(intent.exactTarget) { selector ->
            AuthorizedChangeIntent.RenameSymbol(selector, intent.newName)
        }
    }

    private fun authorizeExact(
        target: io.github.amichne.kast.protocol.contract.ProtocolText,
        authorized: (SymbolSelector) -> AuthorizedChangeIntent,
    ): ChangeIntentAuthorization = when (val lookup = protocolAuthority.exact(target)) {
        is ExactSelectorLookup.Found -> ChangeIntentAuthorization.Authorized(
            authorized(lookup.selector),
        )
        ExactSelectorLookup.Missing -> ChangeIntentAuthorization.MissingTarget
    }

    private fun planned(
        plan: ChangePlan,
    ): OperationOutcome<ChangePlanResult, ChangePlanQualification, ChangePlanRejection> = when (
        val issued = authority.issuePlan(plan)
    ) {
        is ChangePlanIssuance.Issued -> OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperation.CHANGE_PLAN.id,
                plan.priorLease.generation,
                ChangePlanResult(issued.identity),
            ),
        )
        is ChangePlanIssuance.Rejected ->
            OperationOutcome.Rejected(ChangePlanRejection.INTENT_REJECTED)
    }
}

private sealed interface ChangeIntentAuthorization {
    data class Authorized(
        val intent: AuthorizedChangeIntent,
    ) : ChangeIntentAuthorization

    data object MissingTarget : ChangeIntentAuthorization
}

private fun ChangePlanAdmissionFailure.protocol(): ChangePlanRejection = when (this) {
    ChangePlanAdmissionFailure.WORKSPACE_NOT_READY -> ChangePlanRejection.WORKSPACE_NOT_READY
    ChangePlanAdmissionFailure.SYMBOL_RESOLVE_REQUIRED ->
        ChangePlanRejection.SYMBOL_RESOLVE_REQUIRED
    ChangePlanAdmissionFailure.EDITABLE_TARGET_REQUIRED ->
        ChangePlanRejection.EDITABLE_TARGET_REQUIRED
    ChangePlanAdmissionFailure.RELATION_READ_REQUIRED ->
        ChangePlanRejection.RELATION_READ_REQUIRED
    ChangePlanAdmissionFailure.TOPOLOGY_BUILD_REQUIRED ->
        ChangePlanRejection.TOPOLOGY_BUILD_REQUIRED
    ChangePlanAdmissionFailure.REQUIRED_TRAVERSAL_INCOMPLETE ->
        ChangePlanRejection.REQUIRED_TRAVERSAL_INCOMPLETE
    ChangePlanAdmissionFailure.DIAGNOSTIC_CHECK_REQUIRED ->
        ChangePlanRejection.DIAGNOSTIC_CHECK_REQUIRED
    ChangePlanAdmissionFailure.INTENT_REJECTED -> ChangePlanRejection.INTENT_REJECTED
}

private fun ChangePlanningFailure.protocol(): ChangePlanRejection = when (this) {
    ChangePlanningFailure.RELATION_EVIDENCE_REQUIRED,
    ChangePlanningFailure.RELATION_EVIDENCE_INCOMPLETE,
        -> ChangePlanRejection.RELATION_READ_REQUIRED
    ChangePlanningFailure.TRAVERSAL_EVIDENCE_REQUIRED ->
        ChangePlanRejection.INTENT_REJECTED
    ChangePlanningFailure.TRAVERSAL_EVIDENCE_INCOMPLETE ->
        ChangePlanRejection.REQUIRED_TRAVERSAL_INCOMPLETE
    ChangePlanningFailure.DIAGNOSTIC_EVIDENCE_REQUIRED,
    ChangePlanningFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE,
        -> ChangePlanRejection.DIAGNOSTIC_CHECK_REQUIRED
    ChangePlanningFailure.EVIDENCE_LEASE_MISMATCH,
    ChangePlanningFailure.EVIDENCE_TARGET_MISMATCH,
        -> ChangePlanRejection.SYMBOL_RESOLVE_REQUIRED
}

private fun RenameSymbolPlanningFailure.protocol(): ChangePlanRejection = when (this) {
    is RenameSymbolPlanningFailure.Evidence -> failure.protocol()
    RenameSymbolPlanningFailure.REFERENCE_EVIDENCE_REQUIRED,
    RenameSymbolPlanningFailure.REFERENCE_EVIDENCE_AMBIGUOUS,
        -> ChangePlanRejection.RELATION_READ_REQUIRED
    RenameSymbolPlanningFailure.NEW_NAME_UNCHANGED,
    RenameSymbolPlanningFailure.OCCURRENCE_EVIDENCE_MISMATCH,
        -> ChangePlanRejection.INTENT_REJECTED
}

private fun ReplaceDeclarationPlanningFailure.protocol(): ChangePlanRejection = when (this) {
    is ReplaceDeclarationPlanningFailure.Evidence -> failure.protocol()
    ReplaceDeclarationPlanningFailure.REPLACEMENT_UNCHANGED ->
        ChangePlanRejection.INTENT_REJECTED
}
