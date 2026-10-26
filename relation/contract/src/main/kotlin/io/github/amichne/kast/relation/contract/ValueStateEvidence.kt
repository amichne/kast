package io.github.amichne.kast.relation.contract

internal fun ValueSite.stateEndpoints(includeFormal: Boolean = true): List<RelationEndpoint> = buildList {
    (role as? ValueRole.LoopResult)?.result?.protocol?.invocations()?.forEach {
        add(it.enclosing)
        add(it.callable)
    }
    valuePath.segments.filterIsInstance<ValuePathSegment.Field>().forEach { add(it.field.declaration) }
    callStack.frames.forEach { addAll(it.stateEndpoints()) }
    conditions.facts.forEach { condition ->
        val origin = condition.origin
        add(origin.execution.enclosing)
        origin.valuePath.segments.filterIsInstance<ValuePathSegment.Field>().forEach { add(it.field.declaration) }
        origin.callStack.frames.forEach { addAll(it.stateEndpoints()) }
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
    valueState(site.executionOwner, site.valuePath, site.callStack)
    field(site.conditions.facts.size.toString())
    site.conditions.facts.forEach { condition ->
        when (condition) {
            is ValueCondition.StableReceiver -> {
                field("STABLE_RECEIVER")
                conditionOrigin(condition.origin)
                range(condition.useRange)
                field(condition.kind.name)
                field(condition.originalType.value)
                field(condition.refinedType.value)
            }
            is ValueCondition.CastSuccess -> {
                field("CAST_SUCCESS")
                conditionOrigin(condition.origin)
                range(condition.useRange)
                field(condition.kind.name)
                field(condition.originalType.value)
                field(condition.targetType.value)
            }
        }
    }
}

private fun StringBuilder.conditionOrigin(origin: ValueConditionOrigin) {
    endpoint(origin.execution.enclosing)
    range(origin.execution.range)
    field(origin.execution.identity.host.compiler.value)
    range(origin.range)
    valueRole(origin.role)
    valueState(origin.execution, origin.valuePath, origin.callStack)
}

private fun StringBuilder.valueState(
    execution: ValueExecutionOwner,
    path: ValueAccessPath,
    stack: ValueCallStack,
) {
    when (val context = execution.context) {
        ValueExecutionContext.Declaration -> Unit
        is ValueExecutionContext.ReviewedCallback -> {
            field("REVIEWED_CALLBACK_VALUE")
            field(context.binding.body.compilerIdentity.value)
            range(context.binding.body.range)
            range(context.binding.occurrence.range)
            field(context.binding.target.compilerIdentity.value)
            field(context.binding.target.file.stableValue)
            range(context.binding.target.range)
            field(context.binding.position.value.toString())
            field(context.binding.artifact.binaryArtifact.value)
            field(context.binding.artifact.sourceArtifact.value)
        }
        is ValueExecutionContext.Callback -> {
            field("CAPTURED_EXECUTION")
            field(context.body.compilerIdentity.value)
            range(context.body.range)
            field(context.invocations.size.toString())
            context.invocations.forEach { frame ->
                field(frame.body.compilerIdentity.value)
                valueCall(frame.supply.invocation)
                range(frame.supply.parameter.range)
                field(frame.supply.position.value.toString())
                field(frame.invocation.canonicalProjection())
            }
            field("SELECTED_INVOCATION")
            valueCall(context.selectedInvocation.supply.invocation)
            body(context.selectedInvocation.supply.invocationOwner)
            occurrence(context.selectedInvocation.supply.parameter)
            field(context.selectedInvocation.supply.position.value.toString())
            field(context.selectedInvocation.invocation.canonicalProjection())
        }
    }
    field(path.depth.toString())
    path.segments.forEach { segment ->
        when (segment) {
            ValuePathSegment.Element -> field("ELEMENT")
            is ValuePathSegment.Field -> {
                field("FIELD")
                endpoint(segment.field.declaration)
            }
        }
    }
    field(stack.frames.size.toString())
    stack.frames.forEach { frame -> callFrame(frame) }
}

internal fun StringBuilder.callFrame(frame: ValueCallFrame) {
    valueCall(frame.invocation)
    endpoint(frame.callee.enclosing)
    range(frame.callee.range)
    field(frame.callee.identity.host.compiler.value)
    when (val target = frame.target) {
        ValueCallTarget.Direct -> field("DIRECT")
        is ValueCallTarget.Implementation -> {
            field("IMPLEMENTATION")
            field(target.dispatch.identity.canonical)
            field(target.relation.canonicalProjection())
            sourceOwnership(target.ownership)
        }
    }
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

private fun StringBuilder.valueCall(call: ValueInvocation) {
    endpoint(call.enclosing)
    range(call.range)
    endpoint(call.callable)
    range(call.executionOwner.range)
    field(call.executionOwner.identity.host.compiler.value)
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
            field(execution.identity.host.compiler.value)
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
        is ValueSourceOperation.Return -> {
            field("RETURN")
            callFrame(operation.frame)
        }
        is ValueSourceOperation.SeedReturn -> {
            field("SEED_RETURN")
            callFrame(operation.frame)
        }
        is ValueSourceOperation.CallbackReturn -> {
            field("CALLBACK_RETURN")
            field(operation.invocation.body.compilerIdentity.value)
            range(operation.invocation.body.range)
            valueCall(operation.invocation.supply.invocation)
            range(operation.invocation.supply.parameter.range)
            field(operation.invocation.supply.position.value.toString())
            field(operation.invocation.invocation.canonicalProjection())
            callFrame(operation.frame)
        }
        is ValueSourceOperation.CallbackArgumentEntry -> {
            field("CALLBACK_ARGUMENT_ENTRY")
            field(operation.invocation.body.compilerIdentity.value)
            range(operation.invocation.body.range)
            valueCall(operation.invocation.supply.invocation)
            range(operation.invocation.supply.parameter.range)
            field(operation.invocation.supply.position.value.toString())
            field(operation.invocation.invocation.canonicalProjection())
            callFrame(operation.supplierFrame)
            formalPosition(ValueFormalPosition.Parameter(operation.parameter))
            range(operation.argumentRange)
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
