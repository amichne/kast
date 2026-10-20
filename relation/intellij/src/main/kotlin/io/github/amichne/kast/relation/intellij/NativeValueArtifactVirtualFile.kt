package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.jar.JarFile

/** The archive comes from the exact compiler module root, never an assumed installation path. */
internal class NativeValueArtifactVirtualFile(
    private val file: VirtualFile,
    private val observation: IntellijReadObservation,
) : NativeValueArtifactFile {
    override fun identity(): Refinement<NativeValueArchiveIdentity, NativeValueArtifactFailure> {
        if (!file.isValid || !file.isInLocalFileSystem)
            return Refinement.Rejected(NativeValueArtifactFailure.UNAVAILABLE)
        val attributes = Files.readAttributes(file.toNioPath(), BasicFileAttributes::class.java)
        val key = attributes.fileKey() ?: return Refinement.Rejected(NativeValueArtifactFailure.UNAVAILABLE)
        if (!attributes.isRegularFile) return Refinement.Rejected(NativeValueArtifactFailure.UNAVAILABLE)
        return Refinement.Refined(
            NativeValueArchiveIdentity(
                fileKey = key.toString(),
                size = attributes.size(),
                modified = attributes.lastModifiedTime(),
                created = attributes.creationTime(),
            )
        )
    }

    override fun openArchive(): InputStream = Files.newInputStream(file.toNioPath())

    override fun openManifest(): NativeValueManifest {
        val archive = observation.call(IntellijReadCall.FILE_STREAM_READ) { JarFile(file.toNioPath().toFile()) }
        return try {
            val entry = archive.getJarEntry(JarFile.MANIFEST_NAME)
            if (entry == null) {
                archive.close()
                NativeValueManifest.Missing
            } else NativeValueManifest.Present(ArchiveManifestStream(archive.getInputStream(entry), archive))
        } catch (failure: IOException) {
            archive.close()
            throw failure
        }
    }
}

private class ArchiveManifestStream(stream: InputStream, private val archive: JarFile) : FilterInputStream(stream) {
    override fun close() {
        try {
            super.close()
        } finally {
            archive.close()
        }
    }
}
