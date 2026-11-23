package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * One settled write state of a Gradle module cache.
 *
 * Gradle writes sequence zero before each index write and a larger sequence after its flush. A recreated cache has a
 * new creation number. Two equal generations therefore prove that no index write ran between the two reads. An idle
 * daemon keeps the exclusive lock, so lock availability does not answer this question.
 */
internal data class GradleCacheGeneration private constructor(private val creation: Long, private val sequence: Long) {
    companion object {
        private const val LOCK_NAME = "modules-2.lock"
        private const val SUPPORTED_VERSION: Byte = 3
        private const val STATE_BYTES = 1 + 2 * Long.SIZE_BYTES

        fun read(source: Path): Refinement<GradleCacheGeneration, DependencyCacheRejection> {
            val lock = source.resolve(LOCK_NAME)
            if (!Files.isRegularFile(lock, NOFOLLOW_LINKS) || lock.toRealPath() != lock)
                return Refinement.Rejected(DependencyCacheRejection.SOURCE_UNAVAILABLE)
            return parse(Files.newInputStream(lock, NOFOLLOW_LINKS).use { it.readNBytes(STATE_BYTES) })
        }

        fun parse(state: ByteArray): Refinement<GradleCacheGeneration, DependencyCacheRejection> {
            if (state.size != STATE_BYTES || state[0] != SUPPORTED_VERSION)
                return Refinement.Rejected(DependencyCacheRejection.UNSUPPORTED_WRITE_STATE)
            val fields = ByteBuffer.wrap(state, 1, STATE_BYTES - 1)
            val creation = fields.getLong()
            val sequence = fields.getLong()
            return when {
                sequence == 0L -> Refinement.Rejected(DependencyCacheRejection.WRITE_IN_PROGRESS)
                sequence < 0L -> Refinement.Rejected(DependencyCacheRejection.UNSUPPORTED_WRITE_STATE)
                else -> Refinement.Refined(GradleCacheGeneration(creation, sequence))
            }
        }
    }
}
