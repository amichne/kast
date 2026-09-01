package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.CliLocalMetadataFailure
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFailure
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandSurface
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.distribution.contract.SemanticRuntimeFailure
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifestAdmission
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSource
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSourceSelection
import io.github.amichne.kast.distribution.managed.ManagedSemanticRuntimeProvider
import io.github.amichne.kast.distribution.managed.RuntimeStore
import io.github.amichne.kast.distribution.managed.RuntimeStoreAdmission
import io.github.amichne.kast.distribution.managed.RuntimeStoreFailure
import io.github.amichne.kast.distribution.managed.SemanticRuntimeResolution
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.KastPluginVersion
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val RUNTIME_ARCHIVE_ENVIRONMENT = "KAST_RUNTIME_ARCHIVE"
private const val RUNTIME_DIRECTORY_ENVIRONMENT = "KAST_RUNTIME_DIRECTORY"
private const val RUNTIME_STORE_ENVIRONMENT = "KAST_RUNTIME_STORE"
private const val SIDECAR_CACHE_ROOT_ENVIRONMENT = "KAST_CACHE_ROOT"

internal const val SUPPORTED_IDE_BUILD = "262.9437.185"
internal const val SUPPORTED_KOTLIN_PLUGIN_BUILD = "262.9437.185-IJ"

internal sealed interface InstalledCompositionFailure : KastCliCompositionFailure {
    data class ControlProductRejected(
        val failure: InstalledKastControlProductFailure,
    ) : InstalledCompositionFailure

    data class ResourceUnavailable(
        val resource: InstalledControlResource,
    ) : InstalledCompositionFailure

    data class ProductVersionRejected(
        val failure: IdeHostCompatibilityFailure,
    ) : InstalledCompositionFailure

    data class RuntimeManifestRejected(
        val failure: SemanticRuntimeFailure,
    ) : InstalledCompositionFailure

    data class SidecarMetadataRejected(
        val failure: IndexSeedFailure,
    ) : InstalledCompositionFailure

    data object SidecarPathsRejected : InstalledCompositionFailure

    data class SidecarCacheRootRejected(
        val failure: InstalledSidecarCacheRootFailure,
    ) : InstalledCompositionFailure

    data class RuntimeDirectoryRejected(
        val failure: InstalledRuntimeDirectoryFailure,
    ) : InstalledCompositionFailure

    data class RuntimeProcessModeRejected(
        val failure: RuntimeProcessModeFailure,
    ) : InstalledCompositionFailure {
        override val outputReason: String = "invalid-launchd-flag"
    }

    data class CommandGraphRejected(
        val failures: Set<CliCommandGraphFailure>,
    ) : InstalledCompositionFailure

    data class SchemaRejected(
        val failure: InstalledSchemaFailure,
    ) : InstalledCompositionFailure

    data class LocalMetadataRejected(
        val failure: CliLocalMetadataFailure,
    ) : InstalledCompositionFailure
}

