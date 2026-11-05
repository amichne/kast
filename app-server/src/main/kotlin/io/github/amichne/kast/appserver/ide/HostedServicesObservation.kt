package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.protocol.contract.DeclaredIntellijTargets
import io.github.amichne.kast.protocol.contract.HostProvenance
import io.github.amichne.kast.protocol.contract.HostedCompatibilityRequirements
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

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
    return when (val targets = DeclaredIntellijTargets.packaged) {
        is Refinement.Refined ->
            Refinement.Refined(
                HostedCompatibilityRequirements.forTargets(targets.value, CanonicalHostedContract.required)
            )
        is Refinement.Rejected -> Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
    }
}

/** Select recorded project owners, then admit each actual live describe. No discovery protocol is added. */
fun observeRunningHostedServices(home: Path): HostedServicesObservation =
    observeRunningHostedServices(home, ProcessHostedEndpointOwnerProbe, JsonLineHostedAdmissionObserver)

internal fun observeRunningHostedServices(
    home: Path,
    ownerProbe: HostedEndpointOwnerProbe,
    observer: HostedAdmissionObserver,
): HostedServicesObservation {
    val evidence = BoundedHostedAdmissionObserver(observer)
    return try {
        val directory = home.resolve(".kast/ide-hosted")
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
            rejectedHostedAdmission(evidence, HostedAdmissionStage.ENTRY_FAMILY, ExistingIdeFailure.HOST_UNAVAILABLE)
        else
            Files.newDirectoryStream(directory).use { entries ->
                observeHostedEntries(home, directory, entries, ownerProbe, evidence)
            }
    } catch (_: java.io.IOException) {
        rejectedHostedAdmission(evidence, HostedAdmissionStage.DESCRIPTOR_OWNER, ExistingIdeFailure.HOST_UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        rejectedHostedAdmission(evidence, HostedAdmissionStage.DESCRIPTOR_OWNER, ExistingIdeFailure.DESCRIPTOR_REJECTED)
    } catch (_: SecurityException) {
        rejectedHostedAdmission(evidence, HostedAdmissionStage.DESCRIPTOR_OWNER, ExistingIdeFailure.DESCRIPTOR_REJECTED)
    }
}
