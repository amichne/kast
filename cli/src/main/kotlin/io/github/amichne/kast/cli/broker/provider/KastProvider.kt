package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.BrokerTool
import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.cli.broker.core.ProviderCall
import io.github.amichne.kast.cli.broker.core.ProviderFailureCode
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ProviderRegistration
import io.github.amichne.kast.cli.broker.core.ProviderStartup
import io.github.amichne.kast.cli.broker.core.ProviderVersion
import io.github.amichne.kast.cli.broker.core.ToolDescription
import io.github.amichne.kast.cli.broker.core.ToolLoading
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.cli.broker.core.ToolPresentation
import io.github.amichne.kast.cli.broker.schema.CompiledJsonSchema
import io.github.amichne.kast.cli.broker.schema.JsonDomainDefinition
import io.github.amichne.kast.cli.broker.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.cli.broker.schema.ValidatedJsonValue
import io.github.amichne.kast.cli.broker.schema.canonicalJson
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal enum class KastProviderOptionsFailure {
    EXECUTABLE_UNAVAILABLE,
    QUALIFICATION_DIRECTORY_REJECTED,
    QUALIFICATION_TIMEOUT_REJECTED,
}

internal class KastProviderOptions private constructor(
    val executable: BrokerExecutable,
    val qualificationDirectory: CanonicalBrokerDirectory,
    val processExecutor: BrokerProcessExecutor,
    val qualificationTimeoutMillis: Long,
) {
    companion object {
        internal fun admit(
            executable: Path,
            qualificationDirectory: Path,
            processExecutor: BrokerProcessExecutor = JdkBrokerProcessExecutor,
            qualificationTimeoutMillis: Long = 10_000L,
        ): Refinement<KastProviderOptions, KastProviderOptionsFailure> {
            val admittedExecutable = when (val admission = BrokerExecutable.admit(executable)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    KastProviderOptionsFailure.EXECUTABLE_UNAVAILABLE,
                )
            }
            val admittedDirectory = CanonicalBrokerDirectory.admit(qualificationDirectory)
                ?: return Refinement.Rejected(
                    KastProviderOptionsFailure.QUALIFICATION_DIRECTORY_REJECTED,
                )
            if (qualificationTimeoutMillis !in 1..300_000L) {
                return Refinement.Rejected(
                    KastProviderOptionsFailure.QUALIFICATION_TIMEOUT_REJECTED,
                )
            }
            return Refinement.Refined(
                KastProviderOptions(
                    admittedExecutable,
                    admittedDirectory,
                    processExecutor,
                    qualificationTimeoutMillis,
                ),
            )
        }
    }
}

internal enum class KastQualificationFailure {
    VERSION_UNAVAILABLE,
    VERSION_INVALID,
    SCHEMA_UNAVAILABLE,
    SCHEMA_INVALID,
    SCHEMA_INCOMPATIBLE,
}

internal sealed interface KastProviderQualification {
    data class Qualified(
        val evidence: KastQualificationEvidence,
        val registration: ProviderRegistration<KastRuntime>,
    ) : KastProviderQualification

    data class Rejected(
        val failure: KastQualificationFailure,
    ) : KastProviderQualification
}

@JvmInline
internal value class KastCliVersion private constructor(val value: String) {
    companion object {
        internal fun admit(raw: String): KastCliVersion? = raw.trim().takeIf { value ->
            value.startsWith("kast ") && value.length <= 512 &&
                value.none { character -> character == '\n' || character == '\r' || character == '\u0000' }
        }?.let(::KastCliVersion)
    }
}

