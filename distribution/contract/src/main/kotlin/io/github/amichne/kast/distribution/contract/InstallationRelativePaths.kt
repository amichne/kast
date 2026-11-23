package io.github.amichne.kast.distribution.contract

import java.nio.file.Path

/** One `/`-separated path beneath the owned root that holds `config` and `state`. */
class OwnedRelativePath private constructor(private val segments: List<String>) {
    /** The recorded form, for example `state/run/c.sock`. */
    val value: String
        get() = segments.joinToString("/")

    /** The same path beneath [root]. */
    internal fun under(root: Path): Path = segments.fold(root) { path, segment -> path.resolve(segment) }

    override fun equals(other: Any?): Boolean = other is OwnedRelativePath && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = value

    internal companion object {
        fun of(vararg segments: String): OwnedRelativePath = OwnedRelativePath(segments.toList())
    }
}

/** One `/`-separated installation-relative path, built from its segments. */
class InstallationRelativePath private constructor(private val segments: List<String>) {
    /** The manifest form, for example `share/kast/libexec/kast-service`. */
    val value: String
        get() = segments.joinToString(SEPARATOR)

    /** The same path beneath [root]. */
    fun under(root: Path): Path {
        var path = root
        for (segment in segments) path = path.resolve(segment)
        return path
    }

    /** The relative [Path] form, for suffix checks such as [Path.endsWith]. */
    fun asPath(): Path = Path.of(segments.first(), *segments.drop(1).toTypedArray())

    override fun equals(other: Any?): Boolean = other is InstallationRelativePath && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = value

    internal companion object {
        private const val SEPARATOR = "/"

        fun of(vararg segments: String): InstallationRelativePath = InstallationRelativePath(segments.toList())
    }
}
