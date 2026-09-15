package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.bootstrap.HostedRejectionSchemas
import io.github.amichne.kast.cli.command.CliCommandSurface
import io.github.amichne.kast.cli.projection.cliName
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.registry.AgentToolDefinition
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.HostedBindingCompleteness
import io.github.amichne.kast.protocol.registry.HostedOperationProjection
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val MAXIMUM_PROTOCOL_TEXT_LENGTH = 1_048_576
internal const val MAXIMUM_WORKSPACE_FILE_LENGTH = 4_096
internal const val MAXIMUM_PROTOCOL_COUNT = 1_000

private const val SERVER_PROJECTION_SCHEMA_VERSION = 13
private const val HOSTED_BOOTSTRAP_SCHEMA_VERSION = 1
private const val CLI_INVOCATIONS_SCHEMA_VERSION = 3

/** One closed server-facing projection owned by this installed command graph. */
@Serializable
internal data class InstalledServerProjectionDocument(
    val schemaVersion: Int,
    val namespace: String,
    val hostedBootstrap: InstalledHostedBootstrapDocument,
    val cliInvocations: InstalledCliInvocationsDocument,
)

@Serializable
internal data class InstalledHostedBootstrapDocument(
    val schemaVersion: Int,
    val policy: String,
    val tools: List<InstalledHostedToolDocument>,
)

@Serializable
internal data class InstalledHostedToolDocument(
    val operationId: String,
    val name: String,
    val description: String,
    val deferLoading: Boolean,
    val effect: String,
    val approvalPolicy: String,
    val executionBudget: InstalledServerExecutionBudgetDocument,
    val inputSchema: JsonElement,
    val outputSchema: JsonElement,
)

@Serializable
internal data class InstalledServerExecutionBudgetDocument(
    val readinessMillis: Long,
    val operationMillis: Long,
)

@Serializable
internal data class InstalledCliInvocationsDocument(
    val schemaVersion: Int,
    val operations: List<InstalledCliOperationInvocationDocument>,
)

@Serializable
internal data class InstalledCliOperationInvocationDocument(
    val toolName: String,
    val operationId: String,
    val cliUsage: String,
    val invocation: InstalledServerCliInvocationDocument,
)

@Serializable
internal data class InstalledServerCliInvocationDocument(
    val type: InstalledServerInvocationType,
    val command: List<String>,
)

@Serializable
internal enum class InstalledServerInvocationType {
    CLI
}

/** One public hosted tool retained with its canonical operation and exact CLI invocation. */
internal class InstalledServerBinding
private constructor(
    val operation: CanonicalOperation,
    val tool: InstalledHostedToolDocument,
    val invocation: InstalledCliOperationInvocationDocument,
) {
    companion object {
        /** Derives the complete binding set without accepting independently associated members. */
        fun from(commandSurface: CliCommandSurface): List<InstalledServerBinding> {
            val commandByOperation = commandSurface.semanticCommands.associateBy { it.operation }
            val facadeByIdentity = commandSurface.toolCommands.associateBy { it.identity }
            val toolsByOperation = installedServerTools.associateBy(InstalledServerTool::operation)
            return CanonicalAgentToolDefinitions.all.map { definition ->
                val operation = definition.operation.operation
                val tool = toolsByOperation.getValue(operation)
                InstalledServerBinding(
                    operation = operation,
                    tool = tool.hostedDocument(definition),
                    invocation =
                        when (val input = definition.inputBinding) {
                            AgentToolInputBinding.Canonical ->
                                tool.cliInvocationDocument(
                                    definition.name.value,
                                    commandByOperation.getValue(operation).usage,
                                )
                            is AgentToolInputBinding.Facade ->
                                InstalledCliOperationInvocationDocument(
                                    toolName = definition.name.value,
                                    operationId = operation.id.value,
                                    cliUsage = facadeByIdentity.getValue(input.identity).usage,
                                    invocation =
                                        InstalledServerCliInvocationDocument(
                                            InstalledServerInvocationType.CLI,
                                            listOf("tool", input.identity.toolName),
                                        ),
                                )
                        },
                )
            }
        }
    }
}

/**
 * Proof transition: `CliCommandSurface -> InstalledServerProjectionDocument`.
 *
 * The proven canonical command graph supplies exact operation usage while this closed projection supplies the
 * corresponding server name, JSON shapes, and CLI binding grammar. The resulting document is the sole broker-facing
 * authority for an installed executable; no runtime or filesystem input is interpreted here.
 */
