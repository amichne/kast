package io.github.amichne.kast.relation.contract

internal fun StringBuilder.transferEvidence(evidence: ValueTransferEvidence) {
    when (evidence) {
        ValueTransferEvidence.Direct -> field("DIRECT")
        is ValueSourceTransfer -> sourceOperation(evidence.operation)
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
    when (val role = site.role) {
        ValueRole.ExpressionResult -> field("EXPRESSION_RESULT")
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
