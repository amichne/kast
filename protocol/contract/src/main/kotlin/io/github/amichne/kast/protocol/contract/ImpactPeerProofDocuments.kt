package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

/** Historical completed peer acquisition; later source publication does not revalidate this independent basis. */
@Serializable
data class ImpactPeerAcquisitionReceiptDocument(
    val completedBasis: ImpactSemanticBasisDocument,
    val budget: ImpactFlowBudgetDocument,
    val examinedWorkUnits: QueryDiscoveryCountDocument,
    val elapsedNanos: QueryDiscoveryCountDocument,
)

@Serializable
data class ImpactPeerSiteAdmissionDocument(
    val acquisition: ImpactPeerAcquisitionReceiptDocument,
    val selection: ImpactSiteAdmissionDocument,
)

@Serializable
enum class ImpactPeerContinuationReasonDocument {
    PEER_FLOW_NOT_INVESTIGATED
}

@Serializable
enum class ImpactPeerProofFailureDocument(val recoveryAction: ReadRecoveryAction) {
    WORK_RECEIPT_EXCEEDS_GRANT(ReadRecoveryAction.REPORT_FAILURE),
    TIME_RECEIPT_EXCEEDS_GRANT(ReadRecoveryAction.REPORT_FAILURE),
    SITE_WORK_EXCEEDS_ACQUISITION_WORK(ReadRecoveryAction.REPORT_FAILURE),
    SITE_GRANT_EXCEEDS_ACQUISITION_GRANT(ReadRecoveryAction.REPORT_FAILURE),
    TARGET_BASIS_MISMATCH(ReadRecoveryAction.REACQUIRE_AUTHORITY),
    TARGET_IDENTITY_MISMATCH(ReadRecoveryAction.REACQUIRE_AUTHORITY),
    SOURCE_BASIS_MISMATCH(ReadRecoveryAction.REACQUIRE_AUTHORITY),
    SAME_SOURCE_ROOT(ReadRecoveryAction.CORRECT_REQUEST),
    CONNECTION_MISMATCH(ReadRecoveryAction.REPORT_FAILURE),
}
