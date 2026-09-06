package io.github.amichne.kast.distribution.managed.network

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Creates or verifies the exact private directory shared by trust artifacts and bounded receipts. */
internal fun privateNetworkDirectory(path: Path) {
    val parent = path.parent ?: throw java.io.IOException("private parent unavailable")
    if (!path.isAbsolute || path.normalize() != path || !Files.isDirectory(parent) || parent.toRealPath() != parent || Files.isSymbolicLink(path)) {
        throw java.io.IOException("private directory rejected")
    }
    val permissions = PosixFilePermissions.fromString("rwx------")
    val posix = Files.getFileStore(parent).supportsFileAttributeView("posix")
    if (!Files.exists(path)) {
        Files.createDirectory(path, *if (posix) arrayOf(PosixFilePermissions.asFileAttribute(permissions)) else emptyArray())
    }
    if (!Files.isDirectory(path) || path.toRealPath() != path) throw java.io.IOException("private directory rejected")
    if (posix) Files.setPosixFilePermissions(path, permissions)
}
