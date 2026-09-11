package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.host.CodexClientLaunch
import io.github.amichne.kast.appserver.host.CodexClientLaunchRun
import io.github.amichne.kast.appserver.host.CodexClientLauncher

/** Launch outcome projection stays at the explicit external-process boundary. */
internal fun launchCodex(codexClientLauncher: CodexClientLauncher, client: CodexClientLaunch): CliExit =
    when (val run = codexClientLauncher.launch(client)) {
        is CodexClientLaunchRun.Completed -> CliExit.Delegated(run.exitCode)
        is CodexClientLaunchRun.Rejected ->
            boundaryExit(
                CliBoundaryExitStatus.RUNTIME,
                "codex-${run.failure.name.lowercase().replace('_', '-')}",
            )
    }
