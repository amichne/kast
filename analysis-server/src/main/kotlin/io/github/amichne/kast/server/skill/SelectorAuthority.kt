package io.github.amichne.kast.server.skill

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.server.PublicSymbolReadBinding
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
    declarationStartOffset = NonNegativeInt(location.startOffset),
    containingType = containingDeclaration,
)

internal fun SkillRpcContext.issueSelectorHandle(symbol: Symbol): String =
    when (
        val issued = selectorHandleAuthority().issue(
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

internal fun SkillRpcContext.selectorHandleAuthority(): SelectorHandleAuthority =
    when (val binding = publicSymbolReads) {
        PublicSymbolReadBinding.LegacyAnalysisBackend -> backend.selectorHandles
        is PublicSymbolReadBinding.Native -> binding.selectorHandles
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
                val resolution = selectorHandleAuthority().resolve(
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

internal fun KastExactSymbolSelector.matches(actual: SymbolIdentity): Boolean =
    fqName == actual.fqName &&
    NormalizedPath.parse(declarationFile) == actual.declarationFile &&
    declarationStartOffset == actual.declarationStartOffset.value &&
    (kind == null || kind == actual.kind) &&
    (containingType == null || containingType == actual.containingType)

internal suspend fun SkillRpcContext.workspaceRootFor(explicit: String?): String =
    explicit?.takeIf(String::isNotBlank)?.normalizedAbsolutePath()
    ?: when (val binding = publicSymbolReads) {
        PublicSymbolReadBinding.LegacyAnalysisBackend -> backend.runtimeStatus().workspaceRoot
        is PublicSymbolReadBinding.Native -> binding.workspaceRoot.value
    }

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
