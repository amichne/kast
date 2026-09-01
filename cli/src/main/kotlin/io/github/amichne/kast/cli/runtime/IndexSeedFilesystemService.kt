package io.github.amichne.kast.cli

import java.io.IOException
import java.io.IOError
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties

private const val INDEX_SEED_PROJECT_STATE = "cache-state.xml"
private const val INDEX_SEED_RECEIPT = "seed-receipt.properties"
private const val MAX_PROJECT_STATE_BYTES = 4L * 1024L * 1024L
private val INDEX_SEED_PROJECT_DIRECTORY = Regex("[A-Za-z0-9._-]{1,160}")
private val INDEX_SEED_GLOBAL_LAYOUT = mapOf(
    IndexSeedCategory.GLOBAL_VFS to listOf(Path.of(".home"), Path.of("caches")),
    IndexSeedCategory.GLOBAL_INDEXES to listOf(Path.of("index")),
    IndexSeedCategory.CLASSPATH_METADATA to listOf(
        Path.of("classpath"),
        Path.of("global-model-cache"),
    ),
)

/** Raw effect request; every field is refined again before any copy authority is issued. */
data class IndexSeedRequest(
    val sourceSystem: Path,
    val cacheRoot: Path,
    val runtime: InstalledIdeRuntime,
    val cacheIdentity: KastCacheIdentity,
    val consentRequest: IndexSeedConsentRequest,
)

/** One observation of both independent source-quiescence facts. */
data class SourceIdeQuiescence(
    val processState: SourceIdeProcessState,
    val lockState: SourceIdeLockState,
)

fun interface SourceIdeQuiescenceProbe {
    fun observe(sourceSystem: Path): SourceIdeQuiescence
}

fun interface IndexSeedFilesystemProbe {
    fun observe(sourceSystem: Path, cacheRoot: Path): IndexSeedFilesystem
}

/** One admitted, version-specific source entry and its unchanged relative destination. */
data class IndexSeedCopyEntry internal constructor(
    val source: Path,
    val relativePath: Path,
)

sealed interface IndexSeedCopyResult {
    data object Copied : IndexSeedCopyResult
    data object Rejected : IndexSeedCopyResult
}

fun interface IndexSeedCloner {
    fun clone(entries: List<IndexSeedCopyEntry>, targetSystem: Path): IndexSeedCopyResult
}

/** Interactive terminal authority; a missing terminal is an explicit absence of consent. */
data object ConsoleIndexSeedConsentProvider : IndexSeedConsentProvider {
    override fun request(disclosure: IndexSeedDisclosure): IndexSeedConsent {
        val console = System.console() ?: return IndexSeedConsent.ABSENT
        return try {
            val categories = disclosure.categories
                .map { it.name.lowercase().replace('_', '-') }
                .sorted()
                .joinToString(", ")
            console.writer().apply {
                println("Kast index seed copy categories: $categories")
                println("Estimated allowlisted source size: ${disclosure.estimatedBytes.value} bytes")
                println(
                    "Required global VFS/index data can contain entries from other projects; " +
                        "Kast copies it only into its private cache.",
                )
                flush()
            }
            when (console.readLine("Copy these indexes into Kast's private cache? [y/N] ")) {
                "y", "Y", "yes", "YES", "Yes" -> IndexSeedConsent.GRANTED
                else -> IndexSeedConsent.ABSENT
            }
        } catch (_: RuntimeException) {
            IndexSeedConsent.ABSENT
        } catch (_: IOError) {
            IndexSeedConsent.ABSENT
        }
    }
}

/** A cache path becomes usable only together with its validated receipt. */
data class IndexSeedPublication(
    val root: Path,
    val systemDirectory: Path,
    val receipt: IndexSeedReceipt,
)

sealed interface IndexSeedExecution {
    data class Seeded(val publication: IndexSeedPublication) : IndexSeedExecution
    data class Rejected(val failure: IndexSeedFailure) : IndexSeedExecution
}

