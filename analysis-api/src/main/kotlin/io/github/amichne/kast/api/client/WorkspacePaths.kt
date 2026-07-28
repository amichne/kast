package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

internal data class KastPathDefaults(
    val installRoot: Path,
    val binDir: Path,
    val libDir: Path,
    val configRoot: Path,
    val dataRoot: Path,
    val cacheDir: Path,
    val logsDir: Path,
    val runtimeDir: Path,
    val descriptorDir: Path,
    val socketDir: Path,
    val cliBinary: Path,
    val headlessRuntimeLibsDir: Path,
)

fun kastConfigHome(): Path = kastConfigHome(System::getenv, defaultUserHome())

fun kastConfigHome(
    envLookup: (String) -> String?,
    userHome: Path = defaultUserHome(),
): Path =
    envLookup("KAST_CONFIG_HOME")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { Path(it).toAbsolutePath().normalize() }
        ?: resolveKastPathDefaults(envLookup, userHome).configRoot

fun kastInstallRoot(): Path =
    resolveKastPathDefaults().installRoot

fun kastDataRoot(): Path = resolveKastPathDefaults().dataRoot

fun kastDataRoot(
    envLookup: (String) -> String?,
    installRoot: Path,
): Path {
    val cliInstallRoot = envLookup("KAST_HOME")
        ?.takeIf(String::isNotEmpty)
        ?.let { Path(it).toAbsolutePath().normalize() }
        ?: installRoot.toAbsolutePath().normalize()
    return activeCliDataRoot(cliInstallRoot)
        ?: cliInstallRoot.resolve("state/data").toAbsolutePath().normalize()
}

fun defaultDescriptorDirectory(): Path =
    resolveKastPathDefaults().descriptorDir

fun defaultSocketPath(workspaceRoot: Path): Path =
    socketPathForWorkspaceRoot(workspaceRoot, resolveKastPathDefaults().socketDir)

fun socketPathForWorkspaceRoot(
    workspaceRoot: Path,
    socketDirectory: Path,
): Path {
    val workspaceHash = FileHashing.sha256(
        workspaceRoot.toAbsolutePath().normalize().toString(),
    ).take(12)
    val configured = socketDirectory
        .resolve("kast-$workspaceHash.sock")
        .toAbsolutePath()
        .normalize()
    return if (configured.toString().toByteArray(StandardCharsets.UTF_8).size > maxUnixSocketPathBytes) {
        Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("kast-$workspaceHash.sock")
            .toAbsolutePath()
            .normalize()
    } else {
        configured
    }
}

internal fun resolveKastPathDefaults(
    envLookup: (String) -> String? = System::getenv,
    userHome: Path = defaultUserHome(),
): KastPathDefaults {
    val requestedInstallRoot = envLookup("KAST_HOME")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(Path::of)
        ?: userHome.resolve(".local/share/kast")
    val normalizedInstallRoot = requestedInstallRoot.toAbsolutePath().normalize()
    return activeCliPathDefaults(normalizedInstallRoot) ?: fallbackPathDefaults(normalizedInstallRoot)
}

private fun fallbackPathDefaults(installRoot: Path): KastPathDefaults {
    val current = installRoot.resolve("current")
    val binDir = current.resolve("bin")
    val libDir = current.resolve("lib")
    val runtimeDir = installRoot.resolve("state/runtime")
    return KastPathDefaults(
        installRoot = installRoot,
        binDir = binDir,
        libDir = libDir,
        configRoot = current.resolve("config"),
        dataRoot = installRoot.resolve("state/data"),
        cacheDir = installRoot.resolve("state/cache"),
        logsDir = installRoot.resolve("state/logs"),
        runtimeDir = runtimeDir,
        descriptorDir = runtimeDir.resolve("daemons"),
        socketDir = runtimeDir,
        cliBinary = binDir.resolve("kast"),
        headlessRuntimeLibsDir = libDir.resolve("backends/headless/current/runtime-libs"),
    )
}

private fun activeCliPathDefaults(installRoot: Path): KastPathDefaults? {
    val receiptPath = installRoot.resolve("current/receipt.json")
    if (Files.notExists(receiptPath)) return null
    return parseActiveCliReceipt(receiptPath)
}

private fun activeCliDataRoot(installRoot: Path): Path? {
    val receiptPath = installRoot.resolve("current/receipt.json")
    if (Files.notExists(receiptPath)) return null
    return parseActiveCliReceipt(receiptPath).dataRoot
}

private fun parseActiveCliReceipt(receiptPath: Path): KastPathDefaults {
    return runCatching {
        require(Files.isRegularFile(receiptPath)) { "receipt is not a regular file" }
        val receipt = Json.parseToJsonElement(Files.readString(receiptPath)).jsonObject
        require(receipt.getValue("tool").jsonPrimitive.content == "kast") { "tool must be `kast`" }
        val roots = receipt.getValue("roots").jsonObject
        val installRoot = roots.requiredPath("install")
        val binDir = roots.requiredPath("bin")
        val configRoot = roots.requiredPath("config")
        val dataRoot = roots.requiredPath("data")
        val cacheDir = roots.requiredPath("cache")
        val logsDir = roots.requiredPath("logs")
        val runtimeDir = roots.requiredPath("runtime")
        val cliBinary = receipt.getValue("entrypoints").jsonObject.requiredPath("shim")
        val libDir = installRoot.resolve("current/lib")
        val headlessRuntimeLibsDir = receipt["backends"]
            ?.jsonArray
            ?.asSequence()
            ?.map { backend -> backend.jsonObject }
            ?.firstOrNull { backend -> backend["name"]?.jsonPrimitive?.contentOrNull == "headless" }
            ?.get("runtimeLibsDir")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?: libDir.resolve("backends/headless/current/runtime-libs")
        KastPathDefaults(
            installRoot = installRoot,
            binDir = binDir,
            libDir = libDir,
            configRoot = configRoot,
            dataRoot = dataRoot,
            cacheDir = cacheDir,
            logsDir = logsDir,
            runtimeDir = runtimeDir,
            descriptorDir = runtimeDir.resolve("daemons"),
            socketDir = runtimeDir,
            cliBinary = cliBinary,
            headlessRuntimeLibsDir = headlessRuntimeLibsDir,
        )
    }.getOrElse { cause ->
        throw AnalysisException(
            statusCode = 500,
            errorCode = "INSTALL_MANIFEST_INVALID",
            message = "Invalid Kast install manifest at $receiptPath: ${cause.message}",
            details = mapOf("path" to receiptPath.toString()),
        ).also { it.initCause(cause) }
    }
}

private fun kotlinx.serialization.json.JsonObject.requiredPath(key: String): Path =
    Path(getValue(key).jsonPrimitive.content).toAbsolutePath().normalize()

private fun defaultUserHome(): Path =
    Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()

private const val maxUnixSocketPathBytes: Int = 100
