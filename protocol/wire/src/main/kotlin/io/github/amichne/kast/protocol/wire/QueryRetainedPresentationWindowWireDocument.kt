package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.admittedPresentationWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Carries explicit retained bounds; neither a successor cursor nor row count can manufacture this proof. */
@Serializable
internal data class QueryRetainedPresentationWindowWireDocument(
    val reference: QueryResultReference,
    val start: QueryResultCursor,
    val end: QueryResultCursor,
    @SerialName("result_end") val resultEnd: QueryResultCursor,
)

internal fun QueryRetainedPresentationWindow.toWireDocument() =
    QueryRetainedPresentationWindowWireDocument(reference, start, end, resultEnd)

internal fun QueryRunResult.withWirePresentationWindow(
    document: QueryRetainedPresentationWindowWireDocument?
): WireDocumentConversion<QueryRunResult> =
    if (document == null) WireDocumentConversion.Converted(this)
    else {
        QueryRetainedPresentationWindow.create(document.reference, document.start, document.end, document.resultEnd)
            .toWireDocumentConversion()
            .flatMapConverted { window ->
                val result = copy(presentationWindow = window)
                result.admittedPresentationWindow().toWireDocumentConversion().mapConverted { result }
            }
    }
