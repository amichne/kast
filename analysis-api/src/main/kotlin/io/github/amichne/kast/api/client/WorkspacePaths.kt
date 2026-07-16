package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
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
): Path = envLookup("KAST_DATA_HOME")
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let { Path(it).toAbsolutePath().normalize() }
    ?: installRoot.resolve("state").toAbsolutePath().normalize()

fun defaultDescriptorDirectory(): Path =
    configPath(KastConfig.defaults().paths.descriptorDir.value)

fun defaultSocketPath(workspaceRoot: Path): Path =
    WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).defaultSocketFile

private fun configPath(value: String): Path = Path(value).toAbsolutePath().normalize()
