package io.github.amichne.kast.api.contract.selector

import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.Symbol
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

fun SymbolKind.selectorOperationFamilies(): Set<SelectorOperationFamily> = when (this) {
    SymbolKind.CLASS, SymbolKind.INTERFACE -> setOf(
        SelectorOperationFamily.IDENTITY,
        SelectorOperationFamily.REFERENCES,
        SelectorOperationFamily.IMPLEMENTATIONS,
        SelectorOperationFamily.HIERARCHY,
        SelectorOperationFamily.IMPACT,
        SelectorOperationFamily.RENAME,
    )
    SymbolKind.OBJECT -> setOf(
        SelectorOperationFamily.IDENTITY,
        SelectorOperationFamily.REFERENCES,
        SelectorOperationFamily.HIERARCHY,
        SelectorOperationFamily.IMPACT,
        SelectorOperationFamily.RENAME,
    )
    SymbolKind.FUNCTION -> setOf(
        SelectorOperationFamily.IDENTITY,
        SelectorOperationFamily.REFERENCES,
        SelectorOperationFamily.CALLERS,
        SelectorOperationFamily.CALLEES,
        SelectorOperationFamily.IMPACT,
        SelectorOperationFamily.RENAME,
        SelectorOperationFamily.REPLACE_DECLARATION,
    )
    SymbolKind.PROPERTY -> setOf(
        SelectorOperationFamily.IDENTITY,
        SelectorOperationFamily.REFERENCES,
        SelectorOperationFamily.IMPACT,
        SelectorOperationFamily.RENAME,
        SelectorOperationFamily.REPLACE_DECLARATION,
    )
    SymbolKind.PARAMETER -> setOf(
        SelectorOperationFamily.IDENTITY,
        SelectorOperationFamily.REFERENCES,
        SelectorOperationFamily.RENAME,
    )
    SymbolKind.UNKNOWN -> emptySet()
}

fun Symbol.toExactSelector(): KastExactSymbolSelector = KastExactSymbolSelector(
    fqName = fqName,
    declarationFile = location.filePath,
    declarationStartOffset = location.startOffset,
    kind = kind,
    containingType = containingDeclaration,
)
