package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.validation.FileHashing
import java.nio.file.Path

object ReadOnlyGitCommand {
    private const val OPTIONAL_LOCKS_ENVIRONMENT = "GIT_OPTIONAL_LOCKS"

    fun processBuilder(vararg arguments: String): ProcessBuilder =
        processBuilder(listOf("git", *arguments))

    fun processBuilder(command: List<String>): ProcessBuilder {
        require(command.isNotEmpty()) { "Git command must not be empty" }
        return ProcessBuilder(command).also { builder ->
            builder.environment()[OPTIONAL_LOCKS_ENVIRONMENT] = "0"
        }
    }
}

data class GitRemote(
    val host: String,
    val owner: String,
    val repo: String,
)

data class GitWorkspace(
    val toplevel: Path,
    val commonDir: Path,
    val gitDir: Path,
    val remote: GitRemote?,
)

object GitRemoteParser {
    private val sshRemote = Regex("^git@([^:]+):([^/]+)/(.+?)(?:\\.git)?$")
    private val httpsRemote = Regex("^https://([^/]+)/([^/]+)/(.+?)(?:\\.git)?$")

    fun parse(remoteUrl: String): GitRemote? = listOf(sshRemote, httpsRemote)
        .asSequence()
        .mapNotNull { pattern -> pattern.matchEntire(remoteUrl.trim()) }
        .map { match ->
            GitRemote(
                host = match.groupValues[1],
                owner = match.groupValues[2],
                repo = match.groupValues[3],
            )
        }
        .firstOrNull()

    fun origin(workspaceRoot: Path): GitRemote? = runCatching {
        val process = ReadOnlyGitCommand.processBuilder("config", "--get", "remote.origin.url")
            .directory(workspaceRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val remoteUrl = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0) parse(remoteUrl) else null
    }.getOrNull()
}

object GitWorkspaceResolver {
    fun discover(workspaceRoot: Path): GitWorkspace? = runCatching {
        val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
        val toplevel = gitPath(normalizedRoot, "rev-parse", "--show-toplevel") ?: return null
        val commonDir = gitPath(normalizedRoot, "rev-parse", "--git-common-dir") ?: return null
        val gitDir = gitPath(normalizedRoot, "rev-parse", "--git-dir") ?: return null
        GitWorkspace(
            toplevel = toplevel,
            commonDir = commonDir,
            gitDir = gitDir,
            remote = null,
        )
    }.getOrNull()

    private fun gitPath(workspaceRoot: Path, vararg args: String): Path? =
        gitOutput(workspaceRoot, *args)
            ?.let(Path::of)
            ?.let { path ->
                if (path.isAbsolute) path else workspaceRoot.resolve(path)
            }
            ?.toAbsolutePath()
            ?.normalize()

    private fun gitOutput(workspaceRoot: Path, vararg args: String): String? = runCatching {
        val process = ReadOnlyGitCommand.processBuilder(*args)
            .directory(workspaceRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        output.takeIf { process.waitFor() == 0 && it.isNotBlank() }
    }.getOrNull()
}

fun gitWorktreeHash(toplevel: Path, gitDir: Path): String = FileHashing.sha256(
    listOf(
        toplevel.toAbsolutePath().normalize().toString(),
        gitDir.toAbsolutePath().normalize().toString(),
    ).joinToString(separator = "\n"),
).take(12)

fun gitCommonDirHash(commonDir: Path): String = FileHashing.sha256(
    commonDir.toAbsolutePath().normalize().toString(),
).take(12)
