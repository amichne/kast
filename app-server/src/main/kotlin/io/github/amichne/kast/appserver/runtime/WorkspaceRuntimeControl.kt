package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.appserver.protocol.ThreadBindingOwner
import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface WorkerConfigurationExpectation {
    data object ProtocolFixture : WorkerConfigurationExpectation
    class Selected private constructor(val identity: String) : WorkerConfigurationExpectation {
        companion object {
            fun admit(identity: String): Refinement<Selected,WorkerControlFailure> = if (identity.matches(Regex("[0-9a-f]{64}"))) Refinement.Refined(Selected(identity))
                else Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
        }
    }
}

internal sealed interface WorkerControlLifecycle {
    data object ProtocolFixture : WorkerControlLifecycle
    data class Installed(val stoppedMarker: Path) : WorkerControlLifecycle
}

/** One installation's process-owned startup boundary. Frontend cancellation cannot cancel shared launch. */
internal class WorkspaceRuntimeControl private constructor(
    private val installationRoot: Path,
    private val owner: ThreadBindingOwner.Installation,
    private val generation: BrokerServiceGeneration,
    private val configuration: ResolvedKastConfiguration,
    private val effects: InstalledWorkerEffects,
    private val ledger: WorkerAdmissionCoordinator,
    private val receipts: Path,
    private val lifecycle: WorkerControlLifecycle,
    private val hostObservation: () -> BrokerFrontendObservation,
    private val sourceEnvironment: Map<String,String>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val admission = Mutex()
    private val closed = AtomicBoolean(false)
    private val jobs = ConcurrentHashMap<WorkerReservationId, Job>()
    private val routes = ConcurrentHashMap<WorkerReservationId, InstalledWorkerEndpoint>()
    private val configurations = ConcurrentHashMap<WorkerReservationId, ResolvedKastConfiguration>()
    private val revisions = ConcurrentHashMap<WorkerReservationId, WorkspaceRegistryRevision>()
    private class StartupSelection(val reservation: WorkerReservationId, val startup: InstalledWorkerStartup)
    private val startups = ConcurrentHashMap<io.github.amichne.kast.appserver.BrokerWorkspaceId, StartupSelection>()
    private val registry = WorkspaceEnrollmentStore(installationRoot.resolve("config/workspaces.json"))
    private enum class RootRetirementState { IN_PROGRESS, UNCERTAIN }
    private val rootRetirements = ConcurrentHashMap<Path, RootRetirementState>()
    private sealed interface DemandSelection {
        class Admitted(val demand: WorkerDemand) : DemandSelection
        class Replace(val reservation: WorkerReservation, val endpoint: InstalledWorkerEndpoint) : DemandSelection
    }

    private sealed interface StopSelection {
        val binding: WorkerRouteBinding.Installation
        class Reserved(val reservation: WorkerReservation, override val binding: WorkerRouteBinding.Installation) : StopSelection
        class Unreserved(override val binding: WorkerRouteBinding.Installation) : StopSelection
    }

    suspend fun stop(root: Path): InstalledWorkerStop {
        val canonical = try { root.toRealPath() } catch (_: Exception) { return InstalledWorkerStop.Rejected(WorkerControlFailure.IDENTITY_REJECTED) }
        val selection = admission.withLock {
            if (rootRetirements[canonical] == RootRetirementState.IN_PROGRESS) return InstalledWorkerStop.Rejected(WorkerControlFailure.RETIREMENT_UNPROVEN)
            val record = ledger.snapshot().workers.singleOrNull { it.reservation.identity.workspace.root.path == canonical }
            val selected = if (record != null) {
                val binding = when (val admitted = binding(record.reservation)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return InstalledWorkerStop.Rejected(admitted.failure)
                }
                StopSelection.Reserved(record.reservation, binding)
            } else {
                val snapshot = when (val observed = registry.snapshot()) {
                    is WorkspaceRegistryRead.Read -> observed.snapshot
                    is WorkspaceRegistryRead.Rejected -> return InstalledWorkerStop.Rejected(WorkerControlFailure.REGISTRATION_REJECTED)
                }
                if (snapshot.workspaces.none { it.root.path == canonical }) return InstalledWorkerStop.Rejected(WorkerControlFailure.REGISTRATION_REJECTED)
                val selected = when (val resolved = InstalledWorkspaceConfigurationIngress.resolve(installationRoot, canonical, sourceEnvironment)) {
                    is Refinement.Refined -> resolved.value
                    is Refinement.Rejected -> return InstalledWorkerStop.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
                }
                val binding = when (val admitted = WorkerRouteBinding.Installation.admit(owner.installationId.value, owner.stateEpoch.value.toString(),
                    generation.value.toString(), workerConfigurationIdentity(selected), snapshot.revision.value)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return InstalledWorkerStop.Rejected(admitted.failure)
                }
                StopSelection.Unreserved(binding)
            }
            rootRetirements[canonical] = RootRetirementState.IN_PROGRESS
            selected
        }
        val retired = try { when (selection) {
            is StopSelection.Reserved -> {
                val reservation = selection.reservation
                val settled = jobs[reservation.id]?.let { job -> job.cancel(); (withTimeoutOrNull(BrokerOperationalLimits.workerStartupJoin.value) { job.join(); true } == true) } ?: true
                if (settled) retire(reservation, routes[reservation.id]) else InstalledWorkerRetirement.UNPROVEN
            }
            is StopSelection.Unreserved -> withTimeoutOrNull(OperationExecutionBudget.LOCAL_QUALIFICATION.value) { effects.retireUnreserved(canonical) }
                ?: InstalledWorkerRetirement.UNPROVEN
        } } catch (cancelled: CancellationException) {
            rootRetirements[canonical] = RootRetirementState.UNCERTAIN
            throw cancelled
        } catch (_: Exception) { InstalledWorkerRetirement.UNPROVEN }
        admission.withLock {
            if (retired == InstalledWorkerRetirement.EXACT_RETIRED) rootRetirements.remove(canonical)
            else rootRetirements[canonical] = RootRetirementState.UNCERTAIN
        }
        return if (retired == InstalledWorkerRetirement.EXACT_RETIRED) InstalledWorkerStop.Stopped(canonical, selection.binding)
            else InstalledWorkerStop.Rejected(WorkerControlFailure.RETIREMENT_UNPROVEN)
    }

    suspend fun demand(root: Path, heap: IndexerHeapSize, startup: InstalledWorkerStartup, consentAuthority: WorkerSeedConsentAuthority = WorkerSeedConsentAuthority.Unavailable, expectedConfiguration: WorkerConfigurationExpectation = WorkerConfigurationExpectation.ProtocolFixture): InstalledWorkerStart {
        if (!available()) return rejected(WorkerControlFailure.LIFECYCLE_TRANSITION)
        val selection = admission.withLock {
            if (!available()) return rejected(WorkerControlFailure.LIFECYCLE_TRANSITION)
            val registration = when (val registered = registry.enroll(root)) {
                is Refinement.Refined -> registered.value
                is Refinement.Rejected -> return rejected(WorkerControlFailure.REGISTRATION_REJECTED)
            }
            val workspace = registration.workspace
            if (rootRetirements.containsKey(workspace.root.path)) return rejected(WorkerControlFailure.RECOVERY_REQUIRED)
            val selectedConfiguration = when (val selected = InstalledWorkspaceConfigurationIngress.resolve(installationRoot, workspace.root.path, sourceEnvironment)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return rejected(WorkerControlFailure.IDENTITY_REJECTED)
            }
            val selectedIdentity = workerConfigurationIdentity(selectedConfiguration)
            if (expectedConfiguration is WorkerConfigurationExpectation.Selected &&
                (expectedConfiguration.identity != selectedIdentity || selectedConfiguration.indexerHeap != heap)) return rejected(WorkerControlFailure.IDENTITY_REJECTED)
            val memory = when (val admitted = DeclaredWorkerMemory.admit(heap, configuration.workerCapacity.native.value, configuration.workerCapacity.gradle.value)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(WorkerControlFailure.CAPACITY_REJECTED)
            }
            val identity = WorkspaceWorkerIdentity(owner, generation, workspace)
            val existing = ledger.snapshot().workers.singleOrNull { it.reservation.identity.workspace.id == workspace.id }
            val previous = existing?.let { configurations[it.reservation.id] }
            if (existing != null && startup is InstalledWorkerStartup.Reuse && startup.ideHome != null &&
                startups[workspace.id]?.startup?.ideHome != startup.ideHome) return rejected(WorkerControlFailure.IDENTITY_REJECTED)
            if (previous != null && workerConfigurationIdentity(previous) != selectedIdentity && startup is InstalledWorkerStartup.Reuse)
                return rejected(WorkerControlFailure.IDENTITY_REJECTED)
            if (existing?.phase == WorkerReservationPhase.READY && startup !is InstalledWorkerStartup.Reuse) {
                val endpoint = routes[existing.reservation.id] ?: return rejected(WorkerControlFailure.RECOVERY_REQUIRED)
                rootRetirements[workspace.root.path] = RootRetirementState.IN_PROGRESS
                return@withLock DemandSelection.Replace(existing.reservation, endpoint)
            }
            val selected = ledger.request(identity, memory)
            if (selected is WorkerDemand.Awaiting && !(startup is InstalledWorkerStartup.Reuse && startup.ideHome == null) && startups[workspace.id]?.startup != startup) return rejected(WorkerControlFailure.IDENTITY_REJECTED)
            DemandSelection.Admitted(selected.also { demand ->
                if (demand is WorkerDemand.Start) {
                    revisions[demand.reservation.id] = registration.revision
                    configurations[demand.reservation.id] = selectedConfiguration
                    startups[workspace.id] = StartupSelection(demand.reservation.id, startup)
                    if (!receipt(demand.reservation, WorkerReservationPhase.RESERVED)) {
                        ledger.quarantine(demand.reservation, WorkerAdmissionFailure.RECOVERY_REQUIRED)
                    } else {
                        val job = scope.launch(start = CoroutineStart.LAZY) { launch(demand.reservation, InstalledWorkerStartRequest(workspace.root.path, heap, startup, selectedConfiguration, consentAuthority)) }
                        jobs[demand.reservation.id] = job
                        job.invokeOnCompletion { jobs.remove(demand.reservation.id, job) }
                        job.start()
                    }
                }
            })
        }
        val result = when (selection) {
            is DemandSelection.Admitted -> selection.demand
            is DemandSelection.Replace -> {
                val previous = selection.reservation
                val settled = jobs[previous.id]?.let { (withTimeoutOrNull(BrokerOperationalLimits.workerStartupJoin.value) { it.join(); true } == true) } ?: true
                val retired = if (settled) retire(previous, selection.endpoint) else InstalledWorkerRetirement.UNPROVEN
                admission.withLock {
                    if (retired == InstalledWorkerRetirement.EXACT_RETIRED) rootRetirements.remove(previous.identity.workspace.root.path)
                    else rootRetirements[previous.identity.workspace.root.path] = RootRetirementState.UNCERTAIN
                }
                return if (retired == InstalledWorkerRetirement.EXACT_RETIRED) demand(root, heap, startup, consentAuthority, expectedConfiguration)
                    else rejected(WorkerControlFailure.RETIREMENT_UNPROVEN)
            }
        }
        val route = when (result) {
            is WorkerDemand.Start -> result.readiness.await()
            is WorkerDemand.Awaiting -> result.readiness.await()
            is WorkerDemand.Ready -> WorkerReadiness.Ready(result.route)
            is WorkerDemand.Rejected -> return rejected(result.failure.controlFailure())
        }
        return when (route) {
            is WorkerReadiness.Rejected -> rejected(route.failure.controlFailure())
            is WorkerReadiness.Ready -> {
                val endpoint = routes[route.route.reservation.id] ?: return rejected(WorkerControlFailure.RECOVERY_REQUIRED)
                if (effects.observe(endpoint) != InstalledWorkerObservation.EXACT_READY) {
                    quarantine(route.route.reservation, WorkerAdmissionFailure.WORKER_LOST)
                    return rejected(WorkerControlFailure.RECOVERY_REQUIRED)
                }
                bound(route.route.reservation, endpoint)
            }
        }
    }

    private suspend fun launch(reservation: WorkerReservation, request: InstalledWorkerStartRequest) {
        try {
            val permit = admission.withLock {
                if (!available() || !receipt(reservation, WorkerReservationPhase.STARTING)) {
                    quarantine(reservation, WorkerAdmissionFailure.COORDINATOR_UNAVAILABLE)
                    return
                }
                when (val consumed = ledger.consume(reservation.id, reservation.identity)) {
                    is Refinement.Refined -> consumed.value
                    is Refinement.Rejected -> return
                }
            }
            val started = withTimeoutOrNull(OperationExecutionBudget.WORKSPACE_READINESS.value) { effects.start(request) }
                ?: rejected(WorkerControlFailure.DEADLINE_EXCEEDED)
            when (started) {
                is InstalledWorkerStart.Rejected -> quarantine(reservation, WorkerAdmissionFailure.STARTUP_FAILED)
                is InstalledWorkerStart.Ready -> {
                    val endpoint = started.endpoint
                    if (endpoint.root != reservation.identity.workspace.root.path || effects.observe(endpoint) != InstalledWorkerObservation.EXACT_READY) {
                        quarantine(reservation, WorkerAdmissionFailure.PUBLICATION_REJECTED)
                        return
                    }
                    routes[reservation.id] = endpoint
                    admission.withLock {
                        if (!available() || !receipt(reservation, WorkerReservationPhase.READY, endpoint)) {
                            quarantine(reservation, WorkerAdmissionFailure.PUBLICATION_REJECTED)
                            return@withLock
                        }
                        when (ledger.publishReady(permit, endpoint.attempt)) {
                            is Refinement.Refined -> Unit
                            is Refinement.Rejected -> quarantine(reservation, WorkerAdmissionFailure.PUBLICATION_REJECTED)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            quarantine(reservation, WorkerAdmissionFailure.STARTUP_FAILED)
            throw cancelled
        } catch (_: Exception) {
            quarantine(reservation, WorkerAdmissionFailure.STARTUP_FAILED)
        }
    }

    private fun quarantine(reservation: WorkerReservation, failure: WorkerAdmissionFailure) {
        ledger.quarantine(reservation, failure)
        val phase = ledger.snapshot().workers.firstOrNull { it.reservation === reservation }?.phase ?: return
        if (!receipt(reservation, phase, routes[reservation.id])) { closed.set(true); ledger.drain() }
    }

    private suspend fun retire(reservation: WorkerReservation, endpoint: InstalledWorkerEndpoint?): InstalledWorkerRetirement {
        if (jobs.containsKey(reservation.id)) return InstalledWorkerRetirement.UNPROVEN
        quarantine(reservation, WorkerAdmissionFailure.RETIREMENT_UNPROVEN)
        val retired = ledger.retire(reservation) { exact ->
            if ((if (endpoint == null) effects.retireUnpublished(reservation.identity.workspace.root.path) else effects.stop(endpoint)) == InstalledWorkerRetirement.EXACT_RETIRED) WorkerRetirementObservation.ExactRetired(exact)
            else WorkerRetirementObservation.Unproven
        }
        if (retired !is Refinement.Refined) return InstalledWorkerRetirement.UNPROVEN
        return try {
            Files.delete(receipts.resolve("${reservation.id.value}.json"))
            routes.remove(reservation.id)
            revisions.remove(reservation.id)
            configurations.remove(reservation.id)
            startups.computeIfPresent(reservation.identity.workspace.id) { _, selected -> if (selected.reservation == reservation.id) null else selected }
            InstalledWorkerRetirement.EXACT_RETIRED
        } catch (_: Exception) { closed.set(true); ledger.drain(); InstalledWorkerRetirement.UNPROVEN }
    }

    suspend fun handle(session: DefaultWebSocketServerSession, handshakeAllowed: Boolean = true) {
        val frame = withTimeoutOrNull(BrokerOperationalLimits.workerControlHandshake.value) { session.incoming.receiveCatching().getOrNull() }
        val document = try {
            val text = (frame as? Frame.Text)?.readText() ?: return
            if (text.toByteArray().size > CoordinatorStatusProtocol.maximumCommandBytes) return
            Json.decodeFromString<WorkerControlDocument>(text)
        } catch (_: Exception) { session.send(WorkerControlReply.encode(rejected(WorkerControlFailure.INVALID_REQUEST))); return }
        if (document.action == WorkerControlAction.STATUS) {
            if (!handshakeAllowed) return
            session.send(admission.withLock { if (available()) buildJsonObject {
                put("status", "READY"); put("installationId", owner.installationId.value)
                put("stateEpoch", owner.stateEpoch.value.toString()); put("serviceGeneration", generation.value.toString()); put("configurationIdentity", workerLaunchConfigurationIdentity(configuration))
                val observed = ledger.snapshot()
                put("reservedMiB", observed.reserved.value); put("starting", observed.starting); put("hostAttachment", hostObservation().name)
                putJsonArray("workers") {
                    observed.workers.forEach { worker -> add(buildJsonObject {
                        put("workspaceId", worker.reservation.identity.workspace.id.value)
                        put("reservationId", worker.reservation.id.value.toString())
                        put("phase", worker.phase.name); put("reservedMiB", worker.reservation.memory.total.value)
                        put("requestedHeapMiB", worker.reservation.memory.heap.mebibytes); put("heapObservation", "UNOBSERVED")
                        configurations[worker.reservation.id]?.let { put("configurationIdentity", workerConfigurationIdentity(it)) }
                    }) }
                }
            }.toString() else WorkerControlReply.encode(rejected(WorkerControlFailure.LIFECYCLE_TRANSITION)) })
            handle(session, handshakeAllowed = false)
            return
        }
        if (document.action == WorkerControlAction.RETIRED) {
            val result = try { stop(Path.of(document.root)) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { InstalledWorkerStop.Rejected(WorkerControlFailure.INVALID_REQUEST) }
            session.send(WorkerStopReply.encode(result))
            return
        }
        val result = try {
            val root = Path.of(document.root)
            when (document.action) {
                WorkerControlAction.STATUS -> rejected(WorkerControlFailure.INVALID_REQUEST)
                WorkerControlAction.DEMAND -> when (val heap = IndexerHeapSize.parse(document.heap)) {
                    is Refinement.Refined -> when (val expectation = WorkerConfigurationExpectation.Selected.admit(document.configurationIdentity)) {
                        is Refinement.Rejected -> rejected(expectation.failure)
                        is Refinement.Refined -> withTimeoutOrNull(OperationExecutionBudget.WORKSPACE_READINESS.value) { demand(root, heap.value, document.startupRequest(), WorkerSeedConsentExchange(session), expectation.value) }
                            ?: rejected(WorkerControlFailure.DEADLINE_EXCEEDED)
                    }
                    is Refinement.Rejected -> rejected(WorkerControlFailure.INVALID_REQUEST)
                }
                WorkerControlAction.RETIRED -> rejected(WorkerControlFailure.INVALID_REQUEST)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { rejected(WorkerControlFailure.INVALID_REQUEST) }
        session.send(WorkerControlReply.encode(result))
    }

    private fun bound(reservation: WorkerReservation, endpoint: InstalledWorkerEndpoint): InstalledWorkerStart {
        return when (val admitted = binding(reservation)) {
            is Refinement.Refined -> InstalledWorkerStart.Ready(endpoint, admitted.value)
            is Refinement.Rejected -> rejected(admitted.failure)
        }
    }

    private fun binding(reservation: WorkerReservation): Refinement<WorkerRouteBinding.Installation, WorkerControlFailure> {
        val revision = revisions[reservation.id] ?: return Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
        return WorkerRouteBinding.Installation.admit(owner.installationId.value, owner.stateEpoch.value.toString(),
            generation.value.toString(), workerConfigurationIdentity(configurations[reservation.id]
                ?: return Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)), revision.value)
    }

    suspend fun drain() {
        closed.set(true)
        ledger.drain()
        val active = jobs.values.toList()
        active.forEach { it.cancel() }
        withTimeoutOrNull(BrokerOperationalLimits.workerShutdownJoin.value) { active.joinAll() }
        withContext(NonCancellable) {
            ledger.snapshot().workers.forEach { status ->
                val endpoint = routes[status.reservation.id]
                if (retire(status.reservation, endpoint) != InstalledWorkerRetirement.EXACT_RETIRED) {
                    receipt(status.reservation, status.phase, endpoint)
                }
            }
        }
        scope.cancel()
    }

    private fun available(): Boolean = !closed.get() && InstallationLifecycleFence.observe(installationRoot) == InstallationLifecycleStartAdmission.AVAILABLE &&
        when (val mode = lifecycle) {
            WorkerControlLifecycle.ProtocolFixture -> true
            is WorkerControlLifecycle.Installed -> try {
                Files.readAttributes(mode.stoppedMarker, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                false
            } catch (_: NoSuchFileException) { true } catch (_: Exception) { false }
        }

    private fun receipt(reservation: WorkerReservation, phase: WorkerReservationPhase, endpoint: InstalledWorkerEndpoint? = null): Boolean { return try {
        val process = ProcessHandle.current()
        val started = process.info().startInstant().orElse(null) ?: return false
        val document = buildJsonObject {
            put("schemaVersion", 1); put("reservationId", reservation.id.value.toString())
            put("installationId", owner.installationId.value); put("stateEpoch", owner.stateEpoch.value.toString())
            put("serviceGeneration", generation.value.toString()); put("workspaceRoot", reservation.identity.workspace.root.path.toString())
            put("workspaceId", reservation.identity.workspace.id.value); put("phase", phase.name)
            put("coordinatorPid", process.pid()); put("coordinatorStartedAt", started.toString())
            put("configurationIdentity", workerConfigurationIdentity(configurations[reservation.id] ?: return false))
            put("heapMiB", reservation.memory.heap.mebibytes); put("nativeMiB", reservation.memory.nativeAllowance.value); put("gradleMiB", reservation.memory.gradleAllowance.value)
            if (endpoint != null) putJsonObject("endpoint") {
                put("runtimeId", endpoint.runtimeId.value); put("socket", endpoint.socket.toString()); put("bootstrapAttempt", endpoint.attempt.value)
            }
        }.toString().toByteArray()
        if (receipts.toRealPath() != receipts || Files.isSymbolicLink(receipts)) return false
        val temporary = Files.createTempFile(receipts, ".receipt-", ".tmp")
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(document); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true)
            }
            Files.move(temporary, receipts.resolve("${reservation.id.value}.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } finally { Files.deleteIfExists(temporary) }
    } catch (_: Exception) { false }
    }

    companion object {
        fun create(installationRoot: Path, owner: ThreadBindingOwner.Installation, generation: BrokerServiceGeneration, configuration: ResolvedKastConfiguration, effects: InstalledWorkerEffects, lifecycle: WorkerControlLifecycle = WorkerControlLifecycle.ProtocolFixture, hostObservation: () -> BrokerFrontendObservation = { BrokerFrontendObservation.PENDING }, sourceEnvironment: Map<String,String> = emptyMap()): Refinement<WorkspaceRuntimeControl,WorkerControlFailure> {
            return try {
                if (installationRoot.toRealPath() != installationRoot || InstallationLifecycleFence.observe(installationRoot) != InstallationLifecycleStartAdmission.AVAILABLE) return Refinement.Rejected(WorkerControlFailure.LIFECYCLE_TRANSITION)
                val receipts = installationRoot.resolve("state/workers")
                Files.createDirectories(receipts)
                if (receipts.toRealPath() != receipts || Files.list(receipts).use { it.findAny().isPresent }) return Refinement.Rejected(WorkerControlFailure.RECOVERY_REQUIRED)
                Files.setPosixFilePermissions(receipts, PosixFilePermissions.fromString("rwx------"))
                val capacity = configuration.workerCapacity
                val policy = when (val admitted = WorkerAdmissionPolicy.admit(capacity.resident.value, capacity.startup.value, capacity.aggregate.value)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return Refinement.Rejected(WorkerControlFailure.CAPACITY_REJECTED)
                }
                Refinement.Refined(WorkspaceRuntimeControl(installationRoot, owner, generation, configuration, effects, WorkerAdmissionCoordinator(owner,generation,policy, WorkerReservationIdSource { WorkerReservationId.fresh() }), receipts, lifecycle, hostObservation, sourceEnvironment.toMap()))
            } catch (_: Exception) { Refinement.Rejected(WorkerControlFailure.RECEIPT_REJECTED) }
        }
    }
}
private fun rejected(failure: WorkerControlFailure) = InstalledWorkerStart.Rejected(failure)
private fun WorkerAdmissionFailure.controlFailure(): WorkerControlFailure = when (this) {
    WorkerAdmissionFailure.RESIDENT_CAPACITY_EXCEEDED, WorkerAdmissionFailure.STARTUP_CAPACITY_EXCEEDED,
    WorkerAdmissionFailure.AGGREGATE_RESERVATION_EXCEEDED -> WorkerControlFailure.CAPACITY_REJECTED
    WorkerAdmissionFailure.STARTUP_FAILED -> WorkerControlFailure.STARTUP_REJECTED
    WorkerAdmissionFailure.COORDINATOR_UNAVAILABLE -> WorkerControlFailure.UNAVAILABLE
    else -> WorkerControlFailure.RECOVERY_REQUIRED
}
