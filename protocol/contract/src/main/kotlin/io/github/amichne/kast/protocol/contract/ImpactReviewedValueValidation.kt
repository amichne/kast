package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Wire admission preserves the reviewed identities without asserting an actual execution. */
fun ImpactReviewedValueTransferDocument.admitsEvidence(): Boolean =
    binding.admits(binding.enclosing) &&
        source.isOnBasis(binding.basis) &&
        target.isOnBasis(binding.basis) &&
        source.enclosing == binding.enclosing &&
        target.enclosing == binding.enclosing &&
        source.executionOwner == ImpactExecutionOwnerDocument.ReviewedCallback(binding) &&
        target.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        source.role == ImpactValueRoleDocument.Return &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        target.range == binding.supplierRange &&
        preservesReturnState()

private fun ImpactReviewedValueTransferDocument.preservesReturnState(): Boolean =
    source.conditions.hasSameConditions(target.conditions) &&
        source.callStack.hasSameCallStackIdentity(target.callStack) &&
        listOf(ImpactValuePathSegmentDocument.Element) + source.valuePath.values == target.valuePath.values

internal fun ImpactReviewedValueBindingDocument.admits(host: ImpactDeclarationReferenceDocument): Boolean =
    basis == host.basis &&
        enclosing == host &&
        selected.declaration.basis == basis &&
        enclosing.declarationFailure() is Refinement.Refined &&
        selected.declaration.declarationFailure() is Refinement.Refined &&
        body.file == host.file &&
        host.range.containsExecutionRange(supplierRange) &&
        supplierRange.containsExecutionRange(body.range) &&
        enclosing
            .copy(file = body.file, range = body.range, compilerIdentity = body.compilerIdentity)
            .declarationFailure() is Refinement.Refined &&
        selected.admitsReviewedMap() &&
        artifacts.admitsReviewedMap()

private fun ImpactReviewedFunctionDocument.admitsReviewedMap(): Boolean {
    if (
        qualifiedIdentity.value != "kotlin.collections.map" ||
            receiver.value != "kotlin/collections/Iterable<T>" ||
            !declaration.file.value.endsWith("!/kotlin/collections/CollectionsKt.class")
    )
        return false
    if (contextReceivers.values.isNotEmpty() || valueParameters.values.map { it.value } != listOf("(T)->R"))
        return false
    if (typeParameterCount.value != 2L || formalIndex.value != 0) return false
    val count =
        when (val parsed = CompilerTypeParameterCountDocument.parse(typeParameterCount.value.toInt())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return false
        }
    val signature =
        CompilerSignatureDocument.Function(
            qualifiedIdentity,
            CompilerReceiverDocument.Present(receiver),
            contextReceivers,
            valueParameters,
            count,
        )
    return CompilerSymbolEvidenceDocument.restore(declaration.compilerIdentity, signature) is Refinement.Refined
}

private fun ImpactReviewedArtifactsDocument.admitsReviewedMap(): Boolean =
    binaryArtifactSha256.value == "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3" &&
        sourceArtifactSha256.value == "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2" &&
        sourceEntry.value == "commonMain/generated/_Collections.kt" &&
        sourceEntrySha256.value == "aad6a5398189369be27f65952a5f2c935e3bf00d166e2046a0fb4be0d007a85c" &&
        facadeSha256.value == "7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878" &&
        implementationSha256.value == "e5afca255b864c70152adeeecd88367f5477966ab26bd16f8db2b5c56c995296" &&
        mapLines.first.value == REVIEWED_MAP_FIRST_LINE &&
        mapLines.last.value == REVIEWED_MAP_LAST_LINE &&
        mapToLines.first.value == REVIEWED_MAP_TO_FIRST_LINE &&
        mapToLines.last.value == REVIEWED_MAP_TO_LAST_LINE

private const val REVIEWED_MAP_FIRST_LINE = 1744
private const val REVIEWED_MAP_LAST_LINE = 1746
private const val REVIEWED_MAP_TO_FIRST_LINE = 1819
private const val REVIEWED_MAP_TO_LAST_LINE = 1823
