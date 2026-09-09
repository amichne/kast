package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.cli.runtime.bootstrap.SidecarBootstrapStateFile
import io.github.amichne.kast.cli.runtime.bootstrap.SidecarBootstrapStateObservation
import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class WorkerProcessCapture(
    val executable: IndexerExecutable,
    val context: SidecarLaunchContext,
    val endpoint: RuntimeEndpoint,
    val authority: RuntimeBootstrapProcessAuthority,
    val lifecycle: RuntimeLifecycleController,
)

/** Reuses the installed launcher and its exact bootstrap/process authority inside coordinator ownership. */
internal class InstalledRuntimeWorkerEffects(
    private val factory: (InstalledWorkerStartRequest, (WorkerProcessCapture) -> Unit, IndexSeedConsentProvider) -> RootRuntimeDemander,
    private val endpointProbe: RuntimeEndpointProbe = JdkUnixDomainEndpointProbe,
    private val unreservedRetirement: (Path) -> InstalledWorkerRetirement = { InstalledWorkerRetirement.UNPROVEN },
) : InstalledWorkerEffects {
    private class LiveRoute(val endpoint: RuntimeEndpoint, val query: RuntimeBootstrapProcessQuery,
        val authority: RuntimeBootstrapProcessAuthority, val lifecycle: RuntimeLifecycleController)
    private sealed interface LaunchOwnership {
        data object NotEntered : LaunchOwnership
        class Entered(val route: LiveRoute) : LaunchOwnership
    }
    private val live = ConcurrentHashMap<Path, LaunchOwnership>()

    override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart = runInterruptible(Dispatchers.IO) {
        val root = when (val discovered = FilesystemCanonicalRootDiscovery.discover(request.root)) {
            is CanonicalRootDiscovery.Discovered -> discovered.root.takeIf { it.path == request.root }
            is CanonicalRootDiscovery.Rejected -> null
        } ?: return@runInterruptible rejected(WorkerControlFailure.IDENTITY_REJECTED)
        live[request.root] = LaunchOwnership.NotEntered
        val demander = factory(request, { selected ->
            live[request.root] = LaunchOwnership.Entered(LiveRoute(selected.endpoint,
                RuntimeBootstrapProcessQuery.from(selected.endpoint, selected.executable, selected.context), selected.authority, selected.lifecycle))
        }, request.consentAuthority.seedProvider())
        val startup = RuntimeStartupRequest.Requested(
            request.startup.ideHome?.let(StartupIdeHome::Explicit) ?: StartupIdeHome.Standard,
            when (val selected = request.startup) {
                is InstalledWorkerStartup.Reuse -> StartupCacheIntent.Reuse
                is InstalledWorkerStartup.Rebuild -> StartupCacheIntent.Rebuild
                is InstalledWorkerStartup.Seed -> StartupCacheIntent.Seed(selected.sourceSystem?.let(StartupIdeaSystem::Explicit) ?: StartupIdeaSystem.Standard, when (selected.consent) {
                    WorkerSeedConsentSelection.PREGRANTED -> IndexSeedConsentRequest.PREGRANTED
                    WorkerSeedConsentSelection.INTERACTIVE -> IndexSeedConsentRequest.INTERACTIVE
                })
            },
        )
        val ready = when (val result = demander.demand(root, HostedRuntimeDemand.Lifecycle, startup)) {
            is RuntimeAdmission.Ready -> result
            is RuntimeAdmission.Rejected -> return@runInterruptible rejected(WorkerControlFailure.STARTUP_REJECTED)
        }
        val route = (live[request.root] as? LaunchOwnership.Entered)?.route
            ?: return@runInterruptible rejected(WorkerControlFailure.IDENTITY_REJECTED)
        if (ready.endpoint.root != root || ready.endpoint != route.endpoint) return@runInterruptible rejected(WorkerControlFailure.IDENTITY_REJECTED)
        val query = route.query
        val bootstrap = when (val state = SidecarBootstrapStateFile.observe(query.bootstrapState)) {
            is SidecarBootstrapStateObservation.Observed -> state.state as? SemanticRuntimeBootstrapState.Ready
            is SidecarBootstrapStateObservation.Rejected -> null
        } ?: return@runInterruptible rejected(WorkerControlFailure.IDENTITY_REJECTED)
        val endpoint = when (val admitted = InstalledWorkerEndpoint.admit(root.path, ready.endpoint.runtimeId, ready.endpoint.socketPath, bootstrap.attemptId)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return@runInterruptible rejected(admitted.failure)
        }
        if (!exact(route, endpoint)) return@runInterruptible rejected(WorkerControlFailure.IDENTITY_REJECTED)
        live[endpoint.root] = LaunchOwnership.Entered(route)
        InstalledWorkerStart.Ready(endpoint)
    }

    override suspend fun observe(route: InstalledWorkerEndpoint): InstalledWorkerObservation = runInterruptible(Dispatchers.IO) {
        val known = (live[route.root] as? LaunchOwnership.Entered)?.route
        if (known != null && exact(known, route)) InstalledWorkerObservation.EXACT_READY else InstalledWorkerObservation.UNPROVEN
    }

    override suspend fun stop(route: InstalledWorkerEndpoint): InstalledWorkerRetirement = runInterruptible(Dispatchers.IO) {
        val ownership = live[route.root] as? LaunchOwnership.Entered ?: return@runInterruptible InstalledWorkerRetirement.UNPROVEN
        val known = ownership.route
        if (known.endpoint.socketPath != route.socket || known.endpoint.runtimeId != route.runtimeId) return@runInterruptible InstalledWorkerRetirement.UNPROVEN
        when (known.lifecycle.stop(known.endpoint)) {
            is RuntimeStopResult.Stopped -> { live.remove(route.root, ownership); InstalledWorkerRetirement.EXACT_RETIRED }
            is RuntimeStopResult.Rejected -> InstalledWorkerRetirement.UNPROVEN
        }
    }

    override suspend fun retireUnpublished(root: Path): InstalledWorkerRetirement = runInterruptible(Dispatchers.IO) {
        val ownership = live[root] ?: return@runInterruptible InstalledWorkerRetirement.UNPROVEN
        if (ownership == LaunchOwnership.NotEntered) {
            return@runInterruptible if (live.remove(root, ownership)) InstalledWorkerRetirement.EXACT_RETIRED else InstalledWorkerRetirement.UNPROVEN
        }
        val known = (ownership as LaunchOwnership.Entered).route
        when (known.lifecycle.stop(known.endpoint)) {
            is RuntimeStopResult.Stopped -> { live.remove(root, ownership); InstalledWorkerRetirement.EXACT_RETIRED }
            is RuntimeStopResult.Rejected -> InstalledWorkerRetirement.UNPROVEN
        }
    }

    override suspend fun retireUnreserved(root: Path): InstalledWorkerRetirement = runInterruptible(Dispatchers.IO) {
        if (live.containsKey(root)) InstalledWorkerRetirement.UNPROVEN else unreservedRetirement(root)
    }

    private fun exact(known: LiveRoute, route: InstalledWorkerEndpoint): Boolean {
        if (known.endpoint.root.path != route.root || known.endpoint.socketPath != route.socket || known.endpoint.runtimeId != route.runtimeId) return false
        val process = known.authority.observe(known.query)
        if (process !is RuntimeBootstrapProcessObservation.Owned || process.attemptId != route.attempt || process.session.observe() != RuntimeSessionObservation.Present) return false
        val state = SidecarBootstrapStateFile.observe(known.query.bootstrapState)
        if (state !is SidecarBootstrapStateObservation.Observed || state.state !is SemanticRuntimeBootstrapState.Ready || state.state.attemptId != route.attempt) return false
        return endpointProbe.probe(known.endpoint) is RuntimeEndpointReachability.Reachable
    }
}
private fun rejected(failure: WorkerControlFailure) = InstalledWorkerStart.Rejected(failure)
