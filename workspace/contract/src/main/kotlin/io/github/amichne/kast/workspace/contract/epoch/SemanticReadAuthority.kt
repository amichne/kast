package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID

/** Read authority is either a published workspace lease or an original-owner live IDE admission. */
sealed interface SemanticReadAuthority {
    val workspaceRoot: CanonicalWorkspaceRoot

    val identity: SemanticReadIdentity

    fun requirePublished(): Refinement<SemanticReadLease, PublishedReadAuthorityFailure> =
        when (this) {
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

enum class IdeReadEpochRevisionFailure {
    NOT_POSITIVE
}

enum class IdeReadContentView {
    SAVED_PSI_COMMITTED
}

/**
 * Versioned transport reference. Decoding it proves only representation validity. The original owner must re-admit the
 * current epoch; selectors must then recheck scope, declaration and content.
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
class LiveSemanticReadAuthority
private constructor(
    val reference: LiveSemanticReadReference,
    val admittedEpoch: ProjectReadEpoch<*>,
    private val owner: LiveSemanticReadOwner,
) : SemanticReadAuthority {
    override val identity: SemanticReadIdentity = SemanticReadIdentity.Live(reference)
    override val workspaceRoot: CanonicalWorkspaceRoot
        get() = reference.workspaceRoot

    /** Short detached-state transitions only; never run semantic work or acquire native access in this callback. */
    fun <Value> withCurrentOwner(action: () -> Value): Refinement<Value, LiveSemanticReadFailure> =
        owner.withCurrent(this, action)

    fun requireSameOwner(other: LiveSemanticReadAuthority): Refinement<Unit, LiveSemanticReadFailure> =
        when {
            workspaceRoot != other.workspaceRoot -> Refinement.Rejected(LiveSemanticReadFailure.WRONG_ROOT)
            owner !== other.owner -> Refinement.Rejected(LiveSemanticReadFailure.WRONG_HOST)
            else -> Refinement.Refined(Unit)
        }

    internal companion object {
        @JvmSynthetic
        internal fun issue(
            reference: LiveSemanticReadReference,
            epoch: ProjectReadEpoch<*>,
            owner: LiveSemanticReadOwner,
        ) = LiveSemanticReadAuthority(reference, epoch, owner)
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

internal sealed interface LiveSemanticReadAdmissionFailure {
    data class Freshness(val cause: VfsPassiveReadAdmissionFailure) : LiveSemanticReadAdmissionFailure

    data class Authority(val cause: LiveSemanticReadFailure) : LiveSemanticReadAdmissionFailure
}

/**
 * Explicit session effect boundary, retained only by the admitted project's original owner. The adapter supplies
 * freshly admitted VFS evidence and one host-lifetime nonce. This owner never observes a project, reads a file,
 * generates randomness, or manufactures freshness.
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

    /** Native access must precede this monitor; freshly observe inside the same ownership transition. */
    @Synchronized
    @JvmSynthetic
    internal fun admit(
        observe: () -> VfsPassiveReadAdmission
    ): Refinement<LiveSemanticReadAuthority, LiveSemanticReadAdmissionFailure> {
        if (state == State.Retired)
            return Refinement.Rejected(LiveSemanticReadAdmissionFailure.Authority(LiveSemanticReadFailure.RETIRED))
        val freshness =
            when (val observed = observe()) {
                is VfsPassiveReadAdmission.Admitted -> observed.capability
                is VfsPassiveReadAdmission.Rejected ->
                    return Refinement.Rejected(LiveSemanticReadAdmissionFailure.Freshness(observed.failure))
            }
        return when (val admitted = admitFresh(freshness)) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> Refinement.Rejected(LiveSemanticReadAdmissionFailure.Authority(admitted.failure))
        }
    }

    private fun admitFresh(
        freshness: VfsPassiveReadCapability
    ): Refinement<LiveSemanticReadAuthority, LiveSemanticReadFailure> {
        if (state == State.Retired) return rejected(LiveSemanticReadFailure.RETIRED)
        if (freshness.canonicalRoot != root) return rejected(LiveSemanticReadFailure.WRONG_ROOT)
        val next =
            when (val current = state) {
                State.Unobserved -> 1L
                State.Retired -> return rejected(LiveSemanticReadFailure.RETIRED)
                is State.Current ->
                    when (current.authority.admittedEpoch.relationTo(freshness.admittedEpoch)) {
                        ProjectReadEpochRelation.SAME -> return Refinement.Refined(current.authority)
                        ProjectReadEpochRelation.INCOMPARABLE ->
                            return rejected(LiveSemanticReadFailure.INCOMPARABLE_EPOCH)
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
        val revision =
            when (val parsed = IdeReadEpochRevision.parse(next)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return rejected(LiveSemanticReadFailure.EPOCH_EXHAUSTED)
            }
        val authority =
            LiveSemanticReadAuthority.issue(
                LiveSemanticReadReference(
                    root,
                    host,
                    revision,
                    IdeReadContentView.SAVED_PSI_COMMITTED,
                    LiveSemanticReadReference.VERSION,
                ),
                freshness.admittedEpoch,
                this,
            )
        state = State.Current(authority)
        return Refinement.Refined(authority)
    }

    /** A detached reference can only select a freshly admitted authority of this same owner. */
    @Synchronized
    @JvmSynthetic
    internal fun restore(
        reference: LiveSemanticReadReference,
        observe: () -> VfsPassiveReadAdmission,
    ): Refinement<LiveSemanticReadAuthority, LiveSemanticReadAdmissionFailure> {
        val current =
            when (val admitted = admit(observe)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val failure =
            when {
                reference.version != LiveSemanticReadReference.VERSION ->
                    LiveSemanticReadFailure.REFERENCE_VERSION_UNSUPPORTED
                reference.workspaceRoot != root -> LiveSemanticReadFailure.WRONG_ROOT
                reference.host != host -> LiveSemanticReadFailure.WRONG_HOST
                reference != current.reference -> LiveSemanticReadFailure.EPOCH_MOVED
                else -> return Refinement.Refined(current)
            }
        return Refinement.Rejected(LiveSemanticReadAdmissionFailure.Authority(failure))
    }

    @Synchronized
    internal fun <Value> withCurrent(
        authority: LiveSemanticReadAuthority,
        action: () -> Value,
    ): Refinement<Value, LiveSemanticReadFailure> =
        when (val current = state) {
            State.Retired -> Refinement.Rejected(LiveSemanticReadFailure.RETIRED)
            State.Unobserved -> Refinement.Rejected(LiveSemanticReadFailure.EPOCH_MOVED)
            is State.Current ->
                if (current.authority !== authority) Refinement.Rejected(LiveSemanticReadFailure.EPOCH_MOVED)
                else Refinement.Refined(action())
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
        override val workspaceRoot
            get() = lease.workspaceRoot

        override val revisionKey
            get() = SemanticReadRevisionKey.published(lease)
    }

    data class Live(val reference: LiveSemanticReadReference) : SemanticReadIdentity {
        override val workspaceRoot
            get() = reference.workspaceRoot

        override val revisionKey
            get() = SemanticReadRevisionKey.live(reference)
    }
}

/** Canonical revision field for fingerprints which separately retain the exact workspace root. */
@JvmInline
value class SemanticReadRevisionKey private constructor(val value: String) {
    companion object {
        internal fun published(lease: SemanticReadLease) = SemanticReadRevisionKey(lease.generation.value.toString())

        internal fun live(reference: LiveSemanticReadReference) =
            SemanticReadRevisionKey(
                "live-ide-v${reference.version}:${reference.host.value}:${reference.epoch.value}:${reference.contentView.name}"
            )
    }
}

enum class PublishedReadAuthorityFailure {
    LIVE_AUTHORITY
}

/** Request-local freshness effect, implemented by the owner of the admitted authority. */
fun interface SemanticReadValidationPort {
    suspend fun validate(expected: SemanticReadAuthority): SemanticReadValidation
}

enum class SemanticReadValidation {
    CURRENT,
    UNAVAILABLE,
    ROOT_MISMATCH,
    MOVED,
}

/** Published observation adapter; a live authority can never pass a publication observation. */
fun WorkspaceInspectionOperations.semanticReadValidation(): SemanticReadValidationPort =
    SemanticReadValidationPort { expected ->
        when (val state = inspect()) {
            is WorkspaceRuntimeState.Ready ->
                when {
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