internal fun installedServerProjection(commandSurface: CliCommandSurface): InstalledServerProjectionDocument {
    val bindings = installedServerBindings(commandSurface)
    return InstalledServerProjectionDocument(
        schemaVersion = SERVER_PROJECTION_SCHEMA_VERSION,
        namespace = "kast",
        hostedBootstrap =
            InstalledHostedBootstrapDocument(
                schemaVersion = HOSTED_BOOTSTRAP_SCHEMA_VERSION,
                policy = CanonicalAgentToolDefinitions.policy.text,
                tools = bindings.map(InstalledServerBinding::tool),
            ),
        cliInvocations =
            InstalledCliInvocationsDocument(
                schemaVersion = CLI_INVOCATIONS_SCHEMA_VERSION,
                operations = bindings.map(InstalledServerBinding::invocation),
            ),
    )
}

/**
 * Proof transition: `CliCommandSurface -> List<InstalledServerBinding>`.
 *
 * Retains the canonical operation while joining its hosted schema and CLI invocation. Consumers can project another
 * representation without reconstructing operation identity from JSON text.
 */
internal fun installedServerBindings(commandSurface: CliCommandSurface): List<InstalledServerBinding> =
    InstalledServerBinding.from(commandSurface)

private val installedServerTools: List<InstalledServerTool> =
    InstalledServerTool.entries
        .filter { tool ->
            HostedOperationProjection.publicDefinitions.any { it.operation == tool.operation }
        }
        .sortedBy { tool -> CanonicalOperation.entries.indexOf(tool.operation) }
        .also {
            val operations = it.map { tool -> tool.operation }
            when (val completeness = HostedOperationProjection.verifyBindings(operations)) {
                HostedBindingCompleteness.Complete -> Unit
                is HostedBindingCompleteness.Rejected ->
                    error("Invalid installed server projection: ${completeness.failures}")
            }
        }

private enum class InstalledServerTool(
    val operation: CanonicalOperation,
    private val requestSerializer: KSerializer<*>,
    private val command: List<String>,
) {
    INDEX_SYNC(
        operation = CanonicalOperation.INDEX_SYNC,
        requestSerializer = IndexSyncRequest.serializer(),
        command = listOf("index", "sync"),
    ),
    TOPOLOGY_BUILD(
        operation = CanonicalOperation.TOPOLOGY_BUILD,
        requestSerializer = TopologyBuildRequest.serializer(),
        command = listOf("topology", "build"),
    ),
    SYMBOL_DISCOVER(
        operation = CanonicalOperation.SYMBOL_DISCOVER,
        requestSerializer = SymbolDiscoverRequest.serializer(),
        command = listOf("symbol", "discover"),
    ),
    SYMBOL_INSPECT(
        operation = CanonicalOperation.SYMBOL_INSPECT,
        requestSerializer = SymbolInspectRequest.serializer(),
        command = listOf("symbol", "inspect"),
    ),
    SOURCE_READ(
        operation = CanonicalOperation.SOURCE_READ,
        requestSerializer = SourceReadRequest.serializer(),
        command = listOf("source", "read"),
    ),
    RELATION_READ(
        operation = CanonicalOperation.RELATION_READ,
        requestSerializer = RelationReadRequest.serializer(),
        command = listOf("relation", "read"),
    ),
    TRAVERSAL_RUN(
        operation = CanonicalOperation.TRAVERSAL_RUN,
        requestSerializer = TraversalRunRequest.serializer(),
        command = listOf("traversal", "run"),
    ),
    QUERY_RUN(
        operation = CanonicalOperation.QUERY_RUN,
        requestSerializer = QueryRunRequest.serializer(),
        command = listOf("query", "run"),
    ),
    DIAGNOSTIC_CHECK(
        operation = CanonicalOperation.DIAGNOSTIC_CHECK,
        requestSerializer = DiagnosticCheckRequest.serializer(),
        command = listOf("diagnostic", "check"),
    ),
    CHANGE_PLAN(
        operation = CanonicalOperation.CHANGE_PLAN,
        requestSerializer = ChangePlanRequest.serializer(),
        command = listOf("change", "plan"),
    ),
    CHANGE_APPLY(
        operation = CanonicalOperation.CHANGE_APPLY,
        requestSerializer = ChangeApplyRequest.serializer(),
        command = listOf("change", "apply"),
    ),
    CHANGE_RECOVER(
        operation = CanonicalOperation.CHANGE_RECOVER,
        requestSerializer = ChangeRecoverRequest.serializer(),
        command = listOf("change", "recover"),
    );

    fun hostedDocument(definition: AgentToolDefinition): InstalledHostedToolDocument =
        InstalledHostedToolDocument(
            operationId = definition.operation.id.value,
            name = definition.name.value,
            description = definition.description.value,
            deferLoading = definition.loading == HostedToolLoading.DEFERRED,
            effect = definition.operation.effect.name.lowercase(),
            approvalPolicy = definition.approval.name.lowercase(),
            executionBudget =
                InstalledServerExecutionBudgetDocument(
                    readinessMillis = OperationExecutionBudget.WORKSPACE_READINESS.value,
                    operationMillis = OperationExecutionBudget.forOperation(operation).operation.value,
                ),
            inputSchema =
                when (val input = definition.inputBinding) {
                    is AgentToolInputBinding.Facade -> PublicToolContract.parameters(input.identity)
                    AgentToolInputBinding.Canonical ->
                        generatedHostedRequestSchema(requestSerializer, definition.operation.hostedVariants)
                },
            outputSchema = installedServerOutputSchema(operation),
        )

    fun cliInvocationDocument(toolName: String, cliUsage: String): InstalledCliOperationInvocationDocument =
        InstalledCliOperationInvocationDocument(
            toolName = toolName,
            operationId = operation.id.value,
            cliUsage = cliUsage,
            invocation =
                InstalledServerCliInvocationDocument(
                    type = InstalledServerInvocationType.CLI,
                    command = command,
                ),
        )
}

