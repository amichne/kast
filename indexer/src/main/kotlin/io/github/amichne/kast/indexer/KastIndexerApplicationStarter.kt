package io.github.amichne.kast.indexer

import com.intellij.openapi.application.ApplicationStarter
import io.github.amichne.kast.runtime.composition.InstalledKastRuntime
import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeConstruction
import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeFailure
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
        val endpoint = when (val preparation = PreparedIndexerEndpoint.prepare(options)) {
            is IndexerEndpointPreparation.Prepared -> preparation.endpoint
            is IndexerEndpointPreparation.Rejected -> reject(
                InstalledIndexerStartupFailure.Transport(preparation.failure),
            )
        }
        val dispatch = when (val runtime = InstalledKastRuntime.create(
            options.workspaceRoot,
            endpoint.stateDirectory,
        )) {
            is InstalledKastRuntimeConstruction.Created -> runtime.dispatch
            is InstalledKastRuntimeConstruction.Rejected -> reject(
                InstalledIndexerStartupFailure.Runtime(runtime.failures),
            )
        }
        val transport = when (
            val activation = InstalledIndexerTransport.activate(
                endpoint,
                KastIndexerHost(dispatch),
            )
        ) {
            is IndexerTransportActivation.Activated -> activation.transport
            is IndexerTransportActivation.Rejected -> reject(
                InstalledIndexerStartupFailure.Transport(activation.failure),
            )
        }
        transport.use { installedTransport ->
            installedTransport.serve()
        }
    }
}

private fun reject(failure: InstalledIndexerStartupFailure): Nothing {
    System.err.println("kast-indexer: startup rejected: $failure")
    exitProcess(70)
}
