package io.github.amichne.kast.server.mutation.coordination

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.ValidationException
import java.nio.file.Path

@JvmInline
internal value class MutationPathScope private constructor(
    private val paths: Set<Path>,
) {
    fun overlaps(other: MutationPathScope): Boolean = paths.any(other.paths::contains)

    companion object {
        fun parse(
            workspaceRoot: NormalizedPath,
            paths: Collection<String>,
        ): MutationPathScope {
            require(paths.isNotEmpty()) { "Mutation path scope must not be empty" }
            val root = workspaceRoot.toJavaPath()
            val relativePaths = paths.map { rawPath ->
                val path = NormalizedPath.of(Path.of(rawPath)).toJavaPath()
                if (!path.startsWith(root)) {
                    throw ValidationException(
                        message = "Mutation path scope must stay inside the exact workspace root",
                        details = mapOf(
                            "workspaceRoot" to workspaceRoot.value,
                            "filePath" to rawPath,
                        ),
                    )
                }
                root.relativize(path)
            }
            return MutationPathScope(relativePaths.toSet())
        }
    }
}