internal data class ServerSchemaProperty(
    val name: String,
    val schema: JsonObject,
    val required: Boolean = true,
)

internal fun installedServerOutputSchema(operation: CanonicalOperation): JsonObject =
    unionSchema(
            objectSchema(
                ServerSchemaProperty("status", constantSchema("completed", "Process outcome.")),
                ServerSchemaProperty("document", operationProcessDocumentSchema(operation)),
            ),
            objectSchema(
                ServerSchemaProperty("status", constantSchema("rejected", "Process outcome.")),
                ServerSchemaProperty("diagnostic", processDiagnosticSchema()),
            ),
        )
        .withLocalOutputDefinitions()

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
            "relationReadOperation" to
                constantSchema(CanonicalOperation.RELATION_READ.id.value, "Canonical operation identity."),
            "traversalRunOperation" to
                constantSchema(CanonicalOperation.TRAVERSAL_RUN.id.value, "Canonical operation identity."),
            "queryRunOperation" to
                constantSchema(CanonicalOperation.QUERY_RUN.id.value, "Canonical operation identity."),
            "finiteFailureEvidence" to textSchema("Finite failure evidence."),
            "compilerQualifiedIdentity" to textSchema("Compiler qualified identity."),
            "compilerIdentity" to compilerIdentitySchema(),
            "readRecoveryAction" to
                generatedRequestSchema(io.github.amichne.kast.protocol.contract.ReadRecoveryAction.serializer()),
            "executionBudget" to
                generatedRequestSchema(io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.serializer()),
            "executionLimit" to
                generatedRequestSchema(io.github.amichne.kast.protocol.contract.ExecutionLimitDocument.serializer()),
            "liveReadEvidence" to liveReadEvidenceSchema(),
            "hostedEndpointRejection" to HostedRejectionSchemas.endpoint,
            "hostedReadRejection" to HostedRejectionSchemas.read,
            "queryResultItem" to queryResultItemSchema(),
            "queryItemFailure" to queryItemFailureSchema(),
            "ExactSymbolRef" to queryOutputReferenceSchema("exact-symbol"),
            "CandidateRef" to queryOutputReferenceSchema("declaration-candidate"),
            "ContinuationRef" to textSchema("Opaque snapshot and pipeline-bound next page handle."),
            "queryRejection" to queryRejectionSchema(),
            "sourceReadRejection" to canonicalReadRejectionSchema(CanonicalOperation.SOURCE_READ),
            "relationReadRejection" to canonicalReadRejectionSchema(CanonicalOperation.RELATION_READ),
            "traversalRunRejection" to canonicalReadRejectionSchema(CanonicalOperation.TRAVERSAL_RUN),
            "compilerFunctionSignature" to functionCompilerSignatureSchema(),
            "compilerReceiver" to compilerReceiverSchema(),
            "sourceRange" to sourceRangeSchema(),
            "symbol" to symbolSchema(),
            "symbolDiscovery" to symbolDiscoverySchema(),
            "relationFact" to relationFactSchema(),
            "relationOmission" to relationOmissionSchema(),
            "queryQualification" to queryQualificationSchema(),
            "queryTerminalReason" to queryTerminalReasonSchema(),
            "publishedSourceSnapshot" to sourceSnapshotSchema(ServerReadEvidenceShape.PUBLISHED),
            "liveSourceSnapshot" to sourceSnapshotSchema(ServerReadEvidenceShape.LIVE),
            "sourceSelection" to sourceSelectionSchema(),
            "sourceRegion" to sourceRegionSchema(),
            "sourceEntity" to sourceEntitySchema(),
            "sourceTextProjection" to sourceTextProjectionSchema(),
            "sourceQualification" to sourceReadQualificationSchema(),
            "traversalQualification" to traversalQualificationSchema(),
            "relationQualification" to relationQualificationSchema(),
            "publishedTraversalGraph" to normalizedTraversalGraphSchema(ServerReadEvidenceShape.PUBLISHED),
            "liveTraversalGraph" to normalizedTraversalGraphSchema(ServerReadEvidenceShape.LIVE),
            "diagnostic" to diagnosticSchema(),
            "gradleJvmObservation" to gradleJvmSelectionObservationSchema(),
            "gradleJvmReport" to gradleJvmSelectionReportSchema(),
            "gradleJvmCandidate" to gradleJvmCandidateSchema(),
            "gradleJvmOutcome" to gradleJvmSelectionOutcomeSchema(),
        )
        .apply {
            for ((name, definition) in
                HostedRejectionSchemas.readDefinitions.entries + HostedRejectionSchemas.endpointDefinitions.entries) {
                check(name !in this || this[name] == definition) {
                    "Conflicting hosted output schema definition: $name"
                }
                put(name, definition.jsonObject)
            }
        }
}

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
        CanonicalOperation.INDEX_SYNC ->
            outcomeSchema(
                operation,
                ServerSchemaProperty(
                    "state",
                    enumSchema(listOf("synchronized", "unchanged"), "Index synchronization result."),
                ),
            )
        CanonicalOperation.TOPOLOGY_BUILD -> topologyBuildDocumentSchema(operation)
        CanonicalOperation.SYMBOL_DISCOVER ->
            outcomeSchema(
                operation,
                ServerSchemaProperty("items", arraySchema(symbolDiscoverySchema())),
            )
        CanonicalOperation.SYMBOL_INSPECT ->
            outcomeSchema(
                operation,
                ServerSchemaProperty("symbol", symbolSchema()),
                ServerSchemaProperty(
                    "acquisition",
                    enumSchema(listOf("strict", "reacquired"), "Exact inspection authority acquisition."),
                ),
            )
        CanonicalOperation.SOURCE_READ -> sourceReadOutputSchema(operation)
        CanonicalOperation.RELATION_READ ->
            proofQualifiedOutcomeSchema(
                operation,
                relationQualificationSchema(),
                ServerSchemaProperty("relations", arraySchema(relationFactSchema())),
                executionBudgetProperty(),
                ServerSchemaProperty("omissions", arraySchema(relationOmissionSchema())),
                ServerSchemaProperty(
                    "soundness",
                    constantSchema(
                        "EXACT_RETURNED_FACTS",
                        "Returned facts retain exact proof independently of enumeration coverage.",
                    ),
                ),
            )
        CanonicalOperation.TRAVERSAL_RUN ->
            proofQualifiedOutcomeSchema(
                operation,
                traversalQualificationSchema(),
                executionBudgetProperty(),
                ServerSchemaProperty("graph", normalizedTraversalGraphSchema()),
                ServerSchemaProperty(
                    "partialExpansions",
                    arraySchema(
                        objectSchema(
                            ServerSchemaProperty(
                                "subject",
                                textSchema("Exact reference to the partially expanded node."),
                            ),
                            ServerSchemaProperty(
                                "depth",
                                integerSchema(0, description = "Subject depth; the start node is depth zero."),
                            ),
                            ServerSchemaProperty("limitations", relationLimitationsSchema()),
                            ServerSchemaProperty(
                                "remainder",
                                enumSchema(
                                    listOf("continuation_retained", "not_explored"),
                                    "Disposition of unenumerated neighbors; no omitted subtree count is inferred.",
                                ),
                            ),
                            ServerSchemaProperty(
                                "scope",
                                constantSchema("page", "Only qualified node reads performed on this page."),
                            ),
                        )
                    ),
                ),
                ServerSchemaProperty(
                    "progress",
                    objectSchema(
                        ServerSchemaProperty(
                            "checkpointSequence",
                            integerSchema(0, description = "Monotonic committed checkpoint sequence."),
                        ),
                        ServerSchemaProperty(
                            "totalReads",
                            integerSchema(0, description = "Cumulative committed one-hop reads."),
                        ),
                        ServerSchemaProperty(
                            "totalEdges",
                            integerSchema(0, description = "Cumulative emitted relation edges."),
                        ),
                        ServerSchemaProperty(
                            "maximumDepthReached",
                            integerSchema(0, description = "Deepest emitted edge."),
                        ),
                    ),
                ),
                ServerSchemaProperty(
                    "strategy",
                    unionSchema(
                        objectSchema(
                            ServerSchemaProperty(
                                "type",
                                constantSchema("breadth_first", "Exhaust each breadth-first frontier."),
                            )
                        ),
                        objectSchema(
                            ServerSchemaProperty(
                                "type",
                                constantSchema(
                                    "bounded_fan_out",
                                    "Bound each node expansion and retain qualified coverage.",
                                ),
                            ),
                            ServerSchemaProperty(
                                "maximumEdgesPerNode",
                                countSchema("Maximum edges expanded per node."),
                            ),
                        ),
                    ),
                ),
            )
        CanonicalOperation.QUERY_RUN -> queryRunDocumentSchema(operation)
        CanonicalOperation.DIAGNOSTIC_CHECK ->
            proofQualifiedOutcomeSchema(
                operation,
                diagnosticQualificationSchema(),
                ServerSchemaProperty("diagnostics", arraySchema(diagnosticSchema())),
                ServerSchemaProperty(
                    "progress",
                    generatedRequestSchema(
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

private fun queryRunDocumentSchema(operation: CanonicalOperation): JsonObject =
    unionSchema(
        operationOutcomeVariant(
            operation,
            "complete",
            ServerSchemaProperty("items", arraySchema(queryResultItemSchema())),
            executionBudgetProperty(),
            ServerSchemaProperty("failures", arraySchema(queryItemFailureSchema())),
        ),
        operationOutcomeVariant(
            operation,
            "qualified",
            ServerSchemaProperty(
                "continuation",
                nullableSchema(textSchema("Opaque snapshot and pipeline-bound next page handle.")),
            ),
            ServerSchemaProperty(
                "terminal_reason",
                queryTerminalReasonSchema(),
            ),
            ServerSchemaProperty("items", arraySchema(queryResultItemSchema())),
            executionBudgetProperty(),
            ServerSchemaProperty("failures", arraySchema(queryItemFailureSchema())),
            ServerSchemaProperty(
                "qualification",
                queryQualificationSchema(),
            ),
        ),
        operationOutcomeVariant(
            operation,
            "rejected",
            ServerSchemaProperty("rejection", queryRejectionSchema()),
            readRecoveryActionProperty(),
        ),
        operationOutcomeVariant(
            operation,
            "rejected",
            ServerSchemaProperty("rejection", queryRejectionSchema()),
            readRecoveryActionProperty(),
            ServerSchemaProperty(
                "execution_budget",
                generatedRequestSchema(io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.serializer()),
            ),
        ),
    )

private fun queryTerminalReasonSchema(): JsonObject =
    nullableSchema(
        enumSchema(
            listOf(
                "upstream-incomplete",
                "output-item-too-large",
                "checkpoint-capacity-exceeded",
                "no-progress",
            ),
            "Why incomplete enumeration cannot continue.",
        )
    )

private fun queryQualificationSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("knownMinimum", integerSchema(0, description = "Known returned item count.")),
        ServerSchemaProperty(
            "progress",
            generatedRequestSchema(
                io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument.serializer()
            ),
        ),
        ServerSchemaProperty(
            "limitations",
            nonEmptyArraySchema(
                enumSchema(
                    listOf(
                        "result-limit-reached",
                        "byte-limit-reached",
                        "work-limit-reached",
                        "time-limit-reached",
                        "discovery-incomplete",
                        "refinement-incomplete",
                        "visibility-incomplete",
                        "relation-incomplete",
                    ),
                    "Every aggregate query limitation.",
                )
            ),
        ),
    )

private fun queryResultItemSchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty("type", constantSchema("candidate", "Declaration-candidate result.")),
            ServerSchemaProperty("ref", queryOutputReferenceSchema("declaration-candidate")),
            ServerSchemaProperty("kind", enumSchema(listOf("class", "symbol"), "Candidate discovery kind.")),
            ServerSchemaProperty("name", nullableSchema(textSchema("Projected declaration name."))),
            ServerSchemaProperty(
                "location",
                nullableSchema(
                    objectSchema(
                        ServerSchemaProperty("file", textSchema("Workspace-relative source file.")),
                        ServerSchemaProperty("offset", integerSchema(0, description = "Declaration offset.")),
                    )
                ),
            ),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("exact-symbol", "Exact-symbol result.")),
            ServerSchemaProperty("ref", queryOutputReferenceSchema("exact-symbol")),
            ServerSchemaProperty(
                "kind",
                enumSchema(
                    listOf("classlike", "constructor", "function", "property", "type-alias"),
                    "Compiler symbol kind.",
                ),
            ),
            ServerSchemaProperty("name", nullableSchema(textSchema("Projected declaration name."))),
            ServerSchemaProperty(
                "location",
                nullableSchema(
                    objectSchema(
                        ServerSchemaProperty("file", textSchema("Workspace-relative source file.")),
                        ServerSchemaProperty("range", sourceRangeSchema()),
                    )
                ),
            ),
            ServerSchemaProperty(
                "signature",
                nullableSchema(
                    unionSchema(
                        functionCompilerSignatureSchema(),
                        propertyCompilerSignatureSchema(),
                        typeAliasCompilerSignatureSchema(),
                        classLikeCompilerSignatureSchema(),
                    )
                ),
            ),
            ServerSchemaProperty("connections", arraySchema(relationFactSchema())),
        ),
    )

