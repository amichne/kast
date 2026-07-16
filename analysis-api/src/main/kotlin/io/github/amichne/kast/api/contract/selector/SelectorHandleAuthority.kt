package io.github.amichne.kast.api.contract.selector

import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import kotlinx.serialization.Serializable

interface SelectorHandleAuthority {
    fun issue(
        selector: KastExactSymbolSelector,
        allowedFamilies: Set<SelectorOperationFamily>,
    ): IssueResult

    fun resolve(
        handle: String,
        workspaceRoot: String,
        family: SelectorOperationFamily,
    ): Resolution

    sealed interface IssueResult {
        data class Issued(val handle: SelectorHandle) : IssueResult

        data object Unavailable : IssueResult
    }

    sealed interface Resolution {
        data class Resolved(val selector: KastExactSymbolSelector) : Resolution

        data class Rejected(val reason: RejectionReason) : Resolution

        @Serializable
        enum class RejectionReason {
            TAMPERED,
            WRONG_WORKSPACE,
            WRONG_BACKEND,
            STALE,
            FAMILY_NOT_ALLOWED,
            UNAVAILABLE,
        }
    }

    data object Unsupported : SelectorHandleAuthority {
        override fun issue(
            selector: KastExactSymbolSelector,
            allowedFamilies: Set<SelectorOperationFamily>,
        ): IssueResult = IssueResult.Unavailable

        override fun resolve(
            handle: String,
            workspaceRoot: String,
            family: SelectorOperationFamily,
        ): Resolution = Resolution.Rejected(Resolution.RejectionReason.UNAVAILABLE)
    }
}
