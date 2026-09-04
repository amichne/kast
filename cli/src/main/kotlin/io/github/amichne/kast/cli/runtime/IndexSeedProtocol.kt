package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentIdentity
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

private val INDEX_SEED_COMPATIBILITY_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")
private val INDEX_SEED_DIGEST = Regex("sha256:[0-9a-f]{64}")
private val INDEX_SEED_GRADLE_DISTRIBUTION = Regex("[0-9]+(?:\\.[0-9]+)+(?:[-+][A-Za-z0-9._-]+)?")
private const val MAX_INDEX_SEED_ENTRY_CHARACTERS = 4096
internal const val INDEX_SEED_RECEIPT_FORMAT = "kast.index-seed.receipt.v2"
private val IDEA_RUNTIME_BUILD = Regex("([0-9]{3})\\.[0-9]+(?:\\.[0-9]+)*")
private val KOTLIN_RUNTIME_BUILD = Regex("([0-9]{3})\\.[0-9]+(?:\\.[0-9]+)*-IJ")

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

@JvmInline
private value class JetBrainsPlatformReleaseLine(val value: String)

private data class JetBrainsRuntimeBuild(
    val value: String,
    val releaseLine: JetBrainsPlatformReleaseLine,
)

internal sealed interface IdeRuntimePairCompatibility {
    data class Compatible(val observed: SupportedIdeRuntimePair) : IdeRuntimePairCompatibility
    data object Incompatible : IdeRuntimePairCompatibility
}

