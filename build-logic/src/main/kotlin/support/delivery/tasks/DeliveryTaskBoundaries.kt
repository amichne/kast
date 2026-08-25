package support.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import org.gradle.api.GradleException

internal sealed interface GitHeadObservation {
    data class Complete(val revision: AuthorityGitRevision) : GitHeadObservation
    data class Rejected(val failure: GitHeadFailure) : GitHeadObservation
}

internal enum class GitHeadFailure {
    METADATA_MISSING,
    MALFORMED_GITDIR,
    MALFORMED_COMMONDIR,
    COMMONDIR_MISSING,
    HEAD_MISSING,
    HEAD_MALFORMED,
    REF_MALFORMED,
    REF_ESCAPES_GITDIR,
    REF_MISSING,
    READ_FAILED,
}

internal sealed interface GitHeadRevalidation {
    data class Complete(
        val revision: AuthorityGitRevision,
    ) : GitHeadRevalidation

    data class Rejected(
        val before: AuthorityGitRevision,
        val after: AuthorityGitRevision,
    ) : GitHeadRevalidation
}

/**
 * Proof transition: before and after `AuthorityGitRevision` observations -> revalidated exact head.
 *
 * [GitHeadRevalidation.Complete] preserves the equal revision. Movement returns
 * [GitHeadRevalidation.Rejected]. Raw Git extraction remains in [observeGitHead].
 */
internal fun revalidateGitHead(
    before: AuthorityGitRevision,
    after: AuthorityGitRevision,
): GitHeadRevalidation = if (before == after) {
    GitHeadRevalidation.Complete(before)
} else {
    GitHeadRevalidation.Rejected(before, after)
}

/**
 * Proof transition: repository `Path` -> exact `AuthorityGitRevision`.
 *
 * Establishes a full lowercase commit identity from worktree-local Git HEAD metadata and loose or
 * packed refs in the common Git directory, without process start. Expected failures return
 * [GitHeadObservation.Rejected]. Raw text stays at this Gradle task boundary.
 */
internal fun observeGitHead(repositoryRoot: Path): GitHeadObservation {
    val dotGit = repositoryRoot.resolve(".git")
    val worktreeGitDirectory = try {
        when {
            Files.isDirectory(dotGit, NOFOLLOW_LINKS) -> dotGit
            Files.isRegularFile(dotGit, NOFOLLOW_LINKS) -> {
                val marker = Files.readString(dotGit).trim()
                if (!marker.startsWith("gitdir: ")) {
                    return GitHeadObservation.Rejected(GitHeadFailure.MALFORMED_GITDIR)
                }
                dotGit.parent.resolve(marker.removePrefix("gitdir: ")).normalize()
            }
            else -> return GitHeadObservation.Rejected(GitHeadFailure.METADATA_MISSING)
        }
    } catch (_: IOException) {
        return GitHeadObservation.Rejected(GitHeadFailure.READ_FAILED)
    } catch (_: InvalidPathException) {
        return GitHeadObservation.Rejected(GitHeadFailure.MALFORMED_GITDIR)
    }
    val commonGitDirectory = try {
        val commonDirectoryMarker = worktreeGitDirectory.resolve("commondir")
        if (!Files.exists(commonDirectoryMarker, NOFOLLOW_LINKS)) {
            worktreeGitDirectory
        } else {
            if (!Files.isRegularFile(commonDirectoryMarker, NOFOLLOW_LINKS)) {
                return GitHeadObservation.Rejected(GitHeadFailure.MALFORMED_COMMONDIR)
            }
            val marker = Files.readString(commonDirectoryMarker).trim()
            if (marker.isEmpty()) {
                return GitHeadObservation.Rejected(GitHeadFailure.MALFORMED_COMMONDIR)
            }
            val configured = Path.of(marker)
            val resolved = (
                if (configured.isAbsolute) configured else worktreeGitDirectory.resolve(configured)
            ).normalize()
            if (!Files.isDirectory(resolved, NOFOLLOW_LINKS)) {
                return GitHeadObservation.Rejected(GitHeadFailure.COMMONDIR_MISSING)
            }
            resolved
        }
    } catch (_: IOException) {
        return GitHeadObservation.Rejected(GitHeadFailure.READ_FAILED)
    } catch (_: InvalidPathException) {
        return GitHeadObservation.Rejected(GitHeadFailure.MALFORMED_COMMONDIR)
    }
    val head = try {
        val headPath = worktreeGitDirectory.resolve("HEAD")
        if (!Files.isRegularFile(headPath, NOFOLLOW_LINKS)) {
            return GitHeadObservation.Rejected(GitHeadFailure.HEAD_MISSING)
        }
        Files.readString(headPath).trim()
    } catch (_: IOException) {
        return GitHeadObservation.Rejected(GitHeadFailure.READ_FAILED)
    }
    val revision = if (head.startsWith("ref: ")) {
        val ref = head.removePrefix("ref: ")
        if (ref.isEmpty()) {
            return GitHeadObservation.Rejected(GitHeadFailure.REF_MALFORMED)
        }
        val refPath = try {
            commonGitDirectory.resolve(ref).normalize()
        } catch (_: InvalidPathException) {
            return GitHeadObservation.Rejected(GitHeadFailure.REF_MALFORMED)
        }
        if (!refPath.startsWith(commonGitDirectory.normalize())) {
            return GitHeadObservation.Rejected(GitHeadFailure.REF_ESCAPES_GITDIR)
        }
        try {
            if (Files.isRegularFile(refPath, NOFOLLOW_LINKS)) {
                Files.readString(refPath).trim()
            } else {
                when (val packed = readPackedRef(commonGitDirectory.resolve("packed-refs"), ref)) {
                    is PackedRefObservation.Complete -> packed.revision
                    PackedRefObservation.Rejected -> {
                        return GitHeadObservation.Rejected(GitHeadFailure.REF_MISSING)
                    }
                }
            }
        } catch (_: IOException) {
            return GitHeadObservation.Rejected(GitHeadFailure.READ_FAILED)
        }
    } else {
        head
    }
    return if (revision.matches(Regex("[0-9a-f]{40}"))) {
        GitHeadObservation.Complete(AuthorityGitRevision(revision))
    } else {
        GitHeadObservation.Rejected(GitHeadFailure.HEAD_MALFORMED)
    }
}

