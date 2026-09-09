package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.*
import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Path

interface WorkerControlClient {
    suspend fun demand(root: Path, heap: IndexerHeapSize, startup: InstalledWorkerStartup): InstalledWorkerStart
    suspend fun retired(root: Path): InstalledWorkerStop
}

/** A runtime control connection never initializes an upstream Codex protocol session. */
class InstalledWorkerClient(
    private val kast: Path,
    private val userHome: Path,
    private val environment: Map<String, String> = System.getenv(),
    private val seedConsent: WorkerSeedConsentAuthority = WorkerSeedConsentAuthority.Unavailable,
) : WorkerControlClient {
    override suspend fun demand(root: Path, heap: IndexerHeapSize, startup: InstalledWorkerStartup): InstalledWorkerStart =
        exchange(WorkerControlDocument.demand(root, heap, startup), startService = true, ::rejected) { raw ->
            when (val result = WorkerControlReply.decode(raw)) {
                is InstalledWorkerStart.Rejected -> Refinement.Rejected(result.failure)
                is InstalledWorkerStart.Ready -> when (val binding = result.binding) {
                    is WorkerRouteBinding.Installation -> Refinement.Refined(Decoded(result, binding))
                    WorkerRouteBinding.EffectBoundary -> Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
                }
            }
        }

    /** Notification must never start the coordinator after installation retirement has begun. */
    override suspend fun retired(root: Path): InstalledWorkerStop = exchange(WorkerControlDocument.retired(root), startService = false, InstalledWorkerStop::Rejected) { raw ->
        when (val result = WorkerStopReply.decode(raw)) {
            is InstalledWorkerStop.Rejected -> Refinement.Rejected(result.failure)
            is InstalledWorkerStop.Stopped -> if (result.root == root) Refinement.Refined(Decoded(result, result.binding))
                else Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
        }
    }

    /** Passive observation uses the runtime route and never ensures a service or host frontend. */
    internal suspend fun status(command: BrokerServiceLaunchCommand): CoordinatorStatusRead {
        val publication = when (val read = published(command, requireDesired = false)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return CoordinatorStatusRead.Rejected(read.failure)
        }
        return withTimeoutOrNull(OperationExecutionBudget.LOCAL_QUALIFICATION.value) {
            val connection = when (val connected = connectCodexUnixWebSocket(command.publicSocket, CoordinatorStatusProtocol.maximumMessageBytes,
                OperationExecutionBudget.LOCAL_QUALIFICATION.value, BrokerControlRoute.RUNTIME)) {
                is BrokerUpstreamConnectionAdmission.Connected -> connected.connection
                BrokerUpstreamConnectionAdmission.Rejected -> return@withTimeoutOrNull CoordinatorStatusRead.Rejected(WorkerControlFailure.UNAVAILABLE)
            }
            try {
                if (connection.send(Json.encodeToString(WorkerControlDocument(WorkerControlAction.STATUS, ""))) != BrokerUpstreamSend.SENT)
                    return@withTimeoutOrNull CoordinatorStatusRead.Rejected(WorkerControlFailure.UNAVAILABLE)
                val document = when (val frame = connection.receive()) {
                    is BrokerUpstreamFrame.Text -> try { Json.parseToJsonElement(frame.message).jsonObject } catch (_: Exception) {
                        return@withTimeoutOrNull CoordinatorStatusRead.Rejected(WorkerControlFailure.INVALID_REQUEST)
                    }
                    else -> return@withTimeoutOrNull CoordinatorStatusRead.Rejected(WorkerControlFailure.UNAVAILABLE)
                }
                if (document["status"] != JsonPrimitive("READY") || document["serviceGeneration"] != JsonPrimitive(publication.generation) ||
                    document["installationId"] != JsonPrimitive(publication.installation) || document["stateEpoch"] != JsonPrimitive(publication.epoch))
                    return@withTimeoutOrNull CoordinatorStatusRead.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
                when (val admitted = CoordinatorStatusSnapshot.admit(document)) {
                    is Refinement.Refined -> CoordinatorStatusRead.Observed(admitted.value)
                    is Refinement.Rejected -> CoordinatorStatusRead.Rejected(admitted.failure)
                }
            } finally { withContext(NonCancellable) { connection.close() } }
        } ?: CoordinatorStatusRead.Rejected(WorkerControlFailure.DEADLINE_EXCEEDED)
    }

    private suspend fun <T> exchange(request: WorkerControlDocument, startService: Boolean, reject: (WorkerControlFailure) -> T, decode: (String) -> Refinement<Decoded<T>,WorkerControlFailure>): T {
        val command = when (val resolved = BrokerServiceLaunchCommand.resolveCoordinator(kast, userHome, environment)) {
            is BrokerServiceLaunchCommandResolution.Resolved -> resolved.command
            is BrokerServiceLaunchCommandResolution.Rejected -> return reject(WorkerControlFailure.UNAVAILABLE)
        }
        if (startService && withContext(Dispatchers.IO) { InstalledPersistentBrokerService(kast, userHome, environment).ensure() } !is PersistentBrokerServiceAdmission.Ready) {
            return reject(WorkerControlFailure.UNAVAILABLE)
        }
        val selectedRequest = if (request.action == WorkerControlAction.DEMAND) {
            val selected = when (val admitted = InstalledWorkspaceConfigurationIngress.resolve(kast.toRealPath().parent.parent, Path.of(request.root), environment)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return reject(WorkerControlFailure.IDENTITY_REJECTED)
            }
            request.copy(heap = "${selected.indexerHeap.mebibytes}m", configurationIdentity = workerConfigurationIdentity(selected))
        } else request
        val published = when (val observed = published(command, requireDesired = startService)) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected -> return reject(observed.failure)
        }
        val connection = when (val connected = connectCodexUnixWebSocket(command.publicSocket, CoordinatorStatusProtocol.maximumMessageBytes, BrokerOperationalLimits.clientConnect.value, BrokerControlRoute.RUNTIME)) {
            is BrokerUpstreamConnectionAdmission.Connected -> connected.connection
            BrokerUpstreamConnectionAdmission.Rejected -> return reject(WorkerControlFailure.UNAVAILABLE)
        }
        return try {
            withTimeoutOrNull(OperationExecutionBudget.WORKSPACE_READINESS.value) {
                if (connection.send(Json.encodeToString(WorkerControlDocument(WorkerControlAction.STATUS, ""))) != BrokerUpstreamSend.SENT) return@withTimeoutOrNull reject(WorkerControlFailure.UNAVAILABLE)
                val status = when (val frame = connection.receive()) {
                    is BrokerUpstreamFrame.Text -> try { Json.parseToJsonElement(frame.message).jsonObject } catch (_: Exception) { return@withTimeoutOrNull reject(WorkerControlFailure.IDENTITY_REJECTED) }
                    else -> return@withTimeoutOrNull reject(WorkerControlFailure.UNAVAILABLE)
                }
                if (status["status"] != JsonPrimitive("READY") || status["serviceGeneration"] != JsonPrimitive(published.generation) ||
                    status["installationId"] != JsonPrimitive(published.installation) || status["stateEpoch"] != JsonPrimitive(published.epoch) ||
                    (startService && status["configurationIdentity"] != JsonPrimitive(published.configuration))) return@withTimeoutOrNull reject(WorkerControlFailure.IDENTITY_REJECTED)
                if (connection.send(Json.encodeToString(selectedRequest)) != BrokerUpstreamSend.SENT) return@withTimeoutOrNull reject(WorkerControlFailure.UNAVAILABLE)
                receiveResult(connection,selectedRequest,published,reject,decode)
            } ?: reject(WorkerControlFailure.DEADLINE_EXCEEDED)
        } finally { withContext(NonCancellable) { connection.close() } }
    }
    private suspend fun <T> receiveResult(connection: BrokerUpstreamConnection, request: WorkerControlDocument, published: Published,
        reject: (WorkerControlFailure) -> T, decode: (String) -> Refinement<Decoded<T>,WorkerControlFailure>): T {
        var prompted = false
        while (true) {
            val frame = connection.receive()
            if (frame !is BrokerUpstreamFrame.Text) return reject(WorkerControlFailure.UNAVAILABLE)
            if (WorkerSeedPrompt.isPrompt(frame.message)) {
                if (prompted || request.action != WorkerControlAction.DEMAND || request.startup != WorkerStartupAction.SEED || request.seedConsent != WorkerSeedConsentSelection.INTERACTIVE)
                    return reject(WorkerControlFailure.INVALID_REQUEST)
                prompted = true
                val prompt = when (val admitted = WorkerSeedPrompt.decode(frame.message)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return reject(admitted.failure)
                }
                val decision = withTimeoutOrNull(BrokerOperationalLimits.seedConsent.value) { seedConsent.request(prompt.disclosure) } ?: WorkerSeedConsent.ABSENT
                if (connection.send(prompt.reply(decision)) != BrokerUpstreamSend.SENT) return reject(WorkerControlFailure.UNAVAILABLE)
            } else return when (val decoded = decode(frame.message)) {
                is Refinement.Rejected -> reject(decoded.failure)
                is Refinement.Refined -> {
                    val binding = decoded.value.binding
                    if (binding.installationId != published.installation || binding.stateEpoch.toString() != published.epoch ||
                        binding.serviceGeneration.toString() != published.generation || (request.action == WorkerControlAction.DEMAND && binding.configurationIdentity != request.configurationIdentity))
                        reject(WorkerControlFailure.IDENTITY_REJECTED)
                    else decoded.value.result
                }
            }
        }
    }
    private class Decoded<T>(val result: T, val binding: WorkerRouteBinding.Installation)
    private class Published(val installation: String, val epoch: String, val generation: String, val configuration: String)
    private fun published(command: BrokerServiceLaunchCommand, requireDesired: Boolean = true): Refinement<Published,WorkerControlFailure> { return try {
        val root = kast.toRealPath().parent.parent
        val owner = when (val observed = BrokerInstallationState.observe(root)) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected -> return Refinement.Rejected(if (observed.failure == InstallationStateFailure.EPOCH_ABSENT) WorkerControlFailure.UNAVAILABLE else WorkerControlFailure.IDENTITY_REJECTED)
        }
        val state = BROKER_SERVICE_STATE_JSON.decodeFromString<BrokerServiceStateDocument>(readBounded(command.readinessFile))
        if (state !is BrokerServiceStateDocument.Ready || state.schemaVersion != BROKER_SERVICE_STATE_SCHEMA_VERSION ||
            (requireDesired && state.serviceIdentity != command.identity.value)) Refinement.Rejected(WorkerControlFailure.UNAVAILABLE)
        else Refinement.Refined(Published(owner.installationId.value,owner.stateEpoch.value.toString(),state.serviceInstanceId,workerLaunchConfigurationIdentity(command.configuration)))
    } catch (_: Exception) { Refinement.Rejected(WorkerControlFailure.UNAVAILABLE) }
    }

    private fun readBounded(path: Path): String {
        if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw java.io.IOException("state rejected")
        return java.nio.file.Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use {
            val bytes = it.readNBytes(BrokerOperationalLimits.maximumControlStateBytes + 1)
            if (bytes.size > BrokerOperationalLimits.maximumControlStateBytes) throw java.io.IOException("state rejected")
            bytes.toString(Charsets.UTF_8)
        }
    }

}

