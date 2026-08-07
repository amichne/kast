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
import java.nio.charset.CharacterCodingException

data class CommittedGitTree(
    val treeOid: GitObjectId,
    val files: Map<RepositoryRelativePath, GitObjectId>,
)

sealed interface CommittedGitTreeFailure {
    data object WorkspaceHasChanges : CommittedGitTreeFailure

    data class WorkspaceHasIgnoredKotlinSources(val path: RepositoryRelativePath) : CommittedGitTreeFailure

    data class GitReadFailed(val request: GitTreeReadRequest) : CommittedGitTreeFailure

    data class InvalidGitOutput(val request: GitTreeReadRequest) : CommittedGitTreeFailure

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

@JvmInline
private value class GitOutputText private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `(ByteArray, GitTreeReadRequest) -> GitOutputTextResolution`.
         *
         * Refines raw process bytes into lossless UTF-8 text or one finite
         * request-bound failure. Replacement decoding is never admitted.
         */
        fun resolve(
            rawOutput: ByteArray,
            request: GitTreeReadRequest,
        ): GitOutputTextResolution = try {
            GitOutputTextResolution.Decoded(
                GitOutputText(rawOutput.decodeToString(throwOnInvalidSequence = true)),
            )
        } catch (_: CharacterCodingException) {
            GitOutputTextResolution.Rejected(CommittedGitTreeFailure.InvalidGitOutput(request))
        }
    }
}

private sealed interface GitOutputTextResolution {
    data class Decoded(val text: GitOutputText) : GitOutputTextResolution

    data class Rejected(
        val failure: CommittedGitTreeFailure.InvalidGitOutput,
    ) : GitOutputTextResolution
}

private sealed interface IgnoredKotlinSourceAuthority {
    data object Admitted : IgnoredKotlinSourceAuthority

    data class Rejected(val failure: CommittedGitTreeFailure) : IgnoredKotlinSourceAuthority
}

internal object CommittedGitTreeManifest {
    /**
     * Proof transition: `(GitObjectId, ByteArray) -> CommittedGitTreeResolution`.
     *
     * Publishes a typed tree only after the complete NUL-delimited manifest is
     * losslessly decoded and every eligible entry carries a canonical path and
     * object ID. Invalid bytes fail the whole manifest before path identity can
     * collapse through replacement characters.
     */
    fun resolve(
        treeOid: GitObjectId,
        rawManifest: ByteArray,
    ): CommittedGitTreeResolution {
        val request = GitTreeReadRequest.TreeManifest(treeOid)
        val manifest = when (val resolution = GitOutputText.resolve(rawManifest, request)) {
            is GitOutputTextResolution.Decoded -> resolution.text.value
            is GitOutputTextResolution.Rejected -> return CommittedGitTreeResolution.Unavailable(resolution.failure)
        }
        val files = sortedMapOf<RepositoryRelativePath, GitObjectId>()
        manifest.split('\u0000').asSequence().filter(String::isNotEmpty).forEach { record ->
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
}

object CommittedGitTreeResolver {
    /**
     * Proof transition: `NormalizedPath -> CommittedGitTreeResolution`.
     *
     * A resolved tree proves that the workspace has no tracked, untracked, or
     * ignored source-index-eligible Kotlin changes and carries canonical
     * repository-relative paths bound to one committed Git tree.
     * Unavailability is finite [CommittedGitTreeFailure] data. Raw command
     * arguments and bytes exist only inside this Git process boundary.
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
        when (val authority = ignoredKotlinSourceAuthority(ignored.output)) {
            IgnoredKotlinSourceAuthority.Admitted -> Unit
            is IgnoredKotlinSourceAuthority.Rejected ->
                return CommittedGitTreeResolution.Unavailable(authority.failure)
        }

        val prefix = read(workspaceRoot, GitTreeReadRequest.WorkspacePrefix)
        if (prefix !is GitReadResult.Completed) {
            return unavailable(GitTreeReadRequest.WorkspacePrefix)
        }
        val prefixText = when (
            val resolution = GitOutputText.resolve(prefix.output, GitTreeReadRequest.WorkspacePrefix)
        ) {
            is GitOutputTextResolution.Decoded -> resolution.text.value
            is GitOutputTextResolution.Rejected -> return CommittedGitTreeResolution.Unavailable(resolution.failure)
        }
        val treeExpression = NonBlankString(prefixText
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
        val rawTreeOid = when (val resolution = GitOutputText.resolve(treeOidRead.output, treeOidRequest)) {
            is GitOutputTextResolution.Decoded -> resolution.text.value.trim()
            is GitOutputTextResolution.Rejected -> return CommittedGitTreeResolution.Unavailable(resolution.failure)
        }
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
        return CommittedGitTreeManifest.resolve(treeOid, manifest.output)
    }

    /**
     * Proof transition: `ByteArray -> IgnoredKotlinSourceAuthority`.
     *
     * Admits snapshot reuse only after every NUL-delimited Git path is proven
     * outside [SourceIndexFilePolicy]. Invalid Git output and eligible ignored
     * sources remain finite rejection data.
     */
    private fun ignoredKotlinSourceAuthority(rawOutput: ByteArray): IgnoredKotlinSourceAuthority {
        val output = when (
            val resolution = GitOutputText.resolve(rawOutput, GitTreeReadRequest.IgnoredKotlinSources)
        ) {
            is GitOutputTextResolution.Decoded -> resolution.text.value
            is GitOutputTextResolution.Rejected -> return IgnoredKotlinSourceAuthority.Rejected(resolution.failure)
        }
        output.split('\u0000').asSequence().filter(String::isNotEmpty).forEach { value ->
            val path = when (val resolution = RepositoryRelativePath.resolve(value)) {
                is RepositoryRelativePathResolution.Resolved -> resolution.path
                is RepositoryRelativePathResolution.Rejected -> return IgnoredKotlinSourceAuthority.Rejected(
                    CommittedGitTreeFailure.InvalidRepositoryPath(value, resolution.failure),
                )
            }
            if (SourceIndexFilePolicy.isEligibleWorkspaceRelative(path.value)) {
                return IgnoredKotlinSourceAuthority.Rejected(
                    CommittedGitTreeFailure.WorkspaceHasIgnoredKotlinSources(path),
                )
            }
        }
        return IgnoredKotlinSourceAuthority.Admitted
    }

    private fun unavailable(request: GitTreeReadRequest): CommittedGitTreeResolution =
        CommittedGitTreeResolution.Unavailable(CommittedGitTreeFailure.GitReadFailed(request))

    private fun read(
        workspaceRoot: NormalizedPath,
        request: GitTreeReadRequest,
    ): GitReadResult = runCatching {
        val command = when (request) {
            GitTreeReadRequest.Status ->
                ReadOnlyGitCommand.workspaceStatus()
            GitTreeReadRequest.IgnoredKotlinSources ->
                ReadOnlyGitCommand.ignoredKotlinSources()
            GitTreeReadRequest.WorkspacePrefix ->
                ReadOnlyGitCommand.workspacePrefix()
            is GitTreeReadRequest.TreeOid ->
                ReadOnlyGitCommand.resolveTree(request.expression)
            is GitTreeReadRequest.TreeManifest ->
                ReadOnlyGitCommand.treeManifest(request.oid.value)
        }
        val process = command.processBuilder()
            .directory(workspaceRoot.toJavaPath().toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.use { it.readAllBytes() }
        if (process.waitFor() == 0) GitReadResult.Completed(output) else GitReadResult.Failed
    }.getOrDefault(GitReadResult.Failed)
}
