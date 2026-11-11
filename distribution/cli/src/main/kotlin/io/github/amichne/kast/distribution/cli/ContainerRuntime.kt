package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.ManagedHeadlessAssemblyRecord
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class ContainerPlatform(val publicName: String, val host: String) {
    @SerialName("macos-aarch64") MACOS_ARM64("macos-aarch64", "host/Kast.app/Contents"),
    @SerialName("macos-x86_64") MACOS_X64("macos-x86_64", "host/Kast.app/Contents"),
    @SerialName("linux-aarch64") LINUX_ARM64("linux-aarch64", "host"),
    @SerialName("linux-x86_64") LINUX_X64("linux-x86_64", "host");

    companion object {
        fun current(
            system: String = System.getProperty("os.name"),
            arch: String = System.getProperty("os.arch"),
        ): Refinement<ContainerPlatform, ContainerFailure> =
            when {
                system.startsWith("Mac") && arch in setOf("aarch64", "arm64") -> Refinement.Refined(MACOS_ARM64)
                system.startsWith("Mac") && arch in setOf("x86_64", "amd64") -> Refinement.Refined(MACOS_X64)
                system == "Linux" && arch in setOf("aarch64", "arm64") -> Refinement.Refined(LINUX_ARM64)
                system == "Linux" && arch in setOf("x86_64", "amd64") -> Refinement.Refined(LINUX_X64)
                else -> Refinement.Rejected(ContainerFailure.UNSUPPORTED_PLATFORM)
            }
    }
}

@Serializable
internal sealed interface RuntimeArchiveEntry {
    val path: String

    @Serializable
    @SerialName("FILE")
    data class File(override val path: String, val bytes: Long, val sha256: String, val executable: Boolean) :
        RuntimeArchiveEntry

    @Serializable
    @SerialName("SYMLINK")
    data class Link(override val path: String, val target: String) : RuntimeArchiveEntry
}

@Serializable
internal data class RuntimeArchiveManifest(
    val version: String,
    val platform: ContainerPlatform,
    val platformBuild: String,
    val distributionSha256: String,
    val bootstrapSha256: String,
    val files: List<RuntimeArchiveEntry>,
    @Required val schemaVersion: Int = 1,
    @Required val kind: String = "KAST_RUNTIME_V1",
)

@Serializable
internal data class ContainerRuntimeReceipt(
    val archiveSha256: String,
    val manifestSha256: String,
    @Required val schemaVersion: Int = 1,
)

internal class AdmittedContainerRuntime private constructor(val root: Path, val manifest: RuntimeArchiveManifest) {
    val key: String
        get() = root.fileName.toString()

    val home: Path
        get() = root.resolve(manifest.platform.host)

    val plugins: Path
        get() = root.resolve("components/plugins")

    companion object {
        fun read(
            root: Path,
            version: String,
            platform: ContainerPlatform,
        ): Refinement<AdmittedContainerRuntime, ContainerFailure> = containerFiles {
            readSelected(root, version, platform)
        }

        private fun readSelected(
            root: Path,
            version: String,
            platform: ContainerPlatform,
        ): Refinement<AdmittedContainerRuntime, ContainerFailure> {
            if (!Files.isDirectory(root, NOFOLLOW_LINKS) || root.toRealPath() != root)
                return Refinement.Rejected(ContainerFailure.RUNTIME_UNAVAILABLE)
            val manifest =
                when (
                    val parsed =
                        readContainerRecord<RuntimeArchiveManifest>(
                            root.resolve("manifest.json"),
                            MAXIMUM_RUNTIME_MANIFEST_BYTES,
                        )
                ) {
                    is Refinement.Rejected -> return parsed
                    is Refinement.Refined -> parsed.value
                }
            when (val valid = admitRuntimeManifest(manifest, version, platform)) {
                is Refinement.Rejected -> return valid
                is Refinement.Refined -> Unit
            }
            val receipt =
                when (val parsed = readContainerRecord<ContainerRuntimeReceipt>(root.resolve("runtime.json"))) {
                    is Refinement.Rejected -> return parsed
                    is Refinement.Refined -> parsed.value
                }
            val identityMatches =
                root.fileName.toString() == "$version-${platform.publicName}" && receipt.schemaVersion == 1
            val digestsMatch =
                runtimeDigest.matches(receipt.archiveSha256) &&
                    receipt.manifestSha256 == sha256(root.resolve("manifest.json"))
            return if (identityMatches && digestsMatch) Refinement.Refined(AdmittedContainerRuntime(root, manifest))
            else Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
        }
    }
}

