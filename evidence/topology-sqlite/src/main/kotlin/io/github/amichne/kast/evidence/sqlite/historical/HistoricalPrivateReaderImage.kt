package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalReadCount
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

/** Owns the private bytes that admission and every later reader operation consume. */
internal class HistoricalPrivateReaderImage
private constructor(
    val path: Path,
    val copiedBytes: HistoricalReadCount,
) : AutoCloseable {
    override fun close() {
        Files.deleteIfExists(path)
    }

    companion object {
        fun copy(
            source: Path,
            directory: Path,
        ): Refinement<HistoricalPrivateReaderImage, HistoricalStorageFailure> {
            val destination = Files.createTempFile(directory, ".kast-topology-read-", ".sqlite")
            var ownership = HistoricalImageOwnership.DELETE
            try {
                val result = copyBytes(source, destination).map { HistoricalPrivateReaderImage(destination, it) }
                if (result is Refinement.Refined) ownership = HistoricalImageOwnership.TRANSFER
                return result
            } finally {
                if (ownership == HistoricalImageOwnership.DELETE) Files.deleteIfExists(destination)
            }
        }

        private fun copyBytes(
            source: Path,
            destination: Path,
        ): Refinement<HistoricalReadCount, HistoricalStorageFailure> =
            Files.newInputStream(source, READ, NOFOLLOW_LINKS).use { input ->
                Files.newOutputStream(destination).use { output -> transfer(input, output) }
            }

        private fun transfer(
            input: InputStream,
            output: OutputStream,
        ): Refinement<HistoricalReadCount, HistoricalStorageFailure> {
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var copied = 0L
            while (true) {
                val allowance = minOf(buffer.size.toLong(), MAXIMUM_HISTORICAL_BYTES - copied + 1).toInt()
                val count = input.read(buffer, 0, allowance)
                if (count < 0) break
                if (copied + count > MAXIMUM_HISTORICAL_BYTES) {
                    return Refinement.Rejected(HistoricalStorageFailure.CAPACITY_EXCEEDED)
                }
                output.write(buffer, 0, count)
                copied += count
            }
            return HistoricalReadCount.parse(copied).storage()
        }
    }
}

private enum class HistoricalImageOwnership {
    DELETE,
    TRANSFER,
}

private const val COPY_BUFFER_BYTES = 64 * 1024
