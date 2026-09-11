package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.InstalledBrokerServerRunner
import io.github.amichne.kast.appserver.InstalledSavedConfigurationIngress
import io.github.amichne.kast.appserver.SavedConfigurationIngress
import io.github.amichne.kast.appserver.host.installedCodexClientLauncher
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandGraphFailure
import io.github.amichne.kast.cli.command.CliCommandSurface
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.CliLocalMetadataFailure
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.distribution.contract.IndexerHeapFailure
import io.github.amichne.kast.distribution.contract.SemanticRuntimeFailure
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifestAdmission
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSource
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSourceSelection
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationPathSelection
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationRejection
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationRejectionDetail
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSwitch
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
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

private const val RUNTIME_DIRECTORY_ENVIRONMENT = "KAST_RUNTIME_DIRECTORY"

internal const val SUPPORTED_IDE_BUILD = "262.9437.185"
internal const val SUPPORTED_KOTLIN_PLUGIN_BUILD = "262.9437.185-IJ"

internal enum class SavedConfigurationFailure {
    UNREADABLE,
    DUPLICATE_RECORD,
    UNSUPPORTED_RECORD,
    UNKNOWN,
}

internal class ConfigurationCompositionFailure(val rejection: ConfigurationRejection) : KastCliCompositionFailure {
    override val outputReason: String = "configuration-${rejection.reason.name.lowercase()}"
}

internal sealed interface InstalledCompositionFailure : KastCliCompositionFailure {
    data class IndexerHeapRejected(val failure: IndexerHeapFailure) : InstalledCompositionFailure

    data class SavedConfigurationRejected(val failure: SavedConfigurationFailure) : InstalledCompositionFailure

    data class ControlProductRejected(val failure: InstalledKastControlProductFailure) : InstalledCompositionFailure

    data class ResourceUnavailable(val resource: InstalledControlResource) : InstalledCompositionFailure

    data class ProductVersionRejected(val failure: IdeHostCompatibilityFailure) : InstalledCompositionFailure

    data class RuntimeManifestRejected(val failure: SemanticRuntimeFailure) : InstalledCompositionFailure

    data class SidecarMetadataRejected(val failure: IndexSeedFailure) : InstalledCompositionFailure

    data object SidecarPathsRejected : InstalledCompositionFailure

    data class SidecarCacheRootRejected(val failure: InstalledSidecarCacheRootFailure) : InstalledCompositionFailure

    data class RuntimeDirectoryRejected(val failure: InstalledRuntimeDirectoryFailure) : InstalledCompositionFailure

    data class RuntimeProcessModeRejected(val failure: RuntimeProcessModeFailure) : InstalledCompositionFailure {
        override val outputReason: String = "invalid-launchd-flag"
    }

    data class CommandGraphRejected(val failures: Set<CliCommandGraphFailure>) : InstalledCompositionFailure

    data class SchemaRejected(val failure: InstalledSchemaFailure) : InstalledCompositionFailure

    data class LocalMetadataRejected(val failure: CliLocalMetadataFailure) : InstalledCompositionFailure
}

/** The sole service-loaded composition for an installed Kotlin `kast` executable. */
internal class InstalledKastCliComposition : KastCliComposition {
    override fun inspect(start: Path): CliExit =
        when (val construction = create()) {
            is KastCliCompositionConstruction.Created -> construction.cli.execute(emptyList(), start)
            is KastCliCompositionConstruction.Rejected ->
                CliExit.Complete(
                    io.github.amichne.kast.cli.projection.ProductInspectionDocuments.blocked(
                        start,
                        construction.failure,
                    )
                )
        }

