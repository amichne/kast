package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import java.security.MessageDigest

internal sealed interface RegistrationPayload {
    val digest: String

    data class BundledFile(val source: Path, override val digest: String) : RegistrationPayload

    data class Launcher(val content: String, override val digest: String) : RegistrationPayload
}

internal fun registrationSourceFor(installation: Path, connection: HarnessConnection): Path =
    when (connection) {
        HarnessConnection.CODEX_MCP -> installation.resolve("bin/kast-mcp-complete")
        HarnessConnection.CODEX_APP_SERVER -> installation.resolve("bin/kast-codex-complete")
        HarnessConnection.COPILOT -> installation.resolve("share/kast/adapters/copilot/extension.mjs")
        HarnessConnection.PI -> installation.resolve("share/kast/adapters/pi/extension.ts")
    }

internal fun registrationDestinationFor(root: Path, home: Path, connection: HarnessConnection): Path =
    when (connection) {
        HarnessConnection.CODEX_MCP -> root.resolve("current/bin/kast-mcp-complete")
        HarnessConnection.CODEX_APP_SERVER -> home.resolve(".local/bin/kast-codex")
        HarnessConnection.COPILOT -> home.resolve(".copilot/extensions/kast/extension.mjs")
        HarnessConnection.PI -> home.resolve(".pi/agent/extensions/kast.ts")
    }

/** The installed facade owns both interactive Codex and the App Server stdio protocol. */
internal fun registrationPayload(root: Path, source: Path, connection: HarnessConnection): RegistrationPayload =
    if (connection == HarnessConnection.CODEX_APP_SERVER) {
        val facade = root.resolve("current/bin/kast-codex-complete").toString().replace("'", "'\\''")
        val content = "#!/bin/sh\nexec '$facade' \"\$@\"\n"
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        RegistrationPayload.Launcher(content, digest.joinToString("") { "%02x".format(it) })
    } else RegistrationPayload.BundledFile(source, sha256(source))
