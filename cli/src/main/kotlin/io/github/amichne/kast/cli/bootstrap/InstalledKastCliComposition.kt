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
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.KastPluginVersion
import io.github.amichne.kast.protocol.wire.metadata.CanonicalHostedCapabilities
import io.github.amichne.kast.protocol.wire.metadata.HostedCapabilityCandidate
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSocketDirectory
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSocketDirectoryFailure
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

internal const val SUPPORTED_IDE_BUILD = "262.9437.185"
internal const val SUPPORTED_KOTLIN_PLUGIN_BUILD = "262.9437.185-IJ"
internal const val IDE_RUNTIME_PROTOCOL = "kast.ide-hosted.runtime.v1"
internal val INSTALLED_HOSTED_ACCEPTANCE_EXPECTATIONS: List<HostedCapabilityCandidate> =
    CanonicalHostedCapabilities.candidates
internal val IDE_CAPABILITIES: List<String> = INSTALLED_HOSTED_ACCEPTANCE_EXPECTATIONS.map {
    it.operationId
}

/**
 * Proof transition: installed host constant ->
 * `Refinement<IdeEndpointSocketDirectory, IdeEndpointSocketDirectoryFailure>`.
 *
 * Establishes the same stable, bounded `/tmp` directory used by hosted endpoint publication.
 * [IdeEndpointSocketDirectoryFailure] remains the closed expected failure; raw path extraction is
 * permitted only when the installed composition constructs its endpoint admitter.
 */
internal fun installedIdeEndpointSocketDirectory(): Refinement<
    IdeEndpointSocketDirectory,
    IdeEndpointSocketDirectoryFailure,
> = IdeEndpointSocketDirectory.parse("/tmp")

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

    data class HostedRuntimeIdentityRejected(
        val failure: SemanticRuntimeFailure,
    ) : InstalledCompositionFailure

    data class CommandGraphRejected(
        val failures: Set<CliCommandGraphFailure>,
    ) : InstalledCompositionFailure

    data class EndpointPolicyRejected(
        val failure: IdeHostCompatibilityFailure,
    ) : InstalledCompositionFailure

    data class EndpointSocketDirectoryRejected(
        val failure: IdeEndpointSocketDirectoryFailure,
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
     * protocol resources, exact IDE-host policy, endpoint directory, and local metadata.
     * [InstalledCompositionFailure] is the closed expected failure. Filesystem and environment
     * extraction remain in this installed composition boundary.
     */
    override fun create(): KastCliCompositionConstruction {
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
        val commandGraphFactory = when (
            val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
        ) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.CommandGraphRejected(construction.failures),
                )
        }
        val endpointPolicy = when (
            val admission = installation.ideEndpointPolicy(productVersion.value, protocol)
        ) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.EndpointPolicyRejected(admission.failure),
                )
        }
        val socketDirectory = when (val admission = installedIdeEndpointSocketDirectory()) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return KastCliCompositionConstruction.Rejected(
                InstalledCompositionFailure.EndpointSocketDirectoryRejected(admission.failure),
            )
        }
        val hostedRuntimeId = when (
            val admission = installedHostedRuntimeId(productVersion.value, protocol)
        ) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return KastCliCompositionConstruction.Rejected(
                InstalledCompositionFailure.HostedRuntimeIdentityRejected(admission.failure),
            )
        }
        val endpointAdmitter = IdeEndpointAdmitter(socketDirectory, endpointPolicy)
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
        return KastCliCompositionConstruction.Created(
            KastCli(
                commandGraphFactory,
                FilesystemCanonicalRootDiscovery,
                IdeOnlyRuntimeDemander(
                    endpointAdmitter,
                    hostedRuntimeId,
                ),
                UnixDomainWireClient(),
                localMetadata,
                IdeEndpointRuntimeLifecycle,
                InstalledProductInspector(
                    endpointPolicy.supportedCompatibility,
                    FilesystemCanonicalRootDiscovery,
                    endpointAdmitter,
                ),
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
     * Proof transition: `KastPluginVersion + InstalledProtocolResources ->
     * Refinement<IdeHostCompatibilityPolicy, IdeHostCompatibilityFailure>`.
     *
     * Establishes the exact installed CLI/plugin compatibility tuple from the admitted product
     * version and physical installed protocol bytes. Every malformed candidate remains the closed
     * [IdeHostCompatibilityFailure]. Raw resource text and digest strings exist only here, at the
     * installed metadata boundary.
     */
    fun ideEndpointPolicy(
        productVersion: String,
        protocol: InstalledProtocolResources,
    ): Refinement<IdeHostCompatibilityPolicy, IdeHostCompatibilityFailure> =
        IdeHostCompatibilityPolicy.define(compatibilityCandidate(productVersion, protocol))

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
                InstalledProtocolDigest.derive(operationRegistry),
                InstalledProtocolDigest.derive(wireSchema),
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
