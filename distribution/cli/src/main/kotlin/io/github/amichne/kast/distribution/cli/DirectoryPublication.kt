package io.github.amichne.kast.distribution.cli

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal enum class DirectoryPublication {
    PUBLISHED,
    TARGET_EXISTS,
    UNSUPPORTED,
    IO_FAILURE,
}

/** Darwin's RENAME_EXCL prevents replacement even when another actor creates an empty destination. */
internal fun publishDirectoryNoReplace(staged: Path, destination: Path): DirectoryPublication {
    return renameDirectory(staged, destination, RENAME_EXCL)
}

internal fun exchangeDirectories(staged: Path, destination: Path): DirectoryPublication =
    renameDirectory(staged, destination, RENAME_SWAP)

private fun renameDirectory(staged: Path, destination: Path, flags: Int): DirectoryPublication {
    if (!System.getProperty("os.name").startsWith("Mac")) return DirectoryPublication.UNSUPPORTED
    val linker = Linker.nativeLinker()
    val function = linker.defaultLookup().find("renamex_np")
    if (function.isEmpty) return DirectoryPublication.UNSUPPORTED
    val rename = linker.downcallHandle(function.get(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    return Arena.ofConfined().use { arena ->
        val result =
            rename.invokeWithArguments(
                arena.allocateFrom(staged.toString()),
                arena.allocateFrom(destination.toString()),
                flags,
            )
        if (result == 0) DirectoryPublication.PUBLISHED
        else if (flags == RENAME_EXCL && Files.exists(destination, NOFOLLOW_LINKS)) DirectoryPublication.TARGET_EXISTS
        else DirectoryPublication.IO_FAILURE
    }
}

private const val RENAME_EXCL = 4
private const val RENAME_SWAP = 2
