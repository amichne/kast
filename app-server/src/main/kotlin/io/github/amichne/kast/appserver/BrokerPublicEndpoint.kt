package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.BrokerSocketPath
import io.github.amichne.kast.appserver.runtime.BrokerSocketPathFailure
import io.github.amichne.kast.kernel.Validation
import java.nio.file.Path

/** Discovery policy is independent of the installation-owned upstream transport. */
internal sealed interface BrokerPublicEndpoint {
    val path: Path
    val identityValue: String

    class Private internal constructor(private val installation: BrokerInstallationLayout) : BrokerPublicEndpoint {
        override val path: Path = installation.privatePublicSocket
        override val identityValue: String = "private\n$path"

        override fun prepare(): Validation<BrokerSocketPath, BrokerSocketPathFailure> =
            BrokerSocketPath.prepareInstalled(installation.run.resolve("c.sock"))
    }

    class CodexControl internal constructor(val socket: CodexControlSocketPath) : BrokerPublicEndpoint {
        override val path: Path = socket.path
        override val identityValue: String = "codex-control\n$path"

        override fun prepare(): Validation<BrokerSocketPath, BrokerSocketPathFailure> = BrokerSocketPath.admit(path)
    }

    /** Only startup realizes private aliases; canonical discovery never uses a mutable alias. */
    fun prepare(): Validation<BrokerSocketPath, BrokerSocketPathFailure>

    companion object {
        fun select(
            installation: BrokerInstallationLayout,
            codexHome: Path,
            mode: BrokerPublicEndpointMode,
        ): BrokerPublicEndpoint =
            when (mode) {
                BrokerPublicEndpointMode.PRIVATE -> Private(installation)
                BrokerPublicEndpointMode.CODEX_CONTROL -> CodexControl(CodexControlSocketPath.from(codexHome))
            }
    }
}

/** Constructed only from the admitted absolute, normalized host home. */
internal class CodexControlSocketPath private constructor(val path: Path) {
    companion object {
        fun from(codexHome: Path): CodexControlSocketPath =
            CodexControlSocketPath(codexHome.resolve("app-server-control/app-server-control.sock"))
    }
}

internal enum class BrokerPublicEndpointMode {
    PRIVATE,
    CODEX_CONTROL,
}