private sealed interface PackedRefObservation {
    data class Complete(val revision: String) : PackedRefObservation
    data object Rejected : PackedRefObservation
}

/**
 * Proof transition: packed-ref bytes plus ref name -> one revision.
 *
 * Absent or ambiguous matches return `Rejected`. Raw text stays at the Git boundary.
 */
private fun readPackedRef(packedRefs: Path, ref: String): PackedRefObservation {
    if (!Files.isRegularFile(packedRefs, NOFOLLOW_LINKS)) return PackedRefObservation.Rejected
    val matches = Files.readAllLines(packedRefs).asSequence()
        .filterNot { it.startsWith("#") || it.startsWith("^") }
        .map { it.split(' ', limit = 2) }
        .filter { it.size == 2 && it[1] == ref }
        .map { it.first() }
        .toList()
    return if (matches.size == 1) {
        PackedRefObservation.Complete(matches.single())
    } else {
        PackedRefObservation.Rejected
    }
}

internal sealed interface BoundaryFileRead {
    class Complete(val bytes: ByteArray) : BoundaryFileRead
    data class Rejected(val failure: AuthoritySourceFailure) : BoundaryFileRead
}

/**
 * Proof transition: raw `Path` -> bounded regular non-symlink bytes.
 *
 * Expected file failures return [BoundaryFileRead.Rejected]. Raw bytes stay at the Gradle task
 * boundary.
 */
internal fun readBoundaryFile(path: Path, maximumBytes: Long): BoundaryFileRead = try {
    when {
        maximumBytes < 0 || maximumBytes >= Int.MAX_VALUE -> {
            BoundaryFileRead.Rejected(AuthoritySourceFailure.INVALID_LIMIT)
        }
        !Files.exists(path, NOFOLLOW_LINKS) -> {
            BoundaryFileRead.Rejected(AuthoritySourceFailure.MISSING)
        }
        Files.isSymbolicLink(path) -> BoundaryFileRead.Rejected(AuthoritySourceFailure.SYMLINK)
        !Files.isRegularFile(path, NOFOLLOW_LINKS) -> {
            BoundaryFileRead.Rejected(AuthoritySourceFailure.NOT_REGULAR)
        }
        else -> Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val bytes = input.readNBytes((maximumBytes + 1).toInt())
            if (bytes.size > maximumBytes) {
                BoundaryFileRead.Rejected(AuthoritySourceFailure.TOO_LARGE)
            } else {
                BoundaryFileRead.Complete(bytes)
            }
        }
    }
} catch (_: IOException) {
    BoundaryFileRead.Rejected(AuthoritySourceFailure.READ_FAILED)
}

internal fun BoundaryFileRead.asTextOrReject(owner: String): String = when (this) {
    is BoundaryFileRead.Complete -> bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> rejectAuthority(owner, failure.name)
}

/**
 * Proof transition: declared authority path -> observed byte digest.
 *
 * Expected file failures return [AuthoritySourceObservation.Rejected]. Raw bytes stay at the
 * Gradle task boundary.
 */
