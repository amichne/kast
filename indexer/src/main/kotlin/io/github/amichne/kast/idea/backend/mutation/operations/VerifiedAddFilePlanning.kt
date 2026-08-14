package io.github.amichne.kast.idea.backend.mutation.operations

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.api.contract.query.AddFilePlanQuery
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.server.change.VerifiedAddFileAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFileIntent
import io.github.amichne.kast.server.change.VerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal suspend fun planVerifiedAddFile(
    backend: KastIndexerBackend,
    intent: VerifiedAddFileIntent,
    progress: VerifiedAddFileProgress,
): PlanAttempt = try {
    val exact = backend.planAddFile(
        AddFilePlanQuery(
            targetPath = AdditionTargetPath.parse(intent.targetPath.value),
            proposedContent = intent.content.value,
        ).parsed(),
    )
    when (val admission = VerifiedAddFilePlan.admit(intent, exact)) {
        is VerifiedAddFileAdmission.Admitted -> PlanAttempt.Planned(admission.value)
        is VerifiedAddFileAdmission.Rejected -> PlanAttempt.Rejected(
            rejected(progress, admission.failure),
        )
    }
} catch (failure: AdditionProofIncompleteException) {
    PlanAttempt.Rejected(rejected(progress, failure.toVerifiedFailure()))
} catch (_: ProcessCanceledException) {
    PlanAttempt.Rejected(rejected(progress, VerifiedAddFileFailure.CANCELLED))
} catch (_: CancellationException) {
    PlanAttempt.Rejected(rejected(progress, VerifiedAddFileFailure.CANCELLED))
} catch (_: Exception) {
    PlanAttempt.Rejected(rejected(progress, VerifiedAddFileFailure.PACKAGE_OR_DECLARATION_INVALID))
}

/**
 * Proof transition: `(Path, VerifiedAddFileIntent) -> TargetAdmission`.
 *
 * Absence means canonical containment is proven. Presence means the closed symlink-escape failure
 * is retained before semantic planning can collapse it into a broader ownership error. Raw paths
 * are extracted only at this filesystem admission boundary.
 */
internal fun admitVerifiedAddFileTarget(
    workspaceRoot: Path,
    intent: VerifiedAddFileIntent,
): TargetAdmission {
    val target = Path.of(intent.targetPath.value)
    if (Files.isSymbolicLink(target)) return TargetAdmission.symlinkEscape()
    val parent = target.parent ?: return TargetAdmission.symlinkEscape()
    val canonicalRoot = runCatching { workspaceRoot.toRealPath() }.getOrNull()
        ?: return TargetAdmission.symlinkEscape()
    val canonicalParent = runCatching { parent.toRealPath() }.getOrNull()
        ?: return TargetAdmission.symlinkEscape()
    if (canonicalParent != parent.toAbsolutePath().normalize()) return TargetAdmission.symlinkEscape()
    val canonicalTarget = canonicalParent.resolve(target.fileName).normalize()
    return if (canonicalTarget.startsWith(canonicalRoot)) TargetAdmission.Admitted
    else TargetAdmission.symlinkEscape()
}
