package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeFilePreview
import io.github.amichne.kast.protocol.contract.ChangeFilePreviewKind
import io.github.amichne.kast.protocol.contract.ChangeFilePreviewSet
import io.github.amichne.kast.protocol.contract.ChangePreviewDiff
import io.github.amichne.kast.protocol.contract.ChangePreviewPath
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
import io.github.amichne.kast.protocol.contract.ProtocolText

internal fun ChangeApplyResult.toSerializableDocument(): ChangeApplyResultDocument =
    ChangeApplyResultDocument(
        receiptIdentity.value,
        changes.entries.map(ChangeFilePreview::toSerializableDocument),
    )

/**
 * Proof transition: `ChangeApplyResultDocument -> ChangeApplyResult`.
 *
 * Establishes a refined verified receipt identity. [WireDocumentConversion.Rejected] is the
 * closed expected failure. Raw result text may be extracted only here.
 */
internal fun ChangeApplyResultDocument.toContract(): WireDocumentConversion<ChangeApplyResult> =
    combineConverted(
        receiptIdentity.refineChangeProtocolText(),
        changes.toContract(),
        ::ChangeApplyResult,
    )

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
        ChangeApplyRejection.RESULTING_GENERATION_UNAVAILABLE ->
            ChangeApplyRejectionDocument.RESULTING_GENERATION_UNAVAILABLE
        ChangeApplyRejection.OBLIGATION_FAILED -> ChangeApplyRejectionDocument.OBLIGATION_FAILED
        ChangeApplyRejection.DIAGNOSTIC_REGRESSION ->
            ChangeApplyRejectionDocument.DIAGNOSTIC_REGRESSION
        ChangeApplyRejection.SEMANTIC_DELTA_REJECTED ->
            ChangeApplyRejectionDocument.SEMANTIC_DELTA_REJECTED
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
            ChangeApplyRejectionDocument.RESULTING_GENERATION_UNAVAILABLE ->
                ChangeApplyRejection.RESULTING_GENERATION_UNAVAILABLE
            ChangeApplyRejectionDocument.OBLIGATION_FAILED ->
                ChangeApplyRejection.OBLIGATION_FAILED
            ChangeApplyRejectionDocument.DIAGNOSTIC_REGRESSION ->
                ChangeApplyRejection.DIAGNOSTIC_REGRESSION
            ChangeApplyRejectionDocument.SEMANTIC_DELTA_REJECTED ->
                ChangeApplyRejection.SEMANTIC_DELTA_REJECTED
        },
    )

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

internal fun ChangePlanResult.toSerializableDocument(): ChangePlanResultDocument =
    ChangePlanResultDocument(
        planIdentity.value,
        changes.entries.map(ChangeFilePreview::toSerializableDocument),
    )

/**
 * Proof transition: `ChangePlanResultDocument -> ChangePlanResult`.
 *
 * Establishes a refined plan identity. [WireDocumentConversion.Rejected] is the closed
 * expected failure. Raw result text may be extracted only in this wire adapter.
 */
internal fun ChangePlanResultDocument.toContract(): WireDocumentConversion<ChangePlanResult> =
    combineConverted(
        planIdentity.refineChangeProtocolText(),
        changes.toContract(),
        ::ChangePlanResult,
    )

private fun ChangeFilePreview.toSerializableDocument(): ChangeFilePreviewDocument =
    ChangeFilePreviewDocument(path.value, kind.toSerializableDocument(), diff.value)

private fun ChangeFilePreviewKind.toSerializableDocument(): ChangeFilePreviewKindDocument =
    when (this) {
        ChangeFilePreviewKind.ADD -> ChangeFilePreviewKindDocument.ADD
        ChangeFilePreviewKind.DELETE -> ChangeFilePreviewKindDocument.DELETE
        ChangeFilePreviewKind.UPDATE -> ChangeFilePreviewKindDocument.UPDATE
    }

private fun ChangeFilePreviewDocument.toContract(): WireDocumentConversion<ChangeFilePreview> =
    combineConverted(
        ChangePreviewPath.parse(path).toWireDocumentConversion(),
        ChangePreviewDiff.parse(diff).toWireDocumentConversion(),
    ) { admittedPath, admittedDiff ->
        ChangeFilePreview(admittedPath, kind.toContract(), admittedDiff)
    }

private fun ChangeFilePreviewKindDocument.toContract(): ChangeFilePreviewKind = when (this) {
    ChangeFilePreviewKindDocument.ADD -> ChangeFilePreviewKind.ADD
    ChangeFilePreviewKindDocument.DELETE -> ChangeFilePreviewKind.DELETE
    ChangeFilePreviewKindDocument.UPDATE -> ChangeFilePreviewKind.UPDATE
}

private fun List<ChangeFilePreviewDocument>.toContract():
    WireDocumentConversion<ChangeFilePreviewSet> = convertEach(ChangeFilePreviewDocument::toContract)
    .flatMapConverted { changes ->
        ChangeFilePreviewSet.admit(changes).toWireDocumentConversion()
    }

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
        ChangePlanRejection.EXACT_SYMBOL_REQUIRED ->
            ChangePlanRejectionDocument.EXACT_SYMBOL_REQUIRED
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
        ChangePlanRejection.RECOVERY_REQUIRED ->
            ChangePlanRejectionDocument.RECOVERY_REQUIRED
        ChangePlanRejection.INTENT_REJECTED -> ChangePlanRejectionDocument.INTENT_REJECTED
    }

internal fun ChangePlanRejectionDocument.toContract(): WireDocumentConversion<ChangePlanRejection> =
    WireDocumentConversion.Converted(
        when (this) {
            ChangePlanRejectionDocument.WORKSPACE_NOT_READY ->
                ChangePlanRejection.WORKSPACE_NOT_READY
            ChangePlanRejectionDocument.EXACT_SYMBOL_REQUIRED ->
                ChangePlanRejection.EXACT_SYMBOL_REQUIRED
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
            ChangePlanRejectionDocument.RECOVERY_REQUIRED ->
                ChangePlanRejection.RECOVERY_REQUIRED
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
