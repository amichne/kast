package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.bootstrap.HostedRejectionSchemas
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.cliName
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal data class ServerSchemaProperty(
    val name: String,
    val schema: JsonObject,
    val required: Boolean = true,
)

private val installedOperationOutputSchemas = ConcurrentHashMap<CanonicalOperation, JsonObject>()
private val installedOperationSemanticSchemas = ConcurrentHashMap<CanonicalOperation, JsonObject>()
private val installedToolSemanticSchemas = ConcurrentHashMap<PublicToolIdentity, JsonObject>()
private val installedToolOutputSchemas = ConcurrentHashMap<PublicToolIdentity, JsonObject>()

// Keys are closed protocol enums; schema construction consumes only immutable canonical contracts.
internal fun installedServerOutputSchema(operation: CanonicalOperation): JsonObject =
    installedOperationOutputSchemas.computeIfAbsent(operation) { buildInstalledServerOutputSchema(it) }

private fun buildInstalledServerOutputSchema(operation: CanonicalOperation): JsonObject =
    unionSchema(
            objectSchema(
                ServerSchemaProperty("status", constantSchema("completed", "Process outcome.")),
                ServerSchemaProperty("document", operationProcessDocumentSchema(operation)),
            ),
            objectSchema(
                ServerSchemaProperty("status", constantSchema("rejected", "Process outcome.")),
                ServerSchemaProperty("diagnostic", operationProcessDiagnosticSchema(operation)),
            ),
        )
        .withLocalOutputDefinitions()

/** The operation owns its semantic document across hosted, MCP, and RPC projections. */
internal fun installedSemanticResultSchema(operation: CanonicalOperation): JsonObject =
    installedOperationSemanticSchemas.computeIfAbsent(operation) {
        operationProcessDocumentSchema(it).withLocalOutputDefinitions()
    }

/** A facade may route distinct closed actions to their existing canonical result owners. */
internal fun installedPublicToolSemanticResultSchema(identity: PublicToolIdentity): JsonObject =
    installedToolSemanticSchemas.computeIfAbsent(identity) {
        publicToolProcessDocumentSchema(it).withLocalOutputDefinitions()
    }

internal fun installedPublicToolOutputSchema(identity: PublicToolIdentity): JsonObject =
    installedToolOutputSchemas.computeIfAbsent(identity) { buildInstalledPublicToolOutputSchema(it) }

private fun buildInstalledPublicToolOutputSchema(identity: PublicToolIdentity): JsonObject =
    unionSchema(
            objectSchema(
                ServerSchemaProperty("status", constantSchema("completed", "Process outcome.")),
                ServerSchemaProperty("document", publicToolProcessDocumentSchema(identity)),
            ),
            objectSchema(
                ServerSchemaProperty("status", constantSchema("rejected", "Process outcome.")),
                ServerSchemaProperty("diagnostic", operationProcessDiagnosticSchema(identity.operation)),
            ),
        )
        .withLocalOutputDefinitions()

private fun publicToolProcessDocumentSchema(identity: PublicToolIdentity): JsonObject =
    when (identity) {
        PublicToolIdentity.QUERY_SYMBOLS ->
            unionSchema(
                canonicalActionResultSchema(CanonicalOperation.QUERY_RUN),
                canonicalActionResultSchema(CanonicalOperation.SOURCE_READ),
            )
        else -> operationProcessDocumentSchema(identity.operation)
    }

/** Schema annotations preserve the canonical owner at the facade action union. */
private fun canonicalActionResultSchema(operation: CanonicalOperation): JsonObject =
    kotlinx.serialization.json.Json.encodeToJsonElement(
            operationProcessDocumentSchema(operation) + ("title" to JsonPrimitive(operation.id.value))
        )
        .jsonObject

/** Exact schema reuse keeps each advertised schema standalone and within the provider byte budget. */
private fun JsonObject.withLocalOutputDefinitions(): JsonObject {
    val names = reusableServerOutputSchemas.entries.associate { (name, schema) -> schema to name }
    val used = linkedSetOf<String>()
    val pending = ArrayDeque<String>()
    fun rewrite(value: JsonElement, allowReference: Boolean = true): JsonElement =
        when (value) {
            is JsonArray -> JsonArray(value.map { rewrite(it) })
            is JsonObject -> {
                val reference = value["\$ref"]?.jsonPrimitive?.content
                if (reference != null) {
                    require(reference.startsWith("#/\$defs/")) {
                        "Expected a local output schema reference: $reference"
                    }
                    val referencedName = reference.removePrefix("#/\$defs/")
                    require(referencedName in reusableServerOutputSchemas) {
                        "Missing output schema definition: $reference"
                    }
                    if (used.add(referencedName)) pending.addLast(referencedName)
                }
                val name = if (allowReference) names[value] else null
                if (name == null) {
                    JsonObject(value.mapValues { (_, child) -> rewrite(child) })
                } else {
                    if (used.add(name)) pending.addLast(name)
                    buildJsonObject { put("\$ref", "#/\$defs/$name") }
                }
            }
            else -> value
        }
    val root = rewrite(this, allowReference = false).jsonObject
    val definitions = linkedMapOf<String, JsonElement>()
    while (pending.isNotEmpty()) {
        val name = pending.removeFirst()
        definitions[name] = rewrite(reusableServerOutputSchemas.getValue(name), allowReference = false)
    }
    return if (definitions.isEmpty()) root else JsonObject(root + ("\$defs" to JsonObject(definitions)))
}