/** Syntax identifies the reference family; only the existing semantic owner can admit its authority. */
private fun queryOutputReferenceSchema(kind: String): JsonObject =
    patternTextSchema(
        if (kind == "exact-symbol") "^exact:v[2345]:" else "^candidate:v[2345]:",
        "Opaque reference. Copy verbatim into the next request; never decode or reconstruct it.",
    )

private fun queryItemFailureSchema(): JsonObject =
    unionSchema(
        queryItemFailureVariantSchema("refinement", "declaration-candidate", queryExactFailureSchema()),
        queryItemFailureVariantSchema("exact-reference", "exact-symbol", queryExactFailureSchema()),
        queryItemFailureVariantSchema("predicate", "exact-symbol", queryPredicateFailureSchema()),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("relation", "Per-symbol relation failure.")),
            ServerSchemaProperty("ref", queryOutputReferenceSchema("exact-symbol")),
            ServerSchemaProperty("relation", relationSchema()),
            ServerSchemaProperty("reason", queryRelationFailureSchema()),
        ),
    )

private fun queryItemFailureVariantSchema(
    type: String,
    refKind: String,
    reason: JsonObject,
): JsonObject =
    objectSchema(
        ServerSchemaProperty("type", constantSchema(type, "Per-item query failure.")),
        ServerSchemaProperty("ref", queryOutputReferenceSchema(refKind)),
        ServerSchemaProperty("reason", reason),
    )

