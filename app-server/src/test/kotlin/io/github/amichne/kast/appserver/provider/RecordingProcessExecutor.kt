package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive

internal class RecordingProcessExecutor(
    private val schema: String,
    private val replacementSchema: String = schema,
    private val invocationDelayMillis: Long = 0,
    private val invocationDocument: String = """{"operation":"symbol.discover","status":"complete","items":[]}""",
) : BrokerProcessExecutor {
    val requests = mutableListOf<BrokerProcessRequest>()
    private var schemaReads = 0

    override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution {
        requests += request
        return when (request.arguments) {
            listOf("--version") -> BrokerProcessExecution.Completed(0, "kast 9.9.9\n", "")
            listOf("--schema") -> {
                schemaReads += 1
                BrokerProcessExecution.Completed(
                    0,
                    if (schemaReads == 1) schema else replacementSchema,
                    "",
                )
            }
            listOf("start") -> {
                delay(invocationDelayMillis)
                BrokerProcessExecution.Completed(
                    0,
                    """{"command":"start","status":"complete","runtime":"running"}""",
                    "",
                )
            }
            listOf("change", "apply") -> {
                delay(invocationDelayMillis)
                BrokerProcessExecution.Completed(
                    0,
                    """{"operation":"change.apply","status":"complete","changes":[]}""",
                    "",
                )
            }
            else -> {
                delay(invocationDelayMillis)
                BrokerProcessExecution.Completed(
                    0,
                    invocationDocument,
                    "",
                )
            }
        }
    }
}

internal fun capabilitySchema(): String {
    val policy = JsonPrimitive(CanonicalAgentToolDefinitions.policy.text)
    val symbolDescription = JsonPrimitive(CanonicalAgentToolDefinitions.symbolLookup.description.value)
    val changeDescription = JsonPrimitive(CanonicalAgentToolDefinitions.changeApply.description.value)
    return checkNotNull(RecordingProcessExecutor::class.java.getResource("/kast-provider-capability.json"))
        .readText()
        .replace("@POLICY@", policy.toString())
        .replace("@SYMBOL_DESCRIPTION@", symbolDescription.toString())
        .replace("@CHANGE_DESCRIPTION@", changeDescription.toString())
}