@Serializable internal enum class WorkerControlAction { STATUS, DEMAND, RETIRED }
@Serializable internal enum class WorkerStartupAction { REUSE, REBUILD, SEED }
@Serializable internal data class WorkerControlDocument(
    val action: WorkerControlAction,
    val root: String,
    val heap: String = "",
    val startup: WorkerStartupAction = WorkerStartupAction.REUSE,
    val ideHome: String? = null,
    val sourceSystem: String? = null,
    val configurationIdentity: String = "",
    val seedConsent: WorkerSeedConsentSelection = WorkerSeedConsentSelection.PREGRANTED,
) {
    fun startupRequest(): InstalledWorkerStartup = when (startup) {
        WorkerStartupAction.REUSE -> InstalledWorkerStartup.Reuse(ideHome?.let(Path::of))
        WorkerStartupAction.REBUILD -> InstalledWorkerStartup.Rebuild(ideHome?.let(Path::of))
        WorkerStartupAction.SEED -> InstalledWorkerStartup.Seed(ideHome?.let(Path::of), sourceSystem?.let(Path::of), seedConsent)
    }
    companion object {
        fun demand(root: Path, heap: IndexerHeapSize, startup: InstalledWorkerStartup) = WorkerControlDocument(
            WorkerControlAction.DEMAND, root.toString(), "${heap.mebibytes}m", when (startup) {
                is InstalledWorkerStartup.Reuse -> WorkerStartupAction.REUSE
                is InstalledWorkerStartup.Rebuild -> WorkerStartupAction.REBUILD
                is InstalledWorkerStartup.Seed -> WorkerStartupAction.SEED
            }, startup.ideHome?.toString(), (startup as? InstalledWorkerStartup.Seed)?.sourceSystem?.toString(),
            seedConsent = (startup as? InstalledWorkerStartup.Seed)?.consent ?: WorkerSeedConsentSelection.PREGRANTED,
        )
        fun retired(root: Path) = WorkerControlDocument(WorkerControlAction.RETIRED, root.toString())
    }
}

