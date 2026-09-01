package io.github.amichne.kast.cli

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

private val INDEX_SEED_COMPATIBILITY_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")
private val INDEX_SEED_DIGEST = Regex("sha256:[0-9a-f]{64}")
private val INDEX_SEED_ENTRY = Regex("[A-Za-z0-9._/-]+")

/** Every expected failure admitted by installed-runtime discovery and index seeding. */
sealed interface IndexSeedFailure {
    data class Incompatibility(
        val expected: SupportedIdeRuntimePair,
        val observedIdeaBuild: String,
        val observedKotlinPluginBuild: String,
    ) : IndexSeedFailure

    data object Ambiguity : IndexSeedFailure
    data object MissingInstallation : IndexSeedFailure
    data object RunningSourceIde : IndexSeedFailure
    data object ConsentAbsent : IndexSeedFailure
    data object UnsupportedFilesystem : IndexSeedFailure
    data object SourceMutation : IndexSeedFailure
    data object CopyFailure : IndexSeedFailure
    data object ValidationFailure : IndexSeedFailure
}

/** The sole IDEA and Kotlin build pair supported by one Kast release. */
class SupportedIdeRuntimePair private constructor(
    val ideaBuild: String,
    val kotlinPluginBuild: String,
) {
    override fun equals(other: Any?): Boolean =
        other is SupportedIdeRuntimePair &&
            ideaBuild == other.ideaBuild && kotlinPluginBuild == other.kotlinPluginBuild

    override fun hashCode(): Int = 31 * ideaBuild.hashCode() + kotlinPluginBuild.hashCode()

    companion object {
        /**
         * Proof transition: `String + String -> SupportedIdeRuntimePairAdmission`.
         *
         * Establishes two bounded compatibility tokens owned by release metadata. Malformed
         * metadata is closed [IndexSeedFailure.ValidationFailure]. Raw text may leave only when
         * comparing an installed IDEA boundary.
         */
        fun admit(
            ideaBuild: String,
            kotlinPluginBuild: String,
        ): SupportedIdeRuntimePairAdmission = if (
            INDEX_SEED_COMPATIBILITY_TOKEN.matches(ideaBuild) &&
            INDEX_SEED_COMPATIBILITY_TOKEN.matches(kotlinPluginBuild)
        ) {
            SupportedIdeRuntimePairAdmission.Admitted(
                SupportedIdeRuntimePair(ideaBuild, kotlinPluginBuild),
            )
        } else {
            SupportedIdeRuntimePairAdmission.Rejected(IndexSeedFailure.ValidationFailure)
        }
    }
}

sealed interface SupportedIdeRuntimePairAdmission {
    data class Admitted(val pair: SupportedIdeRuntimePair) : SupportedIdeRuntimePairAdmission
    data class Rejected(val failure: IndexSeedFailure) : SupportedIdeRuntimePairAdmission
}

/** Release-level cache selector that excludes valid caches produced by other Kast payloads. */
class SidecarCacheReleaseIdentity private constructor(
    private val supportedPair: SupportedIdeRuntimePair,
    private val kastPayloadDigest: String,
) {
    /** Retains only identities compatible with the exact current Kast release. */
    fun admits(identity: IdeRuntimeIdentity): Boolean =
        identity.supportedPair == supportedPair &&
            identity.kastPayloadDigest == kastPayloadDigest

    companion object {
        /**
         * Proof transition: `SupportedIdeRuntimePair + String ->
         * SidecarCacheReleaseIdentityAdmission`.
         *
         * The raw digest is accepted only at the release-manifest boundary. Once admitted, cache
         * observation and quarantine cannot accidentally select a valid cache from an older Kast
         * payload. Malformed release metadata remains closed as
         * [IndexSeedFailure.ValidationFailure].
         */
        fun admit(
            supportedPair: SupportedIdeRuntimePair,
            kastPayloadDigest: String,
        ): SidecarCacheReleaseIdentityAdmission = if (
            INDEX_SEED_DIGEST.matches(kastPayloadDigest)
        ) {
            SidecarCacheReleaseIdentityAdmission.Admitted(
                SidecarCacheReleaseIdentity(supportedPair, kastPayloadDigest),
            )
        } else {
            SidecarCacheReleaseIdentityAdmission.Rejected(IndexSeedFailure.ValidationFailure)
        }
    }
}

sealed interface SidecarCacheReleaseIdentityAdmission {
    data class Admitted(val identity: SidecarCacheReleaseIdentity) :
        SidecarCacheReleaseIdentityAdmission

    data class Rejected(val failure: IndexSeedFailure) : SidecarCacheReleaseIdentityAdmission
}

