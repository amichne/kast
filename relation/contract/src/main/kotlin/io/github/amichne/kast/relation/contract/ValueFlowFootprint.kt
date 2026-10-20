package io.github.amichne.kast.relation.contract

import java.util.IdentityHashMap

internal const val VALUE_RECORD_OVERHEAD = 2048L
private const val VALUE_FRAME_OVERHEAD = 4096L
private const val VALUE_REFERENCE_BYTES = 8L
internal const val LOCAL_REFERENCE_KEY_STORAGE_BYTES = 1024L

/** One detached estimate owns its physical inventory. No identities escape this arithmetic. */
internal class ValueFootprintMeter {
    private val charged = IdentityHashMap<Any, Unit>()

    internal fun payload(owner: Any, fields: () -> Long): Long =
        if (charged.put(owner, Unit) == null) fields() else VALUE_REFERENCE_BYTES

    fun seed(seed: ValueProducerSeed): Long =
        VALUE_RECORD_OVERHEAD.addBytes(site(seed.site))
            .addBytes(
                payload(seed.origin) {
                    when (val origin = seed.origin) {
                        is ValueProducerOrigin.Declared -> VALUE_RECORD_OVERHEAD.addBytes(invocation(origin.invocation))
                        is ValueProducerOrigin.SourceFunction ->
                            VALUE_RECORD_OVERHEAD.addBytes(functionInvocation(origin.invocation))
                                .addBytes(endpoint(origin.invocation.enclosing))
                                .addBytes(endpoint(origin.invocation.executionOwner.enclosing))
                                .addBytes(body(origin.invocation.executionOwner.body))
                                .addBytes(
                                    payload(origin.property) {
                                        VALUE_RECORD_OVERHEAD.addBytes(endpoint(origin.property.endpoint))
                                    }
                                )
                                .addBytes(
                                    payload(origin.reference) {
                                        VALUE_RECORD_OVERHEAD.addBytes(text(origin.reference.file.stableValue))
                                    }
                                )
                    }
                }
            )

    fun application(application: ValueModelApplication, source: ValueSite): Long =
        payload(application) {
            VALUE_RECORD_OVERHEAD.addBytes(if (application.source === source) 0L else site(application.source))
                .addBytes(site(application.target))
                .addBytes(binding(application.binding))
                .addBytes(
                    when (application) {
                        is ValueModelApplication.CollectionInsertion -> 0L
                        is ValueModelApplication.CollectionSelection ->
                            payload(application.condition) {
                                VALUE_RECORD_OVERHEAD.addBytes(site(application.condition.receiver))
                                    .addBytes(site(application.condition.result))
                            }
                        is ValueModelApplication.ScopeCallbackInput -> frame(application.frame)
                        is ValueModelApplication.ScopeCallbackReturn -> frame(application.frame)
                    }
                )
        }

    private fun binding(binding: ReviewedLibraryBinding): Long =
        payload(binding) {
            VALUE_RECORD_OVERHEAD.addBytes(invocation(binding.invocation))
                .addBytes(endpoint(binding.invocation.executionOwner.enclosing))
                .addBytes(
                    payload(binding.model) {
                        val model = binding.model
                        VALUE_RECORD_OVERHEAD.addBytes(text(model.reference.model.id.value))
                            .addBytes(text(model.reference.model.provenance.value))
                            .addBytes(text(model.reference.rule.value))
                            .addBytes(model.callable.canonicalEncoding().value.length.toLong().multiplyBytes(2))
                            .addBytes(
                                payload(model.artifact) {
                                    VALUE_RECORD_OVERHEAD.addBytes(text(model.artifact.binaryDigest.value))
                                        .addBytes(text(model.artifact.sourceDigest.value))
                                        .addBytes(text(model.artifact.version.value))
                                }
                            )
                    }
                )
                .addBytes(
                    payload(binding.artifact) {
                        val artifact = binding.artifact
                        VALUE_RECORD_OVERHEAD.addBytes(text(artifact.root.value))
                            .addBytes(text(artifact.moduleName.value))
                            .addBytes(text(artifact.binaryDigest.value))
                            .addBytes(text(artifact.version.value))
                    }
                )
        }

    private fun text(value: String): Long = payload(value) { value.length.toLong().multiplyBytes(2) }

    fun site(site: ValueSite): Long =
        payload(site) {
            VALUE_RECORD_OVERHEAD.addBytes(endpoint(site.enclosing))
                .addBytes(role(site.role))
                .addBytes(endpoints(site.stateEndpoints()))
                .addBytes(path(site.valuePath))
                .addBytes(stack(site.callStack))
                .addBytes(predicates(site.predicates))
                .addBytes(body(site.executionOwner.body))
        }

