package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Properties

private val SIDECAR_PAYLOAD_DIGEST = Regex("sha256:[0-9a-f]{64}")
private const val SIDECAR_SEED_RECEIPT = "seed-receipt.properties"

enum class SidecarPayloadFailure { EXECUTABLE_INVALID, PLUGINS_INVALID, DIGEST_INVALID }

/** Exact small launcher/private-plugin payload; no IntelliJ distribution is represented here. */
class SidecarPayload private constructor(
    val runtimeId: SemanticRuntimeId,
    val executable: IndexerExecutable,
    val privatePluginsDirectory: Path,
    val digest: String,
) {
    companion object {
        fun admit(
            runtimeId: SemanticRuntimeId,
            executable: Path,
            privatePluginsDirectory: Path,
            digest: String,
        ): SidecarPayloadAdmission {
            val admittedExecutable = when (val admission = IndexerExecutable.admit(executable)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return SidecarPayloadAdmission.Rejected(
                    SidecarPayloadFailure.EXECUTABLE_INVALID,
                )
            }
            val plugins = canonicalDirectory(privatePluginsDirectory)
                ?: return SidecarPayloadAdmission.Rejected(SidecarPayloadFailure.PLUGINS_INVALID)
            if (!SIDECAR_PAYLOAD_DIGEST.matches(digest)) {
                return SidecarPayloadAdmission.Rejected(SidecarPayloadFailure.DIGEST_INVALID)
            }
            return SidecarPayloadAdmission.Admitted(
                SidecarPayload(runtimeId, admittedExecutable, plugins, digest),
            )
        }
    }
}

sealed interface SidecarPayloadAdmission {
    data class Admitted(val payload: SidecarPayload) : SidecarPayloadAdmission
    data class Rejected(val failure: SidecarPayloadFailure) : SidecarPayloadAdmission
}

sealed interface SidecarPayloadResolution {
    data class Resolved(val payload: SidecarPayload) : SidecarPayloadResolution
    data class Rejected(val failure: RuntimeAdmissionFailure) : SidecarPayloadResolution
}

fun interface SidecarPayloadResolver {
    fun resolve(): SidecarPayloadResolution
}

fun interface SidecarIdeRuntimeResolver {
    fun resolve(
        support: SupportedIdeRuntimePair,
        payloadDigest: String,
        selection: IdeHomeSelection,
    ): InstalledIdeRuntimeDiscoveryResult
}

enum class KastCacheState(val wireName: String) {
    FRESH("fresh"),
    SEEDED("seeded"),
    REFRESHING("refreshing"),
    SMART("smart"),
    REBUILD_REQUIRED("rebuild-required"),
}

sealed interface SidecarCacheFailure {
    data object FilesystemRejected : SidecarCacheFailure
    data object RebuildRequired : SidecarCacheFailure
    data class SeedRejected(val failure: IndexSeedFailure) : SidecarCacheFailure
}

/** Private cache layout admitted for one exact cache identity. */
class PreparedSidecarCache private constructor(
    val identity: KastCacheIdentity,
    val root: Path,
    val systemDirectory: Path,
    val configDirectory: Path,
    val logDirectory: Path,
    val state: KastCacheState,
) {
    companion object {
        fun admit(
            identity: KastCacheIdentity,
            root: Path,
            systemDirectory: Path,
            configDirectory: Path,
            logDirectory: Path,
            state: KastCacheState,
        ): SidecarCachePreparation {
            val canonicalRoot = canonicalDirectory(root)
                ?: return SidecarCachePreparation.Rejected(
                    SidecarCacheFailure.FilesystemRejected,
                )
            if (canonicalRoot.fileName.toString() != identity.key) {
                return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
            }
            val paths = listOf(systemDirectory, configDirectory, logDirectory).map { path ->
                canonicalDirectory(path)
                    ?: return SidecarCachePreparation.Rejected(
                        SidecarCacheFailure.FilesystemRejected,
                    )
            }
            if (paths.distinct().size != paths.size || paths.any { !it.startsWith(canonicalRoot) }) {
                return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
            }
            if (paths.indices.any { left ->
                    paths.indices.any { right ->
                        left != right && paths[left].startsWith(paths[right])
                    }
                }
            ) {
                return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
            }
            return SidecarCachePreparation.Prepared(
                PreparedSidecarCache(
                    identity,
                    canonicalRoot,
                    paths[0],
                    paths[1],
                    paths[2],
                    state,
                ),
            )
        }
    }
}

