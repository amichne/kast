package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** The user login entry is the same persistent service job that launchctl starts now. */
internal enum class ServiceLoginAgentObservation {
    ABSENT,
    SERVICE,
    LEGACY_BOOTSTRAP,
    REJECTED,
}

internal enum class ServiceLoginAgentChange {
    READY,
    REJECTED,
}

internal object ServiceLoginAgent {
    fun path(command: BrokerServiceLaunchCommand): Path =
        command.userHome.resolve("Library/LaunchAgents/${command.serviceLabel.value}.login.plist")

    fun observe(command: BrokerServiceLaunchCommand): ServiceLoginAgentObservation {
        val agent = path(command)
        return when (
            LegacyLoginBootstrap.observe(agent, command.userHome, BrokerLaunchdServiceDocument.render(command))
        ) {
            LegacyLoginBootstrapObservation.Absent -> ServiceLoginAgentObservation.ABSENT
            LegacyLoginBootstrapObservation.Exact -> ServiceLoginAgentObservation.SERVICE
            LegacyLoginBootstrapObservation.Rejected ->
                if (
                    LegacyLoginBootstrap.observe(agent, command.userHome, LegacyLoginBootstrap.render(command)) ==
                        LegacyLoginBootstrapObservation.Exact
                )
                    ServiceLoginAgentObservation.LEGACY_BOOTSTRAP
                else ServiceLoginAgentObservation.REJECTED
        }
    }

    fun publish(command: BrokerServiceLaunchCommand): ServiceLoginAgentChange =
        try {
            if (PublishedBrokerServiceCommand.observePrivate(command) != PublishedPrivateDocumentObservation.EXACT)
                return ServiceLoginAgentChange.REJECTED
            val before = observe(command)
            if (before == ServiceLoginAgentObservation.REJECTED) return ServiceLoginAgentChange.REJECTED
            if (before == ServiceLoginAgentObservation.SERVICE) return ServiceLoginAgentChange.READY
            val agent = path(command)
            Files.createDirectories(agent.parent)
            val temporary = Files.createTempFile(agent.parent, ".kast-service-", ".plist")
            try {
                Files.writeString(temporary, BrokerLaunchdServiceDocument.render(command))
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
                if (observe(command) != before) return ServiceLoginAgentChange.REJECTED
                Files.move(temporary, agent, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
            if (observe(command) == ServiceLoginAgentObservation.SERVICE) ServiceLoginAgentChange.READY
            else ServiceLoginAgentChange.REJECTED
        } catch (_: Exception) {
            ServiceLoginAgentChange.REJECTED
        }

    fun remove(command: BrokerServiceLaunchCommand): ServiceLoginAgentChange =
        try {
            when (observe(command)) {
                ServiceLoginAgentObservation.ABSENT -> ServiceLoginAgentChange.READY
                ServiceLoginAgentObservation.SERVICE,
                ServiceLoginAgentObservation.LEGACY_BOOTSTRAP -> {
                    Files.delete(path(command))
                    ServiceLoginAgentChange.READY
                }
                ServiceLoginAgentObservation.REJECTED -> ServiceLoginAgentChange.REJECTED
            }
        } catch (_: Exception) {
            ServiceLoginAgentChange.REJECTED
        }
}
