package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID

/** Read authority is either a published workspace lease or an original-owner live IDE admission. */
sealed interface SemanticReadAuthority {
    val workspaceRoot: CanonicalWorkspaceRoot

    val identity: SemanticReadIdentity
        get() = when (this) {
            is SemanticReadLease -> SemanticReadIdentity.Published(this)
            is LiveSemanticReadAuthority -> SemanticReadIdentity.Live(reference)
        }

    fun requirePublished(): Refinement<SemanticReadLease, PublishedReadAuthorityFailure> = when (this) {
        is SemanticReadLease -> Refinement.Refined(this)
        is LiveSemanticReadAuthority -> Refinement.Rejected(PublishedReadAuthorityFailure.LIVE_AUTHORITY)
    }
}

/** Detached identity, never a capability to find, open, or execute against an IDE. */
@JvmInline
value class IdeReadHostLifetime private constructor(val value: UUID) {
    companion object {
        fun fromBoundary(value: UUID): IdeReadHostLifetime = IdeReadHostLifetime(value)
    }
}

@JvmInline
value class IdeReadEpochRevision private constructor(val value: Long) {
    companion object {
        fun parse(value: Long): Refinement<IdeReadEpochRevision, IdeReadEpochRevisionFailure> =
            if (value > 0) Refinement.Refined(IdeReadEpochRevision(value))
            else Refinement.Rejected(IdeReadEpochRevisionFailure.NOT_POSITIVE)
    }
}

enum class IdeReadEpochRevisionFailure { NOT_POSITIVE }
enum class IdeReadContentView { SAVED_PSI_COMMITTED }

/**
 * Versioned transport reference. Decoding it proves only representation validity. The original
 * owner must re-admit the current epoch; selectors must then recheck scope, declaration and content.
 */
data class LiveSemanticReadReference(
    val workspaceRoot: CanonicalWorkspaceRoot,
    val host: IdeReadHostLifetime,
    val epoch: IdeReadEpochRevision,
    val contentView: IdeReadContentView,
    val version: Int,
) {
    companion object {
        const val VERSION = 1
    }
}

/** Opaque in-process read admission. No generation, copy, parser, or execution capability exists. */
class LiveSemanticReadAuthority private constructor(
    val reference: LiveSemanticReadReference,
    val admittedEpoch: ProjectReadEpoch<*>,
) : SemanticReadAuthority {
    override val workspaceRoot: CanonicalWorkspaceRoot get() = reference.workspaceRoot

    internal companion object {
        @JvmSynthetic
        internal fun issue(reference: LiveSemanticReadReference, epoch: ProjectReadEpoch<*>) =
            LiveSemanticReadAuthority(reference, epoch)
    }
}

enum class LiveSemanticReadFailure {
    WRONG_ROOT,
    WRONG_HOST,
    INCOMPARABLE_EPOCH,
    EPOCH_MOVED,
    EPOCH_EXHAUSTED,
    REFERENCE_VERSION_UNSUPPORTED,
    RETIRED,
}

/**
 * Explicit session effect boundary, retained only by the admitted project's original owner.
 * The adapter supplies freshly admitted VFS evidence and one host-lifetime nonce. This owner
 * never observes a project, reads a file, generates randomness, or manufactures freshness.
 */
