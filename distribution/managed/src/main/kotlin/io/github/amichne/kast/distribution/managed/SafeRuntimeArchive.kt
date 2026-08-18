package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream

private const val RECEIPT_SEPARATOR = "  "

sealed interface RuntimeArchiveExtraction {
    data object Extracted : RuntimeArchiveExtraction
    data object Rejected : RuntimeArchiveExtraction
}

internal sealed interface RuntimeLayoutAdmission {
    data object Complete : RuntimeLayoutAdmission
    data object Rejected : RuntimeLayoutAdmission
}

internal sealed interface RuntimeReceiptAdmission {
    data object Matches : RuntimeReceiptAdmission
    data object Rejected : RuntimeReceiptAdmission
}

internal object SafeRuntimeArchive {
    /**
     * Proof transition: `verified ZIP + empty Path + SemanticRuntimeManifest ->
     * RuntimeArchiveExtraction`.
     *
     * Establishes unique, bounded, non-escaping entries limited to the admitted top-level layout.
     * [RuntimeArchiveExtraction.Rejected] is the closed expected failure. Entry streams and paths
     * are permitted only at this extraction boundary.
     */
    fun extract(
        archive: Path,
        target: Path,
        manifest: SemanticRuntimeManifest,
    ): RuntimeArchiveExtraction {
        val allowedRoots = manifest.layout.requiredEntries
            .map { it.value.removeSuffix("/").substringBefore('/') }
            .toSet()
        val seen = mutableSetOf<String>()
        var extractedBytes = 0L
        val maximumExtractedBytes =
            manifest.archive.size.bytes.coerceAtMost(Long.MAX_VALUE / 32) * 32
        return try {
            ZipInputStream(Files.newInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (
                        name.isBlank() || !seen.add(name) || name.startsWith('/') ||
                        name.substringBefore('/').removeSuffix("/") !in allowedRoots
                    ) return RuntimeArchiveExtraction.Rejected
                    val destination = target.resolve(name).normalize()
                    if (!destination.startsWith(target) || Files.isSymbolicLink(destination)) {
                        return RuntimeArchiveExtraction.Rejected
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(destination)
                    } else {
                        Files.createDirectories(destination.parent)
                        Files.newOutputStream(
                            destination,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                        ).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                extractedBytes += count
                                if (extractedBytes > maximumExtractedBytes) {
                                    return RuntimeArchiveExtraction.Rejected
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            manifest.layout.executableEntries.forEach { executableEntry ->
                val executable = target.resolve(executableEntry.value).normalize()
                if (!executable.startsWith(target) || !executable.toFile().setExecutable(true, false)) {
                    return RuntimeArchiveExtraction.Rejected
                }
            }
            if (containsSymbolicLink(target)) {
                RuntimeArchiveExtraction.Rejected
            } else {
                RuntimeArchiveExtraction.Extracted
            }
        } catch (_: IOException) {
            RuntimeArchiveExtraction.Rejected
        } catch (_: SecurityException) {
            RuntimeArchiveExtraction.Rejected
        }
    }
}

/**
 * Proof transition: `Path + SemanticRuntimeManifest -> RuntimeLayoutAdmission`.
 *
 * [RuntimeLayoutAdmission.Complete] establishes every required entry and executable state before
 * [InstalledSemanticRuntime] construction; [RuntimeLayoutAdmission.Rejected] is the closed
 * expected failure. Raw paths remain inside the filesystem adapter.
 */
internal fun admitRuntimeLayout(
    directory: Path,
    manifest: SemanticRuntimeManifest,
): RuntimeLayoutAdmission {
    if (containsSymbolicLink(directory)) return RuntimeLayoutAdmission.Rejected
    if (manifest.layout.requiredEntries.any { entry ->
            val path = directory.resolve(entry.value.removeSuffix("/")).normalize()
            !path.startsWith(directory) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        }
    ) return RuntimeLayoutAdmission.Rejected
    val executablesComplete = manifest.layout.executableEntries.all { entry ->
        val path = directory.resolve(entry.value).normalize()
        path.startsWith(directory) &&
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
        Files.isExecutable(path)
    }
    return if (executablesComplete) {
        RuntimeLayoutAdmission.Complete
    } else {
        RuntimeLayoutAdmission.Rejected
    }
}

internal fun writeReceipt(directory: Path) {
    val lines = contentFiles(directory)
        .map { file -> "${sha256(file)}$RECEIPT_SEPARATOR${directory.relativize(file)}" }
        .sorted()
    Files.write(
        directory.resolve(RECEIPT_NAME),
        lines,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
}

/**
 * Proof transition: `installed Path -> RuntimeReceiptAdmission`.
 *
 * [RuntimeReceiptAdmission.Matches] proves every installed regular file matches the immutable
 * receipt; [RuntimeReceiptAdmission.Rejected] closes missing, malformed, changed, and unreadable
 * state. Raw file content is permitted only at this store-admission boundary.
 */
internal fun admitRuntimeReceipt(directory: Path): RuntimeReceiptAdmission = try {
    val receipt = directory.resolve(RECEIPT_NAME)
    if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
        return RuntimeReceiptAdmission.Rejected
    }
    val expected = Files.readAllLines(receipt, StandardCharsets.UTF_8)
    val actual = contentFiles(directory)
        .map { file -> "${sha256(file)}$RECEIPT_SEPARATOR${directory.relativize(file)}" }
        .sorted()
    if (expected == actual) RuntimeReceiptAdmission.Matches else RuntimeReceiptAdmission.Rejected
} catch (_: IOException) {
    RuntimeReceiptAdmission.Rejected
} catch (_: SecurityException) {
    RuntimeReceiptAdmission.Rejected
}

private fun contentFiles(directory: Path): List<Path> = Files.walk(directory).use { paths ->
    paths.filter { path ->
        path != directory.resolve(RECEIPT_NAME) &&
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    }.toList()
}

private fun containsSymbolicLink(directory: Path): Boolean = try {
    Files.walk(directory).use { paths -> paths.anyMatch(Files::isSymbolicLink) }
} catch (_: IOException) {
    true
} catch (_: SecurityException) {
    true
}