/** The sole service-loaded composition for an installed Kotlin `kast` executable. */
internal class InstalledKastCliComposition : KastCliComposition {
    /**
     * Proof transition: `installed process environment -> KastCliCompositionConstruction`.
     *
     * Establishes one complete CLI graph with an admitted control product, product version,
     * protocol resources, exact installed-IDE support, private runtime directory, and metadata.
     * [InstalledCompositionFailure] is the closed expected failure. Filesystem and environment
     * extraction remain in this installed composition boundary.
     */
    override fun create(): KastCliCompositionConstruction {
        val processMode = when (
            val admission = RuntimeProcessModeEnvironment.admit(
                System.getenv(RUNTIME_PROCESS_MODE_ENVIRONMENT),
            )
        ) {
            is RuntimeProcessModeAdmission.Admitted -> admission.mode
            is RuntimeProcessModeAdmission.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.RuntimeProcessModeRejected(admission.failure),
                )
        }
        val processCapabilities = processMode.capabilities()
        val installation = when (val admission = InstalledKastControlProduct.discover()) {
            is InstalledKastControlProductAdmission.Admitted -> admission.product
            is InstalledKastControlProductAdmission.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.ControlProductRejected(admission.failure),
                )
        }
        val productVersion = when (val admission = installation.productVersion()) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return KastCliCompositionConstruction.Rejected(
                InstalledCompositionFailure.ProductVersionRejected(admission.failure),
            )
        }
        val protocol = when (val construction = installation.protocolResources()) {
            is InstalledProtocolResourcesConstruction.Constructed -> construction.resources
            is InstalledProtocolResourcesConstruction.Rejected ->
                return KastCliCompositionConstruction.Rejected(construction.failure)
        }
        val manifestResource = when (
            val resource = installation.readResource(InstalledControlResource.SEMANTIC_RUNTIME)
        ) {
            is InstalledControlResourceRead.Read -> resource.value
            is InstalledControlResourceRead.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.ResourceUnavailable(resource.resource),
                )
        }
        val manifest = when (val admission = SemanticRuntimeManifest.admit(manifestResource)) {
            is SemanticRuntimeManifestAdmission.Admitted -> admission.manifest
            is SemanticRuntimeManifestAdmission.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.RuntimeManifestRejected(admission.failure),
                )
        }
        if (manifest.productVersion.value != productVersion.value) {
            return KastCliCompositionConstruction.Rejected(
                InstalledCompositionFailure.RuntimeManifestRejected(
                    SemanticRuntimeFailure.MANIFEST_INVALID,
                ),
            )
        }
        val support = when (
            val admission = SupportedIdeRuntimePair.admit(
                SUPPORTED_IDE_BUILD,
                SUPPORTED_KOTLIN_PLUGIN_BUILD,
            )
        ) {
            is SupportedIdeRuntimePairAdmission.Admitted -> admission.pair
            is SupportedIdeRuntimePairAdmission.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.SidecarMetadataRejected(admission.failure),
                )
        }
        val commandGraphFactory = when (
            val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
        ) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.CommandGraphRejected(construction.failures),
                )
        }
        val runtimeDirectory = when (val admission = InstalledRuntimeDirectory.admit()) {
            is InstalledRuntimeDirectoryAdmission.Admitted -> admission.directory
            is InstalledRuntimeDirectoryAdmission.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.RuntimeDirectoryRejected(admission.failure),
                )
        }
        val localMetadata = when (
            val construction = installation.localMetadata(
                productVersion.value,
                protocol,
                commandGraphFactory.surface,
            )
        ) {
            is InstalledLocalMetadataConstruction.Constructed -> construction.metadata
            is InstalledLocalMetadataConstruction.Rejected ->
                return KastCliCompositionConstruction.Rejected(construction.failure)
        }
        val userHome = try {
            Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return KastCliCompositionConstruction.Rejected(
                InstalledCompositionFailure.SidecarPathsRejected,
            )
        }
        val cacheRoot = when (
            val admission = InstalledSidecarCacheRoot.admit(
                System.getenv(SIDECAR_CACHE_ROOT_ENVIRONMENT),
                userHome,
            )
        ) {
            is InstalledSidecarCacheRootAdmission.Admitted -> admission.root.path
            is InstalledSidecarCacheRootAdmission.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.SidecarCacheRootRejected(admission.failure),
                )
        }
        val endpointLocator = Sha256RuntimeEndpointLocator(
            RuntimeSocketDirectory.from(runtimeDirectory),
            manifest.runtimeId,
        )
        val defaultSourceSystem = userHome.resolve(
            "Library/Caches/JetBrains/IntelliJIdea2026.2",
        )
        val cachePreparer = FilesystemSidecarCachePreparer(
            cacheRoot,
            defaultSourceSystem,
            IndexSeedFilesystemService(
                FilesystemSourceIdeQuiescenceProbe,
                ApfsIndexSeedFilesystemProbe,
                ApfsCoWIndexSeedCloner,
                ConsoleIndexSeedConsentProvider,
            ),
        )
        val cacheReleaseIdentity = when (
            val admission = SidecarCacheReleaseIdentity.admit(
                support,
                manifest.kastPluginDigest.value,
                manifest.runtimeId,
            )
        ) {
            is SidecarCacheReleaseIdentityAdmission.Admitted -> admission.identity
            is SidecarCacheReleaseIdentityAdmission.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.SidecarMetadataRejected(admission.failure),
                )
        }
        val cacheLifecycle = FilesystemRootSidecarCacheLifecycle(
            cacheRoot,
            cacheReleaseIdentity,
            SidecarIdeRuntimeResolver { supported, digest, selection ->
                InstalledIdeRuntimeDiscovery.discover(supported, digest, selection)
            },
        )
        return KastCliCompositionConstruction.Created(
            KastCli(
                commandGraphFactory,
                FilesystemCanonicalRootDiscovery,
                endpointLocator,
                InstalledSidecarRootRuntimeDemander(
                    endpointLocator,
                    support,
                    userHome,
                    ManagedInstalledSidecarPayloadResolver(
                        manifest,
                        InstalledSemanticRuntimeResolver(::resolveInstalledRuntime),
                    ),
                    { supported, digest, selection ->
                        InstalledIdeRuntimeDiscovery.discover(supported, digest, selection)
                    },
                    cachePreparer,
                    ExactSidecarProcessDemander(
                        runtimeDemanderFactory = { executable, context ->
                            ExactRootProcessRuntimeDemander(
                                executable,
                                context,
                                processCapabilities.starter,
                            )
                        },
                    ),
                    legacyProcessAuthority = processCapabilities.authority,
                ),
                UnixDomainWireClient(),
                localMetadata,
                ExactRootRuntimeLifecycle(
                    JdkUnixDomainEndpointProbe,
                    processCapabilities.authority,
                ),
                SidecarProductInspector(
                    SidecarProductIdentity(
                        productVersion,
                        manifest.runtimeId,
                        support,
                        manifest.kastPluginDigest,
                    ),
                    FilesystemCanonicalRootDiscovery,
                    cacheLifecycle,
                    endpointLocator,
                ),
                cacheLifecycle,
            ),
        )
    }
}

