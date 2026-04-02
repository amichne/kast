package io.github.amichne.kast.cli

internal fun daemonNoteFor(result: WorkspaceStatusResult): String? {
    if (result.candidates.isEmpty()) {
        return null
    }

    val selected = result.selected ?: result.candidates.first()
    val liveCount = result.candidates.count(RuntimeCandidateStatus::pidAlive)
    val details = buildList {
        if (result.candidates.size > 1) {
            add("${result.candidates.size} descriptors registered")
        }
        if (liveCount > 1) {
            add("${liveCount} live daemons")
        }
    }

    val summary = "daemon: selected ${selected.describeDaemon()}"
    return if (details.isEmpty()) {
        summary
    } else {
        "$summary (${details.joinToString("; ")})"
    }
}

internal fun daemonNoteFor(result: WorkspaceEnsureResult): String? {
    val action = if (result.started) "started" else "using"
    val summary = "daemon: $action ${result.selected.describeDaemon()}"
    return if (result.started && result.logFile != null) {
        "$summary (log: ${result.logFile})"
    } else {
        summary
    }
}

internal fun daemonNoteFor(result: DaemonStopResult): String? {
    if (!result.stopped) {
        return null
    }
    return "daemon: stopped standalone daemon pid=${result.pid ?: "unknown"}"
}

internal fun daemonNoteForRuntime(runtime: RuntimeCandidateStatus): String =
    "daemon: using ${runtime.describeDaemon()}"

private fun RuntimeCandidateStatus.describeDaemon(): String {
    val endpoint = descriptor.socketPath
    val stateText = when {
        ready -> "ready"
        !pidAlive -> "stale"
        !reachable -> "starting"
        runtimeStatus?.indexing == true -> "indexing"
        runtimeStatus != null -> runtimeStatus.state.name.lowercase()
        else -> "unavailable"
    }
    val statusText = if (stateText == "ready") {
        "ready"
    } else {
        "is $stateText"
    }
    val message = runtimeStatus?.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: errorMessage
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    return buildString {
        append("${descriptor.backendName} daemon pid=${descriptor.pid} $statusText")
        append(" at $endpoint")
        if (message != null) {
            append(" — $message")
        }
    }
}