private fun queryExactFailureSchema(): JsonObject =
    enumSchema(
        listOf(
            "workspace-not-ready",
            "workspace-root-mismatch",
            "stale-generation",
            "scope-rejected",
            "workspace-index-unavailable",
            "stale-location",
            "outside-scope",
            "ambiguous-declaration",
            "unsupported-declaration",
            "compiler-identity-unavailable",
            "declaration-moved-or-changed",
            "compiler-contract-violation",
        ),
        "Closed exact-symbol refinement failure.",
    )

private fun queryPredicateFailureSchema(): JsonObject =
    enumSchema(
        listOf(
            "predicate-unproven",
            "workspace-not-ready",
            "workspace-root-mismatch",
            "stale-generation",
            "source-state-mismatch",
            "candidate-stale",
            "source-selector-stale",
            "source-snapshot-mismatch",
            "source-unavailable",
            "document-dirty",
            "psi-document-uncommitted",
            "outside-source-scope",
            "anchor-not-found",
            "ambiguous-anchor",
            "region-not-applicable",
            "region-absent",
            "compiler-analysis-unavailable",
            "contract-violation",
        ),
        "Closed exact-symbol predicate failure.",
    )

private fun queryRelationFailureSchema(): JsonObject =
    enumSchema(
        listOf(
            "workspace-not-ready",
            "workspace-root-mismatch",
            "stale-generation",
            "scope-rejected",
            "workspace-index-unavailable",
            "stale-selector",
            "outside-scope",
            "ambiguous-subject",
            "unsupported-subject",
            "compiler-identity-unavailable",
            "continuation-cursor-moved",
            "compiler-contract-violation",
        ),
        "Closed semantic-relation failure.",
    )

