package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

internal class ConnectionRecoveryTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun corruptPreferencesAndCheckpointRecoverFromRecordedDirectory() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI)
        val directory = temporary.resolve("recorded pi")
        connectHarness(root, home, HarnessConnection.PI, directory = admittedConnectionDirectory(directory.toString()))
        Files.writeString(connectionConfigurationPath(home), "invalid config")
        Files.writeString(connectionCheckpointPath(home), "invalid checkpoint")
        val evidence = mutableListOf<ConnectionRecoveryEvidence>()
        assertTrue(connectHarness(root, home, HarnessConnection.PI, recoveryEvidence = { evidence.add(it) }))
        val recovered = readConnectionConfiguration(home) as ConnectionConfigurationRead.Read
        assertEquals(directory, recovered.configuration.directory(HarnessConnection.PI)?.path)
        assertEquals(
            ConnectionRecoveryEvidence.ConfigurationRebuilt(connectionConfigurationPath(home)),
            evidence.last(),
        )
        assertTrue(Files.exists(directory.resolve("extensions/kast.ts")))
    }

    @Test
    fun invalidUtf8ConfigurationUsesCheckpointWithoutInventingADirectory() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI)
        val directory = temporary.resolve("retainedUTF")
        connectHarness(root, home, HarnessConnection.PI, directory = admittedConnectionDirectory(directory.toString()))
        val config = connectionConfigurationPath(home)
        val text = Files.readString(config)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val offset = text.indexOf("retainedUTF")
        assertTrue(offset >= 0)
        bytes[offset] = 0x80.toByte()
        Files.write(config, bytes)
        val recovered = readConnectionConfiguration(home)
        assertTrue(recovered is ConnectionConfigurationRead.Recovered)
        val configuration = (recovered as ConnectionConfigurationRead.Recovered).configuration
        assertEquals(directory, configuration.directory(HarnessConnection.PI)?.path)
    }

    @Test
    fun explicitDestinationRebuildsUnrecoverablePreferencesAndRetainsRejectedBytes() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI)
        val config = connectionConfigurationPath(home)
        Files.createDirectories(config.parent)
        Files.writeString(config, "invalid config")
        val receipt = Files.readAllBytes(receiptPath(root)).toList()
        assertThrows<ManagementRejected> { connectHarness(root, home, HarnessConnection.PI) }
        assertEquals(receipt, Files.readAllBytes(receiptPath(root)).toList())
        assertEquals("invalid config", Files.readString(config))
        val evidence = mutableListOf<ConnectionRecoveryEvidence>()
        val directory = temporary.resolve("chosen")
        connectHarness(
            root,
            home,
            HarnessConnection.PI,
            directory = admittedConnectionDirectory(directory.toString()),
            recoveryEvidence = { evidence.add(it) },
        )
        val backup = evidence.filterIsInstance<ConnectionRecoveryEvidence.BackupRetained>().single()
        assertEquals("invalid config", Files.readString(backup.backup))
        assertEquals(ConnectionRecoveryEvidence.ConfigurationRebuilt(config), evidence.last())
        assertTrue(Files.exists(directory.resolve("extensions/kast.ts")))
    }

    @Test
    fun concurrentPreferenceChangeRejectsWithoutOverwritingProtectedState() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI)
        connectHarness(root, home, HarnessConnection.PI)
        val config = connectionConfigurationPath(home)
        val original = Files.readString(config)
        val receipt = Files.readAllBytes(receiptPath(root)).toList()
        val directory = temporary.resolve("next")
        val failure =
            assertThrows<ManagementRejected> {
                connectHarness(
                    root,
                    home,
                    HarnessConnection.PI,
                    directory = admittedConnectionDirectory(directory.toString()),
                ) { _, _ ->
                    Files.writeString(config, "concurrent preference owner")
                    throw java.io.IOException("case-owned receipt failure")
                }
            }
        assertEquals("connect-recovery", failure.stage)
        assertEquals("concurrent preference owner", Files.readString(config))
        val backups =
            Files.list(config.parent).use { paths ->
                paths.filter { it.toString().endsWith(".prior") }.toList()
            }
        assertTrue(backups.any { Files.readString(it) == original })
        assertEquals(receipt, Files.readAllBytes(receiptPath(root)).toList())
        assertFalse(Files.exists(directory.resolve("extensions/kast.ts")))
        assertTrue(Files.exists(home.resolve(".pi/agent/extensions/kast.ts")))
    }
}
