package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature

internal fun ImpactFlowObligationEvidenceDocument.StringConversion.admitsConversion(
    site: ImpactValueSiteReferenceDocument
): Boolean {
    val decoded = CanonicalCompilerSignature.restoreCanonicalEncoding(signature.value)
    if (decoded !is Refinement.Refined) return false
    val function = decoded.value as? CanonicalCompilerSignature.Function ?: return false
    if (function.valueParameters.isNotEmpty() || function.contextReceivers.isNotEmpty()) return false
    if (CompilerSymbolIdentity.fromCanonicalSignature(function).value != compilerIdentity.value) return false
    return site.enclosing.range.containsExecutionRange(entryRange) &&
        entryRange.containsExecutionRange(inputRange) &&
        inputRange.containsExecutionRange(site.range)
}
