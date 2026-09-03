package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandSurface
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.HostedBindingCompleteness
import io.github.amichne.kast.protocol.registry.HostedOperationProjection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val SERVER_PROJECTION_SCHEMA_VERSION = 2
private const val MAXIMUM_PROTOCOL_TEXT_LENGTH = 1_048_576
private const val MAXIMUM_WORKSPACE_FILE_LENGTH = 4_096
private const val MAXIMUM_PROTOCOL_COUNT = 1_000

/** One closed server-facing projection owned by this installed command graph. */
@Serializable
internal data class InstalledServerProjectionDocument(
    val schemaVersion: Int,
    val namespace: String,
    val tools: List<InstalledServerToolDocument>,
)

@Serializable
internal data class InstalledServerToolDocument(
    val operationId: String,
    val name: String,
    val description: String,
    val deferLoading: Boolean,
    val approvalPolicy: InstalledServerApprovalPolicy,
    val cliUsage: String,
    val inputSchema: JsonElement,
    val outputSchema: JsonElement,
    val invocation: InstalledServerCliInvocationDocument,
)

@Serializable
internal enum class InstalledServerApprovalPolicy(
    val serialValue: String,
) {
    @kotlinx.serialization.SerialName("none")
    NONE("none"),

    @kotlinx.serialization.SerialName("explicit")
    EXPLICIT("explicit"),
}

@Serializable
internal data class InstalledServerCliInvocationDocument(
    val type: InstalledServerInvocationType,
    val command: List<String>,
    val bindings: List<InstalledServerCliBindingDocument>,
)

@Serializable
internal enum class InstalledServerInvocationType {
    CLI,
}

@Serializable
internal data class InstalledServerCliBindingDocument(
    val type: InstalledServerBindingType,
    val inputField: String,
    val option: String,
)

@Serializable
internal enum class InstalledServerBindingType {
    OPTION,
    REPEATED_OPTION,
    FLAG,
}

/**
 * Proof transition: `CliCommandSurface -> InstalledServerProjectionDocument`.
 *
 * The proven canonical command graph supplies exact operation usage while this closed projection
 * supplies the corresponding server name, JSON shapes, and CLI binding grammar. The resulting
 * document is the sole broker-facing authority for an installed executable; no runtime or
 * filesystem input is interpreted here.
 */
internal fun installedServerProjection(
    commandSurface: CliCommandSurface,
): InstalledServerProjectionDocument {
    val commandByOperation = commandSurface.semanticCommands.associateBy { it.operation }
    return InstalledServerProjectionDocument(
        schemaVersion = SERVER_PROJECTION_SCHEMA_VERSION,
        namespace = "kast",
        tools = installedServerTools.map { tool ->
            tool.document(commandByOperation.getValue(tool.operation).usage)
        },
    )
}

private val installedServerTools: List<InstalledServerTool> = InstalledServerTool.entries.also {
    val operations = it.map { tool -> tool.operation }
    when (val completeness = HostedOperationProjection.verifyBindings(operations)) {
        HostedBindingCompleteness.Complete -> Unit
        is HostedBindingCompleteness.Rejected ->
            error("Invalid installed server projection: ${completeness.failures}")
    }
}

