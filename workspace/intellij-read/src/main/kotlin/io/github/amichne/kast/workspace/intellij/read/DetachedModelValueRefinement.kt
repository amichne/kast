package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal data class BoundedIdentity(val value: String)
private data class BoundedText(val value: String)
private data class NormalizedAbsolutePath(val value: Path)
internal enum class TextFailure { INVALID, TOO_LONG }
private enum class PathFailure { INVALID, TOO_LONG }
private enum class IntellijClasspathProtocol {
    FILE,
    JAR,
    JRT,
}

/**
 * Exact-root proof carried from primitive Project text into detached model construction.
 */
internal class ExactObservedWorkspaceRoot private constructor(
    val canonicalRoot: CanonicalWorkspaceRoot,
) {
    companion object {
        /**
         * Proof transition: `(String?, CanonicalWorkspaceRoot) ->
         * Refinement<ExactObservedWorkspaceRoot, DetachedModelCaptureFailure>`.
         *
         * Establishes bounded exact equality between observed Project text and the already-admitted
         * canonical root. [DetachedModelCaptureFailure] closes unavailable, oversized, and
         * mismatched input. Raw root extraction is permitted only at `Project.basePath` in the live
         * IntelliJ adapter.
         */
        internal fun refineObservedRoot(
            raw: String?,
            expectedRoot: CanonicalWorkspaceRoot,
        ): Refinement<ExactObservedWorkspaceRoot, DetachedModelCaptureFailure> {
            val path = when (val value = parseNormalizedAbsolutePath(raw)) {
                is Refinement.Refined -> value.value.value
                is Refinement.Rejected -> return value.failure.pathRejection(
                    DetachedModelCaptureFailure.ROOT_UNAVAILABLE,
                )
            }
            return if (path == Path.of(expectedRoot.value)) {
                Refinement.Refined(ExactObservedWorkspaceRoot(expectedRoot))
            } else {
                Refinement.Rejected(DetachedModelCaptureFailure.ROOT_MISMATCH)
            }
        }
    }
}

/**
 * Proof transition: `String -> Refinement<BoundedIdentity, TextFailure>`. Establishes a bounded,
 * nonblank, trimmed, control-free identity. [TextFailure] closes invalid and oversized input. Raw
 * identity extraction is permitted only at the live IntelliJ, SDK, or Gradle adapter boundary.
 */
internal fun refineIdentity(raw: String): Refinement<BoundedIdentity, TextFailure> =
    when (val value = refineBoundedText(raw, DetachedModelLimits.MAX_IDENTITY_CHARS)) {
        is Refinement.Refined -> Refinement.Refined(BoundedIdentity(value.value.value))
        is Refinement.Rejected -> value
    }

/**
 * Proof transition: `String -> Refinement<DetachedClasspathEntryUrl,
 * DetachedModelCaptureFailure>`. Establishes a bounded, exact IntelliJ `protocol://raw-path`
 * class-root identity for the closed `file`, `jar`, and `jrt` protocols. Spaces and literal URL
 * punctuation remain part of the VFS path identity. The closed expected failure is
 * [DetachedModelCaptureFailure.INVALID_CLASSPATH_IDENTITY] or
 * [DetachedModelCaptureFailure.CLASSPATH_IDENTITY_TOO_LONG]. Raw URL extraction is permitted only
 * at the IntelliJ `VirtualFile.url` classpath observation boundary.
 */
internal fun refineClasspathUrl(
    raw: String,
): Refinement<DetachedClasspathEntryUrl, DetachedModelCaptureFailure> {
    if (
        raw.length > DetachedModelLimits.MAX_CLASSPATH_URL_CHARS ||
        raw.toByteArray(StandardCharsets.UTF_8).size > DetachedModelLimits.MAX_CLASSPATH_URL_CHARS
    ) {
        return Refinement.Rejected(DetachedModelCaptureFailure.CLASSPATH_IDENTITY_TOO_LONG)
    }
    if (raw.isEmpty() || raw.any(Char::isISOControl)) {
        return Refinement.Rejected(DetachedModelCaptureFailure.INVALID_CLASSPATH_IDENTITY)
    }
    val protocolSeparator = raw.indexOf("://")
    if (protocolSeparator <= 0) {
        return Refinement.Rejected(DetachedModelCaptureFailure.INVALID_CLASSPATH_IDENTITY)
    }
    val protocol = when (raw.substring(0, protocolSeparator)) {
        "file" -> IntellijClasspathProtocol.FILE
        "jar" -> IntellijClasspathProtocol.JAR
        "jrt" -> IntellijClasspathProtocol.JRT
        else -> return Refinement.Rejected(
            DetachedModelCaptureFailure.INVALID_CLASSPATH_IDENTITY,
        )
    }
    val path = raw.substring(protocolSeparator + 3)
    if (
        !path.startsWith('/') || path == "/" || path.contains("//") ||
        path.split('/').any { segment -> segment == "." || segment == ".." }
    ) {
        return Refinement.Rejected(DetachedModelCaptureFailure.INVALID_CLASSPATH_IDENTITY)
    }
    if (
        path.endsWith('/') &&
        !(protocol == IntellijClasspathProtocol.JAR && path.endsWith("!/"))
    ) {
        return Refinement.Rejected(DetachedModelCaptureFailure.INVALID_CLASSPATH_IDENTITY)
    }
    if (protocol != IntellijClasspathProtocol.FILE) {
        val separatorIndex = path.indexOf("!/")
        if (
            separatorIndex <= 1 || path.indexOf("!/", separatorIndex + 2) >= 0 ||
            (protocol == IntellijClasspathProtocol.JRT && separatorIndex + 2 == path.length)
        ) {
            return Refinement.Rejected(DetachedModelCaptureFailure.INVALID_CLASSPATH_IDENTITY)
        }
    }
    return Refinement.Refined(DetachedClasspathEntryUrl(raw))
}