@Serializable internal data class WorkerControlReply(
    val failure: WorkerControlFailure? = null,
    val root: String? = null,
    val runtimeId: String? = null,
    val socket: String? = null,
    val attempt: String? = null,
    val installationId: String? = null,
    val stateEpoch: String? = null,
    val serviceGeneration: String? = null,
    val configurationIdentity: String? = null,
    val workspaceRevision: Long? = null,
) {
    companion object {
        fun encode(result: InstalledWorkerStart): String = Json.encodeToString(when (result) {
            is InstalledWorkerStart.Rejected -> WorkerControlReply(failure = result.failure)
            is InstalledWorkerStart.Ready -> when (val binding = result.binding) {
                WorkerRouteBinding.EffectBoundary -> WorkerControlReply(failure = WorkerControlFailure.IDENTITY_REJECTED)
                is WorkerRouteBinding.Installation -> WorkerControlReply(root = result.endpoint.root.toString(), runtimeId = result.endpoint.runtimeId.value,
                    socket = result.endpoint.socket.toString(), attempt = result.endpoint.attempt.value,
                    installationId = binding.installationId, stateEpoch = binding.stateEpoch.toString(), serviceGeneration = binding.serviceGeneration.toString(),
                    configurationIdentity = binding.configurationIdentity, workspaceRevision = binding.workspaceRevision)
            }
        })
        fun decode(raw: String): InstalledWorkerStart = try {
            val value = Json.decodeFromString<WorkerControlReply>(raw)
            when {
                value.failure != null && listOf(value.root, value.runtimeId, value.socket, value.attempt, value.installationId, value.stateEpoch, value.serviceGeneration, value.configurationIdentity, value.workspaceRevision).all { it == null } -> rejected(value.failure)
                value.failure == null && value.root != null && value.runtimeId != null && value.socket != null && value.attempt != null && value.installationId != null && value.stateEpoch != null && value.serviceGeneration != null && value.configurationIdentity != null && value.workspaceRevision != null -> {
                    val runtime = SemanticRuntimeId.parse(value.runtimeId)
                    val attempt = SemanticRuntimeBootstrapAttemptId.admit(value.attempt)
                    val binding = WorkerRouteBinding.Installation.admit(value.installationId, value.stateEpoch, value.serviceGeneration, value.configurationIdentity, value.workspaceRevision)
                    if (runtime !is Refinement.Refined || attempt !is Refinement.Refined || binding !is Refinement.Refined) rejected(WorkerControlFailure.IDENTITY_REJECTED)
                    else when (val endpoint = InstalledWorkerEndpoint.admit(Path.of(value.root), runtime.value, Path.of(value.socket), attempt.value)) {
                        is Refinement.Refined -> InstalledWorkerStart.Ready(endpoint.value, binding.value)
                        is Refinement.Rejected -> rejected(endpoint.failure)
                    }
                }
                else -> rejected(WorkerControlFailure.INVALID_REQUEST)
            }
        } catch (_: Exception) { rejected(WorkerControlFailure.INVALID_REQUEST) }
    }
}
private fun rejected(failure: WorkerControlFailure) = InstalledWorkerStart.Rejected(failure)

/** Source-independent launch identity; heap is separately retained in the reservation. */
internal fun workerLaunchConfigurationIdentity(configuration: io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration): String {
    val values = configuration.childIdentityInputs(io.github.amichne.kast.distribution.contract.configuration.ConfigurationChild.BROKER)
        .filterKeys { it != "KAST_INDEXER_MAX_HEAP" && it != "KAST_CONFIGURATION_FILE" }
    val material = kotlinx.serialization.json.JsonObject(values.toSortedMap().mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString()
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8)))
}

internal fun workerConfigurationIdentity(configuration: io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration): String {
    val values = configuration.childIdentityInputs(io.github.amichne.kast.distribution.contract.configuration.ConfigurationChild.BROKER)
        .filterKeys { it != "KAST_CONFIGURATION_FILE" }
    val material = JsonObject(values.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8)))
}
