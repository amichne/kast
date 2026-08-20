package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliProjections
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifestAdmission
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSource
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSourceSelection
import io.github.amichne.kast.distribution.managed.ManagedSemanticRuntimeProvider
import io.github.amichne.kast.distribution.managed.RuntimeStore
import io.github.amichne.kast.distribution.managed.RuntimeStoreAdmission
import io.github.amichne.kast.distribution.managed.RuntimeStoreFailure
import io.github.amichne.kast.distribution.managed.SemanticRuntimeResolution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val RUNTIME_DIRECTORY_ENVIRONMENT = "KAST_RUNTIME_DIRECTORY"
private const val RUNTIME_ARCHIVE_ENVIRONMENT = "KAST_RUNTIME_ARCHIVE"
private const val RUNTIME_STORE_ENVIRONMENT = "KAST_RUNTIME_STORE"

/** The sole service-loaded composition for an installed Kotlin `kast` executable. */
class InstalledKastCliComposition : KastCliComposition {
    override fun create(): KastCli {
        val installation = InstalledKastControlProduct.discover()
        val manifest = installation.runtimeManifest()
        val projections = when (val construction = CliProjectionTable.create(canonicalCliProjections())) {
            is CliProjectionTableConstruction.Created -> construction.table
            is CliProjectionTableConstruction.Rejected -> error(
                "canonical CLI projection table is incomplete: ${construction.failures}",
            )
        }
        val runtimeDirectory = when (val admitted = InstalledRuntimeDirectory.admit()) {
            is InstalledRuntimeDirectoryAdmission.Admitted -> admitted.directory
            is InstalledRuntimeDirectoryAdmission.Rejected -> error(
                "installed runtime directory is unavailable: ${admitted.failure}",
            )
        }
        return KastCli(
            projections,
            FilesystemCanonicalRootDiscovery,
            Sha256RuntimeEndpointLocator(runtimeDirectory.path, manifest.runtimeId),
            ManagedExactRootRuntimeDemander(
                manifest,
                InstalledSemanticRuntimeResolver(::resolveInstalledRuntime),
            ),
            UnixDomainWireClient(),
            installation.localMetadata(manifest),
        )
    }
}

/** One control installation proven by the CLI jar and exact `share/kast` resources. */
private class InstalledKastControlProduct private constructor(
    private val root: Path,
) {
    fun runtimeManifest(): SemanticRuntimeManifest {
        val raw = readResource("semantic-runtime.json")
        return when (val admitted = SemanticRuntimeManifest.admit(raw)) {
            is SemanticRuntimeManifestAdmission.Admitted -> admitted.manifest
            is SemanticRuntimeManifestAdmission.Rejected -> error(
                "embedded semantic runtime manifest is invalid: ${admitted.failure.reason}",
            )
        }
    }

    fun localMetadata(manifest: SemanticRuntimeManifest): CliLocalMetadata {
        val schema = installedSchema(
            readResource("operation-registry.json"),
            readResource("wire-schema.json"),
            manifest.canonicalJson.value,
        )
        return when (
            val admitted = CliLocalMetadata.admit(
                manifest.productVersion.value,
                manifest.runtimeId.value,
                schema,
            )
        ) {
            is CliLocalMetadataAdmission.Admitted -> admitted.metadata
            is CliLocalMetadataAdmission.Rejected -> error(
                "installed CLI metadata is invalid: ${admitted.failure}",
            )
        }
    }

    private fun readResource(name: String): String = try {
        Files.readString(root.resolve("share/kast/$name"))
    } catch (failure: IOException) {
        throw IllegalStateException("installed control resource is unavailable: $name", failure)
    }

    companion object {
        /**
         * Proof transition: `InstalledKastCliComposition code source ->
         * InstalledKastControlProduct`.
         *
         * Establishes that the provider was loaded from the installation's `lib` directory and
         * owns one sibling `share/kast` resource directory. Unexpected layouts fail the closed
         * bootstrap boundary. Raw URI and paths remain inside this installed-control adapter.
         */
        fun discover(): InstalledKastControlProduct {
            val codeSource = try {
                Path.of(
                    InstalledKastCliComposition::class.java.protectionDomain.codeSource.location
                        .toURI(),
                ).toRealPath()
            } catch (failure: IOException) {
                throw IllegalStateException("installed CLI code source is unavailable", failure)
            } catch (failure: URISyntaxException) {
                throw IllegalStateException("installed CLI code source is invalid", failure)
            }
            val libraryDirectory = codeSource.parent
                                       ?.takeIf { it.fileName.toString() == "lib" }
                                   ?: error("installed CLI must be loaded from its lib directory")
            val root = libraryDirectory.parent
                       ?: error("installed CLI lib directory has no product root")
            check(Files.isDirectory(root.resolve("share/kast"))) {
                "installed CLI has no share/kast resources"
            }
            return InstalledKastControlProduct(root)
        }
    }
}

private fun installedSchema(
    operationRegistry: String,
    wireSchema: String,
    runtimeManifest: String,
): String {
    val json = Json { explicitNulls = false }
    fun objectResource(raw: String): JsonObject =
        json.parseToJsonElement(raw) as? JsonObject
        ?: error("control schema resource is not an object")

    val projection = buildJsonObject {
        put("localFlags", JsonArray(listOf("--help", "--version", "--schema").map(::JsonPrimitive)))
        put(
            "lifecycleCommands",
            JsonArray(CliLifecycleCommand.entries.map { JsonPrimitive(it.command) }),
        )
        put("commands", buildJsonArray {
            canonicalCliSyntaxes.forEach { syntax -> add(JsonPrimitive(syntax.usage)) }
        })
    }
    val schema = buildJsonObject {
        put("schemaVersion", 1)
        put("operationRegistry", objectResource(operationRegistry))
        put("wireSchema", objectResource(wireSchema))
        put("cliProjection", projection)
        put("semanticRuntime", objectResource(runtimeManifest))
    }
    return json.encodeToString(JsonObject.serializer(), schema)
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
