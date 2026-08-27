package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.CliLocalMetadataFailure
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFailure
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandSurface
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifestAdmission
import io.github.amichne.kast.distribution.contract.SemanticRuntimeFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSocketDirectory
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSocketDirectoryFailure
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

private const val SUPPORTED_IDE_BUILD = "262.9437.185"
private const val SUPPORTED_KOTLIN_PLUGIN_BUILD = "262.9437.185-IJ"
private const val IDE_RUNTIME_PROTOCOL = "kast.ide-hosted.runtime.v1"
private val IDE_CAPABILITIES = listOf(
    "workspace.inspect",
    "symbol.discover",
    "symbol.resolve",
    "symbol.describe",
)

private sealed interface InstalledCompositionFailure : KastCliCompositionFailure {
    data class ControlProductRejected(
        val failure: InstalledKastControlProductFailure,
    ) : InstalledCompositionFailure

    data class ResourceUnavailable(
        val resource: InstalledControlResource,
    ) : InstalledCompositionFailure

    data class RuntimeManifestRejected(
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
     * Establishes one complete CLI graph with an admitted control product, runtime manifest,
     * command surface, exact IDE-host policy, endpoint directory, and local metadata.
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
        val commandGraphFactory = when (
            val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
        ) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.CommandGraphRejected(construction.failures),
                )
        }
        val endpointPolicy = when (val admission = installation.ideEndpointPolicy(manifest)) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.EndpointPolicyRejected(admission.failure),
                )
        }
        val socketDirectory = when (val admission = IdeEndpointSocketDirectory.parse(
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString(),
        )) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return KastCliCompositionConstruction.Rejected(
                InstalledCompositionFailure.EndpointSocketDirectoryRejected(admission.failure),
            )
        }
        val localMetadata = when (
            val construction = installation.localMetadata(manifest, commandGraphFactory.surface)
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
                    IdeEndpointAdmitter(socketDirectory, endpointPolicy),
                    manifest.runtimeId,
                ),
                UnixDomainWireClient(),
                localMetadata,
                IdeEndpointRuntimeLifecycle,
            ),
        )
    }
}

private enum class InstalledKastControlProductFailure {
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

private enum class InstalledControlResource(val fileName: String) {
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
     * Proof transition: `SemanticRuntimeManifest + CliCommandSurface ->
     * InstalledLocalMetadataConstruction`.
     *
     * Establishes readable schema resources and admitted local metadata while preserving any
     * [InstalledCompositionFailure]. Raw installed resource text remains inside this adapter.
     */
    fun localMetadata(
        manifest: SemanticRuntimeManifest,
        commandSurface: CliCommandSurface,
    ): InstalledLocalMetadataConstruction {
        val operationRegistry = when (val resource = readResource(InstalledControlResource.OPERATION_REGISTRY)) {
            is InstalledControlResourceRead.Read -> resource.value
            is InstalledControlResourceRead.Rejected ->
                return InstalledLocalMetadataConstruction.Rejected(
                    InstalledCompositionFailure.ResourceUnavailable(resource.resource),
                )
        }
        val wireSchema = when (val resource = readResource(InstalledControlResource.WIRE_SCHEMA)) {
            is InstalledControlResourceRead.Read -> resource.value
            is InstalledControlResourceRead.Rejected ->
                return InstalledLocalMetadataConstruction.Rejected(
                    InstalledCompositionFailure.ResourceUnavailable(resource.resource),
                )
        }
        val schema = when (val construction = installedSchema(
            operationRegistry,
            wireSchema,
            manifest.canonicalJson.value,
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
                manifest.productVersion.value,
                manifest.runtimeId.value,
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
     * Proof transition: `SemanticRuntimeManifest + installed protocol resources ->
     * Refinement<IdeHostCompatibilityPolicy, IdeHostCompatibilityFailure>`.
     *
     * Establishes the exact installed CLI/plugin compatibility tuple from the admitted product
     * version and physical installed protocol bytes. Every malformed candidate remains the closed
     * [IdeHostCompatibilityFailure]. Raw resource text and digest strings exist only here, at the
     * installed metadata boundary.
     */
    fun ideEndpointPolicy(
        manifest: SemanticRuntimeManifest,
    ): Refinement<IdeHostCompatibilityPolicy, IdeHostCompatibilityFailure> {
        val operationRegistry = when (val resource = readResource(
            InstalledControlResource.OPERATION_REGISTRY,
        )) {
            is InstalledControlResourceRead.Read -> resource.value
            is InstalledControlResourceRead.Rejected -> return Refinement.Rejected(
                IdeHostCompatibilityFailure.Malformed(
                    io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
                        .OPERATION_REGISTRY_DIGEST,
                    io.github.amichne.kast.protocol.contract.IdeHostCompatibilitySyntaxFailure
                        .BLANK,
                ),
            )
        }
        val wireSchema = when (val resource = readResource(InstalledControlResource.WIRE_SCHEMA)) {
            is InstalledControlResourceRead.Read -> resource.value
            is InstalledControlResourceRead.Rejected -> return Refinement.Rejected(
                IdeHostCompatibilityFailure.Malformed(
                    io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
                        .WIRE_SCHEMA_DIGEST,
                    io.github.amichne.kast.protocol.contract.IdeHostCompatibilitySyntaxFailure
                        .BLANK,
                ),
            )
        }
        return IdeHostCompatibilityPolicy.define(
            IdeHostCompatibilityCandidate(
                SUPPORTED_IDE_BUILD,
                SUPPORTED_KOTLIN_PLUGIN_BUILD,
                manifest.productVersion.value,
                IDE_RUNTIME_PROTOCOL,
                digestIdentity(operationRegistry),
                digestIdentity(wireSchema),
                IDE_CAPABILITIES,
            ),
        )
    }

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

private fun digestIdentity(raw: String): String = "sha256:" + HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8)),
)
