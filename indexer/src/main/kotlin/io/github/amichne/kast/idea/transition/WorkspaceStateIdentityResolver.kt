package io.github.amichne.kast.idea.transition

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal class WorkspaceStateIdentityResolver(
    workspaceRoot: Path,
    private val semanticEnvironmentIdentity: () -> String,
    private val indexingScopeIdentity: () -> String,
) {
    private val root = workspaceRoot.toAbsolutePath().normalize()

    fun resolve(): WorkspaceStateIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, "environment", semanticEnvironmentIdentity())
        update(digest, "scope", indexingScopeIdentity())
        semanticInputFiles().forEach { path ->
            val relative = root.relativize(path).toString().replace('\\', '/')
            update(digest, "path", relative)
            digest.update(Files.readAllBytes(path))
            digest.update(RECORD_SEPARATOR)
        }
        return WorkspaceStateIdentity(digest.digest().toHex())
    }

    private fun semanticInputFiles(): List<Path> = Files.walk(root).use { paths ->
        paths.filter { path ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && isSemanticInput(root.relativize(path))
        }.sorted().toList()
    }

    private fun isSemanticInput(relative: Path): Boolean {
        val segments = relative.map(Path::toString)
        if (segments.any { it in EXCLUDED_DIRECTORIES }) return false
        val fileName = relative.fileName?.toString().orEmpty()
        val extension = fileName.substringAfterLast('.', "")
        return extension in SOURCE_OR_BUILD_EXTENSIONS || fileName in SEMANTIC_FILES
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
        val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", ".idea", "node_modules", "out", "target")
        val SOURCE_OR_BUILD_EXTENSIONS = setOf("kt", "kts", "java", "gradle", "toml", "properties")
        val SEMANTIC_FILES = setOf("gradlew", "gradlew.bat", ".kastignore")
    }
}