/** Untrusted identity fields observed from one installed IDEA home. */
data class IdeRuntimeIdentityCandidate(
    val ideaBuild: String,
    val kotlinPluginBuild: String,
    val jbrIdentity: String,
    val kastPayloadDigest: String,
)

/** Exact installed platform, JBR, Kotlin plugin, and private Kast payload identity. */
class IdeRuntimeIdentity private constructor(
    val supportedPair: SupportedIdeRuntimePair,
    val jbrIdentity: String,
    val kastPayloadDigest: String,
) {
    override fun equals(other: Any?): Boolean =
        other is IdeRuntimeIdentity &&
            supportedPair == other.supportedPair &&
            jbrIdentity == other.jbrIdentity &&
            kastPayloadDigest == other.kastPayloadDigest

    override fun hashCode(): Int =
        31 * (31 * supportedPair.hashCode() + jbrIdentity.hashCode()) +
            kastPayloadDigest.hashCode()

    internal fun identityMaterial(): String = listOf(
        supportedPair.ideaBuild,
        supportedPair.kotlinPluginBuild,
        jbrIdentity,
        kastPayloadDigest,
    ).joinToString("\n")

    companion object {
        /**
         * Proof transition: `SupportedIdeRuntimePair + IdeRuntimeIdentityCandidate ->
         * IdeRuntimeIdentityAdmission`.
         *
         * Establishes exact release compatibility plus bounded JBR and SHA-256 private-payload
         * identities. [IndexSeedFailure.Incompatibility] retains an unsupported pair;
         * malformed remaining fields fail closed as [IndexSeedFailure.ValidationFailure]. Raw
         * identity fields may leave only at installation discovery and process launch boundaries.
         */
        fun admit(
            supported: SupportedIdeRuntimePair,
            candidate: IdeRuntimeIdentityCandidate,
        ): IdeRuntimeIdentityAdmission {
            if (
                candidate.ideaBuild != supported.ideaBuild ||
                candidate.kotlinPluginBuild != supported.kotlinPluginBuild
            ) {
                return IdeRuntimeIdentityAdmission.Rejected(
                    IndexSeedFailure.Incompatibility(
                        supported,
                        candidate.ideaBuild,
                        candidate.kotlinPluginBuild,
                    ),
                )
            }
            if (
                !INDEX_SEED_COMPATIBILITY_TOKEN.matches(candidate.jbrIdentity) ||
                !INDEX_SEED_DIGEST.matches(candidate.kastPayloadDigest)
            ) {
                return IdeRuntimeIdentityAdmission.Rejected(IndexSeedFailure.ValidationFailure)
            }
            return IdeRuntimeIdentityAdmission.Admitted(
                IdeRuntimeIdentity(
                    supported,
                    candidate.jbrIdentity,
                    candidate.kastPayloadDigest,
                ),
            )
        }
    }
}

sealed interface IdeRuntimeIdentityAdmission {
    data class Admitted(val identity: IdeRuntimeIdentity) : IdeRuntimeIdentityAdmission
    data class Rejected(val failure: IndexSeedFailure) : IdeRuntimeIdentityAdmission
}

/** Stable key for one canonical project and one exact sidecar runtime identity. */
class KastCacheIdentity private constructor(
    val canonicalProjectRoot: Path,
    val runtimeIdentity: IdeRuntimeIdentity,
    val key: String,
) {
    override fun equals(other: Any?): Boolean =
        other is KastCacheIdentity &&
            canonicalProjectRoot == other.canonicalProjectRoot &&
            runtimeIdentity == other.runtimeIdentity && key == other.key

    override fun hashCode(): Int =
        31 * (31 * canonicalProjectRoot.hashCode() + runtimeIdentity.hashCode()) + key.hashCode()

    companion object {
        /**
         * Proof transition: `Path + IdeRuntimeIdentity -> KastCacheIdentityDerivation`.
         *
         * Establishes an existing physical canonical project root and deterministic SHA-256 key
         * over that root plus every runtime identity field. Invalid roots remain closed
         * [IndexSeedFailure.ValidationFailure]. The path and key may leave only at the private
         * cache-directory boundary.
         */
        fun derive(
            projectRoot: Path,
            runtimeIdentity: IdeRuntimeIdentity,
        ): KastCacheIdentityDerivation {
            val canonical = canonicalDirectory(projectRoot)
                ?: return KastCacheIdentityDerivation.Rejected(
                    IndexSeedFailure.ValidationFailure,
                )
            val material = "${canonical}\n${runtimeIdentity.identityMaterial()}"
            val key = sha256(material.toByteArray(StandardCharsets.UTF_8))
            return KastCacheIdentityDerivation.Derived(
                KastCacheIdentity(canonical, runtimeIdentity, key),
            )
        }
    }
}

