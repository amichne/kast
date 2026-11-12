package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch

internal fun observeManagedIdeProcesses(launch: SelectedIdeLaunch.Resolved): ManagedProcessInventory {
    val counts = ManagedProcessCounts()
    return try {
        captureManagedIdeProcesses(launch, counts)
    } catch (_: SecurityException) {
        counts.report(ManagedProcessInspectionOutcome.SECURITY_REJECTED)
        ManagedProcessInventory.Unavailable
    }
}

private fun captureManagedIdeProcesses(
    launch: SelectedIdeLaunch.Resolved,
    counts: ManagedProcessCounts,
): ManagedProcessInventory {
    val user = System.getProperty("user.name")
    val snapshots = mutableListOf<ManagedIdeProcess>()
    val handles = ProcessHandle.allProcesses().use { processes -> processes.limit(MAXIMUM_PROCESS_COUNT + 1).toList() }
    if (handles.size > MAXIMUM_PROCESS_COUNT) {
        counts.report(ManagedProcessInspectionOutcome.ENTRY_LIMIT_EXCEEDED)
        return ManagedProcessInventory.Unavailable
    }
    for (handle in handles) {
        counts.examined++
        when (val captured = captureManagedProcess(handle, launch.executable, user, counts)) {
            ManagedProcessCapture.OtherUser -> counts.otherUsers++
            ManagedProcessCapture.OtherExecutable -> counts.otherExecutables++
            ManagedProcessCapture.Exited -> counts.exited++
            ManagedProcessCapture.Unavailable,
            is ManagedProcessCapture.NeedsState -> {
                counts.report(ManagedProcessInspectionOutcome.METADATA_UNAVAILABLE)
                return ManagedProcessInventory.Unavailable
            }
            is ManagedProcessCapture.Captured -> {
                counts.selected++
                snapshots += captured.process
            }
        }
    }
    counts.report(ManagedProcessInspectionOutcome.CAPTURED)
    return ManagedProcessInventory.Captured(snapshots.sortedBy { it.pid })
}

private fun captureManagedProcess(
    handle: ProcessHandle,
    executable: String,
    user: String,
    counts: ManagedProcessCounts,
): ManagedProcessCapture {
    val info = handle.info()
    val metadata =
        ManagedProcessMetadata(
            pid = handle.pid(),
            alive = handle.isAlive,
            owner = info.user().orElse(null),
            executable = info.command().orElse(null),
            started = info.startInstant().map { it.toEpochMilli() }.orElse(null),
        )
    val selected = admitManagedProcess(metadata, executable, user)
    if (selected !is ManagedProcessCapture.NeedsState) return selected
    counts.stateInspections++
    return admitMissingManagedExecutable(observeManagedProcessState(selected.pid))
}

private const val MAXIMUM_PROCESS_COUNT = 8_192L
