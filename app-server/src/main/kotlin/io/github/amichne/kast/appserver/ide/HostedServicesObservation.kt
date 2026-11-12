package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.protocol.contract.HostProvenance
import io.github.amichne.kast.protocol.contract.HostedCompatibilityRequirements
import io.github.amichne.kast.protocol.contract.IdeBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.KotlinPluginBuildIdentity
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Properties

sealed interface HostedServiceObservation {
    val root: CanonicalRoot

    data class Compatible(
        override val root: CanonicalRoot,
        val descriptor: ExistingIdeDescriptor,
        val compatibility: AdmittedIdeHostCompatibility,
    ) : HostedServiceObservation

    data class Incompatible(
        override val root: CanonicalRoot,
        val descriptor: ExistingIdeDescriptor,
        val compatibilityFailure: IdeHostCompatibilityFailure,
        val provenance: HostProvenance,
    ) : HostedServiceObservation

    data class Unavailable(override val root: CanonicalRoot, val failure: ExistingIdeFailure) : HostedServiceObservation
}

data class AdmittedHostedService(
    val root: CanonicalRoot,
    val descriptor: ExistingIdeDescriptor,
    val compatibility: AdmittedIdeHostCompatibility,
)

sealed interface HostedServicesObservation {
    class Admitted private constructor(val hosts: List<AdmittedHostedService>) : HostedServicesObservation {
        companion object {
            internal fun from(hosts: List<AdmittedHostedService>): HostedServicesObservation =
                if (hosts.isEmpty()) Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
                else Admitted(java.util.List.copyOf(hosts))
        }
    }

    data class Rejected(val failure: ExistingIdeFailure) : HostedServicesObservation
}

internal fun requiredHostedCompatibilityPolicy(): Refinement<HostedCompatibilityRequirements, ExistingIdeFailure> {
    val properties = Properties()
    return try {
        val resource =
            CanonicalHostedContract::class.java.getResourceAsStream("/kast-hosted-platform.properties")
                ?: return Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
        resource.use(properties::load)
        val ide =
            when (
                val parsed =
                    IdeBuildIdentity.parse(
                        properties.getProperty("ideBuild")
                            ?: return Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
                    )
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
            }
        val kotlin =
            when (
                val parsed =
                    KotlinPluginBuildIdentity.parse(
                        properties.getProperty("kotlinPluginBuild")
                            ?: return Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
                    )
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
            }
        Refinement.Refined(HostedCompatibilityRequirements(ide, kotlin, CanonicalHostedContract.required))
    } catch (_: java.io.IOException) {
        Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
    }
}

/** Select recorded project owners, then admit each actual live describe. No discovery protocol is added. */
fun observeRunningHostedServices(
    home: Path,
    configuration: io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration,
): HostedServicesObservation =
    when (val selected = configuredSemanticDeployment(home, configuration)) {
        is Refinement.Rejected -> HostedServicesObservation.Rejected(selected.failure)
        is Refinement.Refined ->
            observeRunningHostedServices(
                home,
                ProcessHostedEndpointOwnerProbe,
                JsonLineHostedAdmissionObserver,
                ExistingIdeSocketClient(
                    home,
                    configuration.readLimits,
                    HOST_ADMISSION_EXCHANGE_MILLIS,
                    configuration.debug,
                    selected,
                    ::requiredHostedCompatibilityPolicy,
                ),
            )
    }

internal fun observeRunningHostedServices(
    home: Path,
    ownerProbe: HostedEndpointOwnerProbe,
    observer: HostedAdmissionObserver,
    client: ExistingIdeSocketClient = ExistingIdeSocketClient(home, exchangeMillis = HOST_ADMISSION_EXCHANGE_MILLIS),
): HostedServicesObservation {
    val evidence = BoundedHostedAdmissionObserver(observer)
    return try {
        val directory =
            io.github.amichne.kast.distribution.contract.HostedEndpointPaths.family(
                home,
                io.github.amichne.kast.distribution.contract.HostedEndpointPaths.Family.SEMANTIC,
            )
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
            rejectedHostedAdmission(evidence, HostedAdmissionStage.ENTRY_FAMILY, ExistingIdeFailure.HOST_UNAVAILABLE)
        else
            Files.newDirectoryStream(directory).use { entries ->
                observeHostedEntries(home, directory, entries, ownerProbe, evidence, client)
            }
    } catch (_: java.io.IOException) {
        rejectedHostedAdmission(evidence, HostedAdmissionStage.DESCRIPTOR_OWNER, ExistingIdeFailure.HOST_UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        rejectedHostedAdmission(evidence, HostedAdmissionStage.DESCRIPTOR_OWNER, ExistingIdeFailure.DESCRIPTOR_REJECTED)
    } catch (_: SecurityException) {
        rejectedHostedAdmission(evidence, HostedAdmissionStage.DESCRIPTOR_OWNER, ExistingIdeFailure.DESCRIPTOR_REJECTED)
    }
}

private const val HOST_ADMISSION_EXCHANGE_MILLIS = 2_000L
