package support.plugin

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

@JvmInline
private value class RepositoryRelativePluginArchivePath private constructor(val value: String) {
    companion object {
        internal fun fromRepositoryPath(path: Path): RepositoryRelativePluginArchivePath =
            RepositoryRelativePluginArchivePath(path.joinToString("/"))
    }
}

internal class IdeHostedArchiveContent private constructor(private val immutableBytes: ByteArray) {
    companion object {
        /**
         * Proof transition: raw archive bytes `ByteArray -> IdeHostedArchiveContent`.
         *
         * Establishes an ownership-isolated byte snapshot. This transition has no expected failure.
         * Raw bytes are released only as a fresh scanner copy.
         */
        fun snapshot(bytes: ByteArray): IdeHostedArchiveContent =
            IdeHostedArchiveContent(bytes.copyOf())
    }

    internal fun copyForScanner(): ByteArray = immutableBytes.copyOf()
}

internal class RepositoryBoundPluginArchive private constructor(
    private val relativePath: RepositoryRelativePluginArchivePath,
    private val content: IdeHostedArchiveContent,
) {
    companion object {
        /**
         * Proof transition: filesystem paths `(Path, Path) -> RepositoryBoundPluginArchive`.
         *
         * Establishes an exact repository descendant reached without symlink components, a regular
         * non-symlink file, and an immutable byte snapshot. Expected rejection is
         * `ARCHIVE_UNAVAILABLE` or `ARCHIVE_IO_FAILURE`. Raw paths and I/O remain here.
         */
        fun read(repositoryRoot: Path, pluginArchive: Path): RepositoryPluginArchiveReadResult {
            return try {
                val root = repositoryRoot.toAbsolutePath().normalize()
                val archive = pluginArchive.toAbsolutePath().normalize()
                if (!Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root) ||
                    !archive.startsWith(root)
                ) {
                    return archiveUnavailable()
                }
                val relative = root.relativize(archive)
                var current = root
                for (segment in relative) {
                    current = current.resolve(segment)
                    if (Files.isSymbolicLink(current)) return archiveUnavailable()
                }
                val attributes = Files.readAttributes(
                    archive,
                    BasicFileAttributes::class.java,
                    NOFOLLOW_LINKS,
                )
                if (!attributes.isRegularFile) return archiveUnavailable()
                RepositoryPluginArchiveReadResult.Complete(
                    RepositoryBoundPluginArchive(
                        RepositoryRelativePluginArchivePath.fromRepositoryPath(relative),
                        IdeHostedArchiveContent.snapshot(Files.readAllBytes(archive)),
                    ),
                )
            } catch (_: NoSuchFileException) {
                archiveUnavailable()
            } catch (_: IOException) {
                archiveIoFailure()
            } catch (_: SecurityException) {
                archiveIoFailure()
            }
        }
    }

    internal fun relativePathForReport(): String = relativePath.value
    internal fun copyContentForScanner(): ByteArray = content.copyForScanner()
}

internal sealed interface RepositoryPluginArchiveReadResult {
    data class Complete(val archive: RepositoryBoundPluginArchive) :
        RepositoryPluginArchiveReadResult

    data class Rejected(val failure: IdePluginLayoutFailure) : RepositoryPluginArchiveReadResult
}

private fun archiveUnavailable() =
    RepositoryPluginArchiveReadResult.Rejected(IdePluginLayoutFailure.ARCHIVE_UNAVAILABLE)

private fun archiveIoFailure() =
    RepositoryPluginArchiveReadResult.Rejected(IdePluginLayoutFailure.ARCHIVE_IO_FAILURE)