// Names are stable schema addresses; every referenced definition retains the exact existing shape.
private val reusableServerOutputSchemas: Map<String, JsonObject> by lazy {
    linkedMapOf(
            "sourceReadOperation" to
                constantSchema(CanonicalOperation.SOURCE_READ.id.value, "Canonical operation identity."),
            "queryRunOperation" to
                constantSchema(CanonicalOperation.QUERY_RUN.id.value, "Canonical operation identity."),
            "finiteFailureEvidence" to textSchema("Finite failure evidence."),
            "compilerQualifiedIdentity" to textSchema("Compiler qualified identity."),
            "compilerIdentity" to compilerIdentitySchema(),
            "workspaceInspectionObstruction" to
                generatedOutputSchema(
                    io.github.amichne.kast.protocol.contract.WorkspaceInspectionObstruction.serializer()
                ),
            "workspaceInspectionRefreshAttempt" to
                generatedOutputSchema(
                    io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshAttempt.serializer()
                ),
            "readRecoveryAction" to
                generatedOutputSchema(io.github.amichne.kast.protocol.contract.ReadRecoveryAction.serializer()),
            "executionBudget" to
                generatedOutputSchema(io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.serializer()),
            "executionLimit" to
                generatedOutputSchema(io.github.amichne.kast.protocol.contract.ExecutionLimitDocument.serializer()),
            "impactSemanticBasis" to
                generatedOutputSchema(
                    io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument.serializer()
                ),
            "impactDeclarationReference" to
                generatedOutputSchema(
                    io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument.serializer()
                ),
            "impactInvocationReference" to
                generatedOutputSchema(
                    io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument.serializer()
                ),
            "impactValueRole" to
                generatedOutputSchema(io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument.serializer()),
            "impactValueSiteReference" to
                generatedOutputSchema(
                    io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument.serializer()
                ),
            "impactCompilerTransfer" to
                generatedOutputSchema(
                    io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument.serializer()
                ),
            "liveReadEvidence" to liveReadEvidenceSchema(),
            "hostedEndpointRejection" to HostedRejectionSchemas.endpoint,
            "hostedReadRejection" to HostedRejectionSchemas.read,
            "callbackObservation" to
                generatedOutputSchema(
                    io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
                        .callbackObservationSerializer
                ),
            "callableObservation" to
                generatedOutputSchema(
                    io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
                        .callableObservationSerializer
                ),
            "queryResultItem" to queryResultItemSchema(),
            "localDeclarationAddress" to
                generatedOutputSchema(
                    io.github.amichne.kast.protocol.wire.presentation.LocalDeclarationAddressCliDocument.serializer()
                ),
            "queryItemFailure" to queryItemFailureSchema(),
            "ExactSymbolRef" to queryTypedPropertySchema("exact-symbol", "ref"),
            "CandidateRef" to queryOutputReferenceSchema("declaration-candidate"),
            "ContinuationRef" to queryContinuationSchema(),
            "queryRejection" to queryRejectionSchema(),
            "sourceReadRejection" to canonicalReadRejectionSchema(CanonicalOperation.SOURCE_READ),
            "compilerFunctionSignature" to functionCompilerSignatureSchema(),
            "compilerReceiver" to compilerReceiverSchema(),
            "sourceRange" to sourceRangeSchema(),
            "symbol" to symbolSchema(),
            "symbolDiscovery" to symbolDiscoverySchema(),
            "relationFact" to relationFactSchema(),
            "queryQualification" to queryQualificationSchema(),
            "queryTerminalReason" to queryTerminalReasonSchema(),
            "publishedSourceSnapshot" to sourceSnapshotSchema(ServerReadEvidenceShape.PUBLISHED),
            "liveSourceSnapshot" to sourceSnapshotSchema(ServerReadEvidenceShape.LIVE),
            "sourceSelection" to sourceSelectionSchema(),
            "sourceRegion" to sourceRegionSchema(),
            "sourceEntity" to sourceEntitySchema(),
            "sourceTextProjection" to sourceTextProjectionSchema(),
            "sourceQualification" to sourceReadQualificationSchema(),
            "diagnostic" to diagnosticSchema(),
            "gradleJvmObservation" to gradleJvmSelectionObservationSchema(),
            "gradleJvmReport" to gradleJvmSelectionReportSchema(),
            "gradleJvmCandidate" to gradleJvmCandidateSchema(),
            "gradleJvmOutcome" to gradleJvmSelectionOutcomeSchema(),
        )
        .apply {
            for ((name, serializer) in
                io.github.amichne.kast.protocol.wire.presentation.CanonicalCallbackSchemaDocuments.serializers) {
                check(name !in this) { "Duplicate canonical callback schema address: $name" }
                this[name] = generatedOutputSchema(serializer)
            }
            for ((name, definition) in
                HostedRejectionSchemas.readDefinitions.entries + HostedRejectionSchemas.endpointDefinitions.entries) {
                check(name !in this || this[name] == definition) {
                    "Conflicting hosted output schema definition: $name"
                }
                put(name, definition.jsonObject)
            }
        }
}

private fun operationProcessDiagnosticSchema(operation: CanonicalOperation): JsonObject =
    if (operation == CanonicalOperation.WORKSPACE_LIFECYCLE)
        unionSchema(
            processDiagnosticSchema(),
            generatedOutputSchema(io.github.amichne.kast.protocol.contract.IdeLifecycleRejection.serializer()),
        )
    else processDiagnosticSchema()

private fun operationProcessDocumentSchema(operation: CanonicalOperation): JsonObject =
    if (operation.supportsLiveEvidence()) {
        unionSchema(
            operationDocumentSchema(operation),
            HostedRejectionSchemas.forOperation(operation),
            HostedRejectionSchemas.read,
        )
    } else {
        operationDocumentSchema(operation)
    }

