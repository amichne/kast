package kast.baseline.program

/** JSON primitives are introduced only at this deterministic output boundary. */
fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        value.forEach { character -> when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (character.code < 32) append("\\u" + character.code.toString(16).padStart(4, '0')) else append(character)
        } }
        append('"')
    }
    is Number, is Boolean -> value.toString()
    is Enum<*> -> json(value.name)
    is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
    else -> error("unsupported projection value")
}

fun programProjection(): String = json(mapOf(
    "schemaVersion" to 1,
    "baseRevision" to BaselineProgram.BASE_REVISION,
    "slopsentralRevision" to BaselineProgram.SLOPSENTRAL_REVISION,
    "tasks" to BaselineProgram.graph().nodes.map { task -> mapOf(
        "id" to task.id, "title" to task.title, "goal" to task.goal,
        "dependencies" to task.dependencies.sortedBy { it.name }, "allowedReads" to task.allowedReads.sorted(),
        "allowedWrites" to task.allowedWrites.sorted(), "inputs" to task.inputs.sorted(), "outputs" to task.outputs.sorted(),
        "publicInterface" to task.publicInterface, "internalImplementation" to task.internalImplementation,
        "effect" to task.effect, "cost" to task.cost, "forbiddenWork" to task.forbiddenWork.sorted(),
        "redCommand" to task.check.command, "redExpectedFailure" to task.check.expectedFailure,
        "greenCommand" to task.check.command, "greenExpectedProof" to task.check.expectedProof,
        "reviewBoundary" to task.reviewBoundary, "completionReceipt" to task.completionReceipt,
        "action" to when (val action = task.action) {
            is GateAction.Check -> mapOf("kind" to "check", "suite" to action.suite.argument)
            GateAction.BoundaryCheck -> mapOf("kind" to "boundary-check")
            is GateAction.Unimplemented -> mapOf("kind" to "unimplemented", "adapter" to action.adapter.name)
        },
    ) },
    "waves" to BaselineProgram.graph().waves,
    "modules" to ExampleModule.entries.map { mapOf("path" to it.path, "dependencies" to it.dependencies.sorted(), "allowedEffects" to it.allowedEffects.sorted()) },
    "requirements" to BaselineProgram.requirements.map { mapOf("requirement" to it.requirement, "gates" to it.gates.sortedBy { gate -> gate.name }, "implementation" to it.implementation.sorted()) },
    "process" to BaselineProgram.process.map { edge -> when (edge) {
        is ProcessEdge.Next -> mapOf("kind" to "next", "from" to edge.from, "to" to edge.to)
        is ProcessEdge.Choice -> mapOf("kind" to "choice", "from" to edge.from, "lanes" to edge.lanes.sortedBy { it.name })
        is ProcessEdge.Recovery -> mapOf("kind" to "recovery", "from" to edge.from, "authorizedEntry" to edge.authorizedEntry)
    } },
    "retirement" to mapOf("gate" to BaselineProgram.retirement.gate, "internalOperations" to BaselineProgram.retirement.internalOperations.sorted()),
))

private fun objectSchema(properties: Map<String, Any>): Map<String, Any> = mapOf(
    "type" to "object", "properties" to properties, "required" to properties.keys.sorted(), "additionalProperties" to false)
private fun arraySchema(items: Any): Map<String, Any> = mapOf("type" to "array", "items" to items)
private fun names(values: List<String>): Map<String, Any> = mapOf("type" to "string", "enum" to values)
private val text = mapOf("type" to "string", "minLength" to 1)
private val texts = arraySchema(text)
private val gates = names(GateId.entries.map { it.name })
private val steps = names(Step.entries.map { it.name })
private val digest = mapOf("type" to "string", "pattern" to "^[0-9a-f]{64}$")
private val revision = mapOf("type" to "string", "pattern" to "^[0-9a-f]{40}$")
private fun schema(body: Map<String, Any>): String = json(body + ("\$schema" to "https://json-schema.org/draft/2020-12/schema"))

fun programSchema(): String {
    val properties = linkedMapOf<String, Any>(
        "id" to gates, "dependencies" to arraySchema(gates),
        "effect" to names(GateEffect.entries.map { it.name }), "cost" to names(GateCost.entries.map { it.name }),
        "action" to mapOf("oneOf" to listOf(
            objectSchema(mapOf("kind" to mapOf("const" to "check"), "suite" to names(Suite.entries.map { it.argument }))),
            objectSchema(mapOf("kind" to mapOf("const" to "boundary-check"))),
            objectSchema(mapOf("kind" to mapOf("const" to "unimplemented"), "adapter" to names(RequiredAdapter.entries.map { it.name }))),
        )),
    )
    listOf("title", "goal", "publicInterface", "internalImplementation", "redExpectedFailure", "greenExpectedProof",
        "reviewBoundary", "completionReceipt").forEach { properties[it] = text }
    listOf("allowedReads", "allowedWrites", "inputs", "outputs", "forbiddenWork", "redCommand", "greenCommand")
        .forEach { properties[it] = texts }
    return schema(objectSchema(mapOf(
        "schemaVersion" to mapOf("const" to 1), "baseRevision" to revision, "slopsentralRevision" to revision,
        "tasks" to arraySchema(objectSchema(properties)), "waves" to arraySchema(arraySchema(gates)),
        "modules" to arraySchema(objectSchema(mapOf("path" to text, "dependencies" to texts, "allowedEffects" to texts))),
        "requirements" to arraySchema(objectSchema(mapOf("requirement" to names(Requirement.entries.map { it.name }),
            "gates" to arraySchema(gates), "implementation" to texts))),
        "process" to arraySchema(mapOf("oneOf" to listOf(
            objectSchema(mapOf("kind" to mapOf("const" to "next"), "from" to steps, "to" to steps)),
            objectSchema(mapOf("kind" to mapOf("const" to "choice"), "from" to steps, "lanes" to arraySchema(steps))),
            objectSchema(mapOf("kind" to mapOf("const" to "recovery"), "from" to steps, "authorizedEntry" to steps)),
        ))),
        "retirement" to objectSchema(mapOf("gate" to gates, "internalOperations" to texts)),
    )))
}
fun receiptSchema(): String = schema(objectSchema(mapOf(
    "coordinates" to objectSchema(mapOf("program" to digest, "head" to revision, "base" to revision,
        "inputs" to digest, "command" to digest, "dependencies" to mapOf("type" to "object",
            "propertyNames" to gates, "additionalProperties" to digest))),
    "status" to mapOf("const" to "PASS"), "observations" to (texts + ("minItems" to 1)),
    "artifacts" to mapOf("type" to "object", "minProperties" to 1, "additionalProperties" to digest),
)))
