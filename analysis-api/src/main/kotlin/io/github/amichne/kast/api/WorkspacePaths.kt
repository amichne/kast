package io.github.amichne.kast.api

import java.nio.file.Path
import kotlin.io.path.Path

fun defaultDescriptorDirectory(workspaceRoot: Path): Path = System.getenv("KAST_INSTANCE_DIR")
    ?.let(::Path)
    ?.toAbsolutePath()
    ?.normalize()
    ?: workspaceMetadataDirectory(workspaceRoot).resolve("instances")

fun defaultSocketPath(workspaceRoot: Path): Path {
    val workspaceSocketPath = workspaceMetadataDirectory(workspaceRoot).resolve("s")
    return if (workspaceSocketPath.toString().length <= MAX_UNIX_DOMAIN_SOCKET_PATH_LENGTH) {
        workspaceSocketPath
    } else {
        fallbackSocketPath(workspaceRoot)
    }
}

fun workspaceMetadataDirectory(workspaceRoot: Path): Path = workspaceRoot
    .toAbsolutePath()
    .normalize()
    .resolve(".kast")

private fun fallbackSocketPath(workspaceRoot: Path): Path {
    val socketIdentity = FileHashing.sha256(
        workspaceRoot.toAbsolutePath().normalize().toString(),
    ).take(24)
    return Path(System.getProperty("java.io.tmpdir"))
        .toAbsolutePath()
        .normalize()
        .resolve("kast-sockets")
        .resolve("$socketIdentity.sock")
}

private const val MAX_UNIX_DOMAIN_SOCKET_PATH_LENGTH = 100
