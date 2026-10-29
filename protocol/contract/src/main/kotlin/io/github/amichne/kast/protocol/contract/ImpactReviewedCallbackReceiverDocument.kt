package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

@Serializable
data class ImpactReviewedCallbackReceiverDocument(
    val binding: ImpactReviewedValueBindingDocument,
    val declared: ImpactDeclaredCallbackValueBindingDocument,
    val kind: ImpactReceiverKindDocument,
    val compilerType: ProtocolText,
)

internal fun ImpactDeclaredCallbackReceiverDocument.hasType(type: String): Boolean =
    this is ImpactDeclaredCallbackReceiverDocument.Present && compilerType.value == type

internal fun ImpactDeclaredCallbackReceiverDocument.compilerReceiverDocument(): CompilerReceiverDocument =
    when (this) {
        ImpactDeclaredCallbackReceiverDocument.Absent -> CompilerReceiverDocument.Absent
        is ImpactDeclaredCallbackReceiverDocument.Present -> CompilerReceiverDocument.Present(compilerType)
    }
