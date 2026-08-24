package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeRecoveryDocumentState
import io.github.amichne.kast.protocol.contract.ChangeVerifyQualification
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyResult
import io.github.amichne.kast.protocol.contract.ProtocolText

internal fun ChangeApplyRequest.toSerializableDocument(): ChangeApplyRequestDocument =
    ChangeApplyRequestDocument(planIdentity.value)

/**
 * Proof transition: `ChangeApplyRequestDocument -> ChangeApplyRequest`.
 *
 * Establishes a refined plan identity. [WireDocumentConversion.Rejected] is the closed
 * expected failure. Raw request text may be extracted only in this wire adapter.
 */
internal fun ChangeApplyRequestDocument.toContract(): WireDocumentConversion<ChangeApplyRequest> =
    planIdentity.refineChangeProtocolText().mapConverted(::ChangeApplyRequest)

internal fun ChangeApplyResult.toSerializableDocument(): ChangeApplyResultDocument =
    ChangeApplyResultDocument(applicationIdentity.value)

/**
 * Proof transition: `ChangeApplyResultDocument -> ChangeApplyResult`.
 *
 * Establishes a refined application identity. [WireDocumentConversion.Rejected] is the closed
 * closed expected failure. Raw result text may be extracted only here.
 */
internal fun ChangeApplyResultDocument.toContract(): WireDocumentConversion<ChangeApplyResult> =
    applicationIdentity.refineChangeProtocolText().mapConverted(::ChangeApplyResult)

internal fun ChangeApplyQualification.toSerializableDocument():
    ChangeApplyQualificationDocument = when (this) {
    ChangeApplyQualification.RECOVERY_REQUIRED -> ChangeApplyQualificationDocument.RECOVERY_REQUIRED
}

internal fun ChangeApplyQualificationDocument.toContract():
    WireDocumentConversion<ChangeApplyQualification> = WireDocumentConversion.Converted(
    when (this) {
        ChangeApplyQualificationDocument.RECOVERY_REQUIRED ->
            ChangeApplyQualification.RECOVERY_REQUIRED
    },
)

internal fun ChangeApplyRejection.toSerializableDocument(): ChangeApplyRejectionDocument =
    when (this) {
        ChangeApplyRejection.PLAN_NOT_FOUND -> ChangeApplyRejectionDocument.PLAN_NOT_FOUND
        ChangeApplyRejection.ROOT_MISMATCH -> ChangeApplyRejectionDocument.ROOT_MISMATCH
        ChangeApplyRejection.GENERATION_STALE -> ChangeApplyRejectionDocument.GENERATION_STALE
        ChangeApplyRejection.CONTENT_CHANGED -> ChangeApplyRejectionDocument.CONTENT_CHANGED
        ChangeApplyRejection.WRITE_SCOPE_REJECTED ->
            ChangeApplyRejectionDocument.WRITE_SCOPE_REJECTED
        ChangeApplyRejection.ROLLED_BACK -> ChangeApplyRejectionDocument.ROLLED_BACK
        ChangeApplyRejection.RECOVERY_REQUIRED -> ChangeApplyRejectionDocument.RECOVERY_REQUIRED
    }

internal fun ChangeApplyRejectionDocument.toContract(): WireDocumentConversion<ChangeApplyRejection> =
    WireDocumentConversion.Converted(
        when (this) {
            ChangeApplyRejectionDocument.PLAN_NOT_FOUND -> ChangeApplyRejection.PLAN_NOT_FOUND
            ChangeApplyRejectionDocument.ROOT_MISMATCH -> ChangeApplyRejection.ROOT_MISMATCH
            ChangeApplyRejectionDocument.GENERATION_STALE -> ChangeApplyRejection.GENERATION_STALE
            ChangeApplyRejectionDocument.CONTENT_CHANGED -> ChangeApplyRejection.CONTENT_CHANGED
            ChangeApplyRejectionDocument.WRITE_SCOPE_REJECTED ->
                ChangeApplyRejection.WRITE_SCOPE_REJECTED
            ChangeApplyRejectionDocument.ROLLED_BACK -> ChangeApplyRejection.ROLLED_BACK
            ChangeApplyRejectionDocument.RECOVERY_REQUIRED ->
                ChangeApplyRejection.RECOVERY_REQUIRED
        },
    )

