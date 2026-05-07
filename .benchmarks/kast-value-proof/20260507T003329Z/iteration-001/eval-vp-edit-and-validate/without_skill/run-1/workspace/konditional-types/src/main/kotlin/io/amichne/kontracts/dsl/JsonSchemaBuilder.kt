package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.JsonSchema

sealed interface JsonSchemaBuilder<out T : Any> {
    fun build(): JsonSchema<T>
}
