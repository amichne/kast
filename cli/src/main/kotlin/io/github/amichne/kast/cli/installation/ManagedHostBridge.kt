package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.ide.IdeLifecycleClient
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Path

private sealed interface ManagedHostTarget {
    data class Stopped(val launch: SelectedIdeLaunch.Resolved, val profile: Path) : ManagedHostTarget

    data class Running(val launch: SelectedIdeLaunch.Resolved, val profile: Path, val process: ManagedIdeProcess) :
        ManagedHostTarget

    data class Rejected(val reason: ManagedHostFailure) : ManagedHostTarget
}

internal fun executeManagedHost(
    request: ManagedHostRequest,
    home: Path,
    processes: (SelectedIdeLaunch.Resolved) -> ManagedProcessInventory = ::observeManagedIdeProcesses,
    exchange: (Path, IdeLifecycleCommand) -> IdeLifecycleResult = { idea, command ->
        val client = IdeLifecycleClient(home, idea)
        if (command == IdeLifecycleCommand.Inspect) client.inspectForInstallation()
        else client.manageInstallation(command)
    },
    start: (SelectedIdeLaunch.Resolved) -> ManagedIdeLaunchOutcome = ::startManagedIde,
    inspectLegacy: (SelectedIdeLaunch.Resolved, ManagedIdeProcess) -> LegacyProfileRead = ::inspectLegacyHostProfile,
    stopLegacy: (SelectedIdeLaunch.Resolved, ManagedIdeProcess) -> LegacyQuitOutcome = ::stopLegacyHostNormally,
): ManagedHostResponse =
    when (val target = selectManagedHostTarget(request, processes)) {
        is ManagedHostTarget.Rejected -> ManagedHostResponse.Rejected(target.reason)
        is ManagedHostTarget.Stopped ->
            when (request) {
                is ManagedHostRequest.Start -> startStoppedHost(target, home, start)
                is ManagedHostRequest.Inspect -> ManagedHostResponse.Inspected(emptyList())
                else -> ManagedHostResponse.Rejected(ManagedHostFailure.CONCURRENT_CHANGE)
            }
        is ManagedHostTarget.Running ->
            when (request) {
                is ManagedHostRequest.Start -> ManagedHostResponse.Rejected(ManagedHostFailure.CONCURRENT_CHANGE)
                is ManagedHostRequest.LegacyStop -> stopPriorHost(target, request, processes, inspectLegacy, stopLegacy)
                is ManagedHostRequest.Inspect -> inspectRunningHost(target, request, processes, exchange, inspectLegacy)
                is ManagedHostRequest.Settle,
                is ManagedHostRequest.Resume,
                is ManagedHostRequest.Stop,
                is ManagedHostRequest.Refresh -> changeRunningHost(target, request, processes, exchange)
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

private fun startStoppedHost(
    target: ManagedHostTarget.Stopped,
    home: Path,
    start: (SelectedIdeLaunch.Resolved) -> ManagedIdeLaunchOutcome,
): ManagedHostResponse {
    val profile =
        when (val selected = SelectedIdeInstallation.pluginRoot(target.launch, home)) {
            is Refinement.Refined -> selected.value.value.parent
            is Refinement.Rejected -> return ManagedHostResponse.Rejected(ManagedHostFailure.PROFILE_MISMATCH)
        }
    if (profile != target.profile) return ManagedHostResponse.Rejected(ManagedHostFailure.PROFILE_MISMATCH)
    return when (start(target.launch)) {
        ManagedIdeLaunchOutcome.REQUESTED -> ManagedHostResponse.StartRequested
        ManagedIdeLaunchOutcome.REJECTED -> ManagedHostResponse.Rejected(ManagedHostFailure.LAUNCH_REJECTED)
    }
}

private fun stopPriorHost(
    target: ManagedHostTarget.Running,
    request: ManagedHostRequest.LegacyStop,
    processes: (SelectedIdeLaunch.Resolved) -> ManagedProcessInventory,
    inspect: (SelectedIdeLaunch.Resolved, ManagedIdeProcess) -> LegacyProfileRead,
    stop: (SelectedIdeLaunch.Resolved, ManagedIdeProcess) -> LegacyQuitOutcome,
): ManagedHostResponse {
    if (request.process != target.process || !Regex("[A-Za-z0-9._:-]{1,128}").matches(request.transaction))
        return ManagedHostResponse.Rejected(ManagedHostFailure.CONCURRENT_CHANGE)
    val prior =
        when (val captured = inspect(target.launch, target.process)) {
            is LegacyProfileRead.Captured -> captured.profile
            LegacyProfileRead.Unavailable -> return ManagedHostResponse.Rejected(ManagedHostFailure.PROFILE_UNPROVEN)
        }
    if (!matchesLegacyProfile(target, prior)) return ManagedHostResponse.Rejected(ManagedHostFailure.PROFILE_MISMATCH)
    when (val current = readmitTarget(target, processes)) {
        TargetReadmission.Current -> Unit
        is TargetReadmission.Rejected -> return ManagedHostResponse.Rejected(current.reason)
    }
    return legacyQuitResponse(stop(target.launch, target.process), target.process, request)
}

private fun legacyQuitResponse(
    outcome: LegacyQuitOutcome,
    process: ManagedIdeProcess,
    request: ManagedHostRequest.LegacyStop,
): ManagedHostResponse {
    return when (outcome) {
        LegacyQuitOutcome.REQUESTED -> ManagedHostResponse.LegacyStopRequested(process, request.transaction)
        LegacyQuitOutcome.REJECTED -> ManagedHostResponse.Rejected(ManagedHostFailure.HOST_REJECTED)
        LegacyQuitOutcome.PROCESS_IDENTITY_CHANGED -> ManagedHostResponse.Rejected(ManagedHostFailure.CONCURRENT_CHANGE)
        LegacyQuitOutcome.HELPER_START_FAILED ->
            ManagedHostResponse.Rejected(ManagedHostFailure.LEGACY_QUIT_START_FAILED)
        LegacyQuitOutcome.HELPER_TIMED_OUT -> ManagedHostResponse.Rejected(ManagedHostFailure.LEGACY_QUIT_TIMED_OUT)
        LegacyQuitOutcome.HELPER_CAPTURE_FAILED ->
            ManagedHostResponse.Rejected(ManagedHostFailure.LEGACY_QUIT_CAPTURE_FAILED)
        LegacyQuitOutcome.HELPER_OUTPUT_LIMIT_EXCEEDED ->
            ManagedHostResponse.Rejected(ManagedHostFailure.LEGACY_QUIT_OUTPUT_LIMIT_EXCEEDED)
        LegacyQuitOutcome.HELPER_INTERRUPTED -> ManagedHostResponse.Rejected(ManagedHostFailure.LEGACY_QUIT_INTERRUPTED)
        LegacyQuitOutcome.HELPER_EXIT_FAILED -> ManagedHostResponse.Rejected(ManagedHostFailure.LEGACY_QUIT_EXIT_FAILED)
        LegacyQuitOutcome.HELPER_RESPONSE_INVALID ->
            ManagedHostResponse.Rejected(ManagedHostFailure.LEGACY_QUIT_RESPONSE_INVALID)
    }
}

private fun inspectRunningHost(
    target: ManagedHostTarget.Running,
    request: ManagedHostRequest.Inspect,
    processes: (SelectedIdeLaunch.Resolved) -> ManagedProcessInventory,
    exchange: (Path, IdeLifecycleCommand) -> IdeLifecycleResult,
    inspectLegacy: (SelectedIdeLaunch.Resolved, ManagedIdeProcess) -> LegacyProfileRead,
): ManagedHostResponse {
    val inspected = exchange(Path.of(target.launch.home), IdeLifecycleCommand.Inspect)
    if (inspected is IdeLifecycleResult.Inspected && inspected.runtimeIdentity != null) {
        return if (matchesRuntimeTarget(target, inspected))
            ManagedHostResponse.Inspected(listOf(target.process), inspected)
        else ManagedHostResponse.Rejected(ManagedHostFailure.HOST_IDENTITY_MISMATCH, inspected)
    }
    if (request.legacyProfileInspection == LegacyProfileInspection.REJECT_UNPROVEN)
        return ManagedHostResponse.Inspected(listOf(target.process), inspected)
    val legacy =
        when (val captured = inspectLegacy(target.launch, target.process)) {
            is LegacyProfileRead.Captured -> captured.profile
            LegacyProfileRead.Unavailable ->
                return ManagedHostResponse.Rejected(ManagedHostFailure.PROFILE_UNPROVEN, inspected)
        }
    if (!matchesLegacyProfile(target, legacy))
        return ManagedHostResponse.Rejected(ManagedHostFailure.PROFILE_MISMATCH, inspected)
    when (val current = readmitTarget(target, processes)) {
        TargetReadmission.Current -> Unit
        is TargetReadmission.Rejected -> return ManagedHostResponse.Rejected(current.reason, inspected)
    }
    return ManagedHostResponse.Inspected(listOf(target.process), inspected, legacy)
}

private fun changeRunningHost(
    target: ManagedHostTarget.Running,
    request: ManagedHostRequest,
    processes: (SelectedIdeLaunch.Resolved) -> ManagedProcessInventory,
    exchange: (Path, IdeLifecycleCommand) -> IdeLifecycleResult,
): ManagedHostResponse {
    val idea = Path.of(target.launch.home)
    val inspected = exchange(idea, IdeLifecycleCommand.Inspect)
    if (inspected !is IdeLifecycleResult.Inspected)
        return ManagedHostResponse.Rejected(ManagedHostFailure.HOST_REJECTED, inspected)
    if (!matchesRuntimeTarget(target, inspected))
        return ManagedHostResponse.Rejected(ManagedHostFailure.HOST_IDENTITY_MISMATCH, inspected)
    val command =
        when (request) {
            is ManagedHostRequest.Settle -> IdeLifecycleCommand.SettleHost(request.host, request.transaction)
            is ManagedHostRequest.Resume -> IdeLifecycleCommand.ResumeHost(request.host, request.transaction)
            is ManagedHostRequest.Stop -> IdeLifecycleCommand.StopHost(request.host, request.transaction)
            is ManagedHostRequest.Refresh -> IdeLifecycleCommand.RefreshHostBundle(request.host, request.transaction)
            else -> return ManagedHostResponse.Rejected(ManagedHostFailure.INVALID_REQUEST)
        }
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
        is IdeLifecycleResult.HostRefreshRequested ->
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

private fun matchesLegacyProfile(target: ManagedHostTarget.Running, profile: LegacyHostProfile): Boolean =
    profile.ideaHome == target.launch.home &&
        profile.profile == target.profile.toString() &&
        profile.process == target.process

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
        is IdeLifecycleCommand.ResumeHost ->
            result is IdeLifecycleResult.HostResumed &&
                result.host == command.host &&
                result.transaction == command.transaction
        is IdeLifecycleCommand.StopHost ->
            result is IdeLifecycleResult.HostStopRequested &&
                result.host == command.host &&
                result.transaction == command.transaction
        is IdeLifecycleCommand.RefreshHostBundle ->
            result is IdeLifecycleResult.HostRefreshRequested &&
                result.host == command.host &&
                result.transaction == command.transaction
        else -> false
    }
