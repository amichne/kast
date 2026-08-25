package io.github.amichne.kast.cli.codex

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Files
import java.nio.file.Path

private const val CLI_INITIALIZE_REQUEST_ID = 1L
private const val CLI_THREAD_START_REQUEST_ID = 2L
private const val CLI_TURN_START_REQUEST_ID = 3L

private val cliComparisonJson = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
}

/** One observed same-prompt App Server run whose only Kast access is the public CLI. */
internal class CodexCliComparison(
    private val root: Path,
    private val evidencePath: Path,
    private val prompt: String,
) {
    private var rawModelTokens = 0L
    private var threadModelTokens = 0L
    private var tokensBeforeFirstUsefulResult = 0L
    private var modelResponseCompletions = 0
    private var kastCommandCount = 0
    private var malformedCommands = 0
    private var correctiveCommands = 0
    private var finalAnswer = ""
    private var relationTargetNames = emptyList<String>()
    private val completedCommandIds = linkedSetOf<String>()
    private val completedSteps = linkedSetOf<CliSemanticStep>()

    fun run(): CodexCliComparisonEvidenceDocument {
        val stderrLog = evidencePath.resolveSibling("cli-comparison.app-server.stderr.log")
        val protocolLog = evidencePath.resolveSibling("cli-comparison.app-server.protocol.jsonl")
        Files.createDirectories(stderrLog.parent)
        val appServer = when (
            val start = AppServerJsonlSession.start(
                root,
                stderrLog,
                protocolLog,
                AppServerToolAccess.CLI_COMPARISON,
            )
        ) {
            is AppServerStart.Started -> start.session
            is AppServerStart.Rejected -> error("CLI app-server rejected: ${start.failure}")
        }
        return appServer.use { server ->
            initialize(server)
            val started = startThread(server)
            val turnId = startTurn(server, started.thread.id)
            val completed = awaitCompletion(server, started.thread.id, turnId)
            CodexCliComparisonEvidenceDocument(
                completed = completed,
                model = started.model,
                threadId = started.thread.id,
                turnId = turnId,
                appServerCommand = server.command,
                sandboxMode = AppServerSandboxModeDocument.DANGER_FULL_ACCESS,
                modelTokensBeforeFirstUsefulKastResult = tokensBeforeFirstUsefulResult,
                modelResponseCompletions = modelResponseCompletions,
                modelToolTurns = modelResponseCompletions + kastCommandCount,
                kastCommandCount = kastCommandCount,
                malformedCommands = malformedCommands,
                correctiveCommands = correctiveCommands,
                relationTargetNames = relationTargetNames,
                finalAnswerNamesReturnedCallers = finalAnswer.namesEvery(relationTargetNames),
                finalAnswer = finalAnswer,
            )
        }
    }

    private fun initialize(server: AppServerJsonlSession) {
        sendRequest(
            server,
            CLI_INITIALIZE_REQUEST_ID,
            "initialize",
            InitializeParamsDocument(
                ClientInfoDocument("kast-cli-comparison", "Kast CLI comparison", "1"),
                InitializeCapabilitiesDocument(experimentalApi = true, requestAttestation = false),
            ),
            InitializeParamsDocument.serializer(),
        )
        awaitResult(server, CLI_INITIALIZE_REQUEST_ID)
        send(server, cliComparisonJson.encodeToString(RpcNotificationDocument("initialized")))
    }

    private fun startThread(server: AppServerJsonlSession): ThreadStartResultDocument {
        sendRequest(
            server,
            CLI_THREAD_START_REQUEST_ID,
            "thread/start",
            ThreadStartParamsDocument(
                cwd = root.toString(),
                approvalPolicy = "never",
                sandbox = AppServerSandboxModeDocument.DANGER_FULL_ACCESS,
                ephemeral = true,
                experimentalRawEvents = true,
                dynamicTools = emptyList(),
            ),
            ThreadStartParamsDocument.serializer(),
        )
        return cliComparisonJson.decodeFromJsonElement(
            ThreadStartResultDocument.serializer(),
            awaitResult(server, CLI_THREAD_START_REQUEST_ID),
        )
    }

    private fun startTurn(server: AppServerJsonlSession, threadId: String): String {
        sendRequest(
            server,
            CLI_TURN_START_REQUEST_ID,
            "turn/start",
            TurnStartParamsDocument(
                threadId,
                listOf(TextUserInputDocument("text", prompt, emptyList())),
            ),
            TurnStartParamsDocument.serializer(),
        )
        return cliComparisonJson.decodeFromJsonElement(
            TurnStartResultIdentityDocument.serializer(),
            awaitResult(server, CLI_TURN_START_REQUEST_ID),
        ).turn.id
    }

    private fun awaitCompletion(
        server: AppServerJsonlSession,
        threadId: String,
        turnId: String,
    ): Boolean {
        while (true) {
            val incoming = next(server)
            when (incoming.method) {
                "item/tool/call" -> rejectUnexpectedDynamicCall(server, incoming)
                "item/completed" -> observeCompletedItem(incoming)
                "rawResponse/completed" -> observeRawResponse(incoming)
                "thread/tokenUsage/updated" -> observeThreadTokenUsage(incoming)
                "turn/completed" -> {
                    val completion = decodeParams<TurnCompletedParamsDocument>(incoming)
                    check(completion.threadId == threadId)
                    check(completion.turn.id == turnId)
                    return completion.turn.status == "completed"
                }
            }
        }
    }

    private fun rejectUnexpectedDynamicCall(
        server: AppServerJsonlSession,
        incoming: RpcIncomingDocument,
    ) {
        val response = DynamicToolCallResponseDocument(
            listOf(DynamicToolOutputTextDocument("inputText", "UNKNOWN_TOOL")),
            success = false,
        )
        send(
            server,
            cliComparisonJson.encodeToString(RpcResponseDocument(checkNotNull(incoming.id), response)),
        )
    }

    private fun observeCompletedItem(incoming: RpcIncomingDocument) {
        val item = cliComparisonJson.decodeFromJsonElement(
            ObservedThreadItemDocument.serializer(),
            decodeParams<ItemNotificationParamsDocument>(incoming).item,
        )
        when (item.type) {
            "commandExecution" -> observeCommand(item)
            "agentMessage" -> if (!item.text.isNullOrBlank()) finalAnswer = item.text
        }
    }

    private fun observeCommand(item: ObservedThreadItemDocument) {
        val command = item.command ?: return
        if (!command.invokesKastCli()) return
        val identity = item.id ?: "${item.command}:${item.aggregatedOutput}:${item.exitCode}"
        if (!completedCommandIds.add(identity)) return
        kastCommandCount += 1
        val step = CliSemanticStep.from(command)
        if (step != CliSemanticStep.OTHER && !completedSteps.add(step)) {
            correctiveCommands += 1
        }
        val succeeded = item.exitCode == 0 && item.status != "failed"
        if (!succeeded && item.aggregatedOutput.isMalformedKastInvocation()) {
            malformedCommands += 1
        }
        val documents = item.aggregatedOutput?.observedKastDocuments().orEmpty()
        if (
            succeeded &&
            documents.any { it.status == "complete" || it.status == "qualified" } &&
            tokensBeforeFirstUsefulResult == 0L
        ) {
            tokensBeforeFirstUsefulResult = rawModelTokens.coerceAtLeast(threadModelTokens)
        }
        documents.forEach { document ->
            if (document.operation == "relation.read") {
                relationTargetNames = document.targets.map { it.name }
            }
        }
    }

    private fun observeRawResponse(incoming: RpcIncomingDocument) {
        val response = decodeParams<RawResponseCompletedParamsDocument>(incoming)
        response.usage?.let { rawModelTokens += it.totalTokens }
        modelResponseCompletions += 1
    }

    private fun observeThreadTokenUsage(incoming: RpcIncomingDocument) {
        threadModelTokens = decodeParams<ThreadTokenUsageParamsDocument>(incoming)
            .tokenUsage.total.totalTokens
    }

    private fun awaitResult(server: AppServerJsonlSession, requestId: Long): JsonElement {
        while (true) {
            val incoming = next(server)
            if (incoming.id?.toString() == requestId.toString() && incoming.method == null) {
                check(incoming.error == null) { "app-server RPC error: ${incoming.error}" }
                return checkNotNull(incoming.result)
            }
        }
    }

    private fun next(server: AppServerJsonlSession): RpcIncomingDocument = when (
        val read = server.next(cliComparisonJson)
    ) {
        is AppServerIncoming.Received -> read.document
        is AppServerIncoming.Rejected -> error("CLI app-server read rejected: ${read.failure}")
    }

    private inline fun <reified Params> decodeParams(incoming: RpcIncomingDocument): Params =
        cliComparisonJson.decodeFromJsonElement(
            kotlinx.serialization.serializer<Params>(),
            checkNotNull(incoming.params),
        )

    private fun <Params> sendRequest(
        server: AppServerJsonlSession,
        id: Long,
        method: String,
        params: Params,
        serializer: kotlinx.serialization.KSerializer<Params>,
    ) = send(
        server,
        cliComparisonJson.encodeToString(
            RpcRequestDocument(id, method, cliComparisonJson.encodeToJsonElement(serializer, params)),
        ),
    )

    private fun send(server: AppServerJsonlSession, document: String) {
        check(server.send(document) == null) { "app-server write rejected" }
    }
}