private enum class InstalledServerTool(
    val operation: CanonicalOperation,
    private val toolName: String,
    private val toolDescription: String,
    private val inputSchema: JsonObject,
    private val command: List<String>,
    private val optionFields: List<ServerCliOptionField>,
    private val approvalPolicy: InstalledServerApprovalPolicy = InstalledServerApprovalPolicy.NONE,
) {
    WORKSPACE_INSPECT(
        operation = CanonicalOperation.WORKSPACE_INSPECT,
        toolName = "workspace_ensure_ready",
        toolDescription =
            "Admit the exact-root endpoint and report its readiness and canonical identity.",
        inputSchema = objectSchema(),
        command = listOf("start"),
        optionFields = emptyList(),
    ),
    INDEX_SYNC(
        operation = CanonicalOperation.INDEX_SYNC,
        toolName = "index_sync",
        toolDescription =
            "Refresh admitted source roots, wait for indexing, and publish semantic evidence.",
        inputSchema = objectSchema(),
        command = listOf("index", "sync"),
        optionFields = emptyList(),
    ),
    TOPOLOGY_BUILD(
        operation = CanonicalOperation.TOPOLOGY_BUILD,
        toolName = "topology_build",
        toolDescription =
            "Build or reuse the complete durable topology for the current workspace generation.",
        inputSchema = objectSchema(),
        command = listOf("topology", "build"),
        optionFields = emptyList(),
    ),
    SYMBOL_DISCOVER(
        operation = CanonicalOperation.SYMBOL_DISCOVER,
        toolName = "symbol_lookup",
        toolDescription =
            "Look up bounded Kotlin candidates by name or exact file and offset.",
        inputSchema = symbolDiscoverInputSchema(),
        command = listOf("symbol", "discover"),
        optionFields = listOf(
            ServerCliOptionField("mode", "--mode"),
            ServerCliOptionField("query", "--query"),
            ServerCliOptionField("kind", "--kind"),
            ServerCliOptionField("match", "--match"),
            ServerCliOptionField("file", "--file"),
            ServerCliOptionField("offset", "--offset"),
            ServerCliOptionField("scope", "--scope"),
            ServerCliOptionField("limit", "--limit"),
        ),
    ),
    SYMBOL_RESOLVE(
        operation = CanonicalOperation.SYMBOL_RESOLVE,
        toolName = "symbol_resolve",
        toolDescription =
            "Refine one Kast discovery candidate to an exact generation-bound selector.",
        inputSchema = objectSchema(
            ServerSchemaProperty(
                "candidate",
                textSchema("Candidate selector returned by discovery."),
            ),
        ),
        command = listOf("symbol", "resolve"),
        optionFields = listOf(ServerCliOptionField("candidate", "--candidate")),
    ),
    SYMBOL_DESCRIBE(
        operation = CanonicalOperation.SYMBOL_DESCRIBE,
        toolName = "symbol_inspect",
        toolDescription = "Inspect one exact current-generation Kotlin symbol.",
        inputSchema = objectSchema(
            ServerSchemaProperty("selector", textSchema("Exact symbol selector.")),
        ),
        command = listOf("symbol", "describe"),
        optionFields = listOf(ServerCliOptionField("selector", "--selector")),
    ),
    SOURCE_READ(
        operation = CanonicalOperation.SOURCE_READ,
        toolName = "source_read",
        toolDescription =
            "Read one exact bounded Kotlin source region with typed structure and text.",
        inputSchema = sourceReadInputSchema(),
        command = listOf("source", "read"),
        optionFields = listOf(
            ServerCliOptionField("anchor", "--anchor"),
            ServerCliOptionField("region", "--region"),
            ServerCliOptionField(
                "declarationKinds",
                "--declaration-kind",
                InstalledServerBindingType.REPEATED_OPTION,
            ),
            ServerCliOptionField(
                "visibility",
                "--visibility",
                InstalledServerBindingType.REPEATED_OPTION,
            ),
            ServerCliOptionField(
                "includeParameters",
                "--include-parameters",
                InstalledServerBindingType.FLAG,
            ),
            ServerCliOptionField(
                "includeCalls",
                "--include-calls",
                InstalledServerBindingType.FLAG,
            ),
            ServerCliOptionField(
                "includeReferences",
                "--include-references",
                InstalledServerBindingType.FLAG,
            ),
            ServerCliOptionField("containment", "--containment"),
            ServerCliOptionField("text", "--text"),
            ServerCliOptionField("beforeLines", "--before-lines"),
            ServerCliOptionField("afterLines", "--after-lines"),
            ServerCliOptionField("entityLimit", "--entity-limit"),
            ServerCliOptionField("textByteLimit", "--text-byte-limit"),
            ServerCliOptionField("continuation", "--continuation"),
        ),
    ),
    RELATION_READ(
        operation = CanonicalOperation.RELATION_READ,
        toolName = "semantic_query",
        toolDescription =
            "Query one bounded compiler-grounded relation from an exact symbol selector.",
        inputSchema = objectSchema(
            ServerSchemaProperty("selector", textSchema("Exact starting selector.")),
            ServerSchemaProperty("relation", relationSchema()),
            ServerSchemaProperty("limit", countSchema("Maximum returned relations.")),
        ),
        command = listOf("relation", "read"),
        optionFields = listOf(
            ServerCliOptionField("selector", "--selector"),
            ServerCliOptionField("relation", "--relation"),
            ServerCliOptionField("limit", "--limit"),
        ),
    ),
    TRAVERSAL_RUN(
        operation = CanonicalOperation.TRAVERSAL_RUN,
        toolName = "impact_analyze",
        toolDescription =
            "Analyze bounded transitive impact over one durable semantic relation.",
        inputSchema = objectSchema(
            ServerSchemaProperty("selector", textSchema("Exact starting selector.")),
            ServerSchemaProperty("relation", relationSchema()),
            ServerSchemaProperty(
                "maximumDepth",
                countSchema("Maximum traversal depth."),
            ),
            ServerSchemaProperty(
                "maximumResults",
                countSchema("Maximum returned symbols."),
            ),
        ),
        command = listOf("traversal", "run"),
        optionFields = listOf(
            ServerCliOptionField("selector", "--selector"),
            ServerCliOptionField("relation", "--relation"),
            ServerCliOptionField("maximumDepth", "--maximum-depth"),
            ServerCliOptionField("maximumResults", "--maximum-results"),
        ),
    ),
    DIAGNOSTIC_CHECK(
        operation = CanonicalOperation.DIAGNOSTIC_CHECK,
        toolName = "diagnostic_check",
        toolDescription = "Check bounded compiler diagnostics within one explicit scope.",
        inputSchema = objectSchema(
            ServerSchemaProperty("scope", workspaceFileSchema()),
            ServerSchemaProperty("limit", countSchema("Maximum returned diagnostics.")),
        ),
        command = listOf("diagnostic", "check"),
        optionFields = listOf(
            ServerCliOptionField("scope", "--scope"),
            ServerCliOptionField("limit", "--limit"),
        ),
    ),
    CHANGE_PLAN(
        operation = CanonicalOperation.CHANGE_PLAN,
        toolName = "change_plan",
        toolDescription =
            "Derive one hosted add-declaration plan without writing the workspace.",
        inputSchema = objectSchema(
            ServerSchemaProperty(
                "intent",
                constantSchema("add-declaration", "Hosted change intent."),
            ),
            ServerSchemaProperty("target", textSchema("Exact target selector.")),
            ServerSchemaProperty("declaration", textSchema("Declaration to add.")),
        ),
        command = listOf("change", "plan"),
        optionFields = listOf(
            ServerCliOptionField("intent", "--intent"),
            ServerCliOptionField("target", "--target"),
            ServerCliOptionField("declaration", "--declaration"),
        ),
        approvalPolicy = InstalledServerApprovalPolicy.EXPLICIT,
    ),
    CHANGE_APPLY(
        operation = CanonicalOperation.CHANGE_APPLY,
        toolName = "change_apply",
        toolDescription = "Apply one admitted hosted change plan.",
        inputSchema = objectSchema(
            ServerSchemaProperty("plan", textSchema("Plan identity.")),
        ),
        command = listOf("change", "apply"),
        optionFields = listOf(ServerCliOptionField("plan", "--plan")),
        approvalPolicy = InstalledServerApprovalPolicy.EXPLICIT,
    ),
    CHANGE_VERIFY(
        operation = CanonicalOperation.CHANGE_VERIFY,
        toolName = "change_verify",
        toolDescription = "Verify one hosted change application against semantic evidence.",
        inputSchema = objectSchema(
            ServerSchemaProperty("application", textSchema("Application identity.")),
        ),
        command = listOf("change", "verify"),
        optionFields = listOf(ServerCliOptionField("application", "--application")),
        approvalPolicy = InstalledServerApprovalPolicy.EXPLICIT,
    ),
    CHANGE_RECOVER(
        operation = CanonicalOperation.CHANGE_RECOVER,
        toolName = "change_recover",
        toolDescription = "Recover one hosted change plan to a known workspace state.",
        inputSchema = objectSchema(
            ServerSchemaProperty("plan", textSchema("Plan identity.")),
        ),
        command = listOf("change", "recover"),
        optionFields = listOf(ServerCliOptionField("plan", "--plan")),
        approvalPolicy = InstalledServerApprovalPolicy.EXPLICIT,
    ),
    ;

    fun document(cliUsage: String): InstalledServerToolDocument = InstalledServerToolDocument(
        operationId = operation.id.value,
        name = toolName,
        description = toolDescription,
        deferLoading = true,
        approvalPolicy = approvalPolicy,
        cliUsage = cliUsage,
        inputSchema = inputSchema,
        outputSchema = installedServerOutputSchema(operation),
        invocation = InstalledServerCliInvocationDocument(
            type = InstalledServerInvocationType.CLI,
            command = command,
            bindings = optionFields.map { field ->
                InstalledServerCliBindingDocument(
                    type = field.type,
                    inputField = field.inputField,
                    option = field.option,
                )
            },
        ),
    )
}

