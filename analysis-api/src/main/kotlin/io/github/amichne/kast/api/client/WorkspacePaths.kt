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

fun kastHome(): Path? = kastHome(System::getenv)

fun kastHome(envLookup: (String) -> String?): Path? = defaultInstallRoot(envLookup)

fun defaultInstallRoot(): Path = defaultInstallRoot(System::getenv)

fun defaultInstallRoot(envLookup: (String) -> String?): Path =
    configPath(KastConfig.defaults().paths.installRoot.value)

fun defaultBinDirectory(): Path = defaultBinDirectory(System::getenv)

fun defaultBinDirectory(envLookup: (String) -> String?): Path =
    configPath(KastConfig.defaults().paths.binDir.value)

fun defaultHeadlessRuntimeLibsDirectory(): Path? =
    defaultHeadlessRuntimeLibsDirectory(System::getenv)

fun defaultHeadlessRuntimeLibsDirectory(envLookup: (String) -> String?): Path? =
    defaultConfigHeadlessRuntimeLibsDir(KastConfig.defaults().paths.libDir.value)
        .toAbsolutePath()
        .normalize()

fun defaultDescriptorDirectory(): Path =
    configPath(KastConfig.defaults().paths.descriptorDir.value)

fun defaultDescriptorDirectory(envLookup: (String) -> String?): Path =
    configPath(KastConfig.defaults().paths.descriptorDir.value)

fun kastLogDirectory(workspaceRoot: Path): Path =
    configPath(KastConfig.defaults().paths.logsDir.value)

fun kastLogDirectory(
    workspaceRoot: Path,
    envLookup: (String) -> String?,
): Path =
    configPath(KastConfig.defaults().paths.logsDir.value)

fun defaultSocketPath(workspaceRoot: Path): Path =
    socketPathForWorkspace(workspaceRoot)

fun workspaceMetadataDirectory(workspaceRoot: Path): Path =
    workspaceDataDirectory(workspaceRoot)

fun workspaceMetadataDirectory(
    workspaceRoot: Path,
    envLookup: (String) -> String?,
): Path = workspaceDataDirectory(workspaceRoot, envLookup)

private fun configPath(value: String): Path = Path(value).toAbsolutePath().normalize()

private fun socketPathForWorkspace(workspaceRoot: Path): Path = Path(
    System.getProperty("java.io.tmpdir"),
    "kast-${WorkspaceDirectoryResolver().workspaceHash(workspaceRoot)}.sock",
).toAbsolutePath().normalize()
