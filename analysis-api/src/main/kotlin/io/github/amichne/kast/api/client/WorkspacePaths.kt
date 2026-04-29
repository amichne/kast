package io.github.amichne.kast.api.client

import java.nio.file.Path
import kotlin.io.path.Path

fun kastConfigHome(envLookup: (String) -> String? = System::getenv): Path {
    envLookup("KAST_CONFIG_HOME")?.let {
        return Path(it).toAbsolutePath().normalize()
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

fun defaultDescriptorDirectory(envLookup: (String) -> String? = System::getenv): Path =
    kastConfigHome(envLookup).resolve("daemons")

fun kastLogDirectory(workspaceRoot: Path, envLookup: (String) -> String? = System::getenv): Path =
    workspaceDataDirectory(workspaceRoot, envLookup).resolve("logs")

fun defaultSocketPath(workspaceRoot: Path): Path =
    socketPathForWorkspace(workspaceRoot)

fun workspaceMetadataDirectory(
    workspaceRoot: Path,
    envLookup: (String) -> String? = System::getenv,
): Path = workspaceDataDirectory(workspaceRoot, envLookup)

private fun socketPathForWorkspace(workspaceRoot: Path): Path = Path(
    System.getProperty("java.io.tmpdir"),
    "kast-${WorkspaceDirectoryResolver().workspaceHash(workspaceRoot)}.sock",
).toAbsolutePath().normalize()
