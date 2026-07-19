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

internal object MacosMachineManifestLoader {
    private const val schemaVersion = 1
    private const val manifestType = "KAST_MACHINE_MANIFEST"
    private val keys = setOf(
        "type",
        "cliSha256",
        "ideaPluginSha256",
        "skillSha256",
        "codexSha256",
        "schemaVersion",
    )
    private val canonicalKeyPatterns = keys.associateWith { key ->
        Regex("""(?:\{|,)\s*"${Regex.escape(key)}"\s*:""")
    }

    fun defaultPath(
        userHome: Path = Path.of(System.getProperty("user.home")),
    ): Path = userHome.resolve("Library/Application Support/Kast/machine/machine.json")

    fun load(
        path: Path = defaultPath(),
        loadCliVersion: (Path) -> CliImplementationVersion? = ::loadConfiguredCliVersion,
    ): MacosMachineManifestLoadResult {
        if (!Files.isRegularFile(path)) {
            return rejected(
                "Kast machine manifest is missing at $path; run the Kast installer or `./gradlew refreshDevelopmentMachine` while JetBrains IDEs are closed.",
            )
        }
        val raw = runCatching { Files.readString(path) }.getOrElse { error ->
            return invalid(path, error.message)
        }
        if (canonicalKeyPatterns.any { (_, pattern) -> pattern.findAll(raw).count() != 1 }) {
            return invalid(path, "manifest keys must use each canonical spelling exactly once")
        }
        val manifest = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse { error ->
            return invalid(path, error.message)
        }
        if (
            manifest.keys != keys ||
            manifest.int("schemaVersion") != schemaVersion ||
            manifest.string("type") != manifestType
        ) {
            return invalid(path, "invalid schema-1 machine manifest")
        }
        val root = path.toAbsolutePath().normalize().parent
            ?: return invalid(path, "manifest has no machine root")
        val canonicalRoot = runCatching { root.toRealPath() }.getOrElse { error ->
            return invalid(path, error.message)
        }
        val binary = validateComponent(
            root = root,
            canonicalRoot = canonicalRoot,
            relative = "bin/kast",
            expectedSha256 = manifest.string("cliSha256"),
            executable = true,
        ) ?: return modified(path, "CLI")
        if (
            validateComponent(
                root = root,
                canonicalRoot = canonicalRoot,
                relative = "idea/kast.zip",
                expectedSha256 = manifest.string("ideaPluginSha256"),
            ) == null
        ) {
            return modified(path, "IDEA plugin")
        }
        if (
            validateComponent(
                root = root,
                canonicalRoot = canonicalRoot,
                relative = "resources/kast-skill/SKILL.md",
                expectedSha256 = manifest.string("skillSha256"),
            ) == null
        ) {
            return modified(path, "Kast skill")
        }
        if (
            directorySha256(root.resolve("resources/codex-marketplace")) !=
            manifest.string("codexSha256")
        ) {
            return modified(path, "Codex resources")
        }
        val version = loadCliVersion(binary)
            ?: return rejected(
                "Kast machine CLI at $binary did not report a valid implementation version; rerun machine activation.",
            )
        return MacosMachineManifestLoadResult.Loaded(binary = binary, version = version)
    }

    private fun validateComponent(
        root: Path,
        canonicalRoot: Path,
        relative: String,
        expectedSha256: String?,
        executable: Boolean = false,
    ): Path? {
        if (expectedSha256?.matches(Regex("[0-9a-f]{64}")) != true) return null
        val component = root.resolve(relative)
        if (!Files.isRegularFile(component) || executable && !Files.isExecutable(component)) return null
        val canonical = runCatching { component.toRealPath() }.getOrNull() ?: return null
        if (!canonical.startsWith(canonicalRoot)) return null
        return canonical.takeIf { sha256(it) == expectedSha256 }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun directorySha256(root: Path): String? {
        if (!Files.isDirectory(root)) return null
        val files = Files.walk(root).use { entries ->
            entries
                .filter { path -> path != root }
                .map { path -> path to Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java) }
                .toList()
        }
        if (files.any { (path, attributes) -> attributes.isSymbolicLink || !attributes.isDirectory && !attributes.isRegularFile }) {
            return null
        }
        val identity = files
            .filter { (_, attributes) -> attributes.isRegularFile }
            .map { (path, _) -> root.relativize(path).toString() to sha256(path) }
            .sortedBy { (relative, _) -> relative }
            .joinToString(separator = "", transform = { (relative, digest) -> "$relative\n$digest\n" })
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun invalid(path: Path, detail: String?): MacosMachineManifestLoadResult.Rejected =
        rejected("Kast machine manifest is invalid at $path: $detail")

    private fun modified(path: Path, component: String): MacosMachineManifestLoadResult.Rejected =
        rejected("Kast machine bundle is modified at $path: $component does not match its manifest digest.")

    private fun rejected(message: String): MacosMachineManifestLoadResult.Rejected =
        MacosMachineManifestLoadResult.Rejected(message)

    private fun JsonObject.string(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { get(key)?.jsonPrimitive?.intOrNull }.getOrNull()
}
