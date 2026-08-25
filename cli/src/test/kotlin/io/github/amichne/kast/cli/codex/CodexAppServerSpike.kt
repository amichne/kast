package io.github.amichne.kast.cli.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Files
import java.nio.file.Path

private const val INITIALIZE_REQUEST_ID = 1L
private const val THREAD_START_REQUEST_ID = 2L
private const val TURN_START_REQUEST_ID = 3L

private val appServerJson = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
}

internal class CodexAppServerSpike(
    private val root: Path,
    private val evidencePath: Path,
    private val request: CodexAppServerEvaluationRequestDocument,
) {
    private val prompt = CodexEvaluationWorkflowPrompt.forSymbol(request.symbolQuery)
    private var rawModelTokens = 0L
    private var threadModelTokens = 0L
    private var tokensBeforeFirstUsefulResult = 0L
    private var modelResponseCompletions = 0
    private var toolSearchCalls = 0
    private var firstDynamicCallObserved = false
    private var discoveryBeforeDynamicCall = false
    private var inheritedMcpStartupObserved = false
    private var unexpectedToolCallObserved = false
    private var commandObserved = false
    private var kastProcessObserved = false
    private var finalAnswer = ""

    fun run() = when (request.mode) {
        CodexAppServerEvaluationModeDocument.DYNAMIC_ONLY -> {
            val dynamic = runDynamic(request.model)
            writeDynamicEvidence(dynamic)
        }
        CodexAppServerEvaluationModeDocument.COMPARISON -> {
            val comparison = CodexCliComparison(
                root,
                evidencePath,
                prompt,
                request.model,
            ).run()
            val dynamic = runDynamic(comparison.model)
            writeComparisonEvidence(dynamic, comparison)
        }
    }

    private fun runDynamic(model: String?): CodexDynamicToolsPathEvidenceDocument {
        val kastSession = when (val opening = ExistingKastRuntimeConnection.open(root)) {
            is KastSpikeSessionOpening.Opened -> opening.session
            is KastSpikeSessionOpening.Rejected -> error("Kast session rejected: ${opening.failure}")
        }
        kastSession.use { wireSession ->
            val stderrLog = evidencePath.resolveSibling("dynamic.app-server.stderr.log")
            val protocolLog = evidencePath.resolveSibling("dynamic.app-server.protocol.jsonl")
            Files.createDirectories(stderrLog.parent)
            val appServer = when (
                val start = AppServerJsonlSession.start(
                    root,
                    stderrLog,
                    protocolLog,
                    AppServerToolAccess.DYNAMIC_TOOLS_ONLY,
                )
            ) {
                is AppServerStart.Started -> start.session
                is AppServerStart.Rejected -> error("app-server rejected: ${start.failure}")
            }
            appServer.use { server ->
                val adapter = CodexDynamicToolsAdapter(CanonicalWireKastReadOperations(wireSession))
                initialize(server)
                val started = startThread(server, model)
                val turnId = startTurn(server, started.thread.id)
                val modelCompleted = awaitCompletion(server, adapter, started.thread.id, turnId)
                return dynamicEvidence(
                    server.command,
                    adapter,
                    started,
                    turnId,
                    modelCompleted,
                )
            }
        }
    }

    private fun initialize(server: AppServerJsonlSession) {
        sendRequest(
            server,
            INITIALIZE_REQUEST_ID,
            "initialize",
            InitializeParamsDocument(
                ClientInfoDocument("kast-dynamic-tools-spike", "Kast dynamic tools spike", "1"),
                InitializeCapabilitiesDocument(
                    experimentalApi = true,
                    requestAttestation = false,
                ),
            ),
            InitializeParamsDocument.serializer(),
        )
        awaitResult(server, INITIALIZE_REQUEST_ID)
        send(server, appServerJson.encodeToString(RpcNotificationDocument("initialized")))
    }

    private fun startThread(
        server: AppServerJsonlSession,
        model: String?,
    ): ThreadStartResultDocument {
        sendRequest(
            server,
            THREAD_START_REQUEST_ID,
            "thread/start",
            ThreadStartParamsDocument(
                cwd = root.toString(),
                approvalPolicy = "never",
                sandbox = AppServerSandboxModeDocument.READ_ONLY,
                ephemeral = true,
                experimentalRawEvents = true,
                dynamicTools = listOf(CodexDynamicToolDefinitions.kastNamespace()),
                model = model,
            ),
            ThreadStartParamsDocument.serializer(),
        )
        return appServerJson.decodeFromJsonElement(
            ThreadStartResultDocument.serializer(),
            awaitResult(server, THREAD_START_REQUEST_ID),
        )
    }

    private fun startTurn(server: AppServerJsonlSession, threadId: String): String {
        sendRequest(
            server,
            TURN_START_REQUEST_ID,
            "turn/start",
            TurnStartParamsDocument(
                threadId,
                listOf(TextUserInputDocument("text", prompt, emptyList())),
            ),
            TurnStartParamsDocument.serializer(),
        )
        val result = awaitResult(server, TURN_START_REQUEST_ID)
        return appServerJson.decodeFromJsonElement(
            TurnStartResultIdentityDocument.serializer(),
            result,
        ).turn.id
    }

    private fun awaitCompletion(
        server: AppServerJsonlSession,
        adapter: CodexDynamicToolsAdapter,
        threadId: String,
        turnId: String,
    ): Boolean {
        while (true) {
            val incoming = when (val read = server.next(appServerJson)) {
                is AppServerIncoming.Received -> read.document
                is AppServerIncoming.Rejected -> error("app-server read rejected: ${read.failure}")
            }
            when (incoming.method) {
                "item/tool/call" -> handleDynamicToolCall(server, adapter, incoming)
                "item/started", "item/completed" -> observeThreadItem(incoming)
                "rawResponseItem/completed" -> observeRawItem(incoming)
                "rawResponse/completed" -> observeRawResponse(incoming)
                "thread/tokenUsage/updated" -> observeThreadTokenUsage(incoming)
                "mcpServer/startupStatus/updated" -> inheritedMcpStartupObserved = true
                "turn/completed" -> {
                    val completion = decodeParams<TurnCompletedParamsDocument>(incoming)
                    check(completion.threadId == threadId)
                    check(completion.turn.id == turnId)
                    check(completion.turn.status == "completed")
                    return true
                }
            }
        }
    }

    private fun handleDynamicToolCall(
        server: AppServerJsonlSession,
        adapter: CodexDynamicToolsAdapter,
        incoming: RpcIncomingDocument,
    ) {
        val id = checkNotNull(incoming.id)
        val call = decodeParams<DynamicToolCallParamsDocument>(incoming)
        if (!firstDynamicCallObserved) {
            firstDynamicCallObserved = true
            discoveryBeforeDynamicCall = toolSearchCalls > 0
        }
        val result = adapter.call(call.namespace, call.tool, call.arguments)
        if (
            tokensBeforeFirstUsefulResult == 0L &&
            result is CodexDynamicToolCallResult.Succeeded
        ) {
            tokensBeforeFirstUsefulResult = rawModelTokens.coerceAtLeast(threadModelTokens)
        }
        val response = when (result) {
            is CodexDynamicToolCallResult.Succeeded -> DynamicToolCallResponseDocument(
                listOf(DynamicToolOutputTextDocument("inputText", result.canonicalJson)),
                success = true,
            )
            is CodexDynamicToolCallResult.Rejected -> DynamicToolCallResponseDocument(
                listOf(DynamicToolOutputTextDocument("inputText", result.failure.name)),
                success = false,
            )
        }
        send(server, appServerJson.encodeToString(RpcResponseDocument(id, response)))
    }

    private fun observeThreadItem(incoming: RpcIncomingDocument) {
        val item = decodeParams<ItemNotificationParamsDocument>(incoming).item
        val observed = appServerJson.decodeFromJsonElement(
            ObservedThreadItemDocument.serializer(),
            item,
        )
        when (observed.type) {
            "commandExecution" -> {
                commandObserved = true
                if (observed.command?.invokesKastCli() == true) {
                    kastProcessObserved = true
                }
            }
            "agentMessage" -> if (!observed.text.isNullOrBlank()) finalAnswer = observed.text
        }
    }

    private fun observeRawItem(incoming: RpcIncomingDocument) {
        val item = decodeParams<ItemNotificationParamsDocument>(incoming).item
        val observed = appServerJson.decodeFromJsonElement(
            ObservedRawResponseItemDocument.serializer(),
            item,
        )
        when {
            observed.type == "tool_search_call" -> toolSearchCalls += 1
            observed.type == "custom_tool_call" &&
                observed.name == "exec" &&
                observed.input?.contains("ALL_TOOLS") == true -> toolSearchCalls += 1
            observed.type == "custom_tool_call" || observed.type.endsWith("_call") -> {
                unexpectedToolCallObserved = true
            }
        }
    }

    private fun observeRawResponse(incoming: RpcIncomingDocument) {
        val response = decodeParams<RawResponseCompletedParamsDocument>(incoming)
        response.usage?.let { rawModelTokens += it.totalTokens }
        modelResponseCompletions += 1
    }

    private fun observeThreadTokenUsage(incoming: RpcIncomingDocument) {
        val usage = decodeParams<ThreadTokenUsageParamsDocument>(incoming)
        threadModelTokens = usage.tokenUsage.total.totalTokens
    }

    private fun dynamicEvidence(
        command: List<String>,
        adapter: CodexDynamicToolsAdapter,
        started: ThreadStartResultDocument,
        turnId: String,
        modelCompleted: Boolean,
    ): CodexDynamicToolsPathEvidenceDocument {
        val metrics = adapter.metrics()
        val definitions = CodexDynamicToolDefinitions.kastNamespace().tools
        val allDeferred = definitions.size == 2 && definitions.all { it.deferLoading }
        val correctRelation = request.expectedCallerNames.all(metrics.relationTargetNames::contains) &&
            metrics.relationQualificationNames.isEmpty() &&
            metrics.relationRejectionNames.isEmpty()
        return CodexDynamicToolsPathEvidenceDocument(
            completed = modelCompleted,
            model = started.model,
            threadId = started.thread.id,
            turnId = turnId,
            appServerCommand = command,
            modelTokensBeforeFirstUsefulKastResult = tokensBeforeFirstUsefulResult,
            modelResponseCompletions = modelResponseCompletions,
            modelToolTurns = modelResponseCompletions + metrics.dynamicToolCalls,
            dynamicToolCalls = metrics.dynamicToolCalls,
            toolSearchCalls = toolSearchCalls,
            malformedInvocations = metrics.malformedInvocations,
            correctiveInvocations = metrics.correctiveInvocations,
            deferredDiscoveryObserved = discoveryBeforeDynamicCall,
            fullDeferredSchemasPresentBeforeDiscovery = !(allDeferred && discoveryBeforeDynamicCall),
            selectorRoundTripUnchanged = metrics.selectorRoundTripUnchanged,
            inheritedMcpStartupObserved = inheritedMcpStartupObserved,
            unexpectedToolCallObserved = unexpectedToolCallObserved,
            commandExecutionObserved = commandObserved,
            kastProcessExecutionObserved = kastProcessObserved,
            correctRelationResult = correctRelation,
            relationTargetNames = metrics.relationTargetNames,
            relationQualificationNames = metrics.relationQualificationNames,
            relationRejectionNames = metrics.relationRejectionNames,
            relationRejected = metrics.relationRejectionNames.isNotEmpty(),
            finalAnswerNamesReturnedCallers = finalAnswer.namesEvery(
                metrics.relationTargetNames,
            ),
            finalAnswer = finalAnswer,
        )
    }

    private fun writeDynamicEvidence(dynamic: CodexDynamicToolsPathEvidenceDocument) {
        val evidence = CodexAppServerDynamicEvaluationEvidenceDocument(
            schemaVersion = 1,
            mode = CodexAppServerEvaluationModeDocument.DYNAMIC_ONLY,
            scenario = scenario(),
            model = dynamic.model,
            prompt = prompt,
            workingDirectory = root.toString(),
            dynamic = dynamic,
            decision = if (dynamic.isGo()) {
                CodexSpikeDecisionDocument.GO
            } else {
                CodexSpikeDecisionDocument.NO_GO
            },
        )
        CodexEvaluationEvidenceWriter.write(evidencePath, evidence)
    }

    private fun writeComparisonEvidence(
        dynamic: CodexDynamicToolsPathEvidenceDocument,
        comparison: CodexCliComparisonEvidenceDocument,
    ) {
        val evidence = CodexAppServerComparisonEvidenceDocument(
            schemaVersion = 1,
            mode = CodexAppServerEvaluationModeDocument.COMPARISON,
            scenario = scenario(),
            model = dynamic.model,
            prompt = prompt,
            workingDirectory = root.toString(),
            dynamic = dynamic,
            cliComparison = comparison,
            decision = if (dynamic.isGoAgainst(comparison)) {
                CodexSpikeDecisionDocument.GO
            } else {
                CodexSpikeDecisionDocument.NO_GO
            },
        )
        CodexEvaluationEvidenceWriter.write(evidencePath, evidence)
    }

    private fun scenario() = CodexAppServerEvaluationScenarioDocument(
        symbolQuery = request.symbolQuery,
        expectedCallerNames = request.expectedCallerNames,
        requestedModel = request.model,
    )

    private fun awaitResult(server: AppServerJsonlSession, requestId: Long): JsonElement {
        while (true) {
            val incoming = when (val read = server.next(appServerJson)) {
                is AppServerIncoming.Received -> read.document
                is AppServerIncoming.Rejected -> error("app-server read rejected: ${read.failure}")
            }
            if (incoming.method == "mcpServer/startupStatus/updated") {
                inheritedMcpStartupObserved = true
            }
            if (incoming.id?.toString() == requestId.toString() && incoming.method == null) {
                check(incoming.error == null) { "app-server RPC error: ${incoming.error}" }
                return checkNotNull(incoming.result)
            }
        }
    }

    private inline fun <reified Params> decodeParams(incoming: RpcIncomingDocument): Params =
        appServerJson.decodeFromJsonElement(
            kotlinx.serialization.serializer<Params>(),
            checkNotNull(incoming.params),
        )

    private fun <Params> sendRequest(
        server: AppServerJsonlSession,
        id: Long,
        method: String,
        params: Params,
        serializer: kotlinx.serialization.KSerializer<Params>,
    ) {
        val request = RpcRequestDocument(
            id,
            method,
            appServerJson.encodeToJsonElement(serializer, params),
        )
        send(server, appServerJson.encodeToString(request))
    }

    private fun send(server: AppServerJsonlSession, document: String) {
        check(server.send(document) == null) { "app-server write rejected" }
    }
}
