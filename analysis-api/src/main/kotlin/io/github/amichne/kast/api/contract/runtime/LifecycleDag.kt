package io.github.amichne.kast.api.contract

sealed interface RequiredLifecycleCapability {
    data object Source : RequiredLifecycleCapability
    data object Reference : RequiredLifecycleCapability
    data object Graph : RequiredLifecycleCapability
}

class SemanticDemand<C : RequiredLifecycleCapability> private constructor() {
    companion object {
        /** Proof transition: semantic source demand creates one source-capability demand token. */
        fun source(): SemanticDemand<RequiredLifecycleCapability.Source> = SemanticDemand()

        /** Proof transition: semantic reference demand creates one reference-capability demand token. */
        fun reference(): SemanticDemand<RequiredLifecycleCapability.Reference> = SemanticDemand()

        /** Proof transition: semantic graph demand creates one graph-capability demand token. */
        fun graph(): SemanticDemand<RequiredLifecycleCapability.Graph> = SemanticDemand()
    }
}

@JvmInline
value class RuntimeEpochId private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> RuntimeEpochIdResolution`.
         *
         * Establishes a non-blank immutable runtime epoch identifier. Rejection
         * is finite [RuntimeEpochIdFailure] data. Raw extraction is permitted
         * only at descriptor serialization and process-launch boundaries.
         */
        fun parse(value: String): RuntimeEpochIdResolution = if (value.isBlank()) {
            RuntimeEpochIdResolution.Rejected(RuntimeEpochIdFailure.Blank)
        } else {
            RuntimeEpochIdResolution.Resolved(RuntimeEpochId(value))
        }
    }
}

sealed interface RuntimeEpochIdFailure {
    data object Blank : RuntimeEpochIdFailure
}

sealed interface RuntimeEpochIdResolution {
    data class Resolved(val epoch: RuntimeEpochId) : RuntimeEpochIdResolution
    data class Rejected(val failure: RuntimeEpochIdFailure) : RuntimeEpochIdResolution
}

@JvmInline
value class EvidenceRevision private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> EvidenceRevisionResolution`.
         *
         * Establishes a positive immutable lane revision. Rejection is finite
         * [EvidenceRevisionFailure] data. Raw extraction is permitted only at
         * SQLite and protocol serialization boundaries.
         */
        fun parse(value: Long): EvidenceRevisionResolution = if (value <= 0) {
            EvidenceRevisionResolution.Rejected(EvidenceRevisionFailure.NotPositive(value))
        } else {
            EvidenceRevisionResolution.Resolved(EvidenceRevision(value))
        }
    }
}

sealed interface EvidenceRevisionFailure {
    data class NotPositive(val value: Long) : EvidenceRevisionFailure
}

sealed interface EvidenceRevisionResolution {
    data class Resolved(val revision: EvidenceRevision) : EvidenceRevisionResolution
    data class Rejected(val failure: EvidenceRevisionFailure) : EvidenceRevisionResolution
}

class WorkspaceAdmitted<C : RequiredLifecycleCapability> internal constructor(
    internal val demand: SemanticDemand<C>,
)

sealed interface OwnershipObserved<C : RequiredLifecycleCapability> {
    class Absent<C : RequiredLifecycleCapability> internal constructor(
        internal val workspace: WorkspaceAdmitted<C>,
    ) : OwnershipObserved<C>

    class ProvenDead<C : RequiredLifecycleCapability> internal constructor(
        internal val workspace: WorkspaceAdmitted<C>,
    ) : OwnershipObserved<C>

    class ExactOwned<C : RequiredLifecycleCapability> internal constructor(
        internal val workspace: WorkspaceAdmitted<C>,
        internal val epoch: RuntimeEpochId,
    ) : OwnershipObserved<C>

    data class Blocked<C : RequiredLifecycleCapability>(
        val blocker: LifecycleBlocker,
    ) : OwnershipObserved<C>
}

class LaunchPermit<C : RequiredLifecycleCapability> private constructor(
    internal val workspace: WorkspaceAdmitted<C>,
) {
    companion object {
        /** Proof transition: `OwnershipObserved.Absent<C> -> LaunchPermit<C>`. */
        fun <C : RequiredLifecycleCapability> absent(
            ownership: OwnershipObserved.Absent<C>,
        ): LaunchPermit<C> = LaunchPermit(ownership.workspace)

        /** Proof transition: `OwnershipObserved.ProvenDead<C> -> LaunchPermit<C>`. */
        fun <C : RequiredLifecycleCapability> replacement(
            ownership: OwnershipObserved.ProvenDead<C>,
        ): LaunchPermit<C> = LaunchPermit(ownership.workspace)
    }
}

class StartingEpoch<C : RequiredLifecycleCapability> internal constructor(
    internal val permit: LaunchPermit<C>,
    internal val epoch: RuntimeEpochId,
)

class RevalidatedEpoch<C : RequiredLifecycleCapability> internal constructor(
    internal val ownership: OwnershipObserved.ExactOwned<C>,
)

class RuntimeAvailable<C : RequiredLifecycleCapability> private constructor(
    val epoch: RuntimeEpochId,
) {
    companion object {
        /** Proof transition: `StartingEpoch<C> -> RuntimeAvailable<C>`. */
        fun <C : RequiredLifecycleCapability> started(starting: StartingEpoch<C>): RuntimeAvailable<C> =
            RuntimeAvailable(starting.epoch)

        /** Proof transition: `RevalidatedEpoch<C> -> RuntimeAvailable<C>`. */
        fun <C : RequiredLifecycleCapability> reused(revalidated: RevalidatedEpoch<C>): RuntimeAvailable<C> =
            RuntimeAvailable(revalidated.ownership.epoch)
    }
}

class ModelReady<C : RequiredLifecycleCapability> internal constructor(
    internal val runtime: RuntimeAvailable<C>,
)

class SourceReady<C : RequiredLifecycleCapability> internal constructor(
    val epoch: RuntimeEpochId,
    val revision: EvidenceRevision,
)

class ReferenceReady<C : RequiredLifecycleCapability> private constructor(
    val source: SourceReady<C>,
) {
    companion object {
        /** Proof transition: `SourceReady<C> -> ReferenceReady<C>`. */
        fun <C : RequiredLifecycleCapability> committed(source: SourceReady<C>): ReferenceReady<C> =
            ReferenceReady(source)
    }
}

class GraphReady<C : RequiredLifecycleCapability> private constructor(
    val source: SourceReady<C>,
) {
    companion object {
        /** Proof transition: `SourceReady<C> -> GraphReady<C>`. */
        fun <C : RequiredLifecycleCapability> committed(source: SourceReady<C>): GraphReady<C> = GraphReady(source)
    }
}

sealed interface LifecycleBlocker {
    data object UnsupportedRoot : LifecycleBlocker
    data object OwnershipConflict : LifecycleBlocker
    data object OwnershipAmbiguous : LifecycleBlocker
    data object IdentityChanged : LifecycleBlocker
    data object ReplacementFailed : LifecycleBlocker
    data object CapabilityUnavailable : LifecycleBlocker
}
