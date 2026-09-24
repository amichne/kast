package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

private val semanticReadNames =
    setOf(
        "search_classes",
        "search_functions",
        "search_declarations",
        "query_symbols",
        "symbol_lookup",
        "symbol_inspect",
        "source_read",
        "read_relations",
        "traverse_relations",
        "check_diagnostics",
    )

internal data class McpReadPresentation(val summary: String, val envelope: JsonElement)

/** The canonical document is opaque data in this transport projection and is also returned unchanged as text. */
internal fun mcpReadPresentation(name: String, exit: CliExit): McpReadPresentation? {
    if (name !in semanticReadNames) return null
    val canonical =
        try {
            readEnvelopeJson.parseToJsonElement(exit.document.value)
        } catch (_: SerializationException) {
            return null
        }
    if (exit is CliExit.BoundaryRejected || exit is CliExit.OperationRejected)
        return rejectedReadPresentation(name, exit, canonical)
    val header =
        try {
            readEnvelopeJson.decodeFromJsonElement<McpCanonicalHeader>(canonical)
        } catch (_: SerializationException) {
            return null
        }
    if (header.status == McpCanonicalStatus.REJECTED) return rejectedReadPresentation(name, exit, canonical)
    val live = header.live ?: return rejectedReadPresentation(name, exit, canonical)
    val coverage = coverageFor(name, header, canonical)
    val basis = McpWorkspaceBasis(live.root, live.host, live.epoch, live.contentView, live.version)
    val complete = exit is CliExit.Complete && header.status == McpCanonicalStatus.COMPLETE && coverage.exhaustive
    val envelope =
        if (complete)
            readEnvelopeJson.encodeToJsonElement(McpCompleteRead(data = canonical, coverage = coverage, basis = basis))
        else
            readEnvelopeJson.encodeToJsonElement(
                McpPartialRead(
                    data = canonical,
                    coverage = coverage.copy(exhaustive = false),
                    continuation = header.continuationToken(),
                    stopReason = header.stopReason(),
                    basis = basis,
                )
            )
    return McpReadPresentation(summaryFor(name, header, complete), envelope)
}

private fun rejectedReadPresentation(name: String, exit: CliExit, canonical: JsonElement): McpReadPresentation {
    val code =
        when (exit) {
            is CliExit.BoundaryRejected ->
                when (exit.status) {
                    CliBoundaryExitStatus.USAGE -> McpReadErrorCode.INVALID_REQUEST
                    CliBoundaryExitStatus.ROOT -> McpReadErrorCode.OUT_OF_SCOPE
                    CliBoundaryExitStatus.RUNTIME,
                    CliBoundaryExitStatus.TRANSPORT,
                    CliBoundaryExitStatus.BOOTSTRAP -> McpReadErrorCode.HOST_UNAVAILABLE
                    CliBoundaryExitStatus.PROTOCOL -> McpReadErrorCode.INTERNAL_ERROR
                }
            is CliExit.OperationRejected ->
                when (nativeFailure(canonical)) {
                    McpNativeFailure.INVALID_REQUEST -> McpReadErrorCode.INVALID_REQUEST
                    McpNativeFailure.FRESHNESS_REJECTED -> McpReadErrorCode.STALE_REFERENCE
                    McpNativeFailure.INDEX_NOT_READY -> McpReadErrorCode.INDEX_NOT_READY
                    else -> McpReadErrorCode.SEMANTIC_REJECTION
                }
            else -> McpReadErrorCode.SEMANTIC_REJECTION
        }
    val unavailable = code == McpReadErrorCode.HOST_UNAVAILABLE
    val error = McpReadError(code, "The $name read was rejected; see canonical evidence", canonical)
    val envelope =
        if (unavailable) readEnvelopeJson.encodeToJsonElement(McpUnavailableRead(error = error))
        else readEnvelopeJson.encodeToJsonElement(McpRejectedRead(error = error))
    return McpReadPresentation("$name: ${if (unavailable) "unavailable" else "rejected"}", envelope)
}

private fun coverageFor(name: String, header: McpCanonicalHeader, canonical: JsonElement): McpReadCoverage {
    val native = header.coverage
    val source = if (name == "source_read") sourceCompleteness(canonical) else null
    val requiresExplicitCoverage =
        name in setOf("query_symbols", "search_classes", "search_functions", "search_declarations", "check_diagnostics")
    val exhaustive =
        header.status == McpCanonicalStatus.COMPLETE &&
            (native?.exhaustive ?: !requiresExplicitCoverage) &&
            (name != "source_read" || source != null) &&
            source?.textWithheld != true
    return McpReadCoverage(
        exhaustive = exhaustive,
        requestedPath = native?.requestedPath,
        filesDiscovered = native?.filesDiscovered,
        filesAnalyzed = native?.filesAnalyzed,
        filesSkipped = native?.filesSkipped,
        selectedRegion = source?.region,
        textTruncated = source?.textWithheld,
    )
}

@Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
private fun summaryFor(name: String, header: McpCanonicalHeader, complete: Boolean): String {
    val completion = if (complete) "requested scope exhausted" else "partial; absence unverified"
    return when (name) {
        "search_classes",
        "search_functions",
        "search_declarations",
        "query_symbols" -> {
            val items = header.items.orEmpty()
            val lines =
                items.take(MAX_SUMMARY_ITEMS).mapNotNull { item ->
                    item.name?.let { name ->
                        buildString {
                            append(name)
                            item.kind?.let { append(" — ").append(if (it == "classlike") "class" else it) }
                            item.location?.file?.let { file ->
                                val displayPath = header.live?.root?.let { file.removePrefix("$it/") } ?: file
                                append("\n").append(displayPath)
                                (item.location.offset ?: item.location.range?.startInclusive)?.let {
                                    append(" @ offset ").append(it)
                                }
                            }
                            item.signature?.qualifiedIdentity?.let { append("\n").append(it) }
                        }
                    }
                }
            (lines + "${items.size} ${if (items.size == 1) "result" else "results"}; $completion")
                .joinToString("\n")
                .take(MAX_SUMMARY_CHARS)
        }
        "check_diagnostics" -> {
            val coverage = header.coverage
            val analyzed = coverage?.filesAnalyzed
            val discovered = coverage?.filesDiscovered
            val count = header.diagnostics?.size ?: 0
            val files = "${analyzed ?: "?"} of ${discovered ?: "?"} analyzed files"
            val finding = if (count == 0 && complete) "No diagnostics" else "$count diagnostics"
            "$finding in $files; $completion. IDE analysis; project build not run."
        }
        "read_relations" -> "${header.relations?.size ?: 0} exact relation facts; $completion"
        "source_read" -> "Source read; $completion"
        "symbol_lookup" -> "${header.items?.size ?: 0} declaration candidates; $completion"
        "symbol_inspect" -> "Exact symbol inspection; $completion"
        "traverse_relations" -> "Relation traversal; $completion"
        else -> "$name; $completion"
    }
}

private fun McpCanonicalHeader.continuationToken(): String? {
    if (continuation != null) return continuation
    val value = qualificationDetails()?.continuation ?: return null
    return when (value) {
        is JsonPrimitive -> value.content.takeIf(String::isNotBlank)
        is JsonObject -> (value["continuation"] as? JsonPrimitive)?.content
        else -> null
    }
}

private fun McpCanonicalHeader.qualificationDetails(): McpNativeQualification? = qualification?.let {
    try {
        readEnvelopeJson.decodeFromJsonElement<McpNativeQualification>(it)
    } catch (_: SerializationException) {
        null
    }
}

private fun nativeFailure(canonical: JsonElement): McpNativeFailure? =
    try {
        readEnvelopeJson.decodeFromJsonElement<McpNativeFailureDocument>(canonical).failure
    } catch (_: SerializationException) {
        null
    }

private fun McpCanonicalHeader.stopReason(): McpReadStopReason {
    val qualified = qualificationDetails()
    val limitations = qualified?.limitations.orEmpty().map { it.uppercase().replace('-', '_') }
    val resultLimited = limitations.any { limitation -> resultLimitTerms.any(limitation::contains) }
    if (qualified?.resultLimitReached == true || resultLimited) return McpReadStopReason.RESULT_LIMIT
    if (limitations.any { it.contains("BYTE_LIMIT") || it.contains("WORK_LIMIT") || it.contains("TIME_LIMIT") })
        return McpReadStopReason.BUDGET
    if (limitations.any { it.contains("DUMB_MODE_TRANSITION") }) return McpReadStopReason.HOST_INTERRUPTED
    return when (progress?.stop) {
        "enumeration_file_limit" -> McpReadStopReason.RESULT_LIMIT
        "enumeration_work_limit",
        "enumeration_time_limit" -> McpReadStopReason.BUDGET
        "analysis_pending",
        "output_pending" -> McpReadStopReason.BUDGET
        else -> McpReadStopReason.INCOMPLETE_EVIDENCE
    }
}

@Serializable
private data class McpCanonicalHeader(
    val status: McpCanonicalStatus,
    val live: McpLiveRead? = null,
    val coverage: McpNativeCoverage? = null,
    /** Operation qualifications have distinct closed shapes; their canonical bytes remain in data. */
    val qualification: JsonElement? = null,
    val continuation: String? = null,
    val progress: McpNativeProgress? = null,
    val items: List<McpSummaryItem>? = null,
    val diagnostics: List<JsonElement>? = null,
    val relations: List<JsonElement>? = null,
)

