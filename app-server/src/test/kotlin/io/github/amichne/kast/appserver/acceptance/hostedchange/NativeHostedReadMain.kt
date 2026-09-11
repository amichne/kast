package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.KastToolSelection
import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerDispatch
import io.github.amichne.kast.appserver.core.BrokerDispatchRequest
import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ToolAddress
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.provider.KastProviderOptions
import io.github.amichne.kast.appserver.provider.KastProviderQualification
import io.github.amichne.kast.appserver.provider.KastProviderQualifier
import io.github.amichne.kast.appserver.schema.JsonSchemaViolationEvidenceDocument
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.OperationEffect
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Test-only pipe transport: production provider results are assessed in memory, never written as diagnostic logs. */
object NativeHostedReadMain {
    @JvmStatic
    fun main(arguments: Array<String>): Unit = runBlocking {
        try {
            demand(arguments.size == 2, NativeFailure.INPUT_REJECTED)
            val product = Path.of(arguments[0]).toRealPath()
            val workspace = Path.of(arguments[1]).toRealPath()
            val transport = NativeHostedReadTransport.open(product, workspace)
            val input = BufferedInputStream(System.`in`)
            while (true) {
                val request = boundedReadRequest(input) ?: break
                println(readResponseJson.encodeToString(transport.invoke(request)))
                System.out.flush()
            }
        } catch (_: Exception) {
            System.err.println("NATIVE_READ_TRANSPORT_REJECTED")
            kotlin.system.exitProcess(1)
        }
    }
}

@Serializable
private sealed interface NativeReadResponse {
    @Serializable
    @SerialName("completed")
    data class Completed(val success: Boolean, val envelope: JsonElement) : NativeReadResponse

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val failure: String,
        val outputViolationEvidence: JsonSchemaViolationEvidenceDocument? = null,
    ) : NativeReadResponse
}

private val readResponseJson = Json { classDiscriminator = "kind" }

private class NativeHostedReadTransport(private val broker: Broker, private val workspace: Path) {
    private var sequence = 0

    suspend fun invoke(document: JsonObject): NativeReadResponse {
        val tool = document.textAt("tool")
        demand(document.keys == setOf("tool", "arguments") && tool in readToolNames, NativeFailure.INPUT_REJECTED)
        val context =
            BrokerInvocationContext.admit(
                    threadId = "native-read-regression",
                    turnId = "read-turn-${++sequence}",
                    callId = "read-call-$sequence",
                    workingDirectory = workspace,
                )
                .nativeValue()
        val result =
            broker.dispatch(
                BrokerDispatchRequest(
                    ToolAddress(ProviderNamespace.admit("kast").nativeValue(), ToolName.admit(tool).nativeValue()),
                    document.objectAt("arguments"),
                    context,
                )
            )
        return when (result) {
            is BrokerDispatch.Completed ->
                NativeReadResponse.Completed(
                    result.presentation.success,
                    Json.parseToJsonElement(result.presentation.content.last().text),
                )
            is BrokerDispatch.Rejected ->
                NativeReadResponse.Rejected(
                    readBrokerFailure(result.failure),
                    when (val failure = result.failure) {
                        is BrokerFailure.OutputContractRejected -> failure.violationEvidence.toDocument()
                        else -> null
                    },
                )
        }
    }

    companion object {
        suspend fun open(product: Path, workspace: Path): NativeHostedReadTransport {
            val origin = Path.of(Broker::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
            demand(
                product.parent == workspace.parent &&
                    origin.startsWith(product.resolve("lib")) &&
                    Files.isRegularFile(origin),
                NativeFailure.PRODUCT_CLASS_ORIGIN_REJECTED,
            )
            val options =
                KastProviderOptions.admit(
                        executable = product.resolve("bin/kast"),
                        qualificationDirectory = workspace,
                        toolSelection = KastToolSelection.admit(readToolNames.joinToString(",")).nativeValue(),
                    )
                    .nativeValue()
            val qualified =
                KastProviderQualifier.qualify(options) as? KastProviderQualification.Qualified
                    ?: throw NativeRejected(NativeFailure.PROVIDER_QUALIFICATION_REJECTED)
            return NativeHostedReadTransport(
                Broker.create(listOf(qualified.registration), BrokerLimits.defaults()).nativeValue(),
                workspace,
            )
        }
    }
}

private val readToolNames =
    CanonicalAgentToolDefinitions.defaultAppServerTools
        .filter { it.operation.effect in setOf(OperationEffect.NONE, OperationEffect.INTELLIJ_READ) }
        .map { it.name.value }
        .toSet()

private fun readBrokerFailure(failure: BrokerFailure): String =
    when (failure) {
        is BrokerFailure.InvalidArguments -> "INVALID_ARGUMENTS"
        is BrokerFailure.UnknownNamespace -> "UNKNOWN_NAMESPACE"
        is BrokerFailure.UnknownTool -> "UNKNOWN_TOOL"
        is BrokerFailure.ProviderStartupRejected -> failure.code.name
        is BrokerFailure.ProviderInvocationRejected -> failure.code.name
        is BrokerFailure.OutputContractRejected -> "OUTPUT_CONTRACT_REJECTED"
        is BrokerFailure.InvocationCancelled -> "INVOCATION_CANCELLED"
        is BrokerFailure.Overloaded -> "OVERLOADED"
    }

/** EOF is a transport condition; an incomplete or oversized frame cannot become a request. */
internal fun boundedReadRequest(input: java.io.InputStream): JsonObject? {
    val bytes = ByteArrayOutputStream()
    while (true) {
        when (val next = input.read()) {
            -1 -> {
                demand(bytes.size() == 0, NativeFailure.INPUT_REJECTED)
                return null
            }
            '\n'.code -> return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            else -> {
                demand(bytes.size() < MAXIMUM_READ_REQUEST_BYTES, NativeFailure.INPUT_REJECTED)
                bytes.write(next)
            }
        }
    }
}

private const val MAXIMUM_READ_REQUEST_BYTES = 2 * 1024 * 1024
