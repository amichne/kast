package io.github.amichne.kast.relation.contract

internal fun StringBuilder.binding(evidence: CallbackBindingEvidence) {
    when (evidence) {
        is CallbackBindingEvidence.Bound -> {
            field("BOUND")
            endpoint(evidence.binding.invocation.enclosing)
            range(evidence.binding.invocation.range)
            body(evidence.binding.invocationOwner)
            endpoint(evidence.binding.invocation.callable)
            field(evidence.binding.position.value.toString())
            occurrence(evidence.binding.parameter)
        }
        is CallbackBindingEvidence.Default -> {
            field("DEFAULT")
            endpoint(evidence.binding.parameter.callable)
            field(evidence.binding.parameter.position.value.toString())
            occurrence(evidence.binding.parameter.parameter)
            occurrence(evidence.binding.defaultValue)
        }
        is CallbackBindingEvidence.Direct -> {
            field("DIRECT")
            field(evidence.binding.basis.workspaceRoot.value)
            field(evidence.binding.basis.revisionKey.value)
            occurrence(evidence.binding.occurrence)
            body(evidence.binding.owner)
        }
        is CallbackBindingEvidence.DependencyContract -> {
            field("DEPENDENCY_CONTRACT")
            field(evidence.binding.basis.workspaceRoot.value)
            field(evidence.binding.basis.revisionKey.value)
            occurrence(evidence.binding.occurrence)
            body(evidence.binding.owner)
            field(evidence.binding.target.file.stableValue)
            range(evidence.binding.target.range)
            field(evidence.binding.target.name.value)
            field(evidence.binding.target.signature.canonicalEncoding().value)
            field(evidence.binding.position.value.toString())
            field(evidence.binding.classDigest.value)
            field(evidence.binding.provenance.name)
            field(evidence.binding.invocationKind.name)
        }
        is CallbackBindingEvidence.Unavailable -> {
            field("UNAVAILABLE")
            field(evidence.cause.name)
        }
    }
}
