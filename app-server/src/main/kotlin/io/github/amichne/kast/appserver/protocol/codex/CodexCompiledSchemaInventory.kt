package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.schema.CompiledJsonSchema
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.NonEmptyFailures
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.json.JsonObject

/** Compiled schemas prove every required shape and either both legacy rollback shapes or neither. */
internal class CodexCompiledSchemaInventory
private constructor(val schemas: Map<CodexOwnedSchema, CompiledJsonSchema>) {
    companion object {
        fun compile(
            documents: Map<CodexOwnedSchema, JsonObject>
        ): Validation<CodexCompiledSchemaInventory, CodexProtocolContractFailure> {
            val failures = mutableListOf<CodexProtocolContractFailure>()
            val schemas = linkedMapOf<CodexOwnedSchema, CompiledJsonSchema>()
            val presentRollback = documents.keys.intersect(legacyRollbackSchemas)
            if (presentRollback.size == 1) {
                failures += CodexProtocolContractFailure.Missing((legacyRollbackSchemas - presentRollback).single())
            }
            CodexOwnedSchema.entries.forEach { schema ->
                val document = documents[schema]
                if (document == null) {
                    if (schema !in legacyRollbackSchemas) failures += CodexProtocolContractFailure.Missing(schema)
                } else {
                    when (val compilation = NetworkntJsonSchemaCompiler.compile(document)) {
                        is Refinement.Refined -> schemas[schema] = compilation.value
                        is Refinement.Rejected -> failures += CodexProtocolContractFailure.Invalid(schema)
                    }
                }
            }
            return if (failures.isEmpty()) {
                Validation.Validated(CodexCompiledSchemaInventory(schemas.toMap()))
            } else {
                Validation.Rejected(NonEmptyFailures.from(failures.first(), failures.drop(1)))
            }
        }
    }
}
