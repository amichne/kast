package io.github.amichne.kast.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface GitRunner {
    /** Run `git clone --depth=1 <url> <dest>`. Throws on failure. */
    fun cloneShallow(url: String, dest: Path)
}

internal object SystemGitRunner : GitRunner {
    override fun cloneShallow(url: String, dest: Path) {
        val process = ProcessBuilder("git", "clone", "--depth=1", url, dest.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw CliFailure(
                code = "DEMO_GEN_CLONE_FAILED",
                message = "git clone failed with exit code $exitCode for $url",
                details = mapOf(
                    "url" to url,
                    "exitCode" to exitCode.toString(),
                    "output" to output.take(2000),
                ),
            )
        }
    }
}

internal fun interface GitRemoteResolver {
    fun resolveOriginUrl(workingDir: Path): String?
}

internal object SystemGitRemoteResolver : GitRemoteResolver {
    override fun resolveOriginUrl(workingDir: Path): String? {
        val process = try {
            ProcessBuilder("git", "remote", "get-url", "origin")
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return null
        }
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        return try {
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) output else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}

internal object RepoIngestion {
    /** Validates the repo URL is nonblank and clones into a temp dir. Returns workspace root. */
    fun clone(repoUrl: String, processRunner: GitRunner = SystemGitRunner): Path {
        if (repoUrl.isBlank()) {
            throw CliFailure(
                code = "DEMO_GEN_INVALID_URL",
                message = "Repository URL must not be blank",
            )
        }
        val tempDir = Files.createTempDirectory("kast-demo-generate-")
        registerCleanup(tempDir)
        processRunner.cloneShallow(repoUrl, tempDir)
        return tempDir
    }

    /** Bootstraps the workspace through CliService.workspaceEnsure(). */
    suspend fun bootstrap(
        workspaceRoot: Path,
        cliService: CliService,
        backendName: String? = "standalone",
        acceptIndexing: Boolean = false,
    ): WorkspaceEnsureResult {
        val opts = RuntimeCommandOptions(
            workspaceRoot = workspaceRoot,
            backendName = backendName,
            waitTimeoutMillis = 180_000L,
            acceptIndexing = acceptIndexing,
        )
        return cliService.workspaceEnsure(opts)
    }

    private fun registerCleanup(tempDir: Path) {
        val cleaned = AtomicBoolean(false)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                if (!cleaned.compareAndSet(false, true)) return@Thread
                runCatching {
                    if (!Files.exists(tempDir)) return@runCatching
                    Files.walk(tempDir).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { path ->
                            runCatching { Files.deleteIfExists(path) }
                        }
                    }
                }
            },
        )
    }
}
