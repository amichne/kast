package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal fun rejectedHostedAdmission(
    evidence: HostedAdmissionObserver,
    stage: HostedAdmissionStage,
    failure: ExistingIdeFailure,
): HostedServicesObservation.Rejected {
    evidence.record(HostedAdmissionEvidence.Rejected(stage, failure))
    return HostedServicesObservation.Rejected(failure)
}

internal fun observeHostedEntries(
    home: Path,
    directory: Path,
    entries: Iterable<Path>,
    ownerProbe: HostedEndpointOwnerProbe,
    evidence: HostedAdmissionObserver,
): HostedServicesObservation {
    val hosts = ArrayList<AdmittedHostedService>()
    val client = ExistingIdeSocketClient(home, exchangeMillis = HOST_ADMISSION_EXCHANGE_MILLIS)
    val deadline = System.nanoTime() + HOST_ADMISSION_SCAN_NANOS
    var count = 0
    for (entry in entries) {
        if (System.nanoTime() >= deadline)
            return rejectedHostedAdmission(
                evidence,
                HostedAdmissionStage.ENTRY_FAMILY,
                ExistingIdeFailure.DEADLINE_EXCEEDED,
            )
        when (val selected = selectHostedCandidate(home, entry, ownerProbe, evidence)) {
            HostedEndpointSelection.Excluded -> continue
            is HostedEndpointSelection.Rejected -> return HostedServicesObservation.Rejected(selected.failure)
            is HostedEndpointSelection.Selected -> {
                if (++count > MAXIMUM_HOST_ADMISSION_ENTRIES)
                    return rejectedHostedAdmission(
                        evidence,
                        HostedAdmissionStage.LIVE_ADMISSION,
                        ExistingIdeFailure.DEADLINE_EXCEEDED,
                    )
                when (val admitted = admitHostedCandidate(directory, entry, selected.endpoint, client, evidence)) {
                    is Refinement.Refined -> hosts += admitted.value
                    is Refinement.Rejected -> return HostedServicesObservation.Rejected(admitted.failure)
                }
            }
        }
    }
    return when (val admitted = HostedServicesObservation.Admitted.from(hosts)) {
        is HostedServicesObservation.Admitted -> admitted
        is HostedServicesObservation.Rejected ->
            rejectedHostedAdmission(evidence, HostedAdmissionStage.LIVE_ADMISSION, admitted.failure)
    }
}

private sealed interface HostedEndpointSelection {
    data object Excluded : HostedEndpointSelection

    data class Selected(val endpoint: DeclaredHostedEndpoint) : HostedEndpointSelection

    data class Rejected(val failure: ExistingIdeFailure) : HostedEndpointSelection
}

private fun selectHostedCandidate(
    home: Path,
    entry: Path,
    ownerProbe: HostedEndpointOwnerProbe,
    evidence: HostedAdmissionObserver,
): HostedEndpointSelection {
    if (!PROJECT_DIRECTORY.matches(entry.fileName.toString())) {
        evidence.record(HostedAdmissionEvidence.Excluded(HostedAdmissionStage.ENTRY_FAMILY))
        return HostedEndpointSelection.Excluded
    }
    evidence.record(HostedAdmissionEvidence.Selected(HostedAdmissionStage.ENTRY_FAMILY))
    if (!Files.exists(entry.resolve("endpoint.json"), NOFOLLOW_LINKS)) {
        evidence.record(HostedAdmissionEvidence.Excluded(HostedAdmissionStage.DESCRIPTOR_OWNER))
        return HostedEndpointSelection.Excluded
    }
    val recorded =
        when (val read = readHostedOwner(home, entry)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected ->
                return selectionRejected(evidence, HostedAdmissionStage.DESCRIPTOR_OWNER, read.failure)
        }
    evidence.record(HostedAdmissionEvidence.Selected(HostedAdmissionStage.DESCRIPTOR_OWNER))
    return selectLiveEndpoint(recorded, ownerProbe, evidence)
}

private fun selectLiveEndpoint(
    recorded: RecordedHostedEndpointOwner,
    ownerProbe: HostedEndpointOwnerProbe,
    evidence: HostedAdmissionObserver,
): HostedEndpointSelection =
    when (ownerProbe.observe(recorded.owner)) {
        HostedEndpointOwnerObservation.ABSENT -> {
            evidence.record(HostedAdmissionEvidence.Excluded(HostedAdmissionStage.OWNER_LIVENESS))
            HostedEndpointSelection.Excluded
        }
        HostedEndpointOwnerObservation.UNAVAILABLE ->
            selectionRejected(evidence, HostedAdmissionStage.OWNER_LIVENESS, ExistingIdeFailure.HOST_UNAVAILABLE)
        HostedEndpointOwnerObservation.ALIVE -> {
            evidence.record(HostedAdmissionEvidence.Selected(HostedAdmissionStage.OWNER_LIVENESS))
            when (val endpoint = recorded.endpoint()) {
                is Refinement.Refined -> {
                    evidence.record(HostedAdmissionEvidence.Selected(HostedAdmissionStage.CURRENT_DESCRIPTOR))
                    HostedEndpointSelection.Selected(endpoint.value)
                }
                is Refinement.Rejected ->
                    selectionRejected(evidence, HostedAdmissionStage.CURRENT_DESCRIPTOR, endpoint.failure)
            }
        }
    }

