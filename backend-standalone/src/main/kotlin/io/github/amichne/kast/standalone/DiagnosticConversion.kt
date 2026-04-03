package io.github.amichne.kast.standalone

import com.intellij.openapi.util.TextRange
import io.github.amichne.kast.api.Diagnostic
import io.github.amichne.kast.api.DiagnosticSeverity
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity

/**
 * Converts a K2 Analysis API diagnostic to one or more [Diagnostic] API models.
 */
@Suppress("UnstableApiUsage")
internal fun KaDiagnosticWithPsi<*>.toApiDiagnostics(): List<Diagnostic> {
    val ranges = textRanges.ifEmpty { listOf(TextRange(0, psi.textLength)) }
    return ranges.map { range ->
        Diagnostic(
            location = psi.toKastLocation(absoluteRange(range)),
            severity = severity.toApiSeverity(),
            message = defaultMessage,
            code = factoryName,
        )
    }
}

private fun KaDiagnosticWithPsi<*>.absoluteRange(relativeRange: TextRange): TextRange {
    val fileLength = psi.containingFile.textLength
    return when {
        relativeRange.endOffset <= psi.textLength -> {
            val elementStartOffset = psi.textRange.startOffset
            TextRange(
                elementStartOffset + relativeRange.startOffset,
                elementStartOffset + relativeRange.endOffset,
            )
        }
        relativeRange.endOffset <= fileLength -> relativeRange
        else -> TextRange(
            relativeRange.startOffset.coerceIn(0, fileLength),
            relativeRange.endOffset.coerceIn(relativeRange.startOffset.coerceIn(0, fileLength), fileLength),
        )
    }
}

private fun KaSeverity.toApiSeverity(): DiagnosticSeverity = when (this) {
    KaSeverity.ERROR -> DiagnosticSeverity.ERROR
    KaSeverity.WARNING -> DiagnosticSeverity.WARNING
    KaSeverity.INFO -> DiagnosticSeverity.INFO
}