sealed interface SidecarCachePreparation {
    data class Prepared(val cache: PreparedSidecarCache) : SidecarCachePreparation
    data class Rejected(val failure: SidecarCacheFailure) : SidecarCachePreparation
}

fun interface SidecarCachePreparer {
    fun prepare(
        runtime: InstalledIdeRuntime,
        cacheIdentity: KastCacheIdentity,
        intent: StartupCacheIntent,
    ): SidecarCachePreparation
}

/** Process-bound proof retaining both the cache lifecycle state and launch context. */
class PreparedSidecarLaunch internal constructor(
    val cache: PreparedSidecarCache,
    val context: SidecarLaunchContext,
) {
    val runtime: InstalledIdeRuntime get() = context.runtime
    val systemDirectory: Path get() = context.systemDirectory
    val cacheState: KastCacheState get() = cache.state
}

fun interface SidecarProcessDemander {
    fun demand(
        executable: IndexerExecutable,
        launch: PreparedSidecarLaunch,
        root: CanonicalRoot,
        endpoint: RuntimeEndpoint,
    ): RuntimeAdmission
}

internal class ExactSidecarProcessDemander(
    private val endpointProbe: RuntimeEndpointProbe = JdkUnixDomainEndpointProbe,
    private val runtimeDemanderFactory: (
        IndexerExecutable,
        SidecarLaunchContext,
    ) -> RuntimeDemander = { executable, context ->
        ExactRootProcessRuntimeDemander(executable, context)
    },
) : SidecarProcessDemander {
    override fun demand(
        executable: IndexerExecutable,
        launch: PreparedSidecarLaunch,
        root: CanonicalRoot,
        endpoint: RuntimeEndpoint,
    ): RuntimeAdmission {
        if (endpoint.root != root) {
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
        }
        if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
            return RuntimeAdmission.Ready(endpoint)
        }
        if (
            SidecarCacheStateFile.record(launch.cache.root, KastCacheState.REFRESHING) !=
            CacheStateTransition.Recorded
        ) {
            return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.SidecarCacheRejected(
                    SidecarCacheFailure.FilesystemRejected,
                ),
            )
        }
        return runtimeDemanderFactory(executable, launch.context).demand(root, endpoint)
    }
}

/**
 * Root-level installed sidecar admission.
 *
 * Payload, installed IDEA, cache identity, cache state, and launch context are each refined before
 * the next effect can run. Ordinary startup supplies [StartupCacheIntent.ReuseOrFresh], so no
 * source IDEA system path exists in that execution branch.
 */