internal fun ChangeVerifyRequest.toSerializableDocument(): ChangeVerifyRequestDocument =
    ChangeVerifyRequestDocument(applicationIdentity.value)

/**
 * Proof transition: `ChangeVerifyRequestDocument -> ChangeVerifyRequest`.
 *
 * Establishes a refined application identity. [WireDocumentConversion.Rejected] is the closed
 * closed expected failure. Raw request text may be extracted only here.
 */
internal fun ChangeVerifyRequestDocument.toContract(): WireDocumentConversion<ChangeVerifyRequest> =
    applicationIdentity.refineChangeProtocolText().mapConverted(::ChangeVerifyRequest)

internal fun ChangeVerifyResult.toSerializableDocument(): ChangeVerifyResultDocument =
    ChangeVerifyResultDocument(receiptIdentity.value)

/**
 * Proof transition: `ChangeVerifyResultDocument -> ChangeVerifyResult`.
 *
 * Establishes a refined receipt identity. [WireDocumentConversion.Rejected] is the closed
 * closed expected failure. Raw result text may be extracted only here.
 */
internal fun ChangeVerifyResultDocument.toContract(): WireDocumentConversion<ChangeVerifyResult> =
    receiptIdentity.refineChangeProtocolText().mapConverted(::ChangeVerifyResult)

internal fun ChangeVerifyQualification.toSerializableDocument():
    ChangeVerifyQualificationDocument = when (this) {
    ChangeVerifyQualification.PROOF_INCOMPLETE ->
        ChangeVerifyQualificationDocument.PROOF_INCOMPLETE
}

internal fun ChangeVerifyQualificationDocument.toContract():
    WireDocumentConversion<ChangeVerifyQualification> = WireDocumentConversion.Converted(
    when (this) {
        ChangeVerifyQualificationDocument.PROOF_INCOMPLETE ->
            ChangeVerifyQualification.PROOF_INCOMPLETE
    },
)

internal fun ChangeVerifyRejection.toSerializableDocument(): ChangeVerifyRejectionDocument =
    when (this) {
        ChangeVerifyRejection.APPLICATION_NOT_FOUND ->
            ChangeVerifyRejectionDocument.APPLICATION_NOT_FOUND
        ChangeVerifyRejection.RESULTING_GENERATION_UNAVAILABLE ->
            ChangeVerifyRejectionDocument.RESULTING_GENERATION_UNAVAILABLE
        ChangeVerifyRejection.OBLIGATION_FAILED -> ChangeVerifyRejectionDocument.OBLIGATION_FAILED
        ChangeVerifyRejection.DIAGNOSTIC_REGRESSION ->
            ChangeVerifyRejectionDocument.DIAGNOSTIC_REGRESSION
        ChangeVerifyRejection.SEMANTIC_DELTA_REJECTED ->
            ChangeVerifyRejectionDocument.SEMANTIC_DELTA_REJECTED
    }

internal fun ChangeVerifyRejectionDocument.toContract(): WireDocumentConversion<ChangeVerifyRejection> =
    WireDocumentConversion.Converted(
        when (this) {
            ChangeVerifyRejectionDocument.APPLICATION_NOT_FOUND ->
                ChangeVerifyRejection.APPLICATION_NOT_FOUND
            ChangeVerifyRejectionDocument.RESULTING_GENERATION_UNAVAILABLE ->
                ChangeVerifyRejection.RESULTING_GENERATION_UNAVAILABLE
            ChangeVerifyRejectionDocument.OBLIGATION_FAILED ->
                ChangeVerifyRejection.OBLIGATION_FAILED
            ChangeVerifyRejectionDocument.DIAGNOSTIC_REGRESSION ->
                ChangeVerifyRejection.DIAGNOSTIC_REGRESSION
            ChangeVerifyRejectionDocument.SEMANTIC_DELTA_REJECTED ->
                ChangeVerifyRejection.SEMANTIC_DELTA_REJECTED
        },
    )