@JvmInline
internal value class KastContractDigest private constructor(val value: String) {
    companion object {
        internal fun derive(document: JsonObject): KastContractDigest = KastContractDigest(
            "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonicalJson(document).toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )
    }
}

internal data class KastQualificationEvidence(
    val cliVersion: KastCliVersion,
    val contractDigest: KastContractDigest,
    val schemaVersion: Int,
    val projectionVersion: Int,
)

internal object KastProviderQualifier {
    internal suspend fun qualify(options: KastProviderOptions): KastProviderQualification =
        when (val contract = qualifyContract(options)) {
            is KastContractQualification.Rejected ->
                KastProviderQualification.Rejected(contract.failure)

            is KastContractQualification.Qualified -> {
                val registration = buildRegistration(options, contract)
                when (registration) {
                    is Validation.Validated -> KastProviderQualification.Qualified(
                        contract.evidence,
                        registration.value,
                    )
                    is Validation.Rejected -> KastProviderQualification.Rejected(
                        KastQualificationFailure.SCHEMA_INCOMPATIBLE,
                    )
                }
            }
        }

    private suspend fun qualifyContract(options: KastProviderOptions): KastContractQualification {
        val versionExecution = executeQualification(
            options,
            listOf("--version"),
            MAXIMUM_VERSION_BYTES,
        )
        val versionOutput = versionExecution as? BrokerProcessExecution.Completed
            ?: return KastContractQualification.Rejected(KastQualificationFailure.VERSION_UNAVAILABLE)
        if (versionOutput.exitCode != 0) {
            return KastContractQualification.Rejected(KastQualificationFailure.VERSION_UNAVAILABLE)
        }
        val cliVersion = KastCliVersion.admit(versionOutput.stdout)
            ?: return KastContractQualification.Rejected(KastQualificationFailure.VERSION_INVALID)

        val schemaExecution = executeQualification(
            options,
            listOf("--schema"),
            MAXIMUM_SCHEMA_BYTES,
        )
        val schemaOutput = schemaExecution as? BrokerProcessExecution.Completed
            ?: return KastContractQualification.Rejected(KastQualificationFailure.SCHEMA_UNAVAILABLE)
        if (schemaOutput.exitCode != 0) {
            return KastContractQualification.Rejected(KastQualificationFailure.SCHEMA_UNAVAILABLE)
        }
        val rawDocument = try {
            Json.parseToJsonElement(schemaOutput.stdout) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return KastContractQualification.Rejected(KastQualificationFailure.SCHEMA_INVALID)
        val capability = try {
            boundaryJson.decodeFromJsonElement(KastCapabilityBoundary.serializer(), rawDocument)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return KastContractQualification.Rejected(KastQualificationFailure.SCHEMA_INVALID)
        val tools = admitProjection(capability.serverProjection)
            ?: return KastContractQualification.Rejected(
                KastQualificationFailure.SCHEMA_INCOMPATIBLE,
            )
        return KastContractQualification.Qualified(
            KastQualificationEvidence(
                cliVersion,
                KastContractDigest.derive(rawDocument),
                capability.schemaVersion,
                capability.serverProjection.schemaVersion,
            ),
            tools,
        )
    }

    private suspend fun executeQualification(
        options: KastProviderOptions,
        arguments: List<String>,
        maximumOutputBytes: Int,
    ): BrokerProcessExecution {
        val request = refined(
            BrokerProcessRequest.admit(
                options.executable,
                arguments,
                options.qualificationDirectory,
                maximumOutputBytes,
                options.qualificationTimeoutMillis,
            ),
        ) ?: return BrokerProcessExecution.Rejected(BrokerProcessFailure.TERMINATED)
        return try {
            withTimeout(options.qualificationTimeoutMillis) {
                options.processExecutor.execute(request)
            }
        } catch (_: TimeoutCancellationException) {
            BrokerProcessExecution.Rejected(BrokerProcessFailure.TIMED_OUT)
        }
    }

    private fun buildRegistration(
        options: KastProviderOptions,
        contract: KastContractQualification.Qualified,
    ): Validation<ProviderRegistration<KastRuntime>, *> {
        val tools = contract.tools.filter { tool -> tool.approvalPolicy == KastApprovalPolicy.NONE }
            .map { tool -> tool.asBrokerTool() }
        return ProviderRegistration.define(
            namespace = staticNamespace(),
            version = staticVersion(
                "${contract.evidence.cliVersion.value}+server${contract.evidence.projectionVersion}",
            ),
            tools = tools,
            start = {
                when (val current = qualifyContract(options)) {
                    is KastContractQualification.Rejected -> ProviderStartup.Rejected(
                        ProviderFailureCode.KAST_QUALIFICATION_FAILED,
                    )
                    is KastContractQualification.Qualified -> if (
                        current.evidence.cliVersion == contract.evidence.cliVersion &&
                        current.evidence.contractDigest == contract.evidence.contractDigest
                    ) {
                        ProviderStartup.Started(KastRuntime(options))
                    } else {
                        ProviderStartup.Rejected(
                            ProviderFailureCode.KAST_CONTRACT_CHANGED,
                        )
                    }
                }
            },
        )
    }

    private fun QualifiedKastTool.asBrokerTool(): BrokerTool<
        KastRuntime,
        KastInvocationInput,
        KastInvocationOutput,
        KastToolInputFailure
    > {
        val definition = JsonDomainDefinition(
            inputSchema,
            RefinementDefinition<ValidatedJsonValue, KastInvocationInput, KastToolInputFailure> {
                admitted ->
                val arguments = admitted.element as? JsonObject
                if (arguments == null) Validation.rejected(KastToolInputFailure.NOT_OBJECT)
                else Validation.validated(KastInvocationInput(arguments))
            },
        )
        return BrokerTool(
            name,
            description,
            if (deferLoading) ToolLoading.DEFERRED else ToolLoading.EAGER,
            definition,
            outputSchema,
            invoke = { runtime, input, context -> runtime.invoke(this, input, context) },
            encode = KastInvocationOutput::document,
            present = { output ->
                val observerPresentation = try {
                    KastObserverProjector.project(operation, output)
                } catch (_: RuntimeException) {
                    io.github.amichne.kast.cli.broker.core.ObserverPresentation.None
                }
                ToolPresentation.text(
                    canonicalJson(output.document),
                    success = output.success,
                    observer = observerPresentation,
                )
            },
        )
    }

    private fun admitProjection(projection: KastServerProjectionBoundary): List<QualifiedKastTool>? {
        if (projection.schemaVersion !in setOf(1, 2) || projection.namespace != "kast") return null
        if (projection.tools.isEmpty() || projection.tools.size > 64) return null
        if (projection.tools.map(KastServerToolBoundary::name).hasDuplicates()) return null
        if (projection.tools.map(KastServerToolBoundary::operationId).hasDuplicates()) return null
        return projection.tools.map { tool -> admitTool(projection.schemaVersion, tool) ?: return null }
    }

    private fun admitTool(
        projectionVersion: Int,
        tool: KastServerToolBoundary,
    ): QualifiedKastTool? {
        val operation = KastOperationId.admit(tool.operationId) ?: return null
        val name = refined(ToolName.admit(tool.name)) ?: return null
        val description = refined(ToolDescription.admit(tool.description)) ?: return null
        if (tool.cliUsage.isBlank() || tool.cliUsage.length > 16_384) return null
        val approval = when (projectionVersion) {
            1 -> tool.approvalPolicy ?: KastApprovalPolicy.NONE
            2 -> tool.approvalPolicy ?: return null
            else -> return null
        }
        if (tool.invocation.command.isEmpty() || tool.invocation.command.size > 16) return null
        if (tool.invocation.command.any { token -> !token.isAdmittedCliToken() }) return null
        if (tool.invocation.bindings.size > 64) return null
        if (tool.invocation.bindings.any { binding ->
                !INPUT_FIELD.matches(binding.inputField) || !CLI_OPTION.matches(binding.option)
            }
        ) return null
        if (tool.invocation.bindings.map(KastCliBindingBoundary::inputField).hasDuplicates()) return null
        if (tool.invocation.bindings.map(KastCliBindingBoundary::option).hasDuplicates()) return null
        val inputDocument = tool.inputSchema as? JsonObject ?: return null
        val outputDocument = tool.outputSchema as? JsonObject ?: return null
        val inputProperties = bindableInputProperties(inputDocument) ?: return null
        if (inputProperties.keys != tool.invocation.bindings.map { it.inputField }.toSet()) return null
        if (tool.invocation.bindings.any { binding ->
                inputProperties.getValue(binding.inputField).any { schema ->
                    !binding.type.accepts(schema)
                }
            }
        ) return null
        val inputSchema = refined(NetworkntJsonSchemaCompiler.compile(inputDocument)) ?: return null
        val outputSchema = refined(NetworkntJsonSchemaCompiler.compile(outputDocument)) ?: return null
        return QualifiedKastTool(
            operation,
            name,
            description,
            tool.deferLoading,
            approval,
            inputSchema,
            outputSchema,
            tool.invocation.command,
            tool.invocation.bindings.map { binding ->
                QualifiedKastBinding(binding.type, binding.inputField, binding.option)
            },
        )
    }

    private fun bindableInputProperties(schema: JsonObject): Map<String, List<JsonElement>>? {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
        val properties = schema["properties"] as? JsonObject
        if (type == "object" && properties != null) {
            if (schema["additionalProperties"] != JsonPrimitive(false)) return null
            return properties.mapValues { (_, property) -> listOf(property) }
        }
        val variants = schema["anyOf"] as? JsonArray ?: return null
        if (variants.isEmpty()) return null
        return buildMap<String, MutableList<JsonElement>> {
            variants.forEach { variant ->
                val fields = bindableInputProperties(variant as? JsonObject ?: return null)
                    ?: return null
                fields.forEach { (name, schemas) ->
                    getOrPut(name, ::mutableListOf).addAll(schemas)
                }
            }
        }
    }

    private fun isScalarSchema(schema: JsonElement): Boolean {
        val document = schema as? JsonObject ?: return false
        val type = document["type"]?.jsonPrimitive?.contentOrNull
        if (type in setOf("string", "integer", "number", "boolean")) return true
        val variants = document["anyOf"] as? JsonArray ?: return false
        return variants.isNotEmpty() && variants.all(::isScalarSchema)
    }

    private fun KastBindingType.accepts(schema: JsonElement): Boolean = when (this) {
        KastBindingType.OPTION -> isScalarSchema(schema)
        KastBindingType.REPEATED_OPTION -> {
            val document = schema as? JsonObject
            document != null &&
                document["type"]?.jsonPrimitive?.contentOrNull == "array" &&
                document["items"]?.let(::isScalarSchema) == true
        }
        KastBindingType.FLAG -> {
            val document = schema as? JsonObject
            document != null && document["type"]?.jsonPrimitive?.contentOrNull == "boolean"
        }
    }

    private fun String.isAdmittedCliToken(): Boolean =
        isNotBlank() && length <= 4_096 && none { character ->
            character == '\n' || character == '\r' || character == '\u0000'
        }

    private fun <Value> List<Value>.hasDuplicates(): Boolean = toSet().size != size

    private fun staticNamespace(): ProviderNamespace =
        refined(ProviderNamespace.admit("kast")) ?: error("Static namespace is invalid")

    private fun staticVersion(raw: String): ProviderVersion =
        refined(ProviderVersion.admit(raw)) ?: error("Qualified version is invalid")

    private fun <Strong, Failure> refined(refinement: Refinement<Strong, Failure>): Strong? =
        when (refinement) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> null
        }

    private const val MAXIMUM_VERSION_BYTES = 4 * 1_024
    private const val MAXIMUM_SCHEMA_BYTES = 512 * 1_024
    private val INPUT_FIELD = Regex("[a-z][A-Za-z0-9]{0,63}")
    private val CLI_OPTION = Regex("--[a-z][a-z0-9-]{0,125}")
    private val boundaryJson = Json { ignoreUnknownKeys = true }
}

internal class KastRuntime(
    private val options: KastProviderOptions,
) {
    internal suspend fun invoke(
        tool: QualifiedKastTool,
        input: KastInvocationInput,
        context: io.github.amichne.kast.cli.broker.core.BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> {
        val arguments = buildList {
            addAll(tool.command)
            tool.bindings.forEach { binding ->
                val value = input.arguments[binding.inputField] ?: return@forEach
                when (binding.type) {
                    KastBindingType.OPTION -> {
                        val primitive = value as? JsonPrimitive
                            ?: return ProviderCall.Rejected(
                                ProviderFailureCode.KAST_ARGUMENT_NOT_SCALAR,
                            )
                        add(binding.option)
                        add(primitive.content)
                    }
                    KastBindingType.REPEATED_OPTION -> {
                        val values = value as? JsonArray
                            ?: return ProviderCall.Rejected(
                                ProviderFailureCode.KAST_ARGUMENT_NOT_SCALAR,
                            )
                        values.forEach { element ->
                            val primitive = element as? JsonPrimitive
                                ?: return ProviderCall.Rejected(
                                    ProviderFailureCode.KAST_ARGUMENT_NOT_SCALAR,
                                )
                            add(binding.option)
                            add(primitive.content)
                        }
                    }
                    KastBindingType.FLAG -> {
                        val enabled = (value as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
                            ?: return ProviderCall.Rejected(
                                ProviderFailureCode.KAST_ARGUMENT_NOT_SCALAR,
                            )
                        if (enabled) add(binding.option)
                    }
                }
            }
        }
        val request = when (
            val admission = BrokerProcessRequest.admit(
                options.executable,
                arguments,
                context.workingDirectory,
                MAXIMUM_OUTPUT_BYTES,
                INVOCATION_TIMEOUT_MILLIS,
            )
        ) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return ProviderCall.Rejected(
                ProviderFailureCode.UNEXPECTED_FAILURE,
            )
        }
        val execution = options.processExecutor.execute(request)
        val completed = execution as? BrokerProcessExecution.Completed
            ?: return ProviderCall.Rejected(
                (execution as BrokerProcessExecution.Rejected).failure.providerFailureCode(),
            )
        val raw = if (completed.exitCode == 0) completed.stdout else completed.stderr
        val payload = try {
            Json.parseToJsonElement(raw)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return ProviderCall.Rejected(
            ProviderFailureCode.MALFORMED_KAST_OUTPUT,
        )
        val document = if (completed.exitCode == 0) {
            buildJsonObject {
                put("status", "completed")
                put("document", payload)
            }
        } else {
            buildJsonObject {
                put("status", "rejected")
                put("diagnostic", payload)
            }
        }
        return ProviderCall.Completed(
            KastInvocationOutput(
                document,
                success = completed.exitCode == 0,
                observerDirectory = context.workingDirectory,
            ),
        )
    }

    private companion object {
        const val MAXIMUM_OUTPUT_BYTES = 512 * 1_024
        const val INVOCATION_TIMEOUT_MILLIS = 30_000L
    }
}


private sealed interface KastContractQualification {
    data class Qualified(
        val evidence: KastQualificationEvidence,
        val tools: List<QualifiedKastTool>,
    ) : KastContractQualification

    data class Rejected(val failure: KastQualificationFailure) : KastContractQualification
}

internal data class QualifiedKastTool(
    val operation: KastOperationId,
    val name: ToolName,
    val description: ToolDescription,
    val deferLoading: Boolean,
    val approvalPolicy: KastApprovalPolicy,
    val inputSchema: CompiledJsonSchema,
    val outputSchema: CompiledJsonSchema,
    val command: List<String>,
    val bindings: List<QualifiedKastBinding>,
)

@JvmInline
internal value class KastOperationId private constructor(val value: String) {
    companion object {
        internal fun admit(raw: String): KastOperationId? = raw
            .takeIf { value -> OPERATION_ID.matches(value) }
            ?.let(::KastOperationId)

        private val OPERATION_ID = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+")
    }
}

internal data class QualifiedKastBinding(
    val type: KastBindingType,
    val inputField: String,
    val option: String,
)

internal data class KastInvocationInput(val arguments: JsonObject)
internal data class KastInvocationOutput(
    val document: JsonObject,
    val success: Boolean,
    val observerDirectory: CanonicalBrokerDirectory,
)
internal enum class KastToolInputFailure { NOT_OBJECT }

@Serializable
private data class KastCapabilityBoundary(
    val schemaVersion: Int,
    val serverProjection: KastServerProjectionBoundary,
)

@Serializable
private data class KastServerProjectionBoundary(
    val schemaVersion: Int,
    val namespace: String,
    val tools: List<KastServerToolBoundary>,
)

@Serializable
private data class KastServerToolBoundary(
    val operationId: String,
    val name: String,
    val description: String,
    val deferLoading: Boolean,
    val approvalPolicy: KastApprovalPolicy? = null,
    val cliUsage: String,
    val inputSchema: JsonElement,
    val outputSchema: JsonElement,
    val invocation: KastCliInvocationBoundary,
)

@Serializable
internal enum class KastApprovalPolicy {
    @SerialName("none") NONE,
    @SerialName("explicit") EXPLICIT,
}

@Serializable
private data class KastCliInvocationBoundary(
    val type: KastInvocationType,
    val command: List<String>,
    val bindings: List<KastCliBindingBoundary>,
)

@Serializable
private enum class KastInvocationType { CLI }

@Serializable
private data class KastCliBindingBoundary(
    val type: KastBindingType,
    val inputField: String,
    val option: String,
)

@Serializable
internal enum class KastBindingType { OPTION, REPEATED_OPTION, FLAG }
