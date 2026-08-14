package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.idea.SemanticPathContentIdentity
import io.github.amichne.kast.indexer.project.indexing.KastNonSemanticWorkspacePaths
import io.github.amichne.kast.indexer.project.indexing.KastWorkspaceDirectoryTraversal
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

@JvmInline
internal value class BuildSemanticInputIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Build-semantic input identity must not be blank" }
    }
}

internal object BuildSemanticInputPolicy {
    fun includes(relative: Path): Boolean {
        val segments = relative.map(Path::toString)
        if (segments.any(::isExcludedDirectory)) return false
        val fileName = relative.fileName?.toString().orEmpty()
        val extension = fileName.substringAfterLast('.', "")
        return fileName in ROOT_BUILD_FILES ||
            fileName.endsWith(".gradle.kts") ||
            extension == "gradle" ||
            isDependencyLock(segments, fileName, extension) ||
            (segments.any(BUILD_LOGIC_DIRECTORIES::contains) && extension in BUILD_LOGIC_EXTENSIONS) ||
            (segments.any { it == "gradle" } && extension in GRADLE_DIRECTORY_EXTENSIONS)
    }

    fun isExcludedDirectory(name: String): Boolean = name in EXCLUDED_DIRECTORIES

    private fun isDependencyLock(segments: List<String>, fileName: String, extension: String): Boolean =
        fileName == "gradle.lockfile" ||
            extension == "lockfile" && segments.windowed(2).any { (parent, child) ->
                parent == "gradle" && child == "dependency-locks"
            }

    private val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", "out")
    private val BUILD_LOGIC_DIRECTORIES = setOf("buildSrc", "build-logic")
    private val BUILD_LOGIC_EXTENSIONS = setOf("groovy", "java", "kt", "kts", "properties", "toml")
    private val GRADLE_DIRECTORY_EXTENSIONS = setOf("jar", "properties", "toml")
    private val ROOT_BUILD_FILES = setOf(
        "gradle.lockfile",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "local.properties",
        "settings.gradle",
        "settings.gradle.kts",
    )
}

internal class BuildSemanticInputIdentityResolver(
    buildSemanticRoot: Path,
    private val externalBuildSemanticFiles: () -> Collection<Path> = { emptyList() },
    private val isCancelled: () -> Boolean = { false },
) {
    private val root = buildSemanticRoot.toAbsolutePath().normalize()
    private val nonSemanticWorkspacePaths = KastNonSemanticWorkspacePaths.discover(root)

    fun resolve(): BuildSemanticInputIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        inputFiles().forEach { path ->
            SemanticPathContentIdentity.requireActive(isCancelled)
            update(digest, "path", stablePath(path))
            update(digest, "content", SemanticPathContentIdentity.file(path, isCancelled))
        }
        return BuildSemanticInputIdentity(digest.digest().toHex())
    }

    private fun inputFiles(): List<Path> {
        val inputs = linkedSetOf<Path>()
        if (Files.isDirectory(root)) {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                        SemanticPathContentIdentity.requireActive(isCancelled)
                        if (
                            nonSemanticWorkspacePaths.traversalFor(directory) ==
                            KastWorkspaceDirectoryTraversal.Exclude
                        ) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (
                            directory != root &&
                            root.relativize(directory).any { BuildSemanticInputPolicy.isExcludedDirectory(it.toString()) }
                        ) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        SemanticPathContentIdentity.requireActive(isCancelled)
                        if (attributes.isRegularFile && BuildSemanticInputPolicy.includes(root.relativize(file))) {
                            inputs.add(file.toAbsolutePath().normalize())
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        externalBuildSemanticFiles()
            .asSequence()
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .filter(Files::isRegularFile)
            .forEach(inputs::add)
        return inputs.sortedBy(::stablePath)
    }

    private fun stablePath(path: Path): String {
        val normalized = path.toAbsolutePath().normalize()
        return if (normalized.startsWith(root)) {
            root.relativize(normalized).toString().replace('\\', '/')
        } else {
            "\$EXTERNAL/${normalized.toString().replace('\\', '/')}"
        }
    }

    private fun update(digest: MessageDigest, label: String, value: String) {
        digest.update(label.toByteArray())
        digest.update(FIELD_SEPARATOR)
        digest.update(value.toByteArray())
        digest.update(RECORD_SEPARATOR)
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val FIELD_SEPARATOR = byteArrayOf(0)
        val RECORD_SEPARATOR = byteArrayOf(0xff.toByte())
    }
}
