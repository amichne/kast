package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientFailure
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.InstalledSemanticCoordinator
import io.github.amichne.kast.appserver.InstalledSemanticService
import io.github.amichne.kast.appserver.PersistentBrokerServiceFailure
import io.github.amichne.kast.cli.direct.DirectToolRegistration
import io.github.amichne.kast.cli.direct.InstalledClientContext
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.direct.directToolName
import io.github.amichne.kast.cli.ide.executeAdmittedPublicTool
import io.github.amichne.kast.distribution.cli.ControlStage
import io.github.amichne.kast.distribution.cli.ControlStageOutcome
import io.github.amichne.kast.distribution.cli.ControlStageTrace
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

internal fun coordinatedPublicSession(
    installation: Path,
    directory: Path,
    catalog: PublicToolCatalog,
): KastDirectToolSession {
    val roots = FilesystemCanonicalRootDiscovery
    val registration =
        DirectToolRegistration(setOf(directToolName(PublicToolIdentity.QUERY_SYMBOLS.toolName))) {
            emptyList()
        }
    val read = DaemonOperationClient { root, operation ->
        runBlocking {
            val coordinator =
                connectSemanticCoordinator(installation)
                    ?: return@runBlocking DaemonOperationResult.Rejected(
                        DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.UNAVAILABLE)
                    )
            val started = System.nanoTime()
            val result = coordinator.execute(root, operation)
            ControlStageTrace.record(
                ControlStage.COORDINATED_INVOCATION,
                if (result is DaemonOperationResult.Rejected) ControlStageOutcome.REJECTED
                else ControlStageOutcome.COMPLETED,
                started,
                generation = coordinator.generation,
                processId = coordinator.processId,
            )
            result
        }
    }
    return KastDirectToolSession(
        registration,
        root = { roots.discover(directory) },
        invokePublic = { executeAdmittedPublicTool(it, directory, roots, read) },
        queryCatalog = catalog,
    )
}

private suspend fun connectSemanticCoordinator(installation: Path): InstalledSemanticCoordinator? {
    val environment = System.getenv()
    val home =
        try {
            Path.of(environment["HOME"] ?: return null).toRealPath()
        } catch (_: IOException) {
            return null
        }
    val result =
        InstalledSemanticCoordinator.connect(installation, home) {
            runInterruptible(Dispatchers.IO) {
                val executable = installation.resolve("share/kast/libexec/kast-management")
                if (InstalledClientContext.admit(executable, environment) is Refinement.Rejected) {
                    Refinement.Rejected(PersistentBrokerServiceFailure.KAST_QUALIFICATION_REJECTED)
                } else {
                    ControlStageTrace.observeRefinement(ControlStage.COORDINATOR_START) {
                        InstalledSemanticService.submit(installation, home, environment)
                    }
                }
            }
        }
    return when (result) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> null
    }
}
