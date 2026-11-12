package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.ExistingIdeSocketClient
import io.github.amichne.kast.appserver.ide.HostedServicesObservation
import io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient
import io.github.amichne.kast.appserver.ide.configuredSemanticDeployment
import io.github.amichne.kast.appserver.ide.observeRunningHostedServices
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.managed.canonicalRootFixture
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedDeploymentRequiredTest {
    @Test
    fun `selected IDEA without managed state grants no workspace lifecycle client`() {
        val configuration =
            ResolvedKastConfiguration.resolve(
                ConfigurationSources(environment = mapOf("KAST_INSTALL_IDEA_HOME" to "/unused/idea"))
            ) as Refinement.Refined
        assertEquals(
            WorkspaceLifecycleClient.Unavailable,
            installedWorkspaceLifecycleClient(
                Path.of("/unused/home"),
                configuration.value.selectedIdeHome,
            ),
        )
        assertEquals(
            Refinement.Rejected(ExistingIdeFailure.CONFIGURATION_REJECTED),
            configuredSemanticDeployment(Path.of("/unused/home"), configuration.value),
        )
        assertEquals(
            HostedServicesObservation.Rejected(ExistingIdeFailure.CONFIGURATION_REJECTED),
            observeRunningHostedServices(Path.of("/unused/home"), configuration.value),
        )
    }

    @Test
    fun `home alone grants no semantic descriptor discovery`(@TempDir root: Path) {
        val workspace = canonicalRootFixture(root.toRealPath())
        assertEquals(
            ExistingIdeExchange.Rejected(ExistingIdeFailure.CONFIGURATION_REJECTED),
            ExistingIdeSocketClient(root).query(workspace, ExistingIdeOperation.Status),
        )
    }
}