    fun localReadSite(source: ValueSite): Long =
        VALUE_RECORD_OVERHEAD.addBytes(endpoint(source.enclosing))
            .addBytes(endpoints(source.stateEndpoints(includeFormal = false)))
            .addBytes(path(source.valuePath))
            .addBytes(stack(source.callStack))
            .addBytes(predicates(source.predicates))
            .addBytes(body(source.executionOwner.body))

    fun remainder(remainder: LocalBindingReadRemainder): Long =
        payload(remainder) {
            referenceRemainder(remainder.source, remainder.boundary, remainder.consumed, remainder.emitted)
        }

    fun referenceRemainder(
        source: ValueSite,
        boundary: RelationSearchBoundary,
        consumed: Set<LocalReferenceKey>,
        emitted: Set<ValueSiteIdentity>,
    ): Long =
        VALUE_RECORD_OVERHEAD.addBytes(site(source))
            .addBytes(boundary.canonical(source.enclosing).length.toLong().multiplyBytes(2))
            .addBytes(
                consumed.fold(0L) { bytes, key -> bytes.addBytes(payload(key) { LOCAL_REFERENCE_KEY_STORAGE_BYTES }) }
            )
            .addBytes(emitted.fold(0L) { bytes, identity -> bytes.addBytes(identity(identity)) })

    fun localReadIdentity(source: ValueSite): Long =
        identityFields(
            source.identity.owner,
            source.executionOwner.identity,
            source.valuePath,
            source.callStack,
            source.predicates,
        )

    private fun identity(identity: ValueSiteIdentity): Long =
        payload(identity) {
            identityFields(
                identity.owner,
                identity.execution,
                identity.valuePath,
                identity.callStack,
                identity.predicates,
            )
        }

    private fun identityFields(
        owner: ValueDeclarationIdentity,
        execution: ValueExecutionIdentity,
        valuePath: ValueAccessPath,
        callStack: ValueCallStack,
        conjunction: ValuePredicates,
    ): Long =
        VALUE_RECORD_OVERHEAD.addBytes(declaration(owner))
            .addBytes(
                payload(execution) {
                    VALUE_RECORD_OVERHEAD.addBytes(declaration(execution.host)).addBytes(declaration(execution.body))
                }
            )
            .addBytes(path(valuePath))
            .addBytes(stack(callStack))
            .addBytes(predicates(conjunction))

    private fun declaration(identity: ValueDeclarationIdentity): Long =
        payload(identity) {
            VALUE_RECORD_OVERHEAD.addBytes(identity.compiler.value.length.toLong().multiplyBytes(2))
                .addBytes(identity.file.stableValue.length.toLong().multiplyBytes(2))
        }

    internal fun endpoint(endpoint: RelationEndpoint): Long =
        payload(endpoint) { VALUE_RECORD_OVERHEAD.addBytes(endpoint.detachedTextUnits().multiplyBytes(2)) }

    private fun endpoints(values: List<RelationEndpoint>): Long =
        values.fold(0L) { bytes, endpoint -> bytes.addBytes(endpoint(endpoint)) }

    private fun role(role: ValueRole): Long =
        when (role) {
            is ValueRole.Argument -> payload(role) { VALUE_RECORD_OVERHEAD.addBytes(invocation(role.call)) }
            is ValueRole.Receiver -> payload(role) { VALUE_RECORD_OVERHEAD.addBytes(invocation(role.call)) }
            is ValueRole.FunctionArgument -> payload(role) { functionInvocation(role.call) }
            is ValueRole.Formal -> payload(role) { VALUE_RECORD_OVERHEAD }
            ValueRole.ExpressionResult,
            ValueRole.LocalBinding,
            ValueRole.LocalRead,
            ValueRole.Return,
            ValueRole.PropertyAssignment -> 0L
        }

    private fun invocation(call: ValueInvocation): Long =
        payload(call) {
            endpoint(call.enclosing).addBytes(endpoint(call.callable)).addBytes(body(call.executionOwner.body))
        }

    private fun functionInvocation(call: ValueFunctionInvocation): Long =
        payload(call) {
            VALUE_RECORD_OVERHEAD.addBytes(
                    call.callable.signature.canonicalEncoding().value.length.toLong().multiplyBytes(2)
                )
                .addBytes(call.callable.moduleName.value.length.toLong().multiplyBytes(2))
        }

    private fun path(path: ValueAccessPath): Long =
        payload(path) {
            path.segments.fold(0L) { bytes, segment -> bytes.addBytes(payload(segment) { VALUE_RECORD_OVERHEAD }) }
        }

    private fun stack(stack: ValueCallStack): Long =
        payload(stack) { stack.frames.fold(0L) { bytes, frame -> bytes.addBytes(frameRecord(frame)) } }

