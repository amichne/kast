package support.delivery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val canonical = when (
            val refined = refineProjectionGeneration(canonicalGenerationContents.get())
        ) {
            is ProjectionGenerationRefinement.Refined -> refined.generation
            is ProjectionGenerationRefinement.Rejected -> throw GradleException(
                "canonical fixture rejected: ${refined.failure}",
            )
        }
        val fixtures = listOf(
            DeliveryProjectionNegativeCase.REORDERED_JSON_KEYS to canonical.replacing(
                ProjectionArtifactId.PROGRAM,
                reorderTopLevelKeys(canonical.program),
            ),
            DeliveryProjectionNegativeCase.NON_REPEATABLE_GENERATION to canonical.replacing(
                ProjectionArtifactId.PROGRAM,
                canonical.program + " ",
            ),
            DeliveryProjectionNegativeCase.SCHEMA_INVALID_PROGRAM to canonical.replacing(
                ProjectionArtifactId.PROGRAM,
                replaceProgramField(canonical.program, "targetHead", JsonPrimitive("invalid")),
            ),
            DeliveryProjectionNegativeCase.STATUS_FIELD_PRESENT to canonical.replacing(
                ProjectionArtifactId.PROGRAM,
                replaceProgramField(canonical.program, "status", JsonPrimitive("COMPLETE")),
            ),
        )
        val expected = listOf(
            DeliveryProjectionFailure.NON_CANONICAL_JSON,
            DeliveryProjectionFailure.NON_REPEATABLE_GENERATION,
            DeliveryProjectionFailure.SCHEMA_VALIDATION_FAILED,
            DeliveryProjectionFailure.STATUS_FIELD_PRESENT,
        )
        val observed = fixtures.mapIndexed { index, (case, fixture) ->
            val comparison = if (case == DeliveryProjectionNegativeCase.NON_REPEATABLE_GENERATION) {
                canonical
            } else {
                fixture
            }
            val failure = when (val result = admitDeterministicProgramProjection(fixture, comparison)) {
                is DeliveryProjectionAdmission.Rejected -> result.failure
                is DeliveryProjectionAdmission.Admitted -> throw GradleException(
                    "negative projection fixture was admitted: $case",
                )
            }
            if (failure != expected[index]) {
                throw GradleException("$case rejected as $failure instead of ${expected[index]}")
            }
            failure
        }
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            encodeProjectionNegativeProof(
                DeliveryProjectionNegativeProofDocument(
                    observedFailures = observed,
                    rejectedCases = fixtures.map { it.first },
                    schemaVersion = 1,
                    taskId = "KVP-005",
                ),
            ),
        )
    }
}

private val projectionFixtureJson = Json { ignoreUnknownKeys = false }

private fun reorderTopLevelKeys(document: String): String {
    val source = projectionFixtureJson.parseToJsonElement(document) as JsonObject
    val entries = source.entries.toList()
    return JsonObject(linkedMapOf(entries.last().toPair(), *entries.dropLast(1).map {
        it.toPair()
    }.toTypedArray())).toString() + "\n"
}

private fun replaceProgramField(document: String, key: String, value: JsonPrimitive): String {
    val source = projectionFixtureJson.parseToJsonElement(document) as JsonObject
    return canonicalJson(JsonObject(source + (key to value))) + "\n"
}