private fun operationDocumentSchema(operation: CanonicalOperation): JsonObject =
    when (operation) {
        CanonicalOperation.CHANGE ->
            generatedOutputSchema(io.github.amichne.kast.protocol.contract.ChangeRunDocument.serializer())
        CanonicalOperation.WORKSPACE_LIFECYCLE ->
            generatedOutputSchema(io.github.amichne.kast.protocol.contract.IdeLifecycleResult.serializer())
        CanonicalOperation.INDEX_SYNC ->
            outcomeSchema(
                operation,
                ServerSchemaProperty(
                    "state",
                    enumSchema(listOf("synchronized", "unchanged"), "Index synchronization result."),
                ),
            )
        CanonicalOperation.TOPOLOGY_BUILD -> topologyBuildDocumentSchema(operation)
        CanonicalOperation.SOURCE_READ -> sourceReadOutputSchema(operation)
        CanonicalOperation.QUERY_RUN -> queryRunDocumentSchema
        CanonicalOperation.DIAGNOSTIC_CHECK ->
            proofQualifiedOutcomeSchema(
                operation,
                diagnosticQualificationSchema(),
                ServerSchemaProperty("diagnostics", arraySchema(diagnosticSchema())),
                ServerSchemaProperty(
                    "analysisKind",
                    generatedOutputSchema(
                        io.github.amichne.kast.protocol.wire.presentation.DiagnosticAnalysisKindCliDocument.serializer()
                    ),
                ),
                ServerSchemaProperty(
                    "coverage",
                    generatedOutputSchema(
                        io.github.amichne.kast.protocol.wire.presentation.DiagnosticCoverageCliDocument.serializer()
                    ),
                    required = false,
                ),
                ServerSchemaProperty(
                    "progress",
                    generatedOutputSchema(
                        io.github.amichne.kast.protocol.contract.DiagnosticProgressDocument.serializer()
                    ),
                    required = false,
                ),
            )
        CanonicalOperation.CHANGE_PLAN ->
            outcomeSchema(
                operation,
                ServerSchemaProperty("planIdentity", textSchema("Durable change plan identity.")),
                ServerSchemaProperty("changes", changeFilePreviewsSchema()),
            )
        CanonicalOperation.CHANGE_APPLY -> changeApplicationDocumentSchema(operation)
        CanonicalOperation.CHANGE_RECOVER ->
            outcomeSchema(
                operation,
                ServerSchemaProperty("state", textSchema("Recovered workspace state.")),
            )
    }

internal fun changeFilePreviewsSchema(): JsonObject =
    nonEmptyArraySchema(
        objectSchema(
            ServerSchemaProperty("path", workspaceFileSchema()),
            ServerSchemaProperty(
                "kind",
                enumSchema(listOf("add", "delete", "update"), "Closed file-change kind."),
            ),
            ServerSchemaProperty("diff", textSchema("Bounded semantic change preview.")),
        )
    )

private val queryRunDocumentSchema: JsonObject by lazy {
    unionSchema(
        generatedOutputSchema(
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.completeSerializer
        ),
        generatedOutputSchema(
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.qualifiedSerializer
        ),
        generatedOutputSchema(
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.rejectedSerializer
        ),
    )
}

private fun queryTerminalReasonSchema(): JsonObject =
    nullableSchema(
        generatedOutputSchema(
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.terminalReasonSerializer
        )
    )

private fun queryQualificationSchema(): JsonObject =
    generatedOutputSchema(
        io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.qualificationSerializer
    )

private fun queryResultItemSchema(): JsonObject =
    generatedOutputSchema(io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.itemSerializer)

private fun queryTypedPropertySchema(kind: String, property: String): JsonObject =
    queryResultItemSchema()
        .getValue("anyOf")
        .jsonArray
        .map { it.jsonObject }
        .single { item ->
            item
                .getValue("properties")
                .jsonObject
                .getValue("type")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .single()
                .jsonPrimitive
                .content == kind
        }
        .getValue("properties")
        .jsonObject
        .getValue(property)
        .jsonObject

private fun queryContinuationSchema(): JsonObject =
    generatedOutputSchema(
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.qualifiedSerializer
        )
        .getValue("properties")
        .jsonObject
        .getValue("continuation")
        .jsonObject
        .getValue("anyOf")
        .jsonArray
        .first()
        .jsonObject

/** Syntax identifies the reference family; only the existing semantic owner can admit its authority. */
private fun queryOutputReferenceSchema(kind: String): JsonObject =
    patternTextSchema(
        if (kind == "exact-symbol") "^exact:v[2345]:" else "^candidate:v[2345]:",
        "Opaque reference. Copy verbatim into the next request; never decode or reconstruct it.",
    )

private fun queryItemFailureSchema(): JsonObject =
    generatedOutputSchema(
        io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.itemFailureSerializer
    )

private fun queryRejectionSchema(): JsonObject =
    generatedOutputSchema(
        io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.rejectionSerializer
    )

private fun outcomeSchema(
    operation: CanonicalOperation,
    vararg payload: ServerSchemaProperty,
): JsonObject =
    proofQualifiedOutcomeSchema(
        operation,
        textSchema("Closed qualification reason."),
        *payload,
    )

internal fun proofQualifiedOutcomeSchema(
    operation: CanonicalOperation,
    qualificationSchema: JsonObject,
    vararg payload: ServerSchemaProperty,
): JsonObject =
    unionSchema(
        operationOutcomeVariant(operation, "complete", *payload),
        operationOutcomeVariant(
            operation,
            "qualified",
            *payload,
            ServerSchemaProperty("qualification", qualificationSchema),
        ),
        operationOutcomeVariant(
            operation,
            "rejected",
            ServerSchemaProperty("reason", canonicalReadRejectionSchema(operation)),
            *readRecoveryActionProperties(operation),
        ),
        *admittedReadRejectionVariants(operation),
    )