    /**
     * Proof transition: `installed process environment -> KastCliCompositionConstruction`.
     *
     * Establishes one complete CLI graph with an admitted control product, product version, protocol resources, exact
     * installed-IDE support, private runtime directory, and metadata. [InstalledCompositionFailure] is the closed
     * expected failure. Filesystem and environment extraction remain in this installed composition boundary.
     */
    override fun create(): KastCliCompositionConstruction {
        val environment = System.getenv()
        environment["KAST_SAVED_CONFIGURATION_FAILURE"]?.let { raw ->
            val failure =
                when (raw) {
                    "unreadable" -> SavedConfigurationFailure.UNREADABLE
                    "duplicate-record" -> SavedConfigurationFailure.DUPLICATE_RECORD
                    "unsupported-record" -> SavedConfigurationFailure.UNSUPPORTED_RECORD
                    else -> SavedConfigurationFailure.UNKNOWN
                }
            return KastCliCompositionConstruction.Rejected(
                InstalledCompositionFailure.SavedConfigurationRejected(failure)
            )
        }

        val sources =
            when (val saved = InstalledSavedConfigurationIngress.load(environment)) {
                is SavedConfigurationIngress.Loaded -> saved.sources
                is SavedConfigurationIngress.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        SavedConfigurationIngressCompositionFailure(saved.rejection)
                    )
            }
        val configuration =
            when (val resolved = ResolvedKastConfiguration.resolve(sources)) {
                is Refinement.Refined -> resolved.value
                is Refinement.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        when (val detail = resolved.failure.detail) {
                            is ConfigurationRejectionDetail.Heap ->
                                InstalledCompositionFailure.IndexerHeapRejected(detail.failure)
                            ConfigurationRejectionDetail.General ->
                                when (resolved.failure.key) {
                                    ConfigurationParameter.ENABLE_LAUNCHD.key ->
                                        InstalledCompositionFailure.RuntimeProcessModeRejected(
                                            RuntimeProcessModeFailure.INVALID_ENVIRONMENT_VALUE
                                        )
                                    else -> ConfigurationCompositionFailure(resolved.failure)
                                }
                        }
                    )
            }
        val sidecarEnvironment =
            io.github.amichne.kast.cli.SidecarEnvironmentInputs.from(configuration, environment["JAVA_HOME"])
        val maxHeap = configuration.indexerHeap
        val processMode =
            when (configuration.launchd) {
                ConfigurationSwitch.ENABLED -> RuntimeProcessMode.Launchd
                ConfigurationSwitch.DISABLED -> RuntimeProcessMode.Direct
            }
        val processCapabilities = processMode.capabilities()
        val installation =
            when (val admission = InstalledKastControlProduct.discover()) {
                is InstalledKastControlProductAdmission.Admitted -> admission.product
                is InstalledKastControlProductAdmission.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.ControlProductRejected(admission.failure)
                    )
            }
        val productVersion =
            when (val admission = installation.productVersion()) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.ProductVersionRejected(admission.failure)
                    )
            }
        val protocol =
            when (val construction = installation.protocolResources()) {
                is InstalledProtocolResourcesConstruction.Constructed -> construction.resources
                is InstalledProtocolResourcesConstruction.Rejected ->
                    return KastCliCompositionConstruction.Rejected(construction.failure)
            }
        val manifestResource =
            when (val resource = installation.readResource(InstalledControlResource.SEMANTIC_RUNTIME)) {
                is InstalledControlResourceRead.Read -> resource.value
                is InstalledControlResourceRead.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.ResourceUnavailable(resource.resource)
                    )
            }
        val manifest =
            when (val admission = SemanticRuntimeManifest.admit(manifestResource)) {
                is SemanticRuntimeManifestAdmission.Admitted -> admission.manifest
                is SemanticRuntimeManifestAdmission.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.RuntimeManifestRejected(admission.failure)
                    )
            }
        if (manifest.productVersion.value != productVersion.value) {
            return KastCliCompositionConstruction.Rejected(
                InstalledCompositionFailure.RuntimeManifestRejected(SemanticRuntimeFailure.MANIFEST_INVALID)
            )
        }
        val support =
            when (
                val admission =
                    SupportedIdeRuntimePair.admit(
                        SUPPORTED_IDE_BUILD,
                        SUPPORTED_KOTLIN_PLUGIN_BUILD,
                    )
            ) {
                is SupportedIdeRuntimePairAdmission.Admitted -> admission.pair
                is SupportedIdeRuntimePairAdmission.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.SidecarMetadataRejected(admission.failure)
                    )
            }
        val commandGraphFactory =
            when (val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
                is CliCommandGraphConstruction.Created -> construction.factory
                is CliCommandGraphConstruction.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.CommandGraphRejected(construction.failures)
                    )
            }
        val runtimeDirectory =
            when (
                val admission =
                    InstalledRuntimeDirectory.admit(
                        configured =
                            configuration.runtimeDirectory.boundaryPath()
                                ?: installation.root.resolve("state/run").toString(),
                        temporaryDirectory = System.getProperty("java.io.tmpdir"),
                    )
            ) {
                is InstalledRuntimeDirectoryAdmission.Admitted -> admission.directory
                is InstalledRuntimeDirectoryAdmission.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.RuntimeDirectoryRejected(admission.failure)
                    )
            }
        if (runtimeDirectory.path != installation.root.resolve("state/run")) {
            return KastCliCompositionConstruction.Rejected(
                InstalledCompositionFailure.RuntimeDirectoryRejected(InstalledRuntimeDirectoryFailure.INVALID_PATH)
            )
        }
        val localMetadata =
            when (
                val construction =
                    installation.localMetadata(
                        productVersion.value,
                        protocol,
                        commandGraphFactory.surface,
                    )
            ) {
                is InstalledLocalMetadataConstruction.Constructed -> construction.metadata
                is InstalledLocalMetadataConstruction.Rejected ->
                    return KastCliCompositionConstruction.Rejected(construction.failure)
            }
        val userHome =
            try {
                Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                return KastCliCompositionConstruction.Rejected(InstalledCompositionFailure.SidecarPathsRejected)
            }
        val cacheRoot =
            when (
                val admission =
                    InstalledSidecarCacheRoot.admit(
                        configuration.cacheRoot.boundaryPath() ?: installation.root.resolve("state/cache").toString(),
                        userHome,
                    )
            ) {
                is InstalledSidecarCacheRootAdmission.Admitted -> admission.root.path
                is InstalledSidecarCacheRootAdmission.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.SidecarCacheRootRejected(admission.failure)
                    )
            }
        if (cacheRoot != installation.root.resolve("state/cache")) {
            return KastCliCompositionConstruction.Rejected(
                InstalledCompositionFailure.SidecarCacheRootRejected(InstalledSidecarCacheRootFailure.INVALID_PATH)
            )
        }
        val endpointLocator =
            Sha256RuntimeEndpointLocator(
                RuntimeSocketDirectory.installed(runtimeDirectory.path),
                manifest.runtimeId,
            )
        val defaultSourceSystem = userHome.resolve("Library/Caches/JetBrains/IntelliJIdea2026.2")
        fun cachePreparer(consent: IndexSeedConsentProvider) =
            FilesystemSidecarCachePreparer(
                cacheRoot,
                defaultSourceSystem,
                IndexSeedFilesystemService(
                    FilesystemSourceIdeQuiescenceProbe,
                    ApfsIndexSeedFilesystemProbe,
                    ApfsCoWIndexSeedCloner,
                    consent,
                    JsonLineIndexSeedActivitySink(System.err),
                ),
            )
        val cacheReleaseIdentity =
            when (
                val admission =
                    SidecarCacheReleaseIdentity.admit(
                        support,
                        manifest.kastPluginDigest.value,
                        manifest.runtimeId,
                    )
            ) {
                is SidecarCacheReleaseIdentityAdmission.Admitted -> admission.identity
                is SidecarCacheReleaseIdentityAdmission.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.SidecarMetadataRejected(admission.failure)
                    )
            }
        val cacheLifecycle =
            FilesystemRootSidecarCacheLifecycle(
                cacheRoot,
                cacheReleaseIdentity,
                SidecarIdeRuntimeResolver { supported, digest, selection ->
                    InstalledIdeRuntimeDiscovery.discover(supported, digest, selection)
                },
                importEnvironment = sidecarEnvironment::importEnvironment,
            )
        val installedKastExecutable =
            installation.kastExecutable()
                ?: return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.ControlProductRejected(
                        InstalledKastControlProductFailure.KAST_EXECUTABLE_UNAVAILABLE
                    )
                )
        val lifecycle = ExactRootRuntimeLifecycle(JdkUnixDomainEndpointProbe, processCapabilities.authority)
        fun sidecarDemander(
            request: io.github.amichne.kast.appserver.InstalledWorkerStartRequest,
            capture: (WorkerProcessCapture) -> Unit,
            consent: IndexSeedConsentProvider,
        ): RootRuntimeDemander {
            val selected = request.configuration
            if (
                (selected.cacheRoot.boundaryPath()?.let(Path::of) ?: cacheRoot) != cacheRoot ||
                    (selected.runtimeDirectory.boundaryPath()?.let(Path::of) ?: runtimeDirectory.path) !=
                        runtimeDirectory.path
            ) {
                return RootRuntimeDemander { _, _, _ ->
                    RuntimeAdmission.Rejected(RuntimeAdmissionFailure.LayoutInvalid)
                }
            }
            val selectedInputs = SidecarEnvironmentInputs.from(selected, environment["JAVA_HOME"])
            val selectedProcesses =
                when (selected.launchd) {
                    ConfigurationSwitch.ENABLED -> RuntimeProcessMode.Launchd
                    ConfigurationSwitch.DISABLED -> RuntimeProcessMode.Direct
                }.capabilities()
            val selectedLifecycle = ExactRootRuntimeLifecycle(JdkUnixDomainEndpointProbe, selectedProcesses.authority)
            val selectedCache =
                FilesystemRootSidecarCacheLifecycle(
                    cacheRoot,
                    cacheReleaseIdentity,
                    SidecarIdeRuntimeResolver { supported, digest, selection ->
                        InstalledIdeRuntimeDiscovery.discover(supported, digest, selection)
                    },
                    selectedInputs::importEnvironment,
                )
            return InstalledSidecarRootRuntimeDemander(
                endpointLocator,
                support,
                userHome,
                ManagedInstalledSidecarPayloadResolver(
                    manifest,
                    InstalledSemanticRuntimeResolver { selectedManifest ->
                        resolveInstalledRuntime(selectedManifest, selected, installation.root)
                    },
                ),
                { supported, digest, selection ->
                    InstalledIdeRuntimeDiscovery.discover(supported, digest, selection)
                },
                cachePreparer(consent),
                ExactSidecarProcessDemander(
                    runtimeDemanderFactory = { executable, context ->
                        val exact =
                            ExactRootProcessRuntimeDemander(
                                executable,
                                context,
                                selectedProcesses.starter,
                                bootstrapProcessAuthority = selectedProcesses.bootstrapAuthority,
                            )
                        RuntimeDemander { root, endpoint ->
                            capture(
                                WorkerProcessCapture(
                                    executable,
                                    context,
                                    endpoint,
                                    selectedProcesses.bootstrapAuthority,
                                    selectedLifecycle,
                                )
                            )
                            exact.demand(root, endpoint)
                        }
                    }
                ),
                legacyProcessAuthority = selectedProcesses.authority,
                cacheLifecycle = selectedCache,
                lifecycle = selectedLifecycle,
                maxHeap = request.heap,
                sidecarEnvironment = selectedInputs,
            )
        }
        fun retireUnreserved(requested: Path): io.github.amichne.kast.appserver.InstalledWorkerRetirement {
            val unproven = io.github.amichne.kast.appserver.InstalledWorkerRetirement.UNPROVEN
            val root =
                when (val discovered = FilesystemCanonicalRootDiscovery.discover(requested)) {
                    is CanonicalRootDiscovery.Discovered -> discovered.root
                    is CanonicalRootDiscovery.Rejected -> return unproven
                }
            if (root.path != requested) return unproven
            val selected =
                when (
                    val admitted =
                        io.github.amichne.kast.appserver.InstalledWorkspaceConfigurationIngress.resolve(
                            installation.root,
                            requested,
                            environment,
                        )
                ) {
                    is io.github.amichne.kast.kernel.Refinement.Refined -> admitted.value
                    is io.github.amichne.kast.kernel.Refinement.Rejected -> return unproven
                }
            val endpoint =
                when (val located = endpointLocator.locate(root)) {
                    is RuntimeEndpointResolution.Resolved -> located.endpoint
                    is RuntimeEndpointResolution.Rejected -> return unproven
                }
            val selectedInputs = SidecarEnvironmentInputs.from(selected, environment["JAVA_HOME"])
            val selectedCache =
                FilesystemRootSidecarCacheLifecycle(
                    cacheRoot,
                    cacheReleaseIdentity,
                    SidecarIdeRuntimeResolver { supported, digest, selection ->
                        InstalledIdeRuntimeDiscovery.discover(supported, digest, selection)
                    },
                    selectedInputs::importEnvironment,
                )
            val exact =
                when (val cache = selectedCache.observe(root.path)) {
                    RootSidecarCacheObservation.Absent -> endpoint
                    is RootSidecarCacheObservation.Rejected -> return unproven
                    is RootSidecarCacheObservation.Identified ->
                        when (
                            val located =
                                endpoint.forSidecarCache(
                                    cache.status.cacheIdentity,
                                    cache.status.semanticRuntimeId,
                                    cache.status.cacheRoot,
                                )
                        ) {
                            is RuntimeEndpointResolution.Resolved -> located.endpoint
                            is RuntimeEndpointResolution.Rejected -> return unproven
                        }
                }
            val processes =
                when (selected.launchd) {
                    ConfigurationSwitch.ENABLED -> RuntimeProcessMode.Launchd
                    ConfigurationSwitch.DISABLED -> RuntimeProcessMode.Direct
                }.capabilities()
            return when (ExactRootRuntimeLifecycle(JdkUnixDomainEndpointProbe, processes.authority).stop(exact)) {
                is RuntimeStopResult.Stopped -> io.github.amichne.kast.appserver.InstalledWorkerRetirement.EXACT_RETIRED
                is RuntimeStopResult.Rejected -> unproven
            }
        }
        val workerEffects =
            io.github.amichne.kast.cli.InstalledRuntimeWorkerEffects(
                ::sidecarDemander,
                unreservedRetirement = ::retireUnreserved,
            )
        val workerClient =
            io.github.amichne.kast.appserver.InstalledWorkerClient(
                installedKastExecutable,
                userHome,
                environment,
                seedConsent = FrontendWorkerSeedConsent,
            )
        return KastCliCompositionConstruction.Created(
            KastCli(
                commandGraphFactory,
                FilesystemCanonicalRootDiscovery,
                endpointLocator,
                io.github.amichne.kast.cli.CoordinatedRootRuntimeDemander(workerClient, maxHeap),
                UnixDomainWireClient(activity = JsonLineWireActivitySink(System.err)),
                localMetadata,
                io.github.amichne.kast.cli.CoordinatedRuntimeLifecycle(workerClient, lifecycle),
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
                cacheLifecycle = cacheLifecycle,
                existingIdeClient =
                    io.github.amichne.kast.cli.ide.ExistingIdeSocketClient(userHome, configuration.readLimits),
                appServerManager =
                    io.github.amichne.kast.appserver.InstalledAppServerManager(installedKastExecutable, userHome),
                brokerServerRunner =
                    InstalledBrokerServerRunner(
                        installedKastExecutable,
                        userHome,
                        workerEffects = workerEffects,
                    ),
                codexClientLauncher =
                    installedCodexClientLauncher(
                        installedKastExecutable,
                        userHome,
                    ),
            )
        )
    }
}

