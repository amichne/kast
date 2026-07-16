package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.SelectorHandleRecovery
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("SELECTOR_HANDLE_REJECTED")
data class KastSelectorHandleRejectedResponse(
    val reason: SelectorHandleAuthority.Resolution.RejectionReason,
    val recovery: SelectorHandleRecovery = SelectorHandleRecovery.forReason(reason),
) : KastReferencesResponse,
    KastCallersResponse,
    KastImplementationsResponse,
    KastHierarchyResponse,
    KastSelectorIdentityResponse {
    init {
        require(recovery == SelectorHandleRecovery.forReason(reason)) {
            "Selector handle recovery must match its rejection reason"
        }
    }
}
