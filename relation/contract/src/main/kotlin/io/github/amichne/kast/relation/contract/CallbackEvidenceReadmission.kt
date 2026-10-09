package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Rebuilds each basis-bearing layer through its owning factory; source-bound body identities stay exact. */
internal class CallbackEvidenceReadmission(private val endpoints: CallbackEndpointReadmissions) {
    fun summary(previous: CallbackParameterSummary): CallbackReadmission<CallbackParameterSummary> =
        formal(previous.formal).then { formal ->
            readmitEach(previous.invocations, ::invocation).then { invocations ->
                readmitEach(previous.ownerBindings, ::ownerBinding).then { owners ->
                    forwarding(previous.forwarding).then { graph ->
                        CallbackParameterSummary.fromCompiler(
                                formal,
                                invocations,
                                previous.obligations,
                                owners,
                                previous.scan,
                                graph,
                            )
                            .callback()
                    }
                }
            }
        }

    fun formal(previous: CallbackParameterIdentity): CallbackReadmission<CallbackParameterIdentity> =
        endpoints.endpoint(previous.callable).then { endpoint ->
            when (
                val admitted = CallbackParameterIdentity.fromCompiler(endpoint, previous.position, previous.parameter)
            ) {
                is Refinement.Refined -> admitted
                is Refinement.Rejected ->
                    Refinement.Rejected(CallbackSummaryReadmissionFailure.Formal(admitted.failure))
            }
        }

    fun invocation(previous: CallbackParameterInvocation): CallbackReadmission<CallbackParameterInvocation> =
        readmitEach(previous.callableTransfers, ::transfer).then { transfers ->
            readmitEach(previous.forwardings, ::edge).then { edges ->
                CallbackParameterInvocation.fromCompiler(previous.occurrence, previous.owner, transfers, edges)
                    .callback()
            }
        }

    fun edge(previous: CallbackParameterForwarding): CallbackReadmission<CallbackParameterForwarding> =
        formal(previous.source).then { source ->
            argument(previous.target).then { target ->
                readmitEach(previous.callableTransfers, ::transfer).then { transfers ->
                    CallbackParameterForwarding.fromCompiler(source, previous.argument, target, transfers).callback()
                }
            }
        }

    private fun forwarding(previous: CallbackForwardingEvidence): CallbackReadmission<CallbackForwardingEvidence> =
        when (previous) {
            CallbackForwardingEvidence.InvocationRoutes -> Refinement.Refined(previous)
            is CallbackForwardingEvidence.ExhaustedGraph ->
                formal(previous.graph.root).then { root ->
                    readmitEach(previous.graph.formals, ::formal).then { formals ->
                        readmitEach(previous.graph.forwardings, ::edge).then { edges ->
                            CompleteCallbackForwardingGraph.fromCompiler(
                                    root,
                                    formals,
                                    edges,
                                    CallbackInvocationScan.EXHAUSTIVE,
                                )
                                .callback()
                                .then { Refinement.Refined(CallbackForwardingEvidence.ExhaustedGraph(it)) }
                        }
                    }
                }
        }

    private fun ownerBinding(previous: CallbackBodyBinding): CallbackReadmission<CallbackBodyBinding> =
        binding(previous.binding).then { binding ->
            CallbackBodyBinding.fromCompiler(previous.body, previous.supply, binding, previous.obligations).callback()
        }

    private fun binding(previous: CallbackBindingEvidence): CallbackReadmission<CallbackBindingEvidence> =
        when (previous) {
            is CallbackBindingEvidence.Bound ->
                argument(previous.binding).then { Refinement.Refined(CallbackBindingEvidence.Bound(it)) }
            is CallbackBindingEvidence.Default ->
                formal(previous.binding.parameter).then { formal ->
                    CallbackDefaultBinding.fromCompiler(formal, previous.binding.defaultValue).callback().then {
                        Refinement.Refined(CallbackBindingEvidence.Default(it))
                    }
                }
            is CallbackBindingEvidence.Direct ->
                CallbackDirectInvocationBinding.fromCompiler(
                        endpoints.authority.identity,
                        previous.binding.occurrence,
                        previous.binding.owner,
                    )
                    .callback()
                    .then { Refinement.Refined(CallbackBindingEvidence.Direct(it)) }
            is CallbackBindingEvidence.DependencyContract ->
                Refinement.Rejected(CallbackSummaryReadmissionFailure.DependencyContractNeedsFreshRead)
            is CallbackBindingEvidence.Unavailable -> Refinement.Refined(previous)
        }

    fun argument(previous: CallbackArgumentBinding): CallbackReadmission<CallbackArgumentBinding> =
        call(previous.invocation).then { call ->
            CallbackArgumentBinding.fromCompiler(call, previous.invocationOwner, previous.position, previous.parameter)
                .callback()
        }

    fun call(previous: ValueInvocation): CallbackReadmission<ValueInvocation> =
        endpoints.endpoint(previous.enclosing).then { owner ->
            endpoints.endpoint(previous.callable).then { target ->
                when (val admitted = ValueInvocation.fromCompiler(owner, previous.range, target)) {
                    is Refinement.Refined -> admitted
                    is Refinement.Rejected ->
                        Refinement.Rejected(CallbackSummaryReadmissionFailure.Invocation(admitted.failure))
                }
            }
        }

    fun transfer(previous: ValueTransfer): CallbackReadmission<ValueTransfer> =
        site(previous.source).then { source ->
            site(previous.target).then { target ->
                when (val admitted = ValueTransfer.fromCompiler(source, target, previous.kind, previous.evidence)) {
                    is Refinement.Refined -> admitted
                    is Refinement.Rejected ->
                        Refinement.Rejected(CallbackSummaryReadmissionFailure.Transfer(admitted.failure))
                }
            }
        }

    fun site(previous: ValueSite): CallbackReadmission<ValueSite> =
        endpoints.endpoint(previous.enclosing).then { owner ->
            role(previous.role).then { role ->
                when (val admitted = ValueSite.fromCompiler(owner, previous.range, role)) {
                    is Refinement.Refined -> admitted
                    is Refinement.Rejected ->
                        Refinement.Rejected(CallbackSummaryReadmissionFailure.Site(admitted.failure))
                }
            }
        }

    private fun role(previous: ValueRole): CallbackReadmission<ValueRole> =
        when (previous) {
            is ValueRole.Argument ->
                call(previous.call).then { Refinement.Refined(ValueRole.Argument(it, previous.position)) }
            ValueRole.ExpressionResult,
            ValueRole.LocalBinding,
            ValueRole.LocalRead,
            ValueRole.Return,
            ValueRole.PropertyAssignment -> Refinement.Refined(previous)
        }
}

private fun <T> Refinement<T, CallbackInvocationFlowFailure>.callback(): CallbackReadmission<T> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected -> Refinement.Rejected(CallbackSummaryReadmissionFailure.Callback(failure))
    }
