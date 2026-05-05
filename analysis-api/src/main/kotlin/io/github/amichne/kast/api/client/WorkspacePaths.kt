package io.github.amichne.kast.api.client

import java.nio.file.Path
import kotlin.io.path.Path

fun kastConfigHome(): Path = kastConfigHomeInternal(System::getenv)

fun kastConfigHome(envLookup: (String) -> String?): Path =
    kastConfigHomeInternal(envLookup)

private fun kastConfigHomeInternal(envLookup: (String) -> String?): Path {
    envLookup("KAST_CONFIG_HOME")?.let {
        return Path(it).toAbsolutePath().normalize()
    }
    kastHome(envLookup)?.let {
        return it.resolve("config").toAbsolutePath().normalize()
    }
    envLookup("XDG_CONFIG_HOME")?.let {
        return Path(it).resolve("kast").toAbsolutePath().normalize()
    }
    return Path(System.getProperty("user.home"))
        .resolve(".config")
        .resolve("kast")
        .toAbsolutePath()
        .normalize()
}

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
    kastConfigHome().resolve("daemons")

fun defaultDescriptorDirectory(envLookup: (String) -> String?): Path =
    kastConfigHome(envLookup).resolve("daemons")

fun kastLogDirectory(workspaceRoot: Path): Path =
    workspaceDataDirectory(workspaceRoot).resolve("logs")

fun kastLogDirectory(workspaceRoot: Path, envLookup: (String) -> String?): Path =
    workspaceDataDirectory(workspaceRoot, envLookup).resolve("logs")

fun defaultSocketPath(workspaceRoot: Path): Path =
    socketPathForWorkspace(workspaceRoot)

fun workspaceMetadataDirectory(workspaceRoot: Path): Path =
    workspaceDataDirectory(workspaceRoot)

fun workspaceMetadataDirectory(
    workspaceRoot: Path,
    envLookup: (String) -> String?,
): Path = workspaceDataDirectory(workspaceRoot, envLookup)

private fun socketPathForWorkspace(workspaceRoot: Path): Path = Path(
    System.getProperty("java.io.tmpdir"),
    "kast-${WorkspaceDirectoryResolver().workspaceHash(workspaceRoot)}.sock",
).toAbsolutePath().normalize()