    private fun frameRecord(frame: ValueCallFrame): Long =
        payload(frame) {
            VALUE_FRAME_OVERHEAD.addBytes(body(frame.callee.body))
                .addBytes(body(frame.invocation.executionOwner.body))
                .addBytes(
                    when (val call = frame.invocation) {
                        is ValueInvocation -> 0L
                        is ValueFunctionInvocation -> functionInvocation(call)
                    }
                )
                .addBytes(target(frame.target))
        }

    private fun frame(frame: ValueCallFrame): Long = endpoints(frame.stateEndpoints()).addBytes(frameRecord(frame))

    private fun target(target: ValueCallTarget): Long =
        when (target) {
            ValueCallTarget.Direct -> 0L
            is ValueReviewedCallbackTarget -> reviewedCallbackTarget(target)
            is ValueCallbackTarget,
            is ValueCallTarget.Implementation -> payload(target) { valueCallTargetStorageBytes(target) }
        }

    fun reviewedCallbackTarget(target: ValueReviewedCallbackTarget): Long =
        payload(target) {
            VALUE_FRAME_OVERHEAD.addBytes(binding(target.binding))
                .addBytes(endpoint(target.formal.callable))
                .addBytes(endpoint(target.formal.executionOwner.enclosing))
                .addBytes(body(target.formal.executionOwner.body))
                .addBytes(text(target.formal.name.value))
                .addBytes(text(target.argument.file.stableValue))
        }

    private fun body(body: ValueExecutionBody): Long =
        when (body) {
            ValueExecutionBody.Declaration -> 0L
            is ValueExecutionBody.Anonymous ->
                payload(body) {
                    VALUE_RECORD_OVERHEAD.addBytes(
                        body.callable.signature.canonicalEncoding().value.length.toLong().multiplyBytes(2)
                    )
                }
        }

    fun predicates(predicates: ValuePredicates): Long =
        payload(predicates) {
            predicates.entries.fold(0L) { bytes, predicate ->
                bytes.addBytes(
                    payload(predicate) {
                        VALUE_RECORD_OVERHEAD.addBytes(
                                payload(predicate.subject) { site(predicate.subject.occurrence) }
                            )
                            .addBytes(
                                when (predicate) {
                                    is ValuePredicate.Subtype -> endpoint(predicate.resolvedType)
                                    is ValuePredicate.NonNull -> 0L
                                }
                            )
                    }
                )
            }
        }

    fun evidence(evidence: ValueTransferEvidence): Long =
        when (evidence) {
            ValueTransferEvidence.Direct -> 0L
            is ValueTransferEvidence.NormalBranchResult,
            is ValueTransferEvidence.StringInterpolation -> payload(evidence) { evidence.retainedBytes }
            is ValueSourceTransfer -> payload(evidence) { operation(evidence.operation) }
            is ValueTransferEvidence.Guarded ->
                payload(evidence) { evidence(evidence.delegate).addBytes(predicates(evidence.added)) }
        }

    fun operation(operation: ValueSourceOperation): Long =
        payload(operation) {
            when (operation) {
                is ValueSourceOperation.Enter -> frame(operation.frame).addBytes(position(operation.position))
                is ValueSourceOperation.SeedCallbackEnter ->
                    frame(operation.frame).addBytes(position(operation.position))
                is ValueSourceOperation.Return -> frame(operation.frame)
                is ValueSourceOperation.SeedReturn -> frame(operation.frame)
                is ValueSourceOperation.SeedCallbackReturn -> frame(operation.frame)
                is ValueSourceOperation.ReadField -> endpoint(operation.field.declaration)
                is ValueSourceOperation.ConstructField ->
                    invocation(operation.call)
                        .addBytes(
                            payload(operation.storage) {
                                parameter(operation.storage.parameter)
                                    .addBytes(endpoint(operation.storage.field.declaration))
                            }
                        )
            }
        }

    private fun position(position: ValueFormalPosition): Long =
        payload(position) {
            when (position) {
                is ValueFormalPosition.Parameter -> parameter(position.parameter)
                is ValueFormalPosition.Receiver -> VALUE_RECORD_OVERHEAD
            }
        }

    private fun parameter(parameter: ValueFormalParameter): Long =
        payload(parameter) {
            endpoint(parameter.callable)
                .addBytes(parameter.name.value.length.toLong().multiplyBytes(2))
                .addBytes(body(parameter.executionOwner.body))
        }

    fun obligationEvidence(evidence: ValueFlowObligationEvidence): Long =
        when (evidence) {
            ValueFlowObligationEvidence.None -> 0L
            is ValueFlowObligationEvidence.CallbackFlow,
            is ValueFlowObligationEvidence.CallbackUnavailable,
            is ValueFlowObligationEvidence.CallbackContractRejected,
            is ValueFlowObligationEvidence.ImplementationDispatch ->
                payload(evidence) { valueObligationEvidenceStorageBytes(evidence) }
        }
}
