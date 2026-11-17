package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientFailure
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.InstalledSemanticCoordinator
import io.github.amichne.kast.appserver.WorkerControlFailure
import io.github.amichne.kast.cli.direct.DirectToolRegistration
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
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
    val child = startSemanticCoordinator(installation, home, readiness) ?: return null
    return withTimeoutOrNull(BrokerOperationalLimits.serviceChildPhases.value) {
        while (child.isAlive) {
            val installed = InstalledSemanticCoordinator.observe(installation, readiness)
            if (installed is Refinement.Refined) return@withTimeoutOrNull installed.value
            delay(READINESS_POLL_MILLIS)
        }
        null
    }
}

private const val READINESS_POLL_MILLIS = 25L

private fun startSemanticCoordinator(installation: Path, home: Path, readiness: Path): Process? =
    try {
        val executable = installation.resolve("share/kast/libexec/kast-management")
        val identity =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256").digest(installation.toString().toByteArray(Charsets.UTF_8))
                )
        val arguments =
            listOf(
                executable.toString(),
                "-Duser.home=$home",
                "--internal-entrypoint=daemon",
                "--semantic",
            )
        ProcessBuilder(arguments)
            .directory(installation.toFile())
            .redirectInput(Path.of("/dev/null").toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment()["BROKER_SERVICE_IDENTITY"] = "sha256:$identity"
                environment()["BROKER_READINESS_FILE"] = readiness.toString()
            }
            .start()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
