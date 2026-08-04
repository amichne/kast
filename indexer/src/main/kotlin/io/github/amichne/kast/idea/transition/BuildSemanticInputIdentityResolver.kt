package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.idea.SemanticPathContentIdentity
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

internal class BuildSemanticInputIdentityResolver(
    buildSemanticRoot: Path,
    private val externalBuildSemanticFiles: () -> Collection<Path> = { emptyList() },
    private val isCancelled: () -> Boolean = { false },
) {
    private val root = buildSemanticRoot.toAbsolutePath().normalize()

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
                        if (directory != root && root.relativize(directory).any { it.toString() in EXCLUDED_DIRECTORIES }) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        SemanticPathContentIdentity.requireActive(isCancelled)
                        if (attributes.isRegularFile && isBuildSemanticInput(root.relativize(file))) {
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

    private fun isBuildSemanticInput(relative: Path): Boolean {
        val segments = relative.map(Path::toString)
        val fileName = relative.fileName?.toString().orEmpty()
        val extension = fileName.substringAfterLast('.', "")
        return fileName in ROOT_BUILD_FILES ||
            fileName.endsWith(".gradle.kts") ||
            extension == "gradle" ||
            (segments.any { it == "buildSrc" || it == "build-logic" } && extension in BUILD_LOGIC_EXTENSIONS) ||
            (segments.any { it == "gradle" } && extension in GRADLE_DIRECTORY_EXTENSIONS)
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
        val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", "out")
        val BUILD_LOGIC_EXTENSIONS = setOf("java", "kt", "kts", "properties", "toml")
        val GRADLE_DIRECTORY_EXTENSIONS = setOf("jar", "properties", "toml")
        val ROOT_BUILD_FILES = setOf(
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "settings.gradle",
            "settings.gradle.kts",
        )
    }
}
