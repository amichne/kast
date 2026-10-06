package io.github.amichne.kast.distribution.cli

import java.nio.file.Path

internal sealed interface ConnectionRecoveryEvidence {
    data class BackupRetained(val destination: Path, val backup: Path) : ConnectionRecoveryEvidence

    data class ConfigurationRecovered(val checkpoint: Path) : ConnectionRecoveryEvidence

    data class ConfigurationRebuilt(val destination: Path) : ConnectionRecoveryEvidence

    data class TransactionRestored(val connection: HarnessConnection) : ConnectionRecoveryEvidence
}

internal fun printConnectionRecovery(evidence: ConnectionRecoveryEvidence) {
    val description =
        when (evidence) {
            is ConnectionRecoveryEvidence.BackupRetained ->
                "replaced " + evidence.destination + "; previous bytes retained at " + evidence.backup
            is ConnectionRecoveryEvidence.ConfigurationRecovered ->
                "configuration recovered from " + evidence.checkpoint
            is ConnectionRecoveryEvidence.ConfigurationRebuilt -> "configuration rebuilt at " + evidence.destination
            is ConnectionRecoveryEvidence.TransactionRestored ->
                evidence.connection.publicName +
                    " transaction rejected; exact prior connection, configuration and receipt restored"
        }
    System.err.println("kast: connection-recovery: $description")
}