sealed interface KastCacheIdentityDerivation {
    data class Derived(val identity: KastCacheIdentity) : KastCacheIdentityDerivation
    data class Rejected(val failure: IndexSeedFailure) : KastCacheIdentityDerivation
}

/** Content identity for exactly the allowlisted files copied by one seed attempt. */
class IndexContentManifest private constructor(
    val entries: Map<String, String>,
) {
    override fun equals(other: Any?): Boolean =
        other is IndexContentManifest && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    companion object {
        /**
         * Proof transition: `Map<String, String> -> IndexContentManifestAdmission`.
         *
         * Establishes a non-empty, sorted set of safe relative paths and canonical SHA-256 file
         * identities. Unsafe, escaping, or malformed entries remain closed validation failure.
         */
        fun from(entries: Map<String, String>): IndexContentManifestAdmission {
            if (entries.isEmpty()) {
                return IndexContentManifestAdmission.Rejected(
                    IndexSeedFailure.ValidationFailure,
                )
            }
            val admitted = sortedMapOf<String, String>()
            entries.forEach { (entry, digest) ->
                val normalized = entry.removeSuffix("/")
                if (
                    normalized.isBlank() || !INDEX_SEED_ENTRY.matches(entry) ||
                    entry.startsWith('/') ||
                    normalized.split('/').any { it.isBlank() || it == "." || it == ".." } ||
                    !INDEX_SEED_DIGEST.matches(digest)
                ) {
                    return IndexContentManifestAdmission.Rejected(
                        IndexSeedFailure.ValidationFailure,
                    )
                }
                admitted[entry] = digest
            }
            return IndexContentManifestAdmission.Admitted(
                IndexContentManifest(admitted.toMap()),
            )
        }
    }
}

sealed interface IndexContentManifestAdmission {
    data class Admitted(val manifest: IndexContentManifest) : IndexContentManifestAdmission
    data class Rejected(val failure: IndexSeedFailure) : IndexContentManifestAdmission
}

enum class SourceIdeProcessState { STOPPED, RUNNING, UNKNOWN }
enum class SourceIdeLockState { UNLOCKED, LOCKED, UNKNOWN }

/** Source system directory carrying proof that IDEA is stopped and its lock is absent. */
class QuiescentIdeSystem private constructor(
    val sourceSystem: Path,
    val runtimeIdentity: IdeRuntimeIdentity,
    val contentManifest: IndexContentManifest,
) {
    companion object {
        /**
         * Proof transition: `Path + runtime + process + lock + manifest ->
         * QuiescentIdeSystemAdmission`.
         *
         * Establishes one physical source directory with both stopped-process and unlocked-system
         * proof. Running, locked, and unknown observations all fail closed as
         * [IndexSeedFailure.RunningSourceIde].
         */
        fun admit(
            sourceSystem: Path,
            runtimeIdentity: IdeRuntimeIdentity,
            processState: SourceIdeProcessState,
            lockState: SourceIdeLockState,
            contentManifest: IndexContentManifest,
        ): QuiescentIdeSystemAdmission {
            if (
                processState != SourceIdeProcessState.STOPPED ||
                lockState != SourceIdeLockState.UNLOCKED
            ) {
                return QuiescentIdeSystemAdmission.Rejected(
                    IndexSeedFailure.RunningSourceIde,
                )
            }
            val canonical = canonicalDirectory(sourceSystem)
                ?: return QuiescentIdeSystemAdmission.Rejected(
                    IndexSeedFailure.ValidationFailure,
                )
            return QuiescentIdeSystemAdmission.Admitted(
                QuiescentIdeSystem(canonical, runtimeIdentity, contentManifest),
            )
        }
    }
}

sealed interface QuiescentIdeSystemAdmission {
    data class Admitted(val system: QuiescentIdeSystem) : QuiescentIdeSystemAdmission
    data class Rejected(val failure: IndexSeedFailure) : QuiescentIdeSystemAdmission
}

enum class IndexSeedConsent { GRANTED, ABSENT }
enum class IndexSeedConsentRequest { PREGRANTED, INTERACTIVE }
enum class IndexSeedFilesystem { APFS, UNSUPPORTED }

/** Version-specific copy categories disclosed before a seed starts. */
enum class IndexSeedCategory {
    GLOBAL_VFS,
    GLOBAL_INDEXES,
    PROJECT_MODEL,
    CLASSPATH_METADATA,
}

/** Non-negative measured size of the exact allowlisted source entries. */
@JvmInline
value class IndexSeedEstimatedBytes private constructor(val value: Long) {
    companion object {
        internal fun from(value: Long): IndexSeedEstimatedBytes? =
            value.takeIf { it >= 0L }?.let(::IndexSeedEstimatedBytes)
    }
}

