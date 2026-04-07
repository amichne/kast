package io.github.amichne.kast.api

import java.nio.file.Files
import java.nio.file.Path


// @Serializable  are going to unwrap the value classes by default, so we can just use the typed value here without needing to manually extract the underlying string.

/**
 * A file-system path that has been normalized to an absolute, canonical form.
 *
 * Construction is restricted to the companion factory methods, each of which
 * encodes a specific normalization strategy.  Once created, the wrapped
 * [value] is safe to use as a map key, sort comparator, or wire-format field
 * without further processing.
 */
@JvmInline
value class NormalizedPath private constructor(val value: String) : Comparable<NormalizedPath> {

    /** Converts back to a [java.nio.file.Path]. */
    fun toJavaPath(): Path = Path.of(value)

    override fun compareTo(other: NormalizedPath): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        /**
         * Full normalization with symlink resolution.
         *
         * Equivalent to the former `normalizeStandalonePath`: resolves the
         * path to an absolute form, then attempts `toRealPath()` to resolve
         * symlinks.  If the file does not exist, walks up to the nearest
         * existing ancestor, resolves that, and re-appends the relative tail.
         */
        fun of(path: Path): NormalizedPath {
            val absolutePath = path.toAbsolutePath().normalize()
            val resolved = runCatching { absolutePath.toRealPath().normalize() }
                .getOrElse { resolveMissingPath(absolutePath) }
            return NormalizedPath(resolved.toString())
        }

        /**
         * Light normalization — absolute + normalize only, no symlink resolution.
         *
         * Equivalent to the former `normalizeStandaloneModelPath`.
         * Use when the path may not exist on disk yet (e.g. model paths in
         * edits or create-file operations).
         */
        fun ofAbsolute(path: Path): NormalizedPath {
            return NormalizedPath(path.toAbsolutePath().normalize().toString())
        }

        /**
         * Parse a raw string into a [NormalizedPath], validating that the
         * path is absolute.
         *
         * Equivalent to the former `canonicalPath` in [EditPlanValidator].
         *
         * @throws ValidationException if the path is not absolute.
         */
        fun parse(raw: String): NormalizedPath {
            val path = Path.of(raw)
            if (!path.isAbsolute) {
                throw ValidationException(
                    message = "File paths must be absolute",
                    details = mapOf("filePath" to raw),
                )
            }
            return NormalizedPath(path.toAbsolutePath().normalize().toString())
        }

        /**
         * Wraps an already-normalized string without additional processing.
         *
         * **Only** use when the caller can guarantee the string was previously
         * produced by one of the other factory methods (e.g. round-tripping
         * through serialization).
         */
        fun ofNormalized(raw: String): NormalizedPath = NormalizedPath(raw)

        private fun resolveMissingPath(path: Path): Path {
            var existingAncestor: Path? = path.parent
            while (existingAncestor != null && !Files.exists(existingAncestor)) {
                existingAncestor = existingAncestor.parent
            }
            val normalizedAncestor = existingAncestor
                ?.let { ancestor ->
                    runCatching { ancestor.toRealPath().normalize() }.getOrDefault(ancestor)
                }
                ?: return path
            return normalizedAncestor.resolve(existingAncestor.relativize(path)).normalize()
        }
    }
}

/**
 * A Kotlin identifier (simple name, not fully qualified).
 */
@JvmInline
value class KotlinIdentifier(val value: String) {
    override fun toString(): String = value
}

/**
 * A Gradle/IDE module name.
 */
@JvmInline
value class ModuleName(val value: String) {
    override fun toString(): String = value
}

/**
 * A Kotlin package name (dot-separated).
 */
@JvmInline
value class PackageName(val value: String) {
    override fun toString(): String = value
}

/**
 * A fully-qualified Kotlin name (dot-separated).
 */
@JvmInline
value class FqName(val value: String) {
    override fun toString(): String = value
}

/**
 * A zero-based byte offset into a file's content.
 *
 * @throws IllegalArgumentException if [value] is negative.
 */
@JvmInline
value class ByteOffset(val value: Int) : Comparable<ByteOffset> {
    init {
        require(value >= 0) { "ByteOffset must be >= 0, was $value" }
    }

    override fun compareTo(other: ByteOffset): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()
}

/**
 * A 1-based line number.
 *
 * @throws IllegalArgumentException if [value] is less than 1.
 */
@JvmInline
value class LineNumber(val value: Int) {
    init {
        require(value >= 1) { "LineNumber must be >= 1, was $value" }
    }

    override fun toString(): String = value.toString()
}

/**
 * A 1-based column number.
 *
 * @throws IllegalArgumentException if [value] is less than 1.
 */
@JvmInline
value class ColumnNumber(val value: Int) {
    init {
        require(value >= 1) { "ColumnNumber must be >= 1, was $value" }
    }

    override fun toString(): String = value.toString()
}

/**
 * A hex-encoded SHA-256 hash string.
 */
@JvmInline
value class ShaHash(val value: String) {
    override fun toString(): String = value
}

/**
 * A cache or protocol schema version number.
 */
@JvmInline
value class CacheSchemaVersion(val value: Int) {
    override fun toString(): String = value.toString()
}
