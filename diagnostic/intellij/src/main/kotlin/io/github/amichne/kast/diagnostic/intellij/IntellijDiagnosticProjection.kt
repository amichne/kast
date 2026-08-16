package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.util.TextRange
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile
import io.github.amichne.kast.kernel.Refinement
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity

internal sealed interface IntellijDiagnosticProjection {
    data class Projected(
        val facts: List<DiagnosticFact>,
    ) : IntellijDiagnosticProjection

    data object Rejected : IntellijDiagnosticProjection
}

/**
 * Proof transition: `(DiagnosticScope, DiagnosticSourceFile, KaDiagnosticWithPsi) ->
 * IntellijDiagnosticProjection`.
 *
 * [IntellijDiagnosticProjection.Projected] establishes detached typed facts for every compiler
 * range, permanently bound to the exact scope generation. Rejected is the closed unsupported
 * compiler projection. Raw PSI, ranges, severity, code, and message remain inside this boundary.
 */
internal fun projectDiagnostic(
    scope: DiagnosticScope,
    file: DiagnosticSourceFile,
    diagnostic: KaDiagnosticWithPsi<*>,
): IntellijDiagnosticProjection {
    val ranges = diagnostic.textRanges.ifEmpty {
        listOf(TextRange(0, diagnostic.psi.textLength))
    }
    val facts = mutableListOf<DiagnosticFact>()
    ranges.forEach { range ->
        val absolute = diagnostic.absoluteRange(range)
        when (
            val fact = DiagnosticFact.fromBoundary(
                scope,
                file,
                absolute.startOffset,
                absolute.endOffset,
                diagnostic.severity.toContractSeverity(),
                diagnostic.factoryName,
                diagnostic.defaultMessage,
            )
        ) {
            is Refinement.Refined -> facts += fact.value
            is Refinement.Rejected -> return IntellijDiagnosticProjection.Rejected
        }
    }
    return IntellijDiagnosticProjection.Projected(facts)
}

private fun KaDiagnosticWithPsi<*>.absoluteRange(relativeRange: TextRange): TextRange {
    val fileLength = psi.containingFile.textLength
    return when {
        relativeRange.endOffset <= psi.textLength -> {
            val elementStart = psi.textRange.startOffset
            TextRange(
                (elementStart + relativeRange.startOffset).coerceIn(0, fileLength),
                (elementStart + relativeRange.endOffset).coerceIn(0, fileLength),
            )
        }
        relativeRange.endOffset <= fileLength -> relativeRange
        else -> {
            val start = relativeRange.startOffset.coerceIn(0, fileLength)
            TextRange(start, relativeRange.endOffset.coerceIn(start, fileLength))
        }
    }
}

private fun KaSeverity.toContractSeverity(): DiagnosticSeverity = when (this) {
    KaSeverity.ERROR -> DiagnosticSeverity.ERROR
    KaSeverity.WARNING -> DiagnosticSeverity.WARNING
    KaSeverity.INFO -> DiagnosticSeverity.INFO
}