internal class LiveSemanticReadOwner(
    private val root: CanonicalWorkspaceRoot,
    private val host: IdeReadHostLifetime,
) {
    private sealed interface State {
        data object Unobserved : State
        data class Current(val authority: LiveSemanticReadAuthority) : State
        data object Retired : State
    }

    private var state: State = State.Unobserved

    /** Freshness must have just been established by this admitted project's epoch source. */
    @Synchronized
    @JvmSynthetic
    internal fun admit(
        freshness: VfsPassiveReadCapability,
    ): Refinement<LiveSemanticReadAuthority, LiveSemanticReadFailure> {
        if (state == State.Retired) return rejected(LiveSemanticReadFailure.RETIRED)
        if (freshness.canonicalRoot != root) return rejected(LiveSemanticReadFailure.WRONG_ROOT)
        val next = when (val current = state) {
            State.Unobserved -> 1L
            State.Retired -> return rejected(LiveSemanticReadFailure.RETIRED)
            is State.Current -> when (current.authority.admittedEpoch.relationTo(freshness.admittedEpoch)) {
                ProjectReadEpochRelation.SAME -> return Refinement.Refined(current.authority)
                ProjectReadEpochRelation.INCOMPARABLE -> return rejected(LiveSemanticReadFailure.INCOMPARABLE_EPOCH)
                ProjectReadEpochRelation.MOVED -> {
                    val previous = current.authority.reference.epoch.value
                    if (previous == Long.MAX_VALUE) {
                        state = State.Retired
                        return rejected(LiveSemanticReadFailure.EPOCH_EXHAUSTED)
                    }
                    previous + 1
                }
            }
        }
        val revision = when (val parsed = IdeReadEpochRevision.parse(next)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return rejected(LiveSemanticReadFailure.EPOCH_EXHAUSTED)
        }
        val authority = LiveSemanticReadAuthority.issue(
            LiveSemanticReadReference(root, host, revision, IdeReadContentView.SAVED_PSI_COMMITTED,
                LiveSemanticReadReference.VERSION),
            freshness.admittedEpoch,
        )
        state = State.Current(authority)
        return Refinement.Refined(authority)
    }

    /** A detached reference can only select a freshly admitted authority of this same owner. */
    @Synchronized
    @JvmSynthetic
    internal fun restore(
        reference: LiveSemanticReadReference,
        freshness: VfsPassiveReadCapability,
    ): Refinement<LiveSemanticReadAuthority, LiveSemanticReadFailure> {
        val current = when (val admitted = admit(freshness)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
        return when {
            reference.version != LiveSemanticReadReference.VERSION ->
                rejected(LiveSemanticReadFailure.REFERENCE_VERSION_UNSUPPORTED)
            reference.workspaceRoot != root -> rejected(LiveSemanticReadFailure.WRONG_ROOT)
            reference.host != host -> rejected(LiveSemanticReadFailure.WRONG_HOST)
            reference.epoch != current.reference.epoch -> rejected(LiveSemanticReadFailure.EPOCH_MOVED)
            else -> Refinement.Refined(current)
        }
    }

    @Synchronized
    @JvmSynthetic
    internal fun retire() {
        state = State.Retired
    }

    private fun rejected(failure: LiveSemanticReadFailure) = Refinement.Rejected(failure)
}

/** Detached read identity. A live reference remains data until its original owner re-admits it. */
sealed interface SemanticReadIdentity {
    val workspaceRoot: CanonicalWorkspaceRoot
    val revisionKey: SemanticReadRevisionKey

    data class Published(val lease: SemanticReadLease) : SemanticReadIdentity {
        override val workspaceRoot get() = lease.workspaceRoot
        override val revisionKey get() = SemanticReadRevisionKey.published(lease)
    }

    data class Live(val reference: LiveSemanticReadReference) : SemanticReadIdentity {
        override val workspaceRoot get() = reference.workspaceRoot
        override val revisionKey get() = SemanticReadRevisionKey.live(reference)
    }
}

/** Canonical revision field for fingerprints which separately retain the exact workspace root. */
@JvmInline
value class SemanticReadRevisionKey private constructor(val value: String) {
    companion object {
        internal fun published(lease: SemanticReadLease) = SemanticReadRevisionKey(lease.generation.value.toString())
        internal fun live(reference: LiveSemanticReadReference) = SemanticReadRevisionKey(
            "live-ide-v${reference.version}:${reference.host.value}:${reference.epoch.value}:${reference.contentView.name}",
        )
    }
}

enum class PublishedReadAuthorityFailure { LIVE_AUTHORITY }

/** Request-local freshness effect, implemented by the owner of the admitted authority. */
fun interface SemanticReadValidationPort {
    suspend fun validate(expected: SemanticReadAuthority): SemanticReadValidation
}

enum class SemanticReadValidation { CURRENT, UNAVAILABLE, ROOT_MISMATCH, MOVED }

/** Published observation adapter; a live authority can never pass a publication observation. */
fun WorkspaceInspectionOperations.semanticReadValidation(): SemanticReadValidationPort =
    SemanticReadValidationPort { expected ->
        when (val state = inspect()) {
            is WorkspaceRuntimeState.Ready -> when {
                state.workspace.root != expected.workspaceRoot -> SemanticReadValidation.ROOT_MISMATCH
                state.workspace.readLease != expected -> SemanticReadValidation.MOVED
                else -> SemanticReadValidation.CURRENT
            }
            WorkspaceRuntimeState.Absent,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Reconciling,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Stopping -> SemanticReadValidation.UNAVAILABLE
        }
    }