private fun queryRejectionSchema(): JsonObject =
    unionSchema(
        objectSchema(ServerSchemaProperty("type", constantSchema("workspace-not-ready", "Workspace unavailable."))),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("plan-rejected", "Typed stage composition rejected.")),
            ServerSchemaProperty("path", textSchema("Rejected step path.")),
            ServerSchemaProperty(
                "required",
                enumSchema(listOf("declaration-candidate", "exact-symbol"), "Required input type."),
            ),
            ServerSchemaProperty(
                "actual",
                enumSchema(listOf("declaration-candidate", "exact-symbol"), "Actual input type."),
            ),
            ServerSchemaProperty(
                "correction",
                enumSchema(
                    listOf(
                        "insert-inspect",
                        "remove-inspect",
                        "select-symbol-output",
                    ),
                    "Closed corrective action.",
                ),
            ),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("reference-rejected", "Reference admission rejected.")),
            ServerSchemaProperty("path", textSchema("Rejected reference path.")),
            ServerSchemaProperty(
                "reason",
                enumSchema(
                    listOf(
                        "wrong-kind",
                        "malformed",
                        "incompatible-workspace",
                        "stale-generation",
                        "stale-authority",
                        "incompatible-authority",
                        "incompatible-reference-version",
                    ),
                    "Exact reference rejection reason.",
                ),
            ),
        ),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema("source-rejected", "Query source is not exhaustively supported."),
            ),
            ServerSchemaProperty("kind", constantSchema("constructor", "Unsupported declaration family.")),
            ServerSchemaProperty(
                "reason",
                constantSchema("unsupported-declaration-kind", "Closed source-admission failure."),
            ),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("execution-rejected", "Query execution rejected.")),
            ServerSchemaProperty(
                "reason",
                enumSchema(
                    listOf(
                        "continuation-unavailable",
                        "continuation-mismatch",
                        "request-rejected",
                        "discovery-rejected",
                        "reference-stale",
                        "budget-rejected",
                        "internal-contract-violation",
                    ),
                    "Closed execution rejection reason.",
                ),
            ),
        ),
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
        CanonicalOperation.RELATION_READ,
        CanonicalOperation.TRAVERSAL_RUN ->
            arrayOf(
                operationOutcomeVariant(
                    operation,
                    "rejected",
                    ServerSchemaProperty("reason", canonicalReadRejectionSchema(operation)),
                    readRecoveryActionProperty(),
                    ServerSchemaProperty(
                        "execution_budget",
                        generatedRequestSchema(
                            io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.serializer()
                        ),
                    ),
                )
            )
        else -> emptyArray()
    }