internal enum class InstalledKastControlProductFailure {
    CODE_SOURCE_UNAVAILABLE,
    CODE_SOURCE_INVALID,
    LIBRARY_DIRECTORY_INVALID,
    PRODUCT_ROOT_UNAVAILABLE,
    RESOURCE_DIRECTORY_UNAVAILABLE,
}

private sealed interface InstalledKastControlProductAdmission {
    data class Admitted(
        val product: InstalledKastControlProduct,
    ) : InstalledKastControlProductAdmission

    data class Rejected(
        val failure: InstalledKastControlProductFailure,
    ) : InstalledKastControlProductAdmission
}

internal enum class InstalledControlResource(val fileName: String) {
    OPERATION_REGISTRY("operation-registry.json"),
    WIRE_SCHEMA("wire-schema.json"),
    SEMANTIC_RUNTIME("semantic-runtime.json"),
}

private sealed interface InstalledControlResourceRead {
    data class Read(val value: String) : InstalledControlResourceRead
    data class Rejected(val resource: InstalledControlResource) : InstalledControlResourceRead
}

private sealed interface InstalledLocalMetadataConstruction {
    data class Constructed(val metadata: CliLocalMetadata) : InstalledLocalMetadataConstruction
    data class Rejected(
        val failure: InstalledCompositionFailure,
    ) : InstalledLocalMetadataConstruction
}

