@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeFilePreview
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import kotlinx.serialization.Serializable

object CanonicalChangeCliDocuments {
    fun projectPlan(
        outcome:
            OperationOutcome<
                ChangePlanResult,
                ChangePlanQualification,
                ChangePlanRejection,
            >
    ) =
        projectClosedOutcome(
            outcome,
            complete = { result, live ->
                planCompleteFactory.create(
                    ChangePlanCompleteCliDocument(
                        CanonicalOperation.CHANGE_PLAN.id.value,
                        "complete",
                        result.planIdentity.value,
                        result.changes.entries.map(ChangeFilePreview::cliDocument),
                        live = live,
                    )
                )
            },
            qualified = { result, qualification, live ->
                planQualifiedFactory.create(
                    ChangePlanQualifiedCliDocument(
                        CanonicalOperation.CHANGE_PLAN.id.value,
                        "qualified",
                        result.planIdentity.value,
                        result.changes.entries.map(ChangeFilePreview::cliDocument),
                        qualification.cliName(),
                        live = live,
                    )
                )
            },
            rejected = { rejection ->
                canonicalRejectedDocument(CanonicalOperation.CHANGE_PLAN, rejection.cliName())
            },
        )

    fun projectApplication(
        outcome:
            OperationOutcome<
                ChangeApplyResult,
                ChangeApplyQualification,
                ChangeApplyRejection,
            >
    ) =
        projectClosedOutcome(
            outcome,
            complete = { result, live -> applicationDocument(result, ApplicationStatus.COMPLETE, null, live) },
            qualified = { result, qualification, live ->
                applicationDocument(result, ApplicationStatus.QUALIFIED, qualification.cliName(), live)
            },
            rejected = { rejection ->
                canonicalRejectedDocument(CanonicalOperation.CHANGE_APPLY, rejection.cliName())
            },
        )

    fun projectRecovery(
        outcome:
            OperationOutcome<
                ChangeRecoverResult,
                ChangeRecoverQualification,
                ChangeRecoverRejection,
            >
    ) =
        projectClosedOutcome(
            outcome,
            complete = { result, live ->
                recoveryCompleteFactory.create(
                    ChangeRecoveryCompleteCliDocument(
                        CanonicalOperation.CHANGE_RECOVER.id.value,
                        "complete",
                        result.state.cliName(),
                        live = live,
                    )
                )
            },
            qualified = { result, qualification, live ->
                recoveryQualifiedFactory.create(
                    ChangeRecoveryQualifiedCliDocument(
                        CanonicalOperation.CHANGE_RECOVER.id.value,
                        "qualified",
                        result.state.cliName(),
                        qualification.cliName(),
                        live = live,
                    )
                )
            },
            rejected = { rejection ->
                canonicalRejectedDocument(CanonicalOperation.CHANGE_RECOVER, rejection.cliName())
            },
        )
}

@Serializable
private data class ChangePlanCompleteCliDocument(
    val operation: String,
    val status: String,
    val planIdentity: String,
    val changes: List<ChangeFilePreviewCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
private data class ChangePlanQualifiedCliDocument(
    val operation: String,
    val status: String,
    val planIdentity: String,
    val changes: List<ChangeFilePreviewCliDocument>,
    val qualification: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

/** Projection preserves the finite write effect and never invents a receipt for an unverified write. */
@Serializable
private enum class ApplicationStatus {
    @kotlinx.serialization.SerialName("complete") COMPLETE,
    @kotlinx.serialization.SerialName("qualified") QUALIFIED,
}

private fun applicationDocument(
    result: ChangeApplyResult,
    status: ApplicationStatus,
    qualification: String?,
    live: LiveReadCliEvidence?,
): CanonicalJsonDocument {
    val changes = result.changes.entries.map(ChangeFilePreview::cliDocument)
    return when (result) {
        is ChangeApplyResult.Verified ->
            CanonicalJsonDocument.generated(VerifiedApplicationDocument.serializer())
                .create(VerifiedApplicationDocument(status, result.receiptIdentity.value, changes, qualification, live))
        is ChangeApplyResult.AppliedUnverified ->
            CanonicalJsonDocument.generated(UnverifiedApplicationDocument.serializer())
                .create(
                    UnverifiedApplicationDocument(
                        status,
                        result.planIdentity.value,
                        result.reason.cliName(),
                        changes,
                        qualification,
                        live,
                    )
                )
        is ChangeApplyResult.RecoveryRequired ->
            CanonicalJsonDocument.generated(RecoveryApplicationDocument.serializer())
                .create(
                    RecoveryApplicationDocument(
                        status,
                        result.planIdentity.value,
                        result.reason.cliName(),
                        changes,
                        qualification,
                        live,
                    )
                )
    }
}

@Serializable
private data class VerifiedApplicationDocument(
    val status: ApplicationStatus,
    val receiptIdentity: String,
    val changes: List<ChangeFilePreviewCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val qualification: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
    val operation: String = "change.apply",
    val state: String = "verified",
)

@Serializable
private data class UnverifiedApplicationDocument(
    val status: ApplicationStatus,
    val planIdentity: String,
    val reason: String,
    val changes: List<ChangeFilePreviewCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val qualification: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
    val operation: String = "change.apply",
    val state: String = "applied_unverified",
)

@Serializable
private data class RecoveryApplicationDocument(
    val status: ApplicationStatus,
    val planIdentity: String,
    val reason: String,
    val changes: List<ChangeFilePreviewCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val qualification: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
    val operation: String = "change.apply",
    val state: String = "recovery_required",
)

@Serializable
private data class ChangeFilePreviewCliDocument(
    val path: String,
    val kind: String,
    val diff: String,
)

private fun ChangeFilePreview.cliDocument(): ChangeFilePreviewCliDocument =
    ChangeFilePreviewCliDocument(path.value, kind.name.lowercase(), diff.value)

@Serializable
private data class ChangeRecoveryCompleteCliDocument(
    val operation: String,
    val status: String,
    val state: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
private data class ChangeRecoveryQualifiedCliDocument(
    val operation: String,
    val status: String,
    val state: String,
    val qualification: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

private val planCompleteFactory = CanonicalJsonDocument.generated(ChangePlanCompleteCliDocument.serializer())
private val planQualifiedFactory = CanonicalJsonDocument.generated(ChangePlanQualifiedCliDocument.serializer())
private val recoveryCompleteFactory = CanonicalJsonDocument.generated(ChangeRecoveryCompleteCliDocument.serializer())
private val recoveryQualifiedFactory = CanonicalJsonDocument.generated(ChangeRecoveryQualifiedCliDocument.serializer())
