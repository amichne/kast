package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.AgentSessionBootstrap
import io.github.amichne.kast.cli.broker.core.AgentSessionBootstrapQualification
import io.github.amichne.kast.cli.broker.core.BrokerTool
import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.cli.broker.core.HostedToolDefinition
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
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.AgentToolPolicy
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

/** Qualified tool authority retained from broker configuration through catalog construction. */
internal enum class KastToolExposure {
    READ_ONLY,
    MUTATION_ENABLED,
    ;

    internal fun admits(policy: HostedApprovalPolicy): Boolean = when (this) {
        READ_ONLY -> policy == HostedApprovalPolicy.NONE
        MUTATION_ENABLED -> true
    }

    companion object {
        /**
         * Refines the optional launch boundary into catalog authority. Absence retains the
         * least-privileged catalog; only the exact mutation-enabled value broadens it.
         */
        internal fun admit(raw: String?): Refinement<KastToolExposure, KastToolExposureFailure> =
            when (raw) {
                null, "read-only" -> Refinement.Refined(READ_ONLY)
                "mutation-enabled" -> Refinement.Refined(MUTATION_ENABLED)
                else -> Refinement.Rejected(KastToolExposureFailure.UNKNOWN_VALUE)
            }
    }
}

internal enum class KastToolExposureFailure { UNKNOWN_VALUE }

