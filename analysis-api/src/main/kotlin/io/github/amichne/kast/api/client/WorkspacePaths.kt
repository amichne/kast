package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import io.github.amichne.kast.api.protocol.AnalysisException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

fun kastConfigHome(): Path = kastConfigHome(System::getenv)

fun kastConfigHome(envLookup: (String) -> String?): Path =
    envLookup("KAST_CONFIG_HOME")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { Path(it).toAbsolutePath().normalize() }
    ?: Path(System.getProperty("user.home"))
        .resolve(".config")
        .resolve("kast")
        .toAbsolutePath()
        .normalize()

fun kastInstallRoot(): Path =
    configPath(KastConfig.defaults().paths.installRoot.value)

fun kastDataRoot(): Path = kastDataRoot(System::getenv, kastInstallRoot())

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
    configPath(KastConfig.defaults().paths.descriptorDir.value)

fun defaultSocketPath(workspaceRoot: Path): Path =
    WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).defaultSocketFile

private fun configPath(value: String): Path = Path(value).toAbsolutePath().normalize()

private fun activeCliDataRoot(installRoot: Path): Path? {
    val receiptPath = installRoot.resolve("current/receipt.json")
    if (Files.notExists(receiptPath)) return null
    return runCatching {
        require(Files.isRegularFile(receiptPath)) { "receipt is not a regular file" }
        val receipt = Json.parseToJsonElement(Files.readString(receiptPath)).jsonObject
        require(receipt.getValue("tool").jsonPrimitive.content == "kast") { "tool must be `kast`" }
        Path(receipt.getValue("roots").jsonObject.getValue("data").jsonPrimitive.content)
            .toAbsolutePath()
            .normalize()
    }.getOrElse { cause ->
        throw AnalysisException(
            statusCode = 500,
            errorCode = "INSTALL_MANIFEST_INVALID",
            message = "Invalid Kast install manifest at $receiptPath: ${cause.message}",
            details = mapOf("path" to receiptPath.toString()),
        ).also { it.initCause(cause) }
    }
}
