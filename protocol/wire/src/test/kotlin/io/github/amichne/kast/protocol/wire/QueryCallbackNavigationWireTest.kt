package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackNavigationAdmissionWireTest {
    @Test
    fun `rooted bound and direct routes survive complete observation conversion and reject witness mutations`() {
        val fixture = QueryCallbackRefinementWireFixture()
        for (observation in listOf(fixture.forwardedObservation(), fixture.directObservation())) {
            val flow = fixture.flow(observation)
            val body = QueryCallbackNavigationNodeWireDocument.Callable(flow.body)
            val target =
                QueryCallbackNavigationNodeWireDocument.Callable(
                    QueryCallbackBodyWireDocument.Named(observation.target)
                )
            val steps =
                rootedPrefix(observation, flow).let { prefix ->
                    prefix +
                        QueryCallbackNavigationStepWireDocument(
                            prefix.size + 1,
                            body,
                            target,
                            QueryCallbackNavigationEvidenceWireDocument.FunctionInvocation(
                                observation.occurrence,
                                observation.namedPolicy,
                            ),
                        )
                }
            val proven = QueryCallbackNavigationWireDocument.Proven(steps)
            assertObservationRoundTrips(fixture, observation, proven)
            assertObservationMutationsRejected(fixture, observation, steps, target)
        }
    }
}

private fun assertObservationRoundTrips(
    fixture: QueryCallbackRefinementWireFixture,
    observation: QueryCallbackObservationWireDocument,
    proven: QueryCallbackNavigationWireDocument.Proven,
) {
    for (navigation in
        listOf(
            proven,
            QueryCallbackNavigationWireDocument.Unproven(
                QueryCallbackGraphCauseDocument.IncompleteScan,
                proven.steps.take(1),
            ),
        )) {
        val wire = observation.copy(navigation = navigation)
        val admitted = fixture.decode(wire).toContract() as WireDocumentConversion.Converted
        assertEquals(
            wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire),
            wireJson.encodeToJsonElement(
                QueryCallbackObservationWireDocument.serializer(),
                admitted.value.toWireDocument(),
            ),
        )
    }
}

private fun assertObservationMutationsRejected(
    fixture: QueryCallbackRefinementWireFixture,
    observation: QueryCallbackObservationWireDocument,
    steps: List<QueryCallbackNavigationStepWireDocument>,
    target: QueryCallbackNavigationNodeWireDocument,
) {
    val flow = fixture.flow(observation)
    val proven = QueryCallbackNavigationWireDocument.Proven(steps)
    for (mutated in
        listOf(
            steps.dropLast(1),
            steps.mapIndexed { index, step -> if (index == 0) step.copy(depth = 2) else step },
            steps.mapIndexed { index, step -> if (index == 0) step.copy(target = target) else step },
        )) fixture.assertRejected(observation.copy(navigation = QueryCallbackNavigationWireDocument.Proven(mutated)))
    if (flow.binding is QueryCallbackBindingWireDocument.Bound) {
        val initial = steps.first().target as QueryCallbackNavigationNodeWireDocument.Formal
        val supplier = initial.supplier as QueryCallbackBindingWireDocument.Bound
        val wrong = initial.copy(supplier = supplier.copy(invocationOccurrence = fixture.occurrence("Other.kt", 2, 20)))
        fixture.assertRejected(
            observation.copy(
                navigation = proven.copy(steps = listOf(steps.first().copy(target = wrong)) + steps.drop(1))
            )
        )
        fixture.assertRejected(observation.copy(flow = flow.copy(invocations = emptyList()), navigation = proven))
    }
}

