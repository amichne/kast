package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.protocol.wire.CompactSourceTextDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.CompactSourceContentDocument
import io.github.amichne.kast.protocol.wire.presentation.DiagnosticCoverageCliDocument
import io.github.amichne.kast.protocol.wire.presentation.RelationFactCliDocument
import io.github.amichne.kast.protocol.wire.presentation.SymbolCliDocument
import io.github.amichne.kast.protocol.wire.presentation.SymbolDiscoveryCliDocument
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** A positive semantic probe uses only exact identities returned by native reads. */
@Suppress("CyclomaticComplexMethod")
internal fun validateWorkspace(
    arguments: JsonObject,
    root: Path,
    invokeRead: (String, JsonObject) -> CliExit,
): CliExit {
    val request =
        try {
            validationInputJson.decodeFromJsonElement<McpValidationRequest>(arguments)
        } catch (_: SerializationException) {
            return invalidValidationRequest()
        }
    if (!request.valid(root)) return invalidValidationRequest()
    val validation = WorkspaceValidator(root, invokeRead)
    val declaration = request.declaration
    val discovery =
        declaration?.let { validation.discover(it) }
            ?: ExactDiscovery(McpProbe.unverified("No declaration probe requested"), null)
    val inspection =
        if (declaration != null && discovery.candidate != null) validation.inspect(declaration, discovery.candidate)
        else ExactInspection(McpProbe.unverified("Exact inspection requires one discovered declaration"), null)
    val source =
        inspection.symbol?.let { validation.source(it) }
            ?: McpProbe.unverified("Source read requires one exact inspected symbol")
    val relation =
        request.relation?.let { validation.relation(it, declaration, inspection.symbol) }
            ?: McpProbe.unverified("No relation probe requested")
    val diagnostics =
        request.diagnosticPath?.let { validation.diagnostics(it) }
            ?: McpProbe.unverified("No diagnostic path requested")
    return CliExit.Complete(
        validationResultFactory.create(
            McpValidationResult(
                data = McpValidationData(discovery.probe, inspection.probe, source, relation, diagnostics)
            )
        )
    )
}

private fun invalidValidationRequest(): CliExit.OperationRejected =
    CliExit.OperationRejected(
        validationRejectedFactory.create(
            McpValidationRejected(
                error = McpValidationError(McpValidationErrorCode.INVALID_REQUEST, "Invalid semantic probe request")
            )
        )
    )

