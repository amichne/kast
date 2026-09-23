package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ServiceLoginAgentTest {
    @Test
    fun `ready private service publishes one exact login job and removes only that job`(@TempDir temporary: Path) {
        val command = command(temporary)
        val agent = ServiceLoginAgent.path(command)
        assertEquals(ServiceLoginAgentObservation.ABSENT, ServiceLoginAgent.observe(command))
        assertEquals(ServiceLoginAgentChange.REJECTED, ServiceLoginAgent.publish(command))
        assertFalse(Files.exists(agent))

        publishReceipt(command)
        assertEquals(PublishedPrivateDocumentObservation.EXACT, PublishedBrokerServiceCommand.observePrivate(command))
        assertEquals(ServiceLoginAgentChange.READY, ServiceLoginAgent.publish(command))
        assertEquals(BrokerLaunchdServiceDocument.render(command, BrokerLaunchdStart.LOGIN), Files.readString(agent))
        assertEquals(ServiceLoginAgentObservation.SERVICE, ServiceLoginAgent.observe(command))
        assertEquals(ServiceLoginAgentChange.READY, ServiceLoginAgent.publish(command))

        Files.writeString(agent, Files.readString(agent).replace("<integer>10</integer>", "<integer>1</integer>"))
        assertEquals(ServiceLoginAgentObservation.REJECTED, ServiceLoginAgent.observe(command))
        assertEquals(ServiceLoginAgentChange.REJECTED, ServiceLoginAgent.remove(command))
        assertEquals(ServiceLoginAgentChange.REJECTED, ServiceLoginAgent.publish(command))
        assertEquals(true, Files.exists(agent))
    }

    @Test
    fun `owned legacy bootstrap converges to direct service login job`(@TempDir temporary: Path) {
        val command = command(temporary)
        val agent = ServiceLoginAgent.path(command)
        val legacy =
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<!-- Kast App Server login bootstrap v1 -->
<plist version="1.0"><dict><key>Label</key><string>${command.serviceLabel.value}.login</string><key>ProgramArguments</key><array><string>${command.kast}</string><string>app-server</string><string>bootstrap</string></array><key>RunAtLoad</key><true/><key>EnvironmentVariables</key><dict><key>PATH</key><string>${command.executableSearchPath.value}</string><key>CODEX_HOME</key><string>${command.codexHome}</string></dict></dict></plist>
"""
        assertEquals(legacy, LegacyLoginBootstrap.render(command))
        Files.createDirectories(agent.parent)
        Files.writeString(agent, legacy)
        Files.setPosixFilePermissions(agent, PosixFilePermissions.fromString("rw-------"))
        assertEquals(ServiceLoginAgentObservation.LEGACY_BOOTSTRAP, ServiceLoginAgent.observe(command))

        publishReceipt(command)
        assertEquals(ServiceLoginAgentChange.READY, ServiceLoginAgent.publish(command))
        assertEquals(ServiceLoginAgentObservation.SERVICE, ServiceLoginAgent.observe(command))
        assertEquals(ServiceLoginAgentChange.READY, ServiceLoginAgent.remove(command))
        assertFalse(Files.exists(agent))
    }

    private fun command(temporary: Path): BrokerServiceLaunchCommand {
        val product = Files.createDirectory(temporary.resolve("product")).toRealPath()
        val bin = Files.createDirectory(product.resolve("bin"))
        val kast = executable(bin.resolve("kast"))
        executable(Files.createDirectories(product.resolve("share/kast/libexec")).resolve("kast-daemon"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val emptyBin = Files.createDirectory(temporary.resolve("empty-bin"))
        return (BrokerServiceLaunchCommand.resolveCoordinator(kast, home, mapOf("PATH" to emptyBin.toString()))
                as BrokerServiceLaunchCommandResolution.Resolved)
            .command
    }

    private fun publishReceipt(command: BrokerServiceLaunchCommand) {
        Files.createDirectories(command.stateDirectory)
        Files.writeString(command.stateDirectory.resolve("service.plist"), BrokerLaunchdServiceDocument.render(command))
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }
}
