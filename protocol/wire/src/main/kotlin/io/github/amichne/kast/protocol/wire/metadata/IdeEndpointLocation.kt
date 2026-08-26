package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

private const val MAX_SOCKET_DIRECTORY_BYTES = 64
private const val ROOT_DIGEST_BYTES = 12

enum class IdeEndpointSocketDirectoryFailure {
    BLANK,
    TOO_LONG,
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    CONTAINS_NUL,
}

@JvmInline
value class IdeEndpointSocketDirectory private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<IdeEndpointSocketDirectory,
         * IdeEndpointSocketDirectoryFailure>`.
         *
         * Establishes a bounded absolute normalized POSIX directory that leaves enough room for
         * the deterministic exact-root UDS name. [IdeEndpointSocketDirectoryFailure] is the
         * closed expected failure. Raw text may leave only at CLI configuration or hosted UDS
         * boundaries.
         */
        fun parse(
            raw: String,
        ): Refinement<IdeEndpointSocketDirectory, IdeEndpointSocketDirectoryFailure> = when {
            raw.isBlank() -> Refinement.Rejected(IdeEndpointSocketDirectoryFailure.BLANK)
            raw.toByteArray(StandardCharsets.UTF_8).size > MAX_SOCKET_DIRECTORY_BYTES ->
                Refinement.Rejected(IdeEndpointSocketDirectoryFailure.TOO_LONG)
            '\u0000' in raw ->
                Refinement.Rejected(IdeEndpointSocketDirectoryFailure.CONTAINS_NUL)
            !raw.startsWith('/') ->
                Refinement.Rejected(IdeEndpointSocketDirectoryFailure.NOT_ABSOLUTE)
            raw != "/" && (
                raw.endsWith('/') ||
                    raw.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." }
            ) -> Refinement.Rejected(IdeEndpointSocketDirectoryFailure.NOT_NORMALIZED)
            else -> Refinement.Refined(IdeEndpointSocketDirectory(raw))
        }
    }
}

@JvmInline
value class IdeEndpointDescriptorPath private constructor(val value: String) {
    companion object {
        internal fun from(socketPath: IdeUnixSocketPath): IdeEndpointDescriptorPath =
            IdeEndpointDescriptorPath("${socketPath.value}.endpoint.json")
    }
}

/** One deterministic UDS and descriptor pair for an exact root in an admitted state directory. */
class IdeEndpointLocation private constructor(
    val socketPath: IdeUnixSocketPath,
    val descriptorPath: IdeEndpointDescriptorPath,
) {
    companion object {
        /**
         * Proof transition: `IdeEndpointSocketDirectory + IdeEndpointCanonicalRoot ->
         * Refinement<IdeEndpointLocation, IdeEndpointPathFailure>`.
         *
         * Establishes the sole bounded UDS name and adjacent descriptor path for the exact root.
         * [IdeEndpointPathFailure] is the closed expected failure if the construction bound ever
         * stops implying a valid UDS path. Raw path text may leave only at the CLI descriptor-read
         * or hosted publication boundaries.
         */
        fun locate(
            directory: IdeEndpointSocketDirectory,
            root: IdeEndpointCanonicalRoot,
        ): Refinement<IdeEndpointLocation, IdeEndpointPathFailure> {
            val digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(root.value.toByteArray(StandardCharsets.UTF_8)),
                0,
                ROOT_DIGEST_BYTES,
            )
            val separator = if (directory.value == "/") "" else "/"
            val rawSocket = "${directory.value}${separator}kast-ide-$digest.sock"
            return when (val parsed = IdeUnixSocketPath.parse(rawSocket)) {
                is Refinement.Refined -> Refinement.Refined(
                    IdeEndpointLocation(parsed.value, IdeEndpointDescriptorPath.from(parsed.value)),
                )
                is Refinement.Rejected -> Refinement.Rejected(parsed.failure)
            }
        }
    }
}
