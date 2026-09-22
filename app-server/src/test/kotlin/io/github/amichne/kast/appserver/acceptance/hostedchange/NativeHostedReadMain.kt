package io.github.amichne.kast.appserver.acceptance.hostedchange

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
import io.github.amichne.kast.appserver.provider.KastProviderQualifier
import io.github.amichne.kast.appserver.schema.CompiledJsonSchema
import io.github.amichne.kast.appserver.schema.JsonSchemaViolationEvidenceDocument
import io.github.amichne.kast.protocol.contract.SourceReadCause
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
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
internal sealed interface NativeReadResponse {
    @Serializable
    @SerialName("validation_accepted")
    data class ValidationAccepted(val schemaDigest: String) : NativeReadResponse

    @Serializable
    @SerialName("validation_rejected")
    data class ValidationRejected(val evidence: JsonSchemaViolationEvidenceDocument) : NativeReadResponse

    @Serializable
    @SerialName("completed")
    data class Completed(
        val success: Boolean,
        val envelope: JsonElement,
        val presentation: NativePresentationEvidence,
    ) : NativeReadResponse

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val failure: String,
        val outputViolationEvidence: JsonSchemaViolationEvidenceDocument? = null,
        val sourceCause: SourceReadCause? = null,
    ) : NativeReadResponse
}

private val readResponseJson = Json { classDiscriminator = "kind" }

private class NativeHostedReadTransport(
    private val broker: Broker,
    private val workspace: Path,
    private val schemas: Map<String, CompiledJsonSchema>,
) {
    private var sequence = 0

    suspend fun invoke(document: JsonObject): NativeReadResponse {
        val request = readRequestJson.decodeFromJsonElement(NativeReadRequest.serializer(), document)
        val definition = CanonicalAgentToolDefinitions.resolveInput(request.tool).nativeValue()
        demand(definition.name.value in nativeReadToolNames, NativeFailure.INPUT_REJECTED)
        val schema = schemas.getValue(definition.name.value)
        return when (request) {
            is NativeReadRequest.Invoke -> dispatch(request)
            is NativeReadRequest.Validate -> validateNativeReadOutput(schema, request.document)
            is NativeReadRequest.ValidateEnvelope -> validateNativeReadEnvelope(schema, request.envelope)
        }
    }

    private suspend fun dispatch(request: NativeReadRequest.Invoke): NativeReadResponse {
        val tool = request.tool
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
                    request.arguments,
                    context,
                )
            )
        return when (result) {
            is BrokerDispatch.Completed ->
                NativeReadResponse.Completed(
                    result.presentation.success,
                    Json.parseToJsonElement(result.presentation.content.last().text),
                    nativePresentationEvidence(result.presentation),
                )
            is BrokerDispatch.Rejected ->
                NativeReadResponse.Rejected(
                    readBrokerFailure(result.failure),
                    when (val failure = result.failure) {
                        is BrokerFailure.OutputContractRejected -> failure.violationEvidence.toDocument()
                        else -> null
                    },
                    (result.failure as? BrokerFailure.SourceInputRejected)?.cause,
                )
        }
    }

    companion object {
        suspend fun open(product: Path, workspace: Path): NativeHostedReadTransport {
            val origin = Path.of(Broker::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
            demand(
                origin.startsWith(product.resolve("lib")) && Files.isRegularFile(origin),
                NativeFailure.PRODUCT_CLASS_ORIGIN_REJECTED,
            )
            val options =
                KastProviderOptions(
                    catalogSource =
                        io.github.amichne.kast.appserver.provider.PackagedKastCatalog(
                            NativeProductAdmission.executable(product, workspace)
                                .parent
                                .parent
                                .resolve("share/kast/provider-catalog.json")
                        )
                )
            val qualified =
                KastProviderQualifier.qualify(options).nativeQualified { observation ->
                    System.out.println(observation.encodeObservation())
                    System.out.flush()
                }
            return NativeHostedReadTransport(
                Broker.create(listOf(qualified.registration), BrokerLimits.defaults()).nativeValue(),
                workspace,
                qualified.registration.toolDocuments.associate { it.name.value to it.outputSchema },
            )
        }
    }
}

internal val nativeReadToolNames =
    CanonicalAgentToolDefinitions.all
        .filter { definition ->
            definition !== CanonicalAgentToolDefinitions.changePlan &&
                definition.operation.effect in setOf(OperationEffect.NONE, OperationEffect.INTELLIJ_READ)
        }
        .map { it.name.value }
        .toSet()

private fun readBrokerFailure(failure: BrokerFailure): String =
    when (failure) {
        is BrokerFailure.SourceInputRejected ->
            if (failure.cause is SourceReadFailureDetail.InternalContractFailure) "SOURCE_INTERNAL_CONTRACT_FAILURE"
            else "SOURCE_INPUT_REJECTED"
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
