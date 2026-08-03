package io.github.amichne.kast.api.contract.transformation.admission.repository

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun parseResourceBounds(input: RawResourceBoundsInput): EstablishedResourceBounds {
    val rawBounds = listOf(
        ResourceBoundKind.TIME to input.timeLimitMillis,
        ResourceBoundKind.MEMORY to input.memoryLimitBytes,
        ResourceBoundKind.DEPTH to input.traversalDepthLimit?.toLong(),
        ResourceBoundKind.PATHS to input.pathLimit?.toLong(),
        ResourceBoundKind.RESULTS to input.resultLimit?.toLong(),
    )
    val missing = rawBounds.firstOrNull { (_, value) -> value == null }
    if (missing != null) {
        reject(RepositoryOperationRejection.ResourceBoundMissing(missing.first))
    }
    val invalid = rawBounds.firstOrNull { (_, value) -> requireNotNull(value) <= 0L }
    if (invalid != null) {
        reject(
            RepositoryOperationRejection.ResourceBoundInvalid(
                bound = invalid.first,
                rawValue = requireNotNull(invalid.second),
            ),
        )
    }
    return EstablishedResourceBounds.create(
        timeLimitMillis = AnalysisTimeLimitMillis.fromValidated(requireNotNull(input.timeLimitMillis)),
        memoryLimitBytes = AnalysisMemoryLimitBytes.fromValidated(requireNotNull(input.memoryLimitBytes)),
        traversalDepthLimit = TraversalDepthLimit.fromValidated(requireNotNull(input.traversalDepthLimit)),
        pathLimit = AnalysisPathLimit.fromValidated(requireNotNull(input.pathLimit)),
        resultLimit = AnalysisResultLimit.fromValidated(requireNotNull(input.resultLimit)),
    )
}

internal fun requiredGitInventoryPaths(
    workingDirectory: Path,
    budget: SourceInventoryBudget,
    deadlineNanos: Long,
    nanoTime: () -> Long,
    vararg arguments: String,
): List<String> = when (
    val result = gitInventoryPaths(
        workingDirectory,
        budget,
        deadlineNanos,
        nanoTime,
        *arguments,
    )
) {
    is GitInventoryResult.Success -> result.paths
    is GitInventoryResult.BoundExceeded -> reject(
        RepositoryOperationRejection.ResourceBoundExceeded(result.bound),
    )
    GitInventoryResult.Unavailable -> reject(
        RepositoryOperationRejection.SourceStateEvidenceMissing(
            SourceStateEvidenceKind.INVENTORY,
            null,
        ),
    )
}

private fun gitInventoryPaths(
    workingDirectory: Path,
    budget: SourceInventoryBudget,
    deadlineNanos: Long,
    nanoTime: () -> Long,
    vararg arguments: String,
): GitInventoryResult {
    val output = runCatching {
        Files.createTempFile("kast-repository-admission-", ".git-output")
    }.getOrNull() ?: return GitInventoryResult.Unavailable
    var process: Process? = null
    return try {
        process = runCatching {
            gitProcessBuilder(workingDirectory, *arguments)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(output.toFile())
                .start()
        }.getOrNull() ?: return GitInventoryResult.Unavailable
        val timeoutMillis = remainingMillis(deadlineNanos, nanoTime)?.coerceAtMost(GIT_TIMEOUT_MILLIS)
            ?: return GitInventoryResult.BoundExceeded(ResourceBoundKind.TIME)
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return if (remainingMillis(deadlineNanos, nanoTime) == null) {
                GitInventoryResult.BoundExceeded(ResourceBoundKind.TIME)
            } else {
                GitInventoryResult.Unavailable
            }
        }
        if (process.exitValue() != 0) {
            return GitInventoryResult.Unavailable
        }
        if (remainingMillis(deadlineNanos, nanoTime) == null) {
            return GitInventoryResult.BoundExceeded(ResourceBoundKind.TIME)
        }
        val outputSize = Files.size(output)
        if (outputSize > budget.remainingMemoryBytes) {
            return GitInventoryResult.BoundExceeded(ResourceBoundKind.MEMORY)
        }
        val paths = mutableListOf<String>()
        if (outputSize > 0) {
            val record = ByteArrayOutputStream(
                minOf(outputSize, MAXIMUM_GIT_PATH_BYTES).toInt(),
            )
            Files.newInputStream(output).buffered().use { stream ->
                while (true) {
                    if (remainingMillis(deadlineNanos, nanoTime) == null) {
                        return GitInventoryResult.BoundExceeded(ResourceBoundKind.TIME)
                    }
                    when (val next = stream.read()) {
                        -1 -> {
                            if (record.size() != 0) return GitInventoryResult.Unavailable
                            break
                        }

                        0 -> {
                            if (record.size() == 0) return GitInventoryResult.Unavailable
                            if (paths.size >= budget.remainingPathCount) {
                                return GitInventoryResult.BoundExceeded(ResourceBoundKind.PATHS)
                            }
                            paths += record.toString(StandardCharsets.UTF_8)
                            record.reset()
                        }

                        else -> {
                            if (record.size().toLong() >= MAXIMUM_GIT_PATH_BYTES) {
                                return GitInventoryResult.Unavailable
                            }
                            record.write(next)
                        }
                    }
                }
            }
        }
        when (val exceeded = budget.consume(outputSize, paths.size)) {
            null -> GitInventoryResult.Success(paths.toList())
            else -> GitInventoryResult.BoundExceeded(exceeded)
        }
    } catch (_: InterruptedException) {
        process?.destroyForcibly()
        Thread.currentThread().interrupt()
        GitInventoryResult.Unavailable
    } catch (_: Exception) {
        process?.destroyForcibly()
        GitInventoryResult.Unavailable
    } finally {
        runCatching { Files.deleteIfExists(output) }
    }
}

