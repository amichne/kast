package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.KastToolSelection
import io.github.amichne.kast.appserver.core.AgentSessionBootstrap
import io.github.amichne.kast.appserver.core.AgentSessionBootstrapQualification
import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.core.BrokerTool
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.core.HostedToolDefinition
import io.github.amichne.kast.appserver.core.ProviderCall
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ProviderRegistration
import io.github.amichne.kast.appserver.core.ProviderStartup
import io.github.amichne.kast.appserver.core.ProviderVersion
import io.github.amichne.kast.appserver.core.ToolDescription
import io.github.amichne.kast.appserver.core.ToolLoading
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.explanation
import io.github.amichne.kast.appserver.schema.CompiledJsonSchema
import io.github.amichne.kast.appserver.schema.JsonDomainDefinition
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.appserver.schema.ValidatedJsonValue
import io.github.amichne.kast.appserver.schema.canonicalJson
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.AgentToolPolicy
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class KastProviderOptionsFailure {
    EXECUTABLE_UNAVAILABLE,
    QUALIFICATION_DIRECTORY_REJECTED,
    QUALIFICATION_TIMEOUT_REJECTED,
}

internal class KastProviderOptions
private constructor(
    val executable: BrokerExecutable,
    val qualificationDirectory: CanonicalBrokerDirectory,
    val processExecutor: BrokerProcessExecutor,
    val qualificationTimeoutMillis: Long,
    val toolSelection: KastToolSelection,
    val readLimits: ReadLimits,
    val ideClient: io.github.amichne.kast.appserver.ide.ExistingIdeClient,
    val roots: io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer,
    val lifecycleClient: io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient,
) {
    companion object {
        internal fun admit(
            executable: Path,
            qualificationDirectory: Path,
            processExecutor: BrokerProcessExecutor = JdkBrokerProcessExecutor,
            qualificationTimeoutMillis: Long = OperationExecutionBudget.LOCAL_QUALIFICATION.value,
            toolSelection: KastToolSelection = KastToolSelection.defaults(),
            readLimits: ReadLimits = ReadLimits.Default,
            ideClient: io.github.amichne.kast.appserver.ide.ExistingIdeClient =
                io.github.amichne.kast.appserver.ide.ExistingIdeSocketClient(
                    Path.of(System.getProperty("user.home")),
                    readLimits,
                ),
            roots: io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer =
                io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery,
            lifecycleClient: io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient =
                io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient.Unavailable,
        ): Refinement<KastProviderOptions, KastProviderOptionsFailure> {
            val admittedExecutable =
                when (val admission = BrokerExecutable.admit(executable)) {
                    is Refinement.Refined -> admission.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(KastProviderOptionsFailure.EXECUTABLE_UNAVAILABLE)
                }
            val admittedDirectory =
                CanonicalBrokerDirectory.admit(qualificationDirectory)
                    ?: return Refinement.Rejected(KastProviderOptionsFailure.QUALIFICATION_DIRECTORY_REJECTED)
            if (qualificationTimeoutMillis !in 1..BrokerOperationalLimits.maximumKastQualification.value) {
                return Refinement.Rejected(KastProviderOptionsFailure.QUALIFICATION_TIMEOUT_REJECTED)
            }
            return Refinement.Refined(
                KastProviderOptions(
                    admittedExecutable,
                    admittedDirectory,
                    processExecutor,
                    qualificationTimeoutMillis,
                    toolSelection,
                    readLimits,
                    ideClient,
                    roots,
                    lifecycleClient,
                )
            )
        }
    }
}

internal enum class KastQualificationFailure {
    VERSION_UNAVAILABLE,
    VERSION_INVALID,
    SCHEMA_UNAVAILABLE,
    SCHEMA_SIZE_LIMIT,
    SCHEMA_INVALID,
    SCHEMA_INCOMPATIBLE,
}

internal sealed interface KastProviderQualification {
    data class Qualified(
        val evidence: KastQualificationEvidence,
        val registration: ProviderRegistration<KastRuntime>,
        val bootstrap: AgentSessionBootstrap,
    ) : KastProviderQualification

    data class Rejected(val failure: KastQualificationFailure) : KastProviderQualification
}

@JvmInline
internal value class KastCliVersion private constructor(val value: String) {
    companion object {
        internal fun admit(raw: String): KastCliVersion? =
            raw.trim()
                .takeIf { value ->
                    value.startsWith("kast ") &&
                        value.length <= 512 &&
                        value.none { character -> character == '\n' || character == '\r' || character == '\u0000' }
                }
                ?.let(::KastCliVersion)
    }
}