private class WorkspaceValidator(
    private val root: Path,
    private val invokeRead: (String, JsonObject) -> CliExit,
) {
    fun discover(declaration: McpValidationDeclaration): ExactDiscovery {
        val response =
            read<McpDiscoveryDocument>(
                "symbol_lookup",
                validationInputJson
                    .encodeToJsonElement(
                        McpDiscoveryRequest(target = McpDiscoveryTarget(declaration.kind.lookupKind, declaration.name))
                    )
                    .jsonObject,
            )
        if (response !is NativeRead.Complete)
            return ExactDiscovery(response.unverified("Declaration discovery was not exhaustive"), null)
        val matching =
            response.value.items.filterIsInstance<SymbolDiscoveryCliDocument.Declaration>().filter {
                it.name == declaration.name &&
                    root.resolveProbePath(declaration.file)?.toString() == it.file &&
                    it.kind == declaration.kind.discoveryKind
            }
        return when (matching.size) {
            0 ->
                ExactDiscovery(
                    McpProbe.failed("No matching declaration in the requested file", response.evidence),
                    null,
                )
            1 ->
                ExactDiscovery(
                    McpProbe.passed("One exact declaration candidate was discovered", response.evidence),
                    matching.single().candidateSelector,
                )
            else ->
                ExactDiscovery(McpProbe.unverified("Multiple matching declaration candidates", response.evidence), null)
        }
    }

    fun inspect(declaration: McpValidationDeclaration, candidate: String): ExactInspection {
        val response =
            read<McpInspectionDocument>(
                "symbol_inspect",
                validationInputJson
                    .encodeToJsonElement(McpInspectionRequest(McpInspectionTarget(candidate)))
                    .jsonObject,
            )
        if (response !is NativeRead.Complete)
            return ExactInspection(response.unverified("Exact inspection was incomplete"), null)
        val symbol = response.value.symbol
        val nameAndKindMatch = symbol.name == declaration.name && symbol.kind == declaration.kind.inspectedKind
        val fileAndSelectorMatch =
            symbol.file == root.resolveProbePath(declaration.file)?.toString() && symbol.selector.startsWith("exact:")
        if (!nameAndKindMatch || !fileAndSelectorMatch)
            return ExactInspection(
                McpProbe.failed("Inspected identity differs from the requested declaration", response.evidence),
                null,
            )
        return ExactInspection(McpProbe.passed("Candidate refined to an exact symbol", response.evidence), symbol)
    }

    fun source(symbol: SymbolCliDocument): McpProbe {
        val response =
            read<McpSourceDocument>(
                "source_read",
                validationInputJson.encodeToJsonElement(McpSourceRequest(McpSourceAnchor(symbol.selector))).jsonObject,
            )
        if (response !is NativeRead.Complete) return response.unverified("Source text was incomplete or unavailable")
        val returned =
            response.value.content.filterIsInstance<CompactSourceContentDocument.Source>().singleOrNull()?.text
        return if (returned is CompactSourceTextDocument.Returned)
            McpProbe.passed("Exact symbol source text was returned", response.evidence)
        else McpProbe.unverified("The selected source text was withheld", response.evidence)
    }

    fun relation(
        probe: McpValidationRelation,
        declaration: McpValidationDeclaration?,
        inspected: SymbolCliDocument?,
    ): McpProbe {
        val source =
            endpoint(probe.source, declaration, inspected)
                ?: return McpProbe.unverified("Relation source could not be inspected exactly")
        val target =
            endpoint(probe.target, declaration, inspected)
                ?: return McpProbe.unverified("Relation target could not be inspected exactly")
        val subject = probe.kind.subjectSelector(source.selector, target.selector)
        val response =
            read<McpRelationDocument>(
                "read_relations",
                validationInputJson
                    .encodeToJsonElement(McpRelationRequest(subject, probe.kind.name.lowercase(), limit = 1000))
                    .jsonObject,
            )
        val facts =
            when (response) {
                is NativeRead.Complete -> response.value.relations
                is NativeRead.Partial -> response.value.relations
                is NativeRead.Rejected -> return response.unverified("Relation read was unavailable")
            }
        if (facts.any { it.source.selector == source.selector && it.target.selector == target.selector })
            return McpProbe.passed("Exact relation fact was returned", response.evidence)
        return if (response is NativeRead.Complete)
            McpProbe.failed("Exhaustive relation set did not contain the requested fact", response.evidence)
        else McpProbe.unverified("Relation set was incomplete; absence is unproven", response.evidence)
    }

    private fun endpoint(
        endpoint: McpValidationEndpoint,
        declaration: McpValidationDeclaration?,
        inspected: SymbolCliDocument?,
    ): SymbolCliDocument? {
        val isInspectedDeclaration = declaration != null && inspected != null && endpoint.name == declaration.name
        if (isInspectedDeclaration && root.resolveProbePath(endpoint.file)?.toString() == inspected.file)
            return inspected
        val response =
            read<McpDiscoveryDocument>(
                "symbol_lookup",
                validationInputJson
                    .encodeToJsonElement(McpDiscoveryRequest(target = McpDiscoveryTarget("symbol", endpoint.name)))
                    .jsonObject,
            )
        if (response !is NativeRead.Complete) return null
        val candidate =
            response.value.items.filterIsInstance<SymbolDiscoveryCliDocument.Declaration>().singleOrNull {
                it.name == endpoint.name && it.file == root.resolveProbePath(endpoint.file)?.toString()
            } ?: return null
        val inspectedEndpoint =
            read<McpInspectionDocument>(
                "symbol_inspect",
                validationInputJson
                    .encodeToJsonElement(McpInspectionRequest(McpInspectionTarget(candidate.candidateSelector)))
                    .jsonObject,
            )
        return (inspectedEndpoint as? NativeRead.Complete)?.value?.symbol?.takeIf {
            it.name == endpoint.name && it.file == candidate.file && it.selector.startsWith("exact:")
        }
    }

    fun diagnostics(path: String): McpProbe {
        val response =
            read<McpDiagnosticDocument>(
                "check_diagnostics",
                validationInputJson
                    .encodeToJsonElement(
                        McpDiagnosticRequest(relativePath = path, executionBudget = McpDiagnosticBudget())
                    )
                    .jsonObject,
            )
        if (response !is NativeRead.Complete)
            return response.unverified("IDE diagnostic scan was incomplete or unavailable")
        val coverage = response.value.coverage
        if (response.value.analysisKind != "IDE_FILE_DIAGNOSTICS" || coverage == null)
            return McpProbe.unverified("The diagnostic analysis kind or coverage was not reported", response.evidence)
        if (!coverage.exhaustive || coverage.filesDiscovered == null || coverage.filesSkipped != 0)
            return McpProbe.unverified("The requested diagnostic scope was not exhausted", response.evidence)
        if (coverage.filesAnalyzed == 0)
            return McpProbe.failed("No file was analyzed for IDE diagnostics", response.evidence)
        val requestedFile = root.resolveProbePath(path)?.toString()
        val pathProven =
            coverage.requestedPath == path ||
                (coverage.requestedPath == null &&
                    coverage.filesDiscovered == 1 &&
                    response.value.progress?.analyzedFiles == listOf(requestedFile))
        if (!pathProven)
            return McpProbe.unverified(
                "Diagnostic coverage could not be bound to the requested path",
                response.evidence,
            )
        val count = response.value.diagnostics.size
        val analyzed = coverage.filesAnalyzed
        val discovered = coverage.filesDiscovered
        return McpProbe.passed(
            "IDE diagnostics: $count findings in $analyzed of $discovered analyzed files; project build not run",
            response.evidence,
        )
    }

    private inline fun <reified T> read(name: String, request: JsonObject): NativeRead<T> {
        val exit = invokeRead(name, request)
        val evidence =
            try {
                validationOutputJson.parseToJsonElement(exit.document.value)
            } catch (_: SerializationException) {
                return NativeRead.Rejected(null)
            }
        if (exit !is CliExit.Complete && exit !is CliExit.Qualified) return NativeRead.Rejected(evidence)
        val header =
            try {
                validationOutputJson.decodeFromJsonElement<McpReadHeader>(evidence)
            } catch (_: SerializationException) {
                return NativeRead.Rejected(evidence)
            }
        if (header.status == McpValidationReadStatus.REJECTED) return NativeRead.Rejected(evidence)
        val value =
            try {
                validationOutputJson.decodeFromJsonElement<T>(evidence)
            } catch (_: SerializationException) {
                return NativeRead.Rejected(evidence)
            }
        return if (header.status == McpValidationReadStatus.COMPLETE && exit is CliExit.Complete)
            NativeRead.Complete(value, evidence)
        else NativeRead.Partial(value, evidence)
    }
}

