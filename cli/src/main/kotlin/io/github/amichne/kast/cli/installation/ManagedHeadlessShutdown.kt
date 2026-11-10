package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.managed.ManagedHeadlessInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostMode
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class ManagedHeadlessShutdownFailure {
    ADMISSION_REJECTED,
    HOST_REJECTED,
    HOST_NOT_SETTLED,
    HOST_DID_NOT_EXIT,
    INTERRUPTED,
}

internal sealed interface ManagedHeadlessShutdownOutcome {
    data object Completed : ManagedHeadlessShutdownOutcome

    data class Rejected(val failure: ManagedHeadlessShutdownFailure) : ManagedHeadlessShutdownOutcome
}

/** An explicit private deployment uses the Host settlement protocol, never desktop service control. */
internal fun shutdownManagedHeadless(installation: Path, home: Path, state: Path): ManagedHeadlessShutdownOutcome {
    val launch =
        try {
            val bytes =
                Files.newInputStream(installation.resolve("config/selected-ide.json")).use { it.readNBytes(4097) }
            if (bytes.size > 4096)
                return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.ADMISSION_REJECTED)
            Json.decodeFromString<SelectedIdeLaunch>(bytes.decodeToString()) as? SelectedIdeLaunch.Resolved
                ?: return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.ADMISSION_REJECTED)
        } catch (_: Exception) {
            return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.ADMISSION_REJECTED)
        }
    val admitted = ManagedHeadlessInstallation.resolve(launch, state, home)
    if (admitted !is Refinement.Refined)
        return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.ADMISSION_REJECTED)
    val runtime = admitted.value
    val configuration =
        io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration.resolve(
            io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources(
                environment = mapOf("KAST_MANAGED_HEADLESS_STATE" to state.toString())
            )
        )
    if (configuration !is Refinement.Refined)
        return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.ADMISSION_REJECTED)
    val selection = configuration.value.managedHeadlessState
    val execute: (ManagedHostRequest) -> ManagedHostResponse = { executeManagedHost(it, home, selection) }
    return stopManagedHeadlessHost(
        runtime.launch.home,
        runtime.config.toString(),
        execute,
        awaitExit = ::awaitManagedProcessExit,
    )
}

/** The adapter proves profile/process identity on every exchange; no signal targets an unadmitted process. */
internal fun stopManagedHeadlessHost(
    idea: String,
    profile: String,
    execute: (ManagedHostRequest) -> ManagedHostResponse,
    awaitExit: (ManagedIdeProcess) -> Boolean,
    pauseSettlement: () -> Unit = { Thread.sleep(SETTLEMENT_POLL_MILLIS) },
    nanoTime: () -> Long = System::nanoTime,
): ManagedHeadlessShutdownOutcome {
    try {
        val inspected = execute(ManagedHostRequest.Inspect(idea, profile))
        if (inspected !is ManagedHostResponse.Inspected)
            return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_REJECTED)
        if (inspected.processes.isEmpty()) return ManagedHeadlessShutdownOutcome.Completed
        val process =
            inspected.processes.singleOrNull()
                ?: return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_REJECTED)
        val host =
            inspected.result as? IdeLifecycleResult.Inspected
                ?: return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_REJECTED)
        if (host.runtimeIdentity?.mode != IdeHostMode.MANAGED_HEADLESS)
            return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.ADMISSION_REJECTED)
        val transaction = managedShutdownTransaction(host.runtimeIdentity)
        val settled =
            settleManagedHeadlessHost(idea, profile, host.host, transaction, execute, pauseSettlement, nanoTime)
        if (settled !is ManagedHostResponse.Inspected || settled.result !is IdeLifecycleResult.HostSettled)
            return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_NOT_SETTLED)
        val stopped = execute(ManagedHostRequest.Stop(idea, profile, host.host, transaction))
        if (stopped !is ManagedHostResponse.Inspected || stopped.result !is IdeLifecycleResult.HostStopRequested)
            return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_REJECTED)
        return if (awaitExit(process)) ManagedHeadlessShutdownOutcome.Completed
        else ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_DID_NOT_EXIT)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.INTERRUPTED)
    }
}

private fun awaitManagedProcessExit(process: ManagedIdeProcess): Boolean {
    val handle = ProcessHandle.of(process.pid).orElse(null) ?: return true
    val start = handle.info().startInstant().orElse(null) ?: return false
    if (start.toEpochMilli() != process.processStartEpochMillis) return true
    return try {
        handle.onExit().get(HOST_EXIT_SECONDS, TimeUnit.SECONDS)
        true
    } catch (_: java.util.concurrent.TimeoutException) {
        observeManagedProcessState(process.pid) ==
            io.github.amichne.kast.distribution.contract.ObservedProcessState.EXITED
    } catch (_: java.util.concurrent.ExecutionException) {
        false
    }
}

private const val HOST_EXIT_SECONDS = 10L
private const val SETTLEMENT_POLL_MILLIS = 50L

private fun settleManagedHeadlessHost(
    idea: String,
    profile: String,
    host: String,
    transaction: String,
    execute: (ManagedHostRequest) -> ManagedHostResponse,
    pauseSettlement: () -> Unit,
    nanoTime: () -> Long,
): ManagedHostResponse {
    var settled = execute(ManagedHostRequest.Settle(idea, profile, host, transaction))
    val settlementDeadline = nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (
        settled is ManagedHostResponse.Inspected &&
            settled.result is IdeLifecycleResult.HostSettling &&
            nanoTime() < settlementDeadline
    ) {
        pauseSettlement()
        settled = execute(ManagedHostRequest.Settle(idea, profile, host, transaction))
    }
    return settled
}

/** Retries in separate Control processes reclaim only this exact Host's shutdown fence. */
internal fun managedShutdownTransaction(identity: IdeHostRuntimeIdentity): String {
    val owner =
        ManagedShutdownOwner(
            identity.ideaHome,
            identity.profile,
            identity.pid,
            identity.processStartEpochMillis,
            identity.hostLifetime,
        )
    return "shutdown-v1:" + UUID.nameUUIDFromBytes(Json.encodeToString(owner).encodeToByteArray())
}

/** Keep this versioned key stable across Control upgrades while the same Host is running. */
@Serializable
private data class ManagedShutdownOwner(
    val ideaHome: String,
    val profile: String,
    val pid: Long,
    val processStartEpochMillis: Long,
    val hostLifetime: String,
)