internal fun ChangeRecoverRequest.toSerializableDocument(): ChangeRecoverRequestDocument =
    ChangeRecoverRequestDocument(planIdentity.value)

/**
 * Proof transition: `ChangeRecoverRequestDocument -> ChangeRecoverRequest`.
 *
 * Establishes a refined plan identity. [WireDocumentConversion.Rejected] is the closed
 * expected failure. Raw request text may be extracted only in this wire adapter.
 */
internal fun ChangeRecoverRequestDocument.toContract(): WireDocumentConversion<ChangeRecoverRequest> =
    planIdentity.refineChangeProtocolText().mapConverted(::ChangeRecoverRequest)

internal fun ChangeRecoverResult.toSerializableDocument(): ChangeRecoverResultDocument =
    ChangeRecoverResultDocument(state.toSerializableDocument())

/**
 * Proof transition: `ChangeRecoverResultDocument -> ChangeRecoverResult`.
 *
 * Establishes one closed recovery state. This generated enum conversion cannot reject; raw state
 * text never leaves the generated document decoder.
 */
internal fun ChangeRecoverResultDocument.toContract(): WireDocumentConversion<ChangeRecoverResult> =
    WireDocumentConversion.Converted(ChangeRecoverResult(state.toContractValue()))

private fun ChangeRecoveryDocumentState.toSerializableDocument(): ChangeRecoveryStateDocument =
    when (this) {
        ChangeRecoveryDocumentState.PRIOR_STATE -> ChangeRecoveryStateDocument.PRIOR_STATE
        ChangeRecoveryDocumentState.ROLLED_BACK -> ChangeRecoveryStateDocument.ROLLED_BACK
        ChangeRecoveryDocumentState.RECOVERY_REQUIRED ->
            ChangeRecoveryStateDocument.RECOVERY_REQUIRED
    }

private fun ChangeRecoveryStateDocument.toContractValue(): ChangeRecoveryDocumentState = when (this) {
    ChangeRecoveryStateDocument.PRIOR_STATE -> ChangeRecoveryDocumentState.PRIOR_STATE
    ChangeRecoveryStateDocument.ROLLED_BACK -> ChangeRecoveryDocumentState.ROLLED_BACK
    ChangeRecoveryStateDocument.RECOVERY_REQUIRED ->
        ChangeRecoveryDocumentState.RECOVERY_REQUIRED
}

internal fun ChangeRecoverQualification.toSerializableDocument():
    ChangeRecoverQualificationDocument = when (this) {
    ChangeRecoverQualification.MANUAL_RECOVERY_REQUIRED ->
        ChangeRecoverQualificationDocument.MANUAL_RECOVERY_REQUIRED
}

internal fun ChangeRecoverQualificationDocument.toContract():
    WireDocumentConversion<ChangeRecoverQualification> = WireDocumentConversion.Converted(
    when (this) {
        ChangeRecoverQualificationDocument.MANUAL_RECOVERY_REQUIRED ->
            ChangeRecoverQualification.MANUAL_RECOVERY_REQUIRED
    },
)

internal fun ChangeRecoverRejection.toSerializableDocument(): ChangeRecoverRejectionDocument =
    when (this) {
        ChangeRecoverRejection.PLAN_NOT_FOUND -> ChangeRecoverRejectionDocument.PLAN_NOT_FOUND
        ChangeRecoverRejection.JOURNAL_UNAVAILABLE ->
            ChangeRecoverRejectionDocument.JOURNAL_UNAVAILABLE
        ChangeRecoverRejection.RECOVERY_FAILED -> ChangeRecoverRejectionDocument.RECOVERY_FAILED
    }

