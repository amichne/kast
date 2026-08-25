package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class VerifyDeliveryProjectionsNegativeTask : DefaultTask() {
    @get:Input
    abstract val canonicalGenerationContents: MapProperty<String, String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verifyNegativeCases() {
        val configured = when (
            val refined = refineProjectionGeneration(canonicalGenerationContents.get())
        ) {
            is ProjectionGenerationRefinement.Refined -> refined.generation
            is ProjectionGenerationRefinement.Rejected -> throw GradleException(
                "canonical fixture rejected: ${refined.failure}",
            )
        }
        if (configured != DeterministicProgramProjection.generate(
                KastVfsPassiveReusedIndexProgram.validated,
            )
        ) throw GradleException("configured fixture differs from the Kotlin projection authority")
        val proof = when (val result = deriveKvp005ProjectionNegativeProof()) {
            is Kvp005ProjectionNegativeProofResult.Complete -> result.proof
            is Kvp005ProjectionNegativeProofResult.Rejected -> throw GradleException(
                "KVP-005 negative proof rejected: ${result.failure}",
            )
        }
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            encodeKvp005ProjectionNegativeProof(proof),
        )
    }
}
