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
        return renderJob(command.serviceLabel.value, arguments, command.serviceLog.toString())
    }

    fun renderSemantic(command: SemanticServiceLaunch): String =
        renderJob(command.label, command.arguments, command.log.toString(), command.installation.toString())

    private fun renderJob(label: String, arguments: List<String>, log: String, directory: String? = null): String {
        val supervision =
            if (directory == null)
                "<key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>" +
                    "<key>ThrottleInterval</key><integer>10</integer>"
            else
                "<key>WorkingDirectory</key><string>${xml(directory)}</string>" +
                    "<key>StandardInPath</key><string>/dev/null</string>"
        return """<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict><key>Label</key><string>${xml(label)}</string>
<key>ProgramArguments</key><array>${arguments.joinToString("") { "<string>${xml(it)}</string>" }}</array>
<key>RunAtLoad</key><true/>$supervision
<key>StandardOutPath</key><string>${xml(log)}</string>
<key>StandardErrorPath</key><string>${xml(log)}</string></dict></plist>
"""
    }

    private fun xml(raw: String) =
        raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
