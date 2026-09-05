package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.core.BrokerDispatch
import io.github.amichne.kast.cli.broker.core.BrokerDispatchRequest
import io.github.amichne.kast.cli.broker.core.BrokerInvocationContext
import io.github.amichne.kast.cli.broker.core.BrokerLimits
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ToolAddress
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.cli.broker.core.ToolPresentation
import io.github.amichne.kast.cli.broker.schema.canonicalJson
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.TimeSource

/** Account-independent installed proof: real CLI processes and the production broker dispatcher. */
object InstalledColdBrokerAcceptance {
    @JvmStatic
    fun main(arguments: Array<String>) = runBlocking<Unit> {
        require(arguments.size == 2) { "Expected request and evidence paths" }
        val observations = InstalledColdBrokerEvidence(Path.of(arguments[1]))
        try {
            val request = Json.decodeFromString<Request>(Files.readString(Path.of(arguments[0])))
            val workspace = Path.of(request.workspace).toRealPath()
            val options = KastProviderOptions.admit(
                Path.of(request.kast), workspace, observations.executor(JdkBrokerProcessExecutor),
            ).required()
            observations.advance(ColdBrokerStage.QUALIFICATION)
            val qualification = KastProviderQualifier.qualify(options)
            check(qualification is KastProviderQualification.Qualified) { "Installed contract qualification failed" }
            observations.advance(ColdBrokerStage.CATALOG)
            val broker = when (val admission = Broker.create(listOf(qualification.registration), BrokerLimits.defaults())) {
                is Validation.Validated -> admission.value
                is Validation.Rejected -> error("Installed catalog rejected")
            }
            check(broker.catalog.namespaces.single().tools.none { it.name.value.startsWith("change_") })
            val context = BrokerInvocationContext.admit("installed-acceptance", "cold", "call", workspace).required()
            suspend fun cli(vararg arguments: String): JsonObject {
                val process = BrokerProcessRequest.admit(
                    options.executable, arguments.toList(), options.qualificationDirectory,
                    maximumOutputBytes = 1_048_576, timeoutMillis = 60_000,
                ).required()
                val completed = options.processExecutor.execute(process)
                check(completed is BrokerProcessExecution.Completed && completed.exitCode == 0) {
                    "Installed CLI command failed: ${arguments.take(2)}"
                }
                return Json.parseToJsonElement(completed.stdout).jsonObject
            }
            suspend fun tool(name: String, input: JsonObject): ToolPresentation {
                val dispatch = broker.dispatch(BrokerDispatchRequest(
                    ToolAddress(ProviderNamespace.admit("kast").required(), ToolName.admit(name).required()),
                    input, context,
                ))
                observations.dispatch(dispatch)
                check(dispatch is BrokerDispatch.Completed && dispatch.presentation.success) {
                    "Installed broker invocation failed: $name"
                }
                return dispatch.presentation
            }
            fun ToolPresentation.document(): JsonObject =
                Json.parseToJsonElement(content.single().text).jsonObject.getValue("document").jsonObject

            observations.advance(ColdBrokerStage.COLD_STATUS)
            check(cli("status").getValue("runtime").jsonPrimitive.content != "running") {
                "Cold acceptance requires an inactive workspace runtime"
            }
            val cold = TimeSource.Monotonic.markNow()
            val evidence = try {
                observations.advance(ColdBrokerStage.DISCOVERY)
                val discovery = tool("symbol_lookup", buildJsonObject {
                    put("mode", "name"); put("query", request.query); put("kind", "symbol")
                    put("match", "exact-name"); put("limit", 10)
                }).document()
                val coldMillis = cold.elapsedNow().inWholeMilliseconds
                check(coldMillis <= 240_000) { "Installed cold acceptance exceeded 240 seconds" }
                check(discovery == cli("symbol", "discover", "--query", request.query,
                    "--kind", "symbol", "--match", "exact-name", "--limit", "10")) {
                    "CLI and broker discovery evidence differ"
                }
                val candidate = discovery.getValue("items").jsonArray.single().jsonObject
                    .getValue("candidateSelector").jsonPrimitive.content
                observations.advance(ColdBrokerStage.INSPECTION)
                val inspected = tool("symbol_inspect", buildJsonObject { put("candidate", candidate) }).document()
                val selector = inspected.getValue("symbol").jsonObject.getValue("selector").jsonPrimitive.content
                observations.advance(ColdBrokerStage.SOURCE)
                val source = tool("source_read", buildJsonObject { put("anchor", selector); put("text", "complete") })
                val markdown = source.observer
                check(markdown is ObserverPresentation.Markdown && "```kotlin" in markdown.source.value)
                check(selector !in markdown.source.value && "sha256:" !in markdown.source.value)
                val sourceDocument = source.document()
                check(sourceDocument == cli("source", "read", "--anchor", selector, "--text", "complete"))
                val lines = sourceDocument.getValue("text").jsonObject.getValue("lines").jsonObject
                val firstLine = lines.getValue("startInclusive").jsonPrimitive.content.toLong()
                val lastLine = lines.getValue("endInclusive").jsonPrimitive.content.toLong()
                check("lines $firstLine–$lastLine" in markdown.source.value)
                observations.advance(ColdBrokerStage.RELATION)
                val relation = tool("semantic_query", buildJsonObject {
                    put("selector", selector); put("relation", "references"); put("limit", 10)
                }).document()
                check(relation == cli("relation", "read", "--selector", selector, "--relation", "references", "--limit", "10"))
                buildJsonObject {
                    put("schemaVersion", 2); put("status", "passed")
                    put("cliVersion", qualification.evidence.cliVersion.value)
                    put("contractDigest", qualification.evidence.contractDigest.value)
                    put("coldInvocationMillis", coldMillis)
                    put("discoveryDigest", digest(canonicalJson(discovery)))
                    put("sourceDigest", digest(canonicalJson(sourceDocument)))
                    put("sourceFirstLine", firstLine); put("sourceLastLine", lastLine)
                    put("selectorDigest", digest(selector))
                    put("relationDigest", digest(canonicalJson(relation)))
                    put("observerDigest", digest(markdown.source.value))
                    put("readOnlyCatalog", true); put("cliEquivalent", true); put("selectorReused", true)
                }
            } catch (failure: Exception) {
                observations.reject(ColdBrokerFailure.ASSERTION_REJECTED)
                throw failure
            } finally {
                cli("stop")
            }
            observations.complete(evidence)
        } catch (failure: Exception) {
            observations.reject(ColdBrokerFailure.ASSERTION_REJECTED)
            throw failure
        }
    }

    @Serializable
    private data class Request(val kast: String, val workspace: String, val query: String)

    private fun <T, E> Refinement<T, E>.required(): T = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Acceptance input rejected: $failure")
    }

    private fun digest(value: String): String = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
    )
}