/**
 * The sole effectful seed coordinator.
 *
 * Filesystem observations are progressively refined into a fixed 2026.2 layout, a stable content
 * manifest, a quiescent source, an APFS seed plan, an exact clone receipt, and finally an atomic
 * publication. No staging path is returned and every rejected execution removes it.
 */
class IndexSeedFilesystemService(
    private val quiescenceProbe: SourceIdeQuiescenceProbe,
    private val filesystemProbe: IndexSeedFilesystemProbe,
    private val cloner: IndexSeedCloner,
    private val consentProvider: IndexSeedConsentProvider = RejectingIndexSeedConsentProvider,
) {
    fun seed(request: IndexSeedRequest): IndexSeedExecution {
        if (request.runtime.identity != request.cacheIdentity.runtimeIdentity) {
            return IndexSeedExecution.Rejected(IndexSeedFailure.ValidationFailure)
        }
        val sourceSystem = canonicalDirectoryForSeed(request.sourceSystem)
            ?: return IndexSeedExecution.Rejected(IndexSeedFailure.ValidationFailure)
        val cacheRoot = canonicalDirectoryForSeed(request.cacheRoot)
            ?: return IndexSeedExecution.Rejected(IndexSeedFailure.ValidationFailure)
        val layout = when (
            val resolution = Intellij262IndexSeedLayout.resolve(
                sourceSystem,
                request.runtime.home,
                request.cacheIdentity.canonicalProjectRoot,
            )
        ) {
            is IndexSeedLayoutResolution.Resolved -> resolution.layout
            is IndexSeedLayoutResolution.Rejected -> {
                return IndexSeedExecution.Rejected(resolution.failure)
            }
        }
        val sourceCapture = when (val capture = captureManifest(sourceSystem, layout.entries)) {
            is IndexSeedManifestCapture.Captured -> capture
            is IndexSeedManifestCapture.Rejected -> {
                return IndexSeedExecution.Rejected(capture.failure)
            }
        }
        val sourceBefore = sourceCapture.manifest
        val initialQuiescence = quiescenceProbe.observe(sourceSystem)
        val source = when (
            val admission = QuiescentIdeSystem.admit(
                sourceSystem,
                request.runtime.identity,
                initialQuiescence.processState,
                initialQuiescence.lockState,
                sourceBefore,
            )
        ) {
            is QuiescentIdeSystemAdmission.Admitted -> admission.system
            is QuiescentIdeSystemAdmission.Rejected -> {
                return IndexSeedExecution.Rejected(admission.failure)
            }
        }
        val consent = when (request.consentRequest) {
            IndexSeedConsentRequest.PREGRANTED -> IndexSeedConsent.GRANTED
            IndexSeedConsentRequest.INTERACTIVE -> consentProvider.request(
                IndexSeedDisclosure.fixed(sourceCapture.estimatedBytes),
            )
        }
        val plan = when (
            val planning = IndexSeedPlan.create(
                request.cacheIdentity,
                source,
                consent,
                filesystemProbe.observe(sourceSystem, cacheRoot),
            )
        ) {
            is IndexSeedPlanning.Planned -> planning.plan
            is IndexSeedPlanning.Rejected -> return IndexSeedExecution.Rejected(planning.failure)
        }

        val targetRoot = cacheRoot.resolve(request.cacheIdentity.key)
        if (Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS)) {
            return IndexSeedExecution.Rejected(IndexSeedFailure.ValidationFailure)
        }
        val staging = try {
            Files.createTempDirectory(cacheRoot, ".${request.cacheIdentity.key.take(16)}.seed-")
        } catch (_: IOException) {
            return IndexSeedExecution.Rejected(IndexSeedFailure.CopyFailure)
        } catch (_: SecurityException) {
            return IndexSeedExecution.Rejected(IndexSeedFailure.CopyFailure)
        }
        return seedIntoStaging(plan, layout, sourceSystem, staging, targetRoot)
    }

    private fun seedIntoStaging(
        plan: IndexSeedPlan,
        layout: IndexSeedSourceLayout,
        sourceSystem: Path,
        staging: Path,
        targetRoot: Path,
    ): IndexSeedExecution {
        try {
            val stagingSystem = Files.createDirectory(staging.resolve("system"))
            when (cloner.clone(layout.entries, stagingSystem)) {
                IndexSeedCopyResult.Copied -> Unit
                IndexSeedCopyResult.Rejected -> {
                    return IndexSeedExecution.Rejected(IndexSeedFailure.CopyFailure)
                }
            }
            val sourceAfter = when (val capture = captureManifest(sourceSystem, layout.entries)) {
                is IndexSeedManifestCapture.Captured -> capture.manifest
                is IndexSeedManifestCapture.Rejected -> {
                    return IndexSeedExecution.Rejected(capture.failure)
                }
            }
            val afterCopyQuiescence = quiescenceProbe.observe(sourceSystem)
            if (
                afterCopyQuiescence.processState != SourceIdeProcessState.STOPPED ||
                afterCopyQuiescence.lockState != SourceIdeLockState.UNLOCKED
            ) {
                return IndexSeedExecution.Rejected(IndexSeedFailure.RunningSourceIde)
            }
            val cloned = when (val capture = captureManifest(stagingSystem, layout.entries)) {
                is IndexSeedManifestCapture.Captured -> capture.manifest
                is IndexSeedManifestCapture.Rejected -> {
                    return IndexSeedExecution.Rejected(capture.failure)
                }
            }
            val receipt = when (val completion = IndexSeedReceipt.complete(plan, sourceAfter, cloned)) {
                is IndexSeedCompletion.Completed -> completion.receipt
                is IndexSeedCompletion.Rejected -> {
                    return IndexSeedExecution.Rejected(completion.failure)
                }
            }
            if (!writeReceipt(staging.resolve(INDEX_SEED_RECEIPT), receipt)) {
                return IndexSeedExecution.Rejected(IndexSeedFailure.ValidationFailure)
            }
            val publicationQuiescence = quiescenceProbe.observe(sourceSystem)
            if (
                publicationQuiescence.processState != SourceIdeProcessState.STOPPED ||
                publicationQuiescence.lockState != SourceIdeLockState.UNLOCKED
            ) {
                return IndexSeedExecution.Rejected(IndexSeedFailure.RunningSourceIde)
            }
            try {
                Files.move(staging, targetRoot, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                return IndexSeedExecution.Rejected(IndexSeedFailure.CopyFailure)
            } catch (_: IOException) {
                return IndexSeedExecution.Rejected(IndexSeedFailure.CopyFailure)
            } catch (_: SecurityException) {
                return IndexSeedExecution.Rejected(IndexSeedFailure.CopyFailure)
            }
            return IndexSeedExecution.Seeded(
                IndexSeedPublication(
                    targetRoot,
                    targetRoot.resolve("system"),
                    receipt,
                ),
            )
        } catch (_: IOException) {
            return IndexSeedExecution.Rejected(IndexSeedFailure.CopyFailure)
        } catch (_: SecurityException) {
            return IndexSeedExecution.Rejected(IndexSeedFailure.CopyFailure)
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                deleteUnpublishedStaging(staging)
            }
        }
    }
}