private fun rootedPrefix(
    observation: QueryCallbackObservationWireDocument,
    flow: QueryCallbackFlowWireDocument.Observed,
): List<QueryCallbackNavigationStepWireDocument> {
    val root =
        QueryCallbackNavigationNodeWireDocument.Callable(QueryCallbackBodyWireDocument.Named(observation.lexicalOwner))
    val body = QueryCallbackNavigationNodeWireDocument.Callable(flow.body)
    return when (val binding = flow.binding) {
        is QueryCallbackBindingWireDocument.Bound -> boundPrefix(flow, binding, root, body)
        is QueryCallbackBindingWireDocument.Direct ->
            listOf(
                QueryCallbackNavigationStepWireDocument(
                    1,
                    root,
                    body,
                    QueryCallbackNavigationEvidenceWireDocument.DirectInvoke(binding),
                )
            )
        else -> error("fixture must carry bound or direct flow")
    }
}

private fun boundPrefix(
    flow: QueryCallbackFlowWireDocument.Observed,
    binding: QueryCallbackBindingWireDocument.Bound,
    root: QueryCallbackNavigationNodeWireDocument,
    body: QueryCallbackNavigationNodeWireDocument,
): List<QueryCallbackNavigationStepWireDocument> {
    val initial =
        QueryCallbackNavigationNodeWireDocument.Formal(
            QueryCallbackParameterIdentityWireDocument(binding.callable, binding.position, binding.parameter),
            binding,
        )
    val forwarding = flow.invocations.single().forwardings.single()
    val nextBinding = forwarding.target as QueryCallbackBindingWireDocument.Bound
    val next =
        QueryCallbackNavigationNodeWireDocument.Formal(
            QueryCallbackParameterIdentityWireDocument(
                nextBinding.callable,
                nextBinding.position,
                nextBinding.parameter,
            ),
            binding,
        )
    return listOf(
        QueryCallbackNavigationStepWireDocument(
            1,
            root,
            initial,
            QueryCallbackNavigationEvidenceWireDocument.Supply(binding, flow.body),
        ),
        QueryCallbackNavigationStepWireDocument(
            2,
            initial,
            next,
            QueryCallbackNavigationEvidenceWireDocument.Forward(forwarding),
        ),
        QueryCallbackNavigationStepWireDocument(
            3,
            next,
            body,
            QueryCallbackNavigationEvidenceWireDocument.Invoke(flow.invocations.single()),
        ),
    )
}

private fun assertNavigationShape(shape: JsonObject, proven: Boolean, evidenceKeys: List<Set<String>>) {
    val names = listOf("SUPPLY", "FORWARD", "INVOKE", "DIRECT_INVOKE", "FUNCTION_INVOCATION")
    assertEquals(if (proven) setOf("type", "steps") else setOf("type", "cause", "prefix"), shape.keys)
    assertEquals(if (proven) "PROVEN" else "UNPROVEN", shape.getValue("type").jsonPrimitive.content)
    if (!proven) {
        val cause = shape.getValue("cause").jsonObject
        assertEquals(setOf("type"), cause.keys)
        assertEquals("INCOMPLETE_SCAN", cause.getValue("type").jsonPrimitive.content)
    }
    val encodedSteps = shape.getValue(if (proven) "steps" else "prefix").jsonArray
    encodedSteps.forEachIndexed { index, element ->
        val step = element.jsonObject
        assertEquals(setOf("depth", "source", "target", "evidence"), step.keys)
        assertEquals((index + 1).toString(), step.getValue("depth").jsonPrimitive.content)
        val callable = step.getValue("source").jsonObject
        assertEquals(setOf("type", "body"), callable.keys)
        assertEquals("CALLABLE", callable.getValue("type").jsonPrimitive.content)
        val formal = step.getValue("target").jsonObject
        assertEquals(setOf("type", "parameter", "supplier"), formal.keys)
        assertEquals("FORMAL", formal.getValue("type").jsonPrimitive.content)
        val evidence = step.getValue("evidence").jsonObject
        assertEquals(evidenceKeys[index], evidence.keys)
        assertEquals(names[index], evidence.getValue("type").jsonPrimitive.content)
        if (index == 4) {
            val policy = evidence.getValue("policy").jsonObject
            assertEquals(setOf("type", "reason", "excluded_boundary"), policy.keys)
            assertEquals("EXCLUDED", policy.getValue("type").jsonPrimitive.content)
            assertEquals("NON_INLINE_ARGUMENT", policy.getValue("reason").jsonPrimitive.content)
        }
    }
}

