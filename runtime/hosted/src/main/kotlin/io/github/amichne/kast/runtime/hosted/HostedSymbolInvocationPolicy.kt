package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.protocol.QueryInlinePresentation
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.isActive

private const val SYMBOL_PREVIEW_ROWS = 100
private const val SYMBOL_PREVIEW_BYTES = 32_768L

/** One admitted read allowance and its caller's cancellation capability govern internal pages. */
internal fun hostedSymbolInvocationPolicy(
    context: HostedSemanticReadContext,
    caller: CoroutineContext,
): QueryInvocationPolicy =
    QueryInvocationPolicy(
        previewRows =
            provenInvocationBound(
                ResultLimit.parse(minOf(SYMBOL_PREVIEW_ROWS, context.executionBudget.results.effective.value))
            ),
        previewBytesLimit =
            provenInvocationBound(
                QueryByteLimit.parse(minOf(SYMBOL_PREVIEW_BYTES, context.executionBudget.returnedBytes.effective.value))
            ),
        retainedBytes =
            provenInvocationBound(
                QueryByteLimit.parse(context.limits[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value.toLong())
            ),
        previewBytes = CanonicalQueryCliDocuments::previewBytes,
        cancelled = { !caller.isActive },
        inlinePresentation = { outcome ->
            hostedInlineQueryPresentation(
                outcome
                    .withQueryDiagnosticIdentity(context.readTrace)
                    .withQueryBudget(ExecutionBudgetReport.from(context.executionBudget)),
                context.limits,
                context.executionBudget.returnedBytes.effective,
            )
        },
    )

/** Inline admission measures the original full envelope before presentation fitting can change its payload. */
internal fun hostedInlineQueryPresentation(
    outcome: HostedQueryOutcome,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
): QueryInlinePresentation =
    when (HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, outcome, limits, maximumBytes)) {
        is HostedResponse.Canonical<*, *, *> -> QueryInlinePresentation.FITS
        is HostedResponse.Oversized -> QueryInlinePresentation.RETENTION_REQUIRED
        is HostedResponse.EncodingRejected,
        is HostedResponse.Completed,
        is HostedResponse.Rejected,
        is HostedResponse.ChangeRejected,
        is HostedResponse.ReadRejected -> QueryInlinePresentation.INVALID
    }

private fun <T, F> provenInvocationBound(value: Refinement<T, F>): T =
    when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("Admitted invocation bound was lost: ${value.failure}")
    }