@JvmInline
internal value class KastContractDigest private constructor(val value: String) {
    companion object {
        internal fun derive(document: JsonObject): KastContractDigest =
            KastContractDigest(
                "sha256:" +
                    HexFormat.of()
                        .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                .digest(canonicalJson(document).toByteArray(StandardCharsets.UTF_8))
                        )
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
            is KastContractQualification.Rejected -> KastProviderQualification.Rejected(contract.failure)

            is KastContractQualification.Qualified -> {
                val exposedTools = ExposedKastTools.select(options.toolSelection, contract.tools)
                val registration = buildRegistration(options, contract, exposedTools)
                when (registration) {
                    is Validation.Validated ->
                        when (
                            val bootstrap =
                                AgentSessionBootstrap.qualify(
                                    definitions = exposedTools.values.map(QualifiedKastTool::hostedDefinition),
                                    policy = contract.policy,
                                    executableRoutes =
                                        registration.value.tools.mapTo(linkedSetOf()) { tool ->
                                            tool.name
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
                                KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE)
                        }
                    is Validation.Rejected ->
                        KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE)
                }
            }
        }

    private suspend fun qualifyContract(options: KastProviderOptions): KastContractQualification {
        val versionExecution =
            executeQualification(
                options,
                listOf("--version"),
                MAXIMUM_VERSION_BYTES,
            )
        val versionOutput =
            versionExecution as? BrokerProcessExecution.Completed
                ?: return KastContractQualification.Rejected(KastQualificationFailure.VERSION_UNAVAILABLE)
        if (versionOutput.exitCode != 0) {
            return KastContractQualification.Rejected(KastQualificationFailure.VERSION_UNAVAILABLE)
        }
        val cliVersion =
            KastCliVersion.admit(versionOutput.stdout)
                ?: return KastContractQualification.Rejected(KastQualificationFailure.VERSION_INVALID)

        val schemaExecution =
            executeQualification(
                options,
                listOf("--schema"),
                MAXIMUM_SCHEMA_BYTES,
            )
        val schemaOutput =
            when (schemaExecution) {
                is BrokerProcessExecution.Completed -> schemaExecution
                is BrokerProcessExecution.Rejected ->
                    return KastContractQualification.Rejected(
                        when (schemaExecution.failure) {
                            BrokerProcessFailure.OUTPUT_LIMIT -> KastQualificationFailure.SCHEMA_SIZE_LIMIT
                            BrokerProcessFailure.IO_REJECTED,
                            BrokerProcessFailure.SPAWN_FAILED,
                            BrokerProcessFailure.TERMINATED,
                            BrokerProcessFailure.TIMED_OUT -> KastQualificationFailure.SCHEMA_UNAVAILABLE
                        }
                    )
            }
        if (schemaOutput.exitCode != 0) {
            return KastContractQualification.Rejected(KastQualificationFailure.SCHEMA_UNAVAILABLE)
        }
        val rawDocument =
            try {
                Json.parseToJsonElement(schemaOutput.stdout) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return KastContractQualification.Rejected(KastQualificationFailure.SCHEMA_INVALID)
        val capability =
            try {
                boundaryJson.decodeFromJsonElement(KastCapabilityBoundary.serializer(), rawDocument)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return KastContractQualification.Rejected(KastQualificationFailure.SCHEMA_INVALID)
        val projection =
            admitProjection(capability.serverProjection)
                ?: return KastContractQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE)
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
        val request =
            refined(
                BrokerProcessRequest.admit(
                    options.executable,
                    arguments,
                    options.qualificationDirectory,
                    maximumOutputBytes,
                    options.qualificationTimeoutMillis,
                )
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
            version =
                staticVersion("${contract.evidence.cliVersion.value}+server${contract.evidence.projectionVersion}"),
            tools = exposedTools.values.map { tool -> tool.asBrokerTool() },
            start = {
                when (val current = qualifyContract(options)) {
                    is KastContractQualification.Rejected ->
                        ProviderStartup.Rejected(ProviderFailureCode.KAST_QUALIFICATION_FAILED)
                    is KastContractQualification.Qualified ->
                        if (
                            current.evidence.cliVersion == contract.evidence.cliVersion &&
                                current.evidence.contractDigest == contract.evidence.contractDigest
                        ) {
                            ProviderStartup.Started(KastRuntime(options))
                        } else {
                            ProviderStartup.Rejected(ProviderFailureCode.KAST_CONTRACT_CHANGED)
                        }
                }
            },
        )
    }

    private fun QualifiedKastTool.asBrokerTool():
        BrokerTool<
            KastRuntime,
            KastInvocationInput,
            KastInvocationOutput,
            KastToolInputFailure,
        > {
        val definition =
            JsonDomainDefinition(
                inputSchema,
                RefinementDefinition<ValidatedJsonValue, KastInvocationInput, KastToolInputFailure> { admitted ->
                    admitKastInput(hostedDefinition.operation, admitted, inputBinding)
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
            inputAliases = inputAliases,
            effect = BrokerOperationEffect.Canonical(hostedDefinition.effect),
            inputRejectionEvidence = { raw ->
                if (
                    hostedDefinition.operation ==
                        io.github.amichne.kast.protocol.contract.CanonicalOperation.SOURCE_READ
                )
                    sourceInputRejectionEvidence(raw)
                else io.github.amichne.kast.appserver.core.BrokerInputRejectionEvidence.Unspecified
            },
            inputGuidance = { failure ->
                val guidance =
                    when (failure) {
                        is io.github.amichne.kast.appserver.schema.JsonDomainAdmissionFailure.Constraint ->
                            if (inputBinding is AgentToolInputBinding.Facade)
                                listOf(
                                    io.github.amichne.kast.appserver.query.PublicToolInputFailure.SchemaRejected
                                        .explanation()
                                )
                            else emptyList()
                        is io.github.amichne.kast.appserver.schema.JsonDomainAdmissionFailure.Domain ->
                            when (val reason = failure.failure) {
                                is KastToolInputFailure.Source -> emptyList()
                                is KastToolInputFailure.Facade -> listOf(reason.reason.explanation())
                                KastToolInputFailure.NotObject,
                                KastToolInputFailure.SchemaMismatch,
                                is KastToolInputFailure.Query -> emptyList()
                            }
                    }
                guidance.map { message ->
                    when (val admitted = ToolDescription.admit(message)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> error("Static tool guidance exceeded its bound")
                    }
                }
            },
            present = { output -> presentKastSourceOrOutcome(output.document, output.success) },
        )
    }

    private fun admitProjection(projection: KastServerProjectionBoundary): QualifiedKastProjection? {
        if (projection.schemaVersion != KAST_SERVER_PROJECTION_VERSION || projection.namespace != "kast") return null
        val bootstrap = projection.hostedBootstrap
        val cli = projection.cliInvocations
        if (bootstrap.schemaVersion != 1 || cli.schemaVersion != 3) return null
        val policy = refined(AgentToolPolicy.parse(bootstrap.policy)) ?: return null
        if (policy != CanonicalAgentToolDefinitions.policy) return null
        if (bootstrap.tools.isEmpty() || bootstrap.tools.size > 64) return null
        if (bootstrap.tools.map(KastHostedToolBoundary::name).hasDuplicates()) return null
        if (cli.operations.map(KastCliOperationInvocationBoundary::toolName).hasDuplicates()) return null
        val invocationsByTool = cli.operations.associateBy { it.toolName }
        if (invocationsByTool.keys != bootstrap.tools.mapTo(linkedSetOf()) { it.name }) {
            return null
        }
        return QualifiedKastProjection(
            policy,
            bootstrap.tools.map { tool ->
                admitTool(tool, invocationsByTool.getValue(tool.name)) ?: return null
            },
        )
    }

    private fun admitTool(
        tool: KastHostedToolBoundary,
        cliInvocation: KastCliOperationInvocationBoundary,
    ): QualifiedKastTool? {
        val operation = KastOperationId.admit(tool.operationId) ?: return null
        val canonicalOperation =
            CanonicalOperation.entries.singleOrNull {
                it.id.value == operation.value
            } ?: return null
        val canonicalDefinition =
            CanonicalAgentToolDefinitions.all.singleOrNull {
                it.name.value == tool.name && it.operation.operation == canonicalOperation
            } ?: return null
        if (cliInvocation.toolName != tool.name || cliInvocation.operationId != tool.operationId) return null
        val inputBinding = canonicalDefinition.inputBinding
        if (
            inputBinding is AgentToolInputBinding.Facade &&
                cliInvocation.invocation.command != listOf("tool", inputBinding.identity.toolName)
        )
            return null
        val executionBudget = OperationExecutionBudget.forOperation(canonicalOperation)
        if (
            tool.executionBudget.readinessMillis != OperationExecutionBudget.WORKSPACE_READINESS.value ||
                tool.executionBudget.operationMillis != executionBudget.operation.value
        )
            return null
        if (
            tool.name != canonicalDefinition.name.value ||
                tool.description != canonicalDefinition.description.value ||
                tool.effect != canonicalDefinition.operation.effect.name.lowercase()
        )
            return null
        val approval =
            when (tool.approvalPolicy) {
                KastApprovalPolicy.NONE -> HostedApprovalPolicy.NONE
                KastApprovalPolicy.EXPLICIT -> HostedApprovalPolicy.EXPLICIT
                KastApprovalPolicy.EXACT_PROJECT_CLOSE -> HostedApprovalPolicy.EXACT_PROJECT_CLOSE
            }
        if (approval != canonicalDefinition.approval) return null
        if (tool.deferLoading != (canonicalDefinition.loading == HostedToolLoading.DEFERRED)) return null
        val name = refined(ToolName.admit(tool.name)) ?: return null
        val aliases =
            canonicalDefinition.inputAliases.mapTo(linkedSetOf()) { alias ->
                refined(ToolName.admit(alias.value)) ?: return null
            }
        val description = refined(ToolDescription.admit(tool.description)) ?: return null
        if (cliInvocation.cliUsage.isBlank() || cliInvocation.cliUsage.length > 16_384) return null
        if (cliInvocation.invocation.command.isEmpty() || cliInvocation.invocation.command.size > 16) return null
        if (cliInvocation.invocation.command.any { token -> !token.isAdmittedCliToken() }) return null
        val inputDocument = tool.inputSchema as? JsonObject ?: return null
        val outputDocument = tool.outputSchema as? JsonObject ?: return null
        when (val input = canonicalDefinition.inputBinding) {
            is AgentToolInputBinding.Facade ->
                if (inputDocument != PublicToolContract.parameters(input.identity)) return null
            AgentToolInputBinding.Canonical -> Unit
        }
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
                when (inputBinding) {
                    is AgentToolInputBinding.Facade -> PublicToolContract.generationParameters(inputBinding.identity)
                    AgentToolInputBinding.Canonical -> inputDocument
                },
            ),
            inputSchema,
            outputSchema,
            cliInvocation.invocation.command,
            canonicalDefinition.inputBinding,
            aliases,
        )
    }

    private fun String.isAdmittedCliToken(): Boolean =
        isNotBlank() &&
            length <= 4_096 &&
            none { character ->
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

    private const val MAXIMUM_VERSION_BYTES = BrokerOperationalLimits.maximumKastVersionBytes
    private const val MAXIMUM_SCHEMA_BYTES = BrokerOperationalLimits.maximumKastSchemaBytes
    private val boundaryJson = Json { ignoreUnknownKeys = true }
}

internal class KastRuntime(options: KastProviderOptions) {
    private val direct = KastDirectInvocation(options)

    internal suspend fun invoke(
        tool: QualifiedKastTool,
        input: KastInvocationInput,
        context: io.github.amichne.kast.appserver.core.BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> = direct.invoke(tool, input, context)
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
private class ExposedKastTools private constructor(val values: List<QualifiedKastTool>) {
    companion object {
        internal fun select(
            selection: KastToolSelection,
            qualifiedTools: List<QualifiedKastTool>,
        ): ExposedKastTools =
            ExposedKastTools(
                qualifiedTools.filter { tool ->
                    val definition =
                        CanonicalAgentToolDefinitions.all.single { definition ->
                            definition.name == tool.hostedDefinition.name
                        }
                    selection.admits(definition)
                }
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
    val inputBinding: AgentToolInputBinding = AgentToolInputBinding.Canonical,
    val inputAliases: Set<ToolName> = emptySet(),
)

@JvmInline
internal value class KastOperationId private constructor(val value: String) {
    companion object {
        internal fun admit(raw: String): KastOperationId? =
            raw.takeIf { value -> OPERATION_ID.matches(value) }?.let(::KastOperationId)

        private val OPERATION_ID = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+")
    }
}

internal data class KastInvocationOutput(
    val document: JsonObject,
    val success: Boolean,
    val observerDirectory: CanonicalBrokerDirectory,
)

internal val invocationJson = Json { encodeDefaults = true }

/** The payload is opaque here; the owning operation output schema admits it before presentation. */
@Serializable internal data class KastCompletedDocument(val document: JsonElement, val status: String = "completed")

/** Diagnostic payloads are admitted by the installed rejection schema before presentation. */
@Serializable internal data class KastRejectedDocument(val diagnostic: JsonElement, val status: String = "rejected")

private const val KAST_SERVER_PROJECTION_VERSION = 13