/** Relation facts are oriented by the native contract, not by the probe's source field. */
internal fun McpValidationRelationKind.subjectSelector(source: String, target: String): String =
    when (this) {
        McpValidationRelationKind.CALLEES -> source
        McpValidationRelationKind.REFERENCES,
        McpValidationRelationKind.CALLERS,
        McpValidationRelationKind.IMPLEMENTATIONS,
        McpValidationRelationKind.INHERITORS,
        McpValidationRelationKind.OVERRIDES,
        McpValidationRelationKind.TYPE_USES -> target
    }

private data class ExactDiscovery(val probe: McpProbe, val candidate: String?)

private data class ExactInspection(val probe: McpProbe, val symbol: SymbolCliDocument?)

private sealed interface NativeRead<out T> {
    val evidence: JsonElement?

    data class Complete<T>(val value: T, override val evidence: JsonElement) : NativeRead<T>

    data class Partial<T>(val value: T, override val evidence: JsonElement) : NativeRead<T>

    data class Rejected(override val evidence: JsonElement?) : NativeRead<Nothing>
}

private fun NativeRead<*>.unverified(message: String): McpProbe = McpProbe.unverified(message, evidence)

@Serializable private data class McpReadHeader(val status: McpValidationReadStatus)

@Serializable
private enum class McpValidationReadStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("qualified") QUALIFIED,
    @SerialName("rejected") REJECTED,
}