/** Production source proof: no matching process plus no live/stale IDEA system markers. */
data object FilesystemSourceIdeQuiescenceProbe : SourceIdeQuiescenceProbe {
    override fun observe(sourceSystem: Path): SourceIdeQuiescence {
        val canonical = canonicalDirectoryForSeed(sourceSystem)
            ?: return SourceIdeQuiescence(
                SourceIdeProcessState.UNKNOWN,
                SourceIdeLockState.UNKNOWN,
            )
        val pid = canonical.resolve(".pid")
        val port = canonical.resolve(".port")
        val processState = observeProcess(canonical, pid)
        val lockState = try {
            when {
                Files.isSymbolicLink(pid) || Files.isSymbolicLink(port) ->
                    SourceIdeLockState.UNKNOWN
                Files.exists(pid, LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(port, LinkOption.NOFOLLOW_LINKS) -> SourceIdeLockState.LOCKED
                else -> SourceIdeLockState.UNLOCKED
            }
        } catch (_: SecurityException) {
            SourceIdeLockState.UNKNOWN
        }
        return SourceIdeQuiescence(processState, lockState)
    }

    private fun observeProcess(system: Path, pidFile: Path): SourceIdeProcessState = try {
        if (Files.isSymbolicLink(pidFile)) return SourceIdeProcessState.UNKNOWN
        if (Files.isRegularFile(pidFile, LinkOption.NOFOLLOW_LINKS)) {
            val pid = Files.readString(pidFile).trim().toLongOrNull()
                ?: return SourceIdeProcessState.UNKNOWN
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return SourceIdeProcessState.RUNNING
            }
        }
        val marker = "-Didea.system.path=$system"
        ProcessHandle.allProcesses().use { processes ->
            if (
                processes.anyMatch { process ->
                    process.isAlive && process.info().commandLine().orElse("").contains(marker)
                }
            ) {
                SourceIdeProcessState.RUNNING
            } else {
                SourceIdeProcessState.STOPPED
            }
        }
    } catch (_: IOException) {
        SourceIdeProcessState.UNKNOWN
    } catch (_: SecurityException) {
        SourceIdeProcessState.UNKNOWN
    }
}

