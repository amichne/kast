package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.InstalledAppServerManager
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
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationRejection
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.KastPluginVersion
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

internal class ConfigurationCompositionFailure(val rejection: ConfigurationRejection) : KastCliCompositionFailure {
    override val outputReason = "configuration-${rejection.reason.name.lowercase()}"
}

internal sealed interface InstalledCompositionFailure : KastCliCompositionFailure {
    data class ControlProductRejected(val failure: InstalledKastControlProductFailure) : InstalledCompositionFailure

    data class ResourceUnavailable(val resource: InstalledControlResource) : InstalledCompositionFailure

    data class ProductVersionRejected(val failure: IdeHostCompatibilityFailure) : InstalledCompositionFailure

    data class CommandGraphRejected(val failures: Set<CliCommandGraphFailure>) : InstalledCompositionFailure

    data class SchemaRejected(val failure: InstalledSchemaFailure) : InstalledCompositionFailure

    data class LocalMetadataRejected(val failure: CliLocalMetadataFailure) : InstalledCompositionFailure
}

/** The installed control owns local metadata and broker effects; semantic authority belongs to the existing IDE. */
internal class InstalledKastCliComposition : KastCliComposition {
    override fun create(): KastCliCompositionConstruction {
        val environment = System.getenv()
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
                    return KastCliCompositionConstruction.Rejected(ConfigurationCompositionFailure(resolved.failure))
            }
        val installation =
            when (val admission = InstalledKastControlProduct.discover()) {
                is InstalledKastControlProductAdmission.Admitted -> admission.product
                is InstalledKastControlProductAdmission.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.ControlProductRejected(admission.failure)
                    )
            }
        val version =
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
        val graph =
            when (val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
                is CliCommandGraphConstruction.Created -> construction.factory
                is CliCommandGraphConstruction.Rejected ->
                    return KastCliCompositionConstruction.Rejected(
                        InstalledCompositionFailure.CommandGraphRejected(construction.failures)
                    )
            }
        val metadata =
            when (val construction = installation.localMetadata(version.value, protocol, graph.surface)) {
                is InstalledLocalMetadataConstruction.Constructed -> construction.metadata
                is InstalledLocalMetadataConstruction.Rejected ->
                    return KastCliCompositionConstruction.Rejected(construction.failure)
            }
        val executable =
            installation.kastExecutable()
                ?: return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.ControlProductRejected(
                        InstalledKastControlProductFailure.KAST_EXECUTABLE_UNAVAILABLE
                    )
                )
        val userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        return KastCliCompositionConstruction.Created(
            KastCli(
                commandGraphFactory = graph,
                rootDiscovery = FilesystemCanonicalRootDiscovery,
                localMetadata = metadata,
                productVersion = version,
                existingIdeClient =
                    io.github.amichne.kast.cli.ide.ExistingIdeSocketClient(userHome, configuration.readLimits),
                appServerManager = InstalledAppServerManager(executable, userHome),
                brokerServerRunner = InstalledBrokerServerRunner(executable, userHome),
                codexClientLauncher = installedCodexClientLauncher(executable, userHome),
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

internal fun installedKastExecutable(): Refinement<Path, InstalledKastControlProductFailure> =
    when (val admission = InstalledKastControlProduct.discover()) {
        is InstalledKastControlProductAdmission.Admitted ->
            admission.product.kastExecutable()?.let { Refinement.Refined(it) }
                ?: Refinement.Rejected(InstalledKastControlProductFailure.KAST_EXECUTABLE_UNAVAILABLE)
        is InstalledKastControlProductAdmission.Rejected -> Refinement.Rejected(admission.failure)
    }

/** Passive control identity admission is independent of runtime-directory and process configuration. */
