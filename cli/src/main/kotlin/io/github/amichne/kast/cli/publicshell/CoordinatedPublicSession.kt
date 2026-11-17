package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientFailure
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.InstalledSemanticCoordinator
import io.github.amichne.kast.appserver.InstalledSemanticService
import io.github.amichne.kast.appserver.WorkerControlFailure
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
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

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
    val home = Path.of(System.getenv("HOME") ?: return null).toRealPath()
    val readiness = InstalledSemanticCoordinator.readinessPath(installation, home)
    when (val installed = InstalledSemanticCoordinator.observe(installation, readiness)) {
        is Refinement.Refined -> return installed.value
        is Refinement.Rejected -> if (installed.failure != WorkerControlFailure.UNAVAILABLE) return null
    }
    val environment = System.getenv()
    val executable = installation.resolve("share/kast/libexec/kast-management")
    val admissible = runInterruptible(Dispatchers.IO) { InstalledClientContext.admit(executable, environment) }
    if (admissible !is Refinement.Refined) return null

    val submitted =
        runInterruptible(Dispatchers.IO) {
            ControlStageTrace.observeRefinement(ControlStage.COORDINATOR_START) {
                InstalledSemanticService.submit(installation, home, environment)
            }
        }
    if (submitted is Refinement.Rejected) return null
    return withTimeoutOrNull(BrokerOperationalLimits.serviceChildPhases.value) {
        awaitSemanticCoordinator(installation, readiness)
    }
}

private suspend fun awaitSemanticCoordinator(installation: Path, readiness: Path): InstalledSemanticCoordinator {
    while (true) {
        val installed = InstalledSemanticCoordinator.observe(installation, readiness)
        if (installed is Refinement.Refined) return installed.value
        delay(READINESS_POLL_MILLIS)
    }
}

private const val READINESS_POLL_MILLIS = 25L
