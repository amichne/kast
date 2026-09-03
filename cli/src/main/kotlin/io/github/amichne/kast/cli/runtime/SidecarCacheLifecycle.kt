package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

private const val SIDECAR_CACHE_IDENTITY_FILE = "cache-identity.properties"
private const val SIDECAR_CACHE_STATE_FILE = "cache-state"
private const val SIDECAR_CACHE_IDENTITY_FORMAT = "kast.sidecar-cache.identity.v2"
private const val SIDECAR_CACHE_IDENTITY_LEGACY_FORMAT = "kast.sidecar-cache.identity.v1"

sealed interface CacheStateObservation {
    data class Observed(val state: KastCacheState) : CacheStateObservation
    data object Absent : CacheStateObservation
    data object Rejected : CacheStateObservation
}

enum class CacheStateTransition { Recorded, Rejected }

/** Atomic state marker owned by one already-admitted cache directory. */
internal object SidecarCacheStateFile {
    fun observe(cacheRoot: Path): CacheStateObservation {
        val path = cacheRoot.resolve(SIDECAR_CACHE_STATE_FILE)
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return CacheStateObservation.Absent
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return CacheStateObservation.Rejected
        }
        val value = try {
            Files.readString(path).trim()
        } catch (_: IOException) {
            return CacheStateObservation.Rejected
        } catch (_: SecurityException) {
            return CacheStateObservation.Rejected
        }
        val state = KastCacheState.entries.singleOrNull { it.wireName == value }
            ?: return CacheStateObservation.Rejected
        return CacheStateObservation.Observed(state)
    }

    fun record(cacheRoot: Path, state: KastCacheState): CacheStateTransition {
        val canonical = canonicalSidecarDirectory(cacheRoot)
            ?: return CacheStateTransition.Rejected
        val target = canonical.resolve(SIDECAR_CACHE_STATE_FILE)
        if (Files.isSymbolicLink(target)) return CacheStateTransition.Rejected
        val staging = try {
            Files.createTempFile(canonical, ".cache-state-", ".partial")
        } catch (_: IOException) {
            return CacheStateTransition.Rejected
        } catch (_: SecurityException) {
            return CacheStateTransition.Rejected
        }
        return try {
            Files.writeString(staging, state.wireName + "\n")
            Files.move(
                staging,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            CacheStateTransition.Recorded
        } catch (_: AtomicMoveNotSupportedException) {
            CacheStateTransition.Rejected
        } catch (_: IOException) {
            CacheStateTransition.Rejected
        } catch (_: SecurityException) {
            CacheStateTransition.Rejected
        } finally {
            try {
                Files.deleteIfExists(staging)
            } catch (_: IOException) {
                // The unpublished marker is never authoritative.
            }
        }
    }
}

data class RootSidecarCacheStatus(
    val cacheIdentity: String,
    val semanticRuntimeId: SemanticRuntimeId,
    val cacheRoot: Path,
    val state: KastCacheState,
    val ideaHome: Path,
    val ideaBuild: String,
    val kotlinPluginBuild: String,
    val jbrIdentity: String,
    val kastPayloadDigest: String,
)

enum class SidecarCacheLifecycleFailure {
    FILESYSTEM_REJECTED,
    INVALID_IDENTITY,
    AMBIGUOUS_IDENTITY,
    INVALID_STATE,
    QUARANTINE_FAILED,
}

sealed interface RootSidecarCacheObservation {
    sealed interface Identified : RootSidecarCacheObservation {
        val status: RootSidecarCacheStatus
    }

    data class Observed(override val status: RootSidecarCacheStatus) : Identified
    data class Stale(override val status: RootSidecarCacheStatus) : Identified
    data object Absent : RootSidecarCacheObservation
    data class Rejected(val failure: SidecarCacheLifecycleFailure) :
        RootSidecarCacheObservation
}

sealed interface RootSidecarCacheQuarantine {
    data class Quarantined(
        val quarantinedRoot: Path,
        val restart: RuntimeStartupRequest,
    ) : RootSidecarCacheQuarantine