class InstalledSidecarRootRuntimeDemander(
    private val endpointLocator: RuntimeEndpointLocator,
    private val support: SupportedIdeRuntimePair,
    private val userHome: Path,
    private val payloadResolver: SidecarPayloadResolver,
    private val ideRuntimeResolver: SidecarIdeRuntimeResolver,
    private val cachePreparer: SidecarCachePreparer,
    private val processDemander: SidecarProcessDemander = ExactSidecarProcessDemander(),
    private val legacyEndpointProbe: RuntimeEndpointProbe = JdkUnixDomainEndpointProbe,
    private val legacyProcessAuthority: RuntimeProcessAuthority = JdkRuntimeProcessAuthority,
) : RootRuntimeDemander {
    override fun demand(
        root: CanonicalRoot,
        demand: HostedRuntimeDemand,
        startup: RuntimeStartupRequest,
    ): RuntimeAdmission {
        val endpoint = when (val resolution = endpointLocator.locate(root)) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.EndpointUnavailable,
            )
        }
        val payload = when (val resolution = payloadResolver.resolve()) {
            is SidecarPayloadResolution.Resolved -> resolution.payload
            is SidecarPayloadResolution.Rejected -> return RuntimeAdmission.Rejected(
                resolution.failure,
            )
        }
        if (payload.runtimeId != endpoint.runtimeId) {
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.RuntimeIdentityMismatch)
        }
        val selection = when (val requested = startup.ideHome) {
            StartupIdeHome.Standard -> IdeHomeSelection.standard(userHome)
            is StartupIdeHome.Explicit -> IdeHomeSelection.Explicit(requested.path)
        }
        val runtime = when (
            val resolution = ideRuntimeResolver.resolve(support, payload.digest, selection)
        ) {
            is InstalledIdeRuntimeDiscoveryResult.Discovered -> resolution.runtime
            is InstalledIdeRuntimeDiscoveryResult.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.InstalledIdeRejected(resolution.failure),
            )
        }
        val cacheIdentity = when (
            val derivation = KastCacheIdentity.derive(root.path, runtime, endpoint.runtimeId)
        ) {
            is KastCacheIdentityDerivation.Derived -> derivation.identity
            is KastCacheIdentityDerivation.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.SidecarCacheRejected(
                    SidecarCacheFailure.FilesystemRejected,
                ),
            )
        }
        val exactEndpoint = when (
            val resolution = endpoint.forSidecarCache(
                cacheIdentity.key,
                cacheIdentity.semanticRuntimeId,
            )
        ) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.EndpointUnavailable,
            )
        }
        val legacyProcessPresent = when (legacyProcessAuthority.observe(endpoint)) {
            RuntimeProcessObservation.Absent -> false
            RuntimeProcessObservation.Ambiguous,
            is RuntimeProcessObservation.Owned,
                -> true
        }
        if (endpoint != exactEndpoint && (
                legacyProcessPresent ||
                    legacyEndpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable
                )
        ) {
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.LegacySidecarActive)
        }
        val cache = when (
            val preparation = cachePreparer.prepare(
                runtime,
                cacheIdentity,
                startup.cacheIntent,
            )
        ) {
            is SidecarCachePreparation.Prepared -> preparation.cache
            is SidecarCachePreparation.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.SidecarCacheRejected(preparation.failure),
            )
        }
        val context = when (
            val admission = SidecarLaunchContext.admit(
                runtime,
                cache.root,
                cache.systemDirectory,
                cache.configDirectory,
                cache.logDirectory,
                payload.privatePluginsDirectory,
            )
        ) {
            is SidecarLaunchContextAdmission.Admitted -> admission.context
            is SidecarLaunchContextAdmission.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.LayoutInvalid,
            )
        }
        return processDemander.demand(
            payload.executable,
            PreparedSidecarLaunch(cache, context),
            root,
            exactEndpoint,
        )
    }
}

