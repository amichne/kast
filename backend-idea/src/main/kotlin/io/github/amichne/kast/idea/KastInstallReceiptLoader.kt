package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal object KastInstallReceiptLoader {
    private const val schemaVersion = 3
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun defaultPath(
        userHome: Path = Path.of(System.getProperty("user.home")),
        kastHome: String? = System.getenv("KAST_HOME"),
    ): Path = (kastHome?.let(Path::of) ?: userHome.resolve(".local/share/kast"))
        .resolve("current/receipt.json")

    fun load(
        path: Path = defaultPath(),
        loadCliVersion: (Path) -> CliImplementationVersion? = ::loadConfiguredCliVersion,
    ): KastInstallReceiptLoadResult {
        if (!Files.isRegularFile(path)) {
            return rejected("Kast install receipt is missing at $path; rerun `kast setup --source <bundle>`.")
        }
        val receipt = runCatching {
            Json.parseToJsonElement(Files.readString(path)).jsonObject
        }.getOrElse { error ->
            return invalid(path, error.message)
        }
        if (
            receipt.string("tool") != "kast" ||
            receipt.int("schemaVersion") != schemaVersion ||
            receipt.string("releaseDigest")?.matches(digestPattern) != true ||
            receipt.string("manifestDigest")?.matches(digestPattern) != true
        ) {
            return invalid(path, "invalid schema-3 active install receipt")
        }
        val installRoot = receipt.objectValue("roots")?.string("install")
            ?.let { value -> runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull() }
            ?: return invalid(path, "roots.install is missing or invalid")
        val expectedReceipt = installRoot.resolve("current/receipt.json")
        if (path.toAbsolutePath().normalize() != expectedReceipt) {
            return invalid(path, "receipt is not the active KAST_HOME receipt")
        }
        val current = expectedReceipt.parent
        val canonicalCurrent = runCatching { current.toRealPath() }.getOrElse { error ->
            return invalid(path, error.message)
        }
        if (
            Files.isSymbolicLink(current) &&
            canonicalCurrent.fileName.toString() != receipt.string("releaseDigest")
        ) {
            return modified(path, "release activation")
        }
        val manifest = current.resolve("manifest.json")
        if (
            !Files.isRegularFile(manifest) ||
            sha256(manifest) != receipt.string("manifestDigest")
        ) {
            return modified(path, "bundle manifest")
        }
        val binary = receipt.objectValue("entrypoints")?.string("activeBinary")
            ?.let { value -> runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull() }
            ?: return invalid(path, "entrypoints.activeBinary is missing or invalid")
        if (!binary.startsWith(current) || !Files.isRegularFile(binary) || !Files.isExecutable(binary)) {
            return modified(path, "CLI")
        }
        val canonicalBinary = runCatching { binary.toRealPath() }.getOrElse { error ->
            return invalid(path, error.message)
        }
        if (!canonicalBinary.startsWith(canonicalCurrent)) {
            return modified(path, "CLI")
        }
        val version = loadCliVersion(canonicalBinary)
            ?: return rejected(
                "Kast CLI at $canonicalBinary did not report a valid implementation version; rerun `kast setup --source <bundle>`.",
            )
        return KastInstallReceiptLoadResult.Loaded(binary = canonicalBinary, version = version)
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun invalid(path: Path, detail: String?): KastInstallReceiptLoadResult.Rejected =
        rejected("Kast install receipt is invalid at $path: $detail")

    private fun modified(path: Path, component: String): KastInstallReceiptLoadResult.Rejected =
        rejected("Kast active release is modified at $path: $component does not match its receipt.")

    private fun rejected(message: String): KastInstallReceiptLoadResult.Rejected =
        KastInstallReceiptLoadResult.Rejected(message)

    private fun JsonObject.string(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { get(key)?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun JsonObject.objectValue(key: String): JsonObject? =
        runCatching { get(key)?.jsonObject }.getOrNull()
}