private fun relationQualificationSchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty("type", constantSchema("resumable", "Coverage state.")),
            ServerSchemaProperty(
                "knownMinimum",
                integerSchema(0, description = "Known minimum relation count."),
            ),
            ServerSchemaProperty("limitations", relationLimitationsSchema()),
            ServerSchemaProperty(
                "checkpoint",
                generatedRequestSchema(
                    io.github.amichne.kast.protocol.contract.RelationCheckpointDocument.serializer()
                ),
            ),
            ServerSchemaProperty(
                "next_action",
                generatedRequestSchema(io.github.amichne.kast.protocol.contract.ReadResumeActionDocument.serializer()),
            ),
            ServerSchemaProperty(
                "continuation",
                patternTextSchema(
                    io.github.amichne.kast.protocol.contract.RelationContinuationDocument.TOKEN_PATTERN,
                    "Self-contained relation continuation.",
                ),
            ),
        ),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema("terminal_incomplete", "Coverage state."),
            ),
            ServerSchemaProperty(
                "knownMinimum",
                integerSchema(0, description = "Known minimum relation count."),
            ),
            ServerSchemaProperty("limitations", relationLimitationsSchema()),
        ),
    )

internal fun sourceReadQualificationSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty(
            "progress",
            generatedRequestSchema(
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
        ),
        ServerSchemaProperty(
            "epoch",
            buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", Long.MAX_VALUE)
                put("description", "Observed live content and model epoch, never a publication generation.")
            },
        ),
        ServerSchemaProperty(
            "contentView",
            constantSchema("SAVED_PSI_COMMITTED", "Saved and PSI-committed live content."),
        ),
        ServerSchemaProperty("version", integerSchema(1, 1, "Live evidence representation version.")),
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

private fun traversalQualificationSchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty("type", constantSchema("resumable", "Coverage state.")),
            ServerSchemaProperty(
                "checkpoint",
                generatedRequestSchema(
                    io.github.amichne.kast.protocol.contract.TraversalCheckpointDocument.serializer()
                ),
            ),
            ServerSchemaProperty(
                "next_action",
                generatedRequestSchema(io.github.amichne.kast.protocol.contract.ReadResumeActionDocument.serializer()),
            ),
            ServerSchemaProperty("limitations", traversalLimitationsSchema()),
            ServerSchemaProperty("relationLimitations", relationLimitationsSchema()),
            ServerSchemaProperty(
                "continuation",
                patternTextSchema(
                    io.github.amichne.kast.protocol.contract.TraversalContinuationDocument.TOKEN_PATTERN,
                    "Self-contained traversal checkpoint.",
                ),
            ),
        ),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema("terminal_incomplete", "Coverage state."),
            ),
            ServerSchemaProperty("limitations", traversalLimitationsSchema()),
            ServerSchemaProperty("relationLimitations", relationLimitationsSchema()),
        ),
    )