private fun selectionRejected(
    evidence: HostedAdmissionObserver,
    stage: HostedAdmissionStage,
    failure: ExistingIdeFailure,
): HostedEndpointSelection.Rejected {
    evidence.record(HostedAdmissionEvidence.Rejected(stage, failure))
    return HostedEndpointSelection.Rejected(failure)
}

private fun readHostedOwner(
    home: Path,
    entry: Path,
): Refinement<RecordedHostedEndpointOwner, ExistingIdeFailure> {
    val rejected = Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
    val descriptor = entry.resolve("endpoint.json")
    if (!Files.isDirectory(entry, NOFOLLOW_LINKS) || entry.toRealPath() != entry) return rejected
    if (
        Files.getOwner(entry) != Files.getOwner(home) ||
            Files.getPosixFilePermissions(entry) != PosixFilePermissions.fromString("rwx------")
    )
        return rejected
    if (!Files.isRegularFile(descriptor, NOFOLLOW_LINKS) || Files.getOwner(descriptor) != Files.getOwner(home))
        return rejected
    val bytes =
        Files.newInputStream(descriptor, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_HOST_DESCRIPTOR_BYTES + 1) }
    if (bytes.size > MAXIMUM_HOST_DESCRIPTOR_BYTES) return rejected
    return ExistingIdeDocuments.recordedOwner(bytes)
}

private fun admitHostedCandidate(
    directory: Path,
    entry: Path,
    endpoint: DeclaredHostedEndpoint,
    client: ExistingIdeSocketClient,
    evidence: HostedAdmissionObserver,
): Refinement<AdmittedHostedService, ExistingIdeFailure> {
    val root =
        when (val selected = selectHostedRoot(directory, entry, endpoint)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> {
                evidence.record(HostedAdmissionEvidence.Rejected(HostedAdmissionStage.ROOT_ADMISSION, selected.failure))
                return selected
            }
        }
    evidence.record(HostedAdmissionEvidence.Selected(HostedAdmissionStage.ROOT_ADMISSION))
    return when (val observed = observeHostedService(client, root, endpoint)) {
        is Refinement.Refined -> {
            evidence.record(HostedAdmissionEvidence.Selected(HostedAdmissionStage.LIVE_ADMISSION))
            observed
        }
        is Refinement.Rejected -> {
            evidence.record(HostedAdmissionEvidence.Rejected(HostedAdmissionStage.LIVE_ADMISSION, observed.failure))
            observed
        }
    }
}

private fun selectHostedRoot(
    directory: Path,
    entry: Path,
    endpoint: DeclaredHostedEndpoint,
): Refinement<CanonicalRoot, ExistingIdeFailure> {
    val rejected = Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
    val root =
        when (val selected = admitObservedHostedRoot(endpoint.root)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return rejected
        }
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(root.path.toString().toByteArray())
            .take(HOST_ROOT_DIGEST_PREFIX_BYTES)
            .joinToString("") { "%02x".format(it) }
    if (entry != directory.resolve(digest) || endpoint.socket != entry.resolve("host.sock")) return rejected
    return Refinement.Refined(root)
}

private fun observeHostedService(
    client: ExistingIdeSocketClient,
    root: CanonicalRoot,
    endpoint: DeclaredHostedEndpoint,
): Refinement<AdmittedHostedService, ExistingIdeFailure> {
    val declared =
        when (val bound = endpoint.bind(root, endpoint.socket)) {
            is Refinement.Refined -> bound.value
            is Refinement.Rejected -> return bound
        }
    return when (val exchange = client.query(root, ExistingIdeOperation.Status)) {
        is ExistingIdeExchange.Received ->
            when (val observed = client.latestObservation(root)) {
                is HostedServiceObservation.Compatible ->
                    if (observed.descriptor.owner == declared.owner && observed.descriptor.host == declared.host)
                        Refinement.Refined(AdmittedHostedService(root, observed.descriptor, observed.compatibility))
                    else Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
                is HostedServiceObservation.Incompatible ->
                    Refinement.Rejected(ExistingIdeFailure.COMPATIBILITY_REJECTED)
                is HostedServiceObservation.Unavailable -> Refinement.Rejected(observed.failure)
            }
        is ExistingIdeExchange.Rejected -> Refinement.Rejected(exchange.failure)
        is ExistingIdeExchange.HostRejected,
        is ExistingIdeExchange.Semantic -> Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
    }
}

private val PROJECT_DIRECTORY = Regex("[0-9a-f]{32}")
private const val MAXIMUM_HOST_ADMISSION_ENTRIES = 64
private const val MAXIMUM_HOST_DESCRIPTOR_BYTES = 16_384
private const val HOST_ROOT_DIGEST_PREFIX_BYTES = 16
private const val HOST_ADMISSION_EXCHANGE_MILLIS = 2_000L
private const val HOST_ADMISSION_SCAN_NANOS = 10_000_000_000L