private data class ServerCliOptionField(
    val inputField: String,
    val option: String,
    val type: InstalledServerBindingType = InstalledServerBindingType.OPTION,
)

private data class ServerSchemaProperty(
    val name: String,
    val schema: JsonObject,
)

private fun sourceReadInputSchema(): JsonObject = objectSchemaWithRequired(
    setOf("anchor"),
    ServerSchemaProperty(
        "anchor",
        patternTextSchema(
            "^(candidate:v2|exact:v2|source-selector-v1):",
            "Candidate, exact-symbol, or source selector token.",
        ),
    ),
    ServerSchemaProperty(
        "region",
        enumSchema(
            listOf(
                "anchor",
                "callable-body",
                "class-body",
                "file",
                "enclosing-declaration",
                "enclosing-callable-body",
                "enclosing-class-body",
            ),
            "Selected structural region.",
        ),
    ),
    ServerSchemaProperty(
        "declarationKinds",
        arraySchema(
            enumSchema(
                listOf("classlike", "constructor", "function", "property", "type-alias"),
                "Requested declaration kind.",
            ),
        ),
    ),
    ServerSchemaProperty(
        "visibility",
        arraySchema(
            enumSchema(
                listOf("public", "protected", "internal", "private", "local"),
                "Requested declaration visibility.",
            ),
        ),
    ),
    ServerSchemaProperty("includeParameters", booleanSchema("Include value parameters.")),
    ServerSchemaProperty("includeCalls", booleanSchema("Include calls.")),
    ServerSchemaProperty("includeReferences", booleanSchema("Include references.")),
    ServerSchemaProperty(
        "containment",
        enumSchema(listOf("direct", "descendants"), "Structural containment policy."),
    ),
    ServerSchemaProperty(
        "text",
        enumSchema(listOf("complete", "none", "window"), "Requested source-text projection."),
    ),
    ServerSchemaProperty(
        "beforeLines",
        integerSchema(0, 1_000, "Whole lines before the anchor."),
    ),
    ServerSchemaProperty(
        "afterLines",
        integerSchema(0, 1_000, "Whole lines after the anchor."),
    ),
    ServerSchemaProperty("entityLimit", countSchema("Maximum returned entities.")),
    ServerSchemaProperty(
        "textByteLimit",
        integerSchema(1, description = "Maximum UTF-8 bytes for returned text."),
    ),
    ServerSchemaProperty("continuation", textSchema("Snapshot-bound source continuation.")),
)

