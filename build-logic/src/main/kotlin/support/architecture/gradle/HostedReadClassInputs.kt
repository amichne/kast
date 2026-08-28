package support.architecture.gradle

import support.architecture.HostedReadClassBytes
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path

internal sealed interface HostedReadClassInputFailure {
    data class UnreadableDirectory(val path: Path) : HostedReadClassInputFailure
    data class UnreadableClass(val path: Path) : HostedReadClassInputFailure
}

internal sealed interface HostedReadClassInputResult {
    data class Loaded(val classes: List<HostedReadClassBytes>) : HostedReadClassInputResult
    data class Rejected(val failure: HostedReadClassInputFailure) : HostedReadClassInputResult
}

/**
 * Proof transition: `Set<File> -> HostedReadClassInputResult`.
 *
 * Establishes one immutable byte observation for every recursively discovered class. That same
 * byte value is subsequently hashed and scanned. Unreadable directories or classes remain closed
 * [HostedReadClassInputFailure] data. Filesystem extraction is permitted only at Gradle receipt
 * and verification task boundaries.
 */
internal fun loadHostedReadClassInputs(roots: Set<File>): HostedReadClassInputResult {
    val classes = mutableListOf<HostedReadClassBytes>()
    roots.map(File::toPath).sortedBy(Path::toString).forEach { root ->
        val paths = try {
            if (!Files.isDirectory(root)) emptyList() else Files.walk(root).use { stream ->
                stream.filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(".class") }
                    .sorted()
                    .toList()
            }
        } catch (_: IOException) {
            return HostedReadClassInputResult.Rejected(
                HostedReadClassInputFailure.UnreadableDirectory(root),
            )
        } catch (_: UncheckedIOException) {
            return HostedReadClassInputResult.Rejected(
                HostedReadClassInputFailure.UnreadableDirectory(root),
            )
        } catch (_: SecurityException) {
            return HostedReadClassInputResult.Rejected(
                HostedReadClassInputFailure.UnreadableDirectory(root),
            )
        }
        paths.forEach { path ->
            val bytes = try {
                Files.readAllBytes(path)
            } catch (_: IOException) {
                return HostedReadClassInputResult.Rejected(
                    HostedReadClassInputFailure.UnreadableClass(path),
                )
            } catch (_: SecurityException) {
                return HostedReadClassInputResult.Rejected(
                    HostedReadClassInputFailure.UnreadableClass(path),
                )
            }
            classes += HostedReadClassBytes.capture(
                root.relativize(path).toString().replace(File.separatorChar, '/'),
                bytes,
            )
        }
    }
    return HostedReadClassInputResult.Loaded(classes)
}