/** Fixed-category disclosure issued before an interactive copy is authorized. */
class IndexSeedDisclosure private constructor(
    val categories: Set<IndexSeedCategory>,
    val estimatedBytes: IndexSeedEstimatedBytes,
) {
    companion object {
        internal fun fixed(estimatedBytes: IndexSeedEstimatedBytes): IndexSeedDisclosure =
            IndexSeedDisclosure(IndexSeedCategory.entries.toSet(), estimatedBytes)
    }
}

fun interface IndexSeedConsentProvider {
    fun request(disclosure: IndexSeedDisclosure): IndexSeedConsent
}

data object RejectingIndexSeedConsentProvider : IndexSeedConsentProvider {
    override fun request(disclosure: IndexSeedDisclosure): IndexSeedConsent =
        IndexSeedConsent.ABSENT
}

/** Fully admitted authority to clone one quiescent source into one private cache. */
class IndexSeedPlan private constructor(
    val cacheIdentity: KastCacheIdentity,
    val source: QuiescentIdeSystem,
    val categories: Set<IndexSeedCategory>,
) {
    companion object {
        /**
         * Proof transition: `cache + quiescent source + consent + filesystem -> IndexSeedPlanning`.
         *
         * Establishes exact runtime compatibility, explicit global-index copy consent, APFS clone
         * capability, and the fixed version-specific category set before any copy effect occurs.
         */
        fun create(
            cacheIdentity: KastCacheIdentity,
            source: QuiescentIdeSystem,
            consent: IndexSeedConsent,
            filesystem: IndexSeedFilesystem,
        ): IndexSeedPlanning {
            if (consent != IndexSeedConsent.GRANTED) {
                return IndexSeedPlanning.Rejected(IndexSeedFailure.ConsentAbsent)
            }
            if (filesystem != IndexSeedFilesystem.APFS) {
                return IndexSeedPlanning.Rejected(IndexSeedFailure.UnsupportedFilesystem)
            }
            if (source.runtimeIdentity != cacheIdentity.runtimeIdentity) {
                val expected = cacheIdentity.runtimeIdentity.supportedPair
                val observed = source.runtimeIdentity.supportedPair
                return IndexSeedPlanning.Rejected(
                    IndexSeedFailure.Incompatibility(
                        expected,
                        observed.ideaBuild,
                        observed.kotlinPluginBuild,
                    ),
                )
            }
            return IndexSeedPlanning.Planned(
                IndexSeedPlan(
                    cacheIdentity,
                    source,
                    IndexSeedCategory.entries.toSet(),
                ),
            )
        }
    }
}

sealed interface IndexSeedPlanning {
    data class Planned(val plan: IndexSeedPlan) : IndexSeedPlanning
    data class Rejected(val failure: IndexSeedFailure) : IndexSeedPlanning
}

/** Receipt published only after source stability and cloned-content equality are proven. */
class IndexSeedReceipt private constructor(
    val cacheIdentity: KastCacheIdentity,
    val runtimeIdentity: IdeRuntimeIdentity,
    val sourceSystem: Path,
    val categories: Set<IndexSeedCategory>,
    val contentManifest: IndexContentManifest,
) {
    companion object {
        /**
         * Proof transition: `plan + post-copy source manifest + clone manifest ->
         * IndexSeedCompletion`.
         *
         * Establishes that the source did not mutate and the unpublished clone exactly matches the
         * planned allowlisted content. Source drift and clone mismatch remain distinct closed
         * failures; only the completed variant may cross the atomic-publication boundary.
         */
        fun complete(
            plan: IndexSeedPlan,
            sourceAfterCopy: IndexContentManifest,
            clonedContent: IndexContentManifest,
        ): IndexSeedCompletion {
            if (sourceAfterCopy != plan.source.contentManifest) {
                return IndexSeedCompletion.Rejected(IndexSeedFailure.SourceMutation)
            }
            if (clonedContent != plan.source.contentManifest) {
                return IndexSeedCompletion.Rejected(IndexSeedFailure.ValidationFailure)
            }
            return IndexSeedCompletion.Completed(
                IndexSeedReceipt(
                    plan.cacheIdentity,
                    plan.cacheIdentity.runtimeIdentity,
                    plan.source.sourceSystem,
                    plan.categories,
                    clonedContent,
                ),
            )
        }
    }
}

sealed interface IndexSeedCompletion {
    data class Completed(val receipt: IndexSeedReceipt) : IndexSeedCompletion
    data class Rejected(val failure: IndexSeedFailure) : IndexSeedCompletion
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

private fun sha256(bytes: ByteArray): String = "sha256:" + HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(bytes),
)
