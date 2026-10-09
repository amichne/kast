package io.github.amichne.kast.relation.contract

/** Graph nodes retain compiler identities and exact supplier context; formals from different calls never merge. */
sealed interface StaticCallbackNode {
    data class Named internal constructor(val callable: RelationCallableBody.Named) : StaticCallbackNode

    data class Anonymous internal constructor(val body: RelationCallableBody.Anonymous) : StaticCallbackNode

    data class Formal
    internal constructor(
        val parameter: CallbackParameterIdentity,
        val supply: CallbackArgumentBinding,
    ) : StaticCallbackNode
}

/** Static value/invocation relationships. None of these edges asserts runtime activation. */
sealed interface StaticCallbackEdge {
    val source: StaticCallbackNode
    val target: StaticCallbackNode

    data class ImmutableUses
    internal constructor(
        override val source: StaticCallbackNode.Named,
        override val target: StaticCallbackNode.Anonymous,
        val evidence: ImmutableCallbackInvocationFlow,
    ) : StaticCallbackEdge

    data class Supply
    internal constructor(
        override val source: StaticCallbackNode.Named,
        override val target: StaticCallbackNode.Formal,
        val body: StaticCallbackNode.Anonymous,
        val binding: CallbackArgumentBinding,
    ) : StaticCallbackEdge

    data class Forward
    internal constructor(
        override val source: StaticCallbackNode.Formal,
        override val target: StaticCallbackNode.Formal,
        val evidence: CallbackParameterForwarding,
    ) : StaticCallbackEdge

    data class Invoke
    internal constructor(
        override val source: StaticCallbackNode.Formal,
        override val target: StaticCallbackNode.Anonymous,
        val owner: StaticCallbackNode.Named,
        val evidence: CallbackParameterInvocation,
    ) : StaticCallbackEdge

    data class DirectInvoke
    internal constructor(
        override val source: StaticCallbackNode.Named,
        override val target: StaticCallbackNode.Anonymous,
        val evidence: CallbackDirectInvocationBinding,
    ) : StaticCallbackEdge

    data class DependencyContractInvoke
    internal constructor(
        override val source: StaticCallbackNode.Named,
        override val target: StaticCallbackNode.Anonymous,
        val evidence: CallbackDependencyContract,
    ) : StaticCallbackEdge

    data class BodyTarget
    internal constructor(
        override val source: StaticCallbackNode.Anonymous,
        override val target: StaticCallbackNode.Named,
        val occurrence: RelationOccurrence,
        val namedCallPolicy: CallbackNamedCallPolicy,
    ) : StaticCallbackEdge
}

sealed interface StaticCallbackGraphFailure {
    data class Unavailable(val cause: CallbackInvocationFlowCause) : StaticCallbackGraphFailure

    data class InvalidFlow(val cause: CallbackInvocationFlowFailure) : StaticCallbackGraphFailure

    data class ImmutableUnresolved(val flow: ImmutableCallbackInvocationFlow) : StaticCallbackGraphFailure

    data class Unresolved(val flow: CallbackInvocationFlow) : StaticCallbackGraphFailure

    data class UnprovenPolicy(val policy: CallbackNamedCallPolicy) : StaticCallbackGraphFailure

    data object CyclicRoute : StaticCallbackGraphFailure

    data object IncompleteScan : StaticCallbackGraphFailure

    data object UnsupportedDefaultSupply : StaticCallbackGraphFailure

    data object MissingNamedOwner : StaticCallbackGraphFailure

    data object SupplierIdentityMismatch : StaticCallbackGraphFailure

    data object OutsideWorkspace : StaticCallbackGraphFailure

    data object NonCallableTarget : StaticCallbackGraphFailure
}
