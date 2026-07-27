package io.github.amichne.kast.server.skill

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.nio.file.Files
import java.nio.file.Path

internal fun KastExactSymbolSelector.normalizedFor(
    workspaceRoot: String,
): KastExactSymbolSelector {
    val input = Path.of(declarationFile)
    val normalized = if (input.isAbsolute) {
        input.toAbsolutePath().normalize()
    } else {
        Path.of(workspaceRoot).resolve(input).toAbsolutePath().normalize()
    }
    return copy(declarationFile = normalized.toString())
}

internal fun Symbol.toSymbolIdentity(): SymbolIdentity = SymbolIdentity(
    fqName = fqName,
    kind = kind,
    declarationFile = NormalizedPath.parse(location.filePath),
    declarationStartOffset = io.github.amichne.kast.api.contract.NonNegativeInt(location.startOffset),
    containingType = containingDeclaration,
)

internal fun Symbol.toExactSelector(): KastExactSymbolSelector = KastExactSymbolSelector(
    fqName = fqName,
    declarationFile = location.filePath,
    declarationStartOffset = location.startOffset,
    kind = kind,
    containingType = containingDeclaration,
)

internal fun SkillRpcContext.issueSelectorHandle(symbol: Symbol): String =
    when (
        val issued = backend.selectorHandles.issue(
            selector = symbol.toExactSelector(),
            allowedFamilies = symbol.kind.selectorOperationFamilies(),
        )
    ) {
        is SelectorHandleAuthority.IssueResult.Issued -> issued.handle.value
        SelectorHandleAuthority.IssueResult.Unavailable -> throw CapabilityNotSupportedException(
            capability = "SELECTOR_HANDLES",
            message = "The semantic backend cannot issue reusable selector handles",
        )
    }

internal fun SkillRpcContext.selectSelector(
    explicitSelector: KastExactSymbolSelector?,
    selectorHandle: String?,
    workspaceRoot: String,
    family: SelectorOperationFamily,
): SelectorSelection {
    return when {
        explicitSelector != null && selectorHandle == null ->
            SelectorSelection.Explicit(explicitSelector.normalizedFor(workspaceRoot))
        explicitSelector == null && selectorHandle != null -> {
            when (
                val resolution = backend.selectorHandles.resolve(
                    handle = selectorHandle,
                    workspaceRoot = workspaceRoot,
                    family = family,
                )
            ) {
                is SelectorHandleAuthority.Resolution.Resolved ->
                    SelectorSelection.Handle(resolution.selector.normalizedFor(workspaceRoot))
                is SelectorHandleAuthority.Resolution.Rejected ->
                    SelectorSelection.Rejected(resolution.reason)
            }
        }
        else -> throw ValidationException(
            "Provide exactly one of selector or selectorHandle",
        )
    }
}

internal fun KastExactSymbolSelector.toHandleSubject(): SymbolIdentity = SymbolIdentity(
    fqName = fqName,
    kind = kind ?: throw ValidationException("Backend-issued selector handle omitted kind"),
    declarationFile = NormalizedPath.parse(declarationFile),
    declarationStartOffset = NonNegativeInt(declarationStartOffset),
    containingType = containingType,
)

internal fun SymbolKind.selectorOperationFamilies(): Set<SelectorOperationFamily> = when (this) {
    SymbolKind.CLASS, SymbolKind.INTERFACE -> setOf(
        SelectorOperationFamily.REFERENCES,
        SelectorOperationFamily.IMPLEMENTATIONS,
        SelectorOperationFamily.HIERARCHY,
        SelectorOperationFamily.IMPACT,
        SelectorOperationFamily.RENAME,
        SelectorOperationFamily.REPLACE_DECLARATION,
    )
    SymbolKind.OBJECT -> setOf(
        SelectorOperationFamily.REFERENCES,
        SelectorOperationFamily.HIERARCHY,
        SelectorOperationFamily.IMPACT,
        SelectorOperationFamily.RENAME,
        SelectorOperationFamily.REPLACE_DECLARATION,
    )
    SymbolKind.FUNCTION -> setOf(
        SelectorOperationFamily.REFERENCES,
        SelectorOperationFamily.CALLERS,
        SelectorOperationFamily.CALLEES,
        SelectorOperationFamily.IMPACT,
        SelectorOperationFamily.RENAME,
        SelectorOperationFamily.REPLACE_DECLARATION,
    )
    SymbolKind.PROPERTY -> setOf(
        SelectorOperationFamily.REFERENCES,
        SelectorOperationFamily.IMPACT,
        SelectorOperationFamily.RENAME,
        SelectorOperationFamily.REPLACE_DECLARATION,
    )
    SymbolKind.PARAMETER -> setOf(
        SelectorOperationFamily.REFERENCES,
        SelectorOperationFamily.RENAME,
    )
    SymbolKind.UNKNOWN -> emptySet()
}

internal fun KastExactSymbolSelector.matches(actual: SymbolIdentity): Boolean =
    fqName == actual.fqName &&
        NormalizedPath.parse(declarationFile) == actual.declarationFile &&
        declarationStartOffset == actual.declarationStartOffset.value &&
        (kind == null || kind == actual.kind) &&
        (containingType == null || containingType == actual.containingType)

internal suspend fun SkillRpcContext.workspaceRootFor(explicit: String?): String =
    explicit?.takeIf(String::isNotBlank)?.normalizedAbsolutePath() ?: backend.runtimeStatus().workspaceRoot

internal suspend fun SkillRpcContext.requireReadCapability(capability: ReadCapability) {
    requireCapabilities(readCapabilities = setOf(capability))
}

internal suspend fun SkillRpcContext.requireMutationCapability(capability: MutationCapability) {
    requireCapabilities(mutationCapabilities = setOf(capability))
}

internal suspend fun SkillRpcContext.requireCapabilities(
    readCapabilities: Set<ReadCapability> = emptySet(),
    mutationCapabilities: Set<MutationCapability> = emptySet(),
) {
    val capabilities = backend.capabilities()
    val missingReadCapability = readCapabilities.firstOrNull { capability ->
        capability !in capabilities.readCapabilities
    }
    if (missingReadCapability != null) {
        throw CapabilityNotSupportedException(
            capability = missingReadCapability.name,
            message = "The backend does not advertise $missingReadCapability",
        )
    }
    val missingMutationCapability = mutationCapabilities.firstOrNull { capability ->
        capability !in capabilities.mutationCapabilities
    }
    if (missingMutationCapability != null) {
        throw CapabilityNotSupportedException(
            capability = missingMutationCapability.name,
            message = "The backend does not advertise $missingMutationCapability",
        )
    }
}


internal fun String.normalizedAbsolutePath(): String =
    Path.of(this).toAbsolutePath().normalize().toString()

private fun WrapperCallDirection.toCallDirection(): CallDirection = when (this) {
    WrapperCallDirection.INCOMING -> CallDirection.INCOMING
    WrapperCallDirection.OUTGOING -> CallDirection.OUTGOING
}