private fun traversalLimitationsSchema(): JsonObject =
    arraySchema(
        enumSchema(
            listOf(
                "record-limit-reached",
                "byte-limit-reached",
                "work-limit-reached",
                "time-limit-reached",
                "depth-limit-reached",
                "frontier-limit-reached",
                "one-hop-incomplete",
                "no-progress",
            ),
            "Every traversal limitation.",
        )
    )

private fun relationLimitationsSchema(): JsonObject =
    arraySchema(
        enumSchema(
            listOf(
                "result-limit-reached",
                "byte-limit-reached",
                "work-limit-reached",
                "time-limit-reached",
                "dumb-mode-transition",
                "unresolved-target",
                "unsupported-item",
                "provider-failure",
                "provider-incomplete",
                "provider-stalled",
            ),
            "Every relation coverage limitation.",
        )
    )

private fun diagnosticQualificationSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("continuation", textSchema("Retained same-basis diagnostic progress."), required = false),
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
            operation == CanonicalOperation.TRAVERSAL_RUN && property.name == "graph" ->
                ServerSchemaProperty("graph", normalizedTraversalGraphSchema(ServerReadEvidenceShape.LIVE))
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

private fun normalizedTraversalGraphSchema(
    basis: ServerReadEvidenceShape = ServerReadEvidenceShape.PUBLISHED
): JsonObject =
    objectSchema(
        ServerSchemaProperty(
            "snapshot",
            objectSchema(
                ServerSchemaProperty(
                    "canonicalRoot",
                    textSchema("Exact canonical workspace root for the whole graph."),
                ),
                when (basis) {
                    ServerReadEvidenceShape.PUBLISHED ->
                        ServerSchemaProperty(
                            "generation",
                            integerSchema(0, description = "Exact semantic evidence generation."),
                        )
                    ServerReadEvidenceShape.LIVE -> ServerSchemaProperty("live", liveReadEvidenceSchema())
                },
            ),
        ),
        ServerSchemaProperty("nodes", arraySchema(normalizedTraversalNodeSchema())),
        ServerSchemaProperty("edges", arraySchema(normalizedTraversalEdgeSchema())),
        ServerSchemaProperty("proofs", arraySchema(normalizedTraversalProofSchema())),
    )

private fun normalizedTraversalNodeSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("id", integerSchema(0, description = "Graph-local node index.")),
        ServerSchemaProperty("selector", textSchema("Exact generation-bound selector.")),
        ServerSchemaProperty(
            "kind",
            enumSchema(
                listOf("classlike", "constructor", "function", "property", "type-alias"),
                "Compiler symbol kind.",
            ),
        ),
        ServerSchemaProperty("name", textSchema("Source declaration name.")),
        ServerSchemaProperty("qualifiedIdentity", textSchema("Compiler qualified identity.")),
        ServerSchemaProperty("file", textSchema("Exact source file.")),
        ServerSchemaProperty("range", sourceRangeSchema()),
        ServerSchemaProperty("proof", integerSchema(0, description = "Graph-local proof index.")),
    )

private fun normalizedTraversalEdgeSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("depth", integerSchema(0, description = "Breadth-first hop depth.")),
        ServerSchemaProperty("meaning", relationSchema()),
        ServerSchemaProperty("source", integerSchema(0, description = "Source node index.")),
        ServerSchemaProperty("target", integerSchema(0, description = "Target node index.")),
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

private fun normalizedTraversalProofSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("id", integerSchema(0, description = "Graph-local proof index.")),
        ServerSchemaProperty("identity", compilerIdentitySchema()),
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
            generatedRequestSchema(io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.serializer())
        ),
        required = false,
    )

private fun readRecoveryActionProperty(): ServerSchemaProperty =
    ServerSchemaProperty(
        "next_action",
        generatedRequestSchema(io.github.amichne.kast.protocol.contract.ReadRecoveryAction.serializer()),
    )

private fun readRecoveryActionProperties(operation: CanonicalOperation): Array<ServerSchemaProperty> =
    when (operation) {
        CanonicalOperation.SOURCE_READ,
        CanonicalOperation.RELATION_READ,
        CanonicalOperation.TRAVERSAL_RUN -> arrayOf(readRecoveryActionProperty())
        else -> emptyArray()
    }