/**
 * Proof transition: `(String?, Path) -> Refinement<DetachedWorkspaceRelativePath,
 * DetachedModelCaptureFailure>`. Establishes bounded normalized containment beneath the admitted
 * root. [DetachedModelCaptureFailure] closes invalid, oversized, and outside-root input. Raw path
 * extraction is permitted only at the live IntelliJ or cached Gradle adapter boundary.
 */
internal fun refineWorkspacePath(
    raw: String?,
    root: Path,
    invalidFailure: DetachedModelCaptureFailure,
    outsideFailure: DetachedModelCaptureFailure,
): Refinement<DetachedWorkspaceRelativePath, DetachedModelCaptureFailure> {
    val path = when (val value = parseNormalizedAbsolutePath(raw)) {
        is Refinement.Refined -> value.value.value
        is Refinement.Rejected -> return value.failure.pathRejection(invalidFailure)
    }
    if (!path.startsWith(root)) return Refinement.Rejected(outsideFailure)
    val relative = root.relativize(path).joinToString("/") { segment -> segment.toString() }
        .ifEmpty { "." }
    return Refinement.Refined(DetachedWorkspaceRelativePath(relative))
}

/**
 * Proof transition: `String? -> Refinement<NormalizedAbsolutePath, PathFailure>`. Establishes
 * bounded normalized absolute path syntax; [PathFailure] closes invalid and oversized input. Raw
 * path extraction is permitted only at the live IntelliJ or cached Gradle adapter boundary.
 */
private fun parseNormalizedAbsolutePath(
    raw: String?,
): Refinement<NormalizedAbsolutePath, PathFailure> {
    if (raw == null) {
        return Refinement.Rejected(PathFailure.INVALID)
    }
    if (
        raw.length > DetachedModelLimits.MAX_PATH_CHARS ||
        raw.toByteArray(StandardCharsets.UTF_8).size > DetachedModelLimits.MAX_PATH_CHARS
    ) {
        return Refinement.Rejected(PathFailure.TOO_LONG)
    }
    if (raw.isBlank() || raw != raw.trim() || raw.any(Char::isISOControl)) {
        return Refinement.Rejected(PathFailure.INVALID)
    }
    return try {
        val path = Path.of(raw)
        if (!path.isAbsolute || path.normalize() != path) {
            Refinement.Rejected(PathFailure.INVALID)
        } else {
            Refinement.Refined(NormalizedAbsolutePath(path))
        }
    } catch (_: InvalidPathException) {
        Refinement.Rejected(PathFailure.INVALID)
    }
}

/**
 * Proof transition: `(String, Int) -> Refinement<BoundedText, TextFailure>`. Establishes bounded
 * UTF-8 text before a semantic strong type is constructed. [TextFailure] closes invalid and
 * oversized input. Raw text extraction is permitted only at the live IntelliJ, SDK, or cached
 * Gradle adapter boundary.
 */
private fun refineBoundedText(
    raw: String,
    limit: Int,
): Refinement<BoundedText, TextFailure> = when {
    raw.length > limit || raw.toByteArray(StandardCharsets.UTF_8).size > limit ->
        Refinement.Rejected(TextFailure.TOO_LONG)
    raw.isBlank() || raw != raw.trim() || raw.any(Char::isISOControl) ->
        Refinement.Rejected(TextFailure.INVALID)
    else -> Refinement.Refined(BoundedText(raw))
}

private fun PathFailure.pathRejection(
    invalidFailure: DetachedModelCaptureFailure,
): Refinement.Rejected<DetachedModelCaptureFailure> = Refinement.Rejected(
    if (this == PathFailure.TOO_LONG) {
        DetachedModelCaptureFailure.PATH_IDENTITY_TOO_LONG
    } else {
        invalidFailure
    },
)