/** One control installation proven by the CLI jar and exact `share/kast` resources. */
private class InstalledKastControlProduct private constructor(
    private val root: Path,
) {
    /**
     * Proof transition: `KastPluginVersion + InstalledProtocolResources + CliCommandSurface ->
     * InstalledLocalMetadataConstruction`.
     *
     * Establishes readable schema resources and admitted local metadata while preserving any
     * [InstalledCompositionFailure]. Raw installed resource text remains inside this adapter.
     */
    fun localMetadata(
        productVersion: String,
        protocol: InstalledProtocolResources,
        commandSurface: CliCommandSurface,
    ): InstalledLocalMetadataConstruction {
        val schema = when (val construction = installedSchema(
            protocol.operationRegistry,
            protocol.wireSchema,
            commandSurface,
        )) {
            is InstalledSchemaConstruction.Constructed -> construction.document
            is InstalledSchemaConstruction.Rejected ->
                return InstalledLocalMetadataConstruction.Rejected(
                    InstalledCompositionFailure.SchemaRejected(construction.failure),
                )
        }
        return when (
            val admission = CliLocalMetadata.admit(
                productVersion,
                schema,
            )
        ) {
            is CliLocalMetadataAdmission.Admitted ->
                InstalledLocalMetadataConstruction.Constructed(admission.metadata)
            is CliLocalMetadataAdmission.Rejected -> InstalledLocalMetadataConstruction.Rejected(
                InstalledCompositionFailure.LocalMetadataRejected(admission.failure),
            )
        }
    }

    /**
     * Proof transition: `installed metadata files -> InstalledProtocolResourcesConstruction`.
     *
     * Establishes one read of each exact protocol resource plus its SHA-256 identity. Missing
     * resources remain closed [InstalledCompositionFailure] data; raw text remains inside the
     * installed-control boundary.
     */
    fun protocolResources(): InstalledProtocolResourcesConstruction {
        val operationRegistry = when (
            val resource = readResource(InstalledControlResource.OPERATION_REGISTRY)
        ) {
            is InstalledControlResourceRead.Read -> resource.value
            is InstalledControlResourceRead.Rejected ->
                return InstalledProtocolResourcesConstruction.Rejected(
                    InstalledCompositionFailure.ResourceUnavailable(resource.resource),
                )
        }
        val wireSchema = when (val resource = readResource(InstalledControlResource.WIRE_SCHEMA)) {
            is InstalledControlResourceRead.Read -> resource.value
            is InstalledControlResourceRead.Rejected ->
                return InstalledProtocolResourcesConstruction.Rejected(
                    InstalledCompositionFailure.ResourceUnavailable(resource.resource),
                )
        }
        return InstalledProtocolResourcesConstruction.Constructed(
            InstalledProtocolResources(
                operationRegistry,
                wireSchema,
            ),
        )
    }

    /**
     * Proof transition: `installed CLI package metadata -> Refinement<KastPluginVersion, ...>`.
     *
     * Establishes the exact release version embedded in the installed CLI jar. Malformed or
     * absent metadata remains a closed compatibility failure; raw package metadata is extracted
     * only here.
     */
    fun productVersion(): Refinement<KastPluginVersion, IdeHostCompatibilityFailure> =
        KastPluginVersion.parse(
            InstalledKastCliComposition::class.java.`package`.implementationVersion.orEmpty(),
        )

    /**
     * Proof transition: `InstalledControlResource -> InstalledControlResourceRead`.
     *
     * Establishes readable installed resource text. The finite rejected variant retains the exact
     * resource identity. Filesystem reads remain inside this installed-control boundary.
     */
    fun readResource(resource: InstalledControlResource): InstalledControlResourceRead = try {
        InstalledControlResourceRead.Read(
            Files.readString(root.resolve("share/kast/${resource.fileName}")),
        )
    } catch (_: IOException) {
        InstalledControlResourceRead.Rejected(resource)
    }

    companion object {
        /**
         * Proof transition: `InstalledKastCliComposition code source ->
         * InstalledKastControlProductAdmission`.
         *
         * Establishes that the provider was loaded from the installation's `lib` directory and
         * owns one sibling `share/kast` resource directory. [InstalledKastControlProductFailure]
         * is the closed expected failure. Raw URI and paths remain inside this adapter.
         */
        fun discover(): InstalledKastControlProductAdmission {
            val codeSource = try {
                Path.of(
                    InstalledKastCliComposition::class.java.protectionDomain.codeSource.location
                        .toURI(),
                ).toRealPath()
            } catch (_: IOException) {
                return InstalledKastControlProductAdmission.Rejected(
                    InstalledKastControlProductFailure.CODE_SOURCE_UNAVAILABLE,
                )
            } catch (_: URISyntaxException) {
                return InstalledKastControlProductAdmission.Rejected(
                    InstalledKastControlProductFailure.CODE_SOURCE_INVALID,
                )
            }
            val libraryDirectory = codeSource.parent?.takeIf { it.fileName.toString() == "lib" }
                ?: return InstalledKastControlProductAdmission.Rejected(
                    InstalledKastControlProductFailure.LIBRARY_DIRECTORY_INVALID,
                )
            val root = libraryDirectory.parent
                ?: return InstalledKastControlProductAdmission.Rejected(
                    InstalledKastControlProductFailure.PRODUCT_ROOT_UNAVAILABLE,
                )
            if (!Files.isDirectory(root.resolve("share/kast"))) {
                return InstalledKastControlProductAdmission.Rejected(
                    InstalledKastControlProductFailure.RESOURCE_DIRECTORY_UNAVAILABLE,
                )
            }
            return InstalledKastControlProductAdmission.Admitted(InstalledKastControlProduct(root))
        }
    }
}