private fun admittedReadRejectionVariants(operation: CanonicalOperation): Array<JsonObject> =
    when (operation) {
        CanonicalOperation.SOURCE_READ,
        CanonicalOperation.DIAGNOSTIC_CHECK ->
            arrayOf(
                operationOutcomeVariant(
                    operation,
                    "rejected",
                    ServerSchemaProperty("reason", canonicalReadRejectionSchema(operation)),
                    *readRecoveryActionProperties(operation),
                    ServerSchemaProperty(
                        "execution_budget",
                        generatedOutputSchema(
                            io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.serializer()
                        ),
                    ),
                )
            )
        else -> emptyArray()
    }

internal fun sourceReadQualificationSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty(
            "progress",
            generatedOutputSchema(
                io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument.serializer()
            ),
        ),
        ServerSchemaProperty(
            "knownMinimumEntityCount",
            integerSchema(0, description = "Known minimum matching entity count."),
        ),
        ServerSchemaProperty(
            "limitations",
            nonEmptyArraySchema(
                enumSchema(
                    SourceReadLimitationDocument.entries.map { it.cliName() },
                    "Every source-read coverage limitation.",
                )
            ),
        ),
        ServerSchemaProperty(
            "continuation",
            unionSchema(
                objectSchema(
                    ServerSchemaProperty(
                        "type",
                        constantSchema("unavailable", "Continuation state."),
                    )
                ),
                objectSchema(
                    ServerSchemaProperty(
                        "type",
                        constantSchema("available", "Continuation state."),
                    ),
                    ServerSchemaProperty(
                        "continuation",
                        textSchema("Snapshot-bound continuation proof."),
                    ),
                ),
            ),
        ),
    )

internal enum class ServerReadEvidenceShape {
    PUBLISHED,
    LIVE,
}

private fun liveReadEvidenceSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty(
            "root",
            buildJsonObject {
                put("type", "string")
                put("pattern", "^/[^\\x00-\\x1F\\x7F]*$")
                put("maxLength", 4096)
                put("description", "Canonical root of the admitted live project.")
            },
        ),
        ServerSchemaProperty(
            "host",
            patternTextSchema(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                "Original live host incarnation.",
            ),
            required = false,
        ),
        ServerSchemaProperty(
            "epoch",
            buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", Long.MAX_VALUE)
                put("description", "Observed live content and model epoch, never a publication generation.")
            },
            required = false,
        ),
        ServerSchemaProperty(
            "contentView",
            constantSchema("SAVED_PSI_COMMITTED", "Saved and PSI-committed live content."),
        ),
        ServerSchemaProperty("version", integerSchema(1, 1, "Live evidence representation version."), required = false),
    )

internal fun sourceSnapshotSchema(basis: ServerReadEvidenceShape = ServerReadEvidenceShape.PUBLISHED): JsonObject =
    objectSchema(
        ServerSchemaProperty("canonicalRoot", textSchema("Canonical workspace root.")),
        *when (basis) {
            ServerReadEvidenceShape.PUBLISHED ->
                arrayOf(
                    ServerSchemaProperty("generation", integerSchema(0, description = "Semantic generation.")),
                    ServerSchemaProperty("sourceState", textSchema("Workspace source-state identity.")),
                )
            ServerReadEvidenceShape.LIVE -> arrayOf(ServerSchemaProperty("live", liveReadEvidenceSchema()))
        },
        ServerSchemaProperty("file", textSchema("Exact workspace source file.")),
        ServerSchemaProperty("textIdentity", textSchema("Committed document text identity.")),
        ServerSchemaProperty(
            "coordinateUnit",
            constantSchema("utf16-code-unit", "Source coordinate unit."),
        ),
        ServerSchemaProperty("length", integerSchema(0, description = "Document UTF-16 length.")),
    )

private fun sourceSelectionSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("selector", textSchema("Reusable exact source selector.")),
        ServerSchemaProperty("range", diagnosticRangeSchema()),
    )

internal fun sourceRegionSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty(
            "kind",
            enumSchema(
                listOf("anchor", "declaration", "callable-body", "class-body", "file", "window"),
                "Established structural region kind.",
            ),
        ),
        ServerSchemaProperty("selection", sourceSelectionSchema()),
    )

internal fun sourceEntitySchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty("type", constantSchema("declaration", "Source entity kind.")),
            ServerSchemaProperty(
                "kind",
                enumSchema(
                    listOf("classlike", "constructor", "function", "property", "type-alias"),
                    "Declaration kind.",
                ),
            ),
            ServerSchemaProperty("name", textSchema("Declaration source name.")),
            ServerSchemaProperty(
                "visibility",
                enumSchema(
                    listOf("public", "protected", "internal", "private", "local"),
                    "Compiler-established visibility.",
                ),
            ),
            *sourceEntityCommonProperties(),
            ServerSchemaProperty("semanticIdentity", sourceDeclarationSemanticIdentitySchema()),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("value-parameter", "Source entity kind.")),
            ServerSchemaProperty("name", textSchema("Value-parameter name.")),
            *sourceEntityCommonProperties(),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("call", "Source entity kind.")),
            *sourceEntityCommonProperties(),
            ServerSchemaProperty("callee", sourceSelectionSchema()),
            ServerSchemaProperty("target", sourceEntityTargetSchema()),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("reference", "Source entity kind.")),
            ServerSchemaProperty("name", textSchema("Referenced source name.")),
            *sourceEntityCommonProperties(),
            ServerSchemaProperty("target", sourceEntityTargetSchema()),
        ),
    )

private fun sourceEntityCommonProperties(): Array<ServerSchemaProperty> =
    arrayOf(
        ServerSchemaProperty("nestingDepth", integerSchema(0, description = "Structural nesting depth.")),
        ServerSchemaProperty("parentSelector", textSchema("Exact structural parent selector.")),
        ServerSchemaProperty("selection", sourceSelectionSchema()),
    )

private fun sourceDeclarationSemanticIdentitySchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("type", constantSchema("candidate", "Semantic identity state.")),
        ServerSchemaProperty("selector", textSchema("Resolvable declaration candidate selector.")),
    )

private fun sourceEntityTargetSchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty("type", constantSchema("candidate", "Semantic target state.")),
            ServerSchemaProperty("selector", textSchema("Resolvable target candidate selector.")),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("local", "Semantic target state.")),
            ServerSchemaProperty("selector", textSchema("Exact local source selector.")),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("unresolved", "Semantic target state.")),
            ServerSchemaProperty(
                "reason",
                enumSchema(
                    listOf("name-not-found", "ambiguous", "error-type", "unsupported-target"),
                    "Compiler-established unresolved reason.",
                ),
            ),
        ),
    )

internal fun sourceTextProjectionSchema(): JsonObject =
    unionSchema(
        objectSchema(ServerSchemaProperty("type", constantSchema("not-requested", "Text projection state."))),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("returned", "Text projection state.")),
            ServerSchemaProperty("selection", sourceSelectionSchema()),
            ServerSchemaProperty("text", sourceTextSchema()),
            ServerSchemaProperty(
                "lines",
                objectSchema(
                    ServerSchemaProperty("startInclusive", integerSchema(1, description = "First one-based line.")),
                    ServerSchemaProperty("endInclusive", integerSchema(1, description = "Last one-based line.")),
                ),
            ),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("withheld", "Text projection state.")),
            ServerSchemaProperty(
                "reason",
                enumSchema(
                    listOf("byte-limit-reached", "provider-unavailable"),
                    "Explicit reason source text was withheld.",
                ),
            ),
        ),
    )

private fun diagnosticQualificationSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("continuation", textSchema("Retained same-basis diagnostic progress."), required = false),
        ServerSchemaProperty(
            "retentionFailure",
            generatedOutputSchema(
                io.github.amichne.kast.protocol.contract.DiagnosticRetentionFailureDocument.serializer()
            ),
            required = false,
        ),
        ServerSchemaProperty(
            "knownDiagnosticCount",
            integerSchema(0, description = "Known diagnostic count before result truncation."),
        ),
        ServerSchemaProperty(
            "resultLimitReached",
            buildJsonObject {
                put("type", "boolean")
                put("description", "Whether returned diagnostics were truncated by the request limit.")
            },
        ),
        ServerSchemaProperty(
            "analyzedFiles",
            arraySchema(textSchema("Exact analyzed diagnostic source file.")),
        ),
        ServerSchemaProperty(
            "limitations",
            arraySchema(
                objectSchema(
                    ServerSchemaProperty("file", textSchema("Limited diagnostic source file.")),
                    ServerSchemaProperty(
                        "reason",
                        enumSchema(
                            listOf(
                                "file-unavailable",
                                "outside-source-content",
                                "indexing",
                                "psi-unavailable",
                                "unsupported-file-kind",
                                "unsupported-diagnostic",
                                "analysis-unavailable",
                            ),
                            "Exact per-file diagnostic limitation.",
                        ),
                    ),
                )
            ),
        ),
    )

internal fun operationOutcomeVariant(
    operation: CanonicalOperation,
    status: String,
    vararg payload: ServerSchemaProperty,
): JsonObject = operationOutcomeVariant(operation, status, payload.toList())

internal fun operationOutcomeVariant(
    operation: CanonicalOperation,
    status: String,
    payload: List<ServerSchemaProperty>,
): JsonObject {
    val identity =
        listOf(
            ServerSchemaProperty("operation", constantSchema(operation.id.value, "Canonical operation identity.")),
            ServerSchemaProperty("status", constantSchema(status, "Canonical operation outcome.")),
        )
    val published = objectSchema(identity + payload)
    if (!operation.supportsLiveEvidence() || status !in setOf("complete", "qualified")) return published
    val livePayload = payload.map { property ->
        when {
            operation == CanonicalOperation.SOURCE_READ && property.name == "content" ->
                ServerSchemaProperty("content", compactSourceContentSchema(ServerReadEvidenceShape.LIVE))
            operation == CanonicalOperation.SOURCE_READ && property.name == "snapshot" ->
                ServerSchemaProperty("snapshot", sourceSnapshotSchema(ServerReadEvidenceShape.LIVE))
            else -> property
        }
    }
    // The closed variants make top-level and nested Published/Live shapes mutually exclusive.
    return buildJsonObject {
        putJsonArray("oneOf") {
            add(published)
            add(objectSchema(identity + livePayload + ServerSchemaProperty("live", liveReadEvidenceSchema())))
        }
    }
}

private fun topologyBuildDocumentSchema(operation: CanonicalOperation): JsonObject {
    val result =
        arrayOf(
            ServerSchemaProperty("snapshotStatus", textSchema("Topology snapshot status.")),
            ServerSchemaProperty("generation", integerSchema(0, description = "Evidence generation.")),
            ServerSchemaProperty("digest", textSchema("Topology snapshot digest.")),
        )
    return unionSchema(
        operationOutcomeVariant(operation, "complete", *result),
        operationOutcomeVariant(
            operation,
            "qualified",
            *result,
            ServerSchemaProperty("qualification", textSchema("Closed qualification reason.")),
        ),
        operationOutcomeVariant(
            operation,
            "rejected",
            ServerSchemaProperty("reason", textSchema("Closed rejection reason.")),
        ),
        operationOutcomeVariant(
            operation,
            "rejected",
            ServerSchemaProperty("reason", textSchema("Closed rejection reason.")),
            ServerSchemaProperty("failure", textSchema("Topology failure detail.")),
        ),
        operationOutcomeVariant(
            operation,
            "rejected",
            ServerSchemaProperty("reason", textSchema("Closed rejection reason.")),
            ServerSchemaProperty("file", textSchema("Rejected topology source file.")),
            ServerSchemaProperty("failure", textSchema("Topology extraction failure.")),
        ),
        topologyCoverageRejectedSchema(operation),
    )
}

