package io.github.amichne.kast.api.client

import java.nio.file.Path

data class GitRemote(
    val host: String,
    val owner: String,
    val repo: String,
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
        val process = ProcessBuilder("git", "config", "--get", "remote.origin.url")
            .directory(workspaceRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val remoteUrl = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0) parse(remoteUrl) else null
    }.getOrNull()
}
