package io.github.amichne.kast.relation.contract

internal fun StringBuilder.transferEvidence(evidence: ValueTransferEvidence) {
    when (evidence) {
        ValueTransferEvidence.Direct -> field("DIRECT")
        is ValueSourceTransfer -> sourceOperation(evidence.operation)
        is ValueLoopTransfer -> {
            field("LOOP_TRANSFER")
            field(evidence.receiverKind.name)
            field("NORMAL_TRUE")
            loopProtocol(evidence.iteration.protocol)
            when (evidence) {
                is ValueLoopTransfer.Advance -> field("ADVANCE")
            }
        }
        is ValueTransferEvidence.NormalBranchResult -> {
            field("NORMAL_BRANCH_RESULT")
            range(evidence.tryRange)
            range(evidence.branchRange)
            when (val alternative = evidence.alternative) {
                ValueTryBranchAlternative.TryBody -> field("TRY_BODY")
                is ValueTryBranchAlternative.CatchBody -> {
                    field("CATCH_BODY")
                    field(alternative.index.value.toString())
                }
            }
            field("NORMAL_COMPLETION")
        }
    }
}

internal fun StringBuilder.site(site: ValueSite) {
    valueState(site)
    endpoint(site.enclosing)
    range(site.range)
    valueRole(site.role)
    when (val version = site.localVersion) {
        ValueLocalVersion.Unversioned -> Unit
        is ValueLocalVersion.Defined -> {
            field("LOCAL_VALUE_VERSION")
            range(version.declaration)
            range(version.block)
            range(version.definition)
            range(version.rhs)
            when (val end = version.end) {
                is ValueLocalVersion.End.BlockEnd -> {
                    field("BLOCK_END")
                    range(end.range)
                }
                is ValueLocalVersion.End.NextWrite -> {
                    field("NEXT_WRITE")
                    range(end.range)
                    range(end.rhs)
                }
            }
        }
    }
}

internal fun StringBuilder.valueRole(role: ValueRole) {
    when (role) {
        ValueRole.ExpressionResult -> field("EXPRESSION_RESULT")
        is ValueRole.LoopResult -> {
            field("LOOP_RESULT")
            when (role.result) {
                is ValueLoopResult.Iterator -> field("ITERATOR")
                is ValueLoopResult.Element -> field("ELEMENT_NORMAL_TRUE")
            }
            loopProtocol(role.result.protocol)
        }
        ValueRole.LocalBinding -> field("LOCAL_BINDING")
        ValueRole.LocalRead -> field("LOCAL_READ")
        ValueRole.Return -> field("RETURN")
        ValueRole.PropertyAssignment -> field("PROPERTY_ASSIGNMENT")
        is ValueRole.Argument -> {
            field("ARGUMENT")
            endpoint(role.call.enclosing)
            endpoint(role.call.callable)
            range(role.call.range)
            field(role.position.value.toString())
        }
        is ValueRole.Receiver -> {
            field("RECEIVER")
            field(role.kind.name)
            endpoint(role.call.enclosing)
            endpoint(role.call.callable)
            range(role.call.range)
        }
        is ValueRole.Formal -> {
            field("FORMAL")
            formalPosition(role.position)
        }
    }
}

private fun StringBuilder.loopProtocol(protocol: ValueLoopProtocol) {
    protocol.invocations().forEach { invocation ->
        site(invocation.resultSite())
        endpoint(invocation.callable)
    }
}
