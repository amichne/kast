package io.github.amichne.kast.server.change

/** Closed composition state for the operation-specific verified add-file route. */
sealed interface VerifiedAddFileBinding {
    data object Unavailable : VerifiedAddFileBinding

    class Native(
        val operations: NativeVerifiedAddFileOperations,
    ) : VerifiedAddFileBinding
}

interface NativeVerifiedAddFileOperations {
    /**
     * Effect transition: [VerifiedAddFilePlanRequest] -> [VerifiedAddFilePlanResult].
     *
     * A planned result carries one distinct server-issued identity, initial version, exact preview,
     * and absent-target/compiler ownership proof retained by the native owner. Expected failures are
     * the closed [VerifiedAddFileFailure] family.
     */
    suspend fun plan(request: VerifiedAddFilePlanRequest): VerifiedAddFilePlanResult

    /**
     * Effect transition: [VerifiedAddFileApplyRequest] -> [VerifiedAddFileApplyResult].
     *
     * The request carries explicit approval over one persisted plan version. A verified result is
     * terminal version five and retains recovery, application, publication, and PSI evidence.
     * Expected failures are the closed [VerifiedAddFileFailure] family.
     */
    suspend fun apply(request: VerifiedAddFileApplyRequest): VerifiedAddFileApplyResult
}
