package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyUnverifiedReason
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal enum class NativeObservedChangeOperation {
    PLAN,
    APPLY,
    RECOVER,
    HOST_REJECTED,
    UNCLASSIFIED,
}

@Serializable
internal enum class NativeObservedChangeStatus {
    COMPLETE,
    QUALIFIED,
    REJECTED,
    ABSENT,
    UNCLASSIFIED,
}

@Serializable
internal enum class NativeObservedChangeState {
    VERIFIED,
    APPLIED_UNVERIFIED,
    RECOVERY_REQUIRED,
    PRIOR_STATE,
    ROLLED_BACK,
    ABSENT,
    UNCLASSIFIED,
}

@Serializable
internal enum class NativeObservedHostFailure {
    APPROVAL_REJECTED,
    UNCLASSIFIED,
}

@Serializable
internal sealed interface NativeObservedChangeReason {
    @Serializable
    @SerialName("apply-recovery")
    data class ApplyRecovery(val value: ChangeApplyRecoveryReason) : NativeObservedChangeReason

    @Serializable
    @SerialName("apply-unverified")
    data class ApplyUnverified(val value: ChangeApplyUnverifiedReason) : NativeObservedChangeReason

    @Serializable
    @SerialName("apply-rejection")
    data class ApplyRejection(val value: ChangeApplyRejection) : NativeObservedChangeReason

    @Serializable
    @SerialName("plan-rejection")
    data class PlanRejection(val value: ChangePlanRejection) : NativeObservedChangeReason

    @Serializable
    @SerialName("recover-rejection")
    data class RecoverRejection(val value: ChangeRecoverRejection) : NativeObservedChangeReason

    @Serializable
    @SerialName("host-rejection")
    data class HostRejection(val value: NativeObservedHostFailure) : NativeObservedChangeReason

    @Serializable @SerialName("absent") data object Absent : NativeObservedChangeReason

    @Serializable @SerialName("unclassified") data object Unclassified : NativeObservedChangeReason
}

@Serializable
internal data class NativeSemanticChangeObservation(
    val operation: NativeObservedChangeOperation,
    val status: NativeObservedChangeStatus,
    val state: NativeObservedChangeState,
    val reason: NativeObservedChangeReason,
)

internal fun nativeSemanticChangeObservation(document: JsonObject): NativeSemanticChangeObservation {
    val operation =
        when {
            document["type"] == JsonPrimitive("HOST_REJECTED") -> NativeObservedChangeOperation.HOST_REJECTED
            document["operation"] == JsonPrimitive("change.plan") -> NativeObservedChangeOperation.PLAN
            document["operation"] == JsonPrimitive("change.apply") -> NativeObservedChangeOperation.APPLY
            document["operation"] == JsonPrimitive("change.recover") -> NativeObservedChangeOperation.RECOVER
            else -> NativeObservedChangeOperation.UNCLASSIFIED
        }
    val status =
        when (document["status"]) {
            null -> NativeObservedChangeStatus.ABSENT
            JsonPrimitive("complete") -> NativeObservedChangeStatus.COMPLETE
            JsonPrimitive("qualified") -> NativeObservedChangeStatus.QUALIFIED
            JsonPrimitive("rejected") -> NativeObservedChangeStatus.REJECTED
            else -> NativeObservedChangeStatus.UNCLASSIFIED
        }
    val state = observedChangeState(operation, document)
    return NativeSemanticChangeObservation(
        operation = operation,
        status = status,
        state = state,
        reason = observedChangeReason(operation, state, document),
    )
}

private fun observedChangeState(
    operation: NativeObservedChangeOperation,
    document: JsonObject,
): NativeObservedChangeState {
    val raw = document["state"] ?: return NativeObservedChangeState.ABSENT
    return when (operation) {
        NativeObservedChangeOperation.APPLY ->
            when (raw) {
                JsonPrimitive("verified") -> NativeObservedChangeState.VERIFIED
                JsonPrimitive("applied_unverified") -> NativeObservedChangeState.APPLIED_UNVERIFIED
                JsonPrimitive("recovery_required") -> NativeObservedChangeState.RECOVERY_REQUIRED
                else -> NativeObservedChangeState.UNCLASSIFIED
            }
        NativeObservedChangeOperation.RECOVER ->
            when (raw) {
                JsonPrimitive("recovery-required") -> NativeObservedChangeState.RECOVERY_REQUIRED
                JsonPrimitive("prior-state") -> NativeObservedChangeState.PRIOR_STATE
                JsonPrimitive("rolled-back") -> NativeObservedChangeState.ROLLED_BACK
                else -> NativeObservedChangeState.UNCLASSIFIED
            }
        else -> NativeObservedChangeState.UNCLASSIFIED
    }
}

private fun observedChangeReason(
    operation: NativeObservedChangeOperation,
    state: NativeObservedChangeState,
    document: JsonObject,
): NativeObservedChangeReason {
    if (operation == NativeObservedChangeOperation.HOST_REJECTED)
        return NativeObservedChangeReason.HostRejection(
            if (document["failure"] == JsonPrimitive("APPROVAL_REJECTED")) NativeObservedHostFailure.APPROVAL_REJECTED
            else NativeObservedHostFailure.UNCLASSIFIED
        )
    val field = document["reason"] ?: return NativeObservedChangeReason.Absent
    val raw =
        (field as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return NativeObservedChangeReason.Unclassified
    return when (operation) {
        NativeObservedChangeOperation.APPLY -> observedApplyReason(state, raw)
        NativeObservedChangeOperation.PLAN ->
            cliEnum<ChangePlanRejection>(raw)?.let { NativeObservedChangeReason.PlanRejection(it) }
        NativeObservedChangeOperation.RECOVER ->
            cliEnum<ChangeRecoverRejection>(raw)?.let { NativeObservedChangeReason.RecoverRejection(it) }
        else -> null
    } ?: NativeObservedChangeReason.Unclassified
}

private fun observedApplyReason(state: NativeObservedChangeState, raw: String): NativeObservedChangeReason =
    when (state) {
        NativeObservedChangeState.APPLIED_UNVERIFIED ->
            cliEnum<ChangeApplyUnverifiedReason>(raw)?.let { NativeObservedChangeReason.ApplyUnverified(it) }
        NativeObservedChangeState.RECOVERY_REQUIRED ->
            cliEnum<ChangeApplyRecoveryReason>(raw)?.let { NativeObservedChangeReason.ApplyRecovery(it) }
        else -> cliEnum<ChangeApplyRejection>(raw)?.let { NativeObservedChangeReason.ApplyRejection(it) }
    } ?: NativeObservedChangeReason.Unclassified

private inline fun <reified E : Enum<E>> cliEnum(raw: String): E? =
    enumValues<E>().firstOrNull { it.name.lowercase().replace('_', '-') == raw }
