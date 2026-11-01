package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal fun reviewedEntryProperty(
    target: CompilerGroundedSymbolEvidence,
    file: SymbolDiscoveryFileIdentity.External,
    signature: CanonicalCompilerSignature.Property,
): Refinement<ReviewedLibraryOperation, ReviewedCallbackValueFailure> {
    if (
        target.kind != CompilerSymbolKind.PROPERTY ||
            !file.url.value.endsWith("!/kotlin/collections/collections.kotlin_builtins")
    )
        return entryMismatch()
    if (signature.receiver != CanonicalCompilerReceiver.Absent || signature.contextReceivers.isNotEmpty())
        return entryMismatch()
    return when {
        signature.qualifiedIdentity.value == "kotlin.collections.Map.Entry.key" && signature.returnType.value == "K" ->
            Refinement.Refined(ReviewedLibraryOperation.MAP_ENTRY_KEY)
        signature.qualifiedIdentity.value == "kotlin.collections.Map.Entry.value" &&
            signature.returnType.value == "V" -> Refinement.Refined(ReviewedLibraryOperation.MAP_ENTRY_VALUE)
        else -> entryMismatch()
    }
}

internal fun reviewedEntryComponent(
    target: CompilerGroundedSymbolEvidence,
    file: SymbolDiscoveryFileIdentity.External,
    signature: CanonicalCompilerSignature.Function,
): Refinement<ReviewedLibraryOperation, ReviewedCallbackValueFailure> {
    if (target.kind != CompilerSymbolKind.FUNCTION || !file.url.value.endsWith("!/kotlin/collections/MapsKt.class"))
        return entryMismatch()
    if ((signature.receiver as? CanonicalCompilerReceiver.Present)?.type?.value != "kotlin/collections/Map.Entry<K,V>")
        return entryMismatch()
    if (
        signature.valueParameters.isNotEmpty() ||
            signature.typeParameterCount.value != 2 ||
            signature.contextReceivers.isNotEmpty()
    )
        return entryMismatch()
    return when (signature.qualifiedIdentity.value) {
        "kotlin.collections.component1" -> Refinement.Refined(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1)
        "kotlin.collections.component2" -> Refinement.Refined(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2)
        else -> entryMismatch()
    }
}

private fun entryMismatch() = Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
