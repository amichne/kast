package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeVerifyQualification
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyResult
internal object CanonicalChangeSerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

    val changePlanRequest = factory.create(
        ChangePlanRequestDocument.serializer(),
        ChangePlanRequest::toSerializableDocument,
        ChangePlanRequestDocument::toContract,
    )
    val changePlanResult = factory.create(
        ChangePlanResultDocument.serializer(),
        ChangePlanResult::toSerializableDocument,
        ChangePlanResultDocument::toContract,
    )
    val changePlanQualification = factory.create(
        ChangePlanQualificationDocument.serializer(),
        ChangePlanQualification::toSerializableDocument,
        ChangePlanQualificationDocument::toContract,
    )
    val changePlanRejection = factory.create(
        ChangePlanRejectionDocument.serializer(),
        ChangePlanRejection::toSerializableDocument,
        ChangePlanRejectionDocument::toContract,
    )

    val changeApplyRequest = factory.create(
        ChangeApplyRequestDocument.serializer(),
        ChangeApplyRequest::toSerializableDocument,
        ChangeApplyRequestDocument::toContract,
    )
    val changeApplyResult = factory.create(
        ChangeApplyResultDocument.serializer(),
        ChangeApplyResult::toSerializableDocument,
        ChangeApplyResultDocument::toContract,
    )
    val changeApplyQualification = factory.create(
        ChangeApplyQualificationDocument.serializer(),
        ChangeApplyQualification::toSerializableDocument,
        ChangeApplyQualificationDocument::toContract,
    )
    val changeApplyRejection = factory.create(
        ChangeApplyRejectionDocument.serializer(),
        ChangeApplyRejection::toSerializableDocument,
        ChangeApplyRejectionDocument::toContract,
    )

    val changeVerifyRequest = factory.create(
        ChangeVerifyRequestDocument.serializer(),
        ChangeVerifyRequest::toSerializableDocument,
        ChangeVerifyRequestDocument::toContract,
    )
    val changeVerifyResult = factory.create(
        ChangeVerifyResultDocument.serializer(),
        ChangeVerifyResult::toSerializableDocument,
        ChangeVerifyResultDocument::toContract,
    )
    val changeVerifyQualification = factory.create(
        ChangeVerifyQualificationDocument.serializer(),
        ChangeVerifyQualification::toSerializableDocument,
        ChangeVerifyQualificationDocument::toContract,
    )
    val changeVerifyRejection = factory.create(
        ChangeVerifyRejectionDocument.serializer(),
        ChangeVerifyRejection::toSerializableDocument,
        ChangeVerifyRejectionDocument::toContract,
    )

    val changeRecoverRequest = factory.create(
        ChangeRecoverRequestDocument.serializer(),
        ChangeRecoverRequest::toSerializableDocument,
        ChangeRecoverRequestDocument::toContract,
    )
    val changeRecoverResult = factory.create(
        ChangeRecoverResultDocument.serializer(),
        ChangeRecoverResult::toSerializableDocument,
        ChangeRecoverResultDocument::toContract,
    )
    val changeRecoverQualification = factory.create(
        ChangeRecoverQualificationDocument.serializer(),
        ChangeRecoverQualification::toSerializableDocument,
        ChangeRecoverQualificationDocument::toContract,
    )
    val changeRecoverRejection = factory.create(
        ChangeRecoverRejectionDocument.serializer(),
        ChangeRecoverRejection::toSerializableDocument,
        ChangeRecoverRejectionDocument::toContract,
    )
}
