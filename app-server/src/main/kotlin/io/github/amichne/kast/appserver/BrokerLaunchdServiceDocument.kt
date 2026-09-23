package io.github.amichne.kast.appserver

/** The exact launchd job published for a private daemon executable. */
internal enum class BrokerLaunchdStart {
    CURRENT_SESSION,
    LOGIN,
}

internal object BrokerLaunchdServiceDocument {
    fun render(
        command: BrokerServiceLaunchCommand,
        start: BrokerLaunchdStart = BrokerLaunchdStart.CURRENT_SESSION,
    ): String {
        fun xml(raw: String) =
            raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val environment =
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
                    "BROKER_SERVICE_IDENTITY=${command.identity.value}",
                    "BROKER_READINESS_FILE=${command.readinessFile}",
                )
        val daemon = listOf(command.daemonExecutable.toString())
        val arguments =
            listOf("/usr/bin/env", "-i") +
                environment +
                if (start == BrokerLaunchdStart.LOGIN) daemon + "--login" else daemon
        return """<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict><key>Label</key><string>${xml(command.serviceLabel.value)}</string>
<key>ProgramArguments</key><array>${arguments.joinToString("") { "<string>${xml(it)}</string>" }}</array>
<key>RunAtLoad</key><true/><key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
<key>ThrottleInterval</key><integer>10</integer>
<key>StandardOutPath</key><string>${xml(command.serviceLog.toString())}</string>
<key>StandardErrorPath</key><string>${xml(command.serviceLog.toString())}</string></dict></plist>
"""
    }
}