class QueryCallbackNavigationWireTest {
    @Test
    fun `all navigation evidence retains explicit shape and survives isolated wire conversion`() {
        val fixture = QueryCallbackRefinementWireFixture()
        val observation = fixture.forwardedObservation()
        val flow = fixture.flow(observation)
        val binding = flow.binding as QueryCallbackBindingWireDocument.Bound
        val parameter =
            QueryCallbackParameterIdentityWireDocument(binding.callable, binding.position, binding.parameter)
        val source = QueryCallbackNavigationNodeWireDocument.Callable(flow.body)
        val target = QueryCallbackNavigationNodeWireDocument.Formal(parameter, binding)
        val direct =
            QueryCallbackBindingWireDocument.Direct(flow.basis, binding.invocationOccurrence, binding.invocationOwner)
        val cases =
            listOf(
                QueryCallbackNavigationEvidenceWireDocument.Supply(binding, flow.body) to
                    setOf("type", "binding", "body"),
                QueryCallbackNavigationEvidenceWireDocument.Forward(flow.invocations.single().forwardings.single()) to
                    setOf("type", "forwarding"),
                QueryCallbackNavigationEvidenceWireDocument.Invoke(flow.invocations.single()) to
                    setOf("type", "invocation"),
                QueryCallbackNavigationEvidenceWireDocument.DirectInvoke(direct) to setOf("type", "binding"),
                QueryCallbackNavigationEvidenceWireDocument.FunctionInvocation(
                    observation.occurrence,
                    observation.namedPolicy,
                ) to setOf("type", "occurrence", "policy"),
            )
        val steps = cases.mapIndexed { index, (evidence, _) ->
            QueryCallbackNavigationStepWireDocument(index + 1, source, target, evidence)
        }
        for (navigation in
            listOf(
                QueryCallbackNavigationWireDocument.Proven(steps),
                QueryCallbackNavigationWireDocument.Unproven(QueryCallbackGraphCauseDocument.IncompleteScan, steps),
            )) {
            val wire = observation.copy(navigation = navigation)
            val encoded = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire)
            val shape = encoded.jsonObject.getValue("navigation").jsonObject
            val proven = navigation is QueryCallbackNavigationWireDocument.Proven
            assertNavigationShape(shape, proven, cases.map { it.second })
            val admitted = fixture.decode(wire).navigation.toContract() as WireDocumentConversion.Converted
            assertEquals(navigation, admitted.value.navigationWire())
        }
    }

    @Test
    fun `same formal at distinct suppliers and named source identity are preserved`() {
        val fixture = QueryCallbackRefinementWireFixture()
        val observation = fixture.base()
        val flow = fixture.flow(observation)
        val binding = flow.binding as QueryCallbackBindingWireDocument.Bound
        val parameter =
            QueryCallbackParameterIdentityWireDocument(binding.callable, binding.position, binding.parameter)
        val source = QueryCallbackNavigationNodeWireDocument.Callable(binding.invocationOwner)
        val other = binding.copy(invocationOccurrence = fixture.occurrence("Other.kt", 2, 20))
        val steps =
            listOf(binding, other).mapIndexed { index, supplier ->
                QueryCallbackNavigationStepWireDocument(
                    index + 1,
                    source,
                    QueryCallbackNavigationNodeWireDocument.Formal(parameter, supplier),
                    QueryCallbackNavigationEvidenceWireDocument.Supply(supplier, flow.body),
                )
            }
        val navigation = QueryCallbackNavigationWireDocument.Proven(steps)
        val restored =
            fixture.decode(observation.copy(navigation = navigation)).navigation.toContract()
                as WireDocumentConversion.Converted
        assertEquals(navigation, restored.value.navigationWire())
        val retained = (restored.value.navigationWire() as QueryCallbackNavigationWireDocument.Proven).steps
        assertEquals(source, retained[0].source)
        assertEquals(binding, (retained[0].target as QueryCallbackNavigationNodeWireDocument.Formal).supplier)
        assertEquals(other, (retained[1].target as QueryCallbackNavigationNodeWireDocument.Formal).supplier)
    }

    @Test
    fun `default navigation is explicitly not computed and missing legacy field never proves navigation`() {
        val fixture = QueryCallbackRefinementWireFixture()
        val wire = fixture.base()
        val encoded = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val navigation = encoded.getValue("navigation").jsonObject
        assertEquals(setOf("type"), navigation.keys)
        assertEquals("NOT_COMPUTED", navigation.getValue("type").jsonPrimitive.content)
        val legacy =
            wireJson
                .encodeToString(QueryCallbackObservationWireDocument.serializer(), wire)
                .replace(",\"navigation\":{\"type\":\"NOT_COMPUTED\"}", "")
        val decoded = wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), legacy)
        assertEquals(QueryCallbackNavigationWireDocument.NotComputed, decoded.navigation)
        val admitted = decoded.toContract() as WireDocumentConversion.Converted
        assertEquals(QueryCallbackNavigationWireDocument.NotComputed, admitted.value.toWireDocument().navigation)
    }

    @Test
    fun `navigation rejects invalid depths wrong binding kinds and excess retained steps`() {
        val fixture = QueryCallbackRefinementWireFixture()
        val observation = fixture.base()
        val flow = fixture.flow(observation)
        val bound = flow.binding as QueryCallbackBindingWireDocument.Bound
        val parameter = QueryCallbackParameterIdentityWireDocument(bound.callable, bound.position, bound.parameter)
        val source = QueryCallbackNavigationNodeWireDocument.Callable(flow.body)
        val target = QueryCallbackNavigationNodeWireDocument.Formal(parameter, bound)
        val direct =
            QueryCallbackBindingWireDocument.Direct(flow.basis, bound.invocationOccurrence, bound.invocationOwner)
        val step =
            QueryCallbackNavigationStepWireDocument(
                1,
                source,
                target,
                QueryCallbackNavigationEvidenceWireDocument.Supply(bound, flow.body),
            )
        for (invalid in
            listOf(
                step.copy(depth = 0),
                step.copy(depth = -1),
                step.copy(target = target.copy(supplier = direct)),
                step.copy(evidence = QueryCallbackNavigationEvidenceWireDocument.Supply(direct, flow.body)),
                step.copy(evidence = QueryCallbackNavigationEvidenceWireDocument.DirectInvoke(bound)),
            )) assertEquals(WireDocumentConversion.Rejected, invalid.toContract())
        for (size in listOf(1000, 1001)) {
            val steps = List(size) { step }
            for (navigation in
                listOf(
                    QueryCallbackNavigationWireDocument.Proven(steps),
                    QueryCallbackNavigationWireDocument.Unproven(QueryCallbackGraphCauseDocument.IncompleteScan, steps),
                )) {
                val result = fixture.decode(observation.copy(navigation = navigation)).navigation.toContract()
                if (size == 1000) assertTrue(result is WireDocumentConversion.Converted)
                else assertEquals(WireDocumentConversion.Rejected, result)
            }
        }
    }

    @Test
    fun `unknown and incomplete navigation alternatives fail closed at decode`() {
        val malformed =
            checkNotNull(javaClass.getResource("/query/callback-navigation-malformed.jsonl"))
                .readText()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
        assertEquals(12, malformed.size)
        for (raw in malformed) {
            assertThrows(SerializationException::class.java) {
                wireJson.decodeFromString(QueryCallbackNavigationWireDocument.serializer(), raw)
            }
            assertThrows(SerializationException::class.java) {
                wireJson.decodeFromString(QueryCallbackNavigationNodeWireDocument.serializer(), raw)
            }
            assertThrows(SerializationException::class.java) {
                wireJson.decodeFromString(QueryCallbackNavigationEvidenceWireDocument.serializer(), raw)
            }
        }
    }
}
