package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LibraryArtifactDigest
import io.github.amichne.kast.relation.contract.LibraryArtifactVersion
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.attribute.FileTime
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Stream effects establish artifact facts only, without K2 or model applicability claims. */
class NativeValueArtifactReadTest {
    @Test
    fun `reviewed digest precedes manifest and reads stay chunk bounded`() {
        val file = ArtifactFile()
        val observation = ArtifactObservation()
        val result = reader(file, observation).read()
        assertEquals(Refinement.Refined(NativeValueArtifactContent(reviewed.binaryDigest, reviewed.version)), result)
        assertTrue(file.events.indexOf("archive-eof") < file.events.indexOf("manifest-open"))
        assertEquals("identity", file.events.last())
        assertTrue(file.requestSizes.all { size -> size <= ARTIFACT_CHUNK_BYTES })
        assertEquals(PAYLOAD_BYTES.toLong() + MANIFEST.toByteArray().size, observation.bytes)
        assertEquals(1, observation.reads)
        assertEquals(1, file.archiveCloses)
        assertEquals(1, file.manifestCloses)
        file.assertIdentityReads(2)
    }

    @Test
    fun `unreviewed bytes never open the manifest`() {
        val file = ArtifactFile(bytes = "unreviewed".toByteArray())
        assertEquals(artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED), reader(file).read())
        assertTrue("manifest-open" !in file.events)
        assertEquals(1, file.archiveCloses)
        file.assertIdentityReads(2)
    }

    @Test
    fun `changed archive identity rejects otherwise reviewed content`() {
        val file = ArtifactFile(identities = listOf(identity, identity.copy(size = PAYLOAD_BYTES + 1L)))
        assertEquals(artifactRejected(NativeValueArtifactFailure.CHANGED), reader(file).read())
        file.assertIdentityReads(2)
    }

    @Test
    fun `archive stream failure stays unavailable`() {
        val file = ArtifactFile()
        val unavailable =
            object : NativeValueArtifactFile by file {
                override fun openArchive(): InputStream = throw IOException("Controlled stream refusal")
            }
        assertEquals(artifactRejected(NativeValueArtifactFailure.UNAVAILABLE), reader(unavailable).read())
        assertTrue("manifest-open" !in file.events)
        file.assertIdentityReads(1)
    }

    @Test
    fun `work refusal prevents chunk reads and all later artifact effects`() {
        val file = ArtifactFile()
        var admissions = 0
        val read =
            reader(
                    file,
                    permit = {
                        admissions += 1
                        if (admissions == 1) Refinement.Refined(Unit)
                        else Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
                    },
                )
                .read()
        assertEquals(
            Refinement.Rejected(NativeValueModelFailure.Limited(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)),
            read,
        )
        assertTrue(file.requestSizes.isEmpty())
        assertTrue("manifest-open" !in file.events)
        assertEquals(1, file.archiveCloses)
        file.assertIdentityReads(1)
    }

    @Test
    fun `cancellation propagates and closes the already admitted stream`() {
        val file = ArtifactFile()
        var admissions = 0
        assertThrows(CancellationException::class.java) {
            reader(
                    file,
                    permit = {
                        admissions += 1
                        if (admissions > 1) throw CancellationException("Controlled cancellation")
                        Refinement.Refined(Unit)
                    },
                )
                .read()
        }
        assertEquals(1, file.archiveCloses)
        assertTrue(file.requestSizes.isEmpty())
        file.assertIdentityReads(1)
    }

    @Test
    fun `missing implementation version remains unavailable`() {
        val file = ArtifactFile(manifest = "Manifest-Version: 1.0\n\n")
        assertEquals(artifactRejected(NativeValueArtifactFailure.VERSION_UNAVAILABLE), reader(file).read())
        file.assertIdentityReads(2)
    }

    @Test
    fun `oversized manifest metadata is rejected without unbounded retention`() {
        val file = ArtifactFile(manifest = "X".repeat(PAYLOAD_BYTES))
        assertEquals(artifactRejected(NativeValueArtifactFailure.METADATA_INVALID), reader(file).read())
        assertEquals(1, file.manifestCloses)
        file.assertIdentityReads(2)
    }

    private fun reader(
        file: NativeValueArtifactFile,
        observation: IntellijReadObservation = IntellijReadObservation.None,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause> = { Refinement.Refined(Unit) },
    ) = NativeValueArtifactRead(file = file, reviewed = reviewed, observation = observation, permit = permit)
}

private class ArtifactFile(
    private val bytes: ByteArray = ByteArray(PAYLOAD_BYTES) { 'X'.code.toByte() },
    private val manifest: String = MANIFEST,
    identities: List<NativeValueArchiveIdentity> = listOf(identity, identity),
) : NativeValueArtifactFile {
    private val pendingIdentities = ArrayDeque(identities)
    private var identityReads = 0
    val events = mutableListOf<String>()
    val requestSizes = mutableListOf<Int>()
    var archiveCloses = 0
        private set

    var manifestCloses = 0
        private set

    override fun identity(): Refinement<NativeValueArchiveIdentity, NativeValueArtifactFailure> {
        events += "identity"
        identityReads += 1
        return Refinement.Refined(pendingIdentities.removeFirst())
    }

    override fun openArchive(): InputStream {
        events += "archive-open"
        return object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                requestSizes += length
                val count = super.read(buffer, offset, length)
                if (count < 0) events += "archive-eof"
                return count
            }

            override fun close() {
                archiveCloses += 1
            }
        }
    }

    override fun openManifest(): NativeValueManifest {
        events += "manifest-open"
        return NativeValueManifest.Present(
            object : ByteArrayInputStream(manifest.toByteArray()) {
                override fun close() {
                    manifestCloses += 1
                }
            }
        )
    }

    fun assertIdentityReads(expected: Int) {
        assertEquals(expected, identityReads)
        assertEquals(2 - expected, pendingIdentities.size)
    }
}

private class ArtifactObservation : IntellijReadObservation {
    var bytes = 0L
    var reads = 0

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        if (counter == IntellijReadCounter.VALUE_MODEL_ARTIFACT_BYTES_READ) bytes += amount
        if (counter == IntellijReadCounter.VALUE_MODEL_ARTIFACT_READS) reads += amount
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
}

private const val ARTIFACT_CHUNK_BYTES = 65_536
private const val PAYLOAD_BYTES = 65_543
private const val MANIFEST = "Manifest-Version: 1.0\nImplementation-Version: fixture1\n\n"
private val identity =
    NativeValueArchiveIdentity(
        fileKey = "fixture-file",
        size = PAYLOAD_BYTES.toLong(),
        modified = FileTime.fromMillis(1),
        created = FileTime.fromMillis(1),
    )
private val reviewed =
    ReviewedLibraryArtifact(
        (LibraryArtifactDigest.parse("1a4edd30a09dc60f31dbb3681ebc85a14fe4ee70081aa30f7c09559856e0db47")
                as Refinement.Refined)
            .value,
        (LibraryArtifactDigest.parse("1a4edd30a09dc60f31dbb3681ebc85a14fe4ee70081aa30f7c09559856e0db47")
                as Refinement.Refined)
            .value,
        (LibraryArtifactVersion.parse("fixture1") as Refinement.Refined).value,
    )
