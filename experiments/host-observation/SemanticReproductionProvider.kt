package io.github.amichne.kast.appserver.manual

import io.github.amichne.kast.appserver.core.*
import io.github.amichne.kast.appserver.provider.*
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Manual boundary probe. No provider, process executor, or IDE object is replaced. */
object SemanticReproductionProvider {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 4) { "CLI WORKSPACE QUERY_JSON REPORT_JSON" }
        val exit = runBlocking {
            val cli = Path.of(arguments[0]).toRealPath()
            val workspace = Path.of(arguments[1]).toRealPath()
            val query = Json.parseToJsonElement(Files.readString(Path.of(arguments[2])))
            val reportPath = Path.of(arguments[3]).toAbsolutePath()
            fun report(document: JsonObject, exit: Int): Int {
                Files.writeString(reportPath, document.toString() + "\n")
                println(document)
                return exit
            }
            var processNumber = 0
            val recorder = BrokerProcessExecutor { request ->
                val started = System.nanoTime()
                val execution = JdkBrokerProcessExecutor.execute(request)
                val receipt = buildJsonObject {
                    put("executable", request.executable.path.toString())
                    putJsonArray("arguments") { request.arguments.forEach { add(it) } }
                    put("workingDirectory", request.workingDirectory.path.toString())
                    put("elapsedNanos", System.nanoTime() - started)
                    put("timeoutMillis", request.timeoutMillis)
                    put("maximumOutputBytes", request.maximumOutputBytes)
                    when (val input = request.input) {
                        BrokerProcessInput.Empty -> put("input", "")
                        is BrokerProcessInput.Document -> put("input", input.value)
                    }
                    when (execution) {
                        is BrokerProcessExecution.Completed -> {
                            put("outcome", "completed")
                            put("exitCode", execution.exitCode)
                            put("stdout", execution.stdout); put("stderr", execution.stderr)
                        }
                        is BrokerProcessExecution.Rejected -> {
                            put("outcome", "rejected"); put("failure", execution.failure.name)
                        }
                    }
                }
                Files.writeString(reportPath.resolveSibling("process-${processNumber++}.json"), receipt.toString())
                execution
            }
            val options = when (val result = KastProviderOptions.admit(cli, workspace, recorder)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return@runBlocking report(buildJsonObject {
                    put("stage", "provider-options"); put("failure", result.failure.name)
                }, 4)
            }
            // Defaults above retain JdkBrokerProcessExecutor. Qualification launches --version and --schema.
            val qualification = when (val result = KastProviderQualifier.qualify(options)) {
                is KastProviderQualification.Qualified -> result
                is KastProviderQualification.Rejected -> return@runBlocking report(buildJsonObject {
                    put("stage", "provider-qualification"); put("failure", result.failure.name)
                }, 4)
            }
            val broker = when (val admitted = Broker.create(listOf(qualification.registration), BrokerLimits.defaults())) {
                is Validation.Validated -> admitted.value
                is Validation.Rejected -> error("Manual broker construction rejected: ${admitted.failures}")
            }
            val context = BrokerInvocationContext.admit(
                "manual-live-native", "manual-turn", "manual-query", workspace,
            ).refined()
            val result = broker.dispatch(BrokerDispatchRequest(
                ToolAddress(ProviderNamespace.admit("kast").refined(), ToolName.admit("query").refined()),
                query,
                context,
            ))
            val report = buildJsonObject {
                put("harness", "semantic-reproduction-production-provider")
                put("cli", cli.toString()); put("workspace", workspace.toString())
                put("query", query)
                putJsonObject("qualification") {
                    put("cliVersion", qualification.evidence.cliVersion.value)
                    put("contractDigest", qualification.evidence.contractDigest.value)
                    put("schemaVersion", qualification.evidence.schemaVersion)
                    put("projectionVersion", qualification.evidence.projectionVersion)
                }
                put("invocationId", context.invocationId)
                when (result) {
                    is BrokerDispatch.Completed -> {
                        put("dispatch", "completed")
                        put("success", result.presentation.success)
                        putJsonArray("content") { result.presentation.content.forEach { add(it.text) } }
                        put("providerEnvelope", Json.parseToJsonElement(result.presentation.content.last().text))
                        when (val observer = result.presentation.observer) {
                            ObserverPresentation.None -> put("observer", "none")
                            is ObserverPresentation.Markdown -> put("observerMarkdown", observer.source())
                            is ObserverPresentation.FileChanges -> put("observer", "file-changes")
                        }
                    }
                    is BrokerDispatch.Rejected -> {
                        put("dispatch", "rejected")
                        put("failureCode", when (val failure = result.failure) {
                            is BrokerFailure.InvalidArguments -> "INVALID_ARGUMENTS"
                            is BrokerFailure.UnknownNamespace -> "UNKNOWN_NAMESPACE"
                            is BrokerFailure.UnknownTool -> "UNKNOWN_TOOL"
                            is BrokerFailure.ProviderStartupRejected -> failure.code.name
                            is BrokerFailure.ProviderInvocationRejected -> failure.code.name
                            is BrokerFailure.OutputContractRejected -> "OUTPUT_CONTRACT_REJECTED"
                            is BrokerFailure.InvocationCancelled -> "INVOCATION_CANCELLED"
                            is BrokerFailure.Overloaded -> "OVERLOADED"
                        })
                    }
                }
            }
            report(report, if (result is BrokerDispatch.Completed) {
                if (result.presentation.success) 0 else 2
            } else 3)
        }
        exitProcess(exit)
    }

    private fun ObserverPresentation.Markdown.source(): String = source.value

    private fun <T, E> Refinement<T, E>.refined(): T = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Manual input rejected: $failure")
    }
}
