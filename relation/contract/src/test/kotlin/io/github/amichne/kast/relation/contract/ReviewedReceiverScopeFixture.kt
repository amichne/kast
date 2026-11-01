package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType

internal class ReviewedReceiverScopeFixture(
    val case: ReviewedEagerAdmissionCase = ReviewedEagerAdmissionCase.RUN_RECEIVER,
    private val valueParameters: List<String> = emptyList(),
    private val contextReceivers: List<String> = emptyList(),
) {
    val base = ReviewedValueFixture()
    val receiverType = CanonicalCompilerType.fromCompiler("fixture/Payload").flowRefined()
    val body = scopeBody()
    private val position = base.position(case.callbackPositions.single())
    private val selected = ReviewedLibraryCallable.admit(case.target(base), position).flowRefined()
    private val artifact =
        ReviewedLibraryArtifact.admit(
                selected,
                base.digest(ReviewedPositionFixture.BINARY),
                base.digest(ReviewedPositionFixture.SOURCES),
            )
            .flowRefined()
    val binding =
        ReviewedCallbackValueBinding.admit(
                enclosing = base.enclosing,
                body = body,
                occurrence = base.occurrence,
                supplier = base.supplier,
                selected = selected,
                artifact = artifact,
            )
            .flowRefined()
    val declared =
        DeclaredCallbackValueBinding.admit(
                enclosing = base.enclosing,
                body = body,
                contract = contract(),
                resultEffect = DeclaredCallbackResultEffect.UNSPECIFIED,
            )
            .flowRefined()
    private val target =
        RelationEndpoint.resolve(base.enclosing.lease, base.enclosing.scope, binding.target).flowRefined()
    val invocation = ValueInvocation.fromCompiler(base.enclosing, binding.occurrence.range, target).flowRefined()

    fun receiverProof(): ReviewedCallbackReceiver =
        ReviewedCallbackReceiver.fromCompiler(
                binding = binding,
                declared = declared,
                body = body,
                kind = ValueReceiverKind.EXTENSION,
                type = receiverType,
            )
            .flowRefined()

    fun actual(
        path: ValueAccessPath = base.fieldPath,
        role: ValueRole =
            if (case == ReviewedEagerAdmissionCase.WITH) ValueRole.Argument(invocation, base.position(0))
            else ValueRole.Receiver(invocation, ValueReceiverKind.EXTENSION),
    ): ValueSite =
        ValueSite.fromCompiler(
                enclosing = base.enclosing,
                range = flowRange(424, 433),
                role = role,
                valuePath = path,
            )
            .flowRefined()

    fun returned(role: ValueRole = ValueRole.Return): ValueSite =
        ValueSite.fromCompiler(
                enclosing = base.enclosing,
                range = flowRange(436, 457),
                role = role,
                executionOwner = ValueExecutionOwner.declaredCallback(declared),
                valuePath = base.fieldPath,
            )
            .flowRefined()

    private fun scopeBody(): RelationCallableBody.Anonymous {
        val range = flowRange(434, 459)
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(base.enclosing.file, range),
                    rawReceiverType =
                        if (case == ReviewedEagerAdmissionCase.RUN_TOP_LEVEL) null else receiverType.value,
                    rawContextReceiverTypes = contextReceivers,
                    rawValueParameterTypes = valueParameters,
                    rawTypeParameterCount = 0,
                )
                .flowRefined() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(base.enclosing.file, range, signature).flowRefined()
    }

    private fun contract(): CallbackDependencyContract =
        CallbackDependencyContract.fromCompiler(
                basis = base.enclosing.lease.identity,
                occurrence = base.occurrence,
                owner = base.supplier,
                target = selected.target,
                position = position,
                classDigest = DependencyClassDigest.parse(artifact.facadeSha256).flowRefined(),
                provenance = CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                invocationKind = CallbackDependencyInvocationKind.EXACTLY_ONCE,
            )
            .flowRefined()
}