/** Production capability proof: source and destination are the same APFS file store. */
data object ApfsIndexSeedFilesystemProbe : IndexSeedFilesystemProbe {
    override fun observe(sourceSystem: Path, cacheRoot: Path): IndexSeedFilesystem = try {
        val sourceStore = Files.getFileStore(sourceSystem)
        val cacheStore = Files.getFileStore(cacheRoot)
        if (
            sourceStore.type().equals("apfs", ignoreCase = true) &&
            cacheStore.type().equals("apfs", ignoreCase = true) &&
            sourceStore.name() == cacheStore.name()
        ) {
            IndexSeedFilesystem.APFS
        } else {
            IndexSeedFilesystem.UNSUPPORTED
        }
    } catch (_: IOException) {
        IndexSeedFilesystem.UNSUPPORTED
    } catch (_: SecurityException) {
        IndexSeedFilesystem.UNSUPPORTED
    }
}

/** Production copier: every fixed entry is cloned independently with macOS `cp -cR`. */
data object ApfsCoWIndexSeedCloner : IndexSeedCloner {
    override fun clone(
        entries: List<IndexSeedCopyEntry>,
        targetSystem: Path,
    ): IndexSeedCopyResult = try {
        entries.forEach { entry ->
            val target = targetSystem.resolve(entry.relativePath).normalize()
            if (!target.startsWith(targetSystem) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return IndexSeedCopyResult.Rejected
            }
            Files.createDirectories(target.parent)
            val process = ProcessBuilder(
                "/bin/cp",
                "-cR",
                entry.source.toString(),
                target.toString(),
            ).redirectErrorStream(true).start()
            process.inputStream.use(InputStream::readAllBytes)
            if (process.waitFor() != 0) return IndexSeedCopyResult.Rejected
        }
        IndexSeedCopyResult.Copied
    } catch (_: IOException) {
        IndexSeedCopyResult.Rejected
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        IndexSeedCopyResult.Rejected
    } catch (_: SecurityException) {
        IndexSeedCopyResult.Rejected
    }
}

private data class IndexSeedSourceLayout(
    val entries: List<IndexSeedCopyEntry>,
)

private sealed interface IndexSeedLayoutResolution {
    data class Resolved(val layout: IndexSeedSourceLayout) : IndexSeedLayoutResolution
    data class Rejected(val failure: IndexSeedFailure) : IndexSeedLayoutResolution
}

