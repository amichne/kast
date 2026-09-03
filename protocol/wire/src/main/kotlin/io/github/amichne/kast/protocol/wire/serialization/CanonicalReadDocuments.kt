package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data object WorkspaceInspectRequestDocument

@Serializable
internal data class WorkspaceInspectResultDocument(
    val canonicalRoot: String,
    val state: WorkspaceStateWireDocument,
)

@Serializable
internal data class SymbolDiscoverQualificationDocument(
    val limitations: List<SymbolDiscoverLimitationWireDocument>,
)

@Serializable
internal data class SymbolResolveRequestDocument(
    val candidateSelector: String,
)

@Serializable
internal data class SymbolResolveResultDocument(
    val exactSelector: String,
)

@Serializable
internal data class SymbolDescribeRequestDocument(
    val exactSelector: String,
)

@Serializable
internal data class RelationReadRequestDocument(
    val exactSelector: String,
    val relation: RelationKindWireDocument,
    val limit: Int,
)

@Serializable
internal data class TraversalRunRequestDocument(
    val exactSelector: String,
    val relation: RelationKindWireDocument,
    val maximumDepth: Int,
    val maximumResults: Int,
)

@Serializable
internal data class DiagnosticCheckRequestDocument(
    val scope: String,
    val limit: Int,
)

@Serializable
internal data class DiagnosticCheckResultDocument(
    val diagnostics: List<DiagnosticWireDocument>,
)

@Serializable
internal data class DiagnosticWireDocument(
    val severity: DiagnosticSeverityWireDocument,
    val code: String,
    val message: String,
    val location: DiagnosticLocationWireDocument,
)

@Serializable
internal data class DiagnosticLocationWireDocument(
    val candidateSelector: String,
    val file: String,
    val range: DiagnosticRangeWireDocument,
)

@Serializable
internal data class DiagnosticRangeWireDocument(
    val startInclusive: Int,
    val endExclusive: Int,
)

@Serializable
internal enum class DiagnosticSeverityWireDocument {
    @SerialName("error") ERROR,
    @SerialName("warning") WARNING,
    @SerialName("info") INFO,
}

@Serializable
internal enum class WorkspaceStateWireDocument {
    @SerialName("absent") ABSENT,
    @SerialName("starting") STARTING,
    @SerialName("reconciling") RECONCILING,
    @SerialName("ready") READY,
    @SerialName("blocked") BLOCKED,
    @SerialName("stopping") STOPPING,
}

@Serializable
internal enum class WorkspaceInspectQualificationWireDocument {
    @SerialName("reconciling") RECONCILING,
}

@Serializable
internal enum class WorkspaceInspectRejectionWireDocument {
    @SerialName("root_unavailable") ROOT_UNAVAILABLE,
    @SerialName("runtime_blocked") RUNTIME_BLOCKED,
}

@Serializable
internal enum class SymbolDiscoverLimitationWireDocument {
    @SerialName("result-limit") RESULT_LIMIT,
    @SerialName("byte-limit") BYTE_LIMIT,
    @SerialName("work-limit") WORK_LIMIT,
    @SerialName("time-limit") TIME_LIMIT,
    @SerialName("dumb-mode-transition") DUMB_MODE_TRANSITION,
    @SerialName("provider-failure") PROVIDER_FAILURE,
    @SerialName("unscoped-provider") UNSCOPED_PROVIDER,
    @SerialName("unsupported-item") UNSUPPORTED_ITEM,
    @SerialName("exact-definition-unavailable") EXACT_DEFINITION_UNAVAILABLE,
}

@Serializable
internal enum class SymbolDiscoverRejectionWireDocument {
    @SerialName("workspace_not_ready") WORKSPACE_NOT_READY,
    @SerialName("query_rejected") QUERY_REJECTED,
}

@Serializable
internal enum class SymbolResolveQualificationWireDocument {
    @SerialName("evidence_incomplete") EVIDENCE_INCOMPLETE,
}

@Serializable
internal enum class SymbolResolveRejectionWireDocument {
    @SerialName("workspace_not_ready") WORKSPACE_NOT_READY,
    @SerialName("candidate_stale") CANDIDATE_STALE,
    @SerialName("candidate_not_declaration") CANDIDATE_NOT_DECLARATION,
    @SerialName("ambiguous") AMBIGUOUS,
    @SerialName("not_found") NOT_FOUND,
}

@Serializable
internal enum class SymbolDescribeQualificationWireDocument {
    @SerialName("evidence_incomplete") EVIDENCE_INCOMPLETE,
}