internal fun ChangeRecoverRejectionDocument.toContract(): WireDocumentConversion<ChangeRecoverRejection> =
    WireDocumentConversion.Converted(
        when (this) {
            ChangeRecoverRejectionDocument.PLAN_NOT_FOUND -> ChangeRecoverRejection.PLAN_NOT_FOUND
            ChangeRecoverRejectionDocument.JOURNAL_UNAVAILABLE ->
                ChangeRecoverRejection.JOURNAL_UNAVAILABLE
            ChangeRecoverRejectionDocument.RECOVERY_FAILED ->
                ChangeRecoverRejection.RECOVERY_FAILED
        },
    )

internal fun ChangePlanRequest.toSerializableDocument(): ChangePlanRequestDocument =
    ChangePlanRequestDocument(intent.toSerializableDocument())

/**
 * Proof transition: `ChangePlanRequestDocument -> ChangePlanRequest`.
 *
 * Establishes one closed change intent whose primitive fields are refined to [ProtocolText].
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw request fields
 * may be extracted only in this wire adapter.
 */
internal fun ChangePlanRequestDocument.toContract(): WireDocumentConversion<ChangePlanRequest> =
    intent.toContract().mapConverted(::ChangePlanRequest)

private fun ChangeIntentDocument.toSerializableDocument(): ChangeIntentWireDocument = when (this) {
    is ChangeIntentDocument.AddFile -> ChangeIntentWireDocument.AddFile(
        relativePath.value,
        content.value,
    )
    is ChangeIntentDocument.AddDeclaration -> ChangeIntentWireDocument.AddDeclaration(
        exactTarget.value,
        declaration.value,
    )
    is ChangeIntentDocument.ReplaceDeclaration -> ChangeIntentWireDocument.ReplaceDeclaration(
        exactTarget.value,
        replacement.value,
    )
    is ChangeIntentDocument.RenameSymbol -> ChangeIntentWireDocument.RenameSymbol(
        exactTarget.value,
        newName.value,
    )
}

/**
 * Proof transition: `ChangeIntentWireDocument -> ChangeIntentDocument`.
 *
 * Establishes exactly one of the four closed intent variants and refines every text field to
 * [ProtocolText]. [WireDocumentConversion.Rejected] is the closed expected failure. Raw
 * intent fields may be extracted only in this wire adapter.
 */
private fun ChangeIntentWireDocument.toContract(): WireDocumentConversion<ChangeIntentDocument> =
    when (this) {
        is ChangeIntentWireDocument.AddFile -> combineConverted(
            relativePath.refineChangeProtocolText(),
            content.refineChangeProtocolText(),
        ) { path, content -> ChangeIntentDocument.AddFile(path, content) }
        is ChangeIntentWireDocument.AddDeclaration -> combineConverted(
            exactTarget.refineChangeProtocolText(),
            declaration.refineChangeProtocolText(),
        ) { target, declaration -> ChangeIntentDocument.AddDeclaration(target, declaration) }
        is ChangeIntentWireDocument.ReplaceDeclaration -> combineConverted(
            exactTarget.refineChangeProtocolText(),
            replacement.refineChangeProtocolText(),
        ) { target, replacement -> ChangeIntentDocument.ReplaceDeclaration(target, replacement) }
        is ChangeIntentWireDocument.RenameSymbol -> combineConverted(
            exactTarget.refineChangeProtocolText(),
            newName.refineChangeProtocolText(),
        ) { target, name -> ChangeIntentDocument.RenameSymbol(target, name) }
    }

internal fun ChangePlanResult.toSerializableDocument(): ChangePlanResultDocument =
    ChangePlanResultDocument(planIdentity.value)

/**
 * Proof transition: `ChangePlanResultDocument -> ChangePlanResult`.
 *
 * Establishes a refined plan identity. [WireDocumentConversion.Rejected] is the closed
 * expected failure. Raw result text may be extracted only in this wire adapter.
 */
internal fun ChangePlanResultDocument.toContract(): WireDocumentConversion<ChangePlanResult> =
    planIdentity.refineChangeProtocolText().mapConverted(::ChangePlanResult)

