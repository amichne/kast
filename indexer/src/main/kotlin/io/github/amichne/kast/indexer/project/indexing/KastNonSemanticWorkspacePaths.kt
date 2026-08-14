package io.github.amichne.kast.indexer.project.indexing

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Closed traversal decision for one workspace directory. */
internal sealed interface KastWorkspaceDirectoryTraversal {
    data object Traverse : KastWorkspaceDirectoryTraversal

    data object Exclude : KastWorkspaceDirectoryTraversal
}

/**
 * Exact workspace paths proven to be generated or private runtime state.
 *
 * Direct Kast-owned roots are fixed by policy. A nested `target` root is
 * admitted only when its parent owns a `Cargo.toml`, so an unrelated source
 * directory with the same name is not guessed to be generated output.
 */
internal class KastNonSemanticWorkspacePaths private constructor(
    private val paths: Set<Path>,
) {
    /**
     * Proof transition: `Path -> KastWorkspaceDirectoryTraversal`.
     *
     * Establishes whether the normalized path is one of the exact admitted
     * non-semantic roots. The closed result is consumed by filesystem walkers;
     * raw path extraction is not required by callers.
     */
    fun traversalFor(directory: Path): KastWorkspaceDirectoryTraversal =
        if (directory.toAbsolutePath().normalize() in paths) {
            KastWorkspaceDirectoryTraversal.Exclude
        } else {
            KastWorkspaceDirectoryTraversal.Traverse
        }

    /** Raw extraction is permitted only at IntelliJ's VFS exclusion boundary. */
    fun pathsForVfsBoundary(): List<Path> = paths.sortedBy(Path::toString)

    companion object {
        /**
         * Proof transition: `Path -> KastNonSemanticWorkspacePaths`.
         *
         * Establishes absolute, normalized direct Kast roots and Cargo-owned
         * target roots without following symlinks or entering known generated
         * trees. Filesystem failures remain fail-closed at this discovery
         * boundary rather than manufacturing an incomplete proof.
         */
        fun discover(workspaceRoot: Path): KastNonSemanticWorkspacePaths {
            val root = workspaceRoot.toAbsolutePath().normalize()
            val admitted = KastDirectNonSemanticWorkspaceRoot.entries
                .mapTo(linkedSetOf()) { root.resolve(it.directoryName) }
            if (Files.isDirectory(root)) {
                Files.walkFileTree(
                    root,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            directory: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            if (directory == root) return FileVisitResult.CONTINUE
                            val normalized = directory.toAbsolutePath().normalize()
                            if (normalized in admitted || directory.isDiscoveryExcluded()) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            if (directory.isCargoTargetRoot()) {
                                admitted.add(normalized)
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            if (attributes.isRegularFile && file.fileName?.toString() == CARGO_MANIFEST) {
                                admitted.add(file.parent.resolve(RUST_TARGET_DIRECTORY).toAbsolutePath().normalize())
                            }
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            }
            return KastNonSemanticWorkspacePaths(admitted)
        }
    }
}

private enum class KastDirectNonSemanticWorkspaceRoot(
    val directoryName: String,
) {
    GRADLE_AND_KAST_CACHE(".gradle"),
    INTELLIJ_PLATFORM_CACHE(".intellijPlatform"),
    KAST_WORKSPACE_CACHE(".kast"),
    RUN_CONFIGURATION_STATE(".run"),
    PYTHON_VIRTUAL_ENVIRONMENT(".venv"),
    GENERATED_SITE("site"),
    NODE_MODULES("node_modules"),
    RUST_TARGET("target"),
}

private fun Path.isDiscoveryExcluded(): Boolean =
    fileName?.toString() in DISCOVERY_EXCLUDED_DIRECTORY_NAMES

private fun Path.isCargoTargetRoot(): Boolean =
    fileName?.toString() == RUST_TARGET_DIRECTORY &&
        parent?.resolve(CARGO_MANIFEST)?.let(Files::isRegularFile) == true

private const val CARGO_MANIFEST = "Cargo.toml"
private const val RUST_TARGET_DIRECTORY = "target"
private val DISCOVERY_EXCLUDED_DIRECTORY_NAMES = setOf(
    ".git",
    ".idea",
    ".kotlin",
    "build",
    "out",
)