private fun topologyCoverageRejectedSchema(operation: CanonicalOperation): JsonObject =
    operationOutcomeVariant(
        operation,
        "rejected",
        ServerSchemaProperty("reason", constantSchema("coverage-incomplete", "Rejection reason.")),
        ServerSchemaProperty("missing", finiteArraySchema(textSchema("Missing source path."))),
        ServerSchemaProperty("unexpected", finiteArraySchema(textSchema("Unexpected source path."))),
        ServerSchemaProperty(
            "duplicateCandidates",
            finiteArraySchema(textSchema("Duplicate candidate source path.")),
        ),
        ServerSchemaProperty(
            "duplicateCompletions",
            finiteArraySchema(textSchema("Duplicate completion source path.")),
        ),
        ServerSchemaProperty(
            "workspaceMismatches",
            finiteArraySchema(textSchema("Workspace-mismatched source path.")),
        ),
        ServerSchemaProperty(
            "candidateEvidenceMismatches",
            finiteArraySchema(topologyCoverageCandidateEvidenceMismatchSchema()),
        ),
        ServerSchemaProperty(
            "duplicateSymbols",
            finiteArraySchema(topologyCoverageNodeSchema()),
        ),
        ServerSchemaProperty(
            "missingEdgeTargets",
            finiteArraySchema(topologyCoverageNodeSchema()),
        ),
        ServerSchemaProperty(
            "mismatchedEdgeEndpoints",
            finiteArraySchema(topologyCoverageSymbolSchema()),
        ),
    )

private fun topologyCoverageCandidateEvidenceMismatchSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("candidate", topologyCoverageFileEvidenceSchema()),
        ServerSchemaProperty("completed", topologyCoverageFileEvidenceSchema()),
    )

private fun topologyCoverageNodeSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("compilerIdentity", compilerIdentitySchema()),
        ServerSchemaProperty("file", textSchema("Exact topology source file.")),
        ServerSchemaProperty("range", sourceRangeSchema()),
    )

private fun topologyCoverageSymbolSchema(): JsonObject =
    unionSchema(
        topologyCoverageSymbolVariantSchema("classlike", classLikeCompilerSignatureSchema()),
        topologyCoverageSymbolVariantSchema("constructor", functionCompilerSignatureSchema()),
        topologyCoverageSymbolVariantSchema("function", functionCompilerSignatureSchema()),
        topologyCoverageSymbolVariantSchema("property", propertyCompilerSignatureSchema()),
        topologyCoverageSymbolVariantSchema("type-alias", typeAliasCompilerSignatureSchema()),
    )

private fun topologyCoverageSymbolVariantSchema(
    kind: String,
    signature: JsonObject,
): JsonObject =
    objectSchema(
        ServerSchemaProperty("node", topologyCoverageNodeSchema()),
        ServerSchemaProperty("fileEvidence", topologyCoverageFileEvidenceSchema()),
        ServerSchemaProperty("name", textSchema("Topology symbol name.")),
        ServerSchemaProperty("qualifiedIdentity", topologyCoverageQualifiedIdentitySchema()),
        ServerSchemaProperty("kind", constantSchema(kind, "Compiler symbol kind.")),
        ServerSchemaProperty("compilerEvidence", compilerEvidenceSchema(signature)),
    )

private fun topologyCoverageQualifiedIdentitySchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("state", constantSchema("available", "Identity state.")),
        ServerSchemaProperty("value", textSchema("Compiler qualified identity.")),
    )

private fun topologyCoverageFileEvidenceSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty(
            "workspace",
            objectSchema(
                ServerSchemaProperty("root", textSchema("Canonical workspace root.")),
                ServerSchemaProperty(
                    "generation",
                    integerSchema(0, description = "Evidence generation."),
                ),
                ServerSchemaProperty("sourceState", textSchema("Workspace source-state identity.")),
            ),
        ),
        ServerSchemaProperty(
            "sourceRoot",
            objectSchema(
                ServerSchemaProperty("module", textSchema("IDE module identity.")),
                ServerSchemaProperty("buildRoot", textSchema("Workspace-relative build root.")),
                ServerSchemaProperty("projectPath", textSchema("Gradle project path.")),
                ServerSchemaProperty("sourceSet", textSchema("Gradle source-set name.")),
                ServerSchemaProperty("location", textSchema("Workspace-relative source root.")),
                ServerSchemaProperty(
                    "provenance",
                    enumSchema(
                        listOf("authored", "generated", "unknown-excluded-from-source-model"),
                        "Source-root provenance.",
                    ),
                ),
            ),
        ),
        ServerSchemaProperty("path", textSchema("Workspace-relative source path.")),
        ServerSchemaProperty("contentHash", sha256Schema("Exact source content hash.")),
    )

private fun symbolSchema(): JsonObject =
    unionSchema(
        symbolVariantSchema("classlike", classLikeCompilerSignatureSchema()),
        symbolVariantSchema("constructor", functionCompilerSignatureSchema()),
        symbolVariantSchema("function", functionCompilerSignatureSchema()),
        symbolVariantSchema("property", propertyCompilerSignatureSchema()),
        symbolVariantSchema("type-alias", typeAliasCompilerSignatureSchema()),
    )

