package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The Python fixture owner binds downloaded archives and installer output before the harness starts. */
@Serializable
internal data class NativeReleasedProductWitness(
    val schemaVersion: Int,
    val ownedRoot: String,
    val product: String,
    val executable: String,
    val pluginsDirectory: String,
    val version: String,
    val sourceCommit: String,
    val installerSha256: String,
    val controlSha256: String,
    val hostedPluginSha256: String,
    val payloadIdentity: String,
    val installationManifestSha256: String,
)

@Serializable private data class ReleasedManifest(val payloadFiles: List<ReleasedFile>)

@Serializable private data class ReleasedFile(val path: String, val sha256: String)

/** Source mode has the exact root/product layout; released mode requires the owned manifest witness. */
internal object NativeProductAdmission {
    private val manifestJson = Json { ignoreUnknownKeys = true }

    fun executable(product: Path, workspace: Path): Path {
        val root = workspace.parent
        demand(
            root.parent != null && root.toRealPath() == root && workspace == root.resolve("workspace"),
            NativeFailure.INPUT_REJECTED,
        )
        val witness = root.resolve("released-product-admission.json")
        if (!Files.exists(witness, LinkOption.NOFOLLOW_LINKS)) {
            demand(product == root.resolve("product"), NativeFailure.PRODUCT_CLASS_ORIGIN_REJECTED)
            return product.resolve("bin/kast")
        }
        demand(
            witness.toRealPath() == witness && Files.isRegularFile(witness) && Files.size(witness) in 1..8192,
            NativeFailure.INPUT_REJECTED,
        )
        val admitted =
            try {
                Json.decodeFromString<NativeReleasedProductWitness>(Files.readString(witness))
            } catch (_: SerializationException) {
                throw NativeRejected(NativeFailure.INPUT_REJECTED)
            }
        requireProductRoot(admitted, root, product)
        return requireManifest(admitted, product)
    }

    private fun requireProductRoot(admitted: NativeReleasedProductWitness, root: Path, product: Path) {
        val digest = admitted.payloadIdentity.removePrefix("sha256:")
        demand(
            admitted.schemaVersion == 1 &&
                admitted.ownedRoot == root.toString() &&
                admitted.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) &&
                admitted.payloadIdentity == "sha256:$digest" &&
                digest.matches(Regex("[0-9a-f]{64}")) &&
                product == root.resolve("installation/versions/${admitted.version}-$digest") &&
                product.toRealPath() == product &&
                admitted.product == product.toString(),
            NativeFailure.PRODUCT_CLASS_ORIGIN_REJECTED,
        )
    }

    private fun requireManifest(admitted: NativeReleasedProductWitness, product: Path): Path {
        val manifest = product.resolve("installation.json")
        val executable = product.resolve("bin/kast-complete")
        demand(
            manifest.toRealPath() == manifest &&
                Files.size(manifest) in 1..ControlDistributionLimits.maximumManifestBytes.toLong() &&
                sha256(Files.readAllBytes(manifest)) == admitted.installationManifestSha256 &&
                admitted.executable == executable.toString() &&
                executable.toRealPath() == executable &&
                Files.isRegularFile(executable) &&
                Files.isExecutable(executable),
            NativeFailure.PRODUCT_CLASS_ORIGIN_REJECTED,
        )
        val inventory =
            try {
                manifestJson.decodeFromString<ReleasedManifest>(Files.readString(manifest))
            } catch (_: SerializationException) {
                throw NativeRejected(NativeFailure.INPUT_REJECTED)
            }
        val wrapper = inventory.payloadFiles.filter { it.path == "bin/kast-complete" }
        demand(
            wrapper.size == 1 && wrapper.single().sha256 == "sha256:${sha256(Files.readAllBytes(executable))}",
            NativeFailure.PRODUCT_CLASS_ORIGIN_REJECTED,
        )
        return executable
    }
}
