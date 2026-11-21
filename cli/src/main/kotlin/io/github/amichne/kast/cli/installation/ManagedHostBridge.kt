package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.ide.IdeLifecycleClient
import io.github.amichne.kast.appserver.ide.IdeLifecycleDeployment
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationPathSelection
import io.github.amichne.kast.distribution.contract.configuration.HostHeapPolicy
import io.github.amichne.kast.distribution.contract.configuration.HostIdleShutdown
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Path

private sealed interface ManagedHostTarget {
    data class Stopped(val launch: SelectedIdeLaunch.Resolved, val profile: Path) : ManagedHostTarget

    data class Running(val launch: SelectedIdeLaunch.Resolved, val profile: Path, val process: ManagedIdeProcess) :
        ManagedHostTarget

    data class Rejected(val reason: ManagedHostFailure) : ManagedHostTarget
}

/** The adapter proves profile/process identity on every exchange; no effect targets an unadmitted process. */
internal fun executeManagedHost(
    request: ManagedHostRequest,
    home: Path,
    managedHeadlessState: ConfigurationPathSelection = ConfigurationPathSelection.OwnerDefault,
    processes: (SelectedIdeLaunch.Resolved) -> ManagedProcessInventory = ::observeManagedIdeProcesses,
    exchange: (Path, IdeLifecycleCommand) -> IdeLifecycleResult = { idea, command ->
        exchangeSelectedHost(home, idea, managedHeadlessState, command)
    },
): ManagedHostResponse =
    when (val target = selectManagedHostTarget(request, processes)) {
        is ManagedHostTarget.Rejected -> ManagedHostResponse.Rejected(target.reason)
        is ManagedHostTarget.Stopped ->
            when (request) {
                is ManagedHostRequest.Inspect -> ManagedHostResponse.Inspected(emptyList())
                is ManagedHostRequest.Settle,
                is ManagedHostRequest.Stop -> ManagedHostResponse.Rejected(ManagedHostFailure.CONCURRENT_CHANGE)
            }
        is ManagedHostTarget.Running ->
            when (request) {
                is ManagedHostRequest.Inspect -> inspectRunningHost(target, exchange)
                is ManagedHostRequest.Settle ->
                    changeRunningHost(
                        target,
                        IdeLifecycleCommand.SettleHost(request.host, request.transaction),
                        processes,
                        exchange,
                    )
                is ManagedHostRequest.Stop ->
                    changeRunningHost(
                        target,
                        IdeLifecycleCommand.StopHost(request.host, request.transaction),
                        processes,
                        exchange,
                    )
            }
    }

private fun selectManagedHostTarget(
    request: ManagedHostRequest,
    processes: (SelectedIdeLaunch.Resolved) -> ManagedProcessInventory,
): ManagedHostTarget {
    val idea = Path.of(request.ideaHome)
    val profile = Path.of(request.profile)
    if (!idea.isAbsolute || idea.normalize() != idea)
        return ManagedHostTarget.Rejected(ManagedHostFailure.INVALID_REQUEST)
    if (!profile.isAbsolute || profile.normalize() != profile)
        return ManagedHostTarget.Rejected(ManagedHostFailure.INVALID_REQUEST)
    val launch =
        when (val selected = SelectedIdeInstallation.resolve(idea)) {
            is SelectedIdeLaunch.Resolved -> selected
            is SelectedIdeLaunch.Unavailable -> return ManagedHostTarget.Rejected(ManagedHostFailure.IDEA_UNAVAILABLE)
        }
    return when (val inventory = processes(launch)) {
        ManagedProcessInventory.Unavailable ->
            ManagedHostTarget.Rejected(ManagedHostFailure.PROCESS_IDENTITY_UNAVAILABLE)
        is ManagedProcessInventory.Captured ->
            when (inventory.processes.size) {
                0 -> ManagedHostTarget.Stopped(launch, profile)
                1 -> ManagedHostTarget.Running(launch, profile, inventory.processes.single())
                else -> ManagedHostTarget.Rejected(ManagedHostFailure.INSTANCE_AMBIGUOUS)
            }
    }
}

/** Without runtime identity the caller receives the unqualified result and must reject before any effect. */
private fun inspectRunningHost(
    target: ManagedHostTarget.Running,
    exchange: (Path, IdeLifecycleCommand) -> IdeLifecycleResult,
): ManagedHostResponse {
    val inspected = exchange(Path.of(target.launch.home), IdeLifecycleCommand.Inspect)
    if (inspected !is IdeLifecycleResult.Inspected || inspected.runtimeIdentity == null)
        return ManagedHostResponse.Inspected(listOf(target.process), inspected)
    return if (matchesRuntimeTarget(target, inspected)) ManagedHostResponse.Inspected(listOf(target.process), inspected)
    else ManagedHostResponse.Rejected(ManagedHostFailure.HOST_IDENTITY_MISMATCH, inspected)
}

