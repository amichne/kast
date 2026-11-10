package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Canonical worktree and Git metadata paths survive unchanged inside the guest. */
internal class ContainerWorkspace
private constructor(val root: Path, val gitDirectory: Path, val commonDirectory: Path) {
    val mounts: List<Path> =
        listOf(root, gitDirectory, commonDirectory).distinct().filter { path ->
            listOf(root, gitDirectory, commonDirectory).none { other -> path != other && path.startsWith(other) }
        }

    fun key(name: ContainerWorkspaceName, architecture: ContainerArchitecture): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$root\u0000${name.value}\u0000${architecture.oci}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(KEY_LENGTH)

    companion object {
        private const val KEY_LENGTH = 24
        private const val MAXIMUM_GIT_PATH_BYTES = 4096

        fun discover(directory: Path): Refinement<ContainerWorkspace, ContainerFailure> =
            try {
                val root =
                    generateSequence(directory.toRealPath()) { it.parent }
                        .firstOrNull { Files.exists(it.resolve(".git"), NOFOLLOW_LINKS) }
                if (root == null || root.parent == null) Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
                else readGit(root)
            } catch (_: IOException) {
                Refinement.Rejected(ContainerFailure.WORKSPACE_UNAVAILABLE)
            } catch (_: InvalidPathException) {
                Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
            }

        private fun readGit(root: Path): Refinement<ContainerWorkspace, ContainerFailure> {
            val entry = root.resolve(".git")
            if (Files.isSymbolicLink(entry)) return Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
            val git =
                if (Files.isDirectory(entry)) entry
                else {
                    val line = readPath(entry)
                    if (!line.startsWith("gitdir: ")) return Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
                    root.resolve(line.removePrefix("gitdir: ")).normalize()
                }
            val commonEntry = git.resolve("commondir")
            val common =
                if (Files.exists(commonEntry, NOFOLLOW_LINKS)) {
                    val path = readPath(commonEntry)
                    if (path.isBlank()) return Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
                    git.resolve(path).normalize()
                } else git
            val paths = listOf(root, git, common)
            return if (paths.any { !mountable(it) || it.toRealPath() != it } || !gitMetadata(git, common))
                Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
            else Refinement.Refined(ContainerWorkspace(root, git, common))
        }

        private fun gitMetadata(git: Path, common: Path): Boolean =
            Files.isRegularFile(git.resolve("HEAD")) && Files.isDirectory(common.resolve("objects"))

        private fun readPath(path: Path): String =
            if (Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= MAXIMUM_GIT_PATH_BYTES)
                Files.readString(path).trimEnd('\n', '\r')
            else ""

        private fun mountable(path: Path): Boolean =
            path.isAbsolute && path.parent != null && path.toString().none { it == ',' || it == '\n' || it == '\r' }
    }
}

internal enum class ContainerArchitecture(val oci: String, val vm: String) {
    ARM64("arm64", "aarch64"),
    AMD64("amd64", "x86_64");

    companion object {
        fun parse(machine: String): Refinement<ContainerArchitecture, ContainerFailure> =
            when (machine) {
                "aarch64",
                "arm64" -> Refinement.Refined(ARM64)
                "amd64",
                "x86_64" -> Refinement.Refined(AMD64)
                else -> Refinement.Rejected(ContainerFailure.UNSUPPORTED_PLATFORM)
            }
    }
}

internal enum class ContainerFailure(val guidance: String, val exit: Int = 1) {
    INVALID_COMMAND("Use run -- COMMAND [ARGS] with a nonempty command."),
    INVALID_NAME("Use 1 to 48 letters, digits, underscores, or hyphens for --name."),
    INVALID_IMAGE_REFERENCE("Use a local sha256 image ID or a registry reference with @sha256 digest."),
    INVALID_WORKSPACE("Select an ordinary Git worktree with canonical Git metadata paths and no commas or newlines."),
    WORKSPACE_UNAVAILABLE("Cannot read the selected Git worktree."),
    UNSUPPORTED_PLATFORM("This runner supports native ARM64 and x86_64 on macOS."),
    MISSING_RUNTIME("Install Colima and put colima and its limactl dependency on PATH."),
    INVALID_HOME("HOME must name an existing absolute directory."),
    RUNTIME_REJECTED("Profile kast-native must use containerd on the native architecture."),
    RUNTIME_STOPPED("Profile kast-native is stopped or absent. Start a workspace first."),
    PROCESS_START_FAILED("Cannot start the selected runtime command."),
    PROCESS_FAILED("The runtime command exited with an error."),
    OUTPUT_REJECTED("The runtime returned an invalid document."),
    OUTPUT_LIMIT_EXCEEDED("The runtime output exceeded the capture limit."),
    OUTPUT_ENCODING_REJECTED("The runtime output was invalid UTF-8."),
    OUTPUT_CAPTURE_FAILED("Cannot read the runtime output."),
    IMAGE_INSPECT_EMPTY("Image inspection returned no document. Make sure that a native Kast v3 image is available."),
    IMAGE_INSPECT_DOCUMENT_REJECTED("Image inspection returned an invalid native image document."),
    IMAGE_REJECTED("Select a native Kast v3 image with --image. Source builds use mise exec -- kast-container build."),
    WORKSPACE_CONFLICT(
        "The retained workspace differs from these inputs. Select another --name or restore its inputs."
    ),
    PATH_NOT_SHARED(
        "The runtime cannot see this worktree or its Git metadata. Configure Colima mounts for these paths."
    ),
    LOCK_UNAVAILABLE("Cannot lock the container coordinator under HOME/.cache/kast/container."),
    INTERRUPTED("Container startup was interrupted.", PublicQuerySignal.INTERRUPT.exit),
    TERMINATED("Container startup was terminated.", PublicQuerySignal.TERMINATE.exit),
}