private enum class CliSemanticStep {
    SYMBOL_DISCOVER,
    SYMBOL_RESOLVE,
    RELATION_READ,
    OTHER;

    companion object {
        fun from(command: String): CliSemanticStep = when {
            Regex("(?:^|\\s)(?:--help|-h)(?:\\s|$)").containsMatchIn(command) -> OTHER
            Regex("\\bkast\\s+symbol\\s+discover\\b").containsMatchIn(command) -> SYMBOL_DISCOVER
            Regex("\\bkast\\s+symbol\\s+resolve\\b").containsMatchIn(command) -> SYMBOL_RESOLVE
            Regex("\\bkast\\s+relation\\s+read\\b").containsMatchIn(command) -> RELATION_READ
            else -> OTHER
        }
    }
}

private fun String.observedKastDocuments(): List<ObservedKastCliOutputDocument> = lineSequence()
    .mapNotNull { line ->
        try {
            cliComparisonJson.decodeFromString(ObservedKastCliOutputDocument.serializer(), line)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    .toList()

internal fun String.namesEvery(names: List<String>): Boolean =
    names.isNotEmpty() && names.distinct().all(::contains)

internal fun String.invokesKastCli(): Boolean =
    Regex("(?:^|[\\s'\";|&])(?:[^\\s'\";|&]*/)?kast(?:\\s|$)").containsMatchIn(this)

private fun String?.isMalformedKastInvocation(): Boolean = this?.let { output ->
    listOf(
        "Invalid value for",
        "Missing option",
        "Unknown option",
        "Usage:",
        "request-invalid",
    ).any(output::contains)
} == true