private fun symbolDiscoverInputSchema(): JsonObject = unionSchema(
    objectSchema(
        ServerSchemaProperty("mode", constantSchema("name", "Discovery mode.")),
        ServerSchemaProperty("query", textSchema("Exact or fuzzy source-name query.")),
        ServerSchemaProperty(
            "kind",
            enumSchema(
                values = listOf("file", "class", "symbol"),
                description = "Name discovery kind.",
            ),
        ),
        ServerSchemaProperty(
            "match",
            enumSchema(
                values = listOf("fuzzy", "exact-name"),
                description = "Name matching policy.",
            ),
        ),
        ServerSchemaProperty("limit", countSchema("Maximum returned items.")),
    ),
    objectSchema(
        ServerSchemaProperty("mode", constantSchema("location", "Discovery mode.")),
        ServerSchemaProperty("file", workspaceFileSchema()),
        ServerSchemaProperty(
            "offset",
            integerSchema(minimum = 0, description = "Non-negative source offset."),
        ),
        ServerSchemaProperty("limit", countSchema("Maximum returned items.")),
    ),
    objectSchema(
        ServerSchemaProperty("mode", constantSchema("text", "Discovery mode.")),
        ServerSchemaProperty("query", textSchema("Bounded source-text query.")),
        ServerSchemaProperty("scope", constantSchema("workspace", "Text discovery scope.")),
        ServerSchemaProperty("limit", countSchema("Maximum returned items.")),
    ),
    objectSchema(
        ServerSchemaProperty("mode", constantSchema("text", "Discovery mode.")),
        ServerSchemaProperty("query", textSchema("Bounded source-text query.")),
        ServerSchemaProperty("scope", constantSchema("file", "Text discovery scope.")),
        ServerSchemaProperty("file", workspaceFileSchema()),
        ServerSchemaProperty("limit", countSchema("Maximum returned items.")),
    ),
)

internal fun installedServerOutputSchema(operation: CanonicalOperation): JsonObject = unionSchema(
    objectSchema(
        ServerSchemaProperty("status", constantSchema("completed", "Process outcome.")),
        ServerSchemaProperty("document", operationDocumentSchema(operation)),
    ),
    objectSchema(
        ServerSchemaProperty("status", constantSchema("rejected", "Process outcome.")),
        ServerSchemaProperty("diagnostic", processDiagnosticSchema()),
    ),
)

private fun operationDocumentSchema(operation: CanonicalOperation): JsonObject = when (operation) {
    CanonicalOperation.WORKSPACE_INSPECT -> outcomeSchema(
        operation,
        ServerSchemaProperty("canonicalRoot", textSchema("Canonical workspace root.")),
        ServerSchemaProperty(
            "state",
            enumSchema(
                listOf("absent", "starting", "reconciling", "ready", "blocked", "stopping"),
                "Workspace runtime state.",
            ),
        ),
    )
    CanonicalOperation.INDEX_SYNC -> outcomeSchema(
        operation,
        ServerSchemaProperty(
            "state",
            enumSchema(listOf("synchronized", "unchanged"), "Index synchronization result."),
        ),
    )
    CanonicalOperation.TOPOLOGY_BUILD -> topologyBuildDocumentSchema(operation)
    CanonicalOperation.SYMBOL_DISCOVER -> outcomeSchema(
        operation,
        ServerSchemaProperty("items", arraySchema(symbolDiscoverySchema())),
    )
    CanonicalOperation.SYMBOL_RESOLVE -> outcomeSchema(
        operation,
        ServerSchemaProperty("exactSelector", textSchema("Exact compiler-grounded selector.")),
    )
    CanonicalOperation.SYMBOL_DESCRIBE -> outcomeSchema(
        operation,
        ServerSchemaProperty("symbol", symbolSchema()),
    )
    CanonicalOperation.SOURCE_READ -> proofQualifiedOutcomeSchema(
        operation,
        sourceReadQualificationSchema(),
        ServerSchemaProperty("snapshot", sourceSnapshotSchema()),
        ServerSchemaProperty("region", sourceRegionSchema()),
        ServerSchemaProperty("entities", arraySchema(sourceEntitySchema())),
        ServerSchemaProperty("text", sourceTextProjectionSchema()),
    )
    CanonicalOperation.RELATION_READ -> proofQualifiedOutcomeSchema(
        operation,
        relationQualificationSchema(),
        ServerSchemaProperty("relations", arraySchema(relationFactSchema())),
    )
    CanonicalOperation.TRAVERSAL_RUN -> proofQualifiedOutcomeSchema(
        operation,
        traversalQualificationSchema(),
        ServerSchemaProperty("graph", normalizedTraversalGraphSchema()),
    )
    CanonicalOperation.DIAGNOSTIC_CHECK -> proofQualifiedOutcomeSchema(
        operation,
        diagnosticQualificationSchema(),
        ServerSchemaProperty("diagnostics", arraySchema(diagnosticSchema())),
    )
    CanonicalOperation.CHANGE_PLAN -> outcomeSchema(
        operation,
        ServerSchemaProperty("planIdentity", textSchema("Durable change plan identity.")),
    )
    CanonicalOperation.CHANGE_APPLY -> outcomeSchema(
        operation,
        ServerSchemaProperty(
            "applicationIdentity",
            textSchema("Durable change application identity."),
        ),
    )
    CanonicalOperation.CHANGE_VERIFY -> outcomeSchema(
        operation,
        ServerSchemaProperty("receiptIdentity", textSchema("Verification receipt identity.")),
    )
    CanonicalOperation.CHANGE_RECOVER -> outcomeSchema(
        operation,
        ServerSchemaProperty("state", textSchema("Recovered workspace state.")),
    )
}

