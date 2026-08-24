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
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSource
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSourceSelection
import io.github.amichne.kast.distribution.managed.ManagedSemanticRuntimeProvider
import io.github.amichne.kast.distribution.managed.RuntimeStore
import io.github.amichne.kast.distribution.managed.RuntimeStoreAdmission
import io.github.amichne.kast.distribution.managed.RuntimeStoreFailure
import io.github.amichne.kast.distribution.managed.SemanticRuntimeResolution
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val RUNTIME_DIRECTORY_ENVIRONMENT = "KAST_RUNTIME_DIRECTORY"
private const val RUNTIME_ARCHIVE_ENVIRONMENT = "KAST_RUNTIME_ARCHIVE"
private const val RUNTIME_STORE_ENVIRONMENT = "KAST_RUNTIME_STORE"

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

    data class RuntimeDirectoryRejected(
        val failure: InstalledRuntimeDirectoryFailure,
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
     * command surface, runtime directory, and local metadata. [InstalledCompositionFailure] is
     * the closed expected failure. Filesystem and environment extraction remain in this installed
     * composition boundary.
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
        val runtimeDirectory = when (val admission = InstalledRuntimeDirectory.admit()) {
            is InstalledRuntimeDirectoryAdmission.Admitted -> admission.directory
            is InstalledRuntimeDirectoryAdmission.Rejected ->
                return KastCliCompositionConstruction.Rejected(
                    InstalledCompositionFailure.RuntimeDirectoryRejected(admission.failure),
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
                Sha256RuntimeEndpointLocator(runtimeDirectory.path, manifest.runtimeId),
                ManagedExactRootRuntimeDemander(
                    manifest,
                    InstalledSemanticRuntimeResolver(::resolveInstalledRuntime),
                ),
                UnixDomainWireClient(),
                localMetadata,
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

/** Performs source selection and store admission only after semantic demand. */
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
    val store = when (val admitted = RuntimeStore.admit(storePath.toAbsolutePath())) {
        is RuntimeStoreAdmission.Admitted -> admitted.store
        is RuntimeStoreAdmission.Rejected -> return SemanticRuntimeResolution.Rejected(
            admitted.failure,
        )
    }
    return ManagedSemanticRuntimeProvider(store).resolve(manifest, source)
}

private enum class InstalledRuntimeDirectoryFailure { INVALID_PATH }

private sealed interface InstalledRuntimeDirectoryAdmission {
    data class Admitted(val directory: InstalledRuntimeDirectory) : InstalledRuntimeDirectoryAdmission
    data class Rejected(
        val failure: InstalledRuntimeDirectoryFailure,
    ) : InstalledRuntimeDirectoryAdmission
}

/** An absolute normalized runtime path admitted before exact-root socket derivation. */
private class InstalledRuntimeDirectory private constructor(val path: Path) {
    companion object {
        /**
         * Proof transition: `KAST_RUNTIME_DIRECTORY | java.io.tmpdir ->
         * InstalledRuntimeDirectoryAdmission`.
         *
         * Establishes an absolute normalized path for exact-root socket derivation without
         * granting or performing a filesystem write. [InstalledRuntimeDirectoryFailure] closes a
         * malformed path. Raw text is extracted only here; the indexer owns physical admission.
         */
        fun admit(): InstalledRuntimeDirectoryAdmission {
            val configured = try {
                System.getenv(RUNTIME_DIRECTORY_ENVIRONMENT)
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                ?: Path.of(System.getProperty("java.io.tmpdir")).resolve("kast-runtime")
            } catch (_: InvalidPathException) {
                return InstalledRuntimeDirectoryAdmission.Rejected(
                    InstalledRuntimeDirectoryFailure.INVALID_PATH,
                )
            }
            return InstalledRuntimeDirectoryAdmission.Admitted(
                InstalledRuntimeDirectory(configured.toAbsolutePath().normalize()),
            )
        }
    }
}