private fun symbolVariantSchema(kind: String, signature: JsonObject): JsonObject =
    objectSchema(
        ServerSchemaProperty("selector", textSchema("Exact generation-bound selector.")),
        ServerSchemaProperty("kind", constantSchema(kind, "Compiler symbol kind.")),
        ServerSchemaProperty("name", textSchema("Source declaration name.")),
        ServerSchemaProperty("qualifiedIdentity", textSchema("Compiler qualified identity.")),
        ServerSchemaProperty("file", textSchema("Exact source file.")),
        ServerSchemaProperty("range", sourceRangeSchema()),
        ServerSchemaProperty("compilerEvidence", compilerEvidenceSchema(signature)),
    )

private fun compilerEvidenceSchema(signature: JsonObject): JsonObject =
    objectSchema(
        ServerSchemaProperty("identity", compilerIdentitySchema()),
        ServerSchemaProperty("signature", signature),
    )

private fun functionCompilerSignatureSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("type", constantSchema("function", "Signature variant.")),
        ServerSchemaProperty("qualifiedIdentity", textSchema("Compiler qualified identity.")),
        ServerSchemaProperty("receiver", compilerReceiverSchema()),
        ServerSchemaProperty("contextReceivers", arraySchema(textSchema("Compiler type."))),
        ServerSchemaProperty("valueParameters", arraySchema(textSchema("Compiler type."))),
        ServerSchemaProperty(
            "typeParameterCount",
            integerSchema(0, description = "Exact type parameter count."),
        ),
    )

private fun propertyCompilerSignatureSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("type", constantSchema("property", "Signature variant.")),
        ServerSchemaProperty("qualifiedIdentity", textSchema("Compiler qualified identity.")),
        ServerSchemaProperty("receiver", compilerReceiverSchema()),
        ServerSchemaProperty("contextReceivers", arraySchema(textSchema("Compiler type."))),
        ServerSchemaProperty("returnType", textSchema("Canonical compiler return type.")),
    )

private fun typeAliasCompilerSignatureSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("type", constantSchema("type-alias", "Signature variant.")),
        ServerSchemaProperty("qualifiedIdentity", textSchema("Compiler qualified identity.")),
    )

private fun classLikeCompilerSignatureSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("type", constantSchema("class-like", "Signature variant.")),
        ServerSchemaProperty("qualifiedIdentity", textSchema("Compiler qualified identity.")),
    )

private fun compilerIdentitySchema(): JsonObject = buildJsonObject {
    put("type", "string")
    put("pattern", "^canonical-signature-sha256-v1\\|[0-9a-f]{64}$")
    put("description", "Identity derived from the exact canonical compiler signature.")
}

private fun compilerReceiverSchema(): JsonObject =
    unionSchema(
        objectSchema(ServerSchemaProperty("type", constantSchema("absent", "Receiver state."))),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("present", "Receiver state.")),
            ServerSchemaProperty("compilerType", textSchema("Canonical compiler receiver type.")),
        ),
    )

private fun relationFactSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("meaning", relationSchema()),
        ServerSchemaProperty("source", symbolSchema()),
        ServerSchemaProperty("target", symbolSchema()),
        ServerSchemaProperty(
            "occurrence",
            objectSchema(
                ServerSchemaProperty("candidateSelector", textSchema("Occurrence candidate selector.")),
                ServerSchemaProperty("file", textSchema("Exact occurrence file.")),
                ServerSchemaProperty("range", sourceRangeSchema()),
            ),
        ),
        ServerSchemaProperty(
            "provenance",
            enumSchema(
                listOf("k2-authored-source", "k2-generated-source", "k2-project-library"),
                "Compiler and source-root provenance.",
            ),
        ),
        ServerSchemaProperty(
            "coverage",
            constantSchema("exact-compiler-confirmed", "Per-edge compiler coverage proof."),
        ),
    )

private fun diagnosticSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty(
            "severity",
            enumSchema(listOf("error", "warning", "info"), "Compiler diagnostic severity."),
        ),
        ServerSchemaProperty("code", textSchema("Compiler diagnostic code.")),
        ServerSchemaProperty("message", textSchema("Compiler diagnostic message.")),
        ServerSchemaProperty(
            "location",
            objectSchema(
                ServerSchemaProperty("candidateSelector", textSchema("Diagnostic candidate selector.")),
                ServerSchemaProperty("file", textSchema("Diagnostic source file.")),
                ServerSchemaProperty("range", diagnosticRangeSchema()),
            ),
        ),
    )

private fun symbolDiscoverySchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty("type", constantSchema("file", "Discovery evidence variant.")),
            ServerSchemaProperty("candidateSelector", textSchema("Candidate selector.")),
            ServerSchemaProperty("name", textSchema("File name.")),
            ServerSchemaProperty("file", textSchema("Discovered file.")),
        ),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema("declaration", "Discovery evidence variant."),
            ),
            ServerSchemaProperty("candidateSelector", textSchema("Candidate selector.")),
            ServerSchemaProperty("kind", enumSchema(listOf("file", "class", "symbol"), "Kind.")),
            ServerSchemaProperty("name", textSchema("Declaration name.")),
            ServerSchemaProperty("file", textSchema("Declaration file.")),
            ServerSchemaProperty("offset", integerSchema(0, description = "Declaration offset.")),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("text-match", "Discovery evidence variant.")),
            ServerSchemaProperty("candidateSelector", textSchema("Candidate selector.")),
            ServerSchemaProperty("query", textSchema("Matched query.")),
            ServerSchemaProperty("file", textSchema("Matched file.")),
            ServerSchemaProperty("range", sourceRangeSchema()),
        ),
    )

private fun sourceRangeSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("startInclusive", integerSchema(0, description = "Start offset.")),
        ServerSchemaProperty("endExclusive", integerSchema(1, description = "Exclusive end offset.")),
    )

