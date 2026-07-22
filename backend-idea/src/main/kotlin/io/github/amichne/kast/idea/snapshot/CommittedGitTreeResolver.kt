package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import java.nio.file.Path

data class CommittedGitTree(
    val treeOid: GitObjectId,
    val files: Map<String, GitObjectId>,
)

object CommittedGitTreeResolver {
    fun resolve(workspaceRoot: Path): CommittedGitTree? {
        if (git(workspaceRoot, "status", "--porcelain", "--untracked-files=normal")?.isNotEmpty() != false) return null
        val treeOid = git(workspaceRoot, "rev-parse", "HEAD^{tree}")?.let(GitObjectId::parse) ?: return null
        val rawManifest = gitBytes(workspaceRoot, "ls-tree", "-r", "-z", "HEAD") ?: return null
        val files = rawManifest.toString(Charsets.UTF_8)
            .split('\u0000')
            .asSequence()
            .filter(String::isNotEmpty)
            .associate { record ->
                val (metadata, path) = record.split('\t', limit = 2)
                val fields = metadata.split(' ')
                require(fields.size == 3 && fields[1] == "blob") { "Git tree contains an unsupported entry" }
                path to GitObjectId.parse(fields[2])
            }
            .toSortedMap()
        return CommittedGitTree(treeOid, files)
    }

    private fun git(workspaceRoot: Path, vararg arguments: String): String? =
        gitBytes(workspaceRoot, *arguments)?.toString(Charsets.UTF_8)?.trim()

    private fun gitBytes(workspaceRoot: Path, vararg arguments: String): ByteArray? = runCatching {
        val process = ProcessBuilder("git", *arguments)
            .directory(workspaceRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.use { it.readAllBytes() }
        output.takeIf { process.waitFor() == 0 }
    }.getOrNull()
}