internal const val MAXIMUM_RUNTIME_MANIFEST_BYTES = 16_777_216
internal val runtimeDigest = Regex("[a-f0-9]{64}")
private const val MAXIMUM_RUNTIME_BYTES = 8_589_934_592L
private const val MAXIMUM_RUNTIME_ENTRIES = 100_000
private const val MAXIMUM_LINK_BYTES = 4096
private const val COPY_BUFFER_BYTES = 65_536

/** The same native ZIP reader serves every platform. No archive executable, installer, or Python is invoked. */
internal fun importContainerRuntime(
    archive: Path,
    expected: String,
    runtimes: Path,
    version: String,
    platform: ContainerPlatform,
): Refinement<AdmittedContainerRuntime, ContainerFailure> = containerFiles {
    containerProgress(ContainerStage.ARCHIVE_ADMISSION, ContainerStageOutcome.STARTED)
    if (!runtimeDigest.matches(expected) || sha256(archive) != expected)
        return@containerFiles Refinement.Rejected(ContainerFailure.DIGEST_MISMATCH)
    ZipFile(archive.toFile()).use { zip ->
        val manifest =
            when (val admitted = readRuntimeArchive(zip, version, platform)) {
                is Refinement.Rejected -> return@containerFiles admitted
                is Refinement.Refined -> admitted.value
            }
        containerProgress(ContainerStage.ARCHIVE_ADMISSION, ContainerStageOutcome.COMPLETED)
        publishRuntime(zip, manifest, expected, runtimes)
    }
}

private fun readRuntimeArchive(
    zip: ZipFile,
    version: String,
    platform: ContainerPlatform,
): Refinement<RuntimeArchiveManifest, ContainerFailure> {
    val entries = zip.entries().asSequence().take(MAXIMUM_RUNTIME_ENTRIES + 2).toList()
    if (
        entries.size > MAXIMUM_RUNTIME_ENTRIES + 1 ||
            entries.any { it.isDirectory } ||
            entries.map { it.name }.toSet().size != entries.size
    )
        return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
    val metadata = zip.getEntry("manifest.json")
    if (metadata == null || metadata.size !in 1..MAXIMUM_RUNTIME_MANIFEST_BYTES)
        return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
    val bytes = zip.getInputStream(metadata).use { it.readNBytes(MAXIMUM_RUNTIME_MANIFEST_BYTES + 1) }
    if (bytes.size > MAXIMUM_RUNTIME_MANIFEST_BYTES) return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
    val manifest =
        containerJson.decodeFromString<RuntimeArchiveManifest>(bytes.decodeToString(throwOnInvalidSequence = true))
    when (val valid = admitRuntimeManifest(manifest, version, platform)) {
        is Refinement.Rejected -> return valid
        is Refinement.Refined -> Unit
    }
    return if (entries.map { it.name }.toSet() == manifest.files.map { it.path }.toSet() + "manifest.json")
        Refinement.Refined(manifest)
    else Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
}

