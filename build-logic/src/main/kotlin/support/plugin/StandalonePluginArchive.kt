package support.plugin

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarInputStream
import java.util.zip.ZipInputStream

/**
 * Proof transition: `(Path, DecodedStandalonePluginReport) -> StandalonePluginArchiveResult`.
 * Establishes a regular non-symlinked archive beneath the repository root whose complete entry,
 * size, and digest set equals the decoded report and whose designated JAR owns one canonical
 * `plugin.xml`. Expected filesystem or archive mismatch is finite
 * [StandalonePluginReportFailure]. Raw archive bytes are permitted only at this build-evidence
 * boundary.
 */
internal fun verifyStandalonePluginArchive(
    repositoryRoot: Path,
    report: DecodedStandalonePluginReport,
): StandalonePluginArchiveResult {
    val root = repositoryRoot.toAbsolutePath().normalize()
    val archive = root.resolve(report.artifact.path.value).normalize()
    if (!archive.startsWith(root) || !Files.isRegularFile(archive) || Files.isSymbolicLink(archive)) {
        return archiveRejected(StandalonePluginReportFailure.ARTIFACT_UNAVAILABLE)
    }
    val observedSize = try {
        Files.size(archive)
    } catch (_: Exception) {
        return archiveRejected(StandalonePluginReportFailure.ARTIFACT_UNAVAILABLE)
    }
    if (observedSize != report.artifact.size.value) return archiveRejected(
        StandalonePluginReportFailure.ARTIFACT_SIZE_MISMATCH,
    )
    val archiveBytes = try {
        Files.readAllBytes(archive)
    } catch (_: Exception) {
        return archiveRejected(StandalonePluginReportFailure.ARTIFACT_UNAVAILABLE)
    }
    if (StandalonePluginDigest.observe(archiveBytes) != report.artifact.digest) {
        return archiveRejected(StandalonePluginReportFailure.ARTIFACT_DIGEST_MISMATCH)
    }
    val entries = readArchiveEntries(archiveBytes, report.payloadJars)
    if (entries is ArchiveEntryRead.Rejected) return archiveRejected(entries.failure)
    entries as ArchiveEntryRead.Complete
    val descriptorBytes = entries.bytesByEntry[report.descriptorJarEntry.value]
        ?: return archiveRejected(StandalonePluginReportFailure.DESCRIPTOR_ENTRY_MISSING)
    when (val result = admitCanonicalPluginDescriptor(descriptorBytes)) {
        CanonicalPluginDescriptorAdmission.Complete -> Unit
        is CanonicalPluginDescriptorAdmission.Rejected -> return archiveRejected(result.failure)
    }
    return StandalonePluginArchiveResult.Complete(VerifiedStandalonePluginReport(report))
}

private sealed interface ArchiveEntryRead {
    data class Complete(val bytesByEntry: Map<String, ByteArray>) : ArchiveEntryRead
    data class Rejected(val failure: StandalonePluginReportFailure) : ArchiveEntryRead
}

/**
 * Proof transition: reported payload references plus ZIP `ByteArray -> ArchiveEntryRead`.
 * Establishes exact entry order, identity, size, and digest equality. Expected malformed,
 * duplicate, missing, extra, or mismatched entries remain finite report failures; raw ZIP bytes
 * remain at the archive-evidence boundary.
 */
private fun readArchiveEntries(
    archiveBytes: ByteArray,
    expected: List<StandalonePluginJarReference>,
): ArchiveEntryRead = try {
    val expectedByEntry = expected.associateBy { it.entry.value }
    val observed = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val reference = expectedByEntry[entry.name]
                ?: return ArchiveEntryRead.Rejected(
                    StandalonePluginReportFailure.ARCHIVE_ENTRY_MISMATCH,
                )
            if (entry.isDirectory || entry.name in observed) return ArchiveEntryRead.Rejected(
                StandalonePluginReportFailure.ARCHIVE_ENTRY_MISMATCH,
            )
            val bytes = zip.readNBytes(reference.size.value.toInt() + 1)
            if (bytes.size.toLong() != reference.size.value) return ArchiveEntryRead.Rejected(
                StandalonePluginReportFailure.PAYLOAD_SIZE_MISMATCH,
            )
            if (StandalonePluginDigest.observe(bytes) != reference.digest) {
                return ArchiveEntryRead.Rejected(
                    StandalonePluginReportFailure.PAYLOAD_DIGEST_MISMATCH,
                )
            }
            observed[entry.name] = bytes
            entry = zip.nextEntry
        }
    }
    if (observed.keys.toList() != expected.map { it.entry.value }) {
        ArchiveEntryRead.Rejected(StandalonePluginReportFailure.ARCHIVE_ENTRY_MISMATCH)
    } else {
        ArchiveEntryRead.Complete(observed)
    }
} catch (_: Exception) {
    ArchiveEntryRead.Rejected(StandalonePluginReportFailure.ARCHIVE_MALFORMED)
}

private sealed interface CanonicalPluginDescriptorAdmission {
    data object Complete : CanonicalPluginDescriptorAdmission
    data class Rejected(val failure: StandalonePluginReportFailure) :
        CanonicalPluginDescriptorAdmission
}

/**
 * Proof transition: descriptor-owner JAR `ByteArray -> CanonicalPluginDescriptorAdmission`.
 * Establishes one securely parsed canonical descriptor with both required registrations. Expected
 * malformed, absent, duplicate, or mismatched descriptors are finite
 * [CanonicalPluginDescriptorAdmission.Rejected]. Raw JAR and XML bytes remain at this archive
 * evidence boundary.
 */
private fun admitCanonicalPluginDescriptor(
    jarBytes: ByteArray,
): CanonicalPluginDescriptorAdmission = try {
    JarInputStream(ByteArrayInputStream(jarBytes)).use { jar ->
        val descriptors = mutableListOf<ByteArray>()
        var entry = jar.nextJarEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name == "META-INF/plugin.xml") {
                descriptors += jar.readBytes()
            }
            entry = jar.nextJarEntry
        }
        if (descriptors.size != 1) {
            descriptorRejected()
        } else {
            when (val parsed = parseDescriptor(descriptors.single())) {
                is DescriptorParseResult.Complete -> if (
                    parsed.descriptor == PluginDescriptorObservation.Present(
                        KastStandalonePlugin.id.value,
                        RegistrationObservation.PRESENT,
                        RegistrationObservation.PRESENT,
                    )
                ) {
                    CanonicalPluginDescriptorAdmission.Complete
                } else {
                    descriptorRejected()
                }
                DescriptorParseResult.Rejected -> descriptorRejected()
            }
        }
    }
} catch (_: Exception) {
    descriptorRejected()
}

private fun descriptorRejected() = CanonicalPluginDescriptorAdmission.Rejected(
    StandalonePluginReportFailure.DESCRIPTOR_MISMATCH,
)

private fun archiveRejected(failure: StandalonePluginReportFailure) =
    StandalonePluginArchiveResult.Rejected(failure)