@Serializable private data class McpDiscoveryDocument(val items: List<SymbolDiscoveryCliDocument>)

@Serializable private data class McpInspectionDocument(val symbol: SymbolCliDocument)

@Serializable private data class McpSourceDocument(val content: List<CompactSourceContentDocument>)

@Serializable private data class McpRelationDocument(val relations: List<RelationFactCliDocument>)

@Serializable
private data class McpDiagnosticDocument(
    val analysisKind: String,
    val diagnostics: List<McpDiagnosticFinding>,
    val coverage: DiagnosticCoverageCliDocument? = null,
    val progress: McpDiagnosticProgress? = null,
)

@Serializable private data class McpDiagnosticFinding(val severity: String, val code: String, val message: String)

@Serializable private data class McpDiagnosticProgress(val analyzedFiles: List<String>)

@Serializable private data class McpDiscoveryRequest(val limit: Int = 128, val target: McpDiscoveryTarget)

@Serializable
private data class McpDiscoveryTarget(
    val kind: String,
    val query: String,
    val type: String = "name",
    val match: String = "exact-name",
)

@Serializable private data class McpInspectionRequest(val target: McpInspectionTarget)

@Serializable private data class McpInspectionTarget(val selector: String, val type: String = "candidate")

@Serializable
private data class McpSourceRequest(
    val anchor: McpSourceAnchor,
    val region: McpSourceRegion = McpSourceRegion.DECLARATION,
    val text: McpSourceText = McpSourceText(),
    val entities: McpSourceEntities = McpSourceEntities(),
)

@Serializable private data class McpSourceAnchor(val symbolRef: String)

@Serializable
private enum class McpSourceRegion {
    @SerialName("declaration") DECLARATION
}

@Serializable
private data class McpSourceText(val mode: McpSourceTextMode = McpSourceTextMode.COMPLETE, val maxBytes: Int = 6000)

@Serializable
private enum class McpSourceTextMode {
    @SerialName("complete") COMPLETE
}

@Serializable private data class McpSourceEntities(val mode: McpSourceEntityMode = McpSourceEntityMode.NONE)

@Serializable
private enum class McpSourceEntityMode {
    @SerialName("none") NONE
}

@Serializable
private data class McpRelationRequest(
    val exactSelector: String,
    val relation: String,
    val limit: Int,
    val position: McpRelationPosition = McpRelationPosition(),
)

@Serializable private data class McpRelationPosition(val type: String = "start")

@Serializable
private data class McpDiagnosticRequest(
    @SerialName("relative_path") val relativePath: String,
    @SerialName("execution_budget") val executionBudget: McpDiagnosticBudget,
)

@Serializable private data class McpDiagnosticBudget(@SerialName("max_work_units") val maxWorkUnits: Int = 1_000_000)

private val validationInputJson = Json { encodeDefaults = true }
private val validationOutputJson = Json { ignoreUnknownKeys = true }
private val validationResultFactory = CanonicalJsonDocument.generated(McpValidationResult.serializer())
private val validationRejectedFactory = CanonicalJsonDocument.generated(McpValidationRejected.serializer())
