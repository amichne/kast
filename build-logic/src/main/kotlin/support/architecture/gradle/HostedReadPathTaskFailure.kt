package support.architecture.gradle

import support.architecture.ArchitecturePolicyFailure
import support.architecture.HostedReadInjectionFailure
import support.architecture.HostedReadPathDerivation
import support.architecture.HostedReadPathReportFailure
import support.architecture.HostedReadReportMutationVerification
import support.architecture.Kvp018PredecessorArtifactRefinement
import support.architecture.Kvp018PredecessorReceiptArtifact
import support.architecture.Kvp018PredecessorReceiptFailure
import support.architecture.Kvp018PredecessorReceiptId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal sealed interface HostedReadPathTaskFailure {
    data class CanonicalArchitectureRejected(val failures: List<ArchitecturePolicyFailure>) :
        HostedReadPathTaskFailure
    data class HostedModuleUnavailable(val module: support.architecture.ModuleId) :
        HostedReadPathTaskFailure
    data class InjectionRejected(
        val first: HostedReadInjectionFailure,
        val additional: List<HostedReadInjectionFailure>,
    ) : HostedReadPathTaskFailure
    data class ClassInputRejected(val failure: HostedReadClassInputFailure) :
        HostedReadPathTaskFailure
    data class ProjectInputRejected(val failure: HostedReadProjectInputFailure) :
        HostedReadPathTaskFailure
    data class ExternalInputRejected(val failure: HostedReadExternalInputFailure) :
        HostedReadPathTaskFailure
    data class DerivationRejected(val rejection: HostedReadPathDerivation.Rejected) :
        HostedReadPathTaskFailure
    data class ReceiptUnreadable(val id: Kvp018PredecessorReceiptId, val path: Path) :
        HostedReadPathTaskFailure
    data class ReceiptRejected(val failure: Kvp018PredecessorReceiptFailure) :
        HostedReadPathTaskFailure
    data class PredecessorSetRejected(val failure: Kvp018PredecessorReceiptFailure) :
        HostedReadPathTaskFailure
    data class ReportRejected(val failure: HostedReadPathReportFailure) :
        HostedReadPathTaskFailure
    data class MutationProofRejected(
        val rejection: HostedReadReportMutationVerification.Rejected,
    ) : HostedReadPathTaskFailure
    data class ReportWriteRejected(val path: Path) : HostedReadPathTaskFailure
}

internal sealed interface HostedReadPredecessorReceiptObservation {
    data class Observed(val artifact: Kvp018PredecessorReceiptArtifact) :
        HostedReadPredecessorReceiptObservation
    data class Rejected(val failure: HostedReadPathTaskFailure) :
        HostedReadPredecessorReceiptObservation
}

/**
 * Proof transition: `(Kvp018PredecessorReceiptId, Path) ->
 * HostedReadPredecessorReceiptObservation`.
 *
 * Establishes generated-schema decoding, exact completion identity, and a self-derived receipt
 * digest. Malformed or unreadable input remains closed [HostedReadPathTaskFailure] data. Raw path
 * and JSON extraction is confined to this Gradle task/receipt boundary.
 */
internal fun observeKvp018PredecessorReceipt(
    id: Kvp018PredecessorReceiptId,
    path: Path,
): HostedReadPredecessorReceiptObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return HostedReadPredecessorReceiptObservation.Rejected(
            HostedReadPathTaskFailure.ReceiptUnreadable(id, path),
        )
    } catch (_: SecurityException) {
        return HostedReadPredecessorReceiptObservation.Rejected(
            HostedReadPathTaskFailure.ReceiptUnreadable(id, path),
        )
    }
    return when (val result = Kvp018PredecessorReceiptArtifact.decode(id, raw)) {
        is Kvp018PredecessorArtifactRefinement.Admitted ->
            HostedReadPredecessorReceiptObservation.Observed(result.artifact)
        is Kvp018PredecessorArtifactRefinement.Rejected ->
            HostedReadPredecessorReceiptObservation.Rejected(
                HostedReadPathTaskFailure.ReceiptRejected(result.failure),
            )
    }
}

/**
 * Proof transition: `HostedReadPathTaskFailure -> String`.
 *
 * Renders the exhaustive finite task failure family. Typed failure data may be reduced to text
 * only at the owning Gradle task boundary.
 */
internal fun HostedReadPathTaskFailure.renderAtGradleBoundary(): String = when (this) {
    is HostedReadPathTaskFailure.CanonicalArchitectureRejected ->
        "canonical architecture rejected: $failures"
    is HostedReadPathTaskFailure.HostedModuleUnavailable ->
        "hosted module unavailable: $module"
    is HostedReadPathTaskFailure.InjectionRejected ->
        "negative proof missing classifications: ${listOf(first) + additional}"
    is HostedReadPathTaskFailure.ClassInputRejected -> "class discovery rejected: $failure"
    is HostedReadPathTaskFailure.ProjectInputRejected -> "project runtime input rejected: $failure"
    is HostedReadPathTaskFailure.ExternalInputRejected -> "external runtime input rejected: $failure"
    is HostedReadPathTaskFailure.DerivationRejected -> "hosted path derivation rejected: $rejection"
    is HostedReadPathTaskFailure.ReceiptUnreadable -> "predecessor receipt unreadable: $id at $path"
    is HostedReadPathTaskFailure.ReceiptRejected -> "predecessor receipt rejected: $failure"
    is HostedReadPathTaskFailure.PredecessorSetRejected ->
        "predecessor receipt set rejected: $failure"
    is HostedReadPathTaskFailure.ReportRejected -> "report admission rejected: $failure"
    is HostedReadPathTaskFailure.MutationProofRejected ->
        "report mutation proof rejected: $rejection"
    is HostedReadPathTaskFailure.ReportWriteRejected -> "report write rejected: $path"
}
