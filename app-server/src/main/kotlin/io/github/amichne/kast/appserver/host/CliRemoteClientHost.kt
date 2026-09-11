package io.github.amichne.kast.appserver.host

import io.github.amichne.kast.appserver.CodexIntegrationRun
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.host.admission.CodexClientArguments
import io.github.amichne.kast.appserver.host.admission.UpstreamCodexExecutable
import io.github.amichne.kast.appserver.runOwnedCodexClient
import java.nio.file.Path

/** Existing TUI projection: one real Codex client reaches the shared broker through `--remote`. */
internal class CliRemoteClientHost(
    private val codexExecutable: UpstreamCodexExecutable,
    private val publicSocket: Path,
    private val arguments: CodexClientArguments,
    private val workingDirectory: CanonicalBrokerDirectory,
) : CodexIntegrationHost {
    override suspend fun run(closeIntegration: suspend () -> Unit): CodexIntegrationRun =
        runOwnedCodexClient(
            closeServer = closeIntegration,
            startClient = {
                ProcessBuilder(
                        listOf(codexExecutable.path.toString()) +
                            arguments.withOwnedConnection(
                                "unix://$publicSocket",
                                workingDirectory,
                            )
                    )
                    .inheritIO()
                    .start()
            },
        )
}