internal class KastProviderOptions private constructor(
    val executable: BrokerExecutable,
    val qualificationDirectory: CanonicalBrokerDirectory,
    val processExecutor: BrokerProcessExecutor,
    val qualificationTimeoutMillis: Long,
    val toolExposure: KastToolExposure,
) {
    companion object {
        internal fun admit(
            executable: Path,
            qualificationDirectory: Path,
            processExecutor: BrokerProcessExecutor = JdkBrokerProcessExecutor,
            qualificationTimeoutMillis: Long = OperationExecutionBudget.LOCAL_QUALIFICATION.value,
            toolExposure: KastToolExposure = KastToolExposure.READ_ONLY,
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
                    toolExposure,
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
        val bootstrap: AgentSessionBootstrap,
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
                val exposedTools = ExposedKastTools.select(options.toolExposure, contract.tools)
                val registration = buildRegistration(options, contract, exposedTools)
                when (registration) {
                    is Validation.Validated -> when (
                        val bootstrap = AgentSessionBootstrap.qualify(
                            definitions = exposedTools.values.map(QualifiedKastTool::hostedDefinition),
                            policy = contract.policy,
                            executableRoutes = registration.value.tools.mapTo(linkedSetOf()) {
                                tool -> tool.name
                            },
                        )
                    ) {
                        is AgentSessionBootstrapQualification.Qualified ->
                            KastProviderQualification.Qualified(
                                contract.evidence,
                                registration.value,
                                bootstrap.bootstrap,
                            )
                        is AgentSessionBootstrapQualification.Rejected ->
                            KastProviderQualification.Rejected(
                                KastQualificationFailure.SCHEMA_INCOMPATIBLE,
                            )
                    }
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
        val projection = admitProjection(capability.serverProjection)
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
            projection.policy,
            projection.tools,
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
        exposedTools: ExposedKastTools,
    ): Validation<ProviderRegistration<KastRuntime>, *> {
        return ProviderRegistration.define(
            namespace = staticNamespace(),
            version = staticVersion(
                "${contract.evidence.cliVersion.value}+server${contract.evidence.projectionVersion}",
            ),
            tools = exposedTools.values.map { tool -> tool.asBrokerTool() },
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
            invocationBudget = executionBudget.invocation,
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

    private fun admitProjection(
        projection: KastServerProjectionBoundary,
    ): QualifiedKastProjection? {
        if (projection.schemaVersion != 6 || projection.namespace != "kast") return null
        val bootstrap = projection.hostedBootstrap
        val cli = projection.cliInvocations
        if (bootstrap.schemaVersion != 1 || cli.schemaVersion != 2) return null
        val policy = refined(AgentToolPolicy.parse(bootstrap.policy)) ?: return null
        if (policy != CanonicalAgentToolDefinitions.policy) return null
        if (bootstrap.tools.isEmpty() || bootstrap.tools.size > 64) return null
        if (bootstrap.tools.map(KastHostedToolBoundary::name).hasDuplicates()) return null
        if (bootstrap.tools.map(KastHostedToolBoundary::operationId).hasDuplicates()) return null
        if (cli.operations.map(KastCliOperationInvocationBoundary::operationId).hasDuplicates()) return null
        val invocationsByOperation = cli.operations.associateBy { it.operationId }
        if (invocationsByOperation.keys != bootstrap.tools.mapTo(linkedSetOf()) { it.operationId }) {
            return null
        }
        return QualifiedKastProjection(
            policy,
            bootstrap.tools.map { tool ->
                admitTool(tool, invocationsByOperation.getValue(tool.operationId)) ?: return null
            },
        )
    }

    private fun admitTool(
        tool: KastHostedToolBoundary,
        cliInvocation: KastCliOperationInvocationBoundary,
    ): QualifiedKastTool? {
        val operation = KastOperationId.admit(tool.operationId) ?: return null
        val canonicalOperation = CanonicalOperation.entries.singleOrNull {
            it.id.value == operation.value
        } ?: return null
        val canonicalDefinition = CanonicalAgentToolDefinitions.all.singleOrNull {
            it.operation.operation == canonicalOperation
        } ?: return null
        val executionBudget = OperationExecutionBudget.forOperation(canonicalOperation)
        if (tool.executionBudget.readinessMillis != OperationExecutionBudget.WORKSPACE_READINESS.value ||
            tool.executionBudget.operationMillis != executionBudget.operation.value
        ) return null
        if (tool.name != canonicalDefinition.name.value ||
            tool.description != canonicalDefinition.description.value ||
            tool.effect != canonicalDefinition.operation.effect.name.lowercase()
        ) return null
        val approval = when (tool.approvalPolicy) {
            KastApprovalPolicy.NONE -> HostedApprovalPolicy.NONE
            KastApprovalPolicy.EXPLICIT -> HostedApprovalPolicy.EXPLICIT
        }
        if (approval != canonicalDefinition.approval) return null
        if (tool.deferLoading != (canonicalDefinition.loading == HostedToolLoading.DEFERRED)) return null
        val name = refined(ToolName.admit(tool.name)) ?: return null
        val description = refined(ToolDescription.admit(tool.description)) ?: return null
        if (cliInvocation.cliUsage.isBlank() || cliInvocation.cliUsage.length > 16_384) return null
        if (cliInvocation.invocation.command.isEmpty() || cliInvocation.invocation.command.size > 16) return null
        if (cliInvocation.invocation.command.any { token -> !token.isAdmittedCliToken() }) return null
        val inputDocument = tool.inputSchema as? JsonObject ?: return null
        val outputDocument = tool.outputSchema as? JsonObject ?: return null
        if (inputDocument["additionalProperties"] != JsonPrimitive(false)) return null
        val inputSchema = refined(NetworkntJsonSchemaCompiler.compile(inputDocument)) ?: return null
        val outputSchema = refined(NetworkntJsonSchemaCompiler.compile(outputDocument)) ?: return null
        return QualifiedKastTool(
            operation,
            executionBudget,
            name,
            description,
            tool.deferLoading,
            HostedToolDefinition(
                canonicalOperation,
                canonicalDefinition.name,
                canonicalDefinition.description,
                inputSchema,
                outputSchema,
                canonicalDefinition.operation.effect,
                canonicalDefinition.approval,
                executionBudget,
                canonicalDefinition.loading,
            ),
            inputSchema,
            outputSchema,
            cliInvocation.invocation.command,
        )
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
        val requestInput = when (val admission = BrokerProcessInput.Document.admit(input.arguments.toString())) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return ProviderCall.Rejected(
                ProviderFailureCode.UNEXPECTED_FAILURE,
            )
        }
        val request = when (
            val admission = BrokerProcessRequest.admit(
                options.executable,
                tool.command,
                context.workingDirectory,
                MAXIMUM_OUTPUT_BYTES,
                tool.executionBudget.invocation.value,
                input = requestInput,
            )
        ) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return ProviderCall.Rejected(
                ProviderFailureCode.UNEXPECTED_FAILURE,
            )
        }
        return outcome(options.processExecutor.execute(request), context)
    }

    private fun outcome(
        execution: BrokerProcessExecution,
        context: io.github.amichne.kast.cli.broker.core.BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> {
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
    }
}


private sealed interface KastContractQualification {
    data class Qualified(
        val evidence: KastQualificationEvidence,
        val policy: AgentToolPolicy,
        val tools: List<QualifiedKastTool>,
    ) : KastContractQualification

    data class Rejected(val failure: KastQualificationFailure) : KastContractQualification
}

private data class QualifiedKastProjection(
    val policy: AgentToolPolicy,
    val tools: List<QualifiedKastTool>,
)

/** Qualified tools narrowed to the authority granted for this broker lifetime. */
private class ExposedKastTools private constructor(
    val values: List<QualifiedKastTool>,
) {
    companion object {
        internal fun select(
            exposure: KastToolExposure,
            qualifiedTools: List<QualifiedKastTool>,
        ): ExposedKastTools = ExposedKastTools(
            qualifiedTools.filter { tool ->
                exposure.admits(tool.hostedDefinition.approval)
            },
        )
    }
}

internal data class QualifiedKastTool(
    val operation: KastOperationId,
    val executionBudget: OperationExecutionBudget,
    val name: ToolName,
    val description: ToolDescription,
    val deferLoading: Boolean,
    val hostedDefinition: HostedToolDefinition,
    val inputSchema: CompiledJsonSchema,
    val outputSchema: CompiledJsonSchema,
    val command: List<String>,
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
    val hostedBootstrap: KastHostedBootstrapBoundary,
    val cliInvocations: KastCliInvocationsBoundary,
)

@Serializable
private data class KastHostedBootstrapBoundary(
    val schemaVersion: Int,
    val policy: String,
    val tools: List<KastHostedToolBoundary>,
)

@Serializable
private data class KastHostedToolBoundary(
    val operationId: String,
    val name: String,
    val description: String,
    val deferLoading: Boolean,
    val effect: String,
    val approvalPolicy: KastApprovalPolicy,
    val executionBudget: KastExecutionBudgetBoundary,
    val inputSchema: JsonElement,
    val outputSchema: JsonElement,
)

@Serializable
private data class KastCliInvocationsBoundary(
    val schemaVersion: Int,
    val operations: List<KastCliOperationInvocationBoundary>,
)

@Serializable
private data class KastCliOperationInvocationBoundary(
    val operationId: String,
    val cliUsage: String,
    val invocation: KastCliInvocationBoundary,
)

@Serializable
private data class KastExecutionBudgetBoundary(
    val readinessMillis: Long,
    val operationMillis: Long,
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
)

@Serializable
private enum class KastInvocationType { CLI }
