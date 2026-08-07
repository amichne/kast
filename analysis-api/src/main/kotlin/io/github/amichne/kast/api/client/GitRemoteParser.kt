package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.validation.FileHashing
import java.nio.file.Path

internal class ReadOnlyGitProcessEnvironment private constructor(
    private val variables: Map<String, String>,
) {
    internal fun applyTo(builder: ProcessBuilder): ProcessBuilder = builder.also {
        builder.environment().clear()
        builder.environment().putAll(variables)
    }

    companion object {
        private const val OPTIONAL_LOCKS = "GIT_OPTIONAL_LOCKS"
        private val REPOSITORY_SELECTORS = setOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_COMMON_DIR",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_CEILING_DIRECTORIES",
            "GIT_DISCOVERY_ACROSS_FILESYSTEM",
        )

        /**
         * Proof transition: `Map<String, String> -> ReadOnlyGitProcessEnvironment`.
         *
         * Removes every inherited repository-selection variable before a Git
         * child process can observe it and disables optional Git locks. The
         * returned environment therefore cannot redirect a read away from the
         * caller-selected working directory. Raw environment entries are
         * accepted only from the JVM process boundary and extracted only into
         * [ProcessBuilder] at the operating-system boundary.
         */
        fun fromInherited(inherited: Map<String, String>): ReadOnlyGitProcessEnvironment =
            ReadOnlyGitProcessEnvironment(
                inherited
                    .filterKeys { variable -> variable !in REPOSITORY_SELECTORS }
                    .plus(OPTIONAL_LOCKS to "0"),
            )
    }
}

class ReadOnlyGitCommand private constructor(
    private val arguments: List<String>,
) {
    /** Raw command extraction is confined to the operating-system process boundary. */
    fun processBuilder(): ProcessBuilder = processBuilder(
        ReadOnlyGitProcessEnvironment.fromInherited(System.getenv()),
    )

    internal fun processBuilder(environment: ReadOnlyGitProcessEnvironment): ProcessBuilder =
        environment.applyTo(ProcessBuilder(listOf("git") + arguments))

    companion object {
        fun originRemote(): ReadOnlyGitCommand = command("config", "--get", "remote.origin.url")

        fun workspaceTopLevel(): ReadOnlyGitCommand = command("rev-parse", "--show-toplevel")

        fun commonGitDirectory(): ReadOnlyGitCommand = command("rev-parse", "--git-common-dir")

        fun exactGitDirectory(): ReadOnlyGitCommand = command("rev-parse", "--git-dir")

        fun workspacePrefix(): ReadOnlyGitCommand = command("rev-parse", "--show-prefix")

        fun linkedWorktreeRegistration(): ReadOnlyGitCommand = command(
            "rev-parse",
            "--path-format=absolute",
            "--show-toplevel",
            "--absolute-git-dir",
            "--git-common-dir",
        )

        /**
         * Proof transition: `List<String> -> ReadOnlyGitCommand`.
         *
         * Places each opaque marker path only as the operand of `--git-path`
         * inside a fixed `rev-parse` query. The returned command cannot select
         * a mutating Git operation; raw arguments are extracted only by
         * [processBuilder] at the operating-system boundary.
         */
        fun transitionMarkerPaths(markerPaths: List<String>): ReadOnlyGitCommand = command(
            "rev-parse",
            "--path-format=absolute",
            "--show-toplevel",
            "--absolute-git-dir",
            "--git-common-dir",
            *markerPaths.flatMap { path -> listOf("--git-path", path) }.toTypedArray(),
        )

        fun workspaceStatus(): ReadOnlyGitCommand = command(
            "status",
            "--porcelain",
            "--untracked-files=normal",
        )

        fun ignoredKotlinSources(): ReadOnlyGitCommand = command(
            "ls-files",
            "--others",
            "--ignored",
            "--exclude-standard",
            "-z",
            "--",
            "*.kt",
        )

        /**
         * Proof transition: `NonBlankString -> ReadOnlyGitCommand`.
         *
         * Places the already-typed revision expression after
         * `--end-of-options` in a fixed `rev-parse --verify` query. The output
         * is therefore a read-only command even when the operand begins with a
         * dash; raw extraction is confined to [processBuilder].
         */
        fun resolveTree(expression: NonBlankString): ReadOnlyGitCommand = command(
            "rev-parse",
            "--verify",
            "--end-of-options",
            expression.value,
        )

        /**
         * Proof transition: `String -> ReadOnlyGitCommand`.
         *
         * Places the caller's already-proven object name after
         * `--end-of-options` in a fixed recursive tree read. The string is
         * extracted from its owning domain type only at this Git boundary.
         */
        fun treeManifest(objectName: String): ReadOnlyGitCommand = command(
            "ls-tree",
            "--full-tree",
            "-r",
            "-z",
            "--end-of-options",
            objectName,
        )

        fun currentBranch(): ReadOnlyGitCommand = command(
            "symbolic-ref",
            "--quiet",
            "--short",
            "HEAD",
        )

        /**
         * Proof transition: `String -> ReadOnlyGitCommand`.
         *
         * Places the caller's already-proven object ID in the object operand
         * of a fixed blob read. The raw string exists only at this Git process
         * boundary and cannot choose an operation or option.
         */
        fun blob(objectId: String): ReadOnlyGitCommand = command(
            "cat-file",
            "blob",
            "--end-of-options",
            objectId,
        )

        private fun command(vararg arguments: String): ReadOnlyGitCommand =
            ReadOnlyGitCommand(arguments.toList())
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
        val process = ReadOnlyGitCommand.originRemote().processBuilder()
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
        val toplevel = gitPath(normalizedRoot, ReadOnlyGitCommand.workspaceTopLevel()) ?: return null
        val commonDir = gitPath(normalizedRoot, ReadOnlyGitCommand.commonGitDirectory()) ?: return null
        val gitDir = gitPath(normalizedRoot, ReadOnlyGitCommand.exactGitDirectory()) ?: return null
        GitWorkspace(
            toplevel = toplevel,
            commonDir = commonDir,
            gitDir = gitDir,
            remote = null,
        )
    }.getOrNull()

    private fun gitPath(workspaceRoot: Path, command: ReadOnlyGitCommand): Path? =
        gitOutput(workspaceRoot, command)
            ?.let(Path::of)
            ?.let { path ->
                if (path.isAbsolute) path else workspaceRoot.resolve(path)
            }
            ?.toAbsolutePath()
            ?.normalize()

    private fun gitOutput(workspaceRoot: Path, command: ReadOnlyGitCommand): String? = runCatching {
        val process = command.processBuilder()
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