@Serializable
internal enum class SymbolDescribeRejectionWireDocument {
    @SerialName("workspace_not_ready") WORKSPACE_NOT_READY,
    @SerialName("selector_stale") SELECTOR_STALE,
    @SerialName("not_found") NOT_FOUND,
}

@Serializable
internal enum class RelationKindWireDocument {
    @SerialName("references") REFERENCES,
    @SerialName("callers") CALLERS,
    @SerialName("callees") CALLEES,
    @SerialName("implementations") IMPLEMENTATIONS,
    @SerialName("inheritors") INHERITORS,
    @SerialName("overrides") OVERRIDES,
    @SerialName("type_uses") TYPE_USES,
}

@Serializable
internal data class RelationReadQualificationWireDocument(
    val knownMinimum: Int,
    val limitations: List<RelationLimitationWireDocument>,
    val continuation: String,
)

@Serializable
internal enum class RelationLimitationWireDocument {
    @SerialName("result_limit_reached") RESULT_LIMIT_REACHED,
    @SerialName("byte_limit_reached") BYTE_LIMIT_REACHED,
    @SerialName("work_limit_reached") WORK_LIMIT_REACHED,
    @SerialName("time_limit_reached") TIME_LIMIT_REACHED,
    @SerialName("dumb_mode_transition") DUMB_MODE_TRANSITION,
    @SerialName("unresolved_target") UNRESOLVED_TARGET,
    @SerialName("unsupported_item") UNSUPPORTED_ITEM,
    @SerialName("provider_failure") PROVIDER_FAILURE,
    @SerialName("provider_incomplete") PROVIDER_INCOMPLETE,
}

@Serializable
internal enum class RelationReadRejectionWireDocument {
    @SerialName("workspace_not_ready") WORKSPACE_NOT_READY,
    @SerialName("selector_stale") SELECTOR_STALE,
    @SerialName("relation_unsupported") RELATION_UNSUPPORTED,
}

@Serializable
internal data class TraversalRunQualificationWireDocument(
    val limitations: List<TraversalLimitationWireDocument>,
    val relationLimitations: List<RelationLimitationWireDocument>,
    val continuation: String,
)

@Serializable
internal enum class TraversalLimitationWireDocument {
    @SerialName("record_limit_reached") RECORD_LIMIT_REACHED,
    @SerialName("byte_limit_reached") BYTE_LIMIT_REACHED,
    @SerialName("work_limit_reached") WORK_LIMIT_REACHED,
    @SerialName("time_limit_reached") TIME_LIMIT_REACHED,
    @SerialName("depth_limit_reached") DEPTH_LIMIT_REACHED,
    @SerialName("frontier_limit_reached") FRONTIER_LIMIT_REACHED,
    @SerialName("one_hop_incomplete") ONE_HOP_INCOMPLETE,
}

@Serializable
internal enum class TraversalRunRejectionWireDocument {
    @SerialName("workspace_not_ready") WORKSPACE_NOT_READY,
    @SerialName("selector_stale") SELECTOR_STALE,
    @SerialName("topology_build_required") TOPOLOGY_BUILD_REQUIRED,
    @SerialName("plan_rejected") PLAN_REJECTED,
}

@Serializable
internal data class DiagnosticCheckQualificationWireDocument(
    val knownDiagnosticCount: Int,
    val resultLimitReached: Boolean,
    val analyzedFiles: List<String>,
    val limitations: List<DiagnosticLimitationWireDocument>,
)

@Serializable
internal data class DiagnosticLimitationWireDocument(
    val file: String,
    val reason: DiagnosticLimitationReasonWireDocument,
)

@Serializable
internal enum class DiagnosticLimitationReasonWireDocument {
    @SerialName("file_unavailable") FILE_UNAVAILABLE,
    @SerialName("outside_source_content") OUTSIDE_SOURCE_CONTENT,
    @SerialName("indexing") INDEXING,
    @SerialName("psi_unavailable") PSI_UNAVAILABLE,
    @SerialName("unsupported_file_kind") UNSUPPORTED_FILE_KIND,
    @SerialName("unsupported_diagnostic") UNSUPPORTED_DIAGNOSTIC,
    @SerialName("analysis_unavailable") ANALYSIS_UNAVAILABLE,
}

@Serializable
internal enum class DiagnosticCheckRejectionWireDocument {
    @SerialName("workspace_not_ready") WORKSPACE_NOT_READY,
    @SerialName("scope_rejected") SCOPE_REJECTED,
}

internal fun WorkspaceInspectResult.toReadDocument(): WorkspaceInspectResultDocument =
    WorkspaceInspectResultDocument(canonicalRoot.value, state.toWireDocument())

