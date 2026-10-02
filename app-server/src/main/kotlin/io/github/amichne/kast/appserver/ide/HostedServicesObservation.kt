package io.github.amichne.kast.appserver.ide

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/** Read existing endpoint identities, then admit each actual live describe response. No discovery protocol is added. */
fun observeRunningHostedServices(home: Path): HostedServicesObservation =
    try {
        val directory = home.resolve(".kast/ide-hosted")
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
            HostedServicesObservation.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
        else
            Files.newDirectoryStream(directory).use { entries ->
                observeHostedEntries(
                    directory,
                    entries,
                    ExistingIdeSocketClient(home, exchangeMillis = HOST_ADMISSION_EXCHANGE_MILLIS),
                )
            }
    } catch (_: java.io.IOException) {
        HostedServicesObservation.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        HostedServicesObservation.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
    } catch (_: kotlinx.serialization.SerializationException) {
        HostedServicesObservation.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
    } catch (_: SecurityException) {
        HostedServicesObservation.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
    }

private fun observeHostedEntries(
    directory: Path,
    entries: Iterable<Path>,
    client: ExistingIdeSocketClient,
): HostedServicesObservation {
    val hosts = ArrayList<AdmittedHostedService>()
    val deadline = System.nanoTime() + HOST_ADMISSION_SCAN_NANOS
    var count = 0
    for (entry in entries) {
        if (++count > MAXIMUM_HOST_ADMISSION_ENTRIES || System.nanoTime() >= deadline)
            return HostedServicesObservation.Rejected(ExistingIdeFailure.DEADLINE_EXCEEDED)
        if (!Files.exists(entry.resolve("endpoint.json"), NOFOLLOW_LINKS)) continue
        val root =
            when (val selected = selectHostedRoot(directory, entry)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return HostedServicesObservation.Rejected(selected.failure)
            }
        when (val observed = observeHostedService(client, root)) {
            is Refinement.Refined -> hosts += observed.value
            is Refinement.Rejected -> return HostedServicesObservation.Rejected(observed.failure)
        }
    }
    return HostedServicesObservation.Admitted.from(hosts)
}

private fun selectHostedRoot(directory: Path, entry: Path): Refinement<CanonicalRoot, ExistingIdeFailure> {
    val rejected = Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
    val descriptor = entry.resolve("endpoint.json")
    if (!Files.isRegularFile(descriptor, NOFOLLOW_LINKS)) return rejected
    val bytes =
        Files.newInputStream(descriptor, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_HOST_DESCRIPTOR_BYTES + 1) }
    if (bytes.size > MAXIMUM_HOST_DESCRIPTOR_BYTES) return rejected
    // Intentional root-selection projection; the client validates the full descriptor before connecting.
    val document = hostedRootJson.decodeFromString<HostedRootDocument>(bytes.decodeToString())
    val root =
        when (val discovered = FilesystemCanonicalRootDiscovery.discover(Path.of(document.root))) {
            is CanonicalRootDiscovery.Discovered -> discovered.root
            is CanonicalRootDiscovery.Rejected -> return rejected
        }
    val digest =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(root.path.toString().toByteArray())
            .take(HOST_ROOT_DIGEST_PREFIX_BYTES)
            .joinToString("") { "%02x".format(it) }
    if (root.path.toString() != document.root || entry != directory.resolve(digest) || entry.toRealPath() != entry)
        return rejected
    return Refinement.Refined(root)
}

private fun observeHostedService(
    client: ExistingIdeSocketClient,
    root: CanonicalRoot,
): Refinement<AdmittedHostedService, ExistingIdeFailure> =
    when (val exchange = client.query(root, ExistingIdeOperation.Status)) {
        is ExistingIdeExchange.Received ->
            when (val observed = client.latestObservation(root)) {
                is HostedServiceObservation.Compatible ->
                    Refinement.Refined(AdmittedHostedService(root, observed.descriptor, observed.compatibility))
                is HostedServiceObservation.Incompatible ->
                    Refinement.Rejected(ExistingIdeFailure.COMPATIBILITY_REJECTED)
                is HostedServiceObservation.Unavailable -> Refinement.Rejected(observed.failure)
            }
        is ExistingIdeExchange.Rejected -> Refinement.Rejected(exchange.failure)
        is ExistingIdeExchange.HostRejected,
        is ExistingIdeExchange.Semantic -> Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
    }

private const val MAXIMUM_HOST_ADMISSION_ENTRIES = 64
private const val MAXIMUM_HOST_DESCRIPTOR_BYTES = 16_384
private const val HOST_ROOT_DIGEST_PREFIX_BYTES = 16
private const val HOST_ADMISSION_EXCHANGE_MILLIS = 2_000L
private const val HOST_ADMISSION_SCAN_NANOS = 10_000_000_000L
private val hostedRootJson = Json { ignoreUnknownKeys = true }

@Serializable private data class HostedRootDocument(val root: String)
