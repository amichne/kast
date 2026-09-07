package io.github.amichne.kast.cli.broker.host

import io.github.amichne.kast.cli.broker.CodexIntegrationRun
import io.github.amichne.kast.cli.broker.runOwnedCodexClient
import io.github.amichne.kast.cli.broker.host.admission.CodexClientArguments
import io.github.amichne.kast.cli.broker.host.admission.UpstreamCodexExecutable
import java.nio.file.Path

/** Existing TUI projection: one real Codex client reaches the shared broker through `--remote`. */
internal class CliRemoteClientHost(
    private val codexExecutable: UpstreamCodexExecutable,
    private val publicSocket: Path,
    private val arguments: CodexClientArguments,
) : CodexIntegrationHost {
    override suspend fun run(
        closeIntegration: suspend () -> Unit,
    ): CodexIntegrationRun = runOwnedCodexClient(
        closeServer = closeIntegration,
        startClient = {
            ProcessBuilder(
                listOf(
                    codexExecutable.path.toString(),
                    "--remote",
                    "unix://$publicSocket",
                ) + arguments.values,
            ).inheritIO().start()
        },
    )
}
