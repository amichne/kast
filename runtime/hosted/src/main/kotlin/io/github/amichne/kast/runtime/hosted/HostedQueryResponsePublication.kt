package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** A header preserves the semantic page while the publication owner charges its full retained encoding. */
internal fun fitHostedQueryPresentationHeader(
    semantic: HostedQueryOutcome,
    fitted: HostedResponse,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
    published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)?,
): HostedResponse {
    if (fitted !is HostedResponse.Oversized || semantic.hasSelectedDetail()) return fitted
    val response = HostedResponse.Canonical.queryPresentation(semantic, limits, maximumBytes)
    if (response is HostedResponse.Canonical<*, *, *>)
        published?.invoke(encodedPublication(semantic.publicationPage(), response))
    return response
}

private fun HostedQueryOutcome.hasSelectedDetail(): Boolean =
    when (this) {
        is OperationOutcome.Complete -> evidence.payload.detailSelection != null
        is OperationOutcome.Qualified -> evidence.payload.detailSelection != null
        is OperationOutcome.Rejected -> false
    }

internal fun encodeHostedRejectedQueryResponse(
    semantic: OperationOutcome.Rejected<QueryRunFailure>,
    original: HostedResponse,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
    published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)?,
): HostedResponse {
    val completion = semantic.reason.reason() as? QueryRunRejection.CompletionUnproven
    return if (completion != null) original.publishEncodedPage(semantic.publicationPage(), published) else original
}

internal fun HostedQueryOutcome.publicationPage(): io.github.amichne.kast.query.protocol.QueryPublishedPage =
    when (this) {
        is OperationOutcome.Complete ->
            OperationOutcome.Complete(evidence.copy(payload = evidence.payload.copy(executionBudget = null)))
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(
                evidence.copy(payload = evidence.payload.copy(executionBudget = null)),
                qualification,
            )
        is OperationOutcome.Rejected -> OperationOutcome.Rejected(reason.reason())
    }

/** Carries the successful encoding bound into the existing publication owner without encoding again. */
internal fun encodedPublication(
    page: io.github.amichne.kast.query.protocol.QueryPublishedPage,
    response: HostedResponse.Canonical<*, *, *>,
): io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded {
    val canonicalPage = page.publicationPage()
    val responseBytes =
        io.github.amichne.kast.query.protocol.QueryRetentionByteCount.parse(
                response.document.toByteArray(Charsets.UTF_8).size.toLong()
            )
            .proven()
    return when (response.presentation) {
        HostedCanonicalPresentation.FULL ->
            io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded.fromEncoding(
                canonicalPage,
                responseBytes,
            )
        HostedCanonicalPresentation.HEADER_ONLY -> {
            val full =
                when (val encoded = CanonicalOperationWireBindings.queryRun.encodeOutcome(canonicalPage)) {
                    is io.github.amichne.kast.protocol.wire.WireEncoding.Encoded -> encoded.document
                    is io.github.amichne.kast.protocol.wire.WireEncoding.Rejected ->
                        error("An admitted header lost its original semantic encoding")
                }
            io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded.fromPresentationEncoding(
                canonicalPage,
                responseBytes,
                io.github.amichne.kast.query.protocol.QueryRetentionByteCount.parse(
                        full.toByteArray(Charsets.UTF_8).size.toLong()
                    )
                    .proven(),
            )
        }
    }
}

internal fun HostedResponse.publishEncodedPage(
    page: io.github.amichne.kast.query.protocol.QueryPublishedPage,
    published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)?,
): HostedResponse {
    if (this is HostedResponse.Canonical<*, *, *>) published?.invoke(encodedPublication(page, this))
    return this
}

private fun <T, E> Refinement<T, E>.proven(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An admitted publication lost its byte accounting")
    }
