package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.distribution.contract.WireRuntimeIdentity

/** All installed frontend demand reaches the same coordinator before any JVM startup boundary. */
internal class CoordinatedRootRuntimeDemander(private val client: WorkerControlClient, private val heap: IndexerHeapSize, private val budgetPolicy: RuntimeInvocationBudgetPolicy = RuntimeInvocationBudgetPolicy()) : RootRuntimeDemander {
    override fun demand(root: CanonicalRoot, demand: HostedRuntimeDemand, startup: RuntimeStartupRequest): RuntimeAdmission {
        val deadline = budgetPolicy.begin(demand)
        val ideHome = (startup.ideHome as? StartupIdeHome.Explicit)?.path
        val selected = when (val cache = startup.cacheIntent) {
            StartupCacheIntent.Reuse -> InstalledWorkerStartup.Reuse(ideHome)
            StartupCacheIntent.Rebuild -> InstalledWorkerStartup.Rebuild(ideHome)
            is StartupCacheIntent.Seed -> {
                InstalledWorkerStartup.Seed(ideHome, (cache.sourceSystem as? StartupIdeaSystem.Explicit)?.path,
                    when (cache.consentRequest) {
                        IndexSeedConsentRequest.PREGRANTED -> WorkerSeedConsentSelection.PREGRANTED
                        IndexSeedConsentRequest.INTERACTIVE -> WorkerSeedConsentSelection.INTERACTIVE
                    })
            }
        }
        val initial = when (val allowance = deadline.remaining()) {
            is Refinement.Refined -> allowance.value
            is Refinement.Rejected -> return rejected(WorkerControlFailure.DEADLINE_EXCEEDED)
        }
        return when (val admitted = runBlocking { withTimeoutOrNull(initial.value) { client.demand(root.path, heap, selected) } } ?: return rejected(WorkerControlFailure.DEADLINE_EXCEEDED)) {
            is InstalledWorkerStart.Rejected -> rejected(admitted.failure)
            is InstalledWorkerStart.Ready -> {
                if (admitted.endpoint.root != root.path || admitted.binding !is WorkerRouteBinding.Installation) return rejected(WorkerControlFailure.IDENTITY_REJECTED)
                if (deadline.remaining() is Refinement.Rejected) return rejected(WorkerControlFailure.DEADLINE_EXCEEDED)
                val identity = when (val admittedIdentity = WireRuntimeIdentity.admit(root.path, admitted.endpoint.runtimeId.value, admitted.endpoint.attempt)) {
                    is Refinement.Refined -> admittedIdentity.value
                    is Refinement.Rejected -> return rejected(WorkerControlFailure.IDENTITY_REJECTED)
                }
                when (val endpoint = RuntimeEndpoint.at(root, admitted.endpoint.runtimeId, admitted.endpoint.socket)) {
                    is RuntimeEndpointResolution.Resolved -> RuntimeAdmission.Ready(endpoint.endpoint, workerBinding = admitted.binding, deadline = deadline, wireAuthority = RuntimeWireAuthority.Qualified(identity))
                    is RuntimeEndpointResolution.Rejected -> rejected(WorkerControlFailure.IDENTITY_REJECTED)
                }
            }
        }
    }
}

/** Exact local retirement remains available to reset after the coordinator has been disabled. */
internal class CoordinatedRuntimeLifecycle(private val client: WorkerControlClient, private val local: RuntimeLifecycleController) : RuntimeLifecycleController {
    override fun status(endpoint: RuntimeEndpoint) = local.status(endpoint)
    override fun stop(endpoint: RuntimeEndpoint): RuntimeStopResult {
        val notified = runBlocking { client.retired(endpoint.root.path) }
        return when (notified) {
            is InstalledWorkerStop.Stopped -> RuntimeStopResult.Stopped()
            is InstalledWorkerStop.Rejected -> when (notified.failure) {
                WorkerControlFailure.UNAVAILABLE, WorkerControlFailure.LIFECYCLE_TRANSITION -> local.stop(endpoint)
                else -> RuntimeStopResult.Rejected(RuntimeStopFailure.PROCESS_AMBIGUOUS)
            }
        }
    }
}
private fun rejected(failure: WorkerControlFailure) = RuntimeAdmission.Rejected(RuntimeAdmissionFailure.WorkerControlRejected(failure))
