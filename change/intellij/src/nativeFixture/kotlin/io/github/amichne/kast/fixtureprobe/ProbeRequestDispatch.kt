package io.github.amichne.kast.fixtureprobe

internal interface ProbeDispatchPorts {
    fun executeNative(request: ProbeRequest): ProbeExecution

    fun awaitReadiness(request: ProbeRequest): ProbeExecution

    fun controlIndexing(request: ProbeRequest): ProbeExecution

    fun reimport(request: ProbeRequest): ProbeExecution
}

internal object ProbeRequestDispatch {
    fun dispatch(request: ProbeRequest, ports: ProbeDispatchPorts): ProbeExecution =
        when (request.command) {
            ProbeCommand.HOLD_INDEXING,
            ProbeCommand.RELEASE_INDEXING -> ports.controlIndexing(request)
            ProbeCommand.REIMPORT_GRADLE -> ports.reimport(request)
            ProbeCommand.AWAIT_SETUP_READY,
            ProbeCommand.AWAIT_REOPEN_READY -> ports.awaitReadiness(request)
            ProbeCommand.OBSERVE,
            ProbeCommand.DIRTY_UNCOMMITTED,
            ProbeCommand.COMMIT_DOCUMENT,
            ProbeCommand.RESTORE_SAVED,
            ProbeCommand.UNDO_PRODUCTION_CHANGE,
            ProbeCommand.ARM_POST_SAVE_BARRIER,
            ProbeCommand.UNLOAD_PRODUCTION_PLUGIN -> ports.executeNative(request)
        }
}