internal fun sha256(
    root: Path,
    relativePath: RepositoryRelativePath,
    memoryLimitBytes: Long,
    deadlineNanos: Long,
    nanoTime: () -> Long,
): ContentDigestResult {
    if (remainingMillis(deadlineNanos, nanoTime) == null) return ContentDigestResult.TimeExceeded
    return try {
        val path = root.resolve(relativePath.value)
        val before = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!before.isRegularFile) return ContentDigestResult.Unavailable
        val canonicalBefore = path.toRealPath().normalize()
        if (!canonicalBefore.startsWith(root)) return ContentDigestResult.Unavailable
        val digestValue = FileChannel.open(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(minOf(memoryLimitBytes, HASH_BUFFER_BYTES).toInt())
            while (true) {
                if (remainingMillis(deadlineNanos, nanoTime) == null) {
                    return ContentDigestResult.TimeExceeded
                }
                val read = channel.read(buffer)
                if (read < 0) break
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        if (remainingMillis(deadlineNanos, nanoTime) == null) return ContentDigestResult.TimeExceeded
        val after = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        val canonicalAfter = path.toRealPath().normalize()
        if (
            !after.isRegularFile ||
            canonicalAfter != canonicalBefore ||
            after.fileKey() != before.fileKey() ||
            after.size() != before.size() ||
            after.lastModifiedTime() != before.lastModifiedTime()
        ) {
            return ContentDigestResult.Unavailable
        }
        if (remainingMillis(deadlineNanos, nanoTime) == null) {
            ContentDigestResult.TimeExceeded
        } else {
            ContentDigestResult.Success(digestValue)
        }
    } catch (_: Exception) {
        ContentDigestResult.Unavailable
    }
}

internal fun remainingMillis(
    deadlineNanos: Long,
    nanoTime: () -> Long,
): Long? {
    val remainingNanos = deadlineNanos - nanoTime()
    if (remainingNanos <= 0) return null
    return ((remainingNanos - 1) / NANOS_PER_MILLISECOND) + 1
}

internal fun deadlineAfter(
    timeLimitMillis: Long,
    nanoTime: () -> Long,
): Long {
    val durationNanos = timeLimitMillis
        .coerceAtMost(Long.MAX_VALUE / NANOS_PER_MILLISECOND) * NANOS_PER_MILLISECOND
    val now = nanoTime()
    return if (now > Long.MAX_VALUE - durationNanos) Long.MAX_VALUE else now + durationNanos
}

internal class SourceInventoryBudget(
    memoryLimitBytes: Long,
    pathLimit: Int,
) {
    var remainingMemoryBytes: Long = memoryLimitBytes
        private set

    var remainingPathCount: Int = pathLimit
        private set

    fun consume(byteCount: Long, pathCount: Int): ResourceBoundKind? = when {
        byteCount > remainingMemoryBytes -> ResourceBoundKind.MEMORY
        pathCount > remainingPathCount -> ResourceBoundKind.PATHS
        else -> {
            remainingMemoryBytes -= byteCount
            remainingPathCount -= pathCount
            null
        }
    }
}

internal sealed interface ContentDigestResult {
    data class Success(val value: String) : ContentDigestResult

    data object TimeExceeded : ContentDigestResult

    data object Unavailable : ContentDigestResult
}

private sealed interface GitInventoryResult {
    data class Success(val paths: List<String>) : GitInventoryResult

    data class BoundExceeded(val bound: ResourceBoundKind) : GitInventoryResult

    data object Unavailable : GitInventoryResult
}

internal val EXACT_REVISION: Regex = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
internal val SHA_256: Regex = Regex("[0-9a-fA-F]{64}")
internal const val GIT_INDEX_RECORD_PREFIX_LENGTH: Int = 2
internal const val GIT_SKIP_WORKTREE_TAG: Char = 'S'

private const val GIT_TIMEOUT_MILLIS: Long = 5_000
private const val MAXIMUM_GIT_PATH_BYTES: Long = 4_096
private const val HASH_BUFFER_BYTES: Long = 8_192
private const val NANOS_PER_MILLISECOND: Long = 1_000_000
