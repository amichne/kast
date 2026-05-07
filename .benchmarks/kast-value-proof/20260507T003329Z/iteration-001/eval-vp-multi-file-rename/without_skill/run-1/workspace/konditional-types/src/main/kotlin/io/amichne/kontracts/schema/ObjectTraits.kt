package io.amichne.kontracts.schema

interface ObjectTraits {
    val fields: Map<String, FieldSchema>
    val required: Set<String>?
}