@Serializable
private enum class McpCanonicalStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("qualified") QUALIFIED,
    @SerialName("rejected") REJECTED,
}

@Serializable
private data class McpLiveRead(
    val root: String,
    val host: String,
    val epoch: Long,
    val contentView: McpContentView,
    val version: Int,
)

@Serializable
private data class McpNativeCoverage(
    val exhaustive: Boolean? = null,
    val requestedPath: String? = null,
    val filesDiscovered: Int? = null,
    val filesAnalyzed: Int? = null,
    val filesSkipped: Int? = null,
)

@Serializable
private data class McpNativeQualification(
    val limitations: List<String>? = null,
    val resultLimitReached: Boolean? = null,
    /** Canonical continuation variants remain opaque until copied unchanged. */
    val continuation: JsonElement? = null,
)

@Serializable private data class McpNativeProgress(val stop: String? = null)

@Serializable private data class McpNativeFailureDocument(val failure: McpNativeFailure)

@Serializable
private enum class McpNativeFailure {
    INVALID_REQUEST,
    FRESHNESS_REJECTED,
    INDEX_NOT_READY,
}

@Serializable
private data class McpSummaryItem(
    val name: String? = null,
    val kind: String? = null,
    val location: McpSummaryLocation? = null,
    val signature: McpSummarySignature? = null,
)

@Serializable
private data class McpSummaryLocation(
    val file: String? = null,
    val offset: Int? = null,
    val range: McpSummaryRange? = null,
)

@Serializable private data class McpSummaryRange(val startInclusive: Int)

@Serializable private data class McpSummarySignature(val qualifiedIdentity: String? = null)

@Serializable
private data class McpCompleteRead(
    val status: McpReadStatus = McpReadStatus.COMPLETE,
    /** Exact canonical payload, including budgets, selectors, signatures, and provenance. */
    val data: JsonElement,
    val coverage: McpReadCoverage,
    val basis: McpWorkspaceBasis,
)

@Serializable
private data class McpPartialRead(
    val status: McpReadStatus = McpReadStatus.PARTIAL,
    val data: JsonElement,
    val coverage: McpReadCoverage,
    val continuation: String? = null,
    val stopReason: McpReadStopReason,
    val basis: McpWorkspaceBasis,
)

@Serializable
private data class McpRejectedRead(val status: McpReadStatus = McpReadStatus.REJECTED, val error: McpReadError)

@Serializable
private data class McpUnavailableRead(val status: McpReadStatus = McpReadStatus.UNAVAILABLE, val error: McpReadError)

@Serializable
private enum class McpReadStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("partial") PARTIAL,
    @SerialName("rejected") REJECTED,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
private enum class McpReadStopReason {
    @SerialName("budget") BUDGET,
    @SerialName("result_limit") RESULT_LIMIT,
    @SerialName("host_interrupted") HOST_INTERRUPTED,
    /** Native incomplete semantic evidence cannot truthfully be called a budget or interruption. */
    @SerialName("incomplete_evidence") INCOMPLETE_EVIDENCE,
}

@Serializable
private data class McpReadCoverage(
    val exhaustive: Boolean,
    val requestedPath: String? = null,
    val filesDiscovered: Int? = null,
    val filesAnalyzed: Int? = null,
    val filesSkipped: Int? = null,
    /** Operation-specific selected region is opaque canonical evidence. */
    val selectedRegion: JsonElement? = null,
    val textTruncated: Boolean? = null,
)

@Serializable
private data class McpWorkspaceBasis(
    val root: String,
    val host: String,
    val epoch: Long,
    val contentView: McpContentView,
    val version: Int,
    val hostState: McpHostState = McpHostState.INDEXED,
)

@Serializable
private enum class McpContentView {
    SAVED_PSI_COMMITTED
}

@Serializable
private enum class McpHostState {
    INDEXED
}

@Serializable
private data class McpReadError(val code: McpReadErrorCode, val message: String, val evidence: JsonElement)

@Serializable
private enum class McpReadErrorCode {
    INVALID_REQUEST,
    INDEX_NOT_READY,
    STALE_REFERENCE,
    OUT_OF_SCOPE,
    HOST_UNAVAILABLE,
    INTERNAL_ERROR,
    SEMANTIC_REJECTION,
}

private const val MAX_SUMMARY_ITEMS = 10
private const val MAX_SUMMARY_CHARS = 4_000
private val resultLimitTerms = listOf("RESULT_LIMIT", "ENTITY_LIMIT", "RECORD_LIMIT", "FRONTIER_LIMIT", "DEPTH_LIMIT")
private val readEnvelopeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