private fun outcomeSchema(
    operation: CanonicalOperation,
    vararg payload: ServerSchemaProperty,
): JsonObject = proofQualifiedOutcomeSchema(
    operation,
    textSchema("Closed qualification reason."),
    *payload,
)

private fun proofQualifiedOutcomeSchema(
    operation: CanonicalOperation,
    qualificationSchema: JsonObject,
    vararg payload: ServerSchemaProperty,
): JsonObject = unionSchema(
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
        ServerSchemaProperty("reason", textSchema("Closed rejection reason.")),
    ),
)

private fun relationQualificationSchema(): JsonObject = objectSchema(
    ServerSchemaProperty(
        "knownMinimum",
        integerSchema(0, description = "Known minimum relation count."),
    ),
    ServerSchemaProperty(
        "limitations",
        relationLimitationsSchema(),
    ),
    ServerSchemaProperty("continuation", sha256Schema("Opaque relation continuation proof.")),
)

private fun sourceReadQualificationSchema(): JsonObject = objectSchema(
    ServerSchemaProperty(
        "knownMinimumEntityCount",
        integerSchema(0, description = "Known minimum matching entity count."),
    ),
    ServerSchemaProperty(
        "limitations",
        nonEmptyArraySchema(
            enumSchema(
                listOf(
                    "entity-limit-reached",
                    "text-byte-limit-reached",
                    "work-limit-reached",
                    "time-limit-reached",
                    "dumb-mode-transition",
                    "semantic-resolution-incomplete",
                    "unsupported-entity",
                    "provider-failure",
                ),
                "Every source-read coverage limitation.",
            ),
        ),
    ),
    ServerSchemaProperty(
        "continuation",
        unionSchema(
            objectSchema(
                ServerSchemaProperty(
                    "type",
                    constantSchema("unavailable", "Continuation state."),
                ),
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

private fun sourceSnapshotSchema(): JsonObject = objectSchema(
    ServerSchemaProperty("canonicalRoot", textSchema("Canonical workspace root.")),
    ServerSchemaProperty("generation", integerSchema(0, description = "Semantic generation.")),
    ServerSchemaProperty("sourceState", textSchema("Workspace source-state identity.")),
    ServerSchemaProperty("file", textSchema("Exact workspace source file.")),
    ServerSchemaProperty("textIdentity", textSchema("Committed document text identity.")),
    ServerSchemaProperty(
        "coordinateUnit",
        constantSchema("utf16-code-unit", "Source coordinate unit."),
    ),
    ServerSchemaProperty("length", integerSchema(0, description = "Document UTF-16 length.")),
)

private fun sourceSelectionSchema(): JsonObject = objectSchema(
    ServerSchemaProperty("selector", textSchema("Reusable exact source selector.")),
    ServerSchemaProperty("range", diagnosticRangeSchema()),
)

private fun sourceRegionSchema(): JsonObject = objectSchema(
    ServerSchemaProperty(
        "kind",
        enumSchema(
            listOf("anchor", "declaration", "callable-body", "class-body", "file", "window"),
            "Established structural region kind.",
        ),
    ),
    ServerSchemaProperty("selection", sourceSelectionSchema()),
)

private fun sourceEntitySchema(): JsonObject = unionSchema(
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

private fun sourceEntityCommonProperties(): Array<ServerSchemaProperty> = arrayOf(
    ServerSchemaProperty("nestingDepth", integerSchema(0, description = "Structural nesting depth.")),
    ServerSchemaProperty("parentSelector", textSchema("Exact structural parent selector.")),
    ServerSchemaProperty("selection", sourceSelectionSchema()),
)

private fun sourceDeclarationSemanticIdentitySchema(): JsonObject = unionSchema(
    objectSchema(
        ServerSchemaProperty("type", constantSchema("candidate", "Semantic identity state.")),
        ServerSchemaProperty("selector", textSchema("Resolvable declaration candidate selector.")),
    ),
    objectSchema(
        ServerSchemaProperty(
            "type",
            constantSchema("existing-symbol", "Semantic identity state."),
        ),
        ServerSchemaProperty("selector", textSchema("Existing exact symbol selector.")),
    ),
)

private fun sourceEntityTargetSchema(): JsonObject = unionSchema(
    objectSchema(
        ServerSchemaProperty("type", constantSchema("symbol", "Semantic target state.")),
        ServerSchemaProperty("selector", textSchema("Exact target symbol selector.")),
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

private fun sourceTextProjectionSchema(): JsonObject = unionSchema(
    objectSchema(
        ServerSchemaProperty("type", constantSchema("not-requested", "Text projection state.")),
    ),
    objectSchema(
        ServerSchemaProperty("type", constantSchema("returned", "Text projection state.")),
        ServerSchemaProperty("selection", sourceSelectionSchema()),
        ServerSchemaProperty("text", sourceTextSchema()),
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

private fun traversalQualificationSchema(): JsonObject = objectSchema(
    ServerSchemaProperty(
        "limitations",
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
                ),
                "Every traversal limitation.",
            ),
        ),
    ),
    ServerSchemaProperty(
        "relationLimitations",
        relationLimitationsSchema(),
    ),
    ServerSchemaProperty("continuation", sha256Schema("Opaque traversal continuation proof.")),
)

private fun relationLimitationsSchema(): JsonObject = arraySchema(
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
        ),
        "Every relation coverage limitation.",
    ),
)

private fun diagnosticQualificationSchema(): JsonObject = objectSchema(
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
            ),
        ),
    ),
)

private fun operationOutcomeVariant(
    operation: CanonicalOperation,
    status: String,
    vararg payload: ServerSchemaProperty,
): JsonObject = objectSchema(
    ServerSchemaProperty(
        "operation",
        constantSchema(operation.id.value, "Canonical operation identity."),
    ),
    ServerSchemaProperty("status", constantSchema(status, "Canonical operation outcome.")),
    *payload,
)

private fun topologyBuildDocumentSchema(operation: CanonicalOperation): JsonObject {
    val result = arrayOf(
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

private fun topologyCoverageCandidateEvidenceMismatchSchema(): JsonObject = objectSchema(
    ServerSchemaProperty("candidate", topologyCoverageFileEvidenceSchema()),
    ServerSchemaProperty("completed", topologyCoverageFileEvidenceSchema()),
)

private fun topologyCoverageNodeSchema(): JsonObject = objectSchema(
    ServerSchemaProperty("compilerIdentity", compilerIdentitySchema()),
    ServerSchemaProperty("file", textSchema("Exact topology source file.")),
    ServerSchemaProperty("range", sourceRangeSchema()),
)

private fun topologyCoverageSymbolSchema(): JsonObject = unionSchema(
    topologyCoverageSymbolVariantSchema("classlike", classLikeCompilerSignatureSchema()),
    topologyCoverageSymbolVariantSchema("constructor", functionCompilerSignatureSchema()),
    topologyCoverageSymbolVariantSchema("function", functionCompilerSignatureSchema()),
    topologyCoverageSymbolVariantSchema("property", propertyCompilerSignatureSchema()),
    topologyCoverageSymbolVariantSchema("type-alias", typeAliasCompilerSignatureSchema()),
)

private fun topologyCoverageSymbolVariantSchema(
    kind: String,
    signature: JsonObject,
): JsonObject = objectSchema(
    ServerSchemaProperty("node", topologyCoverageNodeSchema()),
    ServerSchemaProperty("fileEvidence", topologyCoverageFileEvidenceSchema()),
    ServerSchemaProperty("name", textSchema("Topology symbol name.")),
    ServerSchemaProperty("qualifiedIdentity", topologyCoverageQualifiedIdentitySchema()),
    ServerSchemaProperty("kind", constantSchema(kind, "Compiler symbol kind.")),
    ServerSchemaProperty("compilerEvidence", compilerEvidenceSchema(signature)),
)

private fun topologyCoverageQualifiedIdentitySchema(): JsonObject = objectSchema(
    ServerSchemaProperty("state", constantSchema("available", "Identity state.")),
    ServerSchemaProperty("value", textSchema("Compiler qualified identity.")),
)

private fun topologyCoverageFileEvidenceSchema(): JsonObject = objectSchema(
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

private fun symbolSchema(): JsonObject = unionSchema(
    symbolVariantSchema("classlike", classLikeCompilerSignatureSchema()),
    symbolVariantSchema("constructor", functionCompilerSignatureSchema()),
    symbolVariantSchema("function", functionCompilerSignatureSchema()),
    symbolVariantSchema("property", propertyCompilerSignatureSchema()),
    symbolVariantSchema("type-alias", typeAliasCompilerSignatureSchema()),
)

private fun symbolVariantSchema(kind: String, signature: JsonObject): JsonObject = objectSchema(
    ServerSchemaProperty("selector", textSchema("Exact generation-bound selector.")),
    ServerSchemaProperty("kind", constantSchema(kind, "Compiler symbol kind.")),
    ServerSchemaProperty("name", textSchema("Source declaration name.")),
    ServerSchemaProperty("qualifiedIdentity", textSchema("Compiler qualified identity.")),
    ServerSchemaProperty("file", textSchema("Exact source file.")),
    ServerSchemaProperty("range", sourceRangeSchema()),
    ServerSchemaProperty("compilerEvidence", compilerEvidenceSchema(signature)),
)

private fun compilerEvidenceSchema(signature: JsonObject): JsonObject = objectSchema(
    ServerSchemaProperty("identity", compilerIdentitySchema()),
    ServerSchemaProperty("signature", signature),
)

private fun functionCompilerSignatureSchema(): JsonObject = objectSchema(
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

private fun propertyCompilerSignatureSchema(): JsonObject = objectSchema(
    ServerSchemaProperty("type", constantSchema("property", "Signature variant.")),
    ServerSchemaProperty("qualifiedIdentity", textSchema("Compiler qualified identity.")),
    ServerSchemaProperty("receiver", compilerReceiverSchema()),
    ServerSchemaProperty("contextReceivers", arraySchema(textSchema("Compiler type."))),
    ServerSchemaProperty("returnType", textSchema("Canonical compiler return type.")),
)

private fun typeAliasCompilerSignatureSchema(): JsonObject = objectSchema(
    ServerSchemaProperty("type", constantSchema("type-alias", "Signature variant.")),
    ServerSchemaProperty("qualifiedIdentity", textSchema("Compiler qualified identity.")),
)

private fun classLikeCompilerSignatureSchema(): JsonObject = objectSchema(
    ServerSchemaProperty("type", constantSchema("class-like", "Signature variant.")),
    ServerSchemaProperty("qualifiedIdentity", textSchema("Compiler qualified identity.")),
)

private fun compilerIdentitySchema(): JsonObject = buildJsonObject {
    put("type", "string")
    put("pattern", "^canonical-signature-sha256-v1\\|[0-9a-f]{64}$")
    put("description", "Identity derived from the exact canonical compiler signature.")
}

private fun compilerReceiverSchema(): JsonObject = unionSchema(
    objectSchema(
        ServerSchemaProperty("type", constantSchema("absent", "Receiver state.")),
    ),
    objectSchema(
        ServerSchemaProperty("type", constantSchema("present", "Receiver state.")),
        ServerSchemaProperty("compilerType", textSchema("Canonical compiler receiver type.")),
    ),
)

private fun relationFactSchema(): JsonObject = objectSchema(
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

private fun normalizedTraversalGraphSchema(): JsonObject = objectSchema(
    ServerSchemaProperty(
        "snapshot",
        objectSchema(
            ServerSchemaProperty(
                "canonicalRoot",
                textSchema("Exact canonical workspace root for the whole graph."),
            ),
            ServerSchemaProperty(
                "generation",
                integerSchema(0, description = "Exact semantic evidence generation."),
            ),
        ),
    ),
    ServerSchemaProperty("nodes", arraySchema(normalizedTraversalNodeSchema())),
    ServerSchemaProperty("edges", arraySchema(normalizedTraversalEdgeSchema())),
    ServerSchemaProperty("proofs", arraySchema(normalizedTraversalProofSchema())),
)

private fun normalizedTraversalNodeSchema(): JsonObject = objectSchema(
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

private fun normalizedTraversalEdgeSchema(): JsonObject = objectSchema(
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

private fun normalizedTraversalProofSchema(): JsonObject = objectSchema(
    ServerSchemaProperty("id", integerSchema(0, description = "Graph-local proof index.")),
    ServerSchemaProperty("identity", compilerIdentitySchema()),
)

private fun diagnosticSchema(): JsonObject = objectSchema(
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

private fun symbolDiscoverySchema(): JsonObject = unionSchema(
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

private fun sourceRangeSchema(): JsonObject = objectSchema(
    ServerSchemaProperty("startInclusive", integerSchema(0, description = "Start offset.")),
    ServerSchemaProperty("endExclusive", integerSchema(1, description = "Exclusive end offset.")),
)

private fun diagnosticRangeSchema(): JsonObject = objectSchema(
    ServerSchemaProperty("startInclusive", integerSchema(0, description = "Start offset.")),
    ServerSchemaProperty("endExclusive", integerSchema(0, description = "Exclusive end offset.")),
)

private fun processDiagnosticSchema(): JsonObject = unionSchema(
    objectSchema(
        ServerSchemaProperty("status", constantSchema("rejected", "Boundary outcome.")),
        ServerSchemaProperty("boundary", textSchema("Rejected process boundary.")),
        ServerSchemaProperty("reason", textSchema("Closed boundary rejection reason.")),
    ),
    objectSchema(
        ServerSchemaProperty("status", constantSchema("rejected", "Boundary outcome.")),
        ServerSchemaProperty("boundary", textSchema("Rejected process boundary.")),
        ServerSchemaProperty("reason", textSchema("Closed boundary rejection reason.")),
        ServerSchemaProperty("diagnostic", textSchema("Usage diagnostic.")),
    ),
    objectSchema(
        ServerSchemaProperty("status", constantSchema("rejected", "Boundary outcome.")),
        ServerSchemaProperty("boundary", constantSchema("runtime", "Rejected process boundary.")),
        ServerSchemaProperty("reason", textSchema("Closed boundary rejection reason.")),
        ServerSchemaProperty("details", ideDescriptorFailureSchema()),
    ),
)

private fun ideDescriptorFailureSchema(): JsonObject = unionSchema(
    *listOf(
        "malformed-document",
        "non-canonical-document",
        "unsupported-schema",
        "unsupported-host-kind",
        "unsupported-framing",
    ).map(::typeOnlyFailureSchema).toTypedArray(),
    typedFailureSchema("invalid-canonical-root", "failure"),
    typedFailureSchema("invalid-socket-path", "failure"),
    typedFailureSchema("invalid-process-id", "failure"),
    typedFailureSchema("invalid-runtime-epoch", "failure"),
    objectSchema(
        ServerSchemaProperty(
            "type",
            constantSchema("compatibility-rejected", "Descriptor failure variant."),
        ),
        ServerSchemaProperty("failure", compatibilityFailureSchema()),
    ),
    objectSchema(
        ServerSchemaProperty(
            "type",
            constantSchema("hosted-capabilities-rejected", "Descriptor failure variant."),
        ),
        ServerSchemaProperty("failure", hostedCapabilitiesFailureSchema()),
    ),
)

private fun compatibilityFailureSchema(): JsonObject = unionSchema(
    objectSchema(
        ServerSchemaProperty("type", constantSchema("malformed", "Compatibility failure.")),
        ServerSchemaProperty("field", textSchema("Rejected compatibility field.")),
        ServerSchemaProperty("syntax", textSchema("Closed syntax failure.")),
    ),
    objectSchema(
        ServerSchemaProperty("type", constantSchema("mismatch", "Compatibility failure.")),
        ServerSchemaProperty("field", textSchema("Mismatched compatibility field.")),
        ServerSchemaProperty("expected", textSchema("Expected identity.")),
        ServerSchemaProperty("observed", textSchema("Observed identity.")),
    ),
    objectSchema(
        ServerSchemaProperty(
            "type",
            constantSchema("capability-set-mismatch", "Compatibility failure."),
        ),
        ServerSchemaProperty("field", textSchema("Mismatched capability field.")),
        ServerSchemaProperty("expected", finiteArraySchema(textSchema("Expected operation."))),
        ServerSchemaProperty("observed", finiteArraySchema(textSchema("Observed operation."))),
    ),
    typedFailureSchema("unknown-capability", "operationId"),
    typedFailureSchema("unsupported-capability", "operationId"),
    typedFailureSchema("duplicate-capability", "operationId"),
)

private fun hostedCapabilitiesFailureSchema(): JsonObject = unionSchema(
    typedFailureSchema("malformed-operation-id", "failure"),
    typedFailureSchema("unknown-operation", "operationId"),
    typedFailureSchema("unsupported-intent", "operationId"),
    typedFailureSchema("duplicate-operation", "operationId"),
    objectSchema(
        ServerSchemaProperty(
            "type",
            constantSchema("duplicate-intent", "Hosted-capability failure."),
        ),
        ServerSchemaProperty("operationId", textSchema("Canonical operation identity.")),
        ServerSchemaProperty("intent", textSchema("Duplicate hosted intent.")),
    ),
    typeOnlyFailureSchema("canonical-projection-mismatch"),
)

private fun typeOnlyFailureSchema(type: String): JsonObject = objectSchema(
    ServerSchemaProperty("type", constantSchema(type, "Closed failure variant.")),
)

private fun typedFailureSchema(type: String, field: String): JsonObject = objectSchema(
    ServerSchemaProperty("type", constantSchema(type, "Closed failure variant.")),
    ServerSchemaProperty(field, textSchema("Finite failure evidence.")),
)

private fun objectSchema(vararg properties: ServerSchemaProperty): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        properties.forEach { property -> put(property.name, property.schema) }
    }
    putJsonArray("required") {
        properties.forEach { property -> add(JsonPrimitive(property.name)) }
    }
}

private fun objectSchemaWithRequired(
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
        properties.filter { it.name in required }.forEach { property ->
            add(JsonPrimitive(property.name))
        }
    }
}

private fun unionSchema(vararg variants: JsonObject): JsonObject = buildJsonObject {
    putJsonArray("anyOf") {
        variants.forEach(::add)
    }
}

private fun arraySchema(item: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", item)
    put("maxItems", MAXIMUM_PROTOCOL_COUNT)
}

private fun nonEmptyArraySchema(item: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", item)
    put("minItems", 1)
    put("maxItems", MAXIMUM_PROTOCOL_COUNT)
}

private fun finiteArraySchema(item: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", item)
}

private fun textSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("minLength", 1)
    put("maxLength", MAXIMUM_PROTOCOL_TEXT_LENGTH)
    put("description", description)
}

private fun sourceTextSchema(): JsonObject = buildJsonObject {
    put("type", "string")
    put("maxLength", MAXIMUM_PROTOCOL_TEXT_LENGTH)
    put("description", "Exact normalized source text; empty files remain valid.")
}

private fun patternTextSchema(pattern: String, description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("minLength", 1)
    put("maxLength", MAXIMUM_PROTOCOL_TEXT_LENGTH)
    put("pattern", pattern)
    put("description", description)
}

private fun booleanSchema(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private fun workspaceFileSchema(): JsonObject = buildJsonObject {
    put("type", "string")
    put("minLength", 1)
    put("maxLength", MAXIMUM_WORKSPACE_FILE_LENGTH)
    put("description", "Workspace-relative file path.")
}

private fun sha256Schema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("pattern", "^[0-9a-f]{64}$")
    put("description", description)
}

private fun countSchema(description: String): JsonObject = integerSchema(
    minimum = 1,
    maximum = MAXIMUM_PROTOCOL_COUNT,
    description = description,
)

private fun integerSchema(
    minimum: Int,
    maximum: Int? = null,
    description: String,
): JsonObject = buildJsonObject {
    put("type", "integer")
    put("minimum", minimum)
    maximum?.let { put("maximum", it) }
    put("description", description)
}

private fun constantSchema(value: String, description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("const", value)
    put("description", description)
}

private fun enumSchema(values: List<String>, description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun relationSchema(): JsonObject = enumSchema(
    values = listOf(
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