/** Adapts the digest-verified small runtime archive into launch/private-plugin authority. */
private class ManagedInstalledSidecarPayloadResolver(
    private val manifest: SemanticRuntimeManifest,
    private val resolver: InstalledSemanticRuntimeResolver,
) : SidecarPayloadResolver {
    override fun resolve(): SidecarPayloadResolution {
        val installed = when (val resolution = resolver.resolve(manifest)) {
            is SemanticRuntimeResolution.Installed -> resolution.runtime
            is SemanticRuntimeResolution.Rejected -> return SidecarPayloadResolution.Rejected(
                resolution.failure.sidecarAdmissionFailure(),
            )
        }
        if (installed.runtimeId != manifest.runtimeId) {
            return SidecarPayloadResolution.Rejected(
                RuntimeAdmissionFailure.RuntimeIdentityMismatch,
            )
        }
        return when (
            val admission = SidecarPayload.admit(
                installed.runtimeId,
                installed.executable,
                installed.directory.resolve("private-plugins"),
                manifest.kastPluginDigest.value,
            )
        ) {
            is SidecarPayloadAdmission.Admitted -> SidecarPayloadResolution.Resolved(
                admission.payload,
            )
            is SidecarPayloadAdmission.Rejected -> SidecarPayloadResolution.Rejected(
                RuntimeAdmissionFailure.LayoutInvalid,
            )
        }
    }
}

/** Performs small sidecar payload source selection and store admission only after demand. */
private fun resolveInstalledRuntime(
    manifest: SemanticRuntimeManifest,
): SemanticRuntimeResolution {
    val source = when (
        val selected = SemanticRuntimeSource.select(System.getenv(RUNTIME_ARCHIVE_ENVIRONMENT))
    ) {
        is SemanticRuntimeSourceSelection.Managed -> selected.source
        is SemanticRuntimeSourceSelection.Preseeded -> selected.source
        is SemanticRuntimeSourceSelection.Rejected -> return SemanticRuntimeResolution.Rejected(
            RuntimeStoreFailure.STORE_INVALID,
        )
    }
    val rawStore = System.getenv(RUNTIME_STORE_ENVIRONMENT)
    val storePath = try {
        when {
            rawStore == null -> Path.of(System.getProperty("user.home"))
                .resolve(".cache/kast/semantic-runtimes")
            rawStore.isBlank() -> return SemanticRuntimeResolution.Rejected(
                RuntimeStoreFailure.STORE_INVALID,
            )
            else -> Path.of(rawStore)
        }
    } catch (_: InvalidPathException) {
        return SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.STORE_INVALID)
    }
    val store = when (val admission = RuntimeStore.admit(storePath.toAbsolutePath())) {
        is RuntimeStoreAdmission.Admitted -> admission.store
        is RuntimeStoreAdmission.Rejected -> return SemanticRuntimeResolution.Rejected(
            admission.failure,
        )
    }
    return ManagedSemanticRuntimeProvider(store).resolve(manifest, source)
}

private fun RuntimeStoreFailure.sidecarAdmissionFailure(): RuntimeAdmissionFailure = when (this) {
    RuntimeStoreFailure.STORE_INVALID -> RuntimeAdmissionFailure.SourceInvalid
    RuntimeStoreFailure.ARTIFACT_UNAVAILABLE -> RuntimeAdmissionFailure.ArtifactUnavailable
    RuntimeStoreFailure.DIGEST_MISMATCH -> RuntimeAdmissionFailure.DigestMismatch
    RuntimeStoreFailure.ARCHIVE_REJECTED -> RuntimeAdmissionFailure.ArchiveRejected
    RuntimeStoreFailure.LAYOUT_INVALID -> RuntimeAdmissionFailure.LayoutInvalid
    RuntimeStoreFailure.RUNTIME_INCOMPATIBLE -> RuntimeAdmissionFailure.RuntimeIncompatible
    RuntimeStoreFailure.INTERRUPTED -> RuntimeAdmissionFailure.Interrupted
}

