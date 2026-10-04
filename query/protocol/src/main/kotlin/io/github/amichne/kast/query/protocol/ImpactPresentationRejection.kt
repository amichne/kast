package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.PositiveLimitFailure
import io.github.amichne.kast.protocol.contract.ImpactModelPrimitiveFailure
import io.github.amichne.kast.protocol.contract.ImpactPresentationBudgetCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationCollectionCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationCountCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactPresentationFingerprintCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationModelCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationOffsetCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationTextCause
import io.github.amichne.kast.protocol.contract.ProtocolCollectionFailure
import io.github.amichne.kast.protocol.contract.ProtocolOffsetFailure
import io.github.amichne.kast.protocol.contract.ProtocolTextFailure
import io.github.amichne.kast.protocol.contract.QueryDiscoveryMeasureFailure
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFailure
import io.github.amichne.kast.protocol.contract.QueryRunRejection

internal fun ImpactPathProjectionFailure.presentationRejection(): QueryRunRejection.ImpactPresentationRejected =
    QueryRunRejection.ImpactPresentationRejected(
        when (this) {
            is ImpactPathProjectionFailure.Text -> ImpactPresentationFailureDocument.Text(cause.presentationCause())
            is ImpactPathProjectionFailure.Offset -> ImpactPresentationFailureDocument.Offset(cause.presentationCause())
            is ImpactPathProjectionFailure.Model -> ImpactPresentationFailureDocument.Model(cause.presentationCause())
            is ImpactPathProjectionFailure.Collection ->
                ImpactPresentationFailureDocument.Collection(cause.presentationCause())
            is ImpactPathProjectionFailure.DomainFingerprint ->
                ImpactPresentationFailureDocument.DomainFingerprint(cause.presentationCause())
            is ImpactPathProjectionFailure.Count -> ImpactPresentationFailureDocument.Count(cause.presentationCause())
            is ImpactPathProjectionFailure.Budget -> ImpactPresentationFailureDocument.Budget(cause.presentationCause())
            ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED ->
                ImpactPresentationFailureDocument.DomainProjectionRejected
        }
    )

private fun ProtocolTextFailure.presentationCause(): ImpactPresentationTextCause =
    when (this) {
        ProtocolTextFailure.BLANK -> ImpactPresentationTextCause.BLANK
        ProtocolTextFailure.TOO_LONG -> ImpactPresentationTextCause.TOO_LONG
    }

private fun ProtocolOffsetFailure.presentationCause(): ImpactPresentationOffsetCause =
    when (this) {
        ProtocolOffsetFailure.NEGATIVE -> ImpactPresentationOffsetCause.NEGATIVE
    }

private fun ImpactModelPrimitiveFailure.presentationCause(): ImpactPresentationModelCause =
    when (this) {
        ImpactModelPrimitiveFailure.INVALID_IDENTIFIER -> ImpactPresentationModelCause.INVALID_IDENTIFIER
        ImpactModelPrimitiveFailure.NOT_POSITIVE -> ImpactPresentationModelCause.NOT_POSITIVE
        ImpactModelPrimitiveFailure.UNSUPPORTED_FORMAT -> ImpactPresentationModelCause.UNSUPPORTED_FORMAT
    }

private fun ProtocolCollectionFailure.presentationCause(): ImpactPresentationCollectionCause =
    when (this) {
        ProtocolCollectionFailure.TOO_LARGE -> ImpactPresentationCollectionCause.TOO_LARGE
    }

private fun QueryRelationDomainFailure.presentationCause(): ImpactPresentationFingerprintCause =
    when (this) {
        QueryRelationDomainFailure.INVALID_FINGERPRINT -> ImpactPresentationFingerprintCause.INVALID_FINGERPRINT
    }

private fun QueryDiscoveryMeasureFailure.presentationCause(): ImpactPresentationCountCause =
    when (this) {
        QueryDiscoveryMeasureFailure.NEGATIVE -> ImpactPresentationCountCause.NEGATIVE
    }

private fun PositiveLimitFailure.presentationCause(): ImpactPresentationBudgetCause =
    when (this) {
        PositiveLimitFailure.NOT_POSITIVE -> ImpactPresentationBudgetCause.NOT_POSITIVE
    }
