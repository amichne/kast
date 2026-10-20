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

    fun ownerBinding(previous: CallbackBodyBinding): CallbackReadmission<CallbackBodyBinding> =
        binding(previous.binding).then { binding ->
            CallbackBodyBinding.fromCompiler(
                    previous.body,
                    previous.supply,
                    binding,
                    previous.obligations +
                        CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION +
                        listOfNotNull((binding as? CallbackBindingEvidence.Unavailable)?.cause),
                )
                .callback()
                .then { owner ->
                    when (val execution = previous.execution) {
                        CallbackOwnerExecution.Unproven -> Refinement.Refined(owner)
                        is CallbackOwnerExecution.Observed ->
                            flow(execution.flow).then { owner.withExecution(it).callback() }
                        is CallbackOwnerExecution.Immutable ->
                            CallbackSupplierReadmission(endpoints).flow(execution.flow).then {
                                owner.withExecution(it, execution.owner).callback()
                            }
                    }
                }
        }

    private fun flow(previous: CallbackInvocationFlow): CallbackReadmission<CallbackInvocationFlow> =
        binding(previous.binding).then { binding ->
            readmitEach(previous.invocations, ::invocation).then { invocations ->
                readmitEach(previous.ownerBindings, ::ownerBinding).then { owners ->
                    forwarding(previous.forwarding).then { forwarding ->
                        val obligations =
                            if (
                                callbackExecutionNeedsQualification(binding, invocations) ||
                                    forwarding.needsQualification()
                            )
                                previous.obligations + CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
                            else previous.obligations
                        CallbackInvocationFlow.fromCompiler(
                                endpoints.authority.identity,
                                previous.body,
                                binding,
                                invocations,
                                obligations,
                                previous.scan,
                                forwarding,
                            )
                            .callback()
                            .then { it.withOwnerBindings(owners).callback() }
                    }
                }
            }
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
            is CallbackBindingEvidence.DeferredDependency,
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
                val execution =
                    when (val result = execution(previous.executionOwner, owner)) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected ->
                            return@then Refinement.Rejected(
                                CallbackSummaryReadmissionFailure.Invocation(
                                    ValueInvocationFailure.EXECUTION_OWNER_MISMATCH
                                )
                            )
                    }
                when (val admitted = ValueInvocation.fromCompiler(owner, previous.range, target, execution)) {
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
                val execution =
                    when (val result = execution(previous.executionOwner, owner)) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected ->
                            return@then Refinement.Rejected(
                                CallbackSummaryReadmissionFailure.Site(ValueSiteFailure.EXECUTION_OWNER_MISMATCH)
                            )
                    }
                when (
                    val admitted =
                        ValueSite.fromCompiler(
                            owner,
                            previous.range,
                            role,
                            execution,
                            previous.valuePath,
                            previous.callStack,
                            previous.predicates,
                        )
                ) {
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
            is ValueRole.Receiver ->
                call(previous.call).then { Refinement.Refined(ValueRole.Receiver(it, previous.kind)) }
            is ValueRole.Formal -> Refinement.Refined(previous)
            ValueRole.ExpressionResult,
            ValueRole.LocalBinding,
            ValueRole.LocalRead,
            ValueRole.Return,
            ValueRole.PropertyAssignment -> Refinement.Refined(previous)
        }

    private fun execution(
        previous: ValueExecutionOwner,
        owner: RelationEndpoint,
    ): Refinement<ValueExecutionOwner, ValueExecutionOwnerFailure> =
        when (val body = previous.body) {
            ValueExecutionBody.Declaration -> Refinement.Refined(ValueExecutionOwner.declaration(owner))
            is ValueExecutionBody.Anonymous -> ValueExecutionOwner.fromCompiler(owner, body.callable, previous.basis)
        }
}

private fun <T> Refinement<T, CallbackInvocationFlowFailure>.callback(): CallbackReadmission<T> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected -> Refinement.Rejected(CallbackSummaryReadmissionFailure.Callback(failure))
    }
