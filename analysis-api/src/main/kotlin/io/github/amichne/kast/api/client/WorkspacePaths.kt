package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
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
    if (!Files.isRegularFile(receiptPath)) return null
    return runCatching {
        val receipt = Json.parseToJsonElement(Files.readString(receiptPath)).jsonObject
        require(receipt.getValue("tool").jsonPrimitive.content == "kast")
        Path(receipt.getValue("roots").jsonObject.getValue("data").jsonPrimitive.content)
            .toAbsolutePath()
            .normalize()
    }.getOrNull()
}
