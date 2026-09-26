package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.protocol.wire.CompactSourceTextDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.CompactSourceContentDocument
import io.github.amichne.kast.protocol.wire.presentation.DiagnosticCoverageCliDocument
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
    val declarationResult =
        declaration?.let { validation.declaration(it) }
            ?: QueriedDeclaration(McpProbe.unverified("No declaration probe requested"), null)
    val source =
        declarationResult.symbol?.let { validation.source(it) }
            ?: McpProbe.unverified("Source read requires one exact queried symbol")
    val relation =
        request.relation?.let { validation.relation(it, declaration, declarationResult.symbol) }
            ?: McpProbe.unverified("No relation probe requested")
    val diagnostics =
        request.diagnosticPath?.let { validation.diagnostics(it) }
            ?: McpProbe.unverified("No diagnostic path requested")
    return CliExit.Complete(
        validationResultFactory.create(
            McpValidationResult(
                data = McpValidationData(declarationResult.probe, source, relation, diagnostics)
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
    fun declaration(declaration: McpValidationDeclaration): QueriedDeclaration {
        val response = queryDeclarations(declaration.name, declaration.kind)
        if (response !is NativeRead.Complete)
            return QueriedDeclaration(response.unverified("Declaration query was not exhaustive"), null)
        val matching = response.value.items.filter {
            it.name == declaration.name &&
                it.location?.file == root.resolveProbePath(declaration.file)?.toString() &&
                it.kind == declaration.kind.queryResultKind &&
                it.ref.startsWith("exact:")
        }
        return when (matching.size) {
            0 -> QueriedDeclaration(McpProbe.failed("No matching declaration in the requested file", response.evidence), null)
            1 -> QueriedDeclaration(McpProbe.passed("One exact declaration was queried", response.evidence), matching.single())
            else -> QueriedDeclaration(McpProbe.unverified("Multiple matching declarations", response.evidence), null)
        }
    }

    private fun queryDeclarations(name: String, kind: McpValidationKind? = null): NativeRead<McpQuerySymbolsDocument> =
        read(
            "query_symbols",
            validationInputJson.encodeToJsonElement(
                McpQueryDeclarationRequest(
                    McpQueryDeclarationAction(
                        McpQueryDeclarationSource(
                            declarationName = name,
                            declarationKinds = kind?.let { listOf(it.queryKind) },
                        )
                    )
                )
            ).jsonObject,
        )

    fun source(symbol: McpExactSymbol): McpProbe {
        val response =
            read<McpSourceDocument>(
                "source_read",
                validationInputJson.encodeToJsonElement(McpSourceRequest(McpSourceAnchor(symbol.ref))).jsonObject,
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
        inspected: McpExactSymbol?,
    ): McpProbe {
        val source =
            endpoint(probe.source, declaration, inspected)
                ?: return McpProbe.unverified("Relation source could not be inspected exactly")
        val target =
            endpoint(probe.target, declaration, inspected)
                ?: return McpProbe.unverified("Relation target could not be inspected exactly")
        val subject = probe.kind.subjectSelector(source.ref, target.ref)
        val response =
            read<McpQueryOccurrenceDocument>(
                "query_symbols",
                validationInputJson
                    .encodeToJsonElement(
                        McpQueryRelationRequest(
                            McpQueryRunAction(
                                McpQuerySymbolRefs(listOf(subject)),
                                listOf(McpQueryExpandRelation(probe.kind)),
                            )
                        )
                    )
                    .jsonObject,
            )
        val facts =
            when (response) {
                is NativeRead.Complete -> response.value.items.map { it.relation }
                is NativeRead.Partial -> response.value.items.map { it.relation }
                is NativeRead.Rejected -> return response.unverified("Query relation read was unavailable")
            }
        if (facts.any { it.source.selector == source.ref && it.target.selector == target.ref })
            return McpProbe.passed("Exact relation fact was returned", response.evidence)
        return if (response is NativeRead.Complete)
            McpProbe.failed("Exhaustive relation set did not contain the requested fact", response.evidence)
        else McpProbe.unverified("Relation set was incomplete; absence is unproven", response.evidence)
    }

    private fun endpoint(
        endpoint: McpValidationEndpoint,
        declaration: McpValidationDeclaration?,
        inspected: McpExactSymbol?,
    ): McpExactSymbol? {
        val isInspectedDeclaration = declaration != null && inspected != null && endpoint.name == declaration.name
        if (isInspectedDeclaration && root.resolveProbePath(endpoint.file)?.toString() == inspected.location?.file)
            return inspected
        val response = queryDeclarations(endpoint.name)
        if (response !is NativeRead.Complete) return null
        return response.value.items.singleOrNull {
            it.name == endpoint.name &&
                it.location?.file == root.resolveProbePath(endpoint.file)?.toString() &&
                it.ref.startsWith("exact:")
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

private data class QueriedDeclaration(val probe: McpProbe, val symbol: McpExactSymbol?)

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

@Serializable private data class McpQuerySymbolsDocument(val items: List<McpExactSymbol>)

@Serializable
private data class McpExactSymbol(
    val ref: String,
    val kind: String,
    val name: String?,
    val location: McpExactLocation?,
)

@Serializable private data class McpExactLocation(val file: String)

@Serializable private data class McpSourceDocument(val content: List<CompactSourceContentDocument>)

@Serializable
private data class McpDiagnosticDocument(
    val analysisKind: String,
    val diagnostics: List<McpDiagnosticFinding>,
    val coverage: DiagnosticCoverageCliDocument? = null,
    val progress: McpDiagnosticProgress? = null,
)

@Serializable private data class McpDiagnosticFinding(val severity: String, val code: String, val message: String)

@Serializable private data class McpDiagnosticProgress(val analyzedFiles: List<String>)

@Serializable private data class McpQueryDeclarationRequest(val request: McpQueryDeclarationAction)

@Serializable
private data class McpQueryDeclarationAction(
    val source: McpQueryDeclarationSource,
    val steps: List<McpQueryExpandRelation>? = null,
    val action: McpQueryAction = McpQueryAction.RUN,
)

@Serializable
private data class McpQueryDeclarationSource(
    @SerialName("declaration_name") val declarationName: String,
    @SerialName("declaration_kinds") val declarationKinds: List<String>? = null,
    val type: String = "search_declarations",
)

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
private data class McpDiagnosticRequest(
    @SerialName("relative_path") val relativePath: String,
    @SerialName("execution_budget") val executionBudget: McpDiagnosticBudget,
)

@Serializable private data class McpDiagnosticBudget(@SerialName("max_work_units") val maxWorkUnits: Int = 1_000_000)

private val validationInputJson = Json { encodeDefaults = true }
private val validationOutputJson = Json { ignoreUnknownKeys = true }
private val validationResultFactory = CanonicalJsonDocument.generated(McpValidationResult.serializer())
private val validationRejectedFactory = CanonicalJsonDocument.generated(McpValidationRejected.serializer())
