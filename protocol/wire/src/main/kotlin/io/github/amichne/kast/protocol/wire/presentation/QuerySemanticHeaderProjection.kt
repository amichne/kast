package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentAuthority
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentAuthorityLive
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentAuthorityRetainedResult
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentAuthorityUnavailable
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentBlockers
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentCompletion
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentCompletionModel
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentCompletionPolicy
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentCoverage
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentCoverageProgress
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentCoverageType
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentOmissions
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentOriginalOutcome
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentPresentation
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentPresentationDetail
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentPresentationDetailRead
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentRecovery
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentRecoveryReadResult
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentRecoveryUnavailable
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentRecoveryUnavailableReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Finite facts from a schema-admitted canonical document. No source or witness payload enters this carrier. */
fun querySemanticHeader(
    document: JsonObject,
    outcome: QuerySemanticHeaderDocumentOriginalOutcome,
): QuerySemanticHeaderDocument = SemanticHeaderProjection(document, outcome).project()

private class SemanticHeaderProjection(
    private val document: JsonObject,
    private val outcome: QuerySemanticHeaderDocumentOriginalOutcome,
) {
    private val rejection = document.objectAt("rejection")
    private val detail = rejection?.objectAt("detail")
    private val interpretation = document.objectAt("interpretation")
    private val evidence = detail?.objectAt("evidence")
    private val question = document.objectAt("question") ?: evidence?.objectAt("question")
    private val completion = question?.objectAt("completion")
    private val coverage =
        detail?.objectAt("originalCoverage")
            ?: interpretation?.objectAt("originalCoverage")
            ?: document.objectAt("coverage")
    private val progress = coverage?.objectAt("progress") ?: document.objectAt("qualification")?.objectAt("progress")
    private val resultText = evidence?.stringAt("result") ?: document.objectAt("retention")?.stringAt("reference")
    private val result = resultText?.let { QueryResultReference.parse(it).proven() }
    private val live = document.objectAt("live")
    private val failures = document.arrayAt("failures")
    private val omissions = document.arrayAt("omissions")
    private val original =
        document.objectAt("original_evidence_summary")
            ?: detail?.objectAt("original_evidence_summary")
            ?: evidence?.objectAt("original_evidence_summary_state")?.objectAt("summary")
    private val cause = detail?.get("cause")?.code() ?: interpretation?.get("cause")?.code()
    private val model = detail?.stringAt("model") ?: interpretation?.stringAt("model") ?: completion?.stringAt("model")
    private val window = document.objectAt("evidence_window")

    fun project(): QuerySemanticHeaderDocument =
        QuerySemanticHeaderDocument(
            originalOutcome = outcome,
            completion = completion(),
            authority = authority(),
            coverage = coverage(),
            blockers = blockers(),
            omissions = omissionSummary(),
            presentation = presentation(),
            recovery = recovery(),
        )

    private fun completion(): QuerySemanticHeaderDocumentCompletion =
        QuerySemanticHeaderDocumentCompletion(
            if (completion?.stringAt("type") == "COMPLETE_ONLY")
                QuerySemanticHeaderDocumentCompletionPolicy.COMPLETE_ONLY
            else QuerySemanticHeaderDocumentCompletionPolicy.UNAVAILABLE,
            if (model == "COMPILER_RESOLVED_STATIC_V1")
                QuerySemanticHeaderDocumentCompletionModel.COMPILER_RESOLVED_STATIC_V1
            else QuerySemanticHeaderDocumentCompletionModel.UNAVAILABLE,
            interpretation?.stringAt("type")?.protocolText(),
            cause?.protocolText(),
        )

    private fun authority(): QuerySemanticHeaderDocumentAuthority =
        when {
            result != null -> QuerySemanticHeaderDocumentAuthorityRetainedResult(result)
            live != null ->
                QuerySemanticHeaderDocumentAuthorityLive(
                    requireNotNull(live.stringAt("root")).protocolText(),
                    requireNotNull(live.stringAt("host")).protocolText(),
                    requireNotNull(live.longAt("epoch")),
                    requireNotNull(live.stringAt("contentView")).protocolText(),
                    requireNotNull(live.longAt("version")),
                )
            else -> QuerySemanticHeaderDocumentAuthorityUnavailable
        }

    private fun coverage(): QuerySemanticHeaderDocumentCoverage =
        QuerySemanticHeaderDocumentCoverage(
            coverageKind(),
            (coverage?.get("exhaustive") as? JsonPrimitive)?.booleanOrNull,
            coverage?.longAt("knownMinimum") ?: document.objectAt("qualification")?.longAt("knownMinimum"),
            (coverage?.arrayAt("limitations")?.takeIf { it.isNotEmpty() }
                    ?: document.objectAt("qualification")?.arrayAt("limitations"))
                .orEmpty()
                .mapNotNull(JsonElement::code)
                .distinct()
                .map(String::protocolText),
            QuerySemanticHeaderDocumentCoverageProgress(
                progress?.stringAt("type")?.protocolText(),
                progress?.stringAt("reason")?.protocolText(),
                progress?.stringAt("next_action")?.protocolText(),
            ),
        )

    private fun coverageKind(): QuerySemanticHeaderDocumentCoverageType =
        when (coverage?.stringAt("type")) {
            "COMPLETE" -> QuerySemanticHeaderDocumentCoverageType.COMPLETE
            "QUALIFIED" -> QuerySemanticHeaderDocumentCoverageType.QUALIFIED
            else ->
                when ((coverage?.get("exhaustive") as? JsonPrimitive)?.booleanOrNull) {
                    true -> QuerySemanticHeaderDocumentCoverageType.COMPLETE
                    false -> QuerySemanticHeaderDocumentCoverageType.QUALIFIED
                    null -> QuerySemanticHeaderDocumentCoverageType.UNKNOWN
                }
        }

    private fun blockers(): QuerySemanticHeaderDocumentBlockers =
        QuerySemanticHeaderDocumentBlockers(
            null,
            rejection?.stringAt("type")?.protocolText(),
            cause?.protocolText(),
            (detail?.get("stop")?.code() ?: document.objectAt("invocation")?.get("stop")?.code())?.protocolText(),
            document.objectAt("qualification")?.objectAt("progress")?.stringAt("type")?.protocolText(),
            (failures.mapNotNull { (it as? JsonObject)?.get("reason")?.code() ?: it.code() } + originalFailureCodes())
                .distinct()
                .map(String::protocolText),
            original?.longAt("failure_count"),
            original?.arrayAt("failure_kinds").orEmpty().mapNotNull(JsonElement::code).map(String::protocolText),
        )

    private fun originalFailureCodes(): List<String> =
        original
            .orEmpty()
            .filterKeys {
                it != "omission_reasons" && (it.endsWith("_reasons") || it == "source_limitations")
            }
            .values
            .flatMap { (it as? JsonArray).orEmpty().mapNotNull(JsonElement::code) }

    private fun omissionSummary(): QuerySemanticHeaderDocumentOmissions =
        QuerySemanticHeaderDocumentOmissions(
            count = original?.longAt("omission_count"),
            codes =
                (original?.arrayAt("omission_reasons")?.mapNotNull(JsonElement::code)
                        ?: omissions.mapNotNull {
                            (it as? JsonObject)?.get("reason")?.code()
                                ?: (it as? JsonObject)?.objectAt("evidence")?.get("reason")?.code()
                                ?: it.code()
                        })
                    .distinct()
                    .map(String::protocolText),
        )

    private fun presentation(): QuerySemanticHeaderDocumentPresentation =
        QuerySemanticHeaderDocumentPresentation(
            0,
            document.objectAt("invocation")?.longAt("accumulated_row_count")
                ?: document.objectAt("row_window")?.longAt("total"),
            0,
            original?.longAt("evidence_count") ?: window?.longAt("total"),
            if (result != null) QuerySemanticHeaderDocumentPresentationDetail.DEFERRED_DETAIL
            else QuerySemanticHeaderDocumentPresentationDetail.UNAVAILABLE,
            0,
            detailRead(),
        )

    private fun detailRead(): QuerySemanticHeaderDocumentPresentationDetailRead =
        when (val selected = document.objectAt("detail")) {
            null -> QuerySemanticHeaderDocumentPresentationDetailRead.NONE
            else ->
                if (selected.longAt("end") == selected.longAt("total"))
                    QuerySemanticHeaderDocumentPresentationDetailRead.FINAL_FRAGMENT
                else QuerySemanticHeaderDocumentPresentationDetailRead.FRAGMENT
        }

    private fun recovery(): QuerySemanticHeaderDocumentRecovery =
        if (result != null)
            QuerySemanticHeaderDocumentRecoveryReadResult(
                result,
                document.objectAt("row_window")?.longAt("start") ?: 0,
                window?.longAt("start") ?: 0,
            )
        else
            QuerySemanticHeaderDocumentRecoveryUnavailable(
                QuerySemanticHeaderDocumentRecoveryUnavailableReason.NO_RETAINED_AUTHORITY
            )
}

private fun JsonObject.objectAt(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.arrayAt(name: String): List<JsonElement> = (get(name) as? JsonArray)?.toList().orEmpty()

private fun JsonObject.stringAt(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longAt(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull

private fun JsonElement.code(): String? =
    when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> stringAt("type")
        else -> null
    }

private fun String.protocolText(): ProtocolText = ProtocolText.parse(this).proven()

private fun <T, E> Refinement<T, E>.proven(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Schema-admitted semantic header lost its scalar proof")
    }