/**
 * Proof transition: `WorkspaceInspectResultDocument -> WorkspaceInspectResult`.
 *
 * Establishes a refined canonical root and a closed workspace state.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw text extraction
 * is permitted only in this wire adapter.
 */
internal fun WorkspaceInspectResultDocument.toContract():
    WireDocumentConversion<WorkspaceInspectResult> = canonicalRoot.protocolText().mapConverted {
    canonicalRoot -> WorkspaceInspectResult(canonicalRoot, state.toContract())
}

internal fun SymbolDiscoverQualification.toReadDocument():
    SymbolDiscoverQualificationDocument = SymbolDiscoverQualificationDocument(
    limitations.map { it.toWireDocument() },
)

/**
 * Proof transition: `SymbolDiscoverQualificationDocument -> SymbolDiscoverQualification`.
 *
 * Establishes a non-empty, canonical limitation set.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw
 * document collections remain inside this wire adapter.
 */
internal fun SymbolDiscoverQualificationDocument.toContract():
    WireDocumentConversion<SymbolDiscoverQualification> = SymbolDiscoverQualification.from(
    limitations.map { it.toContract() }.toSet(),
).toWireDocumentConversion()

internal fun SymbolResolveRequest.toReadDocument(): SymbolResolveRequestDocument =
    SymbolResolveRequestDocument(candidateSelector.value)

/**
 * Proof transition: `SymbolResolveRequestDocument -> SymbolResolveRequest`.
 *
 * Establishes a refined candidate selector. [WireDocumentConversion.Rejected] is the
 * closed expected failure. Raw text extraction is permitted only here.
 */
internal fun SymbolResolveRequestDocument.toContract(): WireDocumentConversion<SymbolResolveRequest> =
    candidateSelector.protocolText().mapConverted(::SymbolResolveRequest)

internal fun SymbolResolveResult.toReadDocument(): SymbolResolveResultDocument =
    SymbolResolveResultDocument(exactSelector.value)

/**
 * Proof transition: `SymbolResolveResultDocument -> SymbolResolveResult`.
 *
 * Establishes a refined exact selector. [WireDocumentConversion.Rejected] is the
 * closed expected failure. Raw text extraction is permitted only here.
 */
internal fun SymbolResolveResultDocument.toContract(): WireDocumentConversion<SymbolResolveResult> =
    exactSelector.protocolText().mapConverted(::SymbolResolveResult)

internal fun SymbolDescribeRequest.toReadDocument(): SymbolDescribeRequestDocument =
    SymbolDescribeRequestDocument(exactSelector.value)

/**
 * Proof transition: `SymbolDescribeRequestDocument -> SymbolDescribeRequest`.
 *
 * Establishes a refined exact selector. [WireDocumentConversion.Rejected] is the
 * closed expected failure. Raw text extraction is permitted only here.
 */
internal fun SymbolDescribeRequestDocument.toContract(): WireDocumentConversion<SymbolDescribeRequest> =
    exactSelector.protocolText().mapConverted(::SymbolDescribeRequest)

internal fun RelationReadRequest.toReadDocument(): RelationReadRequestDocument =
    RelationReadRequestDocument(exactSelector.value, relation.toWireDocument(), limit.value)

/**
 * Proof transition: `RelationReadRequestDocument -> RelationReadRequest`.
 *
 * Establishes a refined exact selector, a closed relation kind, and a positive bounded limit.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw primitives
 * are extracted only in this wire adapter.
 */
internal fun RelationReadRequestDocument.toContract(): WireDocumentConversion<RelationReadRequest> =
    combineConverted(exactSelector.protocolText(), limit.protocolCount()) { selector, count ->
        RelationReadRequest(selector, relation.toContract(), count)
    }

internal fun TraversalRunRequest.toReadDocument(): TraversalRunRequestDocument =
    TraversalRunRequestDocument(
        exactSelector.value,
        relation.toWireDocument(),
        maximumDepth.value,
        maximumResults.value,
    )

/**
 * Proof transition: `TraversalRunRequestDocument -> TraversalRunRequest`.
 *
 * Establishes a refined exact selector, a closed relation kind, and positive bounded traversal
 * limits. [WireDocumentConversion.Rejected] is the closed expected failure. Raw primitives
 * are extracted only in this wire adapter.
 */
internal fun TraversalRunRequestDocument.toContract(): WireDocumentConversion<TraversalRunRequest> =
    combineConverted(
        exactSelector.protocolText(),
        maximumDepth.protocolCount(),
        maximumResults.protocolCount(),
    ) { selector, depth, results -> TraversalRunRequest(selector, relation.toContract(), depth, results) }

