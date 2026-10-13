package io.github.amichne.kast.topology.build.historical

import io.github.amichne.kast.kernel.KastSpanCount
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.GitObjectId
import io.github.amichne.kast.topology.contract.historical.HistoricalRepositoryId
import io.github.amichne.kast.topology.contract.historical.HistoricalRevision
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

enum class HistoricalGitFailure {
    INVALID_REQUEST,
    CHECKOUT_UNAVAILABLE,
    CHECKOUT_ROOT_MISMATCH,
    UNSUPPORTED_CHECKOUT_PATH,
    GIT_UNAVAILABLE,
    GIT_FAILED,
    TIME_EXHAUSTED,
    CAPACITY_EXCEEDED,
    REVISION_UNAVAILABLE,
    TRACKED_SOURCE_UNAVAILABLE,
    UNSUPPORTED_GIT_ENTRY,
    CHECKOUT_CONTENT_MISMATCH,
    REVISION_CONTENT_MISMATCH,
}

data class HistoricalGitSource(
    val path: WorkspaceSourcePath,
    val content: WorkspaceSourceContentHash,
    val blob: GitObjectId,
)

data class HistoricalAttributionWork(
    val gitCalls: KastSpanCount,
    val filesCompared: KastSpanCount,
    val bytesExamined: KastSpanCount,
)

/** Parsed effect observations. Admission does not prove that a native compiler ran or that inputs stayed immutable. */
class HistoricalGitAttribution
private constructor(
    val revision: HistoricalRevision,
    files: List<HistoricalGitSource>,
    val work: HistoricalAttributionWork,
) {
    val files: List<HistoricalGitSource> = Collections.unmodifiableList(files.sortedBy { it.path })

    companion object {
        internal fun admit(document: GitAttributionDocument.Attributed): HistoricalAttributionRead {
            val repository =
                when (val parsed = HistoricalRepositoryId.parse(document.revision.repository)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return invalid()
                }
            val commit =
                when (val parsed = GitObjectId.parse(document.revision.commit)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return invalid()
                }
            val tree =
                when (val parsed = GitObjectId.parse(document.revision.tree)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return invalid()
                }
            val work =
                when (val parsed = document.work.admit()) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return invalid()
                }
            val files = ArrayList<HistoricalGitSource>(document.files.size)
            for (source in document.files) when (val parsed = parseSource(source)) {
                is Refinement.Refined -> files += parsed.value
                is Refinement.Rejected -> return invalid()
            }
            if (files.map { it.path }.distinct().size != files.size || work.filesCompared.value != files.size.toLong())
                return invalid()
            return HistoricalAttributionRead.Attributed(
                HistoricalGitAttribution(HistoricalRevision(repository, commit, tree), files, work)
            )
        }
    }
}

sealed interface HistoricalAttributionRead {
    data class Attributed(val attribution: HistoricalGitAttribution) : HistoricalAttributionRead

    data class Rejected(val cause: HistoricalGitFailure, val work: HistoricalAttributionWork) :
        HistoricalAttributionRead

    data object InvalidDocument : HistoricalAttributionRead
}

/** Typed ingress for the opt-in Git effect adapter at packaging/topology/git_attribution.py. */
object HistoricalGitAttributionCodec {
    private val json = Json { classDiscriminator = "status" }

    fun decode(text: String): HistoricalAttributionRead {
        if (text.length > MAX_DOCUMENT_CHARACTERS) return HistoricalAttributionRead.InvalidDocument
        val document =
            try {
                json.decodeFromString<GitAttributionDocument>(text)
            } catch (_: SerializationException) {
                return HistoricalAttributionRead.InvalidDocument
            } catch (_: IllegalArgumentException) {
                return HistoricalAttributionRead.InvalidDocument
            }
        return when (document) {
            is GitAttributionDocument.Attributed -> HistoricalGitAttribution.admit(document)
            is GitAttributionDocument.Rejected ->
                when (val work = document.work.admit()) {
                    is Refinement.Refined -> HistoricalAttributionRead.Rejected(document.cause, work.value)
                    is Refinement.Rejected -> HistoricalAttributionRead.InvalidDocument
                }
        }
    }

    private const val MAX_DOCUMENT_CHARACTERS = 8 * 1024 * 1024
}

@Serializable
internal sealed interface GitAttributionDocument {
    @Serializable
    @SerialName("attributed")
    data class Attributed(
        val revision: GitRevisionDocument,
        val files: List<GitSourceDocument>,
        val work: GitWorkDocument,
    ) : GitAttributionDocument

    @Serializable
    @SerialName("rejected")
    data class Rejected(val cause: HistoricalGitFailure, val work: GitWorkDocument) : GitAttributionDocument
}

@Serializable internal data class GitRevisionDocument(val repository: String, val commit: String, val tree: String)

@Serializable internal data class GitSourceDocument(val path: String, val content: String, val blob: String)

@Serializable
internal data class GitWorkDocument(val gitCalls: Long, val filesCompared: Long, val bytesExamined: Long) {
    fun admit(): Refinement<HistoricalAttributionWork, HistoricalGitWorkFailure> {
        val calls =
            when (val parsed = KastSpanCount.parse(gitCalls)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(HistoricalGitWorkFailure.NEGATIVE)
            }
        val files =
            when (val parsed = KastSpanCount.parse(filesCompared)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(HistoricalGitWorkFailure.NEGATIVE)
            }
        val bytes =
            when (val parsed = KastSpanCount.parse(bytesExamined)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(HistoricalGitWorkFailure.NEGATIVE)
            }
        return Refinement.Refined(HistoricalAttributionWork(calls, files, bytes))
    }
}

private fun invalid() = HistoricalAttributionRead.InvalidDocument

internal enum class HistoricalGitWorkFailure {
    NEGATIVE
}

private enum class HistoricalGitSourceFailure {
    INVALID_SOURCE
}

private fun parseSource(source: GitSourceDocument): Refinement<HistoricalGitSource, HistoricalGitSourceFailure> {
    val path =
        when (val parsed = WorkspaceSourcePath.parse(source.path)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(HistoricalGitSourceFailure.INVALID_SOURCE)
        }
    val content =
        when (val parsed = WorkspaceSourceContentHash.parse(source.content)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(HistoricalGitSourceFailure.INVALID_SOURCE)
        }
    val blob =
        when (val parsed = GitObjectId.parse(source.blob)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(HistoricalGitSourceFailure.INVALID_SOURCE)
        }
    return Refinement.Refined(HistoricalGitSource(path, content, blob))
}
