package io.github.amichne.kast.api

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

fun kastLogDirectory(workspaceRoot: Path, envLookup: (String) -> String? = System::getenv): Path {
    val normalizedRoot = workspaceRoot.toAbsolutePath().normalize().toString()
    val hash = FileHashing.sha256(normalizedRoot).take(12)
    return kastConfigHome(envLookup).resolve("logs").resolve(hash)
}

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
