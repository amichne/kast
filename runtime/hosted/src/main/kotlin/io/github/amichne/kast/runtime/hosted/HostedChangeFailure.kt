package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveChangePlanLookup
import io.github.amichne.kast.change.contract.LiveChangePlanStoreFailure
import io.github.amichne.kast.change.verify.LiveChangeReceiptStoreFailure
import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocationFailure
import io.github.amichne.kast.evidence.contract.KastUserStateRootFailure
import io.github.amichne.kast.evidence.sqlite.SqliteHostedChangeStoresFailure
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournalOpenFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Failure detail is retained as closed data through loading, diagnostics and the response. */
@Serializable
internal sealed interface HostedChangeFailure {
    @Serializable
    @SerialName("STATE_ROOT")
    data class StateRoot(val cause: KastUserStateRootFailure) : HostedChangeFailure

    @Serializable
    @SerialName("LOCATION")
    data class Location(val cause: HostedWorkspaceStateLocationFailure) : HostedChangeFailure

    @Serializable
    @SerialName("DATABASE")
    data class Database(val cause: SqliteMutationRecoveryJournalOpenFailure) : HostedChangeFailure

    @Serializable @SerialName("PLANS") data class Plans(val cause: LiveChangePlanStoreFailure) : HostedChangeFailure

    @Serializable
    @SerialName("RECEIPTS")
    data class Receipts(val cause: LiveChangeReceiptStoreFailure) : HostedChangeFailure

    @Serializable
    @SerialName("PLAN_LOOKUP")
    data class PlanLookup(val cause: LiveChangePlanStoreFailure) : HostedChangeFailure

    @Serializable @SerialName("PLAN_MISSING") data object PlanMissing : HostedChangeFailure

    @Serializable @SerialName("ENDPOINT") data class Endpoint(val cause: HostedEndpointFailure) : HostedChangeFailure
}

internal fun SqliteHostedChangeStoresFailure.hosted(): HostedChangeFailure =
    when (this) {
        is SqliteHostedChangeStoresFailure.Database -> HostedChangeFailure.Database(cause)
        is SqliteHostedChangeStoresFailure.Plans -> HostedChangeFailure.Plans(cause)
        is SqliteHostedChangeStoresFailure.Receipts -> HostedChangeFailure.Receipts(cause)
    }

internal enum class HostedChangeStorageStage {
    OPEN,
    PLAN_LOOKUP,
}

@Serializable
internal sealed interface HostedChangeStorageObservation {
    @Serializable
    @SerialName("COMPLETED")
    data class Completed(val stage: HostedChangeStorageStage) : HostedChangeStorageObservation

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(val stage: HostedChangeStorageStage, val failure: HostedChangeFailure) :
        HostedChangeStorageObservation
}

internal fun interface HostedChangeStorageObserver {
    fun record(observation: HostedChangeStorageObservation)

    object Logger : HostedChangeStorageObserver {
        override fun record(observation: HostedChangeStorageObservation) {
            com.intellij.openapi.diagnostic.Logger.getInstance(HostedChangeResources::class.java)
                .info("kast_change_storage " + Json.encodeToString(observation))
        }
    }
}

internal fun admitLoadedHostedPlan(
    root: CanonicalWorkspaceRoot,
    loaded: LiveChangePlanLookup,
): Refinement<LiveAddDeclarationChangePlan, HostedChangeFailure> =
    when (loaded) {
        is LiveChangePlanLookup.Found ->
            if (loaded.plan.basis.observation.reference.workspaceRoot == root) Refinement.Refined(loaded.plan)
            else Refinement.Rejected(HostedChangeFailure.Endpoint(HostedEndpointFailure.WRONG_ROOT))
        LiveChangePlanLookup.Missing -> Refinement.Rejected(HostedChangeFailure.PlanMissing)
        is LiveChangePlanLookup.Rejected -> Refinement.Rejected(HostedChangeFailure.PlanLookup(loaded.failure))
    }

internal fun <Value> Refinement<Value, HostedChangeFailure>.observed(
    stage: HostedChangeStorageStage,
    observer: HostedChangeStorageObserver,
): Refinement<Value, HostedChangeFailure> = also {
    observer.record(
        when (this) {
            is Refinement.Refined -> HostedChangeStorageObservation.Completed(stage)
            is Refinement.Rejected -> HostedChangeStorageObservation.Rejected(stage, failure)
        }
    )
}

@Serializable
internal data class HostedChangeRejectionDocument(
    val detail: HostedChangeFailure,
    val failure: HostedEndpointFailure =
        when (detail) {
            is HostedChangeFailure.Endpoint -> detail.cause
            HostedChangeFailure.PlanMissing -> HostedEndpointFailure.INVALID_REQUEST
            else -> HostedEndpointFailure.CHANGE_STORAGE_REJECTED
        },
    val type: String = "HOST_REJECTED",
)