internal enum class InstalledRuntimeDirectoryFailure { INVALID_PATH }

internal sealed interface InstalledRuntimeDirectoryAdmission {
    data class Admitted(val directory: InstalledRuntimeDirectory) :
        InstalledRuntimeDirectoryAdmission

    data class Rejected(val failure: InstalledRuntimeDirectoryFailure) :
        InstalledRuntimeDirectoryAdmission
}

/** An absolute normalized logical runtime namespace admitted before physical socket mapping. */
internal class InstalledRuntimeDirectory private constructor(val path: Path) {
    companion object {
        /**
         * Proof transition: `KAST_RUNTIME_DIRECTORY | java.io.tmpdir ->
         * InstalledRuntimeDirectoryAdmission`.
         *
         * Establishes a normalized absolute logical namespace without performing a write. Socket
         * paths are subsequently refined into [RuntimeSocketDirectory].
         */
        fun admit(): InstalledRuntimeDirectoryAdmission = admit(
            configured = System.getenv(RUNTIME_DIRECTORY_ENVIRONMENT),
            temporaryDirectory = System.getProperty("java.io.tmpdir"),
        )

        internal fun admit(
            configured: String?,
            temporaryDirectory: String?,
        ): InstalledRuntimeDirectoryAdmission {
            val logicalPath = try {
                configured
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?: temporaryDirectory
                        ?.takeIf(String::isNotBlank)
                        ?.let(Path::of)
                        ?.resolve("kast-runtime")
                    ?: return InstalledRuntimeDirectoryAdmission.Rejected(
                        InstalledRuntimeDirectoryFailure.INVALID_PATH,
                    )
            } catch (_: InvalidPathException) {
                return InstalledRuntimeDirectoryAdmission.Rejected(
                    InstalledRuntimeDirectoryFailure.INVALID_PATH,
                )
            }
            return InstalledRuntimeDirectoryAdmission.Admitted(
                InstalledRuntimeDirectory(logicalPath.toAbsolutePath().normalize()),
            )
        }
    }
}

internal enum class InstalledSidecarCacheRootFailure { INVALID_PATH }

internal sealed interface InstalledSidecarCacheRootAdmission {
    data class Admitted(val root: InstalledSidecarCacheRoot) :
        InstalledSidecarCacheRootAdmission

    data class Rejected(val failure: InstalledSidecarCacheRootFailure) :
        InstalledSidecarCacheRootAdmission
}

/** An absolute normalized Kast-owned cache root admitted before any filesystem effect. */
internal class InstalledSidecarCacheRoot private constructor(val path: Path) {
    companion object {
        /**
         * Proof transition: `KAST_CACHE_ROOT? + admitted user home ->
         * InstalledSidecarCacheRootAdmission`.
         *
         * Establishes an explicit absolute cache authority. An absent override derives the stable
         * production default; malformed, blank, relative, and filesystem-root overrides fail
         * closed before cache discovery or mutation.
         */
        fun admit(
            configured: String?,
            userHome: Path,
        ): InstalledSidecarCacheRootAdmission {
            val candidate = try {
                when {
                    configured == null -> userHome.resolve(".cache/kast/intellij-caches")
                    configured.isBlank() -> return rejectedCacheRoot()
                    else -> Path.of(configured)
                }
            } catch (_: InvalidPathException) {
                return rejectedCacheRoot()
            }
            val normalized = candidate.normalize()
            if (!normalized.isAbsolute || normalized.nameCount == 0) return rejectedCacheRoot()
            return InstalledSidecarCacheRootAdmission.Admitted(
                InstalledSidecarCacheRoot(normalized),
            )
        }

        private fun rejectedCacheRoot() = InstalledSidecarCacheRootAdmission.Rejected(
            InstalledSidecarCacheRootFailure.INVALID_PATH,
        )
    }
}
