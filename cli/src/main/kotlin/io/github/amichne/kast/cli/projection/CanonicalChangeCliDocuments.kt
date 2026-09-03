package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import kotlinx.serialization.Serializable

internal object CanonicalChangeCliDocuments {
    fun projectPlan(
        outcome: OperationOutcome<
            ChangePlanResult,
            ChangePlanQualification,
            ChangePlanRejection,
            >,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            planCompleteFactory.create(
                ChangePlanCompleteCliDocument(
                    CanonicalOperation.CHANGE_PLAN.id.value,
                    "complete",
                    result.planIdentity.value,
                ),
            )
        },
        qualified = { result, qualification ->
            planQualifiedFactory.create(
                ChangePlanQualifiedCliDocument(
                    CanonicalOperation.CHANGE_PLAN.id.value,
                    "qualified",
                    result.planIdentity.value,
                    qualification.cliName(),
                ),
            )
        },
        rejected = { rejection ->
            canonicalRejectedDocument(CanonicalOperation.CHANGE_PLAN, rejection.cliName())
        },
    )

    fun projectApplication(
        outcome: OperationOutcome<
            ChangeApplyResult,
            ChangeApplyQualification,
            ChangeApplyRejection,
            >,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            applicationCompleteFactory.create(
                ChangeApplyCompleteCliDocument(
                    CanonicalOperation.CHANGE_APPLY.id.value,
                    "complete",
                    result.receiptIdentity.value,
                ),
            )
        },
        qualified = { result, qualification ->
            applicationQualifiedFactory.create(
                ChangeApplyQualifiedCliDocument(
                    CanonicalOperation.CHANGE_APPLY.id.value,
                    "qualified",
                    result.receiptIdentity.value,
                    qualification.cliName(),
                ),
            )
        },
        rejected = { rejection ->
            canonicalRejectedDocument(CanonicalOperation.CHANGE_APPLY, rejection.cliName())
        },
    )

    fun projectRecovery(
        outcome: OperationOutcome<
            ChangeRecoverResult,
            ChangeRecoverQualification,
            ChangeRecoverRejection,
            >,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            recoveryCompleteFactory.create(
                ChangeRecoveryCompleteCliDocument(
                    CanonicalOperation.CHANGE_RECOVER.id.value,
                    "complete",
                    result.state.cliName(),
                ),
            )
        },
        qualified = { result, qualification ->
            recoveryQualifiedFactory.create(
                ChangeRecoveryQualifiedCliDocument(
                    CanonicalOperation.CHANGE_RECOVER.id.value,
                    "qualified",
                    result.state.cliName(),
                    qualification.cliName(),
                ),
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
)

@Serializable
private data class ChangePlanQualifiedCliDocument(
    val operation: String,
    val status: String,
    val planIdentity: String,
    val qualification: String,
)

@Serializable
private data class ChangeApplyCompleteCliDocument(
    val operation: String,
    val status: String,
    val receiptIdentity: String,
)

@Serializable
private data class ChangeApplyQualifiedCliDocument(
    val operation: String,
    val status: String,
    val receiptIdentity: String,
    val qualification: String,
)

@Serializable
private data class ChangeRecoveryCompleteCliDocument(
    val operation: String,
    val status: String,
    val state: String,
)

@Serializable
private data class ChangeRecoveryQualifiedCliDocument(
    val operation: String,
    val status: String,
    val state: String,
    val qualification: String,
)

private val planCompleteFactory =
    CliJsonDocument.generated(ChangePlanCompleteCliDocument.serializer())
private val planQualifiedFactory =
    CliJsonDocument.generated(ChangePlanQualifiedCliDocument.serializer())
private val applicationCompleteFactory =
    CliJsonDocument.generated(ChangeApplyCompleteCliDocument.serializer())
private val applicationQualifiedFactory =
    CliJsonDocument.generated(ChangeApplyQualifiedCliDocument.serializer())
private val recoveryCompleteFactory =
    CliJsonDocument.generated(ChangeRecoveryCompleteCliDocument.serializer())
private val recoveryQualifiedFactory =
    CliJsonDocument.generated(ChangeRecoveryQualifiedCliDocument.serializer())
