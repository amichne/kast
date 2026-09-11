package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PublishedBrokerServiceCommandTest {
    @Test
    fun `published command retains the selected launcher path for exact recovery`() {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-published-launcher-").toRealPath()
        try {
            listOf("bin", "lib", "share", "tools", "upstream").forEach { Files.createDirectory(root.resolve(it)) }
            val kast = executable(root.resolve("bin/kast"))
            val upstream = executable(root.resolve("upstream/codex.js"))
            val launcher = Files.createSymbolicLink(root.resolve("tools/codex"), upstream)
            val environment =
                mapOf(
                    "HOME" to root.toString(),
                    "PATH" to root.resolve("tools").toString(),
                    "CODEX_HOME" to root.resolve("codex").toString(),
                    "KAST_ENABLE_APP_SERVER" to "1",
                )
            val command =
                (BrokerServiceLaunchCommand.resolveCoordinator(kast, root, environment)
                        as BrokerServiceLaunchCommandResolution.Resolved)
                    .command
            Files.createDirectories(command.stateDirectory)
            val arguments =
                listOf("/usr/bin/env", "-i") +
                    listOf(
                        "HOME=${command.userHome}",
                        "PATH=${command.executableSearchPath.value}",
                        "JAVA_HOME=${command.javaHome}",
                        "KAST_OPTS=${command.jvmUserHomeOption.value}",
                        "CODEX_HOME=${command.codexHome}",
                    ) +
                    command.host.environment().map { (key, value) -> "$key=$value" } +
                    command.childEnvironment.assignments +
                    listOf(
                        "KAST_ENABLE_APP_SERVER=1",
                        "KAST_APP_SERVER_TOOLS=${command.toolSelection.environmentValue}",
                        "BROKER_SERVICE_IDENTITY=${command.identity.value}",
                        "BROKER_READINESS_FILE=${command.readinessFile}",
                        command.kast.toString(),
                        "broker",
                        "serve",
                    )
            Files.writeString(command.stateDirectory.resolve("service.plist"), servicePlist(command, arguments))

            assertEquals(launcher, (command.host as BrokerHostSelection.Selected).executable.launcherPath)
            assertEquals(command.identity, PublishedBrokerServiceCommand.recover(command)?.identity)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `owned launch receipt recovers only its exact published command`() {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-published-command-").toRealPath()
        try {
            listOf("bin", "lib", "share").forEach { Files.createDirectory(root.resolve(it)) }
            val kast = Files.writeString(root.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
            val environment =
                mapOf(
                    "HOME" to root.toString(),
                    "PATH" to "/usr/bin:/bin",
                    "CODEX_HOME" to root.resolve("codex").toString(),
                    "KAST_ENABLE_APP_SERVER" to "0",
                )
            val command =
                (BrokerServiceLaunchCommand.resolveCoordinator(kast, root, environment)
                        as BrokerServiceLaunchCommandResolution.Resolved)
                    .command
            Files.createDirectories(command.stateDirectory)
            val arguments =
                listOf("/usr/bin/env", "-i") +
                    listOf(
                        "HOME=${command.userHome}",
                        "PATH=${command.executableSearchPath.value}",
                        "JAVA_HOME=${command.javaHome}",
                        "KAST_OPTS=${command.jvmUserHomeOption.value}",
                        "CODEX_HOME=${command.codexHome}",
                    ) +
                    command.childEnvironment.assignments +
                    listOf(
                        "KAST_ENABLE_APP_SERVER=0",
                        "KAST_APP_SERVER_TOOLS=${command.toolSelection.environmentValue}",
                        "BROKER_SERVICE_IDENTITY=${command.identity.value}",
                        "BROKER_READINESS_FILE=${command.readinessFile}",
                        command.kast.toString(),
                        "broker",
                        "serve",
                    )
            val plist = command.stateDirectory.resolve("service.plist")
            Files.writeString(plist, servicePlist(command, arguments))

            assertEquals(command.identity, PublishedBrokerServiceCommand.recover(command)?.identity)

            Files.writeString(
                plist,
                servicePlist(
                    command,
                    arguments.map { argument ->
                        if (argument.startsWith("BROKER_SERVICE_IDENTITY=")) {
                            "BROKER_SERVICE_IDENTITY=sha256:${"0".repeat(64)}"
                        } else argument
                    },
                ),
            )
            assertNull(PublishedBrokerServiceCommand.recover(command))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun servicePlist(command: BrokerServiceLaunchCommand, arguments: List<String>): String =
        """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0"><dict><key>Label</key><string>${xml(command.serviceLabel.value)}</string>
        <key>ProgramArguments</key><array>${arguments.joinToString("") { "<string>${xml(it)}</string>" }}</array>
        </dict></plist>
        """
            .trimIndent()

    private fun xml(raw: String): String =
        raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }
}
