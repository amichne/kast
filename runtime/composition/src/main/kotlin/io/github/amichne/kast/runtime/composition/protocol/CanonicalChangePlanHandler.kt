package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.change.protocol.CanonicalChangePlanProtocol
import io.github.amichne.kast.change.protocol.ChangePlanAdmission
import io.github.amichne.kast.change.protocol.ChangePlanAdmissionFailure
import io.github.amichne.kast.change.verify.DurableChangeAuthority
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.query.protocol.*
import io.github.amichne.kast.runtime.composition.ChangePlanningOperations
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Public change intent strengthened with selector authority before semantic admission. */
internal sealed interface AuthorizedChangeIntent {
    data class AddFile(val intent: ChangeIntentDocument.AddFile) : AuthorizedChangeIntent

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

/** Physical and semantic admission boundary used before pure change planning. */
internal fun interface ChangePlanAdmissionOperations {
    /**
     * Proof transition: `AuthorizedChangeIntent -> ChangePlanAdmission`.
     *
     * Input already preserves opaque-target resolution as a compiler-grounded selector. A typed output additionally
     * establishes the exact workspace lease, target ownership, source preimage, compiler-derived intent, and required
     * planning evidence for that closed intent. [ChangePlanAdmissionFailure] closes expected boundary failure. Raw
     * protocol text and live compiler values may be extracted only inside the implementing outer adapter.
     */
    suspend fun admit(intent: AuthorizedChangeIntent): ChangePlanAdmission
}

internal class CanonicalChangePlanHandler(
    private val operations: ChangePlanningOperations,
    private val admission: ChangePlanAdmissionOperations,
    private val protocolAuthority: CanonicalProtocolAuthority,
    private val authority: DurableChangeAuthority,
) :
    OperationHandler<
        ChangePlanRequest,
        ChangePlanResult,
        ChangePlanQualification,
        ChangePlanRejection,
    > {
    override suspend fun execute(
        request: ChangePlanRequest
    ): OperationOutcome<
        ChangePlanResult,
        ChangePlanQualification,
        ChangePlanRejection,
    > {
        val authorized =
            when (val result = authorize(request.intent)) {
                is ChangeIntentAuthorization.Authorized -> result.intent
                ChangeIntentAuthorization.MissingTarget ->
                    return OperationOutcome.Rejected(ChangePlanRejection.EXACT_SYMBOL_REQUIRED)
            }
        return CanonicalChangePlanProtocol(operations, { admission.admit(authorized) }, authority).execute(request)
    }

    private fun authorize(intent: ChangeIntentDocument): ChangeIntentAuthorization =
        when (intent) {
            is ChangeIntentDocument.AddFile ->
                ChangeIntentAuthorization.Authorized(AuthorizedChangeIntent.AddFile(intent))
            is ChangeIntentDocument.AddDeclaration ->
                authorizeExact(intent.exactTarget) { selector ->
                    AuthorizedChangeIntent.AddDeclaration(selector, intent.declaration)
                }
            is ChangeIntentDocument.ReplaceDeclaration ->
                authorizeExact(intent.exactTarget) { selector ->
                    AuthorizedChangeIntent.ReplaceDeclaration(selector, intent.replacement)
                }
            is ChangeIntentDocument.RenameSymbol ->
                authorizeExact(intent.exactTarget) { selector ->
                    AuthorizedChangeIntent.RenameSymbol(selector, intent.newName)
                }
        }

    private fun authorizeExact(
        target: io.github.amichne.kast.protocol.contract.ProtocolText,
        authorized: (SymbolSelector) -> AuthorizedChangeIntent,
    ): ChangeIntentAuthorization =
        when (val lookup = protocolAuthority.exact(target)) {
            is ExactSelectorLookup.Found -> ChangeIntentAuthorization.Authorized(authorized(lookup.selector))
            is ExactSelectorLookup.Rejected -> ChangeIntentAuthorization.MissingTarget
        }
}

private sealed interface ChangeIntentAuthorization {
    data class Authorized(val intent: AuthorizedChangeIntent) : ChangeIntentAuthorization

    data object MissingTarget : ChangeIntentAuthorization
}
