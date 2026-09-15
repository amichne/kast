package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.CancellationException

/** Called inside each retryable read action; no mutable collector survives an interrupted attempt. */
internal fun diagnosticCompilationAttempt(
    scope: DiagnosticScope,
    collect: (IntellijDiagnosticCollector) -> Unit,
): DiagnosticCompilation {
    val collector = IntellijDiagnosticCollector(scope)
    collect(collector)
    return collector.finish()
}

internal suspend fun guardedDiagnosticCompilation(block: suspend () -> DiagnosticCompilation): DiagnosticCompilation =
    try {
        block()
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        DiagnosticCompilation.Rejected(DiagnosticCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
    } catch (_: LinkageError) {
        DiagnosticCompilation.Rejected(DiagnosticCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE)
    }

/** The allowance spans retries; only the original detached cursor seeds each new collector. */
internal fun diagnosticEnumerationAttempt(
    request: DiagnosticEnumerationRequest,
    allowance: DiagnosticEnumerationAllowance,
    maximumBytes: Long,
    collect: (BoundedDiagnosticEnumeration) -> Unit,
): DiagnosticEnumerationResult {
    val collector =
        when (val admitted = BoundedDiagnosticEnumeration.create(request, allowance, maximumBytes)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return DiagnosticEnumerationResult.Rejected(admitted.failure)
        }
    collect(collector)
    return collector.finish()
}