private fun publishRuntime(
    zip: ZipFile,
    manifest: RuntimeArchiveManifest,
    expected: String,
    runtimes: Path,
): Refinement<AdmittedContainerRuntime, ContainerFailure> {
    when (val private = privateContainerDirectory(runtimes)) {
        is Refinement.Rejected -> return private
        is Refinement.Refined -> Unit
    }
    val destination = runtimes.resolve("${manifest.version}-${manifest.platform.publicName}")
    if (Files.exists(destination, NOFOLLOW_LINKS)) return reuseRuntime(destination, manifest, expected)
    val staging =
        Files.createDirectory(
            runtimes.resolve(".import-${UUID.randomUUID()}"),
            PosixFilePermissions.asFileAttribute(containerDirectoryMode),
        )
    // Rejected/interrupted staging remains unselected; it never becomes a runnable runtime.
    when (val extracted = extractRuntimeFiles(zip, manifest.files, staging)) {
        is Refinement.Rejected -> return extracted
        is Refinement.Refined -> Unit
    }
    when (val linked = extractRuntimeLinks(zip, manifest.files, staging)) {
        is Refinement.Rejected -> return linked
        is Refinement.Refined -> Unit
    }
    containerProgress(ContainerStage.RUNTIME_PUBLICATION, ContainerStageOutcome.STARTED)
    containerProgress(ContainerStage.RUNTIME_METADATA, ContainerStageOutcome.STARTED)
    zip.getInputStream(zip.getEntry("manifest.json")).use { Files.copy(it, staging.resolve("manifest.json")) }
    writeContainerRecord(
        staging.resolve(manifest.platform.host).resolve("kast-headless.json"),
        ManagedHeadlessAssemblyRecord(
            1,
            destination.resolve(manifest.platform.host).toString(),
            manifest.distributionSha256,
            manifest.bootstrapSha256,
            manifest.platformBuild,
            manifest.platformBuild + "-IJ",
        ),
    )
    writeContainerRecord(
        staging.resolve("runtime.json"),
        ContainerRuntimeReceipt(expected, sha256(staging.resolve("manifest.json"))),
    )
    containerProgress(ContainerStage.RUNTIME_METADATA, ContainerStageOutcome.COMPLETED)
    containerProgress(ContainerStage.RUNTIME_SEALING, ContainerStageOutcome.STARTED)
    sealRuntime(staging)
    containerProgress(ContainerStage.RUNTIME_SEALING, ContainerStageOutcome.COMPLETED)
    containerProgress(ContainerStage.RUNTIME_RENAME, ContainerStageOutcome.STARTED)
    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
    containerProgress(ContainerStage.RUNTIME_RENAME, ContainerStageOutcome.COMPLETED)
    containerProgress(ContainerStage.RUNTIME_PUBLICATION, ContainerStageOutcome.COMPLETED)
    return AdmittedContainerRuntime.read(destination, manifest.version, manifest.platform)
}

private fun reuseRuntime(
    destination: Path,
    manifest: RuntimeArchiveManifest,
    expected: String,
): Refinement<AdmittedContainerRuntime, ContainerFailure> =
    when (val receipt = readContainerRecord<ContainerRuntimeReceipt>(destination.resolve("runtime.json"))) {
        is Refinement.Rejected -> receipt
        is Refinement.Refined ->
            if (receipt.value.archiveSha256 == expected)
                AdmittedContainerRuntime.read(destination, manifest.version, manifest.platform)
            else Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
    }

private fun extractRuntimeFiles(
    zip: ZipFile,
    files: List<RuntimeArchiveEntry>,
    staging: Path,
): Refinement<Unit, ContainerFailure> {
    for (record in files.filterIsInstance<RuntimeArchiveEntry.File>()) {
        val entry = zip.getEntry(record.path)
        if (entry.size != record.bytes) return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
        val output = staging.resolve(record.path)
        Files.createDirectories(output.parent)
        val copied =
            zip.getInputStream(entry).use { input ->
                Files.newOutputStream(output, StandardOpenOption.CREATE_NEW).use { stream ->
                    copyRuntimeBytes(input, stream, record.bytes)
                }
            }
        when (copied) {
            is Refinement.Rejected -> return copied
            is Refinement.Refined -> Unit
        }
        if (sha256(output) != record.sha256) return Refinement.Rejected(ContainerFailure.DIGEST_MISMATCH)
        Files.setPosixFilePermissions(
            output,
            PosixFilePermissions.fromString(if (record.executable) "r-x------" else "r--------"),
        )
    }
    return Refinement.Refined(Unit)
}

private fun copyRuntimeBytes(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    expected: Long,
): Refinement<Unit, ContainerFailure> {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    var count = input.read(buffer)
    while (count >= 0) {
        total += count
        if (total > expected) return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
        output.write(buffer, 0, count)
        count = input.read(buffer)
    }
    return if (total == expected) Refinement.Refined(Unit) else Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
}