private fun changeRunningHost(
    target: ManagedHostTarget.Running,
    command: IdeLifecycleCommand,
    processes: (SelectedIdeLaunch.Resolved) -> ManagedProcessInventory,
    exchange: (Path, IdeLifecycleCommand) -> IdeLifecycleResult,
): ManagedHostResponse {
    val idea = Path.of(target.launch.home)
    val inspected = exchange(idea, IdeLifecycleCommand.Inspect)
    if (inspected !is IdeLifecycleResult.Inspected)
        return ManagedHostResponse.Rejected(ManagedHostFailure.HOST_REJECTED, inspected)
    if (!matchesRuntimeTarget(target, inspected))
        return ManagedHostResponse.Rejected(ManagedHostFailure.HOST_IDENTITY_MISMATCH, inspected)
    when (val current = readmitTarget(target, processes)) {
        TargetReadmission.Current -> Unit
        is TargetReadmission.Rejected -> return ManagedHostResponse.Rejected(current.reason)
    }
    val result = exchange(idea, command)
    if (!matchesManagedHostResult(command, result))
        return ManagedHostResponse.Rejected(ManagedHostFailure.HOST_REJECTED, result)
    when (result) {
        is IdeLifecycleResult.HostSettled ->
            if (result.runtimeIdentity != inspected.runtimeIdentity)
                return ManagedHostResponse.Rejected(ManagedHostFailure.HOST_IDENTITY_MISMATCH, result)
        is IdeLifecycleResult.HostStopRequested ->
            if (result.runtimeIdentity != inspected.runtimeIdentity)
                return ManagedHostResponse.Rejected(ManagedHostFailure.HOST_IDENTITY_MISMATCH, result)
        else -> Unit
    }
    return ManagedHostResponse.Inspected(listOf(target.process), result)
}

private fun matchesRuntimeTarget(target: ManagedHostTarget.Running, inspected: IdeLifecycleResult.Inspected): Boolean {
    val identity = inspected.runtimeIdentity ?: return false
    if (
        identity.ideaHome != target.launch.home ||
            identity.profile != target.profile.toString() ||
            identity.hostLifetime != inspected.host
    )
        return false
    return identity.pid == target.process.pid &&
        identity.processStartEpochMillis == target.process.processStartEpochMillis
}

private sealed interface TargetReadmission {
    data object Current : TargetReadmission

    data class Rejected(val reason: ManagedHostFailure) : TargetReadmission
}

private fun readmitTarget(
    target: ManagedHostTarget.Running,
    processes: (SelectedIdeLaunch.Resolved) -> ManagedProcessInventory,
): TargetReadmission =
    when (val current = processes(target.launch)) {
        ManagedProcessInventory.Unavailable ->
            TargetReadmission.Rejected(ManagedHostFailure.PROCESS_IDENTITY_UNAVAILABLE)
        is ManagedProcessInventory.Captured ->
            if (current.processes == listOf(target.process)) TargetReadmission.Current
            else TargetReadmission.Rejected(ManagedHostFailure.CONCURRENT_CHANGE)
    }

private fun matchesManagedHostResult(command: IdeLifecycleCommand, result: IdeLifecycleResult): Boolean =
    when (command) {
        is IdeLifecycleCommand.SettleHost ->
            when (result) {
                is IdeLifecycleResult.HostSettled ->
                    result.host == command.host && result.transaction == command.transaction
                is IdeLifecycleResult.HostSettling ->
                    result.host == command.host && result.transaction == command.transaction
                else -> false
            }
        is IdeLifecycleCommand.StopHost ->
            result is IdeLifecycleResult.HostStopRequested &&
                result.host == command.host &&
                result.transaction == command.transaction
        else -> false
    }

private fun exchangeSelectedHost(
    home: Path,
    idea: Path,
    selection: ConfigurationPathSelection,
    command: IdeLifecycleCommand,
): IdeLifecycleResult =
    when (selection) {
        // Only a selected managed Host is managed; a default IDEA is never a Kast Host.
        ConfigurationPathSelection.OwnerDefault ->
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.SELECTED_IDE_UNAVAILABLE)
        is ConfigurationPathSelection.Selected ->
            // Management exchanges inspect, settle and stop; they never launch a Host.
            IdeLifecycleClient(
                    home,
                    idea,
                    deployment =
                        IdeLifecycleDeployment.Headless(
                            selection.path,
                            HostIdleShutdown.Disabled,
                            HostHeapPolicy.Inherit,
                        ),
                )
                .manageInstallation(command)
    }