/** An exact IDEA and Kotlin build pair with independently refined platform release lines. */
class SupportedIdeRuntimePair private constructor(
    private val idea: JetBrainsRuntimeBuild,
    private val kotlinPlugin: JetBrainsRuntimeBuild,
) {
    val ideaBuild: String get() = idea.value
    val kotlinPluginBuild: String get() = kotlinPlugin.value

    override fun equals(other: Any?): Boolean =
        other is SupportedIdeRuntimePair &&
            ideaBuild == other.ideaBuild && kotlinPluginBuild == other.kotlinPluginBuild

    override fun hashCode(): Int = 31 * ideaBuild.hashCode() + kotlinPluginBuild.hashCode()

    /** Preserves the observed exact pair only when both release lines remain supported. */
    internal fun compatibilityWith(
        observed: SupportedIdeRuntimePair,
    ): IdeRuntimePairCompatibility = if (
        idea.releaseLine == observed.idea.releaseLine &&
        kotlinPlugin.releaseLine == observed.kotlinPlugin.releaseLine
    ) {
        IdeRuntimePairCompatibility.Compatible(observed)
    } else {
        IdeRuntimePairCompatibility.Incompatible
    }

    companion object {
        /**
         * Proof transition: `String + String -> SupportedIdeRuntimePairAdmission`.
         *
         * Establishes two exact, bounded build identities with explicit JetBrains platform
         * release lines. Malformed metadata is closed [IndexSeedFailure.ValidationFailure]. Raw
         * text may leave only at installed-runtime, cache-receipt, and product-report boundaries.
         */
        fun admit(
            ideaBuild: String,
            kotlinPluginBuild: String,
        ): SupportedIdeRuntimePairAdmission {
            val idea = IDEA_RUNTIME_BUILD.matchEntire(ideaBuild)?.runtimeBuild(ideaBuild)
                ?: return SupportedIdeRuntimePairAdmission.Rejected(
                    IndexSeedFailure.ValidationFailure,
                )
            val kotlin = KOTLIN_RUNTIME_BUILD.matchEntire(kotlinPluginBuild)
                ?.runtimeBuild(kotlinPluginBuild)
                ?: return SupportedIdeRuntimePairAdmission.Rejected(
                    IndexSeedFailure.ValidationFailure,
                )
            return SupportedIdeRuntimePairAdmission.Admitted(
                SupportedIdeRuntimePair(idea, kotlin),
            )
        }

        private fun MatchResult.runtimeBuild(raw: String): JetBrainsRuntimeBuild =
            JetBrainsRuntimeBuild(raw, JetBrainsPlatformReleaseLine(groupValues[1]))
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
    private val semanticRuntimeId: SemanticRuntimeId,
) {
    /** Retains only identities compatible with the exact current Kast release. */
    fun admits(identity: KastCacheIdentity): Boolean =
        supportedPair.compatibilityWith(identity.runtimeIdentity.supportedPair) is
            IdeRuntimePairCompatibility.Compatible &&
            identity.runtimeIdentity.kastPayloadDigest == kastPayloadDigest &&
            identity.semanticRuntimeId == semanticRuntimeId

    /** Re-observes one receipt's IDEA home under this release's compatibility authority. */
    internal fun discoverCurrentRuntime(
        ideaHome: Path,
        resolver: SidecarIdeRuntimeResolver,
    ): InstalledIdeRuntimeDiscoveryResult = resolver.resolve(
        supportedPair,
        kastPayloadDigest,
        IdeHomeSelection.Explicit(ideaHome),
    )

    companion object {
        /**
         * Proof transition: `SupportedIdeRuntimePair + String + SemanticRuntimeId ->
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
            semanticRuntimeId: SemanticRuntimeId,
        ): SidecarCacheReleaseIdentityAdmission = if (
            INDEX_SEED_DIGEST.matches(kastPayloadDigest)
        ) {
            SidecarCacheReleaseIdentityAdmission.Admitted(
                SidecarCacheReleaseIdentity(
                    supportedPair,
                    kastPayloadDigest,
                    semanticRuntimeId,
                ),
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
    /** The exact observed pair after release-line compatibility was proven. */
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
         * Establishes release-line compatibility while preserving the observed exact build pair,
         * plus bounded JBR and SHA-256 private-payload identities.
         * [IndexSeedFailure.Incompatibility] retains an unsupported pair;
         * malformed remaining fields fail closed as [IndexSeedFailure.ValidationFailure]. Raw
         * identity fields may leave only at installation discovery and process launch boundaries.
         */
        fun admit(
            supported: SupportedIdeRuntimePair,
            candidate: IdeRuntimeIdentityCandidate,
        ): IdeRuntimeIdentityAdmission {
            val observed = when (
                val admission = SupportedIdeRuntimePair.admit(
                    candidate.ideaBuild,
                    candidate.kotlinPluginBuild,
                )
            ) {
                is SupportedIdeRuntimePairAdmission.Admitted -> admission.pair
                is SupportedIdeRuntimePairAdmission.Rejected ->
                    return IdeRuntimeIdentityAdmission.Rejected(admission.failure)
            }
            val compatible = when (
                val compatibility = supported.compatibilityWith(observed)
            ) {
                is IdeRuntimePairCompatibility.Compatible -> compatibility.observed
                IdeRuntimePairCompatibility.Incompatible ->
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
                    compatible,
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
    val ideaHome: Path,
    val javaExecutable: Path,
    val semanticRuntimeId: SemanticRuntimeId,
    val runtimeIdentity: IdeRuntimeIdentity,
    val importEnvironmentIdentity: GradleImportEnvironmentIdentity,
    val key: String,
) {
    override fun equals(other: Any?): Boolean =
        other is KastCacheIdentity &&
            canonicalProjectRoot == other.canonicalProjectRoot &&
            ideaHome == other.ideaHome && javaExecutable == other.javaExecutable &&
            semanticRuntimeId == other.semanticRuntimeId &&
            runtimeIdentity == other.runtimeIdentity && key == other.key

    override fun hashCode(): Int = listOf(
        canonicalProjectRoot,
        ideaHome,
        javaExecutable,
        semanticRuntimeId,
        runtimeIdentity,
        key,
    ).hashCode()

    companion object {
        /**
         * Proof transition: `Path + InstalledIdeRuntime + SemanticRuntimeId ->
         * KastCacheIdentityDerivation`.
         *
         * Establishes an existing physical canonical project root and deterministic SHA-256 key
         * over that root, physical IDEA/JBR launch authority, and every runtime identity field.
         * Invalid paths remain closed [IndexSeedFailure.ValidationFailure]. The paths and key may
         * leave only at private cache and process boundaries.
         */
        fun derive(
            projectRoot: Path,
            runtime: InstalledIdeRuntime,
            semanticRuntimeId: SemanticRuntimeId,
            importEnvironmentIdentity: GradleImportEnvironmentIdentity = GradleImportEnvironment.Empty.identity,
        ): KastCacheIdentityDerivation {
            val canonicalProject = canonicalDirectory(projectRoot)
                ?: return KastCacheIdentityDerivation.Rejected(
                    IndexSeedFailure.ValidationFailure,
                )
            val canonicalIdeaHome = canonicalDirectory(runtime.home)
                ?: return KastCacheIdentityDerivation.Rejected(
                    IndexSeedFailure.ValidationFailure,
                )
            val canonicalJava = canonicalRegularFile(runtime.javaExecutable)
                ?: return KastCacheIdentityDerivation.Rejected(
                    IndexSeedFailure.ValidationFailure,
                )
            val material = listOf(
                canonicalProject.toString(),
                canonicalIdeaHome.toString(),
                canonicalJava.toString(),
                semanticRuntimeId.value,
                runtime.identity.identityMaterial(),
                importEnvironmentIdentity.value,
            ).joinToString("\n")
            val key = sha256(material.toByteArray(StandardCharsets.UTF_8))
            return KastCacheIdentityDerivation.Derived(
                KastCacheIdentity(
                    canonicalProject,
                    canonicalIdeaHome,
                    canonicalJava,
                    semanticRuntimeId,
                    runtime.identity,
                    importEnvironmentIdentity,
                    key,
                ),
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
                if (
                    !entry.isCanonicalIndexSeedManifestPath() ||
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

private fun String.isCanonicalIndexSeedManifestPath(): Boolean {
    if (
        isEmpty() || length > MAX_INDEX_SEED_ENTRY_CHARACTERS ||
        any(Char::isISOControl)
    ) {
        return false
    }
    val directory = endsWith('/')
    val relative = if (directory) dropLast(1) else this
    if (relative.isBlank()) return false
    val path = try {
        Path.of(relative)
    } catch (_: InvalidPathException) {
        return false
    }
    if (path.isAbsolute || path.normalize() != path) return false
    if (
        path.any { segment ->
            val value = segment.toString()
            value.isBlank() || value == "." || value == ".."
        }
    ) {
        return false
    }
    val canonical = path.joinToString("/") { segment -> segment.toString() }
    return relative == canonical
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

internal val GLOBAL_INDEX_SEED_CATEGORIES = setOf(
    IndexSeedCategory.GLOBAL_VFS,
    IndexSeedCategory.GLOBAL_INDEXES,
)

/** Raw project-specific compatibility evidence from a seed receipt or current import. */
data class SeedProjectIdentityCandidate(
    val projectRoot: Path,
    val gradleDistribution: String,
    val selectedGradleJvmFingerprint: String,
    val repositoryGradleInputsFingerprint: String,
    val importedProjectJvmModelFingerprint: String,
    val classpathFingerprint: String,
    val sourceGeneration: String,
)

/** Exact identity required before project-model or classpath seed state can be copied. */
class SeedProjectIdentity private constructor(
    val canonicalProjectRoot: Path,
    val gradleDistribution: String,
    val selectedGradleJvmFingerprint: String,
    val repositoryGradleInputsFingerprint: String,
    val importedProjectJvmModelFingerprint: String,
    val classpathFingerprint: String,
    val sourceGeneration: String,
) {
    override fun equals(other: Any?): Boolean =
        other is SeedProjectIdentity &&
            canonicalProjectRoot == other.canonicalProjectRoot &&
            gradleDistribution == other.gradleDistribution &&
            selectedGradleJvmFingerprint == other.selectedGradleJvmFingerprint &&
            repositoryGradleInputsFingerprint == other.repositoryGradleInputsFingerprint &&
            importedProjectJvmModelFingerprint == other.importedProjectJvmModelFingerprint &&
            classpathFingerprint == other.classpathFingerprint &&
            sourceGeneration == other.sourceGeneration

    override fun hashCode(): Int = listOf(
        canonicalProjectRoot,
        gradleDistribution,
        selectedGradleJvmFingerprint,
        repositoryGradleInputsFingerprint,
        importedProjectJvmModelFingerprint,
        classpathFingerprint,
        sourceGeneration,
    ).hashCode()

    internal fun fingerprint(): String = sha256(
        listOf(
            canonicalProjectRoot.toString(),
            gradleDistribution,
            selectedGradleJvmFingerprint,
            repositoryGradleInputsFingerprint,
            importedProjectJvmModelFingerprint,
            classpathFingerprint,
            sourceGeneration,
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8),
    )

    companion object {
        /** Refines every project-specific seed compatibility input as one inseparable identity. */
        fun admit(candidate: SeedProjectIdentityCandidate): SeedProjectIdentityAdmission {
            val projectRoot = canonicalDirectory(candidate.projectRoot)
                ?: return SeedProjectIdentityAdmission.Rejected(
                    IndexSeedFailure.ValidationFailure,
                )
            if (!INDEX_SEED_GRADLE_DISTRIBUTION.matches(candidate.gradleDistribution)) {
                return SeedProjectIdentityAdmission.Rejected(IndexSeedFailure.ValidationFailure)
            }
            val fingerprints = listOf(
                candidate.selectedGradleJvmFingerprint,
                candidate.repositoryGradleInputsFingerprint,
                candidate.importedProjectJvmModelFingerprint,
                candidate.classpathFingerprint,
                candidate.sourceGeneration,
            )
            if (fingerprints.any { fingerprint -> !INDEX_SEED_DIGEST.matches(fingerprint) }) {
                return SeedProjectIdentityAdmission.Rejected(IndexSeedFailure.ValidationFailure)
            }
            return SeedProjectIdentityAdmission.Admitted(
                SeedProjectIdentity(
                    projectRoot,
                    candidate.gradleDistribution,
                    candidate.selectedGradleJvmFingerprint,
                    candidate.repositoryGradleInputsFingerprint,
                    candidate.importedProjectJvmModelFingerprint,
                    candidate.classpathFingerprint,
                    candidate.sourceGeneration,
                ),
            )
        }
    }
}

sealed interface SeedProjectIdentityAdmission {
    data class Admitted(val identity: SeedProjectIdentity) : SeedProjectIdentityAdmission
    data class Rejected(val failure: IndexSeedFailure) : SeedProjectIdentityAdmission
}

/** Optional source-versus-current identity evidence supplied before the seed copy begins. */
sealed interface SeedProjectEvidence {
    data object Absent : SeedProjectEvidence

    data class Comparison(
        val expected: SeedProjectIdentity,
        val observed: SeedProjectIdentity,
    ) : SeedProjectEvidence
}

/** Closed disposition of project-specific seed state; global categories remain independently safe. */
sealed interface SeedProjectProofState {
    val categories: Set<IndexSeedCategory>
    val wireName: String

    data object GlobalOnly : SeedProjectProofState {
        override val categories: Set<IndexSeedCategory> = GLOBAL_INDEX_SEED_CATEGORIES
        override val wireName: String = "global-only"
    }

    data class Verified(val identity: SeedProjectIdentity) : SeedProjectProofState {
        override val categories: Set<IndexSeedCategory> = IndexSeedCategory.entries.toSet()
        override val wireName: String = "verified"
    }

    data class Retired(
        val expected: SeedProjectIdentity,
        val observed: SeedProjectIdentity,
    ) : SeedProjectProofState {
        override val categories: Set<IndexSeedCategory> = GLOBAL_INDEX_SEED_CATEGORIES
        override val wireName: String = "retired"
    }

    companion object {
        fun classify(
            evidence: SeedProjectEvidence,
            requiredProjectRoot: Path,
        ): SeedProjectProofState = when (evidence) {
            SeedProjectEvidence.Absent -> GlobalOnly
            is SeedProjectEvidence.Comparison -> if (
                evidence.expected == evidence.observed &&
                evidence.expected.canonicalProjectRoot == requiredProjectRoot
            ) {
                Verified(evidence.expected)
            } else {
                Retired(evidence.expected, evidence.observed)
            }
        }
    }
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
        internal fun fixed(
            categories: Set<IndexSeedCategory>,
            estimatedBytes: IndexSeedEstimatedBytes,
        ): IndexSeedDisclosure = IndexSeedDisclosure(categories, estimatedBytes)
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
    val projectProofState: SeedProjectProofState,
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
            projectProofState: SeedProjectProofState = SeedProjectProofState.GlobalOnly,
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
                    projectProofState.categories,
                    projectProofState,
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
    val projectProofState: SeedProjectProofState,
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
                    plan.projectProofState,
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

private fun canonicalRegularFile(path: Path): Path? {
    if (!path.isAbsolute || path.normalize() != path) return null
    return try {
        path.toRealPath().takeIf { canonical ->
            canonical == path && Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)
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
