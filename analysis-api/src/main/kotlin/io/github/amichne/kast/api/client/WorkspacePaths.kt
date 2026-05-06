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

fun defaultStandaloneRuntimeLibsDirectory(): Path? =
    defaultStandaloneRuntimeLibsDirectory(System::getenv)

fun defaultStandaloneRuntimeLibsDirectory(envLookup: (String) -> String?): Path? =
    defaultConfigStandaloneRuntimeLibsDir(KastConfig.defaults().paths.libDir.value)
        .toAbsolutePath()
        .normalize()

fun kastHome(): Path? = kastHome(System::getenv)

fun kastHome(envLookup: (String) -> String?): Path? =
    envLookup("KAST_HOME")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { Path(it).toAbsolutePath().normalize() }

fun defaultInstallRoot(): Path = defaultInstallRoot(System::getenv)

fun defaultInstallRoot(envLookup: (String) -> String?): Path =
    envLookup("KAST_INSTALL_ROOT")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { Path(it).toAbsolutePath().normalize() }
        ?: kastHome(envLookup)
            ?.resolve("install")
            ?.toAbsolutePath()
            ?.normalize()
        ?: Path(System.getProperty("user.home"))
            .resolve(".local")
            .resolve("share")
            .resolve("kast")
            .toAbsolutePath()
            .normalize()

fun defaultBinDirectory(): Path = defaultBinDirectory(System::getenv)

fun defaultBinDirectory(envLookup: (String) -> String?): Path =
    envLookup("KAST_BIN_DIR")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { Path(it).toAbsolutePath().normalize() }
        ?: kastHome(envLookup)
            ?.resolve("bin")
            ?.toAbsolutePath()
            ?.normalize()
        ?: Path(System.getProperty("user.home"))
            .resolve(".local")
            .resolve("bin")
            .toAbsolutePath()
            .normalize()

fun defaultStandaloneRuntimeLibsDirectory(): Path? =
    defaultStandaloneRuntimeLibsDirectory(System::getenv)

fun defaultStandaloneRuntimeLibsDirectory(envLookup: (String) -> String?): Path? =
    envLookup("KAST_STANDALONE_RUNTIME_LIBS")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { Path(it).toAbsolutePath().normalize() }
        ?: defaultInstallRoot(envLookup)
            .takeIf { kastHome(envLookup) != null || !envLookup("KAST_INSTALL_ROOT").isNullOrBlank() }
            ?.resolve("backends/current/runtime-libs")
            ?.toAbsolutePath()
            ?.normalize()

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
