package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.endpoint.InstalledUpstreamDirectories
import java.nio.file.Path

/** Only the managed server argument vectors and exact installation upstream establish reset scope. */
internal object ResetCodexListener {
    fun selects(command: Path, arguments: List<String>, root: Path): Boolean {
        if (!command.isAbsolute || command.normalize() != command || command.fileName.toString() != "codex")
            return false
        val upstream = root.resolve("installation/state/run/u.sock")
        val transports = setOf("unix://$upstream", "unix://${InstalledUpstreamDirectories.transportPath(upstream)}")
        val prefix =
            when {
                arguments.take(3) == listOf("-c", "features.code_mode_host=true", "app-server") -> 3
                arguments.firstOrNull() == "app-server" -> 1
                else -> return false
            }
        val role = arguments.drop(prefix)
        val listener = if (role.firstOrNull() == "--analytics-default-enabled") role.drop(1) else role
        return listener.size == 2 && listener[0] == "--listen" && listener[1] in transports
    }
}
