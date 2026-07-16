package io.github.amichne.kast.api.contract.selector

import kotlinx.serialization.Serializable

@Serializable
enum class SelectorHandleRecovery {
    RESOLVE_AGAIN,
    RESOLVE_IN_CURRENT_WORKSPACE,
    RESOLVE_WITH_ACTIVE_BACKEND,
    CHOOSE_COMPATIBLE_OPERATION,
    USE_EXPLICIT_SELECTOR,
    ;

    companion object {
        fun forReason(
            reason: SelectorHandleAuthority.Resolution.RejectionReason,
        ): SelectorHandleRecovery = when (reason) {
            SelectorHandleAuthority.Resolution.RejectionReason.TAMPERED -> RESOLVE_AGAIN
            SelectorHandleAuthority.Resolution.RejectionReason.WRONG_WORKSPACE -> RESOLVE_IN_CURRENT_WORKSPACE
            SelectorHandleAuthority.Resolution.RejectionReason.WRONG_BACKEND -> RESOLVE_WITH_ACTIVE_BACKEND
            SelectorHandleAuthority.Resolution.RejectionReason.STALE -> RESOLVE_AGAIN
            SelectorHandleAuthority.Resolution.RejectionReason.FAMILY_NOT_ALLOWED -> CHOOSE_COMPATIBLE_OPERATION
            SelectorHandleAuthority.Resolution.RejectionReason.UNAVAILABLE -> USE_EXPLICIT_SELECTOR
        }
    }
}
