package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.distribution.contract.bootstrap.ModelInputFailure
import io.github.amichne.kast.distribution.contract.bootstrap.ModelInputFailureReason
import io.github.amichne.kast.distribution.contract.bootstrap.ModelInputPath
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Physical evidence for conventional workspace Gradle inputs at the import boundary.
 *
 * This guard covers build/settings scripts, Gradle properties, version catalogs, and buildSrc or build-logic source. It
 * does not infer arbitrary inputs used by executable build logic. Changed evidence invalidates the imported model; a
 * new import must establish a new guard.
 */
internal class InstalledGradleModelInputs
private constructor(
    private val workspaceRoot: Path,
    private val entries: Map<Path, EntryIdentity>,
) {
    /** Returns the original imported-input authority only while its current physical inputs agree. */
    fun current(): Refinement<InstalledGradleModelInputs, InstalledGradleModelCaptureFailure> =
        when (val observed = capture(workspaceRoot)) {
            is Refinement.Rejected -> observed
            is Refinement.Refined ->
                if (entries == observed.value.entries) {
                    Refinement.Refined(this)
                } else {
                    Refinement.Rejected(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED)
                }
        }

    /**
     * Refines an observation under this imported-input authority into retained evidence only after matching physical
     * input observations before and after the callback. A moved or unavailable input rejects the successful callback
     * result. This proves endpoint agreement, not that a concurrently changed file could never have been restored
     * between the two observations.
     */
    fun <Evidence> observeCurrent(
        capture: (InstalledGradleModelInputs) -> Refinement<Evidence, InstalledGradleModelCaptureFailure>
    ): Refinement<Evidence, InstalledGradleModelCaptureFailure> {
        val before =
            when (val observation = current()) {
                is Refinement.Refined -> observation.value
                is Refinement.Rejected -> return observation
            }
        val captured =
            when (val observation = capture(before)) {
                is Refinement.Refined -> observation
                is Refinement.Rejected -> return observation
            }
        return when (val after = before.current()) {
            is Refinement.Refined -> captured
            is Refinement.Rejected -> after
        }
    }

    private data class ContentIdentity(val sha256: String)

    private data class LinkIdentity(val path: Path, val storedTarget: Path)

    private sealed interface EntryContent {
        data class File(val identity: ContentIdentity) : EntryContent

        data object Directory : EntryContent
    }

    private data class EntryIdentity(
        val links: List<LinkIdentity>,
        val target: Path,
        val content: EntryContent,
    )

    private data class ResolvedEntry(val path: Path, val links: List<LinkIdentity>, val attributes: BasicFileAttributes)

    companion object {
        /** Captures logical inputs without following any link outside the canonical workspace. */
        fun capture(workspaceRoot: Path): Refinement<InstalledGradleModelInputs, InstalledGradleModelCaptureFailure> =
            try {
                if (
                    !Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS) ||
                        workspaceRoot.toRealPath() != workspaceRoot
                ) {
                    Refinement.Rejected(InstalledGradleModelCaptureFailure.MODEL_INPUTS_UNAVAILABLE)
                } else {
                    val entries = sortedMapOf<Path, EntryIdentity>()
                    when (val result = visit(workspaceRoot, Path.of(""), emptySet(), entries)) {
                        is Refinement.Rejected -> result
                        is Refinement.Refined ->
                            Refinement.Refined(InstalledGradleModelInputs(workspaceRoot, entries.toMap()))
                    }
                }
            } catch (_: IOException) {
                Refinement.Rejected(InstalledGradleModelCaptureFailure.MODEL_INPUTS_UNAVAILABLE)
            } catch (_: SecurityException) {
                Refinement.Rejected(InstalledGradleModelCaptureFailure.MODEL_INPUTS_UNAVAILABLE)
            }

        /** Bounded optional JVM configuration uses the same contained resolver before import. */
        fun readProperties(
            root: Path,
            logical: Path,
        ): Refinement<InstalledGradleProperties, InstalledGradleModelCaptureFailure> {
            var prefix = Path.of("")
            try {
                val canonicalRoot = root.toRealPath()
                var parent = canonicalRoot
                for (component in logical) {
                    if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
                        return rejected(logical, ModelInputFailureReason.UNSUPPORTED)
                    if (Files.notExists(parent.resolve(component), LinkOption.NOFOLLOW_LINKS)) {
                        return Refinement.Refined(InstalledGradleProperties.Absent)
                    }
                    prefix = prefix.resolve(component)
                    when (val resolved = resolve(canonicalRoot, prefix)) {
                        is Refinement.Rejected -> return resolved
                        is Refinement.Refined -> parent = resolved.value.path
                    }
                }
                val entry =
                    when (val resolved = resolve(canonicalRoot, logical)) {
                        is Refinement.Rejected -> return resolved
                        is Refinement.Refined -> resolved.value
                    }
                if (!entry.attributes.isRegularFile || entry.attributes.size() > 1_048_576) {
                    return rejected(logical, ModelInputFailureReason.UNSUPPORTED)
                }
                val bytes =
                    when (
                        val content =
                            readResolvedInput(entry.path, logical) { directory, name ->
                                directory
                                    .newByteChannel(
                                        name,
                                        setOf(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                                    )
                                    .use { channel ->
                                        java.nio.channels.Channels.newInputStream(channel).readNBytes(1_048_577)
                                    }
                            }
                    ) {
                        is Refinement.Refined -> content.value
                        is Refinement.Rejected -> return content
                    }
                if (bytes.size > 1_048_576) return rejected(logical, ModelInputFailureReason.UNSUPPORTED)
                val properties = java.util.Properties()
                bytes.inputStream().reader(Charsets.UTF_8).use(properties::load)
                return Refinement.Refined(InstalledGradleProperties.Present(properties))
            } catch (_: IOException) {
                return rejected(logical, ModelInputFailureReason.UNREADABLE)
            } catch (_: SecurityException) {
                return rejected(logical, ModelInputFailureReason.UNREADABLE)
            } catch (_: IllegalArgumentException) {
                return rejected(logical, ModelInputFailureReason.UNSUPPORTED)
            }
        }

        private fun rejected(
            logical: Path,
            reason: ModelInputFailureReason,
        ): Refinement.Rejected<InstalledGradleModelCaptureFailure> =
            when (val path = ModelInputPath.admit(logical.toString().ifEmpty { "." })) {
                is Refinement.Refined ->
                    Refinement.Rejected(
                        InstalledGradleModelCaptureFailure.ModelInputRejected(ModelInputFailure(path.value, reason))
                    )
                is Refinement.Rejected ->
                    Refinement.Rejected(InstalledGradleModelCaptureFailure.MODEL_INPUTS_UNAVAILABLE)
            }

        /** Resolve components in order; a later `..` cannot erase an earlier link boundary. */
        private fun resolve(
            root: Path,
            logical: Path,
            candidate: Path = root.resolve(logical),
            activeLinks: Set<Path> = emptySet(),
        ): Refinement<ResolvedEntry, InstalledGradleModelCaptureFailure> {
            if (activeLinks.size > 256) return rejected(logical, ModelInputFailureReason.UNSUPPORTED)
            if (!candidate.startsWith(root)) return rejected(logical, ModelInputFailureReason.OUTSIDE_WORKSPACE)
            var cursor = root
            val links = mutableListOf<LinkIdentity>()
            try {
                for (component in
                    if (candidate == root) Path.of("") else candidate.subpath(root.nameCount, candidate.nameCount)) {
                    if (
                        component.toString().isNotEmpty() &&
                            !Files.readAttributes(cursor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                                .isDirectory
                    ) {
                        return rejected(logical, ModelInputFailureReason.UNSUPPORTED)
                    }
                    when (component.toString()) {
                        "",
                        "." -> continue
                        ".." -> {
                            if (cursor == root) return rejected(logical, ModelInputFailureReason.OUTSIDE_WORKSPACE)
                            cursor = cursor.parent
                            continue
                        }
                    }
                    cursor = cursor.resolve(component)
                    val attributes =
                        Files.readAttributes(cursor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (attributes.isSymbolicLink) {
                        if (cursor in activeLinks) return rejected(logical, ModelInputFailureReason.LINK_CYCLE)
                        val stored = Files.readSymbolicLink(cursor)
                        links += LinkIdentity(root.relativize(cursor), stored)
                        val target = if (stored.isAbsolute) stored else cursor.parent.resolve(stored)
                        when (val resolved = resolve(root, logical, target, activeLinks + setOf(cursor))) {
                            is Refinement.Rejected -> return resolved
                            is Refinement.Refined -> {
                                cursor = resolved.value.path
                                links += resolved.value.links
                            }
                        }
                    }
                }
                return Refinement.Refined(
                    ResolvedEntry(
                        cursor,
                        links.toList(),
                        Files.readAttributes(cursor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS),
                    )
                )
            } catch (_: NoSuchFileException) {
                return rejected(logical, ModelInputFailureReason.TARGET_MISSING)
            } catch (_: FileSystemLoopException) {
                return rejected(logical, ModelInputFailureReason.LINK_CYCLE)
            } catch (_: IOException) {
                return rejected(logical, ModelInputFailureReason.UNREADABLE)
            } catch (_: SecurityException) {
                return rejected(logical, ModelInputFailureReason.UNREADABLE)
            }
        }

        private fun visit(
            root: Path,
            logical: Path,
            ancestry: Set<Path>,
            entries: MutableMap<Path, EntryIdentity>,
        ): Refinement<Unit, InstalledGradleModelCaptureFailure> {
            val resolved =
                when (val result = resolve(root, logical)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            try {
                if (resolved.attributes.isDirectory) {
                    if (resolved.path in ancestry) return rejected(logical, ModelInputFailureReason.LINK_CYCLE)
                    // Directory identity retains even an empty alias and every link in its chain.
                    if (selected(logical) || resolved.links.isNotEmpty()) {
                        entries[logical] =
                            EntryIdentity(resolved.links, root.relativize(resolved.path), EntryContent.Directory)
                    }
                    val children =
                        when (
                            val read =
                                readResolvedInput(resolved.path, logical) { parent, name ->
                                    parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { directory ->
                                        directory.map { it.fileName }.sorted()
                                    }
                                }
                        ) {
                            is Refinement.Refined -> read.value
                            is Refinement.Rejected -> return read
                        }
                    for (name in children) {
                        val child = logical.resolve(name)
                        val physical = resolved.path.resolve(name)
                        val attributes =
                            Files.readAttributes(physical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (
                            name.toString() in excludedDirectories &&
                                (attributes.isDirectory || attributes.isSymbolicLink)
                        )
                            continue
                        if (
                            attributes.isDirectory ||
                                selected(child) ||
                                (attributes.isSymbolicLink && name.toString() in modelInputDirectories)
                        ) {
                            when (val result = visit(root, child, ancestry + setOf(resolved.path), entries)) {
                                is Refinement.Rejected -> return result
                                is Refinement.Refined -> Unit
                            }
                        }
                    }
                } else if (resolved.attributes.isRegularFile) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    when (
                        val read =
                            readResolvedInput(resolved.path, logical) { parent, name ->
                                parent
                                    .newByteChannel(
                                        name,
                                        setOf(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                                    )
                                    .use { channel ->
                                        val buffer = java.nio.ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                                        while (channel.read(buffer) >= 0) {
                                            buffer.flip()
                                            digest.update(buffer)
                                            buffer.clear()
                                        }
                                    }
                            }
                    ) {
                        is Refinement.Refined -> Unit
                        is Refinement.Rejected -> return read
                    }
                    entries[logical] =
                        EntryIdentity(
                            resolved.links,
                            root.relativize(resolved.path),
                            EntryContent.File(
                                ContentIdentity(digest.digest().joinToString("") { byte -> "%02x".format(byte) })
                            ),
                        )
                } else {
                    return rejected(logical, ModelInputFailureReason.UNSUPPORTED)
                }
                return Refinement.Refined(Unit)
            } catch (_: NoSuchFileException) {
                return rejected(logical, ModelInputFailureReason.TARGET_MISSING)
            } catch (_: IOException) {
                return rejected(logical, ModelInputFailureReason.UNREADABLE)
            } catch (_: SecurityException) {
                return rejected(logical, ModelInputFailureReason.UNREADABLE)
            }
        }

        /**
         * Open resolved input content relative to held directory descriptors. Every ancestor and the final entry refuse
         * links, so a concurrent replacement cannot redirect a read outside the proven target. Platforms without
         * descriptor-relative access fail closed.
         */
        private fun <T> readResolvedInput(
            target: Path,
            logical: Path,
            read: (SecureDirectoryStream<Path>, Path) -> T,
        ): Refinement<T, InstalledGradleModelCaptureFailure> {
            fun descend(
                parent: SecureDirectoryStream<Path>,
                index: Int,
            ): Refinement<T, InstalledGradleModelCaptureFailure> =
                if (index == target.nameCount - 1) Refinement.Refined(read(parent, target.getName(index)))
                else
                    parent.newDirectoryStream(target.getName(index), LinkOption.NOFOLLOW_LINKS).use { child ->
                        descend(child, index + 1)
                    }
            return try {
                Files.newDirectoryStream(target.root).use { filesystem ->
                    if (filesystem !is SecureDirectoryStream<Path>)
                        rejected(logical, ModelInputFailureReason.UNSUPPORTED)
                    else descend(filesystem, 0)
                }
            } catch (_: NoSuchFileException) {
                rejected(logical, ModelInputFailureReason.TARGET_MISSING)
            } catch (_: IOException) {
                rejected(logical, ModelInputFailureReason.UNREADABLE)
            } catch (_: SecurityException) {
                rejected(logical, ModelInputFailureReason.UNREADABLE)
            }
        }

        private fun selected(path: Path): Boolean {
            val name = path.fileName.toString()
            return name.endsWith(".gradle") ||
                name.endsWith(".gradle.kts") ||
                name == "gradle.properties" ||
                name == "gradle-daemon-jvm.properties" ||
                name == "gradle-wrapper.properties" ||
                (name.endsWith(".toml") && path.any { it.toString() == "gradle" }) ||
                path.any { it.toString() == "buildSrc" || it.toString() == "build-logic" }
        }

        private val modelInputDirectories = setOf("gradle", "buildSrc", "build-logic")
        private val excludedDirectories = setOf(".git", ".gradle", ".idea", ".kotlin", ".agent-turn", "build", "out")
    }
}

internal sealed interface InstalledGradleProperties {
    data object Absent : InstalledGradleProperties

    data class Present(val properties: java.util.Properties) : InstalledGradleProperties
}
