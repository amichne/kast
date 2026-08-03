package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.RawExactFileObservationPath
import io.github.amichne.kast.api.contract.result.RawExactFileObservationResult
import io.github.amichne.kast.api.validation.ParsedRawExactFileObservationQuery
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.mutation.SecureWorkspaceFileObservation
import kotlinx.coroutines.withContext

internal suspend fun KastIndexerBackend.rawExactFileObservationOperation(
    query: ParsedRawExactFileObservationQuery,
): RawExactFileObservationResult = mutationAttemptGate.observe(query.mutationAttemptId) {
    withContext(readDispatcher) {
        val workspaceRoot = workspaceIdentity.canonicalWorkspaceRootPath
        val target = workspaceRoot.resolve(query.filePath.value).normalize()
        check(target.startsWith(workspaceRoot)) {
            "Parsed raw exact-file observation path escaped the exact workspace root"
        }
        secureObservationResult(
            filePath = query.filePath,
            observation = exactFileImageMutation.observeExactFile(
                target = target,
                mutation = IdeaWorkspaceMutation.TEXT_EDIT,
            ),
        )
    }
}

private fun secureObservationResult(
    filePath: RawExactFileObservationPath,
    observation: SecureWorkspaceFileObservation,
): RawExactFileObservationResult = when (observation) {
    SecureWorkspaceFileObservation.Absent -> RawExactFileObservationResult.Absent(filePath)
    is SecureWorkspaceFileObservation.Present -> RawExactFileObservationResult.Present(
        filePath = filePath,
        image = ExactByteImage.of(
            bytes = observation.bytes,
            expectedSha256 = observation.sha256,
        ),
    )
}
