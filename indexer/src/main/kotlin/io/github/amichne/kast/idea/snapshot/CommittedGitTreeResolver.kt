package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.api.client.ReadOnlyGitCommand
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.GitObjectIdFailure
import io.github.amichne.kast.indexstore.snapshot.GitObjectIdResolution
import io.github.amichne.kast.indexstore.snapshot.RepositoryRelativePath
import io.github.amichne.kast.indexstore.snapshot.RepositoryRelativePathFailure
import io.github.amichne.kast.indexstore.snapshot.RepositoryRelativePathResolution

data class CommittedGitTree(
    val treeOid: GitObjectId,
    val files: Map<RepositoryRelativePath, GitObjectId>,
)

sealed interface CommittedGitTreeFailure {
    data object WorkspaceHasChanges : CommittedGitTreeFailure

    data object WorkspaceHasIgnoredKotlinSources : CommittedGitTreeFailure

    data class GitReadFailed(val request: GitTreeReadRequest) : CommittedGitTreeFailure

    data class UnsupportedTreeEntry(val record: String) : CommittedGitTreeFailure

    data class InvalidRepositoryPath(
        val path: String,
        val failure: RepositoryRelativePathFailure,
    ) : CommittedGitTreeFailure

    data class InvalidGitObjectId(
        val value: String,
        val failure: GitObjectIdFailure,
    ) : CommittedGitTreeFailure
}

sealed interface CommittedGitTreeResolution {
    data class Resolved(val tree: CommittedGitTree) : CommittedGitTreeResolution

    data class Unavailable(val failure: CommittedGitTreeFailure) : CommittedGitTreeResolution
}

sealed interface GitTreeReadRequest {
    data object Status : GitTreeReadRequest

    data object IgnoredKotlinSources : GitTreeReadRequest

    data object WorkspacePrefix : GitTreeReadRequest

    data class TreeOid(val expression: NonBlankString) : GitTreeReadRequest

    data class TreeManifest(val oid: GitObjectId) : GitTreeReadRequest
}

private sealed interface GitReadResult {
    data class Completed(val output: ByteArray) : GitReadResult

    data object Failed : GitReadResult
}

object CommittedGitTreeResolver {
    /**
     * Proof transition: `NormalizedPath -> CommittedGitTreeResolution`.
     *
     * A resolved tree proves that the workspace has no tracked, untracked, or
     * ignored Kotlin changes and carries canonical repository-relative paths
     * bound to one committed Git tree. Unavailability is finite
     * [CommittedGitTreeFailure] data. Raw command arguments and bytes exist
     * only inside this Git process boundary.
     */
    fun resolve(workspaceRoot: NormalizedPath): CommittedGitTreeResolution {
        val status = read(workspaceRoot, GitTreeReadRequest.Status)
        if (status !is GitReadResult.Completed) {
            return unavailable(GitTreeReadRequest.Status)
        }
        if (status.output.isNotEmpty()) {
            return CommittedGitTreeResolution.Unavailable(CommittedGitTreeFailure.WorkspaceHasChanges)
        }

        val ignored = read(workspaceRoot, GitTreeReadRequest.IgnoredKotlinSources)
        if (ignored !is GitReadResult.Completed) {
            return unavailable(GitTreeReadRequest.IgnoredKotlinSources)
        }
        if (ignored.output.isNotEmpty()) {
            return CommittedGitTreeResolution.Unavailable(
                CommittedGitTreeFailure.WorkspaceHasIgnoredKotlinSources,
            )
        }

        val prefix = read(workspaceRoot, GitTreeReadRequest.WorkspacePrefix)
        if (prefix !is GitReadResult.Completed) {
            return unavailable(GitTreeReadRequest.WorkspacePrefix)
        }
        val treeExpression = NonBlankString(prefix.output.toString(Charsets.UTF_8)
            .removeSuffix("\n")
            .removeSuffix("\r")
            .removeSuffix("/")
            .takeIf(String::isNotEmpty)
            ?.let { "HEAD:$it" }
            ?: "HEAD^{tree}")
        val treeOidRequest = GitTreeReadRequest.TreeOid(treeExpression)
        val treeOidRead = read(workspaceRoot, treeOidRequest)
        if (treeOidRead !is GitReadResult.Completed) {
            return unavailable(treeOidRequest)
        }
        val rawTreeOid = treeOidRead.output.toString(Charsets.UTF_8).trim()
        val treeOid = when (val resolution = GitObjectId.resolve(rawTreeOid)) {
            is GitObjectIdResolution.Resolved -> resolution.objectId
            is GitObjectIdResolution.Rejected -> return CommittedGitTreeResolution.Unavailable(
                CommittedGitTreeFailure.InvalidGitObjectId(rawTreeOid, resolution.failure),
            )
        }
        val manifestRequest = GitTreeReadRequest.TreeManifest(treeOid)
        val manifest = read(workspaceRoot, manifestRequest)
        if (manifest !is GitReadResult.Completed) {
            return unavailable(manifestRequest)
        }
        return parseManifest(treeOid, manifest.output)
    }

