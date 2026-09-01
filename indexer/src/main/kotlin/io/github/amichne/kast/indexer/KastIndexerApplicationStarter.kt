package io.github.amichne.kast.indexer

import com.intellij.openapi.application.ApplicationStarter
import io.github.amichne.kast.runtime.composition.InstalledKastRuntime
import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeConstruction
import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeFailure
import io.github.amichne.kast.runtime.composition.InstalledRuntimeBootstrapObserver
import io.github.amichne.kast.runtime.composition.InstalledRuntimeBootstrapPhase
import io.github.amichne.kast.runtime.composition.InstalledRuntimeIndexScope
import kotlin.system.exitProcess

private sealed interface InstalledIndexerStartupFailure {
    data class Launch(
        val failures: Set<IndexerLaunchFailure>,
    ) : InstalledIndexerStartupFailure

    data class Transport(
        val failure: IndexerTransportFailure,
    ) : InstalledIndexerStartupFailure

    data class Runtime(
        val failures: Set<InstalledKastRuntimeFailure>,
    ) : InstalledIndexerStartupFailure

    data class TransportExecution(
        val failure: IndexerTransportExecutionFailure,
    ) : InstalledIndexerStartupFailure

    data object CacheState : InstalledIndexerStartupFailure
}

private enum class IndexerTransportExecutionFailure {
    RETURNED,
    FAILED,
    INTERRUPTED,
}

/** IntelliJ application command that owns one admitted installed runtime until process exit. */
class KastIndexerApplicationStarter : ApplicationStarter {
    override val isHeadless: Boolean = true
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT

    /**
     * Proof transition: `List<String> -> exact-root installed runtime serving`.
     *
     * Establishes closed command admission, a bound exact socket, canonical owned state, and the
     * sole composition dispatch capability before serving. [InstalledIndexerStartupFailure]
     * closes expected startup rejection. Raw arguments and paths leave only at their named outer
     * process, filesystem, platform, and transport boundaries.
     */
    override fun main(args: List<String>) {
        val options = when (val admission = IndexerLaunchOptions.admit(args)) {
            is IndexerLaunchAdmission.Admitted -> admission.options
            is IndexerLaunchAdmission.Rejected -> reject(
                InstalledIndexerStartupFailure.Launch(admission.failures),
            )
        }
        if (
            IndexerCacheStatePublisher.publish(IndexerCacheState.REFRESHING) !=
            IndexerCacheStatePublication.Published
        ) {
            reject(InstalledIndexerStartupFailure.CacheState)
        }
        val endpoint = when (val preparation = PreparedIndexerEndpoint.prepare(options)) {
            is IndexerEndpointPreparation.Prepared -> preparation.endpoint
            is IndexerEndpointPreparation.Rejected -> reject(
                InstalledIndexerStartupFailure.Transport(preparation.failure),
            )
        }
        val bootstrap = InstalledIndexerBootstrapReporter(
            InstalledIndexerBootstrapStateSink(::reportBootstrapState),
        )
        val dispatch = when (val runtime = InstalledKastRuntime.create(
            options.workspaceRoot,
            endpoint.stateDirectory,
            object : InstalledRuntimeBootstrapObserver {
                override fun observe(phase: InstalledRuntimeBootstrapPhase) {
                    bootstrap.observe(phase)
                }

                override fun observeIndexScope(scope: InstalledRuntimeIndexScope) {
                    reportIndexScope(scope)
                }
            },
        )) {
            is InstalledKastRuntimeConstruction.Created -> runtime.dispatch
            is InstalledKastRuntimeConstruction.Rejected -> {
                bootstrap.rejectRuntime(runtime.failures)
                reject(InstalledIndexerStartupFailure.Runtime(runtime.failures))
            }
        }
        bootstrap.beginTransportActivation()
        val transport = when (
            val activation = InstalledIndexerTransport.activate(
                endpoint,
                KastIndexerHost(dispatch),
            )
        ) {
            is IndexerTransportActivation.Activated -> activation.transport
            is IndexerTransportActivation.Rejected -> {
                bootstrap.rejectTransport(activation.failure)
                reject(InstalledIndexerStartupFailure.Transport(activation.failure))
            }
        }
        if (
            IndexerCacheStatePublisher.publish(IndexerCacheState.SMART) !=
            IndexerCacheStatePublication.Published
        ) {
            reject(InstalledIndexerStartupFailure.CacheState)
        }
        bootstrap.ready()
        transport.use { installedTransport ->
            when (
                DetachedIndexerTransportExecutor.execute {
                    installedTransport.serve()
                }
            ) {
                is IndexerTransportExecution.Completed -> reject(
                    InstalledIndexerStartupFailure.TransportExecution(
                        IndexerTransportExecutionFailure.RETURNED,
                    ),
                )
                IndexerTransportExecution.Failed -> reject(
                    InstalledIndexerStartupFailure.TransportExecution(
                        IndexerTransportExecutionFailure.FAILED,
                    ),
                )
                IndexerTransportExecution.Interrupted -> reject(
                    InstalledIndexerStartupFailure.TransportExecution(
                        IndexerTransportExecutionFailure.INTERRUPTED,
                    ),
                )
            }
        }
    }
}

private fun reportBootstrapState(state: InstalledIndexerBootstrapState) {
    System.err.println("kast-indexer: bootstrap: $state")
}

private fun reportIndexScope(scope: InstalledRuntimeIndexScope) {
    System.err.println("kast-indexer: index-scope: ${scope.processDiagnostic()}")
}

private fun reject(failure: InstalledIndexerStartupFailure): Nothing {
    if (failure != InstalledIndexerStartupFailure.CacheState) {
        IndexerCacheStatePublisher.publish(IndexerCacheState.REBUILD_REQUIRED)
    }
    System.err.println("kast-indexer: startup rejected: $failure")
    exitProcess(70)
}
