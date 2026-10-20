package io.github.amichne.kast.relation.contract

internal fun ValueSite.stateEndpoints(includeFormal: Boolean = true): List<RelationEndpoint> = buildList {
    valuePath.segments.filterIsInstance<ValuePathSegment.Field>().forEach { add(it.field.declaration) }
    callStack.frames.forEach { addAll(it.stateEndpoints()) }
    predicates.entries.forEach { predicate ->
        add(predicate.subject.occurrence.enclosing)
        addAll(predicate.subject.occurrence.stateEndpoints())
        if (predicate is ValuePredicate.Subtype) add(predicate.resolvedType)
    }
    val formal = (role as? ValueRole.Formal)?.position as? ValueFormalPosition.Parameter
    if (includeFormal && formal != null) add(formal.parameter.callable)
}

internal fun ValueCallFrame.stateEndpoints(): List<RelationEndpoint> = buildList {
    add(invocation.enclosing)
    if (invocation is ValueInvocation) add(invocation.callable)
    add(callee.enclosing)
    when (val proof = target) {
        ValueCallTarget.Direct -> Unit
        is ValueCallbackTarget -> {
            add(proof.supplyingFrame.callee.enclosing)
            addAll(proof.flow.requiredRelationEndpoints())
        }
        is ValueReviewedCallbackTarget -> {
            add(proof.binding.invocation.enclosing)
            add(proof.binding.invocation.callable)
            add(proof.formal.callable)
            add(proof.formal.executionOwner.enclosing)
        }
        is ValueCallTarget.Implementation -> {
            add(proof.dispatch.batch.request.subject)
            proof.dispatch.batch.facts.forEach { fact ->
                add(fact.subject)
                add(fact.source)
                add(fact.target)
            }
        }
    }
}

internal fun StringBuilder.valueState(site: ValueSite) {
    field(site.valuePath.depth.toString())
    site.valuePath.segments.forEach { segment ->
        when (segment) {
            is ValuePathSegment.Field -> {
                field("FIELD")
                endpoint(segment.field.declaration)
            }
            ValuePathSegment.AnyElement -> field("ANY_ELEMENT")
            is ValuePathSegment.Element -> {
                field("ELEMENT")
                field(segment.index.value.toString())
            }
        }
    }
    predicates(site.predicates)
    field(site.callStack.frames.size.toString())
    site.callStack.frames.forEach { frame -> callFrame(frame) }
}

internal fun StringBuilder.callFrame(frame: ValueCallFrame) {
    valueCall(frame.invocation)
    endpoint(frame.callee.enclosing)
    range(frame.callee.range)
    field(frame.callee.identity.body.compiler.value)
    when (val target = frame.target) {
        ValueCallTarget.Direct -> field("DIRECT")
        is ValueCallbackTarget -> {
            field("CALLBACK")
            field(target.canonical)
        }
        is ValueReviewedCallbackTarget -> reviewedCallbackTarget(target)
        is ValueCallTarget.Implementation -> {
            field("IMPLEMENTATION")
            field(target.dispatch.identity.canonical)
            field(target.relation.canonicalProjection())
            sourceOwnership(target.ownership)
        }
    }
}

private fun StringBuilder.reviewedCallbackTarget(target: ValueReviewedCallbackTarget) {
    field("REVIEWED_CALLBACK")
    val binding = target.binding
    valueCall(binding.invocation)
    field(binding.model.reference.model.id.value)
    field(binding.model.reference.model.version.value.toString())
    field(binding.model.reference.model.provenance.value)
    field(binding.model.reference.rule.value)
    field(binding.model.artifact.sourceDigest.value)
    field(binding.artifact.root.value)
    field(binding.artifact.moduleName.value)
    field(binding.artifact.binaryDigest.value)
    field(binding.artifact.version.value)
    field(binding.artifact.origin.name)
    field(target.argument.file.stableValue)
    range(target.argument.range)
    formalPosition(ValueFormalPosition.Parameter(target.formal))
    val body = target.formal.executionOwner.body as ValueExecutionBody.Anonymous
    field(body.callable.signature.canonicalEncoding().value)
}

private fun StringBuilder.sourceOwnership(ownership: ValueSourceOwnership) {
    val root = ownership.root
    field(ownership.basis.workspaceRoot.value)
    field(ownership.basis.revisionKey.value)
    field(root.module.value)
    field(root.project.buildRoot.value)
    field(root.project.projectPath.value)
    field(root.sourceSet.value)
    field(root.sourceRoot.value)
    field(root.sourceKind.name)
    field(root.provenance.name)
}

private fun StringBuilder.valueCall(call: ValueCallOccurrence) {
    endpoint(call.enclosing)
    range(call.range)
    when (call) {
        is ValueInvocation -> endpoint(call.callable)
        is ValueFunctionInvocation -> {
            field("BUILTIN_INVOKE")
            field(call.callable.signature.canonicalEncoding().value)
            field(call.callable.origin.name)
            field(call.callable.moduleKind.name)
            field(call.callable.moduleName.value)
        }
    }
    range(call.executionOwner.range)
    field(call.executionOwner.identity.body.compiler.value)
}

internal fun StringBuilder.formalPosition(position: ValueFormalPosition) {
    when (position) {
        is ValueFormalPosition.Parameter -> {
            field("PARAMETER")
            endpoint(position.parameter.callable)
            field(position.parameter.position.value.toString())
            field(position.parameter.name.value)
            range(position.parameter.range)
            val execution = position.parameter.executionOwner
            endpoint(execution.enclosing)
            range(execution.range)
            field(execution.identity.body.compiler.value)
            when (position.parameter.origin) {
                is ValueFormalOrigin.Declared -> field("DECLARED")
                ValueFormalOrigin.Implicit -> field("IMPLICIT")
            }
        }
        is ValueFormalPosition.Receiver -> {
            field("RECEIVER")
            field(position.kind.name)
        }
    }
}

internal fun StringBuilder.sourceOperation(operation: ValueSourceOperation) {
    when (operation) {
        is ValueSourceOperation.Enter -> {
            field("ENTER")
            callFrame(operation.frame)
            formalPosition(operation.position)
        }
        is ValueSourceOperation.SeedCallbackEnter -> {
            field("SEED_CALLBACK_ENTER")
            callFrame(operation.frame)
            formalPosition(operation.position)
        }
        is ValueSourceOperation.Return -> {
            field("RETURN")
            callFrame(operation.frame)
        }
        is ValueSourceOperation.SeedReturn -> {
            field("SEED_RETURN")
            callFrame(operation.frame)
        }
        is ValueSourceOperation.SeedCallbackReturn -> {
            field("SEED_CALLBACK_RETURN")
            callFrame(operation.frame)
        }
        is ValueSourceOperation.ReadField -> {
            field("READ_FIELD")
            endpoint(operation.field.declaration)
        }
        is ValueSourceOperation.ConstructField -> {
            field("CONSTRUCT_FIELD")
            valueCall(operation.call)
            formalPosition(ValueFormalPosition.Parameter(operation.storage.parameter))
            endpoint(operation.storage.field.declaration)
        }
    }
}

internal fun StringBuilder.predicates(predicates: ValuePredicates) {
    val rows =
        predicates.entries
            .map { predicate ->
                buildString {
                    field(buildString { site(predicate.subject.occurrence) })
                    when (predicate) {
                        is ValuePredicate.Subtype -> {
                            field("SUBTYPE")
                            endpoint(predicate.resolvedType)
                        }
                        is ValuePredicate.NonNull -> field("NON_NULL")
                    }
                }
            }
            .sorted()
    field(rows.size.toString())
    rows.forEach(::field)
}