private fun extractRuntimeLinks(
    zip: ZipFile,
    files: List<RuntimeArchiveEntry>,
    staging: Path,
): Refinement<Unit, ContainerFailure> {
    val links = files.filterIsInstance<RuntimeArchiveEntry.Link>()
    for (record in links) {
        val entry = zip.getEntry(record.path)
        val target = record.target.toByteArray()
        if (target.size > MAXIMUM_LINK_BYTES || entry.size != target.size.toLong())
            return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
        if (!zip.getInputStream(entry).use { it.readNBytes(MAXIMUM_LINK_BYTES + 1) }.contentEquals(target))
            return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
        val output = staging.resolve(record.path)
        Files.createDirectories(output.parent)
        Files.createSymbolicLink(output, Path.of(record.target))
    }
    for (link in links) if (!staging.resolve(link.path).toRealPath().startsWith(staging))
        return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
    return Refinement.Refined(Unit)
}

private fun sealRuntime(staging: Path) {
    Files.walk(staging).use { paths ->
        paths
            // Keep the private publication entry renameable; all payload files and descendants are sealed.
            .filter { it != staging && !Files.isSymbolicLink(it) }
            .forEach { path ->
                val mode = if (Files.isDirectory(path) || Files.isExecutable(path)) "r-x------" else "r--------"
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
            }
    }
}

internal fun admitRuntimeManifest(
    manifest: RuntimeArchiveManifest,
    version: String,
    platform: ContainerPlatform,
): Refinement<Unit, ContainerFailure> {
    if (!runtimeIdentityMatches(manifest, version, platform))
        return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
    val files = manifest.files
    if (files.isEmpty() || files.size > MAXIMUM_RUNTIME_ENTRIES || files.map { it.path }.toSet().size != files.size)
        return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
    val names = files.mapTo(hashSetOf()) { it.path }
    if (files.any { !safeRuntimeEntry(it, names) }) return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
    val regular = files.filterIsInstance<RuntimeArchiveEntry.File>()
    val bootstrap = regular.singleOrNull {
        it.path == "${platform.host}/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar"
    }
    val bootstrapMatches = bootstrap?.sha256 == manifest.bootstrapSha256
    val hostPresent = regular.any { it.path.startsWith("components/plugins/kast-ide-hosted/lib/") }
    return if (regular.sumOf { it.bytes } <= MAXIMUM_RUNTIME_BYTES && bootstrapMatches && hostPresent)
        Refinement.Refined(Unit)
    else Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
}

private fun runtimeIdentityMatches(
    manifest: RuntimeArchiveManifest,
    version: String,
    platform: ContainerPlatform,
): Boolean {
    val schema = manifest.schemaVersion == 1 && manifest.kind == "KAST_RUNTIME_V1"
    val target = manifest.version == version && manifest.platform == platform
    val syntax =
        Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version) &&
            Regex("(?:262|263)\\.[0-9]+(?:\\.[0-9]+)*").matches(manifest.platformBuild)
    val digests = runtimeDigest.matches(manifest.distributionSha256) && runtimeDigest.matches(manifest.bootstrapSha256)
    return schema && target && syntax && digests
}

private fun safeRuntimeEntry(entry: RuntimeArchiveEntry, names: Set<String>): Boolean {
    if (!safeRuntimePath(entry.path)) return false
    if (!(entry.path.startsWith("host/") || entry.path.startsWith("components/plugins/"))) return false
    // Ancestor membership is checked before extraction, without an O(n²) descendant scan.
    var ancestor = Path.of(entry.path).parent
    while (ancestor != null) {
        if (ancestor.toString() in names) return false
        ancestor = ancestor.parent
    }
    return when (entry) {
        is RuntimeArchiveEntry.File -> entry.bytes in 0..MAXIMUM_RUNTIME_BYTES && runtimeDigest.matches(entry.sha256)
        is RuntimeArchiveEntry.Link -> {
            val target = Path.of(entry.target)
            val location = Path.of(entry.path).parent.resolve(target).normalize()
            !target.isAbsolute && location.startsWith("host") && entry.target.none { it in "\u0000\r\n\\:" }
        }
    }
}

private fun safeRuntimePath(raw: String): Boolean =
    raw.isNotEmpty() &&
        raw.none { it in "\u0000\r\n\\:" } &&
        raw.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
