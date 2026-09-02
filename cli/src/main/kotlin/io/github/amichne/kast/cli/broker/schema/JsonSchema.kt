package io.github.amichne.kast.cli.broker.schema

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.NonEmptyFailures
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.kernel.mapFailures
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

@JvmInline
internal value class JsonSchemaDigest private constructor(
    val value: String,
) {
    companion object {
        internal fun derive(document: JsonObject): JsonSchemaDigest = JsonSchemaDigest(
            "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonicalJson(document).toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )
    }
}

internal enum class JsonSchemaCompilationFailure {
    INVALID_DEFINITION,
}

@JvmInline
internal value class JsonConstraintDescription private constructor(
    val value: String,
) {
    companion object {
        internal fun admit(raw: String): JsonConstraintDescription? =
            raw.trim().takeIf { it.isNotEmpty() && it.length <= MAXIMUM_DESCRIPTION_LENGTH }
                ?.let(::JsonConstraintDescription)

        private const val MAXIMUM_DESCRIPTION_LENGTH = 4_096
    }
}

internal sealed interface JsonConstraintViolation {
    data class Reported(
        val description: JsonConstraintDescription,
    ) : JsonConstraintViolation

    data object Unspecified : JsonConstraintViolation

    companion object {
        internal fun from(raw: String): JsonConstraintViolation =
            JsonConstraintDescription.admit(raw)?.let(JsonConstraintViolation::Reported)
                ?: Unspecified
    }
}

/** A JSON value carrying proof that it satisfies one exact compiled schema identity. */
internal class ValidatedJsonValue private constructor(
    internal val element: JsonElement,
    internal val schemaDigest: JsonSchemaDigest,
) {
    companion object {
        internal fun admit(
            schema: CompiledJsonSchema,
            candidate: JsonElement,
        ): Validation<ValidatedJsonValue, JsonConstraintViolation> {
            val violations = schema.constraintViolations(candidate)
            return if (violations.isEmpty()) {
                Validation.Validated(ValidatedJsonValue(candidate, schema.digest))
            } else {
                Validation.Rejected(
                    NonEmptyFailures.from(
                        first = violations.first(),
                        remaining = violations.drop(1),
                    ),
                )
            }
        }
    }
}

/** Exact schema document plus its compiled validator. */
internal class CompiledJsonSchema private constructor(
    internal val document: JsonObject,
    internal val digest: JsonSchemaDigest,
    private val validator: Schema,
) {
    internal fun admit(
        candidate: JsonElement,
    ): Validation<ValidatedJsonValue, JsonConstraintViolation> =
        ValidatedJsonValue.admit(this, candidate)

    internal fun constraintViolations(candidate: JsonElement): List<JsonConstraintViolation> =
        validator.validate(canonicalJson(candidate), InputFormat.JSON)
            .map { validationMessage -> JsonConstraintViolation.from(validationMessage.message) }
            .sortedBy { violation -> violation.sortKey() }

    private fun JsonConstraintViolation.sortKey(): String = when (this) {
        is JsonConstraintViolation.Reported -> description.value
        JsonConstraintViolation.Unspecified -> ""
    }

    companion object {
        private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

        internal fun compile(
            document: JsonObject,
        ): Refinement<CompiledJsonSchema, JsonSchemaCompilationFailure> = try {
            val canonical = canonicalJson(document)
            Refinement.Refined(
                CompiledJsonSchema(
                    document = document,
                    digest = JsonSchemaDigest.derive(document),
                    validator = registry.getSchema(canonical),
                ),
            )
        } catch (_: RuntimeException) {
            Refinement.Rejected(JsonSchemaCompilationFailure.INVALID_DEFINITION)
        }
    }
}

internal object NetworkntJsonSchemaCompiler {
    internal fun compile(
        document: JsonObject,
    ): Refinement<CompiledJsonSchema, JsonSchemaCompilationFailure> =
        CompiledJsonSchema.compile(document)
}

internal sealed interface JsonDomainAdmissionFailure<out DomainFailure> {
    data class Constraint(
        val violation: JsonConstraintViolation,
    ) : JsonDomainAdmissionFailure<Nothing>

    data class Domain<DomainFailure>(
        val failure: DomainFailure,
    ) : JsonDomainAdmissionFailure<DomainFailure>
}

/**
 * Applicative admission from arbitrary JSON through one exact schema into a domain value.
 *
 * Domain refinement cannot run until schema proof exists, and callers cannot obtain the strong
 * value by validating a Boolean separately from the candidate.
 */
internal class JsonDomainDefinition<Strong, DomainFailure>(
    internal val schema: CompiledJsonSchema,
    private val definition: RefinementDefinition<ValidatedJsonValue, Strong, DomainFailure>,
) {
    internal fun admit(
        candidate: JsonElement,
    ): Validation<Strong, JsonDomainAdmissionFailure<DomainFailure>> =
        when (val schemaAdmission = schema.admit(candidate)) {
            is Validation.Validated -> definition.refine(schemaAdmission.value)
                .mapFailures(JsonDomainAdmissionFailure<DomainFailure>::Domain)

            is Validation.Rejected -> Validation.Rejected(
                NonEmptyFailures.from(
                    first = JsonDomainAdmissionFailure.Constraint(schemaAdmission.failures.first()),
                    remaining = schemaAdmission.failures.drop(1).map { violation ->
                        JsonDomainAdmissionFailure.Constraint(violation)
                    },
                ),
            )
        }
}
