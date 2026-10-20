package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LibraryArtifactDigest
import io.github.amichne.kast.relation.contract.LibraryArtifactVersion
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.Manifest

internal enum class NativeValueArtifactFailure {
    MISSING,
    NOT_LIBRARY,
    UNREVIEWED_CALLABLE,
    AMBIGUOUS_ROOT,
    UNAVAILABLE,
    CHANGED,
    VERSION_UNAVAILABLE,
    METADATA_INVALID,
    BINDING_REJECTED,
}

internal sealed interface NativeValueModelFailure {
    data class Artifact(val cause: NativeValueArtifactFailure) : NativeValueModelFailure

    data class Limited(val cause: ValueFlowUnsupportedCause) : NativeValueModelFailure
}

internal data class NativeValueArchiveIdentity(
    val fileKey: String,
    val size: Long,
    val modified: java.nio.file.attribute.FileTime,
    val created: java.nio.file.attribute.FileTime,
)

internal sealed interface NativeValueManifest {
    data object Missing : NativeValueManifest

    data class Present(val stream: InputStream) : NativeValueManifest
}

/** The native file adapter owns effects; the reader owns their order and finite outcomes. */
internal interface NativeValueArtifactFile {
    fun identity(): Refinement<NativeValueArchiveIdentity, NativeValueArtifactFailure>

    fun openArchive(): InputStream

    fun openManifest(): NativeValueManifest
}

internal data class NativeValueArtifactContent(val digest: LibraryArtifactDigest, val version: LibraryArtifactVersion)

/** One artifact read shares the caller's work, cancellation, and original elapsed allowance. */
internal class NativeValueArtifactRead(
    private val file: NativeValueArtifactFile,
    private val reviewed: ReviewedLibraryArtifact,
    private val observation: IntellijReadObservation,
    private val permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
) {
    fun read(): Refinement<NativeValueArtifactContent, NativeValueModelFailure> {
        observation.count(IntellijReadCounter.VALUE_MODEL_ARTIFACT_READS)
        when (val admitted = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return limited(admitted.failure)
        }
        return try {
            val before =
                when (val identity = file.identity()) {
                    is Refinement.Refined -> identity.value
                    is Refinement.Rejected -> return artifactRejected(identity.failure)
                }
            val content = content()
            if (content is Refinement.Rejected && content.failure is NativeValueModelFailure.Limited) return content
            when (val admitted = permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return limited(admitted.failure)
            }
            val after =
                when (val identity = file.identity()) {
                    is Refinement.Refined -> identity.value
                    is Refinement.Rejected -> return artifactRejected(identity.failure)
                }
            if (before != after) artifactRejected(NativeValueArtifactFailure.CHANGED) else content
        } catch (_: IOException) {
            artifactRejected(NativeValueArtifactFailure.UNAVAILABLE)
        } catch (_: SecurityException) {
            artifactRejected(NativeValueArtifactFailure.UNAVAILABLE)
        }
    }

    private fun content(): Refinement<NativeValueArtifactContent, NativeValueModelFailure> {
        val digest =
            when (val read = digest()) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        if (digest != reviewed.binaryDigest) return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
        return when (val version = version()) {
            is Refinement.Refined -> Refinement.Refined(NativeValueArtifactContent(digest, version.value))
            is Refinement.Rejected -> version
        }
    }

    private fun digest(): Refinement<LibraryArtifactDigest, NativeValueModelFailure> {
        val digest = MessageDigest.getInstance("SHA-256")
        val content = observation.call(IntellijReadCall.VFS_OPEN_STREAM) { file.openArchive() }
        content.use { stream ->
            val buffer = ByteArray(ARTIFACT_CHUNK_BYTES)
            while (true) {
                val count =
                    when (val read = chunk(stream, buffer)) {
                        is Refinement.Refined -> read.value
                        is Refinement.Rejected -> return read
                    }
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return when (val parsed = LibraryArtifactDigest.parse(HexFormat.of().formatHex(digest.digest()))) {
            is Refinement.Refined -> parsed
            is Refinement.Rejected -> artifactRejected(NativeValueArtifactFailure.METADATA_INVALID)
        }
    }

    private fun version(): Refinement<LibraryArtifactVersion, NativeValueModelFailure> {
        when (val admitted = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return limited(admitted.failure)
        }
        val manifest = observation.call(IntellijReadCall.VFS_OPEN_STREAM) { file.openManifest() }
        if (manifest is NativeValueManifest.Missing)
            return artifactRejected(NativeValueArtifactFailure.VERSION_UNAVAILABLE)
        val bytes = ByteArrayOutputStream()
        (manifest as NativeValueManifest.Present).stream.use { stream ->
            val buffer = ByteArray(ARTIFACT_CHUNK_BYTES)
            while (true) {
                val count =
                    when (val read = chunk(stream, buffer)) {
                        is Refinement.Refined -> read.value
                        is Refinement.Rejected -> return read
                    }
                if (count < 0) break
                if (count > ARTIFACT_CHUNK_BYTES - bytes.size())
                    return artifactRejected(NativeValueArtifactFailure.METADATA_INVALID)
                bytes.write(buffer, 0, count)
            }
        }
        return parseVersion(bytes.toByteArray())
    }

    private fun chunk(stream: InputStream, buffer: ByteArray): Refinement<Int, NativeValueModelFailure> {
        when (val admitted = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return limited(admitted.failure)
        }
        val count = observation.call(IntellijReadCall.FILE_STREAM_READ) { stream.read(buffer) }
        if (count > 0) observation.count(IntellijReadCounter.VALUE_MODEL_ARTIFACT_BYTES_READ, amount = count)
        return Refinement.Refined(count)
    }
}

private fun parseVersion(bytes: ByteArray): Refinement<LibraryArtifactVersion, NativeValueModelFailure> {
    val raw =
        try {
            Manifest(ByteArrayInputStream(bytes)).mainAttributes.getValue("Implementation-Version")
                ?: return artifactRejected(NativeValueArtifactFailure.VERSION_UNAVAILABLE)
        } catch (_: IOException) {
            return artifactRejected(NativeValueArtifactFailure.METADATA_INVALID)
        }
    return when (val parsed = LibraryArtifactVersion.parse(raw)) {
        is Refinement.Refined -> parsed
        is Refinement.Rejected -> artifactRejected(NativeValueArtifactFailure.METADATA_INVALID)
    }
}

internal fun artifactRejected(cause: NativeValueArtifactFailure): Refinement.Rejected<NativeValueModelFailure> =
    Refinement.Rejected(NativeValueModelFailure.Artifact(cause))

private fun limited(cause: ValueFlowUnsupportedCause): Refinement.Rejected<NativeValueModelFailure> =
    Refinement.Rejected(NativeValueModelFailure.Limited(cause))

private const val ARTIFACT_CHUNK_BYTES = 65_536
