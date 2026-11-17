package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.ide.IdeLifecycleClient
import io.github.amichne.kast.appserver.ide.IdeLifecycleDeployment
import io.github.amichne.kast.distribution.cli.WorkspaceSeedEndpoint
import io.github.amichne.kast.distribution.contract.configuration.HostIdleShutdown
import io.github.amichne.kast.distribution.contract.configuration.HostMaxHeap
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Path

/** Read-only inspection through the existing lifecycle transport; never starts or settles a donor Host. */
internal fun inspectWorkspaceSeedHost(runtime: Path, state: Path, home: Path): WorkspaceSeedEndpoint {
    val client =
        IdeLifecycleClient(
            home,
            runtime,
            deployment = IdeLifecycleDeployment.Headless(state, HostIdleShutdown.Disabled, HostMaxHeap.DEFAULT),
        )
    val inspected = client.inspectForInstallation()
    return if (inspected is IdeLifecycleResult.Blocked && inspected.reason == IdeLifecycleFailure.HOST_UNAVAILABLE)
        WorkspaceSeedEndpoint.HOST_UNAVAILABLE
    else WorkspaceSeedEndpoint.UNPROVEN
}