internal enum class InstalledKastControlProductFailure {
    CODE_SOURCE_UNAVAILABLE,
    CODE_SOURCE_INVALID,
    LIBRARY_DIRECTORY_INVALID,
    PRODUCT_ROOT_UNAVAILABLE,
    RESOURCE_DIRECTORY_UNAVAILABLE,
    KAST_EXECUTABLE_UNAVAILABLE,
}

private sealed interface InstalledKastControlProductAdmission {
    data class Admitted(val product: InstalledKastControlProduct) : InstalledKastControlProductAdmission

    data class Rejected(val failure: InstalledKastControlProductFailure) : InstalledKastControlProductAdmission
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

    data class Rejected(val failure: InstalledCompositionFailure) : InstalledLocalMetadataConstruction
}

/** One control installation proven by the CLI jar and exact `share/kast` resources. */
private class InstalledKastControlProduct private constructor(val root: Path) {
    fun kastExecutable(): Path? = admittedFile(root.resolve("bin/kast"), executable = true)

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
        val schema =
            when (
                val construction =
                    installedSchema(
                        protocol.operationRegistry,
                        protocol.wireSchema,
                        commandSurface,
                    )
            ) {
                is InstalledSchemaConstruction.Constructed -> construction.document
                is InstalledSchemaConstruction.Rejected ->
                    return InstalledLocalMetadataConstruction.Rejected(
                        InstalledCompositionFailure.SchemaRejected(construction.failure)
                    )
            }
        return when (
            val admission =
                CliLocalMetadata.admit(
                    productVersion,
                    schema,
                )
        ) {
            is CliLocalMetadataAdmission.Admitted -> InstalledLocalMetadataConstruction.Constructed(admission.metadata)
            is CliLocalMetadataAdmission.Rejected ->
                InstalledLocalMetadataConstruction.Rejected(
                    InstalledCompositionFailure.LocalMetadataRejected(admission.failure)
                )
        }
    }

    /**
     * Proof transition: `installed metadata files -> InstalledProtocolResourcesConstruction`.
     *
     * Establishes one read of each exact protocol resource plus its SHA-256 identity. Missing resources remain closed
     * [InstalledCompositionFailure] data; raw text remains inside the installed-control boundary.
     */
    fun protocolResources(): InstalledProtocolResourcesConstruction {
        val operationRegistry =
            when (val resource = readResource(InstalledControlResource.OPERATION_REGISTRY)) {
                is InstalledControlResourceRead.Read -> resource.value
                is InstalledControlResourceRead.Rejected ->
                    return InstalledProtocolResourcesConstruction.Rejected(
                        InstalledCompositionFailure.ResourceUnavailable(resource.resource)
                    )
            }
        val wireSchema =
            when (val resource = readResource(InstalledControlResource.WIRE_SCHEMA)) {
                is InstalledControlResourceRead.Read -> resource.value
                is InstalledControlResourceRead.Rejected ->
                    return InstalledProtocolResourcesConstruction.Rejected(
                        InstalledCompositionFailure.ResourceUnavailable(resource.resource)
                    )
            }
        return InstalledProtocolResourcesConstruction.Constructed(
            InstalledProtocolResources(
                operationRegistry,
                wireSchema,
            )
        )
    }

    /**
     * Proof transition: `installed CLI package metadata -> Refinement<KastPluginVersion, ...>`.
     *
     * Establishes the exact release version embedded in the installed CLI jar. Malformed or absent metadata remains a
     * closed compatibility failure; raw package metadata is extracted only here.
     */
    fun productVersion(): Refinement<KastPluginVersion, IdeHostCompatibilityFailure> =
        KastPluginVersion.parse(InstalledKastCliComposition::class.java.`package`.implementationVersion.orEmpty())

    /**
     * Proof transition: `InstalledControlResource -> InstalledControlResourceRead`.
     *
     * Establishes readable installed resource text. The finite rejected variant retains the exact resource identity.
     * Filesystem reads remain inside this installed-control boundary.
     */
    fun readResource(resource: InstalledControlResource): InstalledControlResourceRead =
        try {
            InstalledControlResourceRead.Read(Files.readString(root.resolve("share/kast/${resource.fileName}")))
        } catch (_: IOException) {
            InstalledControlResourceRead.Rejected(resource)
        }

    private fun admittedFile(path: Path, executable: Boolean): Path? {
        if (Files.isSymbolicLink(path)) return null
        val physical =
            try {
                path.toRealPath()
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
        return physical.takeIf { candidate ->
            Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                (!executable || Files.isExecutable(candidate))
        }
    }

    companion object {
        /**
         * Proof transition: `InstalledKastCliComposition code source -> InstalledKastControlProductAdmission`.
         *
         * Establishes that the provider was loaded from the installation's `lib` directory and owns one sibling
         * `share/kast` resource directory. [InstalledKastControlProductFailure] is the closed expected failure. Raw URI
         * and paths remain inside this adapter.
         */
        fun discover(): InstalledKastControlProductAdmission {
            val codeSource =
                try {
                    Path.of(InstalledKastCliComposition::class.java.protectionDomain.codeSource.location.toURI())
                        .toRealPath()
                } catch (_: IOException) {
                    return InstalledKastControlProductAdmission.Rejected(
                        InstalledKastControlProductFailure.CODE_SOURCE_UNAVAILABLE
                    )
                } catch (_: URISyntaxException) {
                    return InstalledKastControlProductAdmission.Rejected(
                        InstalledKastControlProductFailure.CODE_SOURCE_INVALID
                    )
                }
            val libraryDirectory =
                codeSource.parent?.takeIf { it.fileName.toString() == "lib" }
                    ?: return InstalledKastControlProductAdmission.Rejected(
                        InstalledKastControlProductFailure.LIBRARY_DIRECTORY_INVALID
                    )
            val root =
                libraryDirectory.parent
                    ?: return InstalledKastControlProductAdmission.Rejected(
                        InstalledKastControlProductFailure.PRODUCT_ROOT_UNAVAILABLE
                    )
            if (!Files.isDirectory(root.resolve("share/kast"))) {
                return InstalledKastControlProductAdmission.Rejected(
                    InstalledKastControlProductFailure.RESOURCE_DIRECTORY_UNAVAILABLE
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
        val installed =
            when (val resolution = resolver.resolve(manifest)) {
                is SemanticRuntimeResolution.Installed -> resolution.runtime
                is SemanticRuntimeResolution.Rejected ->
                    return SidecarPayloadResolution.Rejected(resolution.failure.sidecarAdmissionFailure())
            }
        if (installed.runtimeId != manifest.runtimeId) {
            return SidecarPayloadResolution.Rejected(RuntimeAdmissionFailure.RuntimeIdentityMismatch)
        }
        return when (
            val admission =
                SidecarPayload.admit(
                    installed.runtimeId,
                    installed.executable,
                    installed.directory.resolve("private-plugins"),
                    manifest.kastPluginDigest.value,
                )
        ) {
            is SidecarPayloadAdmission.Admitted -> SidecarPayloadResolution.Resolved(admission.payload)
            is SidecarPayloadAdmission.Rejected ->
                SidecarPayloadResolution.Rejected(RuntimeAdmissionFailure.LayoutInvalid)
        }
    }
}

/** Performs small sidecar payload source selection and store admission only after demand. */
private fun resolveInstalledRuntime(
    manifest: SemanticRuntimeManifest,
    configuration: ResolvedKastConfiguration,
    installationRoot: Path,
): SemanticRuntimeResolution {
    val source =
        when (val selected = SemanticRuntimeSource.select(configuration.runtimeArchive.boundaryPath())) {
            is SemanticRuntimeSourceSelection.Managed -> selected.source
            is SemanticRuntimeSourceSelection.Preseeded -> selected.source
            is SemanticRuntimeSourceSelection.Rejected ->
                return SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.STORE_INVALID)
        }
    val storePath =
        when (val selected = configuration.runtimeStore) {
            ConfigurationPathSelection.OwnerDefault -> installationRoot.resolve("runtime-payloads")
            is ConfigurationPathSelection.Selected -> selected.path
        }
    if (storePath != installationRoot.resolve("runtime-payloads"))
        return SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.STORE_INVALID)
    val store =
        when (val admission = RuntimeStore.admit(storePath.toAbsolutePath())) {
            is RuntimeStoreAdmission.Admitted -> admission.store
            is RuntimeStoreAdmission.Rejected -> return SemanticRuntimeResolution.Rejected(admission.failure)
        }
    return ManagedSemanticRuntimeProvider(store).resolve(manifest, source)
}

/** Existing path ingress adapters retain responsibility for physical filesystem admission. */
private fun ConfigurationPathSelection.boundaryPath(): String? =
    when (this) {
        ConfigurationPathSelection.OwnerDefault -> null
        is ConfigurationPathSelection.Selected -> path.toString()
    }

private fun RuntimeStoreFailure.sidecarAdmissionFailure(): RuntimeAdmissionFailure =
    when (this) {
        RuntimeStoreFailure.STORE_INVALID -> RuntimeAdmissionFailure.SourceInvalid
        RuntimeStoreFailure.ARTIFACT_UNAVAILABLE -> RuntimeAdmissionFailure.ArtifactUnavailable
        RuntimeStoreFailure.DIGEST_MISMATCH -> RuntimeAdmissionFailure.DigestMismatch
        RuntimeStoreFailure.ARCHIVE_REJECTED -> RuntimeAdmissionFailure.ArchiveRejected
        RuntimeStoreFailure.LAYOUT_INVALID -> RuntimeAdmissionFailure.LayoutInvalid
        RuntimeStoreFailure.RUNTIME_INCOMPATIBLE -> RuntimeAdmissionFailure.RuntimeIncompatible
        RuntimeStoreFailure.INTERRUPTED -> RuntimeAdmissionFailure.Interrupted
    }

internal enum class InstalledRuntimeDirectoryFailure {
    INVALID_PATH
}

internal sealed interface InstalledRuntimeDirectoryAdmission {
    data class Admitted(val directory: InstalledRuntimeDirectory) : InstalledRuntimeDirectoryAdmission

    data class Rejected(val failure: InstalledRuntimeDirectoryFailure) : InstalledRuntimeDirectoryAdmission
}

/** An absolute normalized logical runtime namespace admitted before physical socket mapping. */
internal class InstalledRuntimeDirectory private constructor(val path: Path) {
    companion object {
        /**
         * Proof transition: `KAST_RUNTIME_DIRECTORY | java.io.tmpdir -> InstalledRuntimeDirectoryAdmission`.
         *
         * Establishes a normalized absolute logical namespace without performing a write. Socket paths are subsequently
         * refined into [RuntimeSocketDirectory].
         */
        fun admit(): InstalledRuntimeDirectoryAdmission =
            admit(
                configured = System.getenv(RUNTIME_DIRECTORY_ENVIRONMENT),
                temporaryDirectory = System.getProperty("java.io.tmpdir"),
            )

        internal fun admit(
            configured: String?,
            temporaryDirectory: String?,
        ): InstalledRuntimeDirectoryAdmission {
            val logicalPath =
                try {
                    configured?.takeIf(String::isNotBlank)?.let(Path::of)
                        ?: temporaryDirectory?.takeIf(String::isNotBlank)?.let(Path::of)?.resolve("kast-runtime")
                        ?: return InstalledRuntimeDirectoryAdmission.Rejected(
                            InstalledRuntimeDirectoryFailure.INVALID_PATH
                        )
                } catch (_: InvalidPathException) {
                    return InstalledRuntimeDirectoryAdmission.Rejected(InstalledRuntimeDirectoryFailure.INVALID_PATH)
                }
            return InstalledRuntimeDirectoryAdmission.Admitted(
                InstalledRuntimeDirectory(logicalPath.toAbsolutePath().normalize())
            )
        }
    }
}

internal enum class InstalledSidecarCacheRootFailure {
    INVALID_PATH
}

internal sealed interface InstalledSidecarCacheRootAdmission {
    data class Admitted(val root: InstalledSidecarCacheRoot) : InstalledSidecarCacheRootAdmission

    data class Rejected(val failure: InstalledSidecarCacheRootFailure) : InstalledSidecarCacheRootAdmission
}

/** An absolute normalized Kast-owned cache root admitted before any filesystem effect. */
internal class InstalledSidecarCacheRoot private constructor(val path: Path) {
    companion object {
        /**
         * Proof transition: `KAST_CACHE_ROOT? + admitted user home -> InstalledSidecarCacheRootAdmission`.
         *
         * Establishes an explicit absolute cache authority. An absent override derives the stable production default;
         * malformed, blank, relative, and filesystem-root overrides fail closed before cache discovery or mutation.
         */
        fun admit(
            configured: String?,
            userHome: Path,
        ): InstalledSidecarCacheRootAdmission {
            val candidate =
                try {
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
            return InstalledSidecarCacheRootAdmission.Admitted(InstalledSidecarCacheRoot(normalized))
        }

        private fun rejectedCacheRoot() =
            InstalledSidecarCacheRootAdmission.Rejected(InstalledSidecarCacheRootFailure.INVALID_PATH)
    }
}

/** Installation proof shared by the semantic executable and its integration host. */
internal fun installedKastExecutable(): Refinement<Path, InstalledKastControlProductFailure> =
    when (val admission = InstalledKastControlProduct.discover()) {
        is InstalledKastControlProductAdmission.Admitted ->
            admission.product.kastExecutable()?.let { Refinement.Refined(it) }
                ?: Refinement.Rejected(InstalledKastControlProductFailure.KAST_EXECUTABLE_UNAVAILABLE)
        is InstalledKastControlProductAdmission.Rejected -> Refinement.Rejected(admission.failure)
    }

/** Passive control identity admission is independent of runtime-directory and process configuration. */
internal fun inspectInstalledControlIdentity(): Refinement<SidecarProductIdentity, InstalledCompositionFailure> {
    val installation =
        when (val admission = InstalledKastControlProduct.discover()) {
            is InstalledKastControlProductAdmission.Admitted -> admission.product
            is InstalledKastControlProductAdmission.Rejected ->
                return Refinement.Rejected(InstalledCompositionFailure.ControlProductRejected(admission.failure))
        }
    val version =
        when (val admission = installation.productVersion()) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected ->
                return Refinement.Rejected(InstalledCompositionFailure.ProductVersionRejected(admission.failure))
        }
    val document =
        when (val read = installation.readResource(InstalledControlResource.SEMANTIC_RUNTIME)) {
            is InstalledControlResourceRead.Read -> read.value
            is InstalledControlResourceRead.Rejected ->
                return Refinement.Rejected(InstalledCompositionFailure.ResourceUnavailable(read.resource))
        }
    val manifest =
        when (val admission = SemanticRuntimeManifest.admit(document)) {
            is SemanticRuntimeManifestAdmission.Admitted -> admission.manifest
            is SemanticRuntimeManifestAdmission.Rejected ->
                return Refinement.Rejected(InstalledCompositionFailure.RuntimeManifestRejected(admission.failure))
        }
    if (manifest.productVersion.value != version.value)
        return Refinement.Rejected(
            InstalledCompositionFailure.RuntimeManifestRejected(SemanticRuntimeFailure.MANIFEST_INVALID)
        )
    val support =
        when (val admission = SupportedIdeRuntimePair.admit(SUPPORTED_IDE_BUILD, SUPPORTED_KOTLIN_PLUGIN_BUILD)) {
            is SupportedIdeRuntimePairAdmission.Admitted -> admission.pair
            is SupportedIdeRuntimePairAdmission.Rejected ->
                return Refinement.Rejected(InstalledCompositionFailure.SidecarMetadataRejected(admission.failure))
        }
    return Refinement.Refined(SidecarProductIdentity(version, manifest.runtimeId, support, manifest.kastPluginDigest))
}