private fun diagnosticRangeSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("startInclusive", integerSchema(0, description = "Start offset.")),
        ServerSchemaProperty("endExclusive", integerSchema(0, description = "Exclusive end offset.")),
    )

internal fun typeOnlyFailureSchema(type: String): JsonObject =
    objectSchema(ServerSchemaProperty("type", constantSchema(type, "Closed failure variant.")))

internal fun typedFailureSchema(type: String, field: String): JsonObject =
    objectSchema(
        ServerSchemaProperty("type", constantSchema(type, "Closed failure variant.")),
        ServerSchemaProperty(field, textSchema("Finite failure evidence.")),
    )

internal fun objectSchema(vararg properties: ServerSchemaProperty): JsonObject = objectSchema(properties.toList())

internal fun objectSchema(properties: List<ServerSchemaProperty>): JsonObject =
    objectSchemaWithRequired(properties.filter { it.required }.map { it.name }.toSet(), *properties.toTypedArray())

internal fun objectSchemaWithRequired(
    required: Set<String>,
    vararg properties: ServerSchemaProperty,
): JsonObject = buildJsonObject {
    require(required.all { requiredName -> properties.any { it.name == requiredName } })
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        properties.forEach { property -> put(property.name, property.schema) }
    }
    putJsonArray("required") {
        properties
            .filter { it.name in required }
            .forEach { property ->
                add(JsonPrimitive(property.name))
            }
    }
}

internal fun unionSchema(vararg variants: JsonObject): JsonObject = unionSchema(variants.toList())

internal fun unionSchema(variants: List<JsonObject>): JsonObject = buildJsonObject {
    putJsonArray("anyOf") {
        variants.forEach(::add)
    }
}

internal fun nullableSchema(value: JsonObject): JsonObject =
    unionSchema(
        value,
        buildJsonObject { put("type", "null") },
    )

internal fun arraySchema(item: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", item)
    put("maxItems", MAXIMUM_PROTOCOL_COUNT)
}

internal fun nonEmptyArraySchema(item: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", item)
    put("minItems", 1)
    put("maxItems", MAXIMUM_PROTOCOL_COUNT)
}

internal fun uniqueArraySchema(item: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", item)
    put("uniqueItems", true)
    put("maxItems", MAXIMUM_PROTOCOL_COUNT)
}

internal fun uniqueNonEmptyArraySchema(item: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", item)
    put("uniqueItems", true)
    put("minItems", 1)
    put("maxItems", MAXIMUM_PROTOCOL_COUNT)
}

internal fun finiteArraySchema(item: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", item)
}

internal fun textSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("minLength", 1)
    put("maxLength", MAXIMUM_PROTOCOL_TEXT_LENGTH)
    put("description", description)
}

internal fun sourceTextSchema(): JsonObject = buildJsonObject {
    put("type", "string")
    put("maxLength", MAXIMUM_PROTOCOL_TEXT_LENGTH)
    put("description", "Exact normalized source text; empty files remain valid.")
}

internal fun patternTextSchema(pattern: String, description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("minLength", 1)
    put("maxLength", MAXIMUM_PROTOCOL_TEXT_LENGTH)
    put("pattern", pattern)
    put("description", description)
}

internal fun booleanSchema(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun workspaceFileSchema(): JsonObject = buildJsonObject {
    put("type", "string")
    put("minLength", 1)
    put("maxLength", MAXIMUM_WORKSPACE_FILE_LENGTH)
    put("description", "Workspace-relative file path.")
}

internal fun sha256Schema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("pattern", "^[0-9a-f]{64}$")
    put("description", description)
}

internal fun uuidSchema(description: String): JsonObject =
    patternTextSchema(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        description,
    )

internal fun countSchema(description: String): JsonObject =
    integerSchema(
        minimum = 1,
        maximum = MAXIMUM_PROTOCOL_COUNT,
        description = description,
    )

internal fun integerSchema(
    minimum: Int,
    maximum: Int? = null,
    description: String,
): JsonObject = buildJsonObject {
    put("type", "integer")
    put("minimum", minimum)
    maximum?.let { put("maximum", it) }
    put("description", description)
}

internal fun constantSchema(value: String, description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("const", value)
    put("description", description)
}

internal fun enumSchema(values: List<String>, description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun relationSchema(): JsonObject =
    enumSchema(
        values =
            listOf(
                "references",
                "callers",
                "callees",
                "implementations",
                "inheritors",
                "overrides",
                "type-uses",
            ),
        description = "One canonical Kast semantic relation.",
    )

internal fun executionBudgetProperty() =
    ServerSchemaProperty(
        "execution_budget",
        nullableSchema(
            generatedOutputSchema(io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.serializer())
        ),
        required = false,
    )

private fun readRecoveryActionProperty(): ServerSchemaProperty =
    ServerSchemaProperty(
        "next_action",
        generatedOutputSchema(io.github.amichne.kast.protocol.contract.ReadRecoveryAction.serializer()),
    )

private fun readRecoveryActionProperties(operation: CanonicalOperation): Array<ServerSchemaProperty> =
    when (operation) {
        CanonicalOperation.SOURCE_READ -> arrayOf(readRecoveryActionProperty())
        CanonicalOperation.DIAGNOSTIC_CHECK ->
            arrayOf(
                ServerSchemaProperty(
                    "next_action",
                    generatedOutputSchema(
                        io.github.amichne.kast.protocol.contract.DiagnosticRecoveryAction.serializer()
                    ),
                )
            )
        else -> emptyArray()
    }

internal fun referenceAcquisitionsProperty() =
    ServerSchemaProperty(
        "reference_acquisitions",
        generatedOutputSchema(io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions.serializer()),
        required = false,
    )