internal fun observeAuthoritySource(
    repositoryRoot: Path,
    sourcePath: AuthoritySourcePath,
): AuthoritySourceObservation {
    val configured = Path.of(sourcePath.value)
    val resolved = (if (configured.isAbsolute) configured else repositoryRoot.resolve(configured))
        .normalize()
    return when (val read = readBoundaryFile(resolved, MAX_SOURCE_ARTIFACT_BYTES)) {
        is BoundaryFileRead.Rejected -> AuthoritySourceObservation.Rejected(read.failure)
        is BoundaryFileRead.Complete -> AuthoritySourceObservation.Complete(
            AuthorityArtifactDigest(sha256Bytes(read.bytes)),
        )
    }
}

internal fun AuthorityAdmissionFailure.render(): String = when (this) {
    AuthorityAdmissionFailure.MalformedDocument -> "MALFORMED_DOCUMENT"
    AuthorityAdmissionFailure.UnsupportedSchema -> "UNSUPPORTED_SCHEMA"
    AuthorityAdmissionFailure.MalformedGitRevision -> "MALFORMED_GIT_REVISION"
    AuthorityAdmissionFailure.MalformedFingerprint -> "MALFORMED_FINGERPRINT"
    AuthorityAdmissionFailure.MalformedSourceDeclaration -> "MALFORMED_SOURCE_DECLARATION"
    AuthorityAdmissionFailure.MalformedSourcePath -> "MALFORMED_SOURCE_PATH"
    AuthorityAdmissionFailure.BaseRevisionMismatch -> "BASE_REVISION_MISMATCH"
    AuthorityAdmissionFailure.ExactHeadMismatch -> "EXACT_HEAD_MISMATCH"
    AuthorityAdmissionFailure.ProgramFingerprintMismatch -> "PROGRAM_FINGERPRINT_MISMATCH"
    AuthorityAdmissionFailure.RequirementFingerprintMismatch -> "REQUIREMENT_FINGERPRINT_MISMATCH"
    AuthorityAdmissionFailure.SourceSetMismatch -> "SOURCE_SET_MISMATCH"
    is AuthorityAdmissionFailure.SourcePathNotAllowed -> "SOURCE_PATH_NOT_ALLOWED:${sourceId.value}"
    is AuthorityAdmissionFailure.SourceUnavailable -> {
        "SOURCE_UNAVAILABLE:${sourceId.value}:${failure.name}"
    }
    is AuthorityAdmissionFailure.SourceDigestMismatch -> "SOURCE_DIGEST_MISMATCH:${sourceId.value}"
    AuthorityAdmissionFailure.ContradictionSetIncomplete -> "CONTRADICTION_SET_INCOMPLETE"
    AuthorityAdmissionFailure.ObsoleteAssumptionSetIncomplete -> "OBSOLETE_ASSUMPTION_SET_INCOMPLETE"
    AuthorityAdmissionFailure.UnprovenClaimSetIncomplete -> "UNPROVEN_CLAIM_SET_INCOMPLETE"
}

internal fun ProgramAuthorityGenerationFailure.render(): String = when (this) {
    is ProgramAuthorityGenerationFailure.CandidatePathNotAllowed -> {
        "CANDIDATE_PATH_NOT_ALLOWED:${path.value}"
    }
    is ProgramAuthorityGenerationFailure.DuplicateCandidatePath -> {
        "DUPLICATE_CANDIDATE_PATH:${path.value}"
    }
    is ProgramAuthorityGenerationFailure.SourceUnavailable -> {
        "SOURCE_UNAVAILABLE:${path.value}:${failure.name}"
    }
    is ProgramAuthorityGenerationFailure.UnexpectedDigest -> {
        "UNEXPECTED_DIGEST:${path.value}:${digest.value}"
    }
    is ProgramAuthorityGenerationFailure.AmbiguousDeclaredDigest -> {
        "AMBIGUOUS_DECLARED_DIGEST:${digest.value}"
    }
    is ProgramAuthorityGenerationFailure.DuplicateSourceMatch -> {
        "DUPLICATE_SOURCE_MATCH:${sourceId.value}"
    }
    is ProgramAuthorityGenerationFailure.MissingDeclaredSource -> {
        "MISSING_DECLARED_SOURCE:${sourceId.value}"
    }
}

internal fun rejectAuthority(owner: String, failure: String): Nothing =
    throw GradleException("$owner rejected: $failure")

internal fun writeTextAtomically(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        Files.writeString(temporary, content)
        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

internal const val MAX_AUTHORITY_BYTES = 1L shl 20
internal const val MAX_SOURCE_ARTIFACT_BYTES = 32L shl 20
