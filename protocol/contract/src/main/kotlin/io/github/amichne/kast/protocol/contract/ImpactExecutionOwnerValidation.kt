package io.github.amichne.kast.protocol.contract

/** Structural ownership admission does not establish a native execution body. */
fun ImpactValueSiteReferenceDocument.admitsExecutionOwner(): Boolean =
    executionOwner.admits(enclosing, range) &&
        admitsValueState() &&
        when (val position = role) {
            is ImpactValueRoleDocument.LoopResult -> position.admits(this)
            is ImpactValueRoleDocument.Argument -> position.invocation.admitsOwner(enclosing, executionOwner)
            is ImpactValueRoleDocument.Receiver -> position.invocation.admitsOwner(enclosing, executionOwner)
            is ImpactValueRoleDocument.Formal -> position.position.admits(this)
            ImpactValueRoleDocument.ExpressionResult,
            ImpactValueRoleDocument.LocalBinding,
            ImpactValueRoleDocument.LocalRead,
            ImpactValueRoleDocument.Return,
            ImpactValueRoleDocument.PropertyAssignment -> true
        }

private fun ImpactInvocationReferenceDocument.admitsOwner(
    host: ImpactDeclarationReferenceDocument,
    owner: ImpactExecutionOwnerDocument,
): Boolean = executionOwner == owner && executionOwner.admits(host, range)

internal fun ImpactExecutionOwnerDocument.admits(
    host: ImpactDeclarationReferenceDocument,
    anchor: ImpactSourceRangeDocument,
): Boolean =
    when (this) {
        ImpactExecutionOwnerDocument.Declaration -> host.range.containsExecutionRange(anchor)
        is ImpactExecutionOwnerDocument.ReviewedCallback ->
            binding.admits(host) && binding.body.range.containsExecutionRange(anchor)
        is ImpactExecutionOwnerDocument.DeclaredCallback ->
            binding.admits(host) && binding.body.range.containsExecutionRange(anchor)
        is ImpactExecutionOwnerDocument.Callback ->
            body.file == host.file &&
                host.range.containsExecutionRange(body.range) &&
                body.range.containsExecutionRange(anchor) &&
                invocations.values.isNotEmpty() &&
                admitsSelection() &&
                invocations.values.all { it.admitsCapture(host) }
    }

private fun ImpactCaptureInvocationDocument.admitsCapture(host: ImpactDeclarationReferenceDocument): Boolean =
    enclosing.basis == host.basis &&
        supply.callable.basis == host.basis &&
        body.file == host.file &&
        host.range.containsExecutionRange(body.range) &&
        supplyOwner.file == enclosing.file &&
        supplyOwner.range.containsExecutionRange(supply.range) &&
        supply.callable.range.containsExecutionRange(parameter) &&
        invocationOwner.range.containsExecutionRange(invocation)

internal fun ImpactSourceRangeDocument.containsExecutionRange(other: ImpactSourceRangeDocument): Boolean =
    other.start.value < other.end.value && other.start.value >= start.value && other.end.value <= end.value

internal fun ImpactExecutionOwnerDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (basis) {
        is ImpactSemanticBasisDocument.Published,
        is ImpactSemanticBasisDocument.Live ->
            when (this) {
                ImpactExecutionOwnerDocument.Declaration -> true
                is ImpactExecutionOwnerDocument.ReviewedCallback ->
                    binding.basis == basis && binding.admits(binding.enclosing)
                is ImpactExecutionOwnerDocument.DeclaredCallback ->
                    binding.contract.basis == basis && binding.admits(binding.enclosing)
                is ImpactExecutionOwnerDocument.Callback ->
                    admitsSelection() &&
                        invocations.values.all { it.enclosing.basis == basis && it.supply.callable.basis == basis }
            }
    }

internal fun ImpactInvocationReferenceDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    callable.basis == basis && executionOwner.isOnBasis(basis)

internal fun ImpactCallOccurrenceDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is ImpactCallOccurrenceDocument.Declared -> invocation.isOnBasis(basis)
    }