    data class NoCache(val restart: RuntimeStartupRequest = RuntimeStartupRequest.Default) :
        RootSidecarCacheQuarantine

    data class Rejected(val failure: SidecarCacheLifecycleFailure) :
        RootSidecarCacheQuarantine
}

interface RootSidecarCacheLifecycle {
    fun observe(root: Path): RootSidecarCacheObservation
    fun quarantine(root: Path): RootSidecarCacheQuarantine
}

data object NoRootSidecarCacheLifecycle : RootSidecarCacheLifecycle {
    override fun observe(root: Path): RootSidecarCacheObservation =
        RootSidecarCacheObservation.Absent

    override fun quarantine(root: Path): RootSidecarCacheQuarantine =
        RootSidecarCacheQuarantine.NoCache()
}

/** Filesystem registry for exact cache identities; no source IDEA path is ever accepted here. */
class FilesystemRootSidecarCacheLifecycle(
    private val cacheRoot: Path,
    private val releaseIdentity: SidecarCacheReleaseIdentity,
    private val installedRuntimeResolver: SidecarIdeRuntimeResolver,
) : RootSidecarCacheLifecycle {
    override fun observe(root: Path): RootSidecarCacheObservation =
        when (val match = matching(root)) {
            CacheIdentityMatch.Absent -> RootSidecarCacheObservation.Absent
            is CacheIdentityMatch.Rejected -> RootSidecarCacheObservation.Rejected(match.failure)
            is CacheIdentityMatch.Current -> observe(
                match.record,
                CacheIdentityFreshness.CURRENT,
            )
            is CacheIdentityMatch.Stale -> observe(
                match.record,
                CacheIdentityFreshness.STALE,
            )
        }

    override fun quarantine(root: Path): RootSidecarCacheQuarantine =
        when (val match = matching(root)) {
            CacheIdentityMatch.Absent -> RootSidecarCacheQuarantine.NoCache()
            is CacheIdentityMatch.Rejected -> RootSidecarCacheQuarantine.Rejected(match.failure)
            is CacheIdentityMatch.Current -> quarantine(match.record)
            is CacheIdentityMatch.Stale -> quarantine(match.record)
        }

    private fun observe(
        record: CacheIdentityRecord,
        freshness: CacheIdentityFreshness,
    ): RootSidecarCacheObservation = when (
        val state = SidecarCacheStateFile.observe(record.cacheRoot)
    ) {
        CacheStateObservation.Absent -> RootSidecarCacheObservation.Rejected(
            SidecarCacheLifecycleFailure.INVALID_STATE,
        )
        CacheStateObservation.Rejected -> RootSidecarCacheObservation.Rejected(
            SidecarCacheLifecycleFailure.INVALID_STATE,
        )
        is CacheStateObservation.Observed -> when (freshness) {
            CacheIdentityFreshness.CURRENT -> RootSidecarCacheObservation.Observed(
                record.status(state.state),
            )
            CacheIdentityFreshness.STALE -> RootSidecarCacheObservation.Stale(
                record.status(state.state),
            )
        }
    }

    private fun matching(root: Path): CacheIdentityMatch {
        if (Files.notExists(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            return CacheIdentityMatch.Absent
        }
        val canonicalCacheRoot = physicalSidecarDirectory(cacheRoot)
            ?: return CacheIdentityMatch.Rejected(
                SidecarCacheLifecycleFailure.FILESYSTEM_REJECTED,
            )
        val canonicalProject = canonicalSidecarDirectory(root)
            ?: return CacheIdentityMatch.Rejected(
                SidecarCacheLifecycleFailure.INVALID_IDENTITY,
            )
        val records = mutableListOf<CacheIdentityRecord>()
        try {
            val children = Files.list(canonicalCacheRoot).use { paths ->
                paths.filter { child ->
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) &&
                        child.fileName.toString() != "quarantine"
                }.toList()
            }
            for (child in children) {
                when (val read = readIdentity(child, canonicalProject)) {
                    CacheIdentityRead.Unrelated -> Unit
                    is CacheIdentityRead.Read -> records += read.record
                    CacheIdentityRead.Rejected -> return CacheIdentityMatch.Rejected(
                        SidecarCacheLifecycleFailure.INVALID_IDENTITY,
                    )
                }
            }
        } catch (_: IOException) {
            return CacheIdentityMatch.Rejected(
                SidecarCacheLifecycleFailure.FILESYSTEM_REJECTED,
            )
        } catch (_: SecurityException) {
            return CacheIdentityMatch.Rejected(
                SidecarCacheLifecycleFailure.FILESYSTEM_REJECTED,
            )
        }
        val releasedRecords = records.filter { record ->
            releaseIdentity.admits(record.identity)
        }
        val currentRecords = releasedRecords.filter { record ->
            when (
                val discovery = releaseIdentity.discoverCurrentRuntime(
                    record.ideaHome,
                    installedRuntimeResolver,
                )
            ) {
                is InstalledIdeRuntimeDiscoveryResult.Discovered ->
                    discovery.runtime.home == record.identity.ideaHome &&
                        discovery.runtime.javaExecutable == record.identity.javaExecutable &&
                        discovery.runtime.identity == record.identity.runtimeIdentity
                is InstalledIdeRuntimeDiscoveryResult.Rejected -> false
            }
        }
        if (currentRecords.size > 1) {
            return CacheIdentityMatch.Rejected(
                SidecarCacheLifecycleFailure.AMBIGUOUS_IDENTITY,
            )
        }
        if (currentRecords.size == 1) {
            return CacheIdentityMatch.Current(currentRecords.single())
        }
        return when (records.size) {
            0 -> CacheIdentityMatch.Absent
            1 -> CacheIdentityMatch.Stale(records.single())
            else -> CacheIdentityMatch.Rejected(
                SidecarCacheLifecycleFailure.AMBIGUOUS_IDENTITY,
            )
        }
    }

    private fun quarantine(record: CacheIdentityRecord): RootSidecarCacheQuarantine {
        val canonicalCacheRoot = physicalSidecarDirectory(cacheRoot)
            ?: return RootSidecarCacheQuarantine.Rejected(
                SidecarCacheLifecycleFailure.FILESYSTEM_REJECTED,
            )
        val quarantineRoot = try {
            Files.createDirectories(canonicalCacheRoot.resolve("quarantine"))
        } catch (_: IOException) {
            return RootSidecarCacheQuarantine.Rejected(
                SidecarCacheLifecycleFailure.QUARANTINE_FAILED,
            )
        } catch (_: SecurityException) {
            return RootSidecarCacheQuarantine.Rejected(
                SidecarCacheLifecycleFailure.QUARANTINE_FAILED,
            )
        }
        if (Files.isSymbolicLink(quarantineRoot)) {
            return RootSidecarCacheQuarantine.Rejected(
                SidecarCacheLifecycleFailure.QUARANTINE_FAILED,
            )
        }
        val target = quarantineRoot.resolve("${record.identity.key}.${UUID.randomUUID()}")
        try {
            Files.move(record.cacheRoot, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            return RootSidecarCacheQuarantine.Rejected(
                SidecarCacheLifecycleFailure.QUARANTINE_FAILED,
            )
        } catch (_: IOException) {
            return RootSidecarCacheQuarantine.Rejected(
                SidecarCacheLifecycleFailure.QUARANTINE_FAILED,
            )
        } catch (_: SecurityException) {
            return RootSidecarCacheQuarantine.Rejected(
                SidecarCacheLifecycleFailure.QUARANTINE_FAILED,
            )
        }
        return RootSidecarCacheQuarantine.Quarantined(
            target,
            RuntimeStartupRequest.Requested(
                StartupIdeHome.Explicit(record.ideaHome),
                StartupCacheIntent.Reuse,
            ),
        )
    }
}

internal enum class CacheIdentityTransition { Recorded, Rejected }

internal object SidecarCacheIdentityFile {
    fun record(
        cacheRoot: Path,
        runtime: InstalledIdeRuntime,
        identity: KastCacheIdentity,
    ): CacheIdentityTransition {
        if (
            runtime.home != identity.ideaHome ||
            runtime.javaExecutable != identity.javaExecutable ||
            runtime.identity != identity.runtimeIdentity
        ) {
            return CacheIdentityTransition.Rejected
        }
        val canonical = canonicalSidecarDirectory(cacheRoot)
            ?: return CacheIdentityTransition.Rejected
        val properties = Properties().apply {
            setProperty("format", SIDECAR_CACHE_IDENTITY_FORMAT)
            setProperty("cache.key", identity.key)
            setProperty("semantic.runtime.id", identity.semanticRuntimeId.value)
            setProperty("project.root", identity.canonicalProjectRoot.toString())
            setProperty("idea.home", identity.ideaHome.toString())
            setProperty("java.executable", identity.javaExecutable.toString())
            setProperty("idea.build", identity.runtimeIdentity.supportedPair.ideaBuild)
            setProperty(
                "kotlin.plugin.build",
                identity.runtimeIdentity.supportedPair.kotlinPluginBuild,
            )
            setProperty("jbr.identity", identity.runtimeIdentity.jbrIdentity)
            setProperty("kast.payload.digest", identity.runtimeIdentity.kastPayloadDigest)
        }
        val staging = try {
            Files.createTempFile(canonical, ".cache-identity-", ".partial")
        } catch (_: IOException) {
            return CacheIdentityTransition.Rejected
        } catch (_: SecurityException) {
            return CacheIdentityTransition.Rejected
        }
        return try {
            Files.newOutputStream(staging).use { output ->
                properties.store(output, null)
            }
            Files.move(
                staging,
                canonical.resolve(SIDECAR_CACHE_IDENTITY_FILE),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            CacheIdentityTransition.Recorded
        } catch (_: AtomicMoveNotSupportedException) {
            CacheIdentityTransition.Rejected
        } catch (_: IOException) {
            CacheIdentityTransition.Rejected
        } catch (_: SecurityException) {
            CacheIdentityTransition.Rejected
        } finally {
            try {
                Files.deleteIfExists(staging)
            } catch (_: IOException) {
                // The unpublished identity is never authoritative.
            }
        }
    }
}

private data class CacheIdentityRecord(
    val cacheRoot: Path,
    val ideaHome: Path,
    val identity: KastCacheIdentity,
) {
    fun status(state: KastCacheState): RootSidecarCacheStatus = RootSidecarCacheStatus(
        identity.key,
        identity.semanticRuntimeId,
        cacheRoot,
        state,
        ideaHome,
        identity.runtimeIdentity.supportedPair.ideaBuild,
        identity.runtimeIdentity.supportedPair.kotlinPluginBuild,
        identity.runtimeIdentity.jbrIdentity,
        identity.runtimeIdentity.kastPayloadDigest,
    )
}

private sealed interface CacheIdentityRead {
    data class Read(val record: CacheIdentityRecord) : CacheIdentityRead
    data object Unrelated : CacheIdentityRead
    data object Rejected : CacheIdentityRead
}

private sealed interface CacheIdentityMatch {
    data class Current(val record: CacheIdentityRecord) : CacheIdentityMatch
    data class Stale(val record: CacheIdentityRecord) : CacheIdentityMatch
    data object Absent : CacheIdentityMatch
    data class Rejected(val failure: SidecarCacheLifecycleFailure) : CacheIdentityMatch
}

private enum class CacheIdentityFreshness {
    CURRENT,
    STALE,
}

private fun readIdentity(
    cacheDirectory: Path,
    canonicalProject: Path,
): CacheIdentityRead {
    val receipt = cacheDirectory.resolve(SIDECAR_CACHE_IDENTITY_FILE)
    if (Files.notExists(receipt, LinkOption.NOFOLLOW_LINKS)) return CacheIdentityRead.Unrelated
    if (Files.isSymbolicLink(receipt) || !Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
        return CacheIdentityRead.Rejected
    }
    val values = try {
        Properties().apply { Files.newInputStream(receipt).use(::load) }
    } catch (_: IOException) {
        return CacheIdentityRead.Rejected
    } catch (_: SecurityException) {
        return CacheIdentityRead.Rejected
    }
    when (values.getProperty("format")) {
        SIDECAR_CACHE_IDENTITY_FORMAT -> Unit
        SIDECAR_CACHE_IDENTITY_LEGACY_FORMAT -> return CacheIdentityRead.Unrelated
        else -> return CacheIdentityRead.Rejected
    }
    val rawProject = values.getProperty("project.root") ?: return CacheIdentityRead.Rejected
    val project = try {
        Path.of(rawProject)
    } catch (_: RuntimeException) {
        return CacheIdentityRead.Rejected
    }
    if (project != canonicalProject) return CacheIdentityRead.Unrelated
    val pair = when (
        val admission = SupportedIdeRuntimePair.admit(
            values.getProperty("idea.build").orEmpty(),
            values.getProperty("kotlin.plugin.build").orEmpty(),
        )
    ) {
        is SupportedIdeRuntimePairAdmission.Admitted -> admission.pair
        is SupportedIdeRuntimePairAdmission.Rejected -> return CacheIdentityRead.Rejected
    }
    val runtime = when (
        val admission = IdeRuntimeIdentity.admit(
            pair,
            IdeRuntimeIdentityCandidate(
                pair.ideaBuild,
                pair.kotlinPluginBuild,
                values.getProperty("jbr.identity").orEmpty(),
                values.getProperty("kast.payload.digest").orEmpty(),
            ),
        )
    ) {
        is IdeRuntimeIdentityAdmission.Admitted -> admission.identity
        is IdeRuntimeIdentityAdmission.Rejected -> return CacheIdentityRead.Rejected
    }
    val semanticRuntimeId = when (
        val admission = SemanticRuntimeId.parse(
            values.getProperty("semantic.runtime.id").orEmpty(),
        )
    ) {
        is Refinement.Refined -> admission.value
        is Refinement.Rejected -> return CacheIdentityRead.Rejected
    }
    val ideaHome = try {
        Path.of(values.getProperty("idea.home") ?: return CacheIdentityRead.Rejected)
    } catch (_: RuntimeException) {
        return CacheIdentityRead.Rejected
    }
    if (canonicalSidecarDirectory(ideaHome) != ideaHome) return CacheIdentityRead.Rejected
    val javaExecutable = try {
        val recorded = Path.of(
            values.getProperty("java.executable") ?: return CacheIdentityRead.Rejected,
        )
        recorded.toRealPath().takeIf { it == recorded }
            ?: return CacheIdentityRead.Rejected
    } catch (_: IOException) {
        return CacheIdentityRead.Rejected
    } catch (_: SecurityException) {
        return CacheIdentityRead.Rejected
    } catch (_: RuntimeException) {
        return CacheIdentityRead.Rejected
    }
    val installedRuntime = InstalledIdeRuntime(ideaHome, javaExecutable, runtime)
    val identity = when (
        val derivation = KastCacheIdentity.derive(
            project,
            installedRuntime,
            semanticRuntimeId,
        )
    ) {
        is KastCacheIdentityDerivation.Derived -> derivation.identity
        is KastCacheIdentityDerivation.Rejected -> return CacheIdentityRead.Rejected
    }
    val canonicalCache = canonicalSidecarDirectory(cacheDirectory)
        ?: return CacheIdentityRead.Rejected
    if (
        canonicalCache.fileName.toString() != identity.key ||
        values.getProperty("cache.key") != identity.key
    ) {
        return CacheIdentityRead.Rejected
    }
    return CacheIdentityRead.Read(CacheIdentityRecord(canonicalCache, ideaHome, identity))
}

private fun canonicalSidecarDirectory(path: Path): Path? {
    val physical = physicalSidecarDirectory(path) ?: return null
    return physical.takeIf { it == path }
}

private fun physicalSidecarDirectory(path: Path): Path? {
    if (!path.isAbsolute || path.normalize() != path) return null
    return try {
        path.toRealPath().takeIf { physical ->
            Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