internal fun DiagnosticCheckRequest.toReadDocument(): DiagnosticCheckRequestDocument =
    DiagnosticCheckRequestDocument(scope.value, limit.value)

/**
 * Proof transition: `DiagnosticCheckRequestDocument -> DiagnosticCheckRequest`.
 *
 * Establishes a refined diagnostic scope and positive bounded limit.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw primitives
 * are extracted only in this wire adapter.
 */
internal fun DiagnosticCheckRequestDocument.toContract():
    WireDocumentConversion<DiagnosticCheckRequest> = combineConverted(
    scope.protocolText(),
    limit.protocolCount(),
    ::DiagnosticCheckRequest,
)

internal fun DiagnosticCheckResult.toReadDocument(): DiagnosticCheckResultDocument =
    DiagnosticCheckResultDocument(diagnostics.values.map { it.toWireDocument() })

/**
 * Proof transition: `DiagnosticCheckResultDocument -> DiagnosticCheckResult`.
 *
 * Establishes a bounded list of refined diagnostic text.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw
 * strings are extracted only in this wire adapter.
 */
internal fun DiagnosticCheckResultDocument.toContract(): WireDocumentConversion<DiagnosticCheckResult> =
    diagnostics.convertEach { it.toContract() }
        .flatMapConverted { values ->
            BoundedProtocolList.create(values).toWireDocumentConversion()
        }
        .mapConverted(::DiagnosticCheckResult)

private fun DiagnosticDocument.toWireDocument(): DiagnosticWireDocument = DiagnosticWireDocument(
    severity = severity.toWireDocument(),
    code = code.value,
    message = message.value,
    location = DiagnosticLocationWireDocument(
        location.candidateSelector.value,
        location.file.value,
        DiagnosticRangeWireDocument(
            location.range.startInclusive.value,
            location.range.endExclusive.value,
        ),
    ),
)

private fun DiagnosticWireDocument.toContract(): WireDocumentConversion<DiagnosticDocument> =
    combineConverted(
        code.protocolText(),
        message.protocolText(),
        location.toContract(),
    ) { code, message, location ->
        DiagnosticDocument(severity.toContract(), code, message, location)
    }

private fun DiagnosticLocationWireDocument.toContract():
    WireDocumentConversion<DiagnosticLocationDocument> = combineConverted(
    candidateSelector.protocolText(),
    file.protocolText(),
    range.toContract(),
    ::DiagnosticLocationDocument,
)

private fun DiagnosticRangeWireDocument.toContract(): WireDocumentConversion<DiagnosticRangeDocument> =
    combineConverted(
        ProtocolOffset.parse(startInclusive).toWireDocumentConversion(),
        ProtocolOffset.parse(endExclusive).toWireDocumentConversion(),
    ) { start, end -> start to end }
        .flatMapConverted { (start, end) ->
            DiagnosticRangeDocument.create(start, end).toWireDocumentConversion()
        }

private fun DiagnosticSeverityDocument.toWireDocument(): DiagnosticSeverityWireDocument =
    when (this) {
        DiagnosticSeverityDocument.ERROR -> DiagnosticSeverityWireDocument.ERROR
        DiagnosticSeverityDocument.WARNING -> DiagnosticSeverityWireDocument.WARNING
        DiagnosticSeverityDocument.INFO -> DiagnosticSeverityWireDocument.INFO
    }

private fun DiagnosticSeverityWireDocument.toContract(): DiagnosticSeverityDocument = when (this) {
    DiagnosticSeverityWireDocument.ERROR -> DiagnosticSeverityDocument.ERROR
    DiagnosticSeverityWireDocument.WARNING -> DiagnosticSeverityDocument.WARNING
    DiagnosticSeverityWireDocument.INFO -> DiagnosticSeverityDocument.INFO
}

/**
 * Proof transition: `String -> ProtocolText`.
 *
 * Establishes non-blank bounded protocol text.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw extraction is
 * permitted only in generated document conversion.
 */
private fun String.protocolText(): WireDocumentConversion<ProtocolText> = ProtocolText.parse(this)
    .toWireDocumentConversion()

/**
 * Proof transition: `Int -> ProtocolCount`.
 *
 * Establishes a positive count within the public protocol bound.
 * [WireDocumentConversion.Rejected] is the closed expected failure. Raw extraction is
 * permitted only in generated document conversion.
 */
private fun Int.protocolCount(): WireDocumentConversion<ProtocolCount> = ProtocolCount.parse(this)
    .toWireDocumentConversion()
