@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile

internal sealed interface ReplacementBodyDiagnosticObservation {
    data object ErrorFree : ReplacementBodyDiagnosticObservation
    data object ContainsErrors : ReplacementBodyDiagnosticObservation
    data object Unavailable : ReplacementBodyDiagnosticObservation
}

/**
 * Proof transition: [KtFile] and its selected [KtExpression] body ->
 * [ReplacementBodyDiagnosticObservation].
 *
 * [ReplacementBodyDiagnosticObservation.ErrorFree] establishes complete compiler diagnostic
 * collection for the synthetic target file and no error diagnostic intersecting the selected
 * replacement body. It deliberately makes no diagnostic claim for unchanged compiler-context
 * files; those inputs are admitted by byte and project-model generation instead. Compiler errors
 * and unavailable diagnostic evidence remain separate closed outcomes. No analysis-session object
 * escapes this boundary.
 */
internal fun observeReplacementBodyDiagnostics(
    file: KtFile,
    body: KtExpression,
): ReplacementBodyDiagnosticObservation = try {
    val intersection = analyze(file) {
        file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
            .fold(
                ReplacementDiagnosticIntersection.Disjoint as ReplacementDiagnosticIntersection,
            ) { observed, diagnostic ->
                when {
                    observed is ReplacementDiagnosticIntersection.Intersects ->
                        observed

                    diagnostic.severity != KaSeverity.ERROR ->
                        ReplacementDiagnosticIntersection.Disjoint

                    else -> diagnostic.intersectionWith(body.textRange)
                }
            }
    }
    when (intersection) {
        ReplacementDiagnosticIntersection.Intersects ->
            ReplacementBodyDiagnosticObservation.ContainsErrors

        ReplacementDiagnosticIntersection.Disjoint ->
            ReplacementBodyDiagnosticObservation.ErrorFree
    }
} catch (failure: ProcessCanceledException) {
    throw failure
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    ReplacementBodyDiagnosticObservation.Unavailable
}

private sealed interface ReplacementDiagnosticIntersection {
    data object Intersects : ReplacementDiagnosticIntersection
    data object Disjoint : ReplacementDiagnosticIntersection
}

private fun KaDiagnosticWithPsi<*>.intersectionWith(
    selected: TextRange,
): ReplacementDiagnosticIntersection {
    val fileLength = psi.containingFile.textLength
    return textRanges.ifEmpty { listOf(TextRange(0, psi.textLength)) }.fold(
        ReplacementDiagnosticIntersection.Disjoint as ReplacementDiagnosticIntersection,
    ) { intersection, observed ->
        if (intersection is ReplacementDiagnosticIntersection.Intersects) {
            return@fold intersection
        }
        val absolute = when {
            observed.endOffset <= psi.textLength -> observed.shiftRight(psi.textRange.startOffset)
            observed.endOffset <= fileLength -> observed
            else -> TextRange(
                observed.startOffset.coerceIn(0, fileLength),
                observed.endOffset.coerceIn(observed.startOffset.coerceIn(0, fileLength), fileLength),
            )
        }
        if (absolute.startOffset < selected.endOffset && absolute.endOffset > selected.startOffset) {
            ReplacementDiagnosticIntersection.Intersects
        } else {
            ReplacementDiagnosticIntersection.Disjoint
        }
    }
}