internal fun ChangePlanQualification.toSerializableDocument():
    ChangePlanQualificationDocument = when (this) {
    ChangePlanQualification.OPTIONAL_EVIDENCE_INCOMPLETE ->
        ChangePlanQualificationDocument.OPTIONAL_EVIDENCE_INCOMPLETE
}

internal fun ChangePlanQualificationDocument.toContract():
    WireDocumentConversion<ChangePlanQualification> = WireDocumentConversion.Converted(
    when (this) {
        ChangePlanQualificationDocument.OPTIONAL_EVIDENCE_INCOMPLETE ->
            ChangePlanQualification.OPTIONAL_EVIDENCE_INCOMPLETE
    },
)

internal fun ChangePlanRejection.toSerializableDocument(): ChangePlanRejectionDocument =
    when (this) {
        ChangePlanRejection.WORKSPACE_NOT_READY ->
            ChangePlanRejectionDocument.WORKSPACE_NOT_READY
        ChangePlanRejection.SYMBOL_RESOLVE_REQUIRED ->
            ChangePlanRejectionDocument.SYMBOL_RESOLVE_REQUIRED
        ChangePlanRejection.EDITABLE_TARGET_REQUIRED ->
            ChangePlanRejectionDocument.EDITABLE_TARGET_REQUIRED
        ChangePlanRejection.RELATION_READ_REQUIRED ->
            ChangePlanRejectionDocument.RELATION_READ_REQUIRED
        ChangePlanRejection.TOPOLOGY_BUILD_REQUIRED ->
            ChangePlanRejectionDocument.TOPOLOGY_BUILD_REQUIRED
        ChangePlanRejection.REQUIRED_TRAVERSAL_INCOMPLETE ->
            ChangePlanRejectionDocument.REQUIRED_TRAVERSAL_INCOMPLETE
        ChangePlanRejection.DIAGNOSTIC_CHECK_REQUIRED ->
            ChangePlanRejectionDocument.DIAGNOSTIC_CHECK_REQUIRED
        ChangePlanRejection.INTENT_REJECTED -> ChangePlanRejectionDocument.INTENT_REJECTED
    }

internal fun ChangePlanRejectionDocument.toContract(): WireDocumentConversion<ChangePlanRejection> =
    WireDocumentConversion.Converted(
        when (this) {
            ChangePlanRejectionDocument.WORKSPACE_NOT_READY ->
                ChangePlanRejection.WORKSPACE_NOT_READY
            ChangePlanRejectionDocument.SYMBOL_RESOLVE_REQUIRED ->
                ChangePlanRejection.SYMBOL_RESOLVE_REQUIRED
            ChangePlanRejectionDocument.EDITABLE_TARGET_REQUIRED ->
                ChangePlanRejection.EDITABLE_TARGET_REQUIRED
            ChangePlanRejectionDocument.RELATION_READ_REQUIRED ->
                ChangePlanRejection.RELATION_READ_REQUIRED
            ChangePlanRejectionDocument.TOPOLOGY_BUILD_REQUIRED ->
                ChangePlanRejection.TOPOLOGY_BUILD_REQUIRED
            ChangePlanRejectionDocument.REQUIRED_TRAVERSAL_INCOMPLETE ->
                ChangePlanRejection.REQUIRED_TRAVERSAL_INCOMPLETE
            ChangePlanRejectionDocument.DIAGNOSTIC_CHECK_REQUIRED ->
                ChangePlanRejection.DIAGNOSTIC_CHECK_REQUIRED
            ChangePlanRejectionDocument.INTENT_REJECTED -> ChangePlanRejection.INTENT_REJECTED
        },
    )

/**
 * Proof transition: `String -> WireDocumentConversion<ProtocolText>` for a generated field.
 *
 * Establishes bounded non-blank protocol text.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw strings may be
 * extracted only here.
 */
internal fun String.refineChangeProtocolText(): WireDocumentConversion<ProtocolText> =
    ProtocolText.parse(this).toWireDocumentConversion()