/** Real filesystem cache owner used by the installed composition. */
class FilesystemSidecarCachePreparer(
    private val cacheRoot: Path,
    private val defaultSourceSystem: Path,
    private val seedService: IndexSeedFilesystemService,
) : SidecarCachePreparer {
    override fun prepare(
        runtime: InstalledIdeRuntime,
        cacheIdentity: KastCacheIdentity,
        intent: StartupCacheIntent,
    ): SidecarCachePreparation {
        val root = prepareCacheRoot()
            ?: return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
        val preparation = when (intent) {
            StartupCacheIntent.ReuseOrFresh -> prepareExistingOrFresh(root, cacheIdentity)
            is StartupCacheIntent.Seed -> seed(root, runtime, cacheIdentity, intent)
        }
        val cache = when (preparation) {
            is SidecarCachePreparation.Prepared -> preparation.cache
            is SidecarCachePreparation.Rejected -> return preparation
        }
        if (
            SidecarCacheIdentityFile.record(cache.root, runtime, cacheIdentity) !=
            CacheIdentityTransition.Recorded
        ) {
            return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
        }
        when (SidecarCacheStateFile.observe(cache.root)) {
            CacheStateObservation.Absent -> if (
                SidecarCacheStateFile.record(cache.root, cache.state) != CacheStateTransition.Recorded
            ) {
                return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
            }
            CacheStateObservation.Rejected -> return SidecarCachePreparation.Rejected(
                SidecarCacheFailure.RebuildRequired,
            )
            is CacheStateObservation.Observed -> Unit
        }
        return preparation
    }

    private fun seed(
        cacheRoot: Path,
        runtime: InstalledIdeRuntime,
        cacheIdentity: KastCacheIdentity,
        intent: StartupCacheIntent.Seed,
    ): SidecarCachePreparation {
        val source = when (val requested = intent.sourceSystem) {
            StartupIdeaSystem.Standard -> defaultSourceSystem
            is StartupIdeaSystem.Explicit -> requested.path
        }
        val publication = when (
            val execution = seedService.seed(
                IndexSeedRequest(
                    source,
                    cacheRoot,
                    runtime,
                    cacheIdentity,
                    intent.consentRequest,
                ),
            )
        ) {
            is IndexSeedExecution.Seeded -> execution.publication
            is IndexSeedExecution.Rejected -> return SidecarCachePreparation.Rejected(
                SidecarCacheFailure.SeedRejected(execution.failure),
            )
        }
        val config = createPrivateDirectory(publication.root.resolve("config"))
            ?: return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
        val log = createPrivateDirectory(publication.root.resolve("log"))
            ?: return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
        return PreparedSidecarCache.admit(
            cacheIdentity,
            publication.root,
            publication.systemDirectory,
            config,
            log,
            KastCacheState.SEEDED,
        )
    }

    private fun prepareExistingOrFresh(
        cacheRoot: Path,
        cacheIdentity: KastCacheIdentity,
    ): SidecarCachePreparation {
        val root = cacheRoot.resolve(cacheIdentity.key)
        val created = try {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(root)
                true
            } else {
                false
            }
        } catch (_: IOException) {
            return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
        } catch (_: SecurityException) {
            return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
        }
        val canonicalRoot = canonicalDirectory(root)
            ?: return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
        val system = createPrivateDirectory(canonicalRoot.resolve("system"))
            ?: return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
        val config = createPrivateDirectory(canonicalRoot.resolve("config"))
            ?: return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
        val log = createPrivateDirectory(canonicalRoot.resolve("log"))
            ?: return SidecarCachePreparation.Rejected(SidecarCacheFailure.FilesystemRejected)
        val state = when (val recorded = SidecarCacheStateFile.observe(canonicalRoot)) {
            is CacheStateObservation.Observed -> recorded.state
            CacheStateObservation.Rejected -> return SidecarCachePreparation.Rejected(
                SidecarCacheFailure.RebuildRequired,
            )
            CacheStateObservation.Absent -> if (created) {
                KastCacheState.FRESH
            } else {
                when (admitSeedReceipt(canonicalRoot, cacheIdentity)) {
                    SeedReceiptPresence.Absent -> KastCacheState.FRESH
                    SeedReceiptPresence.Valid -> KastCacheState.SEEDED
                    SeedReceiptPresence.Invalid -> return SidecarCachePreparation.Rejected(
                        SidecarCacheFailure.RebuildRequired,
                    )
                }
            }
        }
        return PreparedSidecarCache.admit(
            cacheIdentity,
            canonicalRoot,
            system,
            config,
            log,
            state,
        )
    }

    private fun prepareCacheRoot(): Path? = try {
        if (Files.isSymbolicLink(cacheRoot)) return null
        Files.createDirectories(cacheRoot)
        if (Files.isSymbolicLink(cacheRoot)) return null
        cacheRoot.toRealPath().takeIf { physical ->
            Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private sealed interface SeedReceiptPresence {
    data object Absent : SeedReceiptPresence
    data object Valid : SeedReceiptPresence
    data object Invalid : SeedReceiptPresence
}

private fun admitSeedReceipt(
    cacheRoot: Path,
    identity: KastCacheIdentity,
): SeedReceiptPresence {
    val receipt = cacheRoot.resolve(SIDECAR_SEED_RECEIPT)
    if (Files.notExists(receipt, LinkOption.NOFOLLOW_LINKS)) return SeedReceiptPresence.Absent
    if (Files.isSymbolicLink(receipt) || !Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
        return SeedReceiptPresence.Invalid
    }
    return try {
        val values = Properties().apply {
            Files.newInputStream(receipt).use(::load)
        }
        if (
            values.getProperty("format") == "kast.index-seed.receipt.v1" &&
            values.getProperty("cache.key") == identity.key &&
            values.getProperty("project.root") == identity.canonicalProjectRoot.toString() &&
            values.getProperty("idea.build") == identity.runtimeIdentity.supportedPair.ideaBuild &&
            values.getProperty("kotlin.plugin.build") ==
            identity.runtimeIdentity.supportedPair.kotlinPluginBuild &&
            values.getProperty("jbr.identity") == identity.runtimeIdentity.jbrIdentity &&
            values.getProperty("kast.payload.digest") == identity.runtimeIdentity.kastPayloadDigest
        ) {
            SeedReceiptPresence.Valid
        } else {
            SeedReceiptPresence.Invalid
        }
    } catch (_: IOException) {
        SeedReceiptPresence.Invalid
    } catch (_: SecurityException) {
        SeedReceiptPresence.Invalid
    }
}

private fun createPrivateDirectory(path: Path): Path? = try {
    if (Files.isSymbolicLink(path)) return null
    Files.createDirectories(path)
    canonicalDirectory(path)
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private fun canonicalDirectory(path: Path): Path? {
    if (!path.isAbsolute || path.normalize() != path) return null
    return try {
        path.toRealPath().takeIf { canonical ->
            canonical == path && Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