    private fun parseManifest(
        treeOid: GitObjectId,
        rawManifest: ByteArray,
    ): CommittedGitTreeResolution {
        val files = sortedMapOf<RepositoryRelativePath, GitObjectId>()
        rawManifest.toString(Charsets.UTF_8)
            .split('\u0000')
            .asSequence()
            .filter(String::isNotEmpty)
            .forEach { record ->
                val fields = record.split('\t', limit = 2)
                if (fields.size != 2) {
                    return CommittedGitTreeResolution.Unavailable(
                        CommittedGitTreeFailure.UnsupportedTreeEntry(record),
                    )
                }
                val metadata = fields[0].split(' ')
                if (metadata.size != 3 || metadata[1] != "blob") {
                    return CommittedGitTreeResolution.Unavailable(
                        CommittedGitTreeFailure.UnsupportedTreeEntry(record),
                    )
                }
                val path = when (val resolution = RepositoryRelativePath.resolve(fields[1])) {
                    is RepositoryRelativePathResolution.Resolved -> resolution.path
                    is RepositoryRelativePathResolution.Rejected -> return CommittedGitTreeResolution.Unavailable(
                        CommittedGitTreeFailure.InvalidRepositoryPath(fields[1], resolution.failure),
                    )
                }
                if (SourceIndexFilePolicy.isEligibleWorkspaceRelative(path.value)) {
                    val objectId = when (val resolution = GitObjectId.resolve(metadata[2])) {
                        is GitObjectIdResolution.Resolved -> resolution.objectId
                        is GitObjectIdResolution.Rejected -> return CommittedGitTreeResolution.Unavailable(
                            CommittedGitTreeFailure.InvalidGitObjectId(metadata[2], resolution.failure),
                        )
                    }
                    files[path] = objectId
                }
            }
        return CommittedGitTreeResolution.Resolved(CommittedGitTree(treeOid, files))
    }

    private fun unavailable(request: GitTreeReadRequest): CommittedGitTreeResolution =
        CommittedGitTreeResolution.Unavailable(CommittedGitTreeFailure.GitReadFailed(request))

    private fun read(
        workspaceRoot: NormalizedPath,
        request: GitTreeReadRequest,
    ): GitReadResult = runCatching {
        val arguments = when (request) {
            GitTreeReadRequest.Status ->
                listOf("status", "--porcelain", "--untracked-files=normal")
            GitTreeReadRequest.IgnoredKotlinSources ->
                listOf("ls-files", "--others", "--ignored", "--exclude-standard", "-z", "--", "*.kt")
            GitTreeReadRequest.WorkspacePrefix -> listOf("rev-parse", "--show-prefix")
            is GitTreeReadRequest.TreeOid -> listOf("rev-parse", request.expression.value)
            is GitTreeReadRequest.TreeManifest ->
                listOf("ls-tree", "--full-tree", "-r", "-z", request.oid.value)
        }
        val process = ReadOnlyGitCommand.processBuilder(*arguments.toTypedArray())
            .directory(workspaceRoot.toJavaPath().toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.use { it.readAllBytes() }
        if (process.waitFor() == 0) GitReadResult.Completed(output) else GitReadResult.Failed
    }.getOrDefault(GitReadResult.Failed)
}
