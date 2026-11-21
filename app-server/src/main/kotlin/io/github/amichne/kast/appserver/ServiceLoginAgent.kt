package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** The user login entry is the same persistent service job that launchctl starts now. */
internal enum class ServiceLoginAgentObservation {
    ABSENT,
    SERVICE,
    REJECTED,
}

internal enum class ServiceLoginAgentChange {
    READY,
    REJECTED,
}

internal object ServiceLoginAgent {
    fun path(command: BrokerServiceLaunchCommand): Path =
        command.userHome.resolve("Library/LaunchAgents/${command.serviceLabel.value}.login.plist")

    fun observe(command: BrokerServiceLaunchCommand): ServiceLoginAgentObservation =
        when (
            OwnedLaunchAgentFile.observe(
                path(command),
                command.userHome,
                BrokerLaunchdServiceDocument.render(command, BrokerLaunchdStart.LOGIN),
            )
        ) {
            OwnedLaunchAgentObservation.Absent -> ServiceLoginAgentObservation.ABSENT
            OwnedLaunchAgentObservation.Exact -> ServiceLoginAgentObservation.SERVICE
            OwnedLaunchAgentObservation.Rejected -> ServiceLoginAgentObservation.REJECTED
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
                Files.writeString(temporary, BrokerLaunchdServiceDocument.render(command, BrokerLaunchdStart.LOGIN))
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
                ServiceLoginAgentObservation.SERVICE -> {
                    Files.delete(path(command))
                    ServiceLoginAgentChange.READY
                }
                ServiceLoginAgentObservation.REJECTED -> ServiceLoginAgentChange.REJECTED
            }
        } catch (_: Exception) {
            ServiceLoginAgentChange.REJECTED
        }
}
