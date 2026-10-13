package io.github.amichne.kast.topology.build.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalAttributedInputs
import io.github.amichne.kast.topology.contract.historical.HistoricalAttributedSource
import io.github.amichne.kast.topology.contract.historical.HistoricalCaptureBasis
import io.github.amichne.kast.topology.contract.historical.HistoricalCaptureBasisFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalCapturePlan
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCaptureFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCaptureOutcome
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCapturePort

enum class HistoricalPublicationFailure {
    UNAVAILABLE,
    CANCELLED,
    IDENTITY_MISMATCH,
}

sealed interface HistoricalPublicationOutcome {
    data class Published(val identity: HistoricalDigest) : HistoricalPublicationOutcome

    data class Rejected(val cause: HistoricalPublicationFailure) : HistoricalPublicationOutcome
}

fun interface HistoricalPublicationPort {
    fun publish(basis: HistoricalCaptureBasis): HistoricalPublicationOutcome
}

sealed interface HistoricalCaptureResult {
    data object InvalidAttribution : HistoricalCaptureResult

    data class AttributionRejected(val cause: HistoricalGitFailure, val work: HistoricalAttributionWork) :
        HistoricalCaptureResult

    data class BasisRejected(val cause: HistoricalCaptureBasisFailure, val work: HistoricalAttributionWork) :
        HistoricalCaptureResult

    data class NativeRejected(val cause: HistoricalNativeCaptureFailure, val work: HistoricalAttributionWork) :
        HistoricalCaptureResult

    data class PublicationRejected(val cause: HistoricalPublicationFailure, val work: HistoricalAttributionWork) :
        HistoricalCaptureResult

    data class Published(val identity: HistoricalDigest, val work: HistoricalAttributionWork) : HistoricalCaptureResult
}

/** One explicit coordinator owns attribution, native capture, and the transition to publication. */
class HistoricalCaptureCoordinator(
    private val native: HistoricalNativeCapturePort,
    private val publication: HistoricalPublicationPort,
) {
    /** The input is the Git adapter's result, never a replacement for native capture. */
    fun capture(plan: HistoricalCapturePlan, attributionJson: String): HistoricalCaptureResult {
        val attribution =
            when (val read = HistoricalGitAttributionCodec.decode(attributionJson)) {
                is HistoricalAttributionRead.Attributed -> read.attribution
                is HistoricalAttributionRead.Rejected ->
                    return HistoricalCaptureResult.AttributionRejected(read.cause, read.work)
                HistoricalAttributionRead.InvalidDocument -> return HistoricalCaptureResult.InvalidAttribution
            }
        val inputs =
            when (
                val admitted =
                    HistoricalAttributedInputs.fromGitObservation(
                        plan,
                        attribution.revision,
                        attribution.files.map { HistoricalAttributedSource(it.path, it.content, it.blob) },
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return HistoricalCaptureResult.BasisRejected(admitted.failure, attribution.work)
            }
        val captured =
            when (val outcome = native.capture(plan)) {
                is HistoricalNativeCaptureOutcome.Captured -> outcome
                is HistoricalNativeCaptureOutcome.Rejected ->
                    return HistoricalCaptureResult.NativeRejected(outcome.cause, attribution.work)
            }
        val basis =
            when (val admitted = HistoricalCaptureBasis.fromNativeCapture(plan, inputs, captured)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return HistoricalCaptureResult.BasisRejected(admitted.failure, attribution.work)
            }
        return when (val outcome = publication.publish(basis)) {
            is HistoricalPublicationOutcome.Published ->
                if (outcome.identity == basis.snapshot.identity)
                    HistoricalCaptureResult.Published(outcome.identity, attribution.work)
                else
                    HistoricalCaptureResult.PublicationRejected(
                        HistoricalPublicationFailure.IDENTITY_MISMATCH,
                        attribution.work,
                    )
            is HistoricalPublicationOutcome.Rejected ->
                HistoricalCaptureResult.PublicationRejected(outcome.cause, attribution.work)
        }
    }
}
