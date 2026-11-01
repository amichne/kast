package io.github.amichne.kast.distribution.cli

import java.nio.file.Path

/** A saved Codex registration selects plugin migration, never a transport registration. */
internal fun reconnectHarness(root: Path, home: Path, connection: HarnessConnection, environment: Map<String, String>) {
    when (connection) {
        HarnessConnection.CODEX_MCP,
        HarnessConnection.CODEX_APP_SERVER ->
            when (
                val outcome =
                    installCodexPlugin(
                        root,
                        home,
                        codexHome =
                            admittedConnectionDirectory(environment["CODEX_HOME"] ?: home.resolve(".codex").toString())
                                .path,
                    )
            ) {
                is PluginInstallOutcome.Installed,
                is PluginInstallOutcome.AlreadyInstalled -> Unit
                is PluginInstallOutcome.Rejected,
                is PluginInstallOutcome.RecoveryRequired ->
                    throw ManagementRejected("plugin-migration", outcome.asJson())
            }
        HarnessConnection.PI,
        HarnessConnection.COPILOT -> connectHarness(root, home, connection)
    }
}