/** Exact internal layout admitted only for the release-pinned IntelliJ 2026.2 build. */
private data object Intellij262IndexSeedLayout {
    fun resolve(
        sourceSystem: Path,
        installedIdeaHome: Path,
        canonicalProjectRoot: Path,
    ): IndexSeedLayoutResolution {
        if (!sourceBelongsToRuntime(sourceSystem, installedIdeaHome)) {
            return IndexSeedLayoutResolution.Rejected(IndexSeedFailure.ValidationFailure)
        }
        val projectCache = when (
            val resolution = resolveProjectCache(sourceSystem, canonicalProjectRoot)
        ) {
            is ProjectCacheResolution.Resolved -> resolution.relativePath
            is ProjectCacheResolution.Rejected -> {
                return IndexSeedLayoutResolution.Rejected(resolution.failure)
            }
        }
        val relativeEntries = INDEX_SEED_GLOBAL_LAYOUT.values.flatten() + listOf(projectCache)
        val entries = mutableListOf<IndexSeedCopyEntry>()
        relativeEntries.forEach { relative ->
            val source = sourceSystem.resolve(relative).normalize()
            if (
                !source.startsWith(sourceSystem) ||
                Files.isSymbolicLink(source) ||
                (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            ) {
                return IndexSeedLayoutResolution.Rejected(IndexSeedFailure.ValidationFailure)
            }
            entries += IndexSeedCopyEntry(source, relative)
        }
        return IndexSeedLayoutResolution.Resolved(IndexSeedSourceLayout(entries))
    }

    private fun sourceBelongsToRuntime(sourceSystem: Path, installedIdeaHome: Path): Boolean = try {
        val marker = sourceSystem.resolve(".home")
        if (
            Files.isSymbolicLink(marker) ||
            !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(marker) > 4096L
        ) {
            false
        } else {
            val observed = Path.of(Files.readString(marker))
            canonicalDirectoryForSeed(observed) == installedIdeaHome
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun resolveProjectCache(
        sourceSystem: Path,
        canonicalProjectRoot: Path,
    ): ProjectCacheResolution {
        val projects = sourceSystem.resolve("projects")
        if (!Files.isDirectory(projects, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(projects)) {
            return ProjectCacheResolution.Rejected(IndexSeedFailure.ValidationFailure)
        }
        val matches = try {
            Files.list(projects).use { children ->
                children.filter { child ->
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(child) &&
                        INDEX_SEED_PROJECT_DIRECTORY.matches(child.fileName.toString()) &&
                        stateNamesProject(child.resolve(INDEX_SEED_PROJECT_STATE), canonicalProjectRoot)
                }.map { child -> projects.relativize(child) }.toList()
            }
        } catch (_: IOException) {
            return ProjectCacheResolution.Rejected(IndexSeedFailure.ValidationFailure)
        } catch (_: SecurityException) {
            return ProjectCacheResolution.Rejected(IndexSeedFailure.ValidationFailure)
        }
        return when (matches.size) {
            1 -> ProjectCacheResolution.Resolved(Path.of("projects").resolve(matches.single()))
            0 -> ProjectCacheResolution.Rejected(IndexSeedFailure.ValidationFailure)
            else -> ProjectCacheResolution.Rejected(IndexSeedFailure.Ambiguity)
        }
    }

    private fun stateNamesProject(state: Path, projectRoot: Path): Boolean = try {
        if (
            Files.isSymbolicLink(state) ||
            !Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(state) > MAX_PROJECT_STATE_BYTES
        ) {
            false
        } else {
            val content = Files.readString(state)
            val root = projectRoot.toString()
            content.contains("\"$root\"") || content.contains("value=\"$root\"")
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private sealed interface ProjectCacheResolution {
    data class Resolved(val relativePath: Path) : ProjectCacheResolution
    data class Rejected(val failure: IndexSeedFailure) : ProjectCacheResolution
}

private sealed interface IndexSeedManifestCapture {
    data class Captured(
        val manifest: IndexContentManifest,
        val estimatedBytes: IndexSeedEstimatedBytes,
    ) : IndexSeedManifestCapture
    data class Rejected(val failure: IndexSeedFailure) : IndexSeedManifestCapture
}

private fun captureManifest(
    root: Path,
    entries: List<IndexSeedCopyEntry>,
): IndexSeedManifestCapture {
    val relativeEntries = entries.map(IndexSeedCopyEntry::relativePath)
    val hashes = sortedMapOf<String, String>()
    var estimatedBytes = 0L
    try {
        relativeEntries.forEach { relative ->
            val start = root.resolve(relative).normalize()
            if (!start.startsWith(root) || Files.isSymbolicLink(start)) {
                return IndexSeedManifestCapture.Rejected(IndexSeedFailure.ValidationFailure)
            }
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (attributes.isSymbolicLink || !attributes.isDirectory) {
                        throw InvalidSeedContent()
                    }
                    hashes[manifestName(root, directory, directory = true)] = digestOf(
                        ByteArray(0),
                    )
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (attributes.isSymbolicLink || !attributes.isRegularFile) {
                        throw InvalidSeedContent()
                    }
                    estimatedBytes = Math.addExact(estimatedBytes, attributes.size())
                    hashes[manifestName(root, file, directory = false)] = digestOf(file)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    } catch (_: IOException) {
        return IndexSeedManifestCapture.Rejected(IndexSeedFailure.ValidationFailure)
    } catch (_: SecurityException) {
        return IndexSeedManifestCapture.Rejected(IndexSeedFailure.ValidationFailure)
    } catch (_: InvalidSeedContent) {
        return IndexSeedManifestCapture.Rejected(IndexSeedFailure.ValidationFailure)
    } catch (_: ArithmeticException) {
        return IndexSeedManifestCapture.Rejected(IndexSeedFailure.ValidationFailure)
    }
    val measured = IndexSeedEstimatedBytes.from(estimatedBytes)
        ?: return IndexSeedManifestCapture.Rejected(IndexSeedFailure.ValidationFailure)
    return when (val admission = IndexContentManifest.from(hashes)) {
        is IndexContentManifestAdmission.Admitted -> IndexSeedManifestCapture.Captured(
            admission.manifest,
            measured,
        )
        is IndexContentManifestAdmission.Rejected -> IndexSeedManifestCapture.Rejected(
            admission.failure,
        )
    }
}

private class InvalidSeedContent : RuntimeException()

private fun manifestName(root: Path, path: Path, directory: Boolean): String {
    val relative = root.relativize(path).joinToString("/") { it.toString() }
    return if (directory) "$relative/" else relative
}

private fun digestOf(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return "sha256:${HexFormat.of().formatHex(digest.digest())}"
}

private fun digestOf(bytes: ByteArray): String = "sha256:" + HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(bytes),
)

private fun writeReceipt(path: Path, receipt: IndexSeedReceipt): Boolean = try {
    val properties = Properties().apply {
        setProperty("format", "kast.index-seed.receipt.v1")
        setProperty("cache.key", receipt.cacheIdentity.key)
        setProperty("project.root", receipt.cacheIdentity.canonicalProjectRoot.toString())
        setProperty("source.system", receipt.sourceSystem.toString())
        setProperty("idea.build", receipt.runtimeIdentity.supportedPair.ideaBuild)
        setProperty(
            "kotlin.plugin.build",
            receipt.runtimeIdentity.supportedPair.kotlinPluginBuild,
        )
        setProperty("jbr.identity", receipt.runtimeIdentity.jbrIdentity)
        setProperty("kast.payload.digest", receipt.runtimeIdentity.kastPayloadDigest)
        setProperty(
            "categories",
            receipt.categories.sortedBy(IndexSeedCategory::name).joinToString(",") { it.name },
        )
        val manifestMaterial = receipt.contentManifest.entries.entries.joinToString("\n") {
            "${it.key}=${it.value}"
        }
        setProperty("content.manifest.digest", digestOf(manifestMaterial.toByteArray()))
    }
    Files.newOutputStream(path).use { output -> properties.store(output, null) }
    true
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}

private fun canonicalDirectoryForSeed(path: Path): Path? {
    if (!path.isAbsolute || path.normalize() != path) return null
    return try {
        path.toRealPath().takeIf { canonical ->
            canonical == path && Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private fun deleteUnpublishedStaging(staging: Path) {
    try {
        Files.walk(staging).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: IOException) {
                    // Staging is never semantic authority; best-effort cleanup cannot be success.
                } catch (_: SecurityException) {
                    // Staging is never semantic authority; best-effort cleanup cannot be success.
                }
            }
        }
    } catch (_: IOException) {
        // Staging is never semantic authority; best-effort cleanup cannot be success.
    } catch (_: SecurityException) {
        // Staging is never semantic authority; best-effort cleanup cannot be success.
    }
}
